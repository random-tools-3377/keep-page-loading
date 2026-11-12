# Keep Page Loading

An Android app that opens any web page and keeps it running while the screen is off, and after
you leave the app, until you tap **Stop**.

A phone's web browser pauses a page as soon as you switch away from it or the screen goes off.
This app keeps the page going, like a computer does with a page left open in a window.

## Install

On the phone, open this repository's **Releases** page, download the newest
`keep-page-loading-<version>.apk`, open it, and allow installing from your browser if the phone
asks. Later versions install over the top, keeping your page and logins.

## Use

1. Type the page's address at the top and press **Go**.
2. The first time, allow notifications and allow it to run in the background.
3. Turn the screen off, or leave the app. A notification shows while the pages are kept running.
4. Tap **Stop** in the notification, or ⋮ → **Stop and close**, to end it.

**Tabs:** the square with a number next to the address shows your tabs. Every tab keeps running.
Press and hold a link to open it in a new tab.

**Stopped pages:** if nothing on a page changes for 5 minutes while the connection is working,
the phone buzzes once and the notification says the page seems to have stopped.

If the phone still closes it (some Samsung, Xiaomi and other phones do), tap ⋮ →
**Battery settings** and choose **Unrestricted** under Battery.

## How it keeps the page running

- A notification keeps the app running in the background, the way a music player does, and it
  keeps the phone's processor awake.
- The page is always told it's on screen and in use, so sites that pause themselves when you
  look away carry on. With nothing drawn while the screen is off, animations that pages run on
  still get a turn at least 5 times a second.
- When the connection fails, the page is tried again every 10 seconds until it loads, and a page
  Android closed is loaded again.
- Tabs not being looked at stay in the window, moved off to the side, so they keep running at
  full speed without being drawn.
- Each page tells the app when it changes, and the app counts what it loads from its server.
  Every 30 seconds it checks for pages that have gone quiet for 5 minutes while the phone's
  connection is working.

## Tested

On every push, an emulator opens a test page that counts all the time and pauses itself when it
is hidden or loses focus, like the sites this app is for. The test turns the screen off and
sends the emulator into deep sleep (Doze) for two minutes, then swipes the app away and opens it
again three times, and checks the pages kept counting at full speed in the tab on screen and the
tab behind it, never noticed, and were the same pages each time the app opened. It also checks a
failed connection is tried again every 10 seconds, a tapped link that opens a new window opens a
new tab, and a page that stops changing is reported, and stops being reported once it changes. The same page in an ordinary WebView
beside it does notice, which shows the test is real. A Release is only published when it passes.

An emulator isn't a real phone: a phone maker's own battery saving can still differ.

## Privacy

The app collects nothing and sends nothing anywhere except to the pages you open. Cookies and
logins stay on the phone, and the app is left out of the phone's cloud backup.
