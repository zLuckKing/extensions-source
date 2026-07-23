package eu.kanade.tachiyomi.extension.pt.mangalivre

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Clears the site's reading gate for same-host requests. The gate is a double-submit check: the
 * client-generated `toon_v` cookie must be echoed in the `x-toon-verify` header. On a 403 this
 * primes the cookie via a hidden WebView ([TokenResolver]).
 */
class ReadingGateInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
    private val cookieClient: OkHttpClient,
) : Interceptor {

    private val baseUrlHost = baseUrl.toHttpUrl().host

    @Volatile
    private var lastPrimeAttemptAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }
        return proceedDecrypted(chain, request, primed = false)
    }

    private fun proceedDecrypted(
        chain: Interceptor.Chain,
        request: Request,
        primed: Boolean,
    ): Response {
        val response = chain.proceed(request.withVerifyHeader())

        if (response.code == 403) {
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
            return proceedDecrypted(chain, request, primed = true)
        }

        // A resposta é retornada como está. A descriptografia será feita
        // posteriormente pelo MangaLivreDecryptor (navigate + harvest).
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
        val bucket = System.currentTimeMillis() / 30_000L
        val resource = if (path.contains("/chapters")) "chapters" else "other"
        val toHash = "$bucket:$resource:$SIGNATURE_SALT"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(toHash.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return android.util.Base64.encodeToString("$bucket:$hash".toByteArray(), android.util.Base64.NO_WRAP)
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val SIGNATURE_SALT = "My4xNDE=_1388"
    }
}
