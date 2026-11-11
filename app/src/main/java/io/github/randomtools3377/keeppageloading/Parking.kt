package io.github.randomtools3377.keeppageloading

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Holds the page in an invisible window while the app's screen is closed (swiped away, say).
 *
 * A WebView that isn't in any window is slowed right down, whatever the page is told. So when
 * the screen closes, the page moves into a window on a small private display that only this app
 * can see and nothing is shown on, and moves back when the screen opens again. The page keeps
 * its size, so it doesn't rearrange itself.
 *
 * A private display of the app's own needs no permission.
 */
@SuppressLint("StaticFieldLeak") // The window is meant to last exactly as long as the page is parked.
object Parking {
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var window: FrameLayout? = null
    private var windowManager: WindowManager? = null

    /** The window context the page uses while it's parked. */
    var context: Context? = null
        private set

    val isParked: Boolean get() = window != null

    fun park(app: Context, web: WebView, width: Int, height: Int) {
        leave(web)
        val metrics = app.resources.displayMetrics
        val w = (if (width > 0) width else metrics.widthPixels).coerceAtLeast(1)
        val h = (if (height > 0) height else metrics.heightPixels).coerceAtLeast(1)

        // What the page draws goes here and is thrown away straight away, so drawing never
        // waits for room.
        val images = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        images.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))

        val virtual = app.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("keep-page-loading", w, h, metrics.densityDpi, images.surface, 0)
        val displayContext = app.createDisplayContext(virtual.display)
        val windowContext = if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null)
        } else {
            displayContext
        }
        val manager = windowContext.getSystemService(WindowManager::class.java)
        val holder = FrameLayout(windowContext)
        (web.parent as? ViewGroup)?.removeView(web)
        holder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            manager.addView(
                holder,
                WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.OPAQUE,
                ),
            )
        } catch (e: RuntimeException) {
            // The phone wouldn't make the window. The page still runs, only more slowly.
            holder.removeView(web)
            virtual.release()
            images.close()
            return
        }
        display = virtual
        reader = images
        window = holder
        windowManager = manager
        context = windowContext
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
        reader?.close()
        display = null
        reader = null
        window = null
        windowManager = null
        context = null
    }
}
