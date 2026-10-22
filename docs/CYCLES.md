# Cycle log

For the next cycle: what each one should do differently. What was built is in
[DEVLOG.md](DEVLOG.md). Newest first; the last ten or so.

### Cycle H2 (22 Oct) — build: deeply nested feeds and pages

See [DEVLOG.md](DEVLOG.md).
- **Profile before fixing what the backlog names.** A sampling loop in a throwaway test (`Thread.getStackTrace` every 20 ms) showed the time was in sibling renumbering, not depth; the backlog's "refuse past a few hundred levels" would have fixed only feeds.
- **Diff a behaviour-preserving change against the corpus.** `PlatformCorpusTest` writes every result to `core/build/platform-corpus`; run it on main and on the branch and `diff -rq`. It caught a pre-pass that changed Readability's choice on two real pages, which every test passed.
- **Commit before a falsification run, every time.** A `git checkout` of a file during one threw away an uncommitted edit to it, and the next measurement ran on the wrong code.

### Cycle H1 (22 Oct) — build: the accessibility walk's hang

The first of tonight's H1–H7 run, one session for all of them. See [DEVLOG.md](DEVLOG.md).
- **Read every failed run's logcat before guessing.** Ten failures' `device-test-report` artifacts, fetched with the API, all ended at one step; that one line named the cause. A run that passes uploads nothing, so there's nothing to compare against.
- **Count a device flake with a temporary matrix.** `strategy.matrix.attempt: [1..10]` on the device job, with the runner's `class=` filter, gives ten emulators at once in about ten minutes. Run the old code under the same matrix for the before, then drop both temporary commits.
- **Run baseline:** no emulator, no Docker, 22 GB free, load 0; worktree `/home/user/nr-h2` for the next cycle.

### Cycle G6 (21 Oct) — audit: security and supply chain

See [DEVLOG.md](DEVLOG.md).
- **Give a reviewer that runs Gradle its own worktree.** The input reviewer put a probe test in the main checkout, and the stop hook flagged it as untracked work twice before the reviewer removed it.
- **`git ls-remote` gives an annotated tag's own SHA, not its commit's.** `gradle/actions` v6.4.0 is `b9bee63` as a tag and `3f5f9ad` as a commit; `uses:` needs the commit. Clone at the tag and read `git log -1`, or look for the `^{}` line.
- **An unpinned scanner can turn a required check red on a PR that changed nothing.** zizmor-action took `latest`, and 1.31 adds a high finding. Pin it and run the new version locally before raising it.
- **Run baseline:** no emulator, no Docker; worktrees `/home/user/nr-ci`, `nr-json` and `nr-docs` made and removed; Gradle stopped at the end.

### Cycle G5 (21 Oct) — audit: documentation pass on BACKLOG.md

See [DEVLOG.md](DEVLOG.md).
- **Done items come back as open duplicates.** Four of the five "done but unticked" items already had a ticked line in Done, written by the cycle that built them, which never removed the open one. When ticking, delete the item from *Next* rather than adding a new line to *Done*; grep *Next* for the idea before finishing.
- **A measurement PR answers part of an item, not all of it.** Copy the numbers into the item and say what's still to measure, rather than ticking it.
- **Run baseline:** no emulator, no Docker, no Gradle run (docs only).

### Cycle G4 (21 Oct) — build: onboarding's search result for TalkBack

Built the approved item; see [DEVLOG.md](DEVLOG.md).
- **An announcement deduplicated by its text needs a key besides the text.** The same answer twice was said once, because an answer that needs no network comes before "Checking…" is drawn. The review found it; ask "what if the same text comes twice in a row?" of any `said == text` guard.
- **Gradle here was ready in about five minutes** (the first run fetched the distribution and build-tools 36); a focused test class then took 1–2 minutes.
- **Run baseline:** no emulator, no Docker; Gradle stopped at the end.

### Cycle G3 (21 Oct) — build: a deleted edition's title

Built the approved item; see [DEVLOG.md](DEVLOG.md).
- **List every "went out" path before reading a flag as "never went out".** `deliveredAt` looked like the whole truth, but `markDelivered` deliberately records nothing for a released edition shared during a build. The review found it; a grep for every early return in `markDelivered` would have.
- **A full `./gradlew build` here took 10 minutes** with a warm daemon after the first test run (the first Gradle run fetched the distribution and build-tools 36).
- **Run baseline:** no emulator, no Docker; Gradle stopped at the end.

