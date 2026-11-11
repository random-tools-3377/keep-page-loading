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
3. Turn the screen off, or leave the app. A notification shows while the page is kept running.
4. Tap **Stop** in the notification, or ⋮ → **Stop and close**, to end it.

If the phone still closes it (some Samsung, Xiaomi and other phones do), tap ⋮ →
**Battery settings** and choose **Unrestricted** under Battery.

## How it keeps the page running

- A notification keeps the app running in the background, the way a music player does, and it
  keeps the phone's processor awake.
- The page is always told it's on screen and in use, so sites that pause themselves when you
  look away carry on. With nothing drawn while the screen is off, animations that pages run on
  still get a turn at least 5 times a second.
- A dropped connection is tried again on its own, and a page Android closed is loaded again.

## Tested

On every push, an emulator opens a test page that counts all the time and pauses itself when it
is hidden or loses focus, like the sites this app is for. The test turns the screen off, sends
the emulator into deep sleep (Doze) for two minutes, then closes the app for a minute, and checks
the page kept counting at full speed and never noticed. The same page in an ordinary WebView
beside it does notice, which shows the test is real. A Release is only published when it passes.

An emulator isn't a real phone: a phone maker's own battery saving can still differ.

## Privacy

The app collects nothing and sends nothing anywhere except to the pages you open. Cookies and
logins stay on the phone, and the app is left out of the phone's cloud backup.
