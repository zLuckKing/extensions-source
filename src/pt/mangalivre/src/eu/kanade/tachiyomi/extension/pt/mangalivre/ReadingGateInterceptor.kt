package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ReadingGateInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
    private val cookieClient: OkHttpClient,
    private val decryptor: MangaLivreDecryptor,
) : Interceptor {

    private val baseUrlHost = baseUrl.toHttpUrl().host

    @Volatile
    private var lastPrimeAttemptAt = 0L

    @Volatile
    private var authToken: String = DEFAULT_AUTH_TOKEN

    @Volatile
    private var decoyToken: String = DEFAULT_DECOY_TOKEN

    @Volatile
    private var lastTokenCaptureAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }
        return proceedDecrypted(chain, request, primed = false, reloaded = false, tokenCaptured = false)
    }

    private fun proceedDecrypted(
        chain: Interceptor.Chain,
        request: Request,
        primed: Boolean,
        reloaded: Boolean,
        tokenCaptured: Boolean,
    ): Response {
        val response = chain.proceed(request.withVerifyHeader())

        if (response.code == 403) {
            if (primed && !tokenCaptured) {
                response.close()
                captureTokenFromChapter(request.url.encodedPath)
                return proceedDecrypted(chain, request, primed = true, reloaded = reloaded, tokenCaptured = true)
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
            return proceedDecrypted(chain, request, primed = true, reloaded = reloaded, tokenCaptured = tokenCaptured)
        }

        val dataKey = response.headers["x-toon-datakey"] ?: return response

        val contentType = response.body.contentType()
        val decrypted = decryptor.decrypt(response.body.string(), dataKey)
        if (decrypted != null) {
            return response.newBuilder()
                .body(decrypted.toResponseBody(contentType))
                .build()
        }

        response.close()
        if (reloaded) throw IOException("$NON_JSON_MESSAGE | ${decryptor.debugInfo()}")
        decryptor.reloadConstants()
        return proceedDecrypted(chain, request, primed = primed, reloaded = true, tokenCaptured = tokenCaptured)
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

    private fun buildToonSignature(path: String): String = if (path.contains("/chapters")) authToken else decoyToken

    private fun captureTokenFromChapter(failedPath: String) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastTokenCaptureAt < TOKEN_CAPTURE_COOLDOWN_MS) return
            lastTokenCaptureAt = now
        }

        runCatching {
            val chapterPageUrl = baseUrl + failedPath.substringBeforeLast("/chapters") + "/1"
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

    private suspend fun fetchLiveTokenFromWebView(pageUrl: String): String? = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val latch = CountDownLatch(1)
            var webView: WebView? = null
            var capturedToken: String? = null

            handler.post {
                try {
                    val view = WebView(applicationContext)
                    webView = view
                    with(view.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        if (!userAgent.isNullOrBlank()) userAgentString = userAgent
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(
                                """
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
                                """.trimIndent(),
                                null,
                            )
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
            cont.resume(capturedToken)
        }
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val DEFAULT_AUTH_TOKEN = "v9_auth_k8"
        private const val DEFAULT_DECOY_TOKEN = "v9_decoy_k8"

        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val TOKEN_CAPTURE_COOLDOWN_MS = 1800_000L
        private const val NON_JSON_MESSAGE =
            "Não foi possível decifrar a resposta. Abra a fonte na WebView do app e tente de novo."
    }
}
