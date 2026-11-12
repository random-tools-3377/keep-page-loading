package io.github.randomtools3377.keeppageloading

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Holds the pages in an invisible window while the app's screen is closed (swiped away, say).
 *
 * A WebView that isn't in any window is slowed right down, whatever the page is told. So when
 * the screen closes, the pages move into a window on a private display that only this app can
 * see, and move back when the screen opens again. The pages keep their size, so they don't
 * rearrange themselves.
 *
 * The display has nowhere to show anything, so nothing is drawn while the pages are parked,
 * which saves the battery: they run as they do with the screen off. A private display of the
 * app's own needs no permission.
 */
@SuppressLint("StaticFieldLeak") // The window is meant to last exactly as long as the pages are parked.
object Parking {
    private const val TAG = "Parking"

    private var display: VirtualDisplay? = null
    private var window: FrameLayout? = null
    private var windowManager: WindowManager? = null

    /** The window context the pages use while they're parked. */
    var context: Context? = null
        private set

    val isParked: Boolean get() = window != null

    /** Why the last try to make the window didn't work, if it didn't. */
    var lastError: String? = null
        private set

    /**
     * Moves these tabs' pages into the invisible window, making it first if needed. Each page's
     * context is pointed at the window before the page goes in. If the window can't be made, the
     * pages are left out of any window: they still run, only slower.
     */
    fun park(app: Context, tabs: List<Tab>, width: Int, height: Int) {
        val holder = window ?: open(app, width, height)
        if (holder == null) {
            tabs.forEach { it.wrapper?.baseContext = app }
            return
        }
        val windowContext = context ?: app
        for (tab in tabs) {
            val web = tab.web ?: continue
            if (web.parent === holder) continue
            (web.parent as? ViewGroup)?.removeView(web)
            tab.wrapper?.baseContext = windowContext
            holder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        Log.i(TAG, "Parked ${holder.childCount} page(s) on display ${display?.display?.displayId}")
    }

    private fun open(app: Context, width: Int, height: Int): FrameLayout? {
        var virtual: VirtualDisplay? = null
        try {
            val metrics = app.resources.displayMetrics
            val w = (if (width > 0) width else metrics.widthPixels).coerceAtLeast(1)
            val h = (if (height > 0) height else metrics.heightPixels).coerceAtLeast(1)
            // No surface: the display shows nothing, so nothing is drawn for it.
            virtual = app.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("keep-page-loading", w, h, metrics.densityDpi, null, 0)
            val displayContext = app.createDisplayContext(virtual.display)
            val windowContext = if (Build.VERSION.SDK_INT >= 30) {
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null)
            } else {
                displayContext
            }
            val manager = windowContext.getSystemService(WindowManager::class.java)
            val holder = FrameLayout(windowContext)
            manager.addView(
                holder,
                WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE,
                ),
            )
            display = virtual
            window = holder
            windowManager = manager
            context = windowContext
            lastError = null
            Log.i(TAG, "Made the invisible window on display ${virtual.display.displayId} at ${w}x$h")
            return holder
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't make the invisible window", e)
            lastError = e.toString()
            virtual?.release()
            return null
        }
    }

    /** Takes one page out of the invisible window (its tab is closing). */
    fun remove(web: WebView) {
        val holder = window ?: return
        if (web.parent === holder) holder.removeView(web)
    }

    /** Takes every page out of the invisible window, and closes the window. */
    fun leave() {
        val holder = window ?: return
        holder.removeAllViews()
        try {
            windowManager?.removeViewImmediate(holder)
        } catch (e: RuntimeException) {
            // Already gone.
        }
        display?.release()
        display = null
        window = null
        windowManager = null
        context = null
    }
}
