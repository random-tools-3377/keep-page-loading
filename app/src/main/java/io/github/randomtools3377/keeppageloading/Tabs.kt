package io.github.randomtools3377.keeppageloading

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URISyntaxException

/**
 * The open tabs. Every tab keeps running, on screen or not.
 *
 * The tabs live as long as the app, not as long as the screen: MainActivity shows them while
 * it's open, and KeepRunningService keeps the app alive while it's closed or the phone is asleep.
 * Closing the screen leaves every page running, in an invisible window (see Parking); only Stop
 * ends them.
 *
 * On screen, the tab being looked at is in place and the others are moved off the side. They stay
 * in the window, so they keep running at full speed, but nothing is drawn for them.
 *
 * Each page is also watched: if nothing on it changes for a while, and it doesn't talk to its
 * server, while the phone's connection is fine, it seems to have stopped, and the app says so.
 */
@SuppressLint("StaticFieldLeak") // Holds the application, and the open screen only while it's open.
object Tabs {
    private const val LOG = "PageLog"
    private const val OFFSCREEN = 100_000f

    /** How long to wait before loading a page again when the connection fails. */
    const val RETRY_MS = 10_000L

    /** How long a page must go without changing before it seems to have stopped. */
    const val DEFAULT_STOPPED_AFTER_MS = 5 * 60_000L
    const val DEFAULT_WATCH_EVERY_MS = 30_000L

    lateinit var app: Context

    // Shortened by the tests.
    var stoppedAfterMs = DEFAULT_STOPPED_AFTER_MS
    var watchEveryMs = DEFAULT_WATCH_EVERY_MS

    val all = mutableListOf<Tab>()

    /** The tab being looked at. */
    var current: Tab? = null
        private set

    /** The screen showing the tabs, while it's open. */
    var screen: MainActivity? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var nextId = 1L
    private var restored = false

    // The pages' size on the screen, which they keep while they're parked.
    private var lastWidth = 0
    private var lastHeight = 0

    /** Whether there's a page to keep running. */
    val hasPages: Boolean
        get() = if (restored) all.any { it.address != null } else Prefs.tabs.addresses.isNotEmpty()

    /** Opens the tabs saved last time, once. */
    fun restore() {
        if (restored) return
        restored = true
        val saved = Prefs.tabs
        for (address in saved.addresses) newTab(address, select = false, save = false)
        all.getOrNull(saved.current)?.let { current = it }
        showCurrent()
        startWatching()
    }

    /** Shows the tabs on the screen. */
    fun attach(screen: MainActivity) {
        this.screen = screen
        Parking.leave()
        for (tab in all) addToScreen(tab, screen)
        restore()
        showCurrent()
    }

    /** The screen is closing. The pages carry on without it, in their invisible window. */
    fun detach(screen: MainActivity) {
        if (this.screen !== screen) return
        Log.i(LOG, "The screen closed; parking ${all.size} page(s)")
        dialog?.dismiss()
        dialog = null
        this.screen = null
        for (tab in all) {
            val web = tab.web ?: continue
            web.setOnCreateContextMenuListener(null)
            (web.parent as? ViewGroup)?.removeView(web)
        }
        if (all.isNotEmpty()) Parking.park(app, all, lastWidth, lastHeight)
    }

    /** Opens a new tab, with [address] if there is one. */
    fun newTab(address: String? = null, select: Boolean = true, save: Boolean = true): Tab {
        restore()
        val tab = Tab(nextId++)
        create(tab)
        all += tab
        place(tab)
        if (address != null) load(tab, address, save = false)
        if (select || current == null) current = tab
        showCurrent()
        if (save) save()
        startWatching()
        KeepRunningService.refresh(app)
        return tab
    }

    /** Switches to [tab]. */
    fun select(tab: Tab?) {
        current = tab
        showCurrent()
        save()
    }

    /** Opens an address the user typed, in the tab being looked at. */
    fun open(address: String) {
        val tab = current
        if (tab == null) newTab(address) else load(tab, address)
    }

    fun load(tab: Tab, address: String, save: Boolean = true) {
        handler.removeCallbacks(tab.retry)
        tab.connectionFailing = false
        tab.address = address
        markActive(tab)
        tab.web?.loadUrl(address)
        if (save) save()
        if (tab === current) screen?.showAddress(address)
        KeepRunningService.refresh(app)
    }

    fun closeTab(tab: Tab) {
        val index = all.indexOf(tab)
        if (index < 0) return
        handler.removeCallbacks(tab.retry)
        destroyWeb(tab)
        all.removeAt(index)
        if (current === tab) current = all.getOrNull(index) ?: all.getOrNull(index - 1)
        if (all.isEmpty()) Parking.leave()
        showCurrent()
        save()
        if (!hasPages) {
            @Suppress("ImplicitSamInstance") // The Intent names the service, which is all stopService needs.
            app.stopService(Intent(app, KeepRunningService::class.java))
        }
        stoppedChanged()
    }

