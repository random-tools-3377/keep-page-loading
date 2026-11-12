package io.github.randomtools3377.keeppageloading

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextMenu
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

/** The screen: an address bar, the tabs button, a menu, and the page of the tab being looked at. */
class MainActivity : ComponentActivity() {
    lateinit var pageArea: FrameLayout
        private set
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var startHint: View
    private lateinit var tabCount: TextView
    private lateinit var tabList: View
    private lateinit var tabRows: LinearLayout

    /** Whether the screen is in front of the user right now. */
    var isShowing = false
        private set

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val picked = if (result.resultCode != Activity.RESULT_OK) {
            null
        } else {
            data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        }
        fileCallback?.onReceiveValue(picked)
        fileCallback = null
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBattery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pageArea = findViewById(R.id.page_area)
        address = findViewById(R.id.address)
        progress = findViewById(R.id.progress)
        startHint = findViewById(R.id.start_hint)
        tabCount = findViewById(R.id.tab_count)
        tabList = findViewById(R.id.tab_list)
        tabRows = findViewById(R.id.tab_rows)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }

        address.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go()
                true
            } else {
                false
            }
        }
        address.setOnFocusChangeListener { _, focused ->
            if (!focused) showAddress(Tabs.current?.address)
        }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.tabs).setOnClickListener { if (tabList.isVisible) hideTabList() else showTabList() }
        findViewById<View>(R.id.new_tab).setOnClickListener { openBlankTab() }

        // Back closes the tab list, then goes back a page; from a tab's first page it leaves the
        // app, with every page still running.
        onBackPressedDispatcher.addCallback(this) {
            val web = Tabs.current?.web
            when {
                tabList.isVisible -> hideTabList()
                web != null && web.canGoBack() -> web.goBack()
                else -> moveTaskToBack(true)
            }
        }

        Tabs.attach(this)
        showAddress(Tabs.current?.address)
        selectTabFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        selectTabFrom(intent)
    }

    /** The "page stopped" notification opens the app on that page's tab. */
    private fun selectTabFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_TAB_ID, -1L) ?: -1L
        if (id < 0) return
        Tabs.all.firstOrNull { it.id == id }?.let {
            hideTabList()
            Tabs.select(it)
        }
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        if (Tabs.hasPages) KeepRunningService.start(this)
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        Tabs.detach(this)
        super.onDestroy()
    }

    private fun go() {
        val target = Address.fromTyped(address.text.toString())
        if (target == null) {
            toast(R.string.not_an_address)
            return
        }
        hideKeyboard()
        address.clearFocus()
        Tabs.open(target)
        Tabs.current?.web?.requestFocus()
        showAddress(target)
        KeepRunningService.start(this)
        askSetupOnce()
    }

    fun showAddress(url: String?) {
        startHint.isVisible = url == null
        if (!address.hasFocus()) address.setText(url ?: "")
    }

    fun showProgress(percent: Int) {
        progress.progress = percent
        progress.visibility = if (percent in 1..99) View.VISIBLE else View.INVISIBLE
    }

    fun toast(text: Int) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
    }

    // ----- Tabs -----

    /** The tabs changed: the count on the tabs button, and the list if it's open. */
    fun refreshTabs() {
        tabCount.text = Tabs.all.size.toString()
        if (tabList.isVisible) buildTabRows()
    }

    private fun showTabList() {
        hideKeyboard()
        address.clearFocus()
        buildTabRows()
        tabList.isVisible = true
    }

    private fun hideTabList() {
        tabList.isVisible = false
    }

    private fun openBlankTab() {
        hideTabList()
        Tabs.newTab(null)
        address.setText("")
        address.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(address, 0)
    }

    private fun buildTabRows() {
        tabRows.removeAllViews()
        for (tab in Tabs.all) {
            val current = tab === Tabs.current
            val name = tab.label ?: getString(R.string.new_tab_page)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(4), dp(10))
                if (current) setBackgroundColor(getColor(R.color.field))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    hideTabList()
                    Tabs.select(tab)
                }
            }
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(TextView(this).apply {
                text = name
                setTextColor(getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                if (current) setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            words.addView(TextView(this).apply {
                text = listOfNotNull(tab.host, Tabs.statusOf(this@MainActivity, tab)).joinToString(" · ")
                setTextColor(getColor(if (tab.stopped) R.color.accent else R.color.text_quiet))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                contentDescription = getString(R.string.close_tab_named, name)
                setOnClickListener { Tabs.closeTab(tab) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            tabRows.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Long-pressing a link on a page: open it in a new tab, or copy it. */
    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        val web = view as? WebView ?: return
        val hit = web.hitTestResult
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE && hit.type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return
        menu.add(R.string.open_in_new_tab).setOnMenuItemClickListener {
            linkOf(web, hit) { Tabs.newTab(it, select = true) }
            true
        }
        menu.add(R.string.open_in_background_tab).setOnMenuItemClickListener {
            linkOf(web, hit) {
                Tabs.newTab(it, select = false)
                Toast.makeText(this, R.string.opened_in_background_tab, Toast.LENGTH_SHORT).show()
            }
            true
        }
        menu.add(R.string.copy_link).setOnMenuItemClickListener {
            linkOf(web, hit) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newRawUri(null, it.toUri()))
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    /** The web address of a long-pressed link. A link around a picture has to be asked for. */
    private fun linkOf(web: WebView, hit: WebView.HitTestResult, use: (String) -> Unit) {
        fun useIfWeb(link: String?) {
            if (link != null && Address.isWebAddress(link)) use(link) else toast(R.string.no_app_for_link)
        }
        if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            useIfWeb(hit.extra)
        } else {
            val answer = Handler(Looper.getMainLooper()) { message ->
                useIfWeb(message.data.getString("url"))
                true
            }
            web.requestFocusNodeHref(answer.obtainMessage())
        }
    }

    /** A page asked for a file to upload. */
    fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            filePicker.launch(params.createIntent())
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            toast(R.string.no_file_picker)
            false
        }
    }

    private fun showMenu(anchor: View) {
        val tab = Tabs.current
        val web = tab?.web
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RELOAD, 0, R.string.menu_reload).isEnabled = tab?.address != null
            menu.add(0, MENU_FORWARD, 1, R.string.menu_forward).isEnabled = web?.canGoForward() == true
            menu.add(0, MENU_NEW_TAB, 2, R.string.menu_new_tab)
            menu.add(0, MENU_CLOSE_TAB, 3, R.string.menu_close_tab).isEnabled = tab != null
            menu.add(0, MENU_BATTERY, 4, R.string.menu_battery)
            menu.add(0, MENU_STOP, 5, R.string.menu_stop)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RELOAD -> tab?.address?.let { if (web?.url == null) Tabs.load(tab, it) else web.reload() }
                    MENU_FORWARD -> web?.goForward()
                    MENU_NEW_TAB -> openBlankTab()
                    MENU_CLOSE_TAB -> tab?.let { Tabs.closeTab(it) }
                    MENU_BATTERY -> openBatterySettings()
                    MENU_STOP -> Tabs.closeAll()
                }
                true
            }
            show()
        }
    }

    /** The first time a page is opened: may it show its notification, and may it run in the background? */
    private fun askSetupOnce() {
        if (Prefs.askedSetup) return
        Prefs.askedSetup = true
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBattery()
        }
    }

    private fun isLeftOutOfBatterySaving(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // Asking to be left out of battery saving is the point of this app.
    @SuppressLint("BatteryLife")
    private fun askBattery() {
        if (isLeftOutOfBatterySaving()) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    /** From the menu: ask again, or, if that's done, open the app's settings for the phone's own battery options. */
    private fun openBatterySettings() {
        if (!isLeftOutOfBatterySaving()) askBattery() else openAppSettings()
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            toast(R.string.battery_settings_hint)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.battery_settings_missing)
        }
    }

    companion object {
        /** Opens the app on this tab (from the "page stopped" notification). */
        const val EXTRA_TAB_ID = "io.github.randomtools3377.keeppageloading.TAB_ID"

        private const val MENU_RELOAD = 1
        private const val MENU_FORWARD = 2
        private const val MENU_NEW_TAB = 3
        private const val MENU_CLOSE_TAB = 4
        private const val MENU_BATTERY = 5
        private const val MENU_STOP = 6
    }
}
