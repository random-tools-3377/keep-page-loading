package io.github.randomtools3377.keeppageloading

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URISyntaxException

/**
 * The one page the app keeps running.
 *
 * It lives as long as the app, not as long as the screen: MainActivity shows it while it's open,
 * and KeepRunningService keeps the app alive while it's closed or the phone is asleep. Closing
 * the screen leaves the page running; only Stop ends it.
 */
@SuppressLint("StaticFieldLeak") // Holds the application, and the open screen only while it's open.
object Page {
    lateinit var app: Context

    private var view: KeepRunningWebView? = null
    private var context: MutableContextWrapper? = null
    private var startScriptAdded = false
    private var dialog: AlertDialog? = null

    /** The screen showing the page, while it's open. */
    var screen: MainActivity? = null
        private set

    val currentView: WebView? get() = view

    /** The page's address, or the last one opened if the page isn't loaded. */
    val address: String?
        get() = view?.url?.takeIf { Address.isWebAddress(it) } ?: Prefs.lastAddress

    val hasPage: Boolean get() = Prefs.lastAddress != null

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var failedThisLoad = false
    private val crashes = ArrayDeque<Long>()
    private val retry = Runnable { view?.reload() }

    // The page's size on the screen, which it keeps while it's parked.
    private var lastWidth = 0
    private var lastHeight = 0

    /** Shows the page on the screen, making it first if needed. */
    fun attach(screen: MainActivity, area: ViewGroup) {
        val web = view ?: create()
        this.screen = screen
        Parking.leave(web)
        context?.baseContext = screen
        (web.parent as? ViewGroup)?.removeView(web)
        area.addView(web, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The screen is closing. The page carries on without it, in its invisible window. */
    fun detach(screen: MainActivity) {
        if (this.screen !== screen) return
        dialog?.dismiss()
        dialog = null
        this.screen = null
        view?.let { park(it) }
    }

    private fun park(web: KeepRunningWebView) {
        Parking.park(app, web, lastWidth, lastHeight)
        context?.baseContext = Parking.context ?: app
    }

    /** Opens an address the user typed. */
    fun open(address: String) {
        handler.removeCallbacks(retry)
        retries = 0
        Prefs.lastAddress = address
        val web = view ?: create().also { placeOnScreen(it) }
        web.loadUrl(address)
    }

    /** Loads the last page again, if there is one and nothing is loaded yet. */
    fun reopen() {
        val last = Prefs.lastAddress ?: return
        val web = view ?: create().also { placeOnScreen(it) }
        if (web.url == null) web.loadUrl(last)
    }

    /** Stop: the page is closed, the app stops keeping it running, and the screen closes. */
    fun close() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        Parking.leave(view)
        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        view = null
        context = null
        CookieManager.getInstance().flush()
        @Suppress("ImplicitSamInstance") // The Intent names the service, which is all stopService needs.
        app.stopService(Intent(app, KeepRunningService::class.java))
        screen?.finishAndRemoveTask()
    }

    /** Puts a new page on the screen, or in its invisible window while the screen is closed. */
    private fun placeOnScreen(web: KeepRunningWebView) {
        val screen = screen ?: return park(web)
        (web.parent as? ViewGroup)?.removeView(web)
        screen.pageArea.addView(web, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled") // Pages need it; it's what keeps them running.
    private fun create(): KeepRunningWebView {
        val wrapper = MutableContextWrapper(screen ?: app)
        val web = KeepRunningWebView(wrapper)
        context = wrapper
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        // The page's own process stays as important as the app, so Android doesn't put it to
        // sleep or close it when the app isn't on screen.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // On phones whose WebView is too old for this, the script is added as each page starts.
        startScriptAdded = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, StayActive.SCRIPT, setOf("*"))
            startScriptAdded = true
        }
        web.webViewClient = Client
        web.webChromeClient = ChromeClient
        web.setDownloadListener { url, _, _, _, _ -> openOutside(url) }
        web.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (!Parking.isParked && right > left && bottom > top) {
                lastWidth = right - left
                lastHeight = bottom - top
            }
        }
        view = web
        return web
    }

    private fun addressChanged(url: String?) {
        if (url == null || !Address.isWebAddress(url)) return
        Prefs.lastAddress = url
        screen?.showAddress(url)
        KeepRunningService.refresh(app)
    }

