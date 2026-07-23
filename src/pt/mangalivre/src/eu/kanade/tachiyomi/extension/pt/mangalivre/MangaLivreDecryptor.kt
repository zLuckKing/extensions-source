package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MangaLivreDecryptor v3 - arquitetura "navigate + harvest".
 *
 * Mudança de fundo em relação às versões anteriores: a extensão NÃO tenta
 * mais isolar/replayar uma função de decrypt com um ciphertext obtido via
 * OkHttp. Em vez disso, o WebView navega para a URL real do capítulo (com
 * os cookies/sessão já do próprio WebView) e deixa o site executar seu
 * pipeline de descriptografia inteiro, do jeito dele. A extensão só
 * observa o resultado final.
 *
 * Isso resolve, na raiz:
 * - ponto 2/3 do DeepSeek (this/args perdidos ao "replayar" uma função
 *   fora de contexto) - não existe mais replay, então não existe mais
 *   esse problema.
 * - ponto 4 do DeepSeek (capturar o resultado final do pipeline).
 * - ponto 1 (sem monkey-patch de Function.prototype.apply/call rodando
 *   em cima de toda chamada de função da página).
 * - ponto 6, de verdade: usa WebViewCompat.addDocumentStartJavaScript,
 *   que garante injeção antes de qualquer script da página rodar (quando
 *   suportado - ver installEarlyHook()).
 * - bônus: elimina o problema de sincronização de cookies entre WebView
 *   e OkHttp, porque quem faz a requisição da página é o próprio WebView.
 *
 * Onde ainda existe uma decisão de design (sendo honesto, não dá pra fugir
 * 100% disso): pra saber QUAL objeto observado é "a lista de páginas", uso
 * um critério sobre o formato do resultado final (parece um array de
 * URLs de imagem, ou um objeto com uma lista assim). Isso não é heurística
 * sobre como a criptografia funciona (ponto 5 do DeepSeek, que eu concordo
 * que é frágil) - é heurística sobre o formato do dado de saída que a
 * extensão precisa de qualquer forma para funcionar. Como camada extra de
 * segurança, tem um fallback totalmente agnóstico: se nada for capturado
 * via JSON.parse, faz scraping direto do DOM renderizado (<img> na área
 * do leitor), que não depende de nenhum detalhe interno do site.
 */
