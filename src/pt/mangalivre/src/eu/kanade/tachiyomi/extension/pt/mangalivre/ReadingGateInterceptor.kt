package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * Chapter contents are only served to visitors that carry a proof of work cookie and a
 * short lived token tied to the chapter being requested.
 *
 * The proof of work cookie is entirely synthetic, computed locally and never actually issued
 * by the server, so it's kept off the app's shared cookie store on purpose: that store is
 * backed by `android.webkit.CookieManager`, the same one the WebView reader uses. Writing to
 * it here would leave the WebView with cookies a real browser never produced, which is
 * exactly the kind of mismatched state that makes Cloudflare Turnstile refuse the visitor
 * when the reader later falls back to the WebView. Everything this interceptor needs is
 * instead attached by hand, per request, through a private client that never touches that
 * shared store.
 *
 * The site rotates the values feeding the proof of work, so whenever it stops accepting ours
 * they are read again from the site script instead of waiting for a new release.
 */
class ReadingGateInterceptor(
    private val baseUrl: String,
    private val appClient: OkHttpClient,
) : Interceptor {

    private val homeUrl = baseUrl.toHttpUrl()

    /** Same client, minus the shared cookie jar the WebView reader also relies on. */
    private val client = appClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private var parameters = PowParameters.DEFAULT
    private var visitorCookie: String? = null
    private var visitorCookieExpiresAt = 0L
    private var cachedToken: CachedToken? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.host != homeUrl.host) {
            return chain.proceed(request)
        }

        val chapterKey = request.url.chapterKey()
            ?: return chain.proceed(request)

        val token = routeToken(chapterKey, request.headers)
            ?: throw IOException(BLOCKED_MESSAGE)

        val gatedRequest = request.newBuilder()
            .header(ROUTE_TOKEN, token)
            .header("Cookie", cookieHeaderFor(request.url, request.headers))
            .build()

        return chain.proceed(gatedRequest)
    }

    /** Matches `/api/mangas/{mangaId}/chapters/{chapterId}`, the only gated endpoint. */
    private fun HttpUrl.chapterKey(): String? {
        val segments = pathSegments

        if (segments.size != CHAPTER_SEGMENTS ||
            segments[0] != "api" ||
            segments[1] != "mangas" ||
            segments[3] != "chapters"
        ) {
            return null
        }

        return "${segments[2]}/${segments[4]}"
    }

    /** Retries once with freshly read parameters, since a refusal usually means they rotated. */
    private fun routeToken(key: String, headers: Headers): String? {
        cachedToken?.takeIf { it.key == key && it.isValid }?.let { return it.value }

        return requestToken(key, headers) ?: run {
            reloadParameters(headers)
            requestToken(key, headers)
        }
    }

    @Synchronized
    private fun requestToken(key: String, headers: Headers): String? {
        val tokenRequest = GET("$baseUrl/api/chapter-token/$key", headers).newBuilder()
            .header("Cookie", cookieHeaderFor(homeUrl, headers))
            .build()

        val dto = client.newCall(tokenRequest).execute()
            .use { if (it.isSuccessful) it.parseAs<TokenDto>() else null }
            ?: return null

        cachedToken = CachedToken(
            key = key,
            value = dto.token,
            expiresAt = System.currentTimeMillis() + dto.expiresMs - EXPIRY_MARGIN,
        )

        return dto.token
    }

    @Synchronized
    private fun reloadParameters(headers: Headers) {
        val home = client.newCall(GET(baseUrl, headers)).execute().use { it.body.string() }
        val scriptPath = SCRIPT_REGEX.find(home)?.value ?: return
        val script = client.newCall(GET("$baseUrl$scriptPath", headers)).execute()
            .use { it.body.string() }

        parameters = PowParameters.parse(script)
        visitorCookie = null
        visitorCookieExpiresAt = 0L
    }

    /**
     * Builds the `Cookie` header by hand instead of letting OkHttp's cookie jar attach it:
     * merges whatever the app already holds for this host (e.g. a `cf_clearance` obtained
     * through a prior WebView solve) with our own proof-of-work cookie, without ever saving
     * the latter back into the shared store.
     */
    private fun cookieHeaderFor(url: HttpUrl, requestHeaders: Headers): String {
        requestHeaders["Cookie"]?.let { return it }

        val existing = appClient.cookieJar.loadForRequest(url)
            .filterNot { it.name == parameters.cookieName }
            .joinToString("; ") { "${it.name}=${it.value}" }

        val pow = "${parameters.cookieName}=${visitorCookieValue()}"

        return if (existing.isBlank()) pow else "$existing; $pow"
    }

    private fun visitorCookieValue(): String {
        val now = System.currentTimeMillis()

        visitorCookie?.takeIf { now < visitorCookieExpiresAt }?.let { return it }

        val payload = """{"ts":$now,"pow":"${ProofOfWork(parameters).solve(now)}"}"""
        val value = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)

        visitorCookie = value
        visitorCookieExpiresAt = now + COOKIE_LIFETIME

        return value
    }

    private class CachedToken(val key: String, val value: String, private val expiresAt: Long) {
        val isValid get() = System.currentTimeMillis() < expiresAt
    }

    @Serializable
    private class TokenDto(
        val token: String,
        val expiresMs: Long = 0,
    )

    companion object {
        private const val ROUTE_TOKEN = "x-toon-route-token"
        private const val CHAPTER_SEGMENTS = 5
        private const val COOKIE_LIFETIME = 5 * 60 * 1000L
        private const val EXPIRY_MARGIN = 30 * 1000L
        private const val BLOCKED_MESSAGE =
            "O site recusou a liberação do capítulo. Abra a fonte na WebView e tente de novo."
        private val SCRIPT_REGEX = Regex("""/assets/index-[\w.\-]+\.js""")
    }
}
