#!/usr/bin/env bash
# A freshly started emulator's Google Play Services updates and restarts itself in its first
# minutes. When it does, Android closes every app using its font service, and every WebView does.
# That would fail the emulator test for reasons that have nothing to do with this app, so this
# waits until Play Services has been running steadily: at least 2 minutes after this starts, and
# until its main process has kept the same process id for 90 seconds; at most 8 minutes.
set -uo pipefail
adb="${ANDROID_HOME:?}/platform-tools/adb"
start=$(date +%s)
last_pid="unknown"
steady_since=$start
while true; do
  now=$(date +%s)
  pid="$("$adb" shell pidof com.google.android.gms.persistent 2>/dev/null | tr -d '\r')"
  if [ "$pid" != "$last_pid" ]; then
    echo "Play Services: process ${pid:-not running} at $((now - start)) s"
    last_pid="$pid"
    steady_since=$now
  fi
  if [ -n "$pid" ] && [ $((now - start)) -ge 120 ] && [ $((now - steady_since)) -ge 90 ]; then
    echo "Play Services has been steady for $((now - steady_since)) s. Starting the tests."
    exit 0
  fi
  if [ $((now - start)) -ge 480 ]; then
    echo "::warning::Play Services was still restarting after 8 minutes. Starting the tests anyway."
    exit 0
  fi
  sleep 5
done
