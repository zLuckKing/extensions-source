package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashSet
import rx.Observable
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaLivre :
    HttpSource(),
    ConfigurableSource {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds) { it.host == baseUrlHost }
        .build()

    private val apiUrl: String = "$baseUrl/api"

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "*/*")
        .add("Accept-Language", "pt-BR,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")

    // ============================== Popular =======================================

    private val popularFilter = FilterList(
        listOf(
            OrderByFilter(options = listOf("" to SORT_POPULAR)),
            OrderDirectionFilter(options = listOf("" to DIRECTION_DESC)),
        ),
    )

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", popularFilter)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest =======================================

    private val latestFilter = FilterList(
        listOf(
            OrderByFilter(options = listOf("" to SORT_UPDATED)),
            OrderDirectionFilter(options = listOf("" to DIRECTION_DESC)),
        ),
    )

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", latestFilter)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Search =======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/mangas/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> url.addQueryParameter("sortBy", filter.selected())
                is OrderDirectionFilter -> url.addQueryParameter("sortOrder", filter.selected())
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseJson<WrapperDto>()
        val mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ============================== Details =======================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/manga-by-slug/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseJson<MangaDto>().toSManga(useAlternativeTitle)

    // ============================== Chapters =======================================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseJson<MangaDto>().toSChapterList()

    // ============================== Pages =======================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        runBlocking {
            getPageListWithWebView(chapter)
        }
    }

    private suspend fun getPageListWithWebView(
        chapter: SChapter,
    ): List<Page> {
        val ref = chapter.url.substringAfterLast("#").parseAs<ChapterReferenceDto>()
        val chapterUrl = "$baseUrl${chapter.url.substringBefore('#')}"
        val imageUrls = Collections.synchronizedSet(LinkedHashSet<String>())

        Log.i(LOG_TAG, "Chapter URL: $chapterUrl")
        Log.d(LOG_TAG, "Chapter reference loaded: mangaId=${ref.mangaId}, chapterId=${ref.chapterId}")
        Log.i(LOG_TAG, "Starting WebView")

        fun collect(rawUrl: String) {
            val imageUrl = rawUrl.toCdnImageUrl() ?: return
            if (imageUrls.add(imageUrl)) {
                Log.d(LOG_TAG, "CDN URL found: $imageUrl")
            }
        }

        try {
            return runWebView(timeout = WEBVIEW_TIMEOUT) {
                var previousCount = 0
                var stablePolls = 0

                javaScriptEnabled = true
                domStorageEnabled = true

                interceptRequest { request ->
                    collect(request.url.toString())
                    null
                }
                jsBridge(JS_BRIDGE_NAME) { payload ->
                    payload.parseAs<List<String>>().forEach(::collect)
                }
                onPageFinished { url ->
                    Log.i(LOG_TAG, "Page finished: $url")
                    evaluateJs(COLLECT_IMAGE_URLS_SCRIPT)
                }
                onReceivedError { request, error ->
                    Log.e(
                        LOG_TAG,
                        "WebView error ${error.errorCode} for ${request.url}: ${error.description}",
                    )
                }
                poll(1.seconds) {
                    evaluateJs(COLLECT_IMAGE_URLS_SCRIPT)
                    val currentCount = imageUrls.size
                    if (currentCount > 0 && currentCount == previousCount) {
                        stablePolls++
                    } else {
                        stablePolls = 0
                    }
                    previousCount = currentCount
                    if (stablePolls >= STABLE_POLLS) {
                        val pages = imageUrls.toPageList()
                        Log.i(LOG_TAG, "Pages found: ${pages.size}")
                        resolve(pages)
                    }
                }
                loadUrl(chapterUrl)
            }
        } catch (error: WebViewTimeoutException) {
            Log.e(LOG_TAG, "WebView timeout after $WEBVIEW_TIMEOUT; URLs found: ${imageUrls.size}")
            if (imageUrls.isNotEmpty()) {
                return imageUrls.toPageList()
            }
            throw error
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "WebView failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters =======================================

    override fun getFilterList(): FilterList = FilterList(
        listOf(
            OrderByFilter(
                "Ordem",
                listOf(
                    "Mais Visualizados" to SORT_POPULAR,
                    "Lançamentos" to SORT_RELEASE,
                    "Última Atualização" to SORT_UPDATED,
                    "Melhor Avaliação" to SORT_RATING,
                    "A-Z" to SORT_TITLE,
                ),
            ),
            Filter.Separator(),
            OrderDirectionFilter(
                "Direção",
                listOf(
                    "↑ Decrescente" to DIRECTION_DESC,
                    "↓ Crescente" to DIRECTION_ASC,
                ),
            ),
        ),
    )

    val useAlternativeTitle: Boolean get() =
        preferences.getBoolean(ALTERNATIVE_TITLE_PREF, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_TITLE_PREF
            title = "Titulo alternativo"
            summary = buildString {
                append("Use titulos alternativos como principal quando disponivel.")
                append(" Essa opção não tem efeito sobre obras já adicionadas na sua biblioteca")
            }
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Utilities =======================================

    private inline fun <reified T> Response.parseJson(): T {
        val peek = peekBody(MAX_PEEK).string().trimStart()
        if (peek.isEmpty() || peek.startsWith("<")) {
            close()
            throw IOException(NON_JSON_MESSAGE)
        }
        return parseAs<T>()
    }

    companion object {
        private const val LOG_TAG = "MANGALIVRE_READER"
        private const val JS_BRIDGE_NAME = "MangaLivreReader"
        private const val STABLE_POLLS = 3
        private val WEBVIEW_TIMEOUT = 90.seconds
        private const val CDN_HOST = "cdn.toonlivre.net"
        private const val PROXY_HOST = "slightly-free-mayfly.edgecompute.app"
        private val COLLECT_IMAGE_URLS_SCRIPT =
            """
            (() => {
                const urls = new Set();
                document.querySelectorAll('img').forEach((image) => {
                    [image.currentSrc, image.src, image.dataset.src].forEach((url) => {
                        if (url) urls.add(url);
                    });
                });
                performance.getEntriesByType('resource').forEach((entry) => urls.add(entry.name));
                $JS_BRIDGE_NAME.post(JSON.stringify(Array.from(urls)));
            })();
            """.trimIndent()

        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val MAX_PEEK = 1024L
        private const val NON_JSON_MESSAGE =
            "Este site exige verificação Cloudflare Turnstile.\n\n" +
            "Para ler capítulos:\n" +
            "1. Vá em Mais → Configurações → Avançado\n" +
            "2. Defina o User-Agent da WebView como:\n" +
            "Mozilla/5.0 (Linux; Android 16; 2311DRK48G Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/150.0.7871.181 Mobile Safari/537.36 GoogleApp/17.44.15.ve.arm64\n" +
            "3. Desative 'DNS sobre HTTPS (DoH)'\n" +
            "4. Abra o capítulo pela WebView"

        private const val SORT_POPULAR = "popular"
        private const val SORT_RELEASE = "release"
        private const val SORT_UPDATED = "updated"
        private const val SORT_RATING = "rating"
        private const val SORT_TITLE = "title"
        private const val DIRECTION_DESC = "desc"
        private const val DIRECTION_ASC = "asc"
    }

    private fun String.toCdnImageUrl(): String? {
        val url = toHttpUrlOrNull() ?: return null
        val candidate = when (url.host) {
            CDN_HOST -> url
            PROXY_HOST -> url.queryParameter("url")?.toHttpUrlOrNull()
            else -> null
        } ?: return null

        return candidate.takeIf { it.isHttps && it.host == CDN_HOST }?.toString()
    }

    private fun Set<String>.toPageList(): List<Page> = synchronized(this) {
        mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }
}