### Cycle G2 (21 Oct) — build: the tt-rss sign-in wait

Built the approved lockout item; see [DEVLOG.md](DEVLOG.md).
- **Docker isn't running in a cloud container until you start it:** `nohup dockerd &`, then `docker compose -f tools/ttrss-live/compose.yml up -d --wait` takes about two minutes. The server's PHP is then readable with `docker compose … exec -T app`, which beats guessing its rules from the API.
- **Check a rounding margin's direction before adding one.** I widened the window "because tt-rss counts whole seconds"; flooring both times makes its wait shorter. Write the inequality out.
- **Run baseline:** dockerd and the tt-rss containers started for this cycle and stopped at the end; Gradle stopped.

### Cycle G1 (21 Oct) — build: subscribe from a share

The first of tonight's G1–G7 run, one session per cycle. Built #356; see [DEVLOG.md](DEVLOG.md).
- **A test edit made during a full build can be checked against the old main code.** `build` compiled main before a fix and the tests after it, so one new assertion failed on code that no longer existed. Don't edit sources while `./gradlew build` runs; edit docs only, or wait.
- **Robolectric doesn't deliver a second click to a finished activity,** so a double-tap guard in an activity can't be falsified there. Say so in the PR rather than keep a test that passes either way.
- **A fake HTTP serves only the exact address.** `https://news.example` and `https://news.example/` are different pages to `FakeHttp`; a test of a lookup that 404s silently takes the "no feed" branch.
- **Run baseline:** no emulator, no Docker; one extra worktree (`/home/user/nr-shots`) made and removed; Gradle stopped at the end.

### Cycles F1–F4 (20 Oct) — build: reading times, row caps, episode tidies, Save a copy

One session, a day run alongside other agents on 4 shared cores. Built: #335, #336, #337, #338, and the `PodcastSettingsTest` flake (#340); see [DEVLOG.md](DEVLOG.md).
- **Count a flake's before and after side by side.** The first after-count ran while the machine had gone quiet (load 5, against 12-16 for the before), which proves little for a flake that needs load. Running both versions at once, each loading the other, gave a fair 4 in 20 against 0 in 20.
- **The scratchpad is shared with other agents.** A file named `pr1.md` at its root was overwritten by another project's session, and a `PATCH` from it put that project's PR text on #335 for a minute. Keep every file in a folder named for the project and never reuse a common name; read a PR body back after writing it.
- **A Compose effect runs on the test rule's own dispatcher, not the main looper.** `idleUntil` drains only the looper, so a toast from a `LaunchedEffect` never showed in the test until the wait also called `compose.waitForIdle()`. The rule runs each test inside `runTest`; that's the first lead for the `PodcastSettingsTest` flake.
- **With no subagent tool, a review session works:** `create_session` on the pushed branch, asked to post its findings as one PR comment, then `send_message` to the same session for the second look and the next PR. It found real bugs in two of four PRs.
- **A squash merge deletes the branch on GitHub,** so the next PR's push is a plain `git push -u`, not `--force-with-lease` (which fails with "stale info").
- **Run end check:** no emulator was started (none in this container); the extra worktrees in /home/user (nr-item2, nr-small, nr-save, nr-docs, nr-shots) are removed at the end, and Gradle is stopped.

### Cycles E1–E7 (19 Oct) — build: podcast episodes steps 2 and 3, with two audits

