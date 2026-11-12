package io.github.randomtools3377.keeppageloading

import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks the one thing this app is for: a page keeps running with the screen off, and after
 * the app is closed.
 *
 * The test page behaves like the sites this is for. It counts all the time (a timer ten times a
 * second, and an animation loop), and like many sites it pauses itself whenever it's hidden or
 * loses focus. The test turns the emulator's screen off, sends it into deep sleep, waits, and
 * checks the page carried on at full speed and never noticed.
 *
 * The same page in an ordinary WebView, beside it, shows the problem: it notices and pauses.
 */
@RunWith(AndroidJUnit4::class)
class KeepsRunningTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun cleanUp() {
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        wake()
        instrumentation.runOnMainSync { Page.close() }
        Prefs.lastAddress = null
    }

    @Test
    fun keepsRunningWithTheScreenOff() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var ordinary: WebView
            scenario.onActivity { screen ->
                loadTestPage(Page.currentView!!)
                ordinary = WebView(screen).apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    loadTestPage(this)
                }
                screen.pageArea.addView(ordinary, FrameLayout.LayoutParams(300, 300))
                KeepRunningService.start(screen)
            }
            Thread.sleep(5_000)
            val before = report(Page.currentView!!)
            val ordinaryBefore = report(ordinary)
            assertTrue("The test page isn't counting: $before", before.getInt("counted") > 20)

            screenOffAndDeepSleep()
            Thread.sleep(120_000)
            Log.i(TAG, "The screen is ${scenario.state} (CREATED means it's in the background)")
            val after = report(Page.currentView!!)
            val ordinaryAfter = report(ordinary)
            val service = serviceState()
            wake()

            Log.i(TAG, "This app's page: before $before, after $after")
            Log.i(TAG, "Ordinary WebView: before $ordinaryBefore, after $ordinaryAfter")

            // The ordinary WebView shows what happens in a phone's browser: the page was told it
            // was hidden, and paused itself.
            assertTrue(
                "The page in an ordinary WebView should have been told the screen went off; " +
                    "before $ordinaryBefore, after $ordinaryAfter",
                ordinaryAfter.getInt("hidden") > ordinaryBefore.getInt("hidden"),
            )
            // This app's page didn't notice, and kept going at full speed.
            checkKeptRunning(before, after, seconds = 120)
            assertTrue("The notification should be keeping the app running: $service", "isForeground=true" in service)
        }
    }

    @Test
    fun keepsRunningAfterTheAppIsClosed() {
        // Swiped away and opened again three times: the page must keep going each time, and must
        // be the same page when the app opens again, not loaded afresh.
        var last: JSONObject? = null
        repeat(3) { round ->
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { screen ->
                if (round == 0) loadTestPage(Page.currentView!!)
                KeepRunningService.start(screen)
            }
            Thread.sleep(3_000)
            val shown = report(Page.currentView!!)
            last?.let {
                assertTrue(
                    "Opening the app again should show the same page, still counting: before $it, now $shown",
                    shown.getInt("counted") > it.getInt("counted"),
                )
            }

            scenario.close()
            Thread.sleep(2_000)
            var screenLeft: MainActivity? = null
            var parked = false
            var parkingError: String? = null
            instrumentation.runOnMainSync {
                screenLeft = Page.screen
                parked = Parking.isParked
                parkingError = Parking.lastError
            }
            assertNull("The screen should be closed", screenLeft)
            assertTrue("The page should be in its invisible window: $parkingError", parked)

            val before = report(Page.currentView!!)
            Thread.sleep(20_000)
            val after = report(Page.currentView!!)
            Log.i(TAG, "With the app closed, round ${round + 1}: before $before, after $after")
            checkKeptRunning(before, after, seconds = 20)
            last = after
        }
        val service = serviceState()
        assertTrue("The notification should be keeping the app running: $service", "isForeground=true" in service)
    }

    @Test
    fun triesAgainEvery10SecondsWhenTheConnectionFails() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var retriesBefore = 0
            var failuresBefore = 0
            instrumentation.runOnMainSync {
                retriesBefore = Page.retriesSoFar
                failuresBefore = Page.failuresSoFar
                // An address that can never be reached, like a connection that's down.
                Page.open(UNREACHABLE_ADDRESS)
            }
            Thread.sleep(35_000)
            var retries = 0
            var failures = 0
            instrumentation.runOnMainSync {
                retries = Page.retriesSoFar - retriesBefore
                failures = Page.failuresSoFar - failuresBefore
            }
            Log.i(TAG, "Unreachable page: $failures failures, $retries tries again in 35 s")
            // Every 10 seconds: 3 times in 35 seconds, or 2 if each failure took a few seconds.
            assertTrue("In 35 seconds it should have tried again 2 or 3 times, not $retries", retries in 2..3)
            assertTrue("Each try should have reached for the page again: $failures failures after $retries tries", failures >= retries)
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

    private fun loadTestPage(web: WebView) {
        web.loadDataWithBaseURL(TEST_ADDRESS, TEST_PAGE, "text/html", "utf-8", TEST_ADDRESS)
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
        const val UNREACHABLE_ADDRESS = "https://nothing-here.keep-page-loading.invalid/"

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
