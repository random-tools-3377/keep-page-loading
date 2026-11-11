package io.github.randomtools3377.keeppageloading

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
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

/** The screen: an address bar, a menu, and the page. */
class MainActivity : ComponentActivity() {
    lateinit var pageArea: FrameLayout
        private set
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var startHint: View

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
            if (!focused) showAddress(Page.address)
        }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }

        // Back goes back a page; from the first page it leaves the app, with the page still running.
        onBackPressedDispatcher.addCallback(this) {
            val web = Page.currentView
            if (web != null && web.canGoBack()) web.goBack() else moveTaskToBack(true)
        }

        Page.attach(this, pageArea)
        Page.reopen()
        showAddress(Page.address)
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        if (Page.hasPage) KeepRunningService.start(this)
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        Page.detach(this)
        super.onDestroy()
    }

    private fun go() {
        val target = Address.fromTyped(address.text.toString())
        if (target == null) {
            toast(R.string.not_an_address)
            return
        }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        Page.open(target)
        Page.currentView?.requestFocus()
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
        val web = Page.currentView
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RELOAD, 0, R.string.menu_reload)
            menu.add(0, MENU_FORWARD, 1, R.string.menu_forward).isEnabled = web?.canGoForward() == true
            menu.add(0, MENU_BATTERY, 2, R.string.menu_battery)
            menu.add(0, MENU_STOP, 3, R.string.menu_stop)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RELOAD -> if (Page.currentView?.url == null) Page.reopen() else Page.currentView?.reload()
                    MENU_FORWARD -> Page.currentView?.goForward()
                    MENU_BATTERY -> openBatterySettings()
                    MENU_STOP -> Page.close()
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

    private companion object {
        const val MENU_RELOAD = 1
        const val MENU_FORWARD = 2
        const val MENU_BATTERY = 3
        const val MENU_STOP = 4
    }
}
