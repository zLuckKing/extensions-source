package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.UUID

class ReaderVerificationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val pages = linkedSetOf<String>()
    private lateinit var receiver: ResultReceiver
    private lateinit var pathPrefix: String
    private var delivered = false

    private val deliverPages =
        Runnable {
            if (pages.isNotEmpty()) {
                delivered = true
                receiver.send(
                    RESULT_PAGES,
                    Bundle().apply {
                        putStringArrayList(EXTRA_PAGES, synchronized(pages) { ArrayList(pages) })
                    },
                )
                finish()
            }
        }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val readerUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val mangaId = intent.getStringExtra(EXTRA_MANGA_ID) ?: return finish()
        val chapterNumber = intent.getStringExtra(EXTRA_CHAPTER_NUMBER) ?: return finish()
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER) ?: return finish()
        if (Uri.parse(readerUrl).host != SITE_HOST) return finish()
        pathPrefix = "/obras/$mangaId/$chapterNumber/"

        val bridgeName = "bridge_${UUID.randomUUID().toString().replace("-", "")}"
        val webView =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun post(value: String) {
                            runCatching {
                                val values = JSONArray(value)
                                repeat(values.length()) { index -> addCandidate(values.getString(index)) }
                            }
                        }
                    },
                    bridgeName,
                )
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            request?.url?.toString()?.let(::addCandidate)
                            return null
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            view.evaluateJavascript(
                                """
                                (() => {
                                  if (window.__toonLivreCollector) return;
                                  window.__toonLivreCollector = setInterval(() => {
                                    const urls = [
                                      ...performance.getEntriesByType('resource').map(entry => entry.name),
                                      ...Array.from(document.images).map(image => image.currentSrc || image.src),
                                    ];
                                    window['$bridgeName'].post(JSON.stringify(urls));
                                  }, 500);
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                loadUrl(readerUrl)
            }
        setContentView(webView)
    }

    private fun addCandidate(candidate: String) {
        val cdnUrl = candidate.toCdnUrl() ?: return
        synchronized(pages) {
            if (!pages.add(cdnUrl)) return
        }
        handler.removeCallbacks(deliverPages)
        handler.postDelayed(deliverPages, SETTLE_DELAY_MS)
    }

    private fun String.toCdnUrl(): String? {
        val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
        val cdnUri =
            when (uri.host) {
                CDN_HOST -> uri
                PROXY_HOST -> uri.getQueryParameter("url")?.let(Uri::parse) ?: return null
                else -> return null
            }
        return cdnUri
            .takeIf { it.scheme == "https" && it.host == CDN_HOST && it.path.orEmpty().startsWith(pathPrefix) }
            ?.toString()
    }

    override fun onDestroy() {
        handler.removeCallbacks(deliverPages)
        if (!delivered && ::receiver.isInitialized) receiver.send(RESULT_CANCELED, null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "reader_url"
        const val EXTRA_MANGA_ID = "manga_id"
        const val EXTRA_CHAPTER_NUMBER = "chapter_number"
        const val EXTRA_RECEIVER = "result_receiver"
        const val EXTRA_PAGES = "pages"
        const val RESULT_PAGES = 1

        private const val SITE_HOST = "toonlivre.net"
        private const val CDN_HOST = "cdn.toonlivre.net"
        private const val PROXY_HOST = "slightly-free-mayfly.edgecompute.app"
        private const val SETTLE_DELAY_MS = 3_000L
    }
}
