package io.github.randomtools3377.keeppageloading

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
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
 * Holds the page in an invisible window while the app's screen is closed (swiped away, say).
 *
 * A WebView that isn't in any window is slowed right down, whatever the page is told. So when
 * the screen closes, the page moves into a window on a private display that only this app can
 * see, and moves back when the screen opens again. The page keeps its size, so it doesn't
 * rearrange itself.
 *
 * The display has nowhere to show anything, so nothing is drawn while the page is parked, which
 * saves the battery: the page runs as it does with the screen off. A private display of the
 * app's own needs no permission.
 */
@SuppressLint("StaticFieldLeak") // The window is meant to last exactly as long as the page is parked.
object Parking {
    private var display: VirtualDisplay? = null
    private var window: FrameLayout? = null
    private var windowManager: WindowManager? = null

    private const val TAG = "Parking"

    /** The window context the page uses while it's parked. */
    var context: Context? = null
        private set

    val isParked: Boolean get() = window != null

    /** Why the last try to park the page didn't work, if it didn't. */
    var lastError: String? = null
        private set

    /**
     * Moves the page into its invisible window. [wrapper] is the page's context, which is
     * pointed at the window first, so the page is never in a window it doesn't belong to.
     * If anything goes wrong, the page is left out of any window: it still runs, only slower.
     */
    fun park(app: Context, web: WebView, wrapper: MutableContextWrapper?, width: Int, height: Int) {
        leave(web)
        var virtual: VirtualDisplay? = null
        val holder: FrameLayout
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
            holder = FrameLayout(windowContext)
            (web.parent as? ViewGroup)?.removeView(web)
            wrapper?.baseContext = windowContext
            holder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            Log.i(TAG, "Parked the page on display ${virtual.display.displayId} at ${w}x$h")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't park the page", e)
            lastError = e.toString()
            (web.parent as? ViewGroup)?.removeView(web)
            wrapper?.baseContext = app
            virtual?.release()
        }
    }

    /** Takes the page out of its invisible window, and closes the window. */
    fun leave(web: WebView?) {
        val holder = window ?: return
        if (web != null && web.parent === holder) holder.removeView(web)
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
