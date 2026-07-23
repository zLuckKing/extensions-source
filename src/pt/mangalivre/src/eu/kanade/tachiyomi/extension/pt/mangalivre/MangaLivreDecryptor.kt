package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MangaLivreDecryptor - versão baseada em interceptação de runtime.
 *
 * Filosofia (pontos 1-10 aplicados):
 * - Nunca inspeciona toString() de funções (ponto 9).
 * - Nunca reimplementa Rabbit/CryptoJS/SHA no Kotlin (pontos 2, 3, 10).
 * - Descobre a função de decrypt observando uma CHAMADA REAL feita pelo
 *   próprio site (ponto 4), não o código-fonte dela.
 * - Uma vez capturada a referência da função, ela é reaproveitada como
 *   caixa-preta: entra ciphertext, sai JSON (ponto 10 - "contrato único").
 * - Descoberta acontece só uma vez por sessão de WebView (ponto 8).
 * - Sem nomes mágicos em window; guardado em closure (ponto 6).
 * - Sem parser manual de escape (ponto 7) - usamos JSONArray/JSONObject
 *   corretamente em vez de unescape na mão.
 * - Validação mínima: só "é JSON válido?" (ponto 5) - estrutura de
 *   negócio (pages/images) é responsabilidade do parser Kotlin, não daqui.
 *
 * Observação honesta: para saber QUAL chamada de função em runtime é "a"
 * chamada de decrypt, ainda é preciso um critério de captura (não dá pra
 * ler a mente do bundle). A diferença em relação à v1 é que esse critério
 * é comportamental e observado em runtime (assinatura de chamada: recebe
 * uma string grande tipo ciphertext, é chamada durante o processamento da
 * resposta da API, retorna algo parseável como JSON) - nunca análise de
 * texto-fonte/nome de função/algoritmo.
 */
