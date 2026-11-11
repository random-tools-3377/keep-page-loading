package io.github.randomtools3377.keeppageloading

/**
 * A script that runs in every page, and every frame inside it, before the page's own code.
 *
 * Many sites pause themselves when they think nobody is looking: when the screen is off, the
 * app is in the background, or the page has lost focus. This makes the page always look shown
 * and focused, and stops the "hidden" and "lost focus" messages from reaching it.
 *
 * Animation frames also stop coming while the screen is off, because nothing is being drawn,
 * and a page that runs on them would freeze. So each one also comes after 200 ms at the latest.
 */
object StayActive {
    const val FRAME_FALLBACK_MS = 200

    val SCRIPT = """
        (function () {
          if (window.__keepPageLoading) return;
          window.__keepPageLoading = true;

          var shown = { configurable: true, get: function () { return 'visible'; } };
          var notHidden = { configurable: true, get: function () { return false; } };
          try {
            Object.defineProperty(Document.prototype, 'visibilityState', shown);
            Object.defineProperty(Document.prototype, 'hidden', notHidden);
            Object.defineProperty(Document.prototype, 'webkitVisibilityState', shown);
            Object.defineProperty(Document.prototype, 'webkitHidden', notHidden);
          } catch (e) {}
          try {
            Document.prototype.hasFocus = function () { return true; };
          } catch (e) {}

          // Only the page-wide messages are stopped. A box on the page losing focus still says so.
          var block = function (event) {
            if (event.target === window || event.target === document) event.stopImmediatePropagation();
          };
          ['visibilitychange', 'webkitvisibilitychange', 'blur'].forEach(function (type) {
            window.addEventListener(type, block, true);
            document.addEventListener(type, block, true);
          });

          var realRequest = window.requestAnimationFrame;
          var realCancel = window.cancelAnimationFrame;
          var setTimer = window.setTimeout;
          var clearTimer = window.clearTimeout;
          if (realRequest && realCancel) {
            var nextId = 1;
            var waiting = new Map();
            window.requestAnimationFrame = function (callback) {
              var id = nextId++;
              var frame = 0;
              var timer = 0;
              var run = function (time) {
                if (!waiting.has(id)) return;
                waiting.delete(id);
                realCancel.call(window, frame);
                clearTimer.call(window, timer);
                callback(time);
              };
              waiting.set(id, function () {
                realCancel.call(window, frame);
                clearTimer.call(window, timer);
              });
              frame = realRequest.call(window, run);
              timer = setTimer.call(window, function () { run(performance.now()); }, $FRAME_FALLBACK_MS);
              return id;
            };
            window.cancelAnimationFrame = function (id) {
              var cancel = waiting.get(id);
              if (cancel) {
                waiting.delete(id);
                cancel();
              }
            };
            if (window.webkitRequestAnimationFrame) window.webkitRequestAnimationFrame = window.requestAnimationFrame;
            if (window.webkitCancelAnimationFrame) window.webkitCancelAnimationFrame = window.cancelAnimationFrame;
          }
        })();
    """.trimIndent()
}