One session, overnight. Built: labels (#320), wrong-label reports (#321), audio lengths (#322), readings in Listen (#323); the two audits' fixes are #324 and their larger findings are in the backlog. See [DEVLOG.md](DEVLOG.md).
- **`runTest` skips a real timeout.** Under `runTest`, `withTimeoutOrNull` runs on virtual time. That time jumps ahead while Room works on its own thread, so the timeout fires at once and the code inside never runs. Hit twice tonight: the builder's measuring test, then `AudioLengths.measureEdition`'s. Test code that has a timeout under `runBlocking`, or read from the database before the timeout starts. Learned twice in one night, so it's a rule now: proposed for CLAUDE.md's Tests section, a shared rule and so madCode's call.
- **Save a failed run's results before running again.** A full build failed, the re-run passed, and its results overwrote the failure's, so which test failed was lost. Copy `app/build/test-results` aside first.
- **A wrapper over another speaker gets one test with the real one beneath it.** `ReadingSpeaker` passed every test with a fake voice. With Kokoro's `PodcastSpeaker` beneath, the podcast played the whole article over the recording. The review found it, and the fix's test uses the real `PodcastSpeaker`.
- **Stacked PRs and squash merges cost a merge each.** #313 on #307, then #321 on #320, then #323 on #322: each squash put the stacked branch in conflict with main, in files it only shared with its base. Stack only when the second PR needs the first's code, and merge the base the moment it's green.
- **A falsification that stays green can be the fixture's fault.** Dropping the "second range starts where asked" guard changed nothing, because the test tag was zero padding. Say so in the PR, as #322 does, rather than counting the guard as tested.
- **Run end check:**
  - extra worktrees in /home/user (cloudk, design, kdebug, mainwt, notes, shots) are removed at the end;
  - no emulator was started;
  - no Docker is running.

### Area D, closing

- **Shared docs in every PR cost a CI round each time another session merged.** #274 went green four times and was refused three of them for a conflict in BACKLOG, DEVLOG or CYCLES. With four sessions, each session's docs in one closing PR, or merging the moment CI is green, would have saved about an hour and a half.
- **Two sessions bumping the database on the same day work if they say so early.** C asked before opening; D kept 13, C took 14 on top of D's branch.

### Cycle D8 — build: starred in tt-rss goes in the paper

- **Ask how a list is ordered before capping it.** tt-rss sorts stars by the article's date, not when they were starred, so "the newest 1,000" isn't "the most recent stars". Both reviews' worst findings came from that one assumption.
- **A decision answered on the morning page mid-day is a new item.** Built as its own cycle, stacked on the previous one's migration, rather than waiting for tomorrow.

### Cycle D4 — build: a quiet tt-rss feed says so

- **A fix stacked on an unmerged PR's migration** (v11 → v12 here) branches from that PR's branch and merges main once it lands; two sessions bumping the version at once would collide.
- **One look per sync beats a daily question per feed.** Reading every new article id since the last sync costs one small request and can't miss a feed; the per-feed version had three failure modes the review found.

### Cycle D7 — build, parked: Webtoons strips

- **Measure the real thing at scale before building on a cap.** Episodes run 84–140 panels, not the 60 assumed; the image budget is shared and greedy, so a strip gets gaps mid-episode. "Built it, measured it, not shipping it" was the right call, and the branch keeps the work.

### Cycles B1–B12 (16 Oct) — build: test health and speed

- **A teardown hook is shared by every test, so a reviewer's "low risk, none found" there needs
  the full suite, not the classes touched.** B1 ran 17 classes green and broke main; three
  full runs (about 5 minutes each here) would have shown it. Before merging a change to
  `TestApp`, `testutil` rules or teardown, run the whole `:app:testDebugUnitTest` at least
  twice.
- **Gradle's build cache can answer a test task without running it**, and a screenshot or
  benchmark run then writes nothing. Use `--rerun` and check that the output exists.
- **The app's full Robolectric suite takes about 5 minutes on 4 cores**, not the 50 the day's
  prompt said, so counting a flake over the whole suite is affordable.

### Cycles A2–A6 (16 Oct) — build: the rest of area A's audit list

- **With parallel sessions, keep shared docs out of code PRs.** Every merge to main put each open
  PR's BACKLOG, DEVLOG and cycle log entries in conflict, and each fix cost a CI run. Put a
  run's entries in one docs PR after its code PRs merge, and turn on auto-merge for the code PRs.
  This is now how area A closes; worth a line in the cycles skill if it holds for the other areas.
- **A "keep both" merge script is wrong for a removed line.** It put back backlog items another PR
  had ticked off. After any scripted merge, diff BACKLOG.md against main and read each line.
- **A test that waits for text can pass before the change it checks.** "Sent" matched "Sent it
  another way?". Wait for the condition itself (the database), as CLAUDE.md says.
- **The test WorkManager keeps network-constrained work ENQUEUED**, which is "waiting for a
  connection" for free. Replaced work is pruned at once: `getWorkInfoById` returns null.
- **Run end check:** no emulator was started; nothing left running. The two extra worktrees in
  `/home/user` (wt-a, wt-b) are removed at the end.

### Cycle C8 — build: saved links that can never be read

- **A schema change in a parallel run: ask the other sessions first.** Session D had version 13
  built; agreeing on 14 by message saved a migration renumber after both were open.
- **A filter on one query isn't a filter on the feature.** Main grew a second candidates query
  (#272) for builds while this was in review; grep every query that plans an edition.

### Cycle C6 — build: typed text kept when Android ends the app

Run of 16 Oct, four sessions in parallel; this one (C) owns the screens. Baseline: a fresh cloud
container, no emulator.
- **Learned again, as in A2–A6: shared logs go in one docs PR at the end of a parallel run.** C1
  conflicted three times before it could merge. Two areas learning it makes it a rule: it belongs
  in the cycles skill's parallel-run notes (claude-playground, madCode's call).
- **Merging main into a PR can merge two new tests into one spot** (two `@Test`s inserted before
  the same anchor). Read the conflict, don't keep-both it blindly.
- **Run end check:** nothing left running in the container; no emulator was started.

### Cycle C5 — build: a Kindle how-to, and what counts as sent

- **Text that states what the code does gets reviewed against the code**, for every context it
  is shown in: one line read right in setup and wrong in Settings, where another delivery applies.

### Cycle C4 — build: onboarding's no-feed dead end

- **A button that starts async work goes away (or disables) in the tap**, and the answer checks
  it still belongs. Sources did this; the copy in onboarding didn't until the review.

### Cycle C3 — build: rotation

- **Never copy whole files across branches cut from different mains.** `git checkout other -- file`
  wrote an older test file over #245's teardown. Carry a change as a patch (`git diff base other
  -- file | git apply -3`), and diff each file against main before committing.
- **Saved UI state outlives the view model it describes.** After process death, a saved flag for a
  dialog the view model no longer has comes back the next time it opens. Keep it with the state
  it belongs to.

### Cycle C2 — build: snackbars in the background

- **`collectAsStateWithLifecycle` already pauses what it reads.** A test of "a message arriving
  in the background" passed with the fix removed; the gap was only a snackbar already showing.
  Ask what the screen collects before writing the background case.
- **A wait you add holds whatever is waiting on it.** The reading list's removal was only
  committed when its snackbar ended; making the snackbar wait made the deletion wait.

### Cycle C1 — build: Send for a look

- **A limit on a URL is a limit on the encoded URL.** Capping raw characters let a non-Latin
  address through at twice the length; measure what is sent.
- **Falsify with a break that changes the outcome for the test's input**: a raw cap of 4,000
  stayed green because the test's address was over both; check the break crosses the input.

### Cycle D6 — build: feeds come home from tt-rss

- **Settings screens are shot by `ScreenshotTest` (07i–07l)**: a change to the tt-rss options or the leave dialog needs before and after pairs.

### Cycle D2 — build: mark read in tt-rss when they expire here

- **A change to one article's fate can change what the next sync fetches.** tt-rss sync takes a feed's newest five unread, so marking articles read there pulls in older ones. Ask "what arrives next?" of any change that marks things read on a server.
- **Check a fake's fixed dates against the test clock**: FakeTtrss dated every article a year before `now`, which only mattered once publication dates did.

### Cycle D5 — build: accessibility metadata for the book

- **Run the real validator, not only the spec**: Ace on a live edition found the heading-order problem the backlog item never named. EPUBCheck is a jar from GitHub releases; Ace installs from npm (`@daisy/ace`) and runs with `ace-puppeteer` and `PUPPETEER_EXECUTABLE_PATH` at the pre-installed Chromium (the default Electron one refuses to run as root).
- **A break that stays green can mean the fixture never takes that path**: a plain-space alt passed with or without decoding; `&nbsp;` is the form that needed it.

### Cycle D3 — build: a wait before tt-rss hears of a delivery

- **Replaced WorkManager work is pruned, not left as CANCELLED**: `getWorkInfoById` of the old id can be null. Assert "null or cancelled".
- **Give a delay test the network first** (`setAllConstraintsMet` before the undo), or it passes without the delay because the work was never runnable.

### Cycle A5 (16 Oct) — build: another tt-rss user on the same server

- **"Keep both sides" is wrong for a removed line.** Merging main's shared docs with a script
  that keeps both halves of each conflict put back backlog items another PR had ticked off.
  After such a merge, diff BACKLOG.md against main and check each `-`/`+` line by hand.
- **A reviewer judges the branch it reads, not main.** It flagged a mid-build delete that an
  earlier PR, already merged, had fixed: merge main before the review on a run of related PRs.

### Cycle A1 (16 Oct, day run, four sessions) — build: a source removed during a build

- **Four sessions at once:** cycles are numbered by area (A1, B1…) so they don't collide. Work
  on the next item in a second `git worktree` while a reviewer reads the first, so the tree it
  reads stays still.
- **The first Gradle run in a fresh container takes about 5 minutes** for one test; later runs
  about 1. Start it early, in the background, and read the code meanwhile.

### Cycle D1 — build: Substack Notes through tt-rss

Run of 16 Oct, four sessions in parallel; this one (D) owns content and tt-rss.
- **Count how often a new fetch fires over the corpus before shipping it.** The first trigger
  matched 10 of 11 Substack posts, only 2 with a Note; a corpus count found it in a minute.
- **A falsification harness that greps XML across lines prints nothing**: parse the results
  (a few lines of Python with ElementTree) and print "NO RESULTS" when there are none.

### Cycle 6 — audit: architecture and test health

- **One cycle can run the pass but not fix it**: three reviewers found 20-odd findings, a dozen
  real. Fix what's small and clear, check the rest against the code, and put them at the top of
  the backlog in order; the next run takes them one per cycle.
- **Run end check:** nothing left running in the container (no emulator was started); the
  screenshots worktree in the scratchpad was removed.

### Cycle 5 — build: feed text too big to read back

- **"Is it still real?" can find something bigger.** Checking the memory item, a throwaway test
  with one 3 MB row showed every edition failing. Probing the worst input first is cheap.
- **Fix stored data where it's written, and heal old rows once in a migration**, not on every
  read path: a heal that runs later than the first reader leaves that reader broken.
- **Warnings are errors in this build** (`arrayOf` inferring an intersection type failed it).

### Cycle 4 — build: a line where a player was

- **Check that a test run produced fresh results before reading them.** A compile error left the
  previous run's XML in place, and a "failure" was read from it. Delete the results folder first,
  and in falsification loops treat "no results" as broken, not red.
- **Adding output inside a pipeline affects everything downstream**: word counts, emptiness and
  title rules. Before adding text to an article, list what reads the cleaned HTML and its counts.

### Cycle 3 — build: furniture in French, German and Spanish

- **Ask the reviewer to run the code, not only read it.** It compiled the regexes into a small
  harness and tested real strings, which is what found the misses and the false positives.
- **A test passing for the wrong reason:** the "starts the same way" sentence stayed only
  because it was over the 12-word cap. When a rule has two conditions, test each one alone.
- **Scripted breaks need a count check that matches the file** (three occurrences, not four);
  a break that doesn't apply leaves the test green and proves nothing.

### Cycle 2 — build: a bot check served instead of a feed

- **Check new detection where the old code gives up, not before it.** The first version tested
  for a bot check up front and refused real sites whose pages carry a marker. A heuristic added
  to a path that used to succeed needs a test with a real page that must still succeed.
- **A test's sample must be big enough to reach the code.** The JSON Feed sample fit inside the
  2048 characters `looksLikeFeed` reads, so it couldn't fail. Falsifying caught it; check the
  red comes from the test meant, not a neighbour.
- **Docs after the previous PR merges:** cycle 2 branched from main while #234 was in CI and
  left BACKLOG, DEVLOG and this file until it merged, so nothing conflicted.

### Cycle 1 — build: screenshots on a fixed clock

Run of 16 Oct: six cycles or three backlog items, whichever comes last. Baseline: a fresh cloud
container, nothing running, no emulator.

- **A cloud container needs three things before Gradle works**, and the setup script may not
  have done them: the Android SDK (`platforms;android-37.0`, build-tools 37, `local.properties`
  pointing at it), and a Gradle init script in `~/.gradle/init.d/` that swaps Maven Central for
  Google's mirror (`maven-central.storage-download.googleapis.com/maven2/`) and sets
  `robolectric.dependency.repo.url` to it for test tasks. Central answers the shared egress with
  429, and a 429 stops resolution instead of falling through to JitPack. Check for both first.
- **Check the backlog item against the code first.** "Test DataStores made without a scope" had
  been done in #190 and never ticked.
- **Before trusting a screenshot fix, render twice and `cmp` the PNGs.** It showed the clock
  problem in minutes and also found three shots that vary for another reason (animations), now
  their own backlog item.
- **The review found what a grep for `.now()` missed:** a helper taking a zone but reading the
  year from the real clock, and `ZoneId.systemDefault()` reads. Grep for those too.
