package io.github.randomtools3377.keeppageloading

import android.content.MutableContextWrapper
import android.os.SystemClock

/** One open page. Every tab keeps running, whether it's on screen or not. */
class Tab(val id: Long) {
    var web: KeepRunningWebView? = null
    var wrapper: MutableContextWrapper? = null
    var startScriptAdded = false

    /** The last web address the tab showed. It's saved, so the tab comes back with the app. */
    var address: String? = null
    var title: String? = null
    var progress = 0

    /** How many times the page has been loaded again after a failed connection. */
    var retriesSoFar = 0

    /** How many times loading the page has failed. */
    var failuresSoFar = 0

    /** The last load failed, and the page is being tried again every 10 seconds. */
    var connectionFailing = false
    var loadFailed = false
    val retry = Runnable {
        retriesSoFar++
        web?.reload()
    }

    /** When the page last changed or talked to its server. */
    @Volatile
    var lastActivityAt = SystemClock.elapsedRealtime()

    /** Nothing on the page has changed for a while, though the connection is fine. */
    var stopped = false

    val crashes = ArrayDeque<Long>()

    /** The tab's site, like "example.com". */
    val host: String? get() = Address.hostOf(address)

    /** What the tab list calls the tab: its page's title, or its site. */
    val label: String? get() = title?.takeIf { it.isNotBlank() } ?: host ?: address
}
