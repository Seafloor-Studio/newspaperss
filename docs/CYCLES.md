# Cycle log

For the next cycle: what each one should do differently. What was built is in
[DEVLOG.md](DEVLOG.md). Newest first; the last ten or so.

### Cycle A2 (16 Oct) — build: a late send of a released edition counts

- **A scripted break can hit more than the line meant:** `sed` on a line ending also matched
  `markSentByHand`, so an older test went red too. Count the lines a break changes
  (`git diff --stat`) before reading the result.
- **JUnit XML, not the console, says what failed:** `-q` prints "N tests completed, M failed" only
  on failure; read each testcase from the XML.

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
