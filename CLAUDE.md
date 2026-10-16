# newspaperss

An Android app that makes a personal, finite newspaper from your RSS feeds
and saved links and delivers it to your e-reader as an EPUB. It brings the
ideas of [rss-to-e-reader](https://github.com/madCode/rss-to-e-reader) to
people who don't code. Read `docs/DESIGN.md` before changing behaviour
and `docs/ARCHITECTURE.md` for how the code fits together;
`docs/BACKLOG.md` is the running plan and `docs/DEVLOG.md` the log of what
changed and why.

## Layout

- `:core`: pure Kotlin/JVM, no Android. Feed parsing, the edition planner,
  extraction, images and the EPUB writer. Test it with plain JUnit.
- `:app`: Android, Jetpack Compose, Room, WorkManager, DataStore. Manual
  DI through `AppContainer`. Test with Robolectric and Compose UI tests
  against a real Room database.
- `:epubsink`: a test-only app that receives an edition, so the device
  tests can prove it reaches another app's process.

## Commands

    ./gradlew :core:test                 # fast JVM tests
    ./gradlew :app:testDebugUnitTest     # Robolectric tests
    ./gradlew build                      # everything CI runs
    ./gradlew :epubsink:installDebug :app:connectedDebugAndroidTest   # device tests (emulator)
    ./gradlew koverHtmlReport            # coverage: build/reports/kover/html
    ./gradlew koverVerify                # fails below 93% line coverage (CI runs it)

JDK 21 and the Android SDK (compileSdk 37) are required.

Changing a Room entity means bumping the database version, adding a
Migration with a `MigrationTest` case, and committing the new schema JSON
in `app/schemas`. CI publishes main's debug APK to the `latest-debug`
release; its version name carries the CI run and commit.

Delivery ends in another app's hands. The device tests prove the file reaches
another app's process; what that app does with it can only be checked by a
person: [docs/DELIVERY-CHECKS.md](docs/DELIVERY-CHECKS.md) says how, and a
change to delivery or the EPUB wants it run. Adding a test that
asserts the intent again raises coverage without covering anything.

The sections from Privacy to Pull requests are shared with the projects in
[claude-playground](https://github.com/madCode/claude-playground/blob/main/CLAUDE.md),
whose root CLAUDE.md has the same rules for any project; here they're
worded for this app. A change to a shared rule belongs in both.

## Privacy

This repository is public. Never commit personal data: no feed lists,
hosts, emails or accounts from anyone's own setup. Starter packs contain
only well-known public feeds.

## Posting as madCode

Claude Code posts from madCode's account, so anything it writes says so **at the top**, where
it's read, not only in a footer a reader scrolls past. The first line of a pull request
description, an issue or PR comment, or a review:

    🤖 **Claude · <tag>**

Tags: `pull request`, `proposal`, `fix ready`, `needs info`, `update`, `review notes`,
`question for madCode`. `update` when none of the others fit. The first comment in an issue
thread also opens by saying who is writing and what to expect, which the `handle-issues` skill
spells out.

Nothing from the Claude GitHub App needs this: its author already says so.

## Comments

A comment is for what the code can't say itself: **why** it is written
this way (a constraint, a trade-off, what goes wrong with the obvious
alternative), **the non-obvious** (a gotcha, an invariant, a surprising
dependency), or **a summary of complex code**. Not restating the code, and
not history ("used to", "since the rewrite"): that belongs in the PR or the
commit message. Public API KDoc stays accurate when signatures change.

## Tests

A test should be able to catch a plausible regression. Test behaviour, not
structure. Don't feed code inputs it can never receive, or write a test
only to lift coverage. A test that waits for the screen, the database or a
worker waits for the condition (`idleUntil`), not a fixed time or a short
deadline. When a change has no behaviour to test, say so in the PR instead
of inventing a test.

## Documentation

Docs are for people: keep them readable, current and short.

- A change that alters behaviour updates README.md or docs/DESIGN.md in the
  same PR, if they describe it.
- Every few cycles, a documentation pass: check the docs against the code,
  fix what's stale, cut what's grown long or become history. DESIGN.md says
  how the app works now; plans go in BACKLOG.md, history in DEVLOG.md and
  the PRs.
- Plain words over jargon, short sections, one idea per bullet.

## Pull requests

Several small PRs beat one large one. The description follows
`.github/pull_request_template.md`: what changed, how it was tested (the
commands run and what they showed, not just "tests pass"), what the review
found. Merge only when asked, and only with CI green.

Before opening a PR that changes behaviour, have a fresh-eyes subagent
review the diff. Point it at the risky parts:

- How the change interacts with other features touching the same data,
  including whatever else deletes or replaces that data.
- Sync, delivery and other background work: cancellation, retries, removal
  and re-adding, two runs at once (a timed and a manual edition, a delete
  during a build).
- Rotation, and the app being killed and restored: what's on screen,
  dialogs, typed text, and actions that must happen once.
  `StateRestorationTester` tests the last in Compose.
- Feeds, pages and links as untrusted input: a hostile feed, a link that
  isn't to the web.
- Other locales: non-Latin digits, international domain names, pages in
  older encodings.
- E-ink, accessibility, and e-reader firmware that lacks standard
  Android screens.

Ask for concrete findings only: file:line and a failure scenario, most
severe first, no edits. Ask for bugs and gaps against what the change is
for, not style or what might be nice: a reviewer asked for gaps finds some,
and chasing every one adds code nobody needed. Verify each finding before acting on it, and say
in the PR what the review found and what was fixed or deliberately left.
Docs-, comment- and config-only changes can skip this; after fixing the
findings, a short second look at just the new diff is enough.

A PR that changes how a screen looks shows it: before and after images in
the description, one pair per screen or state that changes (only "after"
for a new screen).

- Render both with `ScreenshotTest` (`./gradlew :app:testDebugUnitTest
  --tests '*ScreenshotTest'`, PNGs in `app/build/screenshots`): "before"
  from `origin/main`, "after" from the branch. A changed screen the test
  doesn't shoot yet gets a shot added, which also guards it.
- Sample data only, never anyone's own: the repo is public.
- Open the PR, then commit the PNGs to the `claude/screenshots` branch
  (never merged) under `pr-<N>/` as `<screen>-before.png` and
  `<screen>-after.png`, and edit them into the description with
  `![<screen>, before](https://github.com/madCode/newspaperss/blob/claude/screenshots/pr-<N>/<screen>-before.png?raw=true)`.
- If they can't be rendered (no Android SDK), say so in the PR.