    /** Opens a link the page can't, like an email address or an app's link, in another app. */
    fun openOutside(link: String) {
        val screen = screen?.takeIf { it.isShowing } ?: return
        val intent = if (link.startsWith("intent:")) {
            try {
                Intent.parseUri(link, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } catch (e: URISyntaxException) {
                return
            }
        } else {
            Intent(Intent.ACTION_VIEW, link.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            screen.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && Address.isWebAddress(fallback)) view?.loadUrl(fallback)
            else screen.toast(R.string.no_app_for_link)
        }
    }

    /** The connection dropped: try again in 30 seconds, then less often, up to every 5 minutes. */
    private fun retryLater() {
        handler.removeCallbacks(retry)
        val delay = minOf(30_000L shl minOf(retries, 4), 5 * 60_000L)
        retries++
        handler.postDelayed(retry, delay)
    }

    /** The page's process stopped (Android closed it, or it crashed). Make a new one. */
    private fun rendererGone(dead: WebView) {
        if (dead !== view) {
            dead.destroy()
            return
        }
        Parking.leave(dead)
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        view = null
        val now = SystemClock.elapsedRealtime()
        crashes.addLast(now)
        while (crashes.isNotEmpty() && crashes.first() < now - 60_000L) crashes.removeFirst()
        val web = create()
        placeOnScreen(web)
        if (crashes.size > 3) {
            screen?.toast(R.string.page_crashed_again)
        } else {
            Prefs.lastAddress?.let { web.loadUrl(it) }
            screen?.toast(R.string.page_crashed)
        }
    }

    private val retryErrors = setOf(
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_UNKNOWN,
    )

    private object Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme in setOf("http", "https", "about", "data", "blob")) return false
            if (request.isForMainFrame && request.hasGesture()) openOutside(request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            failedThisLoad = false
            handler.removeCallbacks(retry)
            if (!startScriptAdded) view.evaluateJavascript(StayActive.SCRIPT, null)
            screen?.showAddress(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!failedThisLoad) retries = 0
            if (!startScriptAdded) view.evaluateJavascript(StayActive.SCRIPT, null)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            addressChanged(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            failedThisLoad = true
            if (error.errorCode in retryErrors) retryLater()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            rendererGone(view)
            return true
        }
    }

    /**
     * Messages from the page (alert, confirm, prompt). They're shown while the app is on screen.
     * While it isn't, they're answered straight away (OK for a message, Cancel for a question),
     * because a message waiting for an answer would stop the page.
     */
    private object ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            screen?.showProgress(newProgress)
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return true.also { result.confirm() }
            ask(result, onNoAnswer = { result.confirm() }) { answered ->
                AlertDialog.Builder(screen)
                    .setTitle(Address.hostOf(url))
                    .setMessage(message)
                    .setPositiveButton(R.string.ok) { _, _ -> answered(); result.confirm() }
            }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return true.also { result.cancel() }
            ask(result, onNoAnswer = { result.cancel() }) { answered ->
                AlertDialog.Builder(screen)
                    .setTitle(Address.hostOf(url))
                    .setMessage(message)
                    .setPositiveButton(R.string.ok) { _, _ -> answered(); result.confirm() }
                    .setNegativeButton(R.string.cancel) { _, _ -> answered(); result.cancel() }
            }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return true.also { result.cancel() }
            val field = EditText(screen).apply { setText(defaultValue) }
            ask(result, onNoAnswer = { result.cancel() }) { answered ->
                AlertDialog.Builder(screen)
                    .setTitle(Address.hostOf(url))
                    .setMessage(message)
                    .setView(field)
                    .setPositiveButton(R.string.ok) { _, _ -> answered(); result.confirm(field.text.toString()) }
                    .setNegativeButton(R.string.cancel) { _, _ -> answered(); result.cancel() }
            }
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return true.also { result.cancel() }
            ask(result, onNoAnswer = { result.cancel() }) { answered ->
                AlertDialog.Builder(screen)
                    .setTitle(R.string.leave_page_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.leave) { _, _ -> answered(); result.confirm() }
                    .setNegativeButton(R.string.stay) { _, _ -> answered(); result.cancel() }
            }
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return false
            return screen.pickFiles(filePathCallback, fileChooserParams)
        }

        /** Shows a message; if it closes without a button (Back, or the screen closing), the page still gets an answer. */
        private fun ask(result: JsResult, onNoAnswer: () -> Unit, build: (answered: () -> Unit) -> AlertDialog.Builder) {
            var done = false
            val shown = build { done = true }
                .setOnDismissListener {
                    if (!done) {
                        done = true
                        onNoAnswer()
                    }
                    if (dialog === it) dialog = null
                }
                .create()
            dialog?.dismiss()
            dialog = shown
            shown.show()
        }
    }
}