    /** Stop: every page is closed, the app stops keeping them running, and the screen closes. */
    fun closeAll() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        Parking.leave()
        all.forEach { destroyWeb(it) }
        all.clear()
        current = null
        // The tabs stay saved, so they open again next time.
        restored = false
        CookieManager.getInstance().flush()
        KeepRunningService.clearStoppedAlert(app)
        @Suppress("ImplicitSamInstance") // The Intent names the service, which is all stopService needs.
        app.stopService(Intent(app, KeepRunningService::class.java))
        screen?.finishAndRemoveTask()
    }

    private fun save() {
        if (!restored) return
        val saved = all.filter { it.address != null }
        Prefs.tabs = Prefs.SavedTabs(saved.mapNotNull { it.address }, saved.indexOf(current).coerceAtLeast(0))
    }

    /** The tab being looked at is in place; the others are moved off the side, still running. */
    private fun showCurrent() {
        for (tab in all) {
            val web = tab.web ?: continue
            val shown = tab === current
            web.translationX = if (shown) 0f else OFFSCREEN
            web.importantForAccessibility =
                if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val screen = screen ?: return
        val tab = current
        screen.showAddress(tab?.address)
        screen.showProgress(tab?.progress ?: 0)
        screen.refreshTabs()
    }

    /** Puts a new page on the screen, or in the invisible window while the screen is closed. */
    private fun place(tab: Tab) {
        val screen = screen
        if (screen != null) addToScreen(tab, screen) else Parking.park(app, listOf(tab), lastWidth, lastHeight)
    }

    private fun addToScreen(tab: Tab, screen: MainActivity) {
        val web = tab.web ?: return
        tab.wrapper?.baseContext = screen
        (web.parent as? ViewGroup)?.removeView(web)
        screen.pageArea.addView(web, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.setOnCreateContextMenuListener(screen)
    }

    private fun destroyWeb(tab: Tab) {
        val web = tab.web ?: return
        Parking.remove(web)
        (web.parent as? ViewGroup)?.removeView(web)
        web.setOnCreateContextMenuListener(null)
        web.stopLoading()
        web.destroy()
        tab.web = null
        tab.wrapper = null
    }

    @SuppressLint("SetJavaScriptEnabled") // Pages need it; it's what keeps them running.
    private fun create(tab: Tab) {
        val wrapper = MutableContextWrapper(screen ?: Parking.context ?: app)
        val web = KeepRunningWebView(wrapper)
        tab.wrapper = wrapper
        tab.web = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Links a site opens in a new window become new tabs (only when tapped).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
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
        // The page tells the app when it changes (see StayActive), for spotting a stopped page.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, StayActive.ACTIVITY_OBJECT, setOf("*")) { _, _, _, _, _ ->
                markActive(tab)
            }
        }
        // On phones whose WebView is too old for this, the script is added as each page starts.
        tab.startScriptAdded = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, StayActive.SCRIPT, setOf("*"))
            tab.startScriptAdded = true
        }
        web.webViewClient = TabClient(tab)
        web.webChromeClient = TabChromeClient(tab)
        web.setDownloadListener { url, _, _, _, _ -> openOutside(tab, url) }
        web.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (!Parking.isParked && right > left && bottom > top) {
                lastWidth = right - left
                lastHeight = bottom - top
            }
        }
    }

    private fun addressChanged(tab: Tab, url: String?) {
        if (url == null || !Address.isWebAddress(url)) return
        tab.address = url
        save()
        if (tab === current) screen?.showAddress(url)
        screen?.refreshTabs()
        KeepRunningService.refresh(app)
    }

    /** Opens a link the page can't, like an email address or an app's link, in another app. */
    private fun openOutside(tab: Tab, link: String) {
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
            if (fallback != null && Address.isWebAddress(fallback)) tab.web?.loadUrl(fallback)
            else screen.toast(R.string.no_app_for_link)
        }
    }

    /** The connection failed: try again in 10 seconds, and every 10 seconds until it works. */
    private fun retryLater(tab: Tab) {
        handler.removeCallbacks(tab.retry)
        handler.postDelayed(tab.retry, RETRY_MS)
    }

    /** The pages' process stopped (Android closed it, or it crashed). Make the tab's page again. */
    private fun rendererGone(tab: Tab, dead: WebView, crashed: Boolean, priority: Int) {
        Log.w(LOG, "The page's process stopped: crashed=$crashed, priority=$priority, parked=${Parking.isParked}")
        if (tab.web !== dead) {
            dead.destroy()
            return
        }
        Parking.remove(dead)
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        tab.web = null
        val now = SystemClock.elapsedRealtime()
        tab.crashes.addLast(now)
        while (tab.crashes.isNotEmpty() && tab.crashes.first() < now - 60_000L) tab.crashes.removeFirst()
        create(tab)
        place(tab)
        showCurrent()
        if (tab.crashes.size > 3) {
            if (tab === current) screen?.toast(R.string.page_crashed_again)
        } else {
            tab.address?.let { tab.web?.loadUrl(it) }
            if (tab === current) screen?.toast(R.string.page_crashed)
        }
    }

    // ----- Spotting a page that has stopped -----

    private val watch = object : Runnable {
        override fun run() {
            checkForStops()
            handler.postDelayed(this, watchEveryMs)
        }
    }

    fun startWatching() {
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, watchEveryMs)
    }

    /** The page changed, or talked to its server. */
    fun markActive(tab: Tab) {
        tab.lastActivityAt = SystemClock.elapsedRealtime()
        if (tab.stopped) {
            tab.stopped = false
            Log.i(LOG, "A page is changing again")
            stoppedChanged()
        }
    }

    /** Looks for pages that have gone quiet while the connection is fine. */
    fun checkForStops() {
        val now = SystemClock.elapsedRealtime()
        val online = isOnline()
        val newlyStopped = mutableListOf<Tab>()
        var changed = false
        for (tab in all) {
            if (tab.address == null || tab.web == null) continue
            val stopped = online && !tab.connectionFailing && now - tab.lastActivityAt >= stoppedAfterMs
            if (stopped != tab.stopped) {
                tab.stopped = stopped
                changed = true
                if (stopped) newlyStopped += tab
            }
        }
        if (newlyStopped.isNotEmpty()) {
            Log.i(LOG, "${newlyStopped.size} page(s) seem to have stopped")
            KeepRunningService.alertStopped(app, newlyStopped)
        }
        if (changed) stoppedChanged()
    }

    private fun stoppedChanged() {
        if (all.none { it.stopped }) KeepRunningService.clearStoppedAlert(app)
        KeepRunningService.refresh(app)
        screen?.refreshTabs()
    }

    /** Whether the phone has a working connection to the internet. */
    fun isOnline(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** How long, in whole minutes (at least 1), the tab's page has been quiet. */
    fun quietMinutes(tab: Tab): Int =
        ((SystemClock.elapsedRealtime() - tab.lastActivityAt) / 60_000L).toInt().coerceAtLeast(1)

    /** A short word on how the tab is doing. */
    fun statusOf(context: Context, tab: Tab): String = when {
        tab.stopped -> context.resources.getQuantityString(R.plurals.status_stopped, quietMinutes(tab), quietMinutes(tab))
        tab.connectionFailing -> context.getString(R.string.status_no_connection)
        tab.address == null -> context.getString(R.string.status_blank)
        else -> context.getString(R.string.status_running)
    }

    // ----- The pages' own messages -----

    private class TabClient(private val tab: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme in setOf("http", "https", "about", "data", "blob")) return false
            if (request.isForMainFrame && request.hasGesture()) openOutside(tab, request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            handler.removeCallbacks(tab.retry)
            tab.loadFailed = false
            markActive(tab)
            if (!tab.startScriptAdded) view.evaluateJavascript(StayActive.SCRIPT, null)
            if (tab === current) screen?.showAddress(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!tab.loadFailed && tab.connectionFailing) {
                tab.connectionFailing = false
                stoppedChanged()
            }
            if (!tab.startScriptAdded) view.evaluateJavascript(StayActive.SCRIPT, null)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            addressChanged(tab, url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            tab.failuresSoFar++
            tab.loadFailed = true
            if (error.errorCode in retryErrors) {
                if (!tab.connectionFailing) {
                    tab.connectionFailing = true
                    stoppedChanged()
                }
                retryLater(tab)
            }
        }

        // Called on a background thread for everything the page loads: talking to its server
        // counts as the page doing something.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            tab.lastActivityAt = SystemClock.elapsedRealtime()
            if (tab.stopped) handler.post { markActive(tab) }
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            rendererGone(tab, view, detail.didCrash(), detail.rendererPriorityAtExit())
            return true
        }
    }

    private val retryErrors = setOf(
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_UNKNOWN,
    )

    /**
     * Messages from the page (alert, confirm, prompt). They're shown while the app is on screen.
     * While it isn't, they're answered straight away (OK for a message, Cancel for a question),
     * because a message waiting for an answer would stop the page.
     */
    private class TabChromeClient(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (tab === current) screen?.showProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title
            screen?.refreshTabs()
        }

        /** A tapped link the site opens in a new window: it opens in a new tab. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val opened = newTab(null, select = true)
            transport.webView = opened.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            all.firstOrNull { it.web === window }?.let { closeTab(it) }
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return true.also { result.confirm() }
            ask(onNoAnswer = { result.confirm() }) { answered ->
                AlertDialog.Builder(screen)
                    .setTitle(Address.hostOf(url))
                    .setMessage(message)
                    .setPositiveButton(R.string.ok) { _, _ -> answered(); result.confirm() }
            }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val screen = screen?.takeIf { it.isShowing } ?: return true.also { result.cancel() }
            ask(onNoAnswer = { result.cancel() }) { answered ->
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
            ask(onNoAnswer = { result.cancel() }) { answered ->
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
            ask(onNoAnswer = { result.cancel() }) { answered ->
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
        private fun ask(onNoAnswer: () -> Unit, build: (answered: () -> Unit) -> AlertDialog.Builder) {
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
