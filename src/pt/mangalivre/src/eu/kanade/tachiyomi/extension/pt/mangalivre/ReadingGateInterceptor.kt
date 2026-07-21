package eu.kanade.tachiyomi.extension.pt.mangalivre

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

class ReadingGateInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
    private val cookieClient: OkHttpClient,
    private val decryptor: MangaLivreDecryptor,
    private val headers: Headers,
) : Interceptor {

    private val baseUrlHost = baseUrl.toHttpUrl().host

    @Volatile
    private var lastPrimeAttemptAt = 0L

    @Volatile
    private var authToken: String = DEFAULT_AUTH_TOKEN

    @Volatile
    private var decoyToken: String = DEFAULT_DECOY_TOKEN

    @Volatile
    private var lastTokenReloadAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }
        return proceedDecrypted(chain, request, primed = false, reloaded = false, tokenReloaded = false)
    }

    private fun proceedDecrypted(
        chain: Interceptor.Chain,
        request: Request,
        primed: Boolean,
        reloaded: Boolean,
        tokenReloaded: Boolean,
    ): Response {
        val response = chain.proceed(request.withVerifyHeader())

        if (response.code == 403) {
            if (primed && !tokenReloaded) {
                response.close()
                reloadSignatureTokens()
                return proceedDecrypted(chain, request, primed = true, reloaded = reloaded, tokenReloaded = true)
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
            return proceedDecrypted(chain, request, primed = true, reloaded = reloaded, tokenReloaded = tokenReloaded)
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
        return proceedDecrypted(chain, request, primed = primed, reloaded = true, tokenReloaded = tokenReloaded)
    }

    private fun primeCookie(request: Request) {
        synchronized(this) {
            if (System.currentTimeMillis() - lastPrimeAttemptAt < REFRESH_COOLDOWN_MS) return
            lastPrimeAttemptAt = System.currentTimeMillis()
            runCatching { TokenResolver.prime(baseUrl, userAgent) }
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

    private fun reloadSignatureTokens() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastTokenReloadAt < TOKEN_RELOAD_COOLDOWN_MS) return
            lastTokenReloadAt = now
        }

        runCatching {
            val indexJsUrl = cookieClient.newCall(GET(baseUrl, headers)).execute()
                .asJsoup()
                .selectFirst("script[src*=index]")
                ?.absUrl("src")
            if (indexJsUrl != null) {
                val js = cookieClient.newCall(GET(indexJsUrl, headers)).execute().body.string()
                val match = TOKEN_REGEX.find(js)
                if (match != null) {
                    val newAuth = match.groupValues[1]
                    val newDecoy = match.groupValues[2]
                    if (newAuth.isNotBlank() && newDecoy.isNotBlank()) {
                        authToken = newAuth
                        decoyToken = newDecoy
                    }
                }
            }
        }
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val DEFAULT_AUTH_TOKEN = "v9_auth_k8"
        private const val DEFAULT_DECOY_TOKEN = "v9_decoy_k8"

        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val TOKEN_RELOAD_COOLDOWN_MS = 30_000L
        private const val NON_JSON_MESSAGE =
            "Não foi possível decifrar a resposta. Abra a fonte na WebView do app e tente de novo."

        // Captura strings como "v9_auth_k8" e "v9_decoy_k8"
        private val TOKEN_REGEX = Regex(
            """["'](v\d+_auth_\w+)["'].*?["'](v\d+_decoy_\w+)["']""",
        )
    }
}
