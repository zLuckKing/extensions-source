package eu.kanade.tachiyomi.extension.pt.mangalivre;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;

public class ReaderVerificationActivity extends Activity {
  public static final String EXTRA_URL = "reader_url";
  public static final String EXTRA_MANGA_ID = "manga_id";
  public static final String EXTRA_CHAPTER_NUMBER = "chapter_number";
  public static final String EXTRA_RECEIVER = "result_receiver";
  public static final String EXTRA_PAGES = "pages";
  public static final int RESULT_PAGES = 1;

  private static final String SITE_HOST = "toonlivre.net";
  private static final String CDN_HOST = "cdn.toonlivre.net";
  private static final String PROXY_HOST = "slightly-free-mayfly.edgecompute.app";
  private static final long COMPLETE_PAGE_LIST_DELAY_MS = 100L;
  private static final long SETTLE_DELAY_MS = 500L;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Set<String> pages = new LinkedHashSet<>();
  private ResultReceiver receiver;
  private String pathPrefix;
  private WebView webView;
  private boolean delivered;

  private final Runnable deliverPages =
      () -> {
        if (delivered) return;
        ArrayList<String> result;
        synchronized (pages) {
          if (pages.isEmpty()) return;
          result = new ArrayList<>(pages);
        }
        delivered = true;
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(EXTRA_PAGES, result);
        receiver.send(RESULT_PAGES, bundle);
        finish();
      };

  @SuppressWarnings("deprecation")
  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    String readerUrl = getIntent().getStringExtra(EXTRA_URL);
    String mangaId = getIntent().getStringExtra(EXTRA_MANGA_ID);
    String chapterNumber = getIntent().getStringExtra(EXTRA_CHAPTER_NUMBER);
    receiver = getIntent().getParcelableExtra(EXTRA_RECEIVER);
    if (readerUrl == null || mangaId == null || chapterNumber == null || receiver == null) {
      finish();
      return;
    }
    if (!SITE_HOST.equals(Uri.parse(readerUrl).getHost())) {
      finish();
      return;
    }
    pathPrefix = "/obras/" + mangaId + "/" + chapterNumber + "/";

    String bridgeName = "bridge_" + UUID.randomUUID().toString().replace("-", "");
    webView = new WebView(this);
    webView.getSettings().setJavaScriptEnabled(true);
    webView.getSettings().setDomStorageEnabled(true);
    webView.setWebChromeClient(new WebChromeClient());
    webView.addJavascriptInterface(
        new Object() {
          @JavascriptInterface
          public void post(String value) {
            if (addCandidates(value)) scheduleDelivery(SETTLE_DELAY_MS);
          }

          @JavascriptInterface
          public void postPages(String value) {
            if (addCandidates(value)) scheduleDelivery(COMPLETE_PAGE_LIST_DELAY_MS);
          }
        },
        bridgeName);
    webView.setWebViewClient(
        new WebViewClient() {
          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            if (request != null && request.getUrl() != null) {
              if (addCandidate(request.getUrl().toString())) scheduleDelivery(SETTLE_DELAY_MS);
            }
            return null;
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri target = request.getUrl();
            return request.isForMainFrame() && !SITE_HOST.equals(target.getHost());
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            String script =
                "(() => {"
                    + "const bridge = window['"
                    + bridgeName
                    + "'];"
                    + "if (!window.__toonLivreFetchWrapped) {"
                    + "window.__toonLivreFetchWrapped = true;"
                    + "const originalFetch = window.fetch;"
                    + "if (typeof originalFetch === 'function') {"
                    + "window.fetch = function() {"
                    + "return originalFetch.apply(this, arguments).then(response => {"
                    + "try {"
                    + "if (response.ok && new URL(response.url).pathname === '/api/reader/chapter/access') {"
                    + "response.clone().json().then(data => {"
                    + "if (Array.isArray(data?.chapter?.pages)) bridge.postPages(JSON.stringify(data.chapter.pages));"
                    + "}).catch(() => {});"
                    + "}"
                    + "} catch (_) {}"
                    + "return response;"
                    + "});"
                    + "};"
                    + "}"
                    + "}"
                    + "if (!window.__toonLivreCollector) {"
                    + "window.__toonLivreCollector = setInterval(() => {"
                    + "const urls = ["
                    + "...performance.getEntriesByType('resource').map(entry => entry.name),"
                    + "...Array.from(document.images).map(image => image.currentSrc || image.src),"
                    + "];"
                    + "bridge.post(JSON.stringify(urls));"
                    + "}, 250);"
                    + "}"
                    + "})();";
            view.evaluateJavascript(script, null);
          }
        });
    webView.loadUrl(readerUrl);
    setContentView(webView);
  }

  private boolean addCandidates(String value) {
    boolean added = false;
    try {
      JSONArray values = new JSONArray(value);
      for (int index = 0; index < values.length(); index++) {
        added |= addCandidate(values.getString(index));
      }
    } catch (JSONException ignored) {
    }
    return added;
  }

  private boolean addCandidate(String candidate) {
    String cdnUrl = toCdnUrl(candidate);
    if (cdnUrl == null) return false;
    synchronized (pages) {
      return pages.add(cdnUrl);
    }
  }

  private void scheduleDelivery(long delayMillis) {
    handler.removeCallbacks(deliverPages);
    handler.postDelayed(deliverPages, delayMillis);
  }

  private String toCdnUrl(String candidate) {
    Uri uri = Uri.parse(candidate);
    Uri cdnUri;
    if (CDN_HOST.equals(uri.getHost())) {
      cdnUri = uri;
    } else if (PROXY_HOST.equals(uri.getHost())) {
      String originalUrl = uri.getQueryParameter("url");
      if (originalUrl == null) return null;
      cdnUri = Uri.parse(originalUrl);
    } else {
      return null;
    }
    String path = cdnUri.getPath();
    if (!"https".equals(cdnUri.getScheme())
        || !CDN_HOST.equals(cdnUri.getHost())
        || path == null
        || !path.startsWith(pathPrefix)) {
      return null;
    }
    return cdnUri.toString();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(deliverPages);
    if (webView != null) {
      ViewGroup parent = (ViewGroup) webView.getParent();
      if (parent != null) parent.removeView(webView);
      webView.destroy();
    }
    if (!delivered && receiver != null && !isChangingConfigurations()) {
      receiver.send(RESULT_CANCELED, null);
    }
    super.onDestroy();
  }
}
