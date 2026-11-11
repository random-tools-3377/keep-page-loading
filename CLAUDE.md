# Keep Page Loading — house rules

An Android app that opens any web page the user types in and keeps it running while the screen is
off and after the app is left, until the user taps Stop. It is for one person.

## Decided

- It is general-purpose. No particular website is named anywhere: not in the app, the code, the
  tests, the commits, the issues or the Releases.
- The page keeps running with the screen off and after the app is closed, until Stop.
- Sites that pause themselves when hidden or unfocused are kept going.

Anything not written here is not decided, so ask the owner rather than filling it in.

## This repository is public

None of it may contain the owner's name or username, what the owner said (feedback, quotes,
requests), or the sites the owner uses. Write "the owner" or "the user". Commits are authored as
`Claude <noreply@anthropic.com>`. Release notes are written for a non-developer and name nobody.

## Builds and releases

- Kotlin, plain Android views, no Compose. Work lands on `main` without pull requests.
- The owner installs from the Releases page in the phone's browser, so every build meant to be
  tried is a Release with the APK attached. A push to `main` whose `appVersionName` (in
  `gradle.properties`) has no Release yet becomes one, with `release-notes/<version>.md`.
- Every release is signed with the same key, or Android refuses to install it over the previous
  one. The key is in repository secrets, never in the repo: `SIGNING_KEYSTORE_B64` (a base64
  PKCS12 keystore, key alias `keep-page-loading`) and `SIGNING_PASSWORD`. The owner keeps a
  backup offline. Never generate another key for this app.
- CI checks the release APK against this certificate, so a build signed with any other key never
  becomes a Release: SHA-256
  `1F:E2:3D:3F:56:33:85:EE:82:BD:D7:F5:C0:CB:3C:E2:5B:A5:82:DA:E4:2D:5F:3A:72:60:32:E7:B5:3A:E5:CD`.
- Releases come only from pushes to `main`. Never use `pull_request_target`, and never give the
  signing secrets to a workflow that runs code from a pull request.
- CI on every push: unit tests, lint, the permission check, the emulator test that the page keeps
  running with the screen off and with the app closed, and the signature check.

## Talking to the owner

- Short and plain. The owner is not an Android developer. Say what changed, what to try, and what
  is not verified.
- "Done" means a Release the owner can install, not a green check.
