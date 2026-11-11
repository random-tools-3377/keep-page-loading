package io.github.randomtools3377.keeppageloading

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that always tells the page it is on screen.
 *
 * When the screen goes off or the app is left, Android tells the WebView its window is hidden,
 * and the WebView then tells the page, which slows its timers right down. This WebView passes
 * "visible" on instead, so the page keeps its full speed.
 */
class KeepRunningWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
