package io.github.randomtools3377.keeppageloading

import android.app.Notification
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks what this app is for: pages keep running with the screen off, and after the app is
 * closed, in every tab.
 *
 * The test page behaves like the sites this is for. It counts all the time (a timer ten times a
 * second, and an animation loop), and like many sites it pauses itself whenever it's hidden or
 * loses focus. The tests turn the emulator's screen off, send it into deep sleep, swipe the app
 * away, and check the pages carried on at full speed and never noticed.
 *
 * The same page in an ordinary WebView, beside them, shows the problem: it notices and pauses.
 */
@RunWith(AndroidJUnit4::class)
class KeepsRunningTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun cleanUp() {
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        wake()
        instrumentation.runOnMainSync {
            Tabs.stoppedAfterMs = Tabs.DEFAULT_STOPPED_AFTER_MS
            Tabs.watchEveryMs = Tabs.DEFAULT_WATCH_EVERY_MS
            Tabs.closeAll()
        }
        Prefs.clearTabs()
    }

    @Test
    fun keepsRunningWithTheScreenOff() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var shown: Tab
            lateinit var behind: Tab
            lateinit var ordinary: WebView
            scenario.onActivity { screen ->
                shown = testTab()
                behind = testTab(select = false)
                ordinary = WebView(screen).apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    loadTestPage(this)
                }
                screen.pageArea.addView(ordinary, FrameLayout.LayoutParams(300, 300))
                KeepRunningService.start(screen)
            }
            Thread.sleep(5_000)
            val before = report(shown.web!!)
            val behindBefore = report(behind.web!!)
            val ordinaryBefore = report(ordinary)
            assertTrue("The test page isn't counting: $before", before.getInt("counted") > 20)

            screenOffAndDeepSleep()
            Thread.sleep(120_000)
            Log.i(TAG, "The screen is ${scenario.state} (CREATED means it's in the background)")
            val after = report(shown.web!!)
            val behindAfter = report(behind.web!!)
            val ordinaryAfter = report(ordinary)
            val service = serviceState()
            wake()

            Log.i(TAG, "This app's page: before $before, after $after")
            Log.i(TAG, "A tab behind it: before $behindBefore, after $behindAfter")
            Log.i(TAG, "Ordinary WebView: before $ordinaryBefore, after $ordinaryAfter")

            // The ordinary WebView shows what happens in a phone's browser: the page was told it
            // was hidden, and paused itself.
            assertTrue(
                "The page in an ordinary WebView should have been told the screen went off; " +
                    "before $ordinaryBefore, after $ordinaryAfter",
                ordinaryAfter.getInt("hidden") > ordinaryBefore.getInt("hidden"),
            )
            // This app's pages didn't notice, and kept going at full speed: the tab on screen
            // and the tab behind it.
            checkKeptRunning(before, after, seconds = 120)
            checkKeptRunning(behindBefore, behindAfter, seconds = 120)
            assertTrue("The notification should be keeping the app running: $service", "isForeground=true" in service)
        }
    }

    @Test
    fun keepsRunningAfterTheAppIsClosed() {
        // Swiped away and opened again three times, with two tabs: both pages must keep going
        // each time, and be the same pages when the app opens again, not loaded afresh.
        lateinit var first: Tab
        lateinit var second: Tab
        var last: Pair<JSONObject, JSONObject>? = null
        repeat(3) { round ->
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { screen ->
                if (round == 0) {
                    first = testTab()
                    second = testTab(select = false)
                }
                KeepRunningService.start(screen)
            }
            Thread.sleep(3_000)
            val shown = report(first.web!!) to report(second.web!!)
            last?.let { (a, b) ->
                assertTrue(
                    "Opening the app again should show the same pages, still counting: before $a and $b, now $shown",
                    shown.first.getInt("counted") > a.getInt("counted") && shown.second.getInt("counted") > b.getInt("counted"),
                )
            }

            scenario.close()
            Thread.sleep(2_000)
            var screenLeft: MainActivity? = null
            var parked = false
            var parkingError: String? = null
            instrumentation.runOnMainSync {
                screenLeft = Tabs.screen
                parked = Parking.isParked
                parkingError = Parking.lastError
            }
            assertNull("The screen should be closed", screenLeft)
            assertTrue("The pages should be in their invisible window: $parkingError", parked)

            val before = report(first.web!!) to report(second.web!!)
            Thread.sleep(20_000)
            val after = report(first.web!!) to report(second.web!!)
            Log.i(TAG, "With the app closed, round ${round + 1}: before $before, after $after")
            checkKeptRunning(before.first, after.first, seconds = 20)
            checkKeptRunning(before.second, after.second, seconds = 20)
            last = after
        }
        val service = serviceState()
        assertTrue("The notification should be keeping the app running: $service", "isForeground=true" in service)
    }

    @Test
    fun triesAgainEvery10SecondsWhenTheConnectionFails() {
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var tab: Tab
            instrumentation.runOnMainSync {
                // An address that can never be reached, like a connection that's down.
                Tabs.open(UNREACHABLE_ADDRESS)
                tab = Tabs.current!!
            }
            Thread.sleep(35_000)
            var retries = 0
            var failures = 0
            var failing = false
            instrumentation.runOnMainSync {
                retries = tab.retriesSoFar
                failures = tab.failuresSoFar
                failing = tab.connectionFailing
            }
            Log.i(TAG, "Unreachable page: $failures failures, $retries tries again in 35 s")
            // Every 10 seconds: 3 times in 35 seconds, or 2 if each failure took a few seconds.
            assertTrue("In 35 seconds it should have tried again 2 or 3 times, not $retries", retries in 2..3)
            assertTrue("Each try should have reached for the page again: $failures failures after $retries tries", failures >= retries)
            assertTrue("The tab should say the connection is failing", failing)
        }
    }

    @Test
    fun aTappedLinkThatOpensANewWindowOpensANewTab() {
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var first: Tab
            instrumentation.runOnMainSync {
                first = Tabs.newTab(null)
                first.web!!.loadDataWithBaseURL(TEST_ADDRESS, LINK_PAGE, "text/html", "utf-8", TEST_ADDRESS)
            }
            Thread.sleep(3_000)
            tapMiddleOf(first.web!!)
            Thread.sleep(3_000)
            var count = 0
            var current: Tab? = null
            var address: String? = null
            instrumentation.runOnMainSync {
                count = Tabs.all.size
                current = Tabs.current
                address = Tabs.current?.web?.url
            }
            assertEquals("Tapping the link should have opened a second tab", 2, count)
            assertTrue("The new tab should be the one shown", current !== first)
            assertTrue("The new tab should have the link's page, not $address", address?.contains("/second") == true)
        }
    }

    @Test
    fun saysWhenAPageStopsAndWhenItCarriesOn() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assertTrue(
            "The emulator has no working connection, so a stopped page can't be told from a dropped one",
            Tabs.isOnline(),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var still: Tab
            lateinit var counting: Tab
            scenario.onActivity { screen ->
                Tabs.stoppedAfterMs = 15_000
                Tabs.watchEveryMs = 2_000
                still = Tabs.newTab(null)
                still.web!!.loadDataWithBaseURL(STILL_ADDRESS, STILL_PAGE, "text/html", "utf-8", STILL_ADDRESS)
                counting = testTab(select = false)
                Tabs.startWatching()
                KeepRunningService.start(screen)
            }
            Thread.sleep(25_000)
            var stillStopped = false
            var countingStopped = true
            instrumentation.runOnMainSync {
                stillStopped = still.stopped
                countingStopped = counting.stopped
            }
            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            val alert = notifications.firstOrNull { it.id == KeepRunningService.STOPPED_NOTIFICATION_ID }
            val keeping = notifications.firstOrNull { it.id == KeepRunningService.NOTIFICATION_ID }
            val keepingTitle = keeping?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            Log.i(TAG, "Stopped: still page $stillStopped, counting page $countingStopped; notification '$keepingTitle'")

            assertTrue("The page where nothing changes should seem to have stopped", stillStopped)
            assertFalse("The counting page should not seem to have stopped", countingStopped)
            assertTrue("A 'page stopped' alert should be showing", alert != null)
            assertEquals(
                "The notification should say a page seems to have stopped",
                context.resources.getQuantityString(R.plurals.notification_title_stopped, 1, 1),
                keepingTitle,
            )

            // The page changes again: the alert goes.
            instrumentation.runOnMainSync {
                still.web!!.evaluateJavascript("document.body.appendChild(document.createElement('p'))", null)
            }
            Thread.sleep(3_000)
            instrumentation.runOnMainSync { stillStopped = still.stopped }
            val alertAfter = context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.id == KeepRunningService.STOPPED_NOTIFICATION_ID }
            assertFalse("Once the page changes it should no longer seem stopped", stillStopped)
            assertNull("The 'page stopped' alert should be gone", alertAfter)
        }
    }

    private fun checkKeptRunning(before: JSONObject, after: JSONObject, seconds: Int) {
        val pauses = after.getInt("pauses") - before.getInt("pauses")
        val hidden = after.getInt("hidden") - before.getInt("hidden")
        val counted = after.getInt("counted") - before.getInt("counted")
        val frames = after.getInt("frames") - before.getInt("frames")
        assertEquals("The page was told it was hidden: before $before, after $after", 0, hidden)
        assertEquals("The page noticed it was hidden or lost focus, and paused: before $before, after $after", 0, pauses)
        // The timer runs 10 times a second. A page slowed down in the background gets 1.
        assertTrue("The page's timer slowed down: $counted in $seconds s (before $before, after $after)", counted >= seconds * 10 * 3 / 4)
        // With nothing drawn, animation frames come at least 5 times a second.
        val framesPerSecond = 1000 / StayActive.FRAME_FALLBACK_MS
        assertTrue("The page's animation loop stopped: $frames in $seconds s (before $before, after $after)", frames >= seconds * framesPerSecond / 2)
        assertTrue("The page stalled for ${after.getLong("longestGapMs")} ms", after.getLong("longestGapMs") <= 5_000)
    }

    /** A new tab with the counting test page. Called on the main thread. */
    private fun testTab(select: Boolean = true): Tab {
        val tab = Tabs.newTab(null, select = select)
        loadTestPage(tab.web!!)
        return tab
    }

    private fun loadTestPage(web: WebView) {
        web.loadDataWithBaseURL(TEST_ADDRESS, TEST_PAGE, "text/html", "utf-8", TEST_ADDRESS)
    }

    private fun tapMiddleOf(web: WebView) {
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val place = IntArray(2)
            web.getLocationOnScreen(place)
            x = place[0] + web.width / 2f
            y = place[1] + web.height / 2f
        }
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** The test page's counts so far. */
    private fun report(web: WebView): JSONObject {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            web.evaluateJavascript("report()") {
                result = it
                done.countDown()
            }
        }
        assertTrue("The page didn't answer", done.await(30, TimeUnit.SECONDS))
        assertTrue("The page gave no counts: $result", result.startsWith("{"))
        return JSONObject(result)
    }

    private fun screenOffAndDeepSleep() {
        shell("input keyevent KEYCODE_SLEEP")
        waitFor("the screen to go off") { "mWakefulness=Awake" !in shell("dumpsys power") }
        shell("dumpsys battery unplug")
        shell("dumpsys deviceidle enable deep")
        Log.i(TAG, "Deep sleep: " + shell("dumpsys deviceidle force-idle deep").trim())
    }

    private fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    private fun serviceState(): String =
        shell("dumpsys activity services io.github.randomtools3377.keeppageloading/.KeepRunningService")

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!check()) {
            assertTrue("Timed out waiting for $what", System.currentTimeMillis() < until)
            Thread.sleep(250)
        }
    }

    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val TAG = "KeepsRunningTest"
        const val TEST_ADDRESS = "https://keep-page-loading.test/"
        const val STILL_ADDRESS = "https://keep-page-loading.test/still"
        const val UNREACHABLE_ADDRESS = "https://nothing-here.keep-page-loading.invalid/"

        /** A page that never changes. */
        const val STILL_PAGE = """<!doctype html><html><body><p>Nothing changes here.</p></body></html>"""

        /** A page that is one big link, opened in a new window. */
        const val LINK_PAGE = """<!doctype html>
<html><body style="margin:0"><a href="https://keep-page-loading.test/second" target="_blank"
 style="display:block;width:100vw;height:100vh;background:#cde">Open</a></body></html>"""

        const val TEST_PAGE = """<!doctype html>
<html><body><h1 id="n">0</h1><script>
  var s = { counted: 0, frames: 0, pauses: 0, hidden: 0, longestGapMs: 0 };
  var paused = false;
  var last = Date.now();
  function pause() { if (!paused) { paused = true; s.pauses++; } }
  function resume() { paused = false; }
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { s.hidden++; pause(); } else { resume(); }
  });
  window.addEventListener('blur', pause);
  window.addEventListener('focus', resume);
  setInterval(function () {
    var now = Date.now();
    s.longestGapMs = Math.max(s.longestGapMs, now - last);
    last = now;
    if (!paused && !document.hidden) {
      s.counted++;
      document.getElementById('n').textContent = s.counted;
    }
  }, 100);
  function frame() {
    if (!paused && !document.hidden) s.frames++;
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  window.report = function () { return s; };
</script></body></html>"""
    }
}