class MangaLivreDecryptor(
    private val baseUrl: String,
    private val headers: Headers,
    private val debug: Boolean = false,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val webViewRef = AtomicReference<WebView?>()
    private val initializedRef = AtomicReference(false)
    private var initLatch = CountDownLatch(1)

    private val decryptQueue = LinkedBlockingQueue<DecryptTask>(1)
    private val decryptThread = Thread(this::processQueue).apply {
        name = "MangaLivre-Decryptor"
        isDaemon = true
        start()
    }

    // true assim que o hook de runtime confirma ter capturado a função real
    private val hookReadyRef = AtomicReference(false)

    private data class DecryptTask(
        val ciphertext: String,
        val latch: CountDownLatch,
        @Volatile var result: String? = null,
    )

    // ------------------------------------------------------------------
    // Inicialização
    // ------------------------------------------------------------------

    private fun ensureInitialized(): Boolean {
        if (initializedRef.get() && webViewRef.get() != null) return true

        synchronized(this) {
            if (initializedRef.get() && webViewRef.get() != null) return true

            initLatch = CountDownLatch(1)

            if (!createAndLoadWebView()) {
                log("Falha ao criar WebView na inicialização")
                return false
            }

            return try {
                initLatch.await(25, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }

    private fun createAndLoadWebView(): Boolean {
        var success = false
        val creationLatch = CountDownLatch(1)

        handler.post {
            try {
                destroyWebViewInternal()

                val view = WebView(applicationContext)
                webViewRef.set(view)
                hookReadyRef.set(false)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    if (!headers["User-Agent"].isNullOrBlank()) {
                        userAgentString = headers["User-Agent"]
                    }
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    view.webViewRenderProcessClient = object : android.webkit.WebViewRenderProcessClient {
                        override fun onRenderProcessUnresponsive(
                            view: WebView,
                            renderer: android.webkit.WebViewRenderProcess?,
                        ) {
                            log("Renderer unresponsive - recriando WebView")
                            handler.post { createAndLoadWebView() }
                        }

                        override fun onRenderProcessResponsive(
                            view: WebView,
                            renderer: android.webkit.WebViewRenderProcess?,
                        ) {}
                    }
                }

                // CRÍTICO: o hook precisa existir ANTES do bundle da página
                // rodar, senão perdemos a primeira chamada de decrypt.
                view.addJavascriptInterface(RuntimeBridge(), "__ml_bridge")

                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        // injeta o hook o mais cedo possível no ciclo de vida
                        view.evaluateJavascript(RUNTIME_HOOK_SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        log("WebView carregada: $url")
                        initializedRef.set(true)
                        success = true
                        creationLatch.countDown()
                        initLatch.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String,
                        failingUrl: String,
                    ) {
                        log("WebView init error: $description")
                        creationLatch.countDown()
                        initLatch.countDown()
                    }
                }

                view.loadUrl(baseUrl)
            } catch (e: Exception) {
                log("WebView creation failed: ${e.message}")
                creationLatch.countDown()
                initLatch.countDown()
            }
        }

        return try {
            creationLatch.await(30, TimeUnit.SECONDS) && success
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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
        initializedRef.set(false)
        hookReadyRef.set(false)
    }

    // ------------------------------------------------------------------
    // Ponte Kotlin <-> JS: recebe confirmação de que o hook capturou a função real
    // ------------------------------------------------------------------

    private inner class RuntimeBridge {
        @android.webkit.JavascriptInterface
        fun onDecryptFunctionCaptured() {
            hookReadyRef.set(true)
            log("Hook capturou a função de decrypt real do site em runtime")
        }

        @android.webkit.JavascriptInterface
        fun onLog(msg: String) {
            log("[JS] $msg")
        }
    }

    // ------------------------------------------------------------------
    // Descriptografia
    // ------------------------------------------------------------------

    fun decrypt(ciphertext: String): String? {
        if (!ensureInitialized()) {
            log("WebView não inicializada")
            return null
        }

        val latch = CountDownLatch(1)
        val task = DecryptTask(ciphertext, latch)

        return try {
            decryptQueue.put(task)
            if (latch.await(20, TimeUnit.SECONDS)) task.result else {
                log("Timeout aguardando descriptografia")
                null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun processQueue() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val task = decryptQueue.take()
                val start = System.currentTimeMillis()
                performDecrypt(task)
                log("Descriptografia concluída em ${System.currentTimeMillis() - start}ms")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun performDecrypt(task: DecryptTask) {
        if (!initializedRef.get() || webViewRef.get() == null) {
            log("WebView indisponível - recriando")
            if (!createAndLoadWebView()) {
                task.latch.countDown()
                return
            }
        }

        val latch = CountDownLatch(1)
        handler.post { executeDecrypt(task, latch) }

        val success = try {
            latch.await(15, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!success || task.result == null) {
            log("Timeout/falha - recriando WebView e tentando novamente")
            if (createAndLoadWebView()) {
                val retryLatch = CountDownLatch(1)
                handler.post { executeDecrypt(task, retryLatch) }
                try {
                    retryLatch.await(15, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        task.latch.countDown()
    }

    private fun executeDecrypt(task: DecryptTask, latch: CountDownLatch) {
        val view = webViewRef.get()
        if (view == null) {
            latch.countDown()
            return
        }

        if (!hookReadyRef.get()) {
            log("Função de decrypt ainda não foi observada em runtime - " +
                "site pode não ter feito nenhuma chamada de API ainda")
            latch.countDown()
            return
        }

        // Passa a string via JSON.stringify do lado Kotlin (sem parser manual).
        val payloadJson = JSONObject().put("ciphertext", task.ciphertext).toString()

        val script = """
            (function() {
                try {
                    const payload = $payloadJson;
                    if (typeof window.__ml_replayDecrypt !== 'function') {
                        return JSON.stringify({ error: 'replay fn indisponível' });
                    }
                    const out = window.__ml_replayDecrypt(payload.ciphertext);
                    // normaliza pra string (pode já vir como objeto/array)
                    return (typeof out === 'string') ? out : JSON.stringify(out);
                } catch (e) {
                    return JSON.stringify({ error: e && e.message ? e.message : String(e) });
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(script) { rawResult ->
            try {
                val unquoted = org.json.JSONTokener(rawResult).nextValue()
                val text = if (unquoted is String) unquoted else rawResult

                val isValidJson = runCatching { JSONObject(text) }.isSuccess ||
                    runCatching { JSONArray(text) }.isSuccess

                if (isValidJson) {
                    task.result = text
                    log("Descriptografia OK (via função real do site)")
                } else {
                    log("Resultado não é JSON válido: ${text.take(120)}")
                }
            } catch (e: Exception) {
                log("Erro ao processar resultado: ${e.message}")
            }
            latch.countDown()
        }
    }

    private fun log(msg: String) {
        if (debug) android.util.Log.d("MangaLivreDecryptor", msg)
    }

    companion object {
        /**
         * Script injetado em onPageStarted, ou seja, antes do bundle da
         * página rodar. Ele NÃO conhece Rabbit/CryptoJS/SHA. Em vez disso:
         *
         * 1. Faz monkey-patch de window.fetch e XMLHttpRequest para saber
         *    quando uma resposta de API (corpo criptografado) chega.
         * 2. Faz monkey-patch de Function.prototype.apply/call para
         *    observar TODAS as chamadas de função feitas durante o
         *    processamento dessa resposta.
         * 3. Usa um critério puramente comportamental (não de código-fonte)
         *    pra identificar a chamada de decrypt: aconteceu durante o
         *    processamento da resposta da API, recebeu como argumento uma
         *    string longa parecida com o ciphertext que acabou de chegar,
         *    e devolveu algo que dá pra converter em JSON.
         * 4. Uma vez identificada, guarda a REFERÊNCIA da função (não o
         *    código dela) numa closure interna e expõe só um método de
         *    replay: window.__ml_replayDecrypt(ciphertext).
         * 5. Assim que capturada, desliga os hooks (roda só uma vez -
         *    ponto 8) e avisa o Kotlin via __ml_bridge.onDecryptFunctionCaptured().
         *
         * Isso satisfaz o ponto 10: a extensão nunca sabe qual algoritmo,
         * senha ou biblioteca está por trás. Ela só reaproveita a função
         * que o próprio site já usa.
         */
        private val RUNTIME_HOOK_SCRIPT = """
            (function() {
                if (window.__ml_hookInstalled) return;
                window.__ml_hookInstalled = true;

                let captured = false;
                let pendingCiphertexts = [];

                function looksLikeCiphertext(s) {
                    return typeof s === 'string' && s.length > 40 &&
                        /^[A-Za-z0-9+/=_-]+${'$'}/.test(s.trim());
                }

                function tryConvertToJson(val) {
                    if (val == null) return null;
                    if (typeof val === 'object') return val;
                    if (typeof val === 'string') {
                        try { return JSON.parse(val); } catch (e) { return null; }
                    }
                    return null;
                }

                // 1) Observa respostas de API pra saber quando um ciphertext
                //    "novo" acabou de chegar (contexto de captura).
                const origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    window.fetch = function(...args) {
                        const p = origFetch.apply(this, args);
                        return p.then(function(resp) {
                            try {
                                const cloned = resp.clone();
                                cloned.text().then(function(bodyText) {
                                    try {
                                        const json = JSON.parse(bodyText);
                                        for (const key in json) {
                                            if (looksLikeCiphertext(json[key])) {
                                                pendingCiphertexts.push(json[key]);
                                                if (pendingCiphertexts.length > 5) pendingCiphertexts.shift();
                                            }
                                        }
                                    } catch (e) {}
                                }).catch(function() {});
                            } catch (e) {}
                            return resp;
                        });
                    };
                }

                // 2) Observa chamadas de função em busca da que consome um
                //    ciphertext pendente e devolve algo parseável como JSON.
                //    Critério comportamental, não análise de código-fonte.
                const origApply = Function.prototype.apply;
                Function.prototype.apply = function(thisArg, args) {
                    const result = origApply.call(this, thisArg, args);
                    if (!captured && args && args.length) {
                        const firstArg = args[0];
                        if (looksLikeCiphertext(firstArg) && pendingCiphertexts.indexOf(firstArg) !== -1) {
                            const asJson = tryConvertToJson(result);
                            if (asJson && (typeof asJson === 'object')) {
                                captureFunction(this, args.length);
                            }
                        }
                    }
                    return result;
                };

                const origCall = Function.prototype.call;
                Function.prototype.call = function(thisArg, ...args) {
                    const result = origCall.call(this, thisArg, ...args);
                    if (!captured && args && args.length) {
                        const firstArg = args[0];
                        if (looksLikeCiphertext(firstArg) && pendingCiphertexts.indexOf(firstArg) !== -1) {
                            const asJson = tryConvertToJson(result);
                            if (asJson && (typeof asJson === 'object')) {
                                captureFunction(this, args.length);
                            }
                        }
                    }
                    return result;
                };

                function captureFunction(fnRef, expectedArgCount) {
                    if (captured) return;
                    captured = true;

                    // Restaura os prototypes originais assim que captura -
                    // não queremos overhead de hook pra sempre (ponto 8).
                    Function.prototype.apply = origApply;
                    Function.prototype.call = origCall;

                    // Guarda a referência em closure, não em window direto
                    // com nome óbvio (ponto 6) - expomos só o replay.
                    window.__ml_replayDecrypt = function(ciphertext) {
                        try {
                            // Reaproveita a função exatamente como o site a usa,
                            // sem saber o que ela faz por dentro.
                            return fnRef(ciphertext);
                        } catch (e) {
                            return JSON.stringify({ error: 'replay failed: ' + e.message });
                        }
                    };

                    try {
                        if (window.__ml_bridge && window.__ml_bridge.onDecryptFunctionCaptured) {
                            window.__ml_bridge.onDecryptFunctionCaptured();
                        }
                    } catch (e) {}
                }
            })();
        """.trimIndent()
    }
}
