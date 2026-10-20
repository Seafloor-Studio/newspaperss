# Emulator checks: what a local session can do

[DELIVERY-CHECKS.md](DELIVERY-CHECKS.md) lists what only a person could check, because each
route ends in another app. Most of it can now be done by a Claude Code session on the owner's
Mac, driving an Android emulator that has those apps installed. This says how. Update
DELIVERY-CHECKS.md's "Last checked" column with what a run confirms.

## What it needs, once

- **Android Studio**, with an emulator image that has **Google Play** (API 36). Name it
  `newspaperss`.
- **Throwaway accounts**, never anyone's own:
  - Google: Play Store and Gmail on the emulator.
  - Amazon: its Kindle app gives a `@kindle.com` address. Add the Gmail address as an approved
    sender in Personal Document Settings.
  - Dropbox (free): the Kobo route.
  - PocketBook Cloud (free): an `@pbsync.com` address.
- **A local session.** Start Claude Code in the Desktop app, or `claude` in a terminal, in this
  folder on the Mac. A cloud session can't reach the emulator.
- `adb` on the PATH (Android Studio's `platform-tools`), and Python 3.

**The owner signs in to every account on the emulator by hand.** The session never asks for or
types a password, and nothing it writes names an account: screenshots stay in `build/emulator/`,
which isn't committed.

## How a session drives it

    emulator -avd newspaperss &                # or start it from Android Studio
    ./gradlew :app:assembleDebug
    tools/emulator/smoke.sh                    # installs, starts, taps through the first step

`smoke.sh` clears the debug app's data. From there, `tools/emulator/ui.py` does the rest:

    tools/emulator/ui.py texts                 # read the screen
    tools/emulator/ui.py tap "Send"            # tap by text
    tools/emulator/ui.py shot kindle-library   # build/emulator/kindle-library.png, to look at

Read the screen as text first; look at a screenshot when layout or a picture matters.
`tools/emulator/delivery.sh make` finishes onboarding with a starter pack and waits for the
first edition; after that, **Make an edition now** on Today makes another.

## The routes

Each needs an edition first. "Proof" is what the session checks before calling the route done.

| Route | Do | Proof |
|---|---|---|
| Kindle, by email | Settings: Kindle by email, with the test `@kindle.com` address. Send; Gmail opens; send it there | The book appears in the Kindle app's library on the emulator (pull to refresh; allow 20 minutes) |
| Kindle app | Settings: the Kindle app. Send, then pick Kindle in the share sheet | The book appears in the Kindle app's library |
| Kobo | Settings: Kobo. Send, pick Dropbox, into `Apps/Rakuten Kobo` (make the folder if Kobo never has) | The file is in the folder in the Dropbox app, under its own name |
| PocketBook | Send by email to the `@pbsync.com` address | The book appears in PocketBook Reader, signed in to the test account |
| Open on this phone | `tools/emulator/delivery.sh koreader` | KOReader opens the book on its cover (look at the screenshot); turn to the contents |
| Folder | `tools/emulator/delivery.sh save-copy` | The book is in Downloads, and the edition says delivered |
| Anything else | Send, and pick any app in the share sheet | The edition says sent |

Then the state checks from DELIVERY-CHECKS.md: two editions built without sending the first,
and a send from the notification after the app was stopped (`adb shell am force-stop
com.app.newspaperss.debug`).

## Without any account

- **Timed editions in deep sleep.** Set a timed edition a few minutes ahead, then
  `tools/emulator/doze.sh on`. Note whether it arrives; `doze.sh window` grants the maintenance
  window Android would give a few times a night; `doze.sh off` at the end. Today's screen says
  what happened.
- **Large text.** `adb shell settings put system font_scale 2.0`, then walk Today, an edition,
  Sources and Settings with screenshots. `1.0` puts it back.
- **TalkBack.** Turn it on in the emulator's Accessibility settings, then use `ui.py texts`
  for what it would read. A person still has to listen to it.
- **A release build.** `./gradlew :app:assembleRelease` with the debug key, installed beside
  the debug app: onboarding, an edition, a send.

What stays with a person: anything on the e-reader itself (a Kindle, Kobo or Boox screen), and
how TalkBack actually sounds.
