package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
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

    // Token JWT extraído da meta tag "t-seed" das páginas de capítulo
    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpiration: Long = 0L

    // Último readerPath conhecido (para buscar o token)
    @Volatile
    private var lastReaderPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }

        // Armazena o readerPath se presente na tag
        val readerPath = request.tag(ReaderPath::class.java)?.path
        if (readerPath != null) {
            lastReaderPath = readerPath
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
                val cookieNow = getToonVCookie()
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

    /**
     * Garante que tenhamos um token JWT válido (não expirado).
     * Se necessário, busca a página do capítulo para extrair um novo.
     */
    private fun ensureToken(): String? {
        val now = System.currentTimeMillis()
        val margin = 5 * 60 * 1000L // 5 minutos de margem

        if (cachedToken != null && tokenExpiration > 0 && now < tokenExpiration - margin) {
            return cachedToken
        }

        // Tenta renovar o token
        Log.d("ReadingGate", "Token expirado ou ausente. Buscando novo token...")
        refreshToken()
        return cachedToken
    }

    /**
     * Faz uma requisição HTTP à página do capítulo e extrai o token JWT da meta tag "t-seed".
     */
    private fun refreshToken() {
        val path = lastReaderPath
        if (path == null) {
            Log.w("ReadingGate", "Nenhum readerPath conhecido para buscar token.")
            return
        }

        runCatching {
            val pageUrl = baseUrl + path
            val cookieValue = getToonVCookie()
            val pageHeaders = headers.newBuilder().apply {
                if (cookieValue != null) {
                    add("Cookie", "toon_v=$cookieValue")
                }
            }.build()

            val request = GET(pageUrl, pageHeaders)
            val response = cookieClient.newCall(request).execute()
            val html = response.body.string()

            val metaRegex = Regex(
                """<meta[^>]+name=["']t-seed["'][^>]+content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
            val match = metaRegex.find(html)
            if (match != null) {
                val token = match.groupValues[1]
                val parts = token.split(".")
                if (parts.size == 3) {
                    val payloadJson = String(
                        Base64.decode(
                            parts[1].replace('-', '+').replace('_', '/'),
                            Base64.DEFAULT,
                        ),
                    )
                    val json = JSONObject(payloadJson)
                    val exp = json.optLong("exp", 0)
                    if (exp > 0) {
                        cachedToken = token
                        tokenExpiration = exp * 1000L
                        Log.d("ReadingGate", "Token atualizado com sucesso. Expira em ${(tokenExpiration - System.currentTimeMillis()) / 1000}s")
                    } else {
                        Log.e("ReadingGate", "Token JWT sem campo 'exp'")
                    }
                } else {
                    Log.e("ReadingGate", "Token JWT não possui três partes")
                }
            } else {
                Log.e("ReadingGate", "Meta tag 't-seed' não encontrada na página do capítulo")
            }
        }.onFailure {
            Log.e("ReadingGate", "Falha ao obter token: ${it.message}")
        }
    }

    data class ReaderPath(val path: String)

    companion object {
        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val NON_JSON_MESSAGE =
            "Não foi possível decifrar a resposta. Abra a fonte na WebView do app e tente de novo."
    }
}
