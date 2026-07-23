package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReadingGateInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
    private val cookieClient: OkHttpClient,
) : Interceptor {

    private val baseUrlHost = baseUrl.toHttpUrl().host

    @Volatile
    private var lastPrimeAttemptAt = 0L

    // Tokens cacheados extraídos via WebView
    @Volatile
    private var authToken: String = "v9_auth_k8"
    @Volatile
    private var decoyToken: String = "v9_decoy_k8"

    @Volatile
    private var lastTokenCaptureAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }
        return proceedDecrypted(chain, request, primed = false, tokenCaptured = false)
    }

    private fun proceedDecrypted(
        chain: Interceptor.Chain,
        request: Request,
        primed: Boolean,
        tokenCaptured: Boolean,
    ): Response {
        val response = chain.proceed(request.withVerifyHeader())

        if (response.code == 403) {
            // Se temos o cookie mas o token foi rejeitado, tenta capturar via WebView
            if (primed && !tokenCaptured) {
                response.close()
                captureTokenFromChapter(request.url.encodedPath)
                return proceedDecrypted(chain, request, primed = true, tokenCaptured = true)
            }

            if (primed) {
                response.close()
                val cookieNow = getToonVCookie()
                throw IOException(
                    "DEBUG 403 pós-prime | toon_v presente=${cookieNow != null} | " +
                        "toon_v valor=${cookieNow?.take(12)}... | url=${request.url}",
                )
            }
            response.close()
            primeCookie(request)
            return proceedDecrypted(chain, request, primed = true, tokenCaptured = tokenCaptured)
        }

        return response
    }

    private fun primeCookie(request: Request) {
        synchronized(this) {
            if (System.currentTimeMillis() - lastPrimeAttemptAt < REFRESH_COOLDOWN_MS) return
            lastPrimeAttemptAt = System.currentTimeMillis()
            TokenResolver.prime(baseUrl, userAgent)
            if (getToonVCookie() == null) {
                throw IOException(
                    "TokenResolver concluído, mas cookie 'toon_v' não foi encontrado no CookieManager.",
                )
            }
        }
    }

    private fun getToonVCookie(): String? {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(baseUrl) ?: return null
        return cookies.split(";")
            .firstOrNull { it.trim().startsWith("toon_v=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
    }

    private fun Request.withVerifyHeader(): Request {
        val verify = getToonVCookie() ?: return this
        return newBuilder()
            .header("x-toon-verify", verify)
            .header("x-toon-signature", buildToonSignature(url.encodedPath))
            .build()
    }

    private fun buildToonSignature(path: String): String {
        return if (path.contains("/chapters")) authToken else decoyToken
    }

    /**
     * Abre um WebView em uma página de capítulo e captura o header x-toon-signature gerado
     * pelo próprio site. Atualiza [authToken] ou [decoyToken] conforme o path da captura.
     */
    private fun captureTokenFromChapter(failedPath: String) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastTokenCaptureAt < TOKEN_CAPTURE_COOLDOWN_MS) return
            lastTokenCaptureAt = now
        }

        runCatching {
            val chapterPageUrl = baseUrl + failedPath.substringBefore("/api")
            val capturedToken = fetchLiveTokenFromWebView(chapterPageUrl)
            if (capturedToken != null) {
                if (failedPath.contains("/chapters")) {
                    authToken = capturedToken
                } else {
                    decoyToken = capturedToken
                }
            }
        }
    }

    /**
     * Abre um WebView oculto, injeta um script que intercepta fetch/XHR e retorna o
     * primeiro valor de x-toon-signature encontrado em requisições à API de mangás.
     */
    private fun fetchLiveTokenFromWebView(pageUrl: String): String? {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var capturedToken: String? = null
        var webView: WebView? = null

        handler.post {
            try {
                val view = WebView(applicationContext)
                webView = view
                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    if (!userAgent.isNullOrBlank()) userAgentString = userAgent
                }

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript("""
                            (function() {
                                const capture = (sig) => {
                                    window._capturedSignature = sig;
                                };
                                const origFetch = window.fetch;
                                window.fetch = function(...args) {
                                    const url = args[0].toString();
                                    const opts = args[1] || {};
                                    if (url.includes('/api/mangas/')) {
                                        if (opts.headers) {
                                            const sig = opts.headers instanceof Headers ? 
                                                opts.headers.get('x-toon-signature') : 
                                                opts.headers['x-toon-signature'];
                                            if (sig) capture(sig);
                                        }
                                    }
                                    return origFetch.apply(this, args);
                                };
                                const origXHR = window.XMLHttpRequest;
                                window.XMLHttpRequest = function() {
                                    const xhr = new origXHR();
                                    const origSetHeader = xhr.setRequestHeader;
                                    xhr.setRequestHeader = function(header, value) {
                                        if (header.toLowerCase() === 'x-toon-signature') {
                                            capture(value);
                                        }
                                        return origSetHeader.apply(this, arguments);
                                    };
                                    return xhr;
                                };
                            })();
                        """.trimIndent(), null)
                    }
                }

                view.loadUrl(pageUrl)

                Thread {
                    var elapsed = 0
                    while (elapsed < 15_000) {
                        if (capturedToken != null) break
                        Thread.sleep(500)
                        handler.post {
                            view.evaluateJavascript("window._capturedSignature") { result ->
                                if (result != null && result != "null" && result.isNotEmpty()) {
                                    capturedToken = result.trim('"')
                                }
                            }
                        }
                        elapsed += 500
                    }
                    latch.countDown()
                }.start()
            } catch (e: Exception) {
                latch.countDown()
            }
        }

        latch.await(20, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }
        return capturedToken
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val TOKEN_CAPTURE_COOLDOWN_MS = 1800_000L // 30 minutos
    }
}