class MangaLivreDecryptor(
    private val headers: Headers,
    private val debug: Boolean = false,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val webViewRef = AtomicReference<WebView?>()

    // Só um carregamento de capítulo por vez. O Tachiyomi/Mihon costuma
    // pré-carregar páginas de vários capítulos em paralelo (prefetch); sem
    // essa trava, duas chamadas concorrentes brigam pelo mesmo WebView e
    // uma pode destruir o WebView que a outra está usando no meio da carga.
    private val loadMutex = Object()

    // ------------------------------------------------------------------
    // API pública: dado a URL real do capítulo, devolve as URLs das páginas
    // ------------------------------------------------------------------

    fun fetchChapterPages(chapterUrl: String): List<String>? = synchronized(loadMutex) {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<List<String>?>(null)

        handler.post {
            loadAndHarvest(chapterUrl) { pages ->
                resultRef.set(pages)
                latch.countDown()
            }
        }

        return try {
            if (latch.await(30, TimeUnit.SECONDS)) {
                resultRef.get()
            } else {
                log("Timeout aguardando captura das páginas de $chapterUrl")
                null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    // ------------------------------------------------------------------
    // WebView: criação, injeção antecipada, harvest
    // ------------------------------------------------------------------

    private fun loadAndHarvest(chapterUrl: String, onResult: (List<String>?) -> Unit) {
        try {
            destroyWebViewInternal()

            val view = WebView(applicationContext)
            webViewRef.set(view)

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                if (!headers["User-Agent"].isNullOrBlank()) {
                    userAgentString = headers["User-Agent"]
                }
            }

            view.addJavascriptInterface(HarvestBridge(onResult), "__ml_bridge")

            // Ponto 6 resolvido de verdade: injeta ANTES de qualquer script
            // da página rodar, se o dispositivo suportar a feature.
            val earlyInjectionOk = installEarlyHook(view)
            if (!earlyInjectionOk) {
                log(
                    "AVISO: dispositivo sem suporte a DOCUMENT_START_SCRIPT - " +
                        "hook será injetado em onPageStarted (menos garantido)",
                )
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!earlyInjectionOk) {
                        // fallback best-effort para dispositivos sem a feature nova
                        v.evaluateJavascript(HARVEST_SCRIPT, null)
                    }
                }

                override fun onPageFinished(v: WebView, url: String) {
                    // Dá um tempo pro app renderizar (SPA/hydration) antes
                    // de disparar o fallback de scraping do DOM.
                    handler.postDelayed({
                        v.evaluateJavascript(DOM_FALLBACK_SCRIPT, null)
                    }, DOM_FALLBACK_DELAY_MS)
                }

                override fun onReceivedError(
                    v: WebView,
                    errorCode: Int,
                    description: String,
                    failingUrl: String,
                ) {
                    log("Erro ao carregar $failingUrl: $description")
                }
            }

            view.loadUrl(chapterUrl)
        } catch (e: Exception) {
            log("Falha ao carregar/harvestar: ${e.message}")
            onResult(null)
        }
    }

    /**
     * Tenta instalar o hook usando a API correta de injeção antecipada.
     * Retorna true se conseguiu registrar via essa API (garantido rodar
     * antes do JS da página).
     */
    private fun installEarlyHook(view: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return false
        }
        return try {
            WebViewCompat.addDocumentStartJavaScript(view, HARVEST_SCRIPT, setOf("*"))
            true
        } catch (e: Exception) {
            log("addDocumentStartJavaScript falhou: ${e.message}")
            false
        }
    }

    private fun destroyWebViewInternal() {
        webViewRef.getAndSet(null)?.let { old ->
            try {
                old.stopLoading()
                old.destroy()
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // Ponte Kotlin <-> JS
    // ------------------------------------------------------------------

    private inner class HarvestBridge(private val onResult: (List<String>?) -> Unit) {
        private val delivered = AtomicReference(false)

        @android.webkit.JavascriptInterface
        fun onPagesFound(jsonArrayOrObject: String, source: String) {
            if (!delivered.compareAndSet(false, true)) return // só a primeira entrega conta
            val pages = parsePagesPayload(jsonArrayOrObject)
            log("Páginas capturadas via '$source': ${pages?.size ?: 0}")
            handler.post { onResult(pages) }
        }

        @android.webkit.JavascriptInterface
        fun onLog(msg: String) {
            log("[JS] $msg")
        }
    }

    private fun parsePagesPayload(raw: String): List<String>? {
        return try {
            when {
                raw.trimStart().startsWith("[") -> {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).map { arr.getString(it) }
                }
                raw.trimStart().startsWith("{") -> {
                    val obj = JSONObject(raw)
                    val key = listOf("pages", "images", "urls").firstOrNull { obj.has(it) }
                        ?: return null
                    val arr = obj.getJSONArray(key)
                    (0 until arr.length()).map { arr.getString(it) }
                }
                else -> null
            }
        } catch (e: Exception) {
            log("Erro ao parsear payload de páginas: ${e.message}")
            null
        }
    }

    private fun log(msg: String) {
        if (debug) android.util.Log.d("MangaLivreDecryptor", msg)
    }

    companion object {
        private const val DOM_FALLBACK_DELAY_MS = 3500L

        /**
         * Hook "primário": observa window.JSON.parse. Não sabe nada sobre
         * o algoritmo de criptografia - só olha o RESULTADO já
         * descriptografado que o app está processando, e verifica se o
         * formato bate com o que a extensão precisa (lista de URLs de
         * imagem). Isso é heurística sobre o dado de saída, não sobre a
         * implementação da criptografia (diferença importante em relação
         * à v1/v2).
         */
        private val HARVEST_SCRIPT = """
            (function() {
                if (window.__ml_harvestInstalled) return;
                window.__ml_harvestInstalled = true;

                function looksLikePageList(val) {
                    if (Array.isArray(val)) {
                        return val.length > 0 && val.every(function(v) {
                            return typeof v === 'string' && /\.(jpg|jpeg|png|webp|avif)(\?|${'$'})/i.test(v);
                        });
                    }
                    if (val && typeof val === 'object') {
                        var keys = ['pages', 'images', 'urls'];
                        for (var i = 0; i < keys.length; i++) {
                            if (Array.isArray(val[keys[i]]) && looksLikePageList(val[keys[i]])) {
                                return true;
                            }
                        }
                    }
                    return false;
                }

                function deliver(rawText) {
                    try {
                        if (window.__ml_bridge && window.__ml_bridge.onPagesFound) {
                            window.__ml_bridge.onPagesFound(rawText, 'json.parse');
                        }
                    } catch (e) {}
                }

                var origParse = JSON.parse;
                JSON.parse = function(text) {
                    var result = origParse.apply(this, arguments);
                    try {
                        if (looksLikePageList(result)) {
                            deliver(typeof text === 'string' ? text : JSON.stringify(result));
                        }
                    } catch (e) {}
                    return result;
                };
            })();
        """.trimIndent()

        /**
         * Fallback totalmente agnóstico: se nada foi capturado via
         * JSON.parse (site pode montar o array sem passar por JSON.parse,
         * por exemplo já vindo de um WebSocket ou de streaming), lê
         * diretamente as tags <img> renderizadas na tela depois que o app
         * termina de montar o leitor. Isso não depende de absolutamente
         * nada sobre como o site descriptografa - é scraping do resultado
         * visual final, o nível mais alto de agnosticismo possível.
         */
        private val DOM_FALLBACK_SCRIPT = """
            (function() {
                try {
                    if (window.__ml_delivered) return;

                    var imgs = Array.prototype.slice.call(document.querySelectorAll('img'))
                        .map(function(img) { return img.currentSrc || img.src; })
                        .filter(function(src) {
                            return src && /\.(jpg|jpeg|png|webp|avif)(\?|${'$'})/i.test(src);
                        });

                    if (imgs.length > 0 && window.__ml_bridge && window.__ml_bridge.onPagesFound) {
                        window.__ml_delivered = true;
                        window.__ml_bridge.onPagesFound(JSON.stringify(imgs), 'dom-fallback');
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
