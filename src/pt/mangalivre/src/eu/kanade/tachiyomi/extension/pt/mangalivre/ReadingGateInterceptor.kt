package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class ReadingGateInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
    private val cookieClient: OkHttpClient,
) : Interceptor {

    private val baseUrlHost = baseUrl.toHttpUrl().host

    @Volatile
    private var lastPrimeAttemptAt = 0L

    // Cache do token JWT
    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiration: Long = 0L

    // Último readerPath conhecido (para buscar o token na meta tag)
    @Volatile
    private var lastReaderPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }

        val readerPath = request.tag(ReaderPath::class.java)?.path
        if (readerPath != null) {
            lastReaderPath = readerPath
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
        val signature = ensureToken() ?: return this
        return newBuilder()
            .header("x-toon-verify", verify)
            .header("x-toon-signature", signature)
            .build()
    }

    private fun ensureToken(): String? {
        val now = System.currentTimeMillis()
        val margin = 5 * 60 * 1000L // 5 minutos de margem

        if (cachedToken != null && tokenExpiration > 0 && now < tokenExpiration - margin) {
            return cachedToken
        }

        Log.d("ReadingGate", "Token expirado ou ausente. Renovando...")
        refreshToken()
        return cachedToken
    }

    private fun refreshToken() {
        val path = lastReaderPath
        if (path != null) {
            val token = fetchTokenFromMeta(path)
            if (token != null) return
        }

        Log.d("ReadingGate", "Meta tag falhou, tentando /api/seed...")
        val token = fetchTokenFromApi()
        if (token != null) return

        Log.e("ReadingGate", "Não foi possível obter token de nenhuma fonte.")
    }

    private fun fetchTokenFromMeta(path: String): String? {
        return runCatching {
            val pageUrl = baseUrl + path
            val cookieValue = getToonVCookie()
            val pageHeaders = okhttp3.Headers.Builder().apply {
                if (cookieValue != null) {
                    add("Cookie", "toon_v=$cookieValue")
                }
                add("Referer", "$baseUrl/${path.trimStart('/').substringBefore("/")}")
            }.build()

            val request = GET(pageUrl, pageHeaders)
            val response = cookieClient.newCall(request).execute()
            val html = response.body.string()

            val metaRegex = Regex(
                """<meta[^>]*?name=["']t-seed["'][^>]*?content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
            val match = metaRegex.find(html)
            if (match != null) {
                val token = match.groupValues[1]
                if (validateAndCacheToken(token)) {
                    Log.d("ReadingGate", "Token obtido da meta tag.")
                    return token
                }
            }
            null
        }.onFailure {
            Log.e("ReadingGate", "Falha ao buscar meta tag: ${it.message}")
        }.getOrNull()
    }

    private fun fetchTokenFromApi(): String? {
        return runCatching {
            val apiUrl = "$baseUrl/api/seed"
            val request = GET(apiUrl, okhttp3.Headers.Builder().build())
            val response = cookieClient.newCall(request).execute()
            val json = response.parseAs<JsonObject>()
            val token = json["token"]?.jsonPrimitive?.content
            if (token != null && validateAndCacheToken(token)) {
                Log.d("ReadingGate", "Token obtido da API /api/seed.")
                return token
            }
            null
        }.onFailure {
            Log.e("ReadingGate", "Falha ao buscar /api/seed: ${it.message}")
        }.getOrNull()
    }

    private fun validateAndCacheToken(token: String): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) {
            Log.e("ReadingGate", "Token JWT inválido: não tem 3 partes.")
            return false
        }
        try {
            val payloadJson = String(
                Base64.decode(
                    parts[1].replace('-', '+').replace('_', '/'),
                    Base64.DEFAULT,
                ),
            )
            val json = JSONObject(payloadJson)
            val exp = json.optLong("exp", 0)
            if (exp == 0L) {
                Log.e("ReadingGate", "Token JWT sem campo 'exp'.")
                return false
            }
            cachedToken = token
            tokenExpiration = exp * 1000L
            Log.d("ReadingGate", "Token válido. Expira em ${(tokenExpiration - System.currentTimeMillis()) / 1000}s")
            return true
        } catch (e: Exception) {
            Log.e("ReadingGate", "Erro ao decodificar token JWT: ${e.message}")
            return false
        }
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val REFRESH_COOLDOWN_MS = 60_000L
    }
}
