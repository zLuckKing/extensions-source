package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TokenResolver {

    private const val PAGE_TIMEOUT_SECONDS = 25L
    private const val COOKIE_TIMEOUT_SECONDS = 10L
    private const val WEBVIEW_WIDTH = 1080
    private const val WEBVIEW_HEIGHT = 1920

    @SuppressLint("SetJavaScriptEnabled")
    fun prime(pageUrl: String, userAgent: String?) {
        val handler = Handler(Looper.getMainLooper())
        val pageFinishedLatch = CountDownLatch(1)
        var webView: WebView? = null

        handler.post {
            try {
                val view = WebView(applicationContext)
                webView = view

                view.layoutParams = ViewGroup.LayoutParams(WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(WEBVIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(WEBVIEW_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, WEBVIEW_WIDTH, WEBVIEW_HEIGHT)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    if (!userAgent.isNullOrBlank()) userAgentString = userAgent
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pageFinishedLatch.countDown()
                    }
                }

                view.loadUrl(pageUrl)
            } catch (_: Throwable) {
                pageFinishedLatch.countDown()
            }
        }

        // Espera a página terminar de carregar
        pageFinishedLatch.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Aguarda ativamente o cookie "toon_v" aparecer
        waitForCookie(pageUrl)

        // Limpa o WebView
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    private fun waitForCookie(pageUrl: String) {
        val baseUrl = pageUrl.substringBefore("?").substringBefore("#")
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(COOKIE_TIMEOUT_SECONDS)
        val cookieManager = CookieManager.getInstance()

        while (System.currentTimeMillis() < deadline) {
            val cookies = cookieManager.getCookie(baseUrl) ?: ""
            if (cookies.contains("toon_v=", ignoreCase = true)) {
                return // cookie encontrado
            }
            Thread.sleep(500)
        }
        // Se chegou aqui, timeout sem cookie – não lançamos exceção para não quebrar o fluxo,
        // mas o interceptor vai reportar a ausência.
    }
}
