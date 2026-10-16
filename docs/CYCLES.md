# Cycle log

For the next cycle: what each one should do differently. What was built is in
[DEVLOG.md](DEVLOG.md). Newest first; the last ten or so.

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
