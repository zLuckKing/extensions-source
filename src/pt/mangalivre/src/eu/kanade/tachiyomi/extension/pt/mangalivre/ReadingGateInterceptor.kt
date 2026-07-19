package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.security.MessageDigest

/**
 * Clears the site's reading gate for same-host requests. The gate is a double-submit check: the
 * client-generated `toon_v` cookie must be echoed in the `x-toon-verify` header. On a 403 this
 * primes the cookie via a hidden WebView ([TokenResolver]); on a decrypt failure it reloads the
 * rotated constants ([decryptor]). The two retries are independent, so a request that is both gated
 * and stale-keyed still recovers.
 */
class ReadingGateInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
    private val cookieClient: OkHttpClient,
    private val decryptor: MangaLivreDecryptor,
) : Interceptor {

    private val baseUrlHost = baseUrl.toHttpUrl().host

    @Volatile
    private var lastPrimeAttemptAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }
        return proceedDecrypted(chain, request, primed = false, reloaded = false)
    }

    private fun proceedDecrypted(
        chain: Interceptor.Chain,
        request: Request,
        primed: Boolean,
        reloaded: Boolean,
    ): Response {
        val response = chain.proceed(request.withVerifyHeader())

        if (response.code == 403) {
            if (primed) {
                response.close()
                val cookieNow = cookieClient.getCookie(baseUrl, "toon_v")
                throw IOException(
                    "DEBUG 403 pós-prime | toon_v presente=${cookieNow != null} | " +
                        "toon_v valor=${cookieNow?.take(12)}... | url=${request.url}",
                )
            }
            response.close()
            primeCookie(request)
            return proceedDecrypted(chain, request, primed = true, reloaded = reloaded)
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
        return proceedDecrypted(chain, request, primed = primed, reloaded = true)
    }

    private fun primeCookie(request: Request) {
        synchronized(this) {
            if (System.currentTimeMillis() - lastPrimeAttemptAt < REFRESH_COOLDOWN_MS) return
            lastPrimeAttemptAt = System.currentTimeMillis()
            val primePath = request.tag(ReaderPath::class.java)?.path ?: "/"
            runCatching { TokenResolver.prime("$baseUrl$primePath", userAgent) }

            // Transfere o cookie do WebView para o cookieClient
            val saved = extractAndSaveToonCookie()
            if (!saved) {
                throw IOException(
                    "TokenResolver concluído, mas cookie 'toon_v' não foi encontrado. " +
                    "Verifique se o WebView foi carregado corretamente."
                )
            }
        }
    }

    /**
     * Lê o cookie `toon_v` do CookieManager do WebView e o persiste no [cookieClient].
     * Retorna `true` se o cookie foi encontrado e salvo com sucesso.
     */
    private fun extractAndSaveToonCookie(): Boolean {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(baseUrl) ?: return false
        val toonV = cookies.split(";")
            .firstOrNull { it.trim().startsWith("toon_v=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
            ?: return false

        val domain = baseUrl.toHttpUrl().host
        val okHttpCookie = Cookie.Builder()
            .name("toon_v")
            .value(toonV)
            .domain(domain)
            .path("/")
            .build()

        cookieClient.cookieJar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(okHttpCookie)
        )
        return true
    }

    private fun Request.withVerifyHeader(): Request {
        val verify = cookieClient.getCookie(baseUrl, "toon_v") ?: return this
        return newBuilder()
            .header("x-toon-verify", verify)
            .header("x-toon-signature", buildToonSignature(url.encodedPath))
            .build()
    }

    private fun buildToonSignature(path: String): String {
        val bucket = System.currentTimeMillis() / 30_000L
        val resource = if (path.contains("/chapters")) "chapters" else "other"
        val toHash = "$bucket:$resource:$SIGNATURE_SALT"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(toHash.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Base64.encodeToString("$bucket:$hash".toByteArray(), Base64.NO_WRAP)
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val REFRESH_COOLDOWN_MS = 60_000L

        // btoa(Math.PI.toString().substring(0, 5)) + "_1388" from the bundle's signer — constant
        // since Math.PI never changes, so it's fine to precompute instead of recomputing per call.
        private const val SIGNATURE_SALT = "My4xNDE=_1388"
        private const val NON_JSON_MESSAGE =
            "Não foi possível decifrar a resposta. Abra a fonte na WebView do app e tente de novo."
    }
}

private fun OkHttpClient.getCookies(baseUrl: String) = cookieJar.loadForRequest(baseUrl.toHttpUrl())

private fun OkHttpClient.getCookie(baseUrl: String, cookie: String): String? =
    getCookies(baseUrl).firstOrNull { it.name == cookie }?.value?.takeUnless { it.isEmpty() }
