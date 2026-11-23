package com.clauder.reader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clauder.reader.data.SiteRule
import com.clauder.reader.extract.ChapterCleaner
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.RawExtraction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * 앱 안의 브라우저(WebView) 하나를 들고 있으면서 화면에 보여줄 상태와 본문 추출 기능을 제공한다.
 * 탭을 옮겨도 페이지가 유지되도록 WebView 는 액티비티가 살아 있는 동안 한 번만 만든다.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    context: Context,
    private val onPageLoaded: (url: String) -> Unit,
) {
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(0)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    /** 한 번이라도 페이지를 열었는지(시작 안내 화면 표시용) */
    var hasPage by mutableStateOf(false)
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private val extractorSource: String by lazy {
        context.assets.open("extractor.js").bufferedReader().use { it.readText() }
    }

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // 로그인 상태와 보안 확인 통과 기록이 유지되도록 쿠키를 허용한다.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 웹 주소만 연다. 다른 앱을 여는 주소(intent://, market:// 등)는 무시한다.
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                isLoading = true
                hasPage = true
                this@BrowserController.url = pageUrl
                syncNav(view)
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                isLoading = false
                this@BrowserController.url = pageUrl
                this@BrowserController.title = view.title.orEmpty()
                syncNav(view)
                CookieManager.getInstance().flush()
                onPageLoaded(pageUrl)
            }

            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
                this@BrowserController.url = pageUrl
                syncNav(view)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@BrowserController.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                this@BrowserController.title = pageTitle.orEmpty()
            }
        }
    }

    private fun syncNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    /** 주소창 입력을 열기. 주소가 아니면 검색한다. */
    fun load(input: String) {
        val target = UrlInput.toUrl(input) ?: return
        hasPage = true
        url = target
        webView.loadUrl(target)
    }

    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()
    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }

    /**
     * 다음(또는 이전) 화로 이동한다. 주소가 있으면 그 주소를 열고,
     * 주소 없는 버튼이면 추출 때 표시해 둔 요소를 대신 눌러 준다.
     */
    fun navigate(chapter: ExtractedChapter, next: Boolean) {
        val target = if (next) chapter.nextUrl else chapter.prevUrl
        if (target != null) {
            webView.loadUrl(target)
        } else {
            val kind = if (next) "next" else "prev"
            webView.evaluateJavascript(
                "(function(){var e=document.querySelector('[data-clauder-nav=\"$kind\"]');if(e)e.click();return !!e;})()",
                null,
            )
        }
    }

    /** 지금 보이는 페이지에서 본문을 추출한다. 반드시 메인 스레드에서 호출. */
    suspend fun extract(rule: SiteRule?): ExtractedChapter {
        val pageUrl = url
        val arg = rule?.toScriptArg() ?: "{}"
        val script = "($extractorSource)($arg)"
        val result = suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
        }
        if (result.isNullOrBlank() || result == "null") {
            return ExtractedChapter.error(pageUrl, "페이지에서 추출 스크립트를 실행하지 못했습니다.")
        }
        val chapter = try {
            ChapterCleaner.finish(json.decodeFromString<RawExtraction>(result))
        } catch (e: Exception) {
            Log.w(TAG, "추출 결과 해석 실패", e)
            return ExtractedChapter.error(pageUrl, "추출 결과를 해석하지 못했습니다: ${e.message}")
        }
        Log.i(
            TAG,
            "extract status=${chapter.status} source=${chapter.contentSource} path=${chapter.contentPath} " +
                "paragraphs=${chapter.paragraphs.size} chars=${chapter.charCount} removed=${chapter.removedLines} " +
                "work='${chapter.workTitle}' chapter='${chapter.chapterTitle}' " +
                "next=${chapter.nextUrl ?: if (chapter.nextClickable) "(button)" else "none"} " +
                "prev=${chapter.prevUrl ?: if (chapter.prevClickable) "(button)" else "none"} " +
                "first='${chapter.paragraphs.firstOrNull()?.take(30).orEmpty()}' " +
                "last='${chapter.paragraphs.lastOrNull()?.take(30).orEmpty()}' warnings=${chapter.warnings}",
        )
        return chapter
    }

    fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    fun onResume() = webView.onResume()

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    companion object {
        const val TAG = "Clauder"
    }
}
