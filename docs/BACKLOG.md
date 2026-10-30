# Backlog

The running plan. Each work cycle picks what matters most for readers right now (device feedback
first, then research and judgment), builds it with tests, and moves it to Done. Feature proposals are
ideas, not commitments.

## Next

Grouped by part of the app. The tag says where each item came from: *device* (your testing),
*personas* ([docs/research/personas.md](research/personas.md)), *resources* (the resource audit),
*live* (building real editions), *a11y* (the accessibility audit), *ux* (the Day 3 UX pass),
*audit* (the architecture audit, #163–#166).

### From the 16 Oct audit *(audit)*
Architecture and test health, cycle 6 of the 16 Oct run. Most severe first; "checked" means read against the code, not yet reproduced.
- [ ] Another tt-rss user, or another server, still meets the first one's delivered links: `delivered_urls` is shared, so a story the first user received is skipped for the second, and marked read on their server. Small, but partners often follow the same blogs. Forget the delivered links that came only from the old source when it goes? *(review, 16 Oct)*
- [ ] Restored onto a new phone, the database comes back without the tt-rss login (it's left out of backups), so whoever signs in next keeps the waiting and starred articles, as the same user would. Ask on sign-in whether it's the same account? *(review, 16 Oct)*
- [ ] About 100 view models built directly in tests (Today, Settings, Sources…) are still never stopped, as the source page's were; `testutil/ViewModels` stops them, so adopt it as those tests are touched *(today B3)*
- [ ] **Waits on a view model's state alone, in screen tests, may race the same way `PodcastSettingsTest` did** *(20 Oct, from #340)*: about 45 `idleUntil { vm.….value … }` waits in screen tests, 23 of them in `EditionDetailScreenTest`, drain the main looper but never let the Compose rule run the effects that subscribe to those flows. (Not `PodcastSettingsTest`'s other three, which come after `show()` has the subscription live, nor the view-model tests, which have no Compose rule.) Not counted; none has been seen to fail. If one does, give it `compose.waitForIdle()` inside the wait, as #340 did, and count it
- [ ] CLAUDE.md says its shared sections match claude-playground's; several rules have drifted (tests "waits for the condition", the review's background-work bullet, the PR template, "merge only when asked"). Your call whether to bring them across
- The next audit of this kind is listed under Recurring reviews.

### From the 22 Oct audit *(audit)*
Architecture and test health, cycle H7. Fixed: a feed's title or address too big to read back, a shared link's tracking doubling a story, and three Share tests that couldn't fail. Read, not reproduced:
- [ ] **Save a copy, then deleting the edition, gives its title to the next one.** Save a copy records nothing, and a deleted edition with no `deliveredAt` frees its title (#358). A reader who sends the copy on by hand and tidies up gets the same title again, which Send to Kindle drops, and the articles come back. Needs the copy recorded (a column, so a migration) without marking it sent.
- [ ] **The self-update hold ends before Save a copy (or an OPML export) has written its file** (`MainActivity` marks the picker answered before the result callback starts the copy). An install in that second could leave half a file. Debug builds only; hold until the copy is done.
- [ ] **`journeys.sh listen` takes any player's audio as the voice's** (`dumpsys audio | grep state:started`): match the TTS engine's or the app's uid.
- [ ] **Two `ShareActivityTest` checks see a doubled launch, not a doubled call inside one**: `act` run twice in one launch still toasts once. Not worth more machinery unless that code changes.

### From the 21 Oct security review *(audit)*
Cycle G6. Fixed in #363 (CI) and #364 (nested JSON). Measured unless it says "read".
- [ ] **Some pages and feed texts still take tens of seconds to read, and nothing stops them** *(H2, #375's review)*. Deep nesting and runs of empty blocks are fixed (#375); these shapes aren't, measured on the JVM, none slower than before:
  - Nested with text at every level (`<div>x` 100,000 times, 600 KB): 68 s to extract, 27 s to clean.
  - 100,000 `<br>` (400 KB): 20 s to extract. 100,000 `<p></p>`: 18 s.
  - 100,000 empty `<span>`s, nested or side by side: 11–14 s to clean (`stripTagsAndAttributes` unwraps them one at a time).
  - Pages are allowed 5 MB, and the edition's deadline is checked only between articles, so the cure is likely a limit on elements per page, or work the deadline can stop.
- [ ] **A feed of over 1,000 items whose dates can't order them keeps its first 1,000** *(H3, #376's review)*: right for the usual newest-first feed, but an undated or same-dated oldest-first one keeps its oldest. Before the cap, all were stored. Rare (it needs both); keep the last 1,000 when no dates differ, if one turns up.
- [ ] OPML and reading-list imports read the whole picked file into memory (`readText()`), with no size limit. Small: the reader picks the file. *(read)*
- [x] **Dependency verification** *(H9)*: `gradle/verification-metadata.xml`, written again on Dependabot's PRs by a workflow
- [ ] **Robolectric's Android jars aren't verified:** Robolectric downloads them itself, outside Gradle
- [ ] **Old checksums stay trusted:** Gradle never drops an entry, so the file only grows; a pass that rewrites it from scratch every so often would drop versions no longer used
- [ ] `recurring-review.yml` steers an agent that can write issues. Its spec comes from claude-playground, and it reads the bodies of open `audit` issues, which anyone can write. Its `gh api repos/madCode/claude-playground/contents/*` allowance also permits PUT and DELETE. Your call whether to narrow it to GET and to issues you opened. *(read)*
- [ ] **Debug builds signed with their own key: the secrets** *(H5, H8)*. CI signs published debug builds with `DEBUG_KEYSTORE_BASE64` and friends once they exist, rotated from the shared key so Android 9+ takes them as an update. madCode adds them after this build has reached the testers' phones, as the morning page says.
- Checked and left as is:
  - **A `mailto:` link in an article opens the mail app with fields the feed chose.** You still press send, and nothing can be attached.
  - **Every shared edition is readable by the Kindle app until reboot.** Deliberate, so Send to Kindle works.
  - **`ListenService` takes any media controller.** As media apps do.
  - **Feed images can point at local-network addresses.** As in any feed reader.
  - **tt-rss in Docker uses `:latest` on purpose,** and the emulator's KOReader is F-Droid's latest.

### From the 30 Oct test audit *(audit)*

What each screen offers by the edition's state wasn't tested past ready and sent editions, so a not-sent edition lost Listen (#401, #410). Robolectric unless it says otherwise.

- [ ] **The natural-voice audio line on the edition page, by status:** ready, sent and not sent, against audio asked for, part made and made. `PodcastLineTest` checks the line alone; only a screenshot shows it inside the page.
- [ ] **Deleting the edition you're listening to, or one that has audio:** through the real wiring in `AppContainer` (`listen.forget`, `podcastStore.delete`), and the clean-up after each run (`pruneFiles`). The bar should let go and the audio's space come back. The tests now check that a stand-in was called.
- [ ] **Today's card when the newest edition is a not-sent one** (after making another and deleting it): it offers only "Try again", while its page offers Mark as sent and Listen. Make the card match the page (EditionActions says they offer the same), with a test.
- [ ] **The status changing while the page is open or playing:** a ready edition released by the morning build keeps Listen and its audio line.
- [ ] **Emulator:** `journeys.sh two-editions` stops at Today's list; go on to open the not-sent edition and wait for "Listen ·" and "Mark as sent". *(device)*
- [ ] **Your call: on a Boox, a not-sent edition asks "Read it another way?"** with no first way offered (no Read or Open). Offer Read there, or word it as "Read it on your Boox?" Recommended: offer Read, since its book is still here.

### Onboarding and setup

### Sources and fetching
- [ ] **Platform pass** *(you asked)*: a test corpus of real posts from the big blog and newsletter platforms, from their own feeds and through tt-rss, checked for footnotes, embeds, captions, paywall teasers and link posts; what breaks gets fixed and the corpus keeps it fixed (`PlatformCorpusTest`). Round 1 covered Substack, Ghost, WordPress, Medium, Blogger, Buttondown and Micro.blog; round 2 added a magazine with its own site (New Left Review), Stratechery and a MemberPress site, found by running `extractionSweep` over a real reading list and over `tools/sweep-feeds.opml`, public feeds picked for variety (news, other languages and scripts, static sites, microblogs). Run that list again before a change to extraction. Still to add: Tumblr (it answers 429 to the collector), beehiiv, posts with galleries, tweets and Bluesky embeds, and more footnote styles
- [x] **Show tt-rss's own feed errors** *(you asked)*: tt-rss's `last_error` for each feed, on the feed's row and page and named in a banner on Sources
- [ ] Paywalled and summary-only sites: warn when a site is added; keep stubs from eating the budget; drop metered sites from starter packs *(personas)*
- [ ] **Skip paid posts' previews too** *(you asked)*: a source can already skip paid posts with nothing free (a title and a picture). Let it also skip the ones that come as a preview, only the free part before "Subscribe to read", so an edition carries whole articles only. Per source, beside the existing choice, with the count on its page.
- [x] A feed over 10 MB was refused as too large: some static sites (a Hugo `index.xml`) put every post they ever wrote in one feed. Now it's read up to 10 MB, keeping the whole items before the cut *(sweep)*
- [ ] Webtoons: episodes are one long strip of 84–140 lazily loaded panels (a phone is sent to m.webtoons.com, which lists them in a script; the desktop viewer has them in `data-url`), beyond the 20-image cap. Built and measured on `claude/today-d-c7` (D7), not shipped. Before it can ship: the image budget must take a strip whole or not at all (today it is shared and greedy, so a strip gets gaps mid-episode and starves later articles); no cap that cuts an ending silently; ask for `?type=opti` (PNG panels ignore `q90`: 800 KB vs 300 KB); drop the feed's two thumbnail panels, which otherwise repeat after the strip; panels contiguous in the book (no figure margins), with alt text. Re-encoded, an episode is about 6–9 MB *(device)*
- [ ] Webtoons: episodes are one long strip of dozens of lazy images (`data-url`), beyond the 20-image cap, and its mobile site hides the feed. Support strips properly *(device)*

### The book
- [ ] EPUB design, round 2: the cover image and a look on real devices (Kindle, Kobo, KOReader) *(device)*
- [ ] Substack Notes when a Substack post is read from its page (PAGE mode, or an excerpt): the page shows a placeholder where the Note goes, so it vanishes. The page's data has the whole post (see `SubstackPost`); a free post could be read from there *(found in D1)*

### Delivery and schedule
- [ ] Dropbox connection (OAuth PKCE) so Kobo delivery is automatic; waiting on an app key ([#18](https://github.com/Seafloor-Studio/newspaperss/issues/18)) *(personas)*
- [ ] Boox: offer folder delivery into the Books folder, so editions stay in the library *(personas)*
- [ ] Folder delivery: tt-rss marks articles read as soon as the file is saved, before Syncthing has synced; old editions pile up in the folder *(personas)*
- [ ] Verify folder delivery and the chooser from the notification on a real device
- [ ] **Your call: an edition listened to the end counts as read?** *(you asked)* Today finishing Listen only notes the edition as finished: it stays "Ready to send", so when the next edition is made it's marked not sent and its articles come back for the next paper. **Read** on a Boox already counts as sent. Options: mark it sent on reaching the closing page; or ask there once, "Finished. Count this edition as read?" (Yes / Not now), and never for one already sent or marked. Recommended: ask, since some listen and then send it to the e-reader too, and marking it sent also marks its articles read in tt-rss
- [ ] If lead time isn't enough on a real device, wake timed editions with an exact alarm (Doze defers WorkManager; expedited work can silently restart a long build). Measured on the emulator (#352, `doze.sh timed`): in deep sleep the timer is held until the next maintenance window, about a minute late there because the emulator grants one at once. On a phone overnight it's however long the next window takes, which the 30-minute lead may or may not cover; a phone's overnight run is what decides this

### tt-rss, for a returning reader *(personas)*
- [ ] Signing in as "Reader" when "reader" is saved counts as another account (`TtrssRepository.add` compares the user exactly) and removes the tt-rss source, though tt-rss looks users up without case. *(G2 review)*
- [ ] A sync within 5 seconds of a wrong password typed in Settings for the same user is turned away by tt-rss and stored as "can't sign in" until the next sync; only the sign-in form knows about the wait. Unlikely; fix if seen. *(G2 review)*
- [ ] Decide what tt-rss is as a source. Explored ([research/ttrss-backend/](research/ttrss-backend/index.html), open it in a browser): one tt-rss row in Sources with its feeds inset under it, folded, each opening its own page, is built (cycles 77–79). Still open: how this joins "tt-rss and Google Reader API servers as full backends" below. Sections were taken out (cycle 80)
- [ ] Search on the account page's feed list (F4), if scrolling 50+ feeds on Sources gets slow
- [ ] Several categories, and tt-rss's Starred and Published as choices
- [ ] Read/unread, after trying the toggle *(you asked)*. The status mark on a source's page now marks read and unread, reaching tt-rss at the next sync. If the two toggles don't feel right on the phone, the one-button cycle (option A in the mockups) is the alternative
- [ ] Read sync: an article marked unread in tt-rss further back than its feed's newest five unread only comes back here once it's among them. Ask tt-rss about delivered articles directly if that turns out to matter

### From the UX pass *(ux)*
- [ ] Boox: consider turning off ripples on e-ink (they cause partial refreshes); Boox's own refresh modes may make it moot

### Today
- [ ] Boox: tell the app on the Boox from the app on a phone (Android's maker is Onyx). Today choosing Boox means **Read** wherever the app runs, so a reader sending with BooxDrop from a phone would read it on the phone, and that counts as sent *(you asked)*
- [ ] Check in a few weeks on the streamlined buttons: one button per card (Send, or Read on a Boox), the rest in ⋮ (the Kindle app included), and no **See what's inside** anywhere since the card opens the edition. If something's missed, [research/edition-buttons/](research/edition-buttons/index.html) has the before and after, and [research/edition-card/](research/edition-card/index.html) round 7 has See what's inside back as a button or a link line *(you asked)*
- [ ] Today's card as the paper's front page *(you asked)*. The card now shows its first three headlines in the book's order. Explored further ([research/edition-card/](research/edition-card/index.html), open it in a browser):
  - F2c: a lead story (your star, otherwise the longest read) over the next two; built, then set aside for the plainer list, as the lead competed with the edition's title
  - G1: the card carries the newspaper styling (the edition's name between double rules, an issue number, "No. 42") and Today's masthead steps back to a plain bar or a small imprint, so there's one front page per screen
  - F4: the same card under today's masthead, where the two compete
  - the lead with its opening line (F3) or its picture (F7), which need more stored per edition
  - Live with the current card first; the masthead's date could go either way, since the edition's title has it

### Reading list
- [ ] A share that only subscribes is lost if Android ends the app during the feed lookup (Both saves first). A worker would make it durable; worth it only if readers report lost shares *(G1 review)*
- [ ] Send for a look on an e-reader with no browser says so and stops there; copying the form's address would let the reader send it from a phone. And check on a device whether the GitHub app, if installed, takes the link and drops the filled-in fields *(today C1 review)*

### Accessibility *(a11y, you asked for it)*
A full pass over the app and the book, not just spot fixes:
- [ ] App: TalkBack walk-through of every screen (labels, headings, focus order), font scale at 200%, display size, touch targets ≥ 48dp, contrast in light and dark, e-ink readability, nothing carried by colour or animation alone. Confirm the new live regions with TalkBack on a device
- [ ] Book: reading order with a screen reader on a device. The metadata, alt text and heading levels are done, and Ace by DAISY passes the platform corpus with its pictures on every change to the book (`book-checks` workflow) *(a11y)*
- [ ] Generate alt text for pictures that have none *(you asked)*. Many feeds' images arrive without a description, so a screen reader (and Listen) says nothing or a file name. Do it when the edition is built, so the book gets it too, and on the phone, so nothing leaves it. Cheapest first, stopping at the first that gives something:
  - The article's own alt text or caption (free, every phone). Never replace it
  - Text in the picture, read with ML Kit text recognition (free, offline, every phone): for charts, screenshots and signs, "Image with text: …"
  - ML Kit's on-device GenAI image description, on the newer phones that have it (free, no download for us). Check which phones and whether it's still in preview
  - Otherwise just "An image"; never made up
  - Not worth it: a downloaded vision model (hundreds of MB, slow) or a paid cloud model (an account, a cost, and the pictures leave the phone)
  - Mark generated text as such

### Performance *(resources)*
- [ ] A floor device: Android 8, 2 GB RAM, slow CPU and storage (a 2018 budget phone or an older Boox) *(you asked)*. Measured so far (#352, CI's `low-end` job, a debug build): cold start about 1 s, the first edition about 10 s, the app about 90 MB. Still to do: a full 30-minute edition rather than the first small one, timed editions under Doze on Android 8 (#352 measured Doze on Android 16), whether long lists and the preview stay smooth, and budgets set from the numbers

### Recurring reviews *(audit)*
Whole-codebase passes for what a review of one PR can't see. Each follows the audit's pattern: fresh-eyes reviewers, each finding checked, a PR per area.
- [ ] **Security and supply chain** (last: 21 Oct, cycle G6; its findings are under "From the 21 Oct security review"), before each release or every 20–30 cycles: hostile feeds and pages (entity expansion, slow regexes, huge inputs), links and intents that leave the app, what CI's jobs can write, signing keys, and pinned actions and dependencies
- [ ] **Architecture and test health** (last: 22 Oct, cycle H7; its findings are under "From the 22 Oct audit"), every 30–40 cycles or before a release: duplication and code that has grown complicated, how features fit together (what one feature deletes that another still uses), tests that can never fail, waits that flake under load, and copied test helpers

### Self-update *(audit)*
- [ ] **Check on a phone: a background install while an edition sent from its notification is in another app's hands.** Send on the notification starts the mail app or e-reader from `SendEditionActivity`, outside `MainActivity`, and nothing waits for it. If an install then replaces the app, can that app still read the book? Steps: tap Send, leave the mail draft open, let the next build install, then send the mail. *(device)*

### Tech debt
- [ ] Listen sometimes stops at the paragraph instead of scrolling down to the sentence being read: its fine scroll needs the paragraph's text layout, which may not be recorded yet when the scroll runs. Seen as `11d-listen-larger-text` differing between two renders; likely on a phone too *(today B7)*

### Later
- [ ] When releases start, the live (debug) channel keeps self-updating; release builds never do.
- [ ] Send the email itself (SMTP, an app password), so a Kindle edition arrives with no tap and from a chosen account; email delivery through the mail app is done (#123)

## Feature proposals
Ideas worth doing, not yet planned. Each gets a sketch before it moves to Next.

### What you found thought-provoking
Let the reader mark articles that stayed with them (in the app, or by finishing or highlighting them on
the e-reader), and let the planner lean towards similar ones from their own feeds. It stays on the phone,
is visible and adjustable, and mustn't narrow the paper into an echo chamber: "nobody's algorithm" is the
pitch, so it has to be the reader's own.

### Smart order: rare posts first *(you asked)*
Taking turns treats every source alike, so a source that posts once a month waits its turn behind
daily ones, and its one post can expire unread. A "Smart" order would let sources that post rarely go
first. It replaces "Source by source", which only differs from Take turns with the per-source cap off;
a reader who chose it moves to Smart. The orders become Take turns, Smart and Shuffle.

- **How often a source posts** comes from what the app has already seen: the typical gap between its
  articles' dates over the last few months (the median, so one burst doesn't count as a habit). A new
  source has no history and is treated as average.
- **Who goes first:** sources with the longest gap. A daily feed will have another article tomorrow; a
  monthly one won't. Within that, an article close to its keep window ending goes ahead of a fresh one.
- **Still finite and fair:** the per-source cap and the budget hold, and stars still come first. Smart
  changes only who goes first, so frequent sources still get their turns once the rare ones are in.
- **Visible:** a source's page could say "posts about once a month", which also explains the order.
- **Open questions:** whether Smart should also replace Take turns (it behaves the same when sources
  post equally often); and whether rare sources need a longer keep window too, which would also catch a
  monthly post that lands the day after an edition is full. Count how many rare sources' articles
  expire once Smart is in: if it stays near zero, the longer window isn't needed.

### High-priority sources *(you asked)*
Mark a source as high priority and its new posts go first, the way a star puts one article first.
The reader's own choice, visible on the source's page, rather than the app guessing.

- **Order, not count:** a priority source goes to the front of the turns when it has something new,
  still within its per-source cap and the budget; stars still come first. "Allow more" stays the
  setting for how many.
- **Too many means none:** when priority sources alone fill an edition, they take turns among
  themselves like everyone else; say so on Settings rather than quietly dropping the rest.
- **With Smart:** Smart is for readers who never set anything; priority is the reader's override on
  top. Build whichever is missed first: in a simulation of one reader's 80 feeds, daily 30-minute
  editions with Take turns already lost almost no posts from rarely-posting sources.

### Sharing an article
Share an article with someone. The link is easy; the full extracted text raises copyright questions and
shouldn't become a way around paywalls. A likely middle: the link plus a short excerpt.

- [ ] **Share the original's link from a row** *(you asked)*. The article preview's top bar has it; rows don't yet (in Select mode's bar, or a TalkBack action, so rows don't get a second icon on e-ink).
  - From the book: each article already ends with "Read the original"; a Kindle can share that link itself, so nothing needed there.
  - Later: the excerpt, if the link alone feels bare.

### Reading notes: removed, and only worth bringing back as highlights
The Markdown notes file (the Notes button on an edition, and a notes folder in Settings) was removed
before the first release: nobody had tried it by hand, and a blank template wasn't used. Quotes are
shared from the article preview instead. To bring it back, revert the PR that removed it ("Remove
reading notes"), resolving whatever has moved since.
- If notes come back, only as highlights pulled back from the e-reader. Each device keeps them its own
  way: Kindle's My Clippings.txt (or Amazon's notebook), Kobo's on-device database, KOReader's sidecar
  files beside each book, Boox's exported notes.
- Start with KOReader (its sidecar files come back through the Syncthing folder) or Boox (the app runs
  on the device): those files are reachable without a cable.

### More than one schedule, and one-off editions
Several timed editions (a weekday morning paper and Sunday long reads), and a one-off custom edition
(pick sources, size) without changing the defaults.

### Add sources to tt-rss, not just the phone
With a server, Add a site now subscribes in tt-rss, into a category the reader picks (cycle 85).
Still open:
- a site tt-rss can't fetch: offer to fetch it from the phone instead, labelled as the one
  exception to "never mixed"? Today it offers only the reading list;
- unsubscribing in tt-rss from a feed's page.

### tt-rss and Google Reader API servers as full backends *(you asked)*
Consider letting a reader server be the backend for every source it can handle, rather than one
source among many. A feed the reader's server already follows would come from the server: fetched
there, read state kept there, and the same in their other reader apps. Sources the server can't
handle (saved links, curated lists) stay on the phone.
- Designed: two setups, server or phone, never mixed, with a build order ([research/server-mode.md](research/server-mode.md)).
  Steps (a), the choice in onboarding and Settings (cycle 83), (b), Sources for a server
  (cycle 84), (c), adding a site to the server (cycle 85), and (d), moving phone feeds there
  (cycle 86), and (e), leaving a server (D6), are built.
- Which servers: tt-rss (done as one source today) and the
  [Google Reader API](https://freshrss.github.io/FreshRSS/en/developers/06_GoogleReader_API.html),
  which [FreshRSS](https://freshrss.org), [Miniflux](https://miniflux.app/docs/google_reader.html),
  [Inoreader](https://www.inoreader.com/developers/), The Old Reader and BazQux speak. One Google
  Reader client covers all of them, and it lessens the risk of tt-rss living on as a
  [fork](https://linuxiac.com/tt-rss-shuts-down-but-the-project-lives-on-under-a-new-fork/).
- What a server's feed is, is settled (cycles 77–79): a publication the account's source carries,
  with its own settings and page, not a source of its own. A Google Reader API account would be
  another source carrying publications the same way. Still to settle: how this joins "Add sources
  to tt-rss".
- An architecture change: the server, not the phone, would fetch and track those feeds.

### Languages
What to do with non-English sources and readers. Today:
- the book's language is always `en`;
- the page text (contents, "min read") is English;
- reading time assumes English words per minute.

Done: each article is tagged with its own language (`xml:lang`, and `dir="rtl"` for Arabic,
Hebrew, Persian), so e-readers hyphenate and lay it out correctly; Chinese and Japanese reading
time is counted by character.

Questions:
- the book's `dc:language` when a whole edition is in one language other than English (Kindle
  picks its dictionary from it);
- whether an edition should mix languages or keep them in sections.

Translation, decided: people translate, not a machine. Nobody on the project can check a machine
translation, so it goes to volunteers who read the language. What that needs first:
- the app's text moved out of the code into `strings.xml` (today almost all of it is written
  inline in the screens), and the book's own words ("Contents", "min read") into a table per language;
- a CONTRIBUTING section on how to translate: which file to copy, how to test it, how to send it;
- optionally a hosted tool such as Weblate (free for open-source projects; the repo is MIT);
- then a call for translators in the README.

### Newsletters *(you asked)*
Many of the writers worth reading publish as newsletters. Two routes, from easy to heavy:
- **Their own feeds, first.** Substack (`/feed`), Ghost (`/rss/`), Buttondown (`/rss`) and beehiiv
  (when the writer turns RSS on) already work as sources. What to handle, recognised by the
  platform's own markers (a feed's `<generator>`, a page's `generator` meta) rather than a list of
  domains, since most use custom domains:
  - paid posts arrive as a teaser ending in "Subscribe to read" or similar: treat as summary-only
    and say so on the source, rather than fetching a paywall;
  - redirect-wrapped and tracking links (`substack.com/redirect/…`, `utm_*`): unwrap for the
    "Read the original" link and strip before fetching;
  - "Share", "Subscribe", "Leave a comment" and like buttons: page-cleanup furniture;
  - podcast episodes in the same feed (an enclosure, little text): skip or note, don't pad the paper.
- **Newsletters that only arrive by email.** Options: a Kill the Newsletter-style address
  (email in, Atom feed out; hosted or self-hosted), which needs nothing new in the app; or reading
  one folder or label over IMAP with an app password (Fastmail, iCloud, most hosts; Gmail needs
  OAuth, much heavier). Email HTML is table layouts, "View in browser" headers, unsubscribe
  footers and tracking pixels, so it needs its own cleanup pass.
- Start with the first route (it's mostly cleanup rules) and a help line pointing email-only
  newsletters at a Kill the Newsletter address; IMAP only if that proves too fiddly.
- **The app could make the Kill the Newsletter inbox itself** *(you asked)*: "Add a newsletter" asks
  for a name, creates the inbox (a form post to the service, or a self-hosted instance), shows the
  email address to paste into the newsletter's signup, and adds the inbox's feed as a source. The
  confirmation email arrives in that feed first, so the app can surface its "Confirm" link instead of
  putting it in a paper. Risks: it depends on a third party's form and goodwill (be a polite client,
  let people point it at their own instance), and the inbox address is effectively a password.

### Listen: the paper as an audiobook
The phone's voice reading an edition, and Settings › Listening for it, are built ([mockups](https://claude.ai/artifact/X9dZJi7XP3GxY85xjdLMXq)). Next:
- **A natural voice, Kokoro's, made ahead** ([mockups and spec](https://claude.ai/artifact/43jwa7iFoBUxnyxVGsiYbE)). Built so far, in debug builds only: the download and the check, making a scheduled edition's audio while charging, starting scheduled editions early for it, and playing it: Listen plays the audio where it's made and the phone's voice where it isn't, switching only between articles. The edition page shows what's made and offers **Make the audio**; the playing screen says when an article plays in the phone's voice. It's made in the foreground, with a quiet notification, so Android doesn't stop it every 10 minutes. Next: trying a real morning on a phone; if it's still slow, asking in Settings › Listening for Unrestricted battery use (the only way Android lets foreground work start overnight), or an exact alarm at the edition's start. Then taking it out of debug-only. Not built from the mockups: "Make the audio" in the edition's ⋮ menu, saying the audio is paused off the charger, and the edition-size page's warning when a bigger paper wouldn't fit.
  - **Turning it on:** a second voice in Settings › Listening, "A natural voice", beside "Your phone's voice". Kokoro (`kokoro-multi-lang-v1_0` through sherpa-onnx, 384 MB) downloads once; off Wi-Fi, Download asks before using mobile data. No setting for it.
  - **Which phones:** 64-bit only. After the download, a 20-second check estimates how long *your* paper takes to make (× 1.5 for warming up). Up to about 1 hour: "can do it"; up to 2½ hours: "slow, still fine"; beyond: "too slow", offering only Remove.
  - **When it's made:** after a scheduled edition's book is written, from the book, article by article, only while charging. Never on battery (a 30-minute paper would use about 20% of a charge). Unplugged, it stops and keeps what's made.
  - **Starting early:** scheduled editions start earlier by paper length × this phone's pace × 1.25 (× 1.1 once real times come in), shown with the ready time in Settings, Schedule and Today ("starts 5:05 for the audio; leave your phone charging"). Built; still to do: when the start moves more than 10 minutes, Schedule and Today say why ("the last three took about 70 minutes to make").
  - **Editions made by hand:** "Make the audio" under Listen and in ⋮, waiting for a charger too.
  - **Stored** compressed (AAC, about 7 MB per 30 minutes) with each sentence's start time, beside its edition and deleted with it. English only; other articles play in the phone's voice.
  - **Test with papers that aren't all English:** an article in French or German among English ones plays in the phone's voice, switching only between articles; an English article quoting French or naming people from elsewhere still reads sensibly in Kokoro; an article with no language tag; a paper that's entirely in another language.
- **A natural voice in other languages.** The Kokoro model already downloaded has voices for French, Spanish, Italian, Portuguese, Hindi and Japanese, and their pronunciation data comes with the English download; Chinese needs 17 MB more. Fewer voices than English, of unknown quality: listen first. Each article would pick a voice in its own language, with the phone's voice for languages Kokoro lacks.
  - **Measured on a Pixel 8 (Tensor G3):** the full model makes speech at 0.8× real time cool and 1.4× warm within 7 minutes, so it can't read live; a 30-minute paper takes 40–50 minutes. The compressed int8 model (170 MB) whines at 4.8 and 9.6 kHz and is no faster. 4 threads was fastest; with 1 thread the same text came out longer (17.6 s, not 14 s), which needs explaining.
- **Kokoro made in the cloud, by Claude Code** *(you asked)*. A phone that's too slow, or isn't charging overnight, would hand the edition's text to a Claude Code cloud session, started with the reader's own routine API token. The session runs Kokoro on a server, as was done for the episode simulations, and the phone downloads the audio. Open:
  - **Privacy:** the edition's articles leave the phone, which the store listing says never happens. So it's opt-in, says what's sent, and stays in debug builds.
  - **The token:** it can start sessions on the reader's account, so it's stored like the tt-rss password, and asked for only by someone who chooses this.
  - **Where the audio waits:** a session can't send files to a phone. It needs a place both can reach, such as a private release or the reader's Dropbox.
  - **Whether it's allowed:** check the terms for using Claude Code sessions as a speech farm, and what it costs against the reader's usage limits.
  - **Timing:** a session takes minutes to start and needs the phone online overnight; it would start when the book is written.
- **🔊 in the article preview**, starting the same player at that article.
- **A sleep timer**, and a way in from Today's card.
- **Save as audio:** the edition as a file with a chapter per article, for a podcast or audiobook app. With the audio made, it's already most of the way there.

### Naming the recording chips *(you asked)*

- [ ] **Your call: the chip names in Settings › Listening › Recordings,** one for each way a recording takes the voice's place:
  1. a transcript's episode in its place;
  2. an author's reading in its place;
  3. a notes post's episode at the end of the edition;
  4. maybe a notes post's episode in place of its show notes (not built).

  Suggested: **Episode instead of its transcript**, **Author's reading**, **Episode after the paper**, **Episode instead of its show notes**. For 3 and 4, one three-way choice (notes read / episode at the end / episode in their place) might be clearer than two chips. Waiting on madCode's pick; then rename the chips, the note above the page and DESIGN.md.

### Listening in the reading view *(you asked)*
The article preview (the book's page in a WebView) and the playing screen (the article redrawn
in Compose, a sentence at a time) are separate, and the playing screen loses the article's
formatting. Step 1, the bar along the bottom of every screen, is built (#398).

**Step 2, decided: one view for reading and listening**, drawn by the app from `ListenScript`,
not a WebView with JavaScript: one renderer, one language, all of it testable on the JVM.
madCode chose it on 2026-10-28 as the ideal. Each step its own PR:
- [x] **The page model:** `ListenScript` holds what's shown as well as what's said (styles, list
  numbers, tables, furniture), each sentence knowing where it's shown. Spoken lines unchanged (#400).
- [x] **The playing screen draws it** (`ui/listen/PageText.kt`): bold, links, numbered lists,
  tables, code, the byline's date and the page's furniture, at the Aa size (#401).
- [x] **The preview drawn from the page model,** no WebView. It keeps all it does now, with
  nothing of Listen's added yet:
  - Aa, Share, Go to source and Send for a look in the top bar; the episode label line; the
    "Next" line at the end; pictures at their size (as `imageSizes` sizes them now).
  - Quote sharing rebuilt on Compose's text selection, with the same "where it's from" line.
  - Pinch zoom goes (Aa covers it). DESIGN.md stops saying the page is exactly the e-reader's:
    each e-reader sets its own fonts and margins; `tools/render-epub.sh` is how to check the book.
  - Checks: before/after screenshots of every preview shot (the WebView ones come out blank on the
    JVM, so "before" comes from the CI emulator, drawn from the same book), and on a Boox by madCode.
- [ ] **One view for both:** the preview becomes the playing screen, and the separate playing
  screen goes. Opening an article, the bar along the bottom, or the media notification all land
  in it.
  - **The player** sits under the page while Listen has this edition open: the dock as now
    (edition bar, ↶ ↷ ⏮ ⏭, speed, times, episodes after the edition). Otherwise the top bar's
    **Listen from here** starts it at this article.
  - **The view and the voice stay on one article.** Scrolling within it is free (it stops
    following until "Back to where it's reading"). Going to another article while listening, by
    "Next", a link in the book or the contents, moves the voice to its start; when the voice
    finishes an article, the view turns to the next.
  - **Contents stay a pop-up** over the article, as on the playing screen now: the voice plays on
    while it's open, and picking an article moves both.
  - **Taps:** while Listen plays, a tap on a sentence reads from it and a web link opens. Not
    playing, taps do nothing but links. Long-press selects text, with quote sharing and **Listen
    from here** in its menu.
  - **The sentence being read** is tinted and underlined, and the page follows the voice.
  - **E-ink:** turn a screen at a time when the voice reaches the bottom, not scroll with it:
    each scroll there is a full refresh.
  - **Pages that aren't articles,** the closing page and the episodes after the edition, are drawn
    from their scripts in the same view, as the playing screen does now.
  - **Rotation and restore:** where the page was, the sentence tinted, an open contents pop-up or
    text selection; `StateRestorationTester` for what must happen once.
  - Checks: TalkBack (each paragraph a stop that can "Read from here", links as actions), 200% font,
    landscape (the dock's height), and a Boox by madCode.

### Podcast episodes in feeds *(you asked)*
The plan is [research/episodes-design.md](research/episodes-design.md); the research and the
labelled corpus are [research/podcast-episodes.md](research/podcast-episodes.md). Debug builds only.
- [x] **Step 1:** posts keep their audio, and each is judged at sync (#313)
- [x] **Step 2:** labels on the edition, article and source screens, and wrong labels sent for a look (#320, #321)
- [x] **Step 3:** recorded readings play in an article's place in Listen, with a switch back to the voice (#322, #323). Built before the planned two weeks of labels, at madCode's request
- [ ] **All labelled posts in one list** *(you asked)*: with about 100 sources, finding a post to star means opening each source's page. A debug-build list of every post the detector labelled, newest first, with its tag, its source and a star, would let the two weeks of labels be tried on purpose. Where it lives (Settings, or a filter on Sources) is open
- [ ] **The two weeks of labels:** what the design's "Before readings go into Listen" asks, now checked alongside step 3 rather than before it. The podcast log now says how far each recording got and which transcripts could have played their episode: share it at the end
- [ ] **Rule 3's silent stub:** an audio version's own short post is still read by the voice when it's in the same edition as its article. Listen needs a way to pass over a page
- [ ] **Readings for editions made before #322:** their audio was never timed, so an article's own audio isn't played. Time what's missing when Listen opens an edition
- [x] **Step 4, transcripts:** a transcript plays its episode in its place, streamed
- [x] **Switches for recordings** in Settings › Listening: all of them, or by kind (#384)
- [ ] **A source's own switch for its recordings** (#384): madCode wants it, with a shorter source page; the layout is still being chosen on the issue
- [x] **Step 4, notes: their episode plays at the end**, after the closing page, streamed
- [ ] **Keep the place in an episode after the edition:** paused there, it plays on while the app is running, but if Android ends the app the edition starts again from the top (it counts as heard) and the episode has to be found in the contents. Which episodes there are can change by the next opening, so it has to be kept by the episode's address, not its page
- [ ] **Step 4, notes read without their furniture:** timestamps, sponsor blocks, link lists and subscribe lines left out when the voice reads notes
- [ ] **The notes say their episode is at the end** ("The episode, 38 minutes, is at the end of this edition."): a line the voice reads after the notes. Kokoro's audio is kept only when made from the same lines Listen reads, and the maker doesn't know which episodes play, so the line has to be decided the same way in both first
- [ ] **Substack voiceovers play in place:** an author's reading isn't in the feed; one request to the site's posts API finds it, for sites that record them. Measured in R7 of `docs/research/podcast-episodes.md`
- [ ] **Step 5:** downloads for listening offline
- [x] **The `handle-issues` skill** handles "Episode label:" and "Extraction:" issues: CLAUDE.md's "Issues the app files" says how
- [x] **An `episodes` label** on the repo, for the wrong-label reports to carry (`extraction` too)

#### From the 19 Oct audits *(audit)*
The middle and end architecture and code-simplicity audits of the episodes work. Their small findings are fixed in #324; these are the larger ones, each checked against the code.
- [ ] **One place decides what plays an article.** `ReadingSpeaker` wraps `PodcastSpeaker`, and each has its own `choose()`; the recording's heading reaches the podcast speaker as plain speech so it doesn't play the article. One choice per article (recording, podcast or phone voice) over the phone speaker would remove that, and the two fallbacks to the voice (`restart` and `PodcastSpeaker.asked`)
- [ ] **`StreamAudio` copies `Media3Audio`** almost line for line; one player taking a list of media items would do for both, and `StreamAudio` has no test of its own meanwhile
- [ ] **Line reporting is written twice**, in `ReadingSpeaker` and `PodcastSpeaker` (from a line and a position, report each line's start, and the end)
- [ ] **`useVoiceInstead` lives in `AppContainer`** and isn't tested; it belongs on the player
- [ ] **A speaker's listener is set by whoever comes last:** `ReadingSpeaker` takes over the voice's in its constructor, and only the debug-build checks in `AppContainer` keep something else from taking it back
- [ ] **The rules for updating earlier posts at sync** (`FeedSync.episodes`) are a three-way condition explained in comments; as one pure function beside the detector, they could be tested in `:core`
- [ ] **`Readings` repeats the contents' pair join** (`observeContents`) with two queries a page; the contents could carry the audio fields
- [ ] **The debug-build flag is checked at several layers** where one gate in `FeedSync` and one in Listen would do, and `FeedSync`'s `episodesOn` defaults to on

### Backup *(you asked)*
Android's Auto Backup already copies the database and settings (sources, reading list, edition
history) to the reader's Google account, within its 25 MB quota. Past EPUBs, the schedule timer and
the tt-rss password are left out on purpose: the password is sealed by a key that never leaves the
phone. What's missing:
- it's invisible: nothing in the app says it's on, when it last ran, or what comes back;
- a restore has never been tried on a real phone (the timer re-arms, but does a restored tt-rss
  source ask for its password clearly?);
- no manual copy: one "Export everything" file (sources as OPML, the reading list as the
  library's Markdown checklist, settings), for people without Google services or moving to another
  reader, and a matching import.

The shape asked for: **Settings › Back up to a file** and **Restore from a file**, one file saved
wherever the reader likes (Drive, Syncthing, a computer), holding everything the app knows:
- sources with their per-source choices (left out, article text, caps, skip paid posts), the
  reading list, stars, curated lists, the schedule and settings;
- **past editions' EPUBs**, so the archive survives a new phone. They're what makes the file
  large, so offer it as a choice ("include past editions, 84 MB") and say the size first;
- never the tt-rss password: a restore asks to sign in again, as Auto Backup does.

Restoring onto a phone that already has sources asks first whether to replace or merge.

### From the competitor research ([docs/research/competitors.md](research/competitors.md))
- **Kobo through Google Drive.** Kobo syncs a "Rakuten Kobo" Drive folder natively. Drive's SAF provider
  has no folder trees, so this needs the Drive API (an OAuth client, like Dropbox's app key).
- **Close the loop from the device:** finished on the e-reader means archived; KOReader highlights come back (see Reading notes above).
- **More importers:** Matter, Readwise, Raindrop and Omnivore exports, for people leaving shut-down apps.
- **An OPDS catalog served from the phone,** for KOReader and jailbroken Kindles.

## Parked and decided against

- **Generated summaries:** not doing. The paper gives whole articles; AI-shortened digests are what
  the competitors do, not what this app is for.
- **A heart for "loved this / keep it", synced to tt-rss:** skipped by madCode, 16 Oct. The ☆ already
  means "put it in my next edition", and tt-rss's own star means "keep". Whether tt-rss's star should
  feed the ☆ was a separate question: madCode chose an opt-in, one-way setting, built 16 Oct.

### Views across your feeds, and a look at tomorrow's paper (decided against)
Decided with madCode, 18 Oct, narrowing **Rethink Sources for a big subscription list** to the hop
from an article to its feed, built the same day (in Done).
- **All the newest articles across feeds, as tt-rss has them:** not doing. With about 100 feeds and
  five unread taken from each at a sync, that list is hundreds of rows — the endless timeline
  principle 3 exists to avoid. The app is where you edit your paper, not where you read it.
- **A look at what's in tomorrow's paper:** not doing, because it can't be told truthfully. Feeds
  sync right before each build (otherwise every 12 hours), so most of what goes into a 6:30 AM paper
  hasn't arrived the evening before, and turn-taking order depends on which source was featured
  longest ago. A preview would often not match the paper, and a wrong one is worse than none: it
  invents a queue to tend. Not knowing is part of a paper that arrives.
- **What's starred, what's waiting, what went in recently:** already answered where the question
  comes up. Today's starred line; a feed's page, its newest 30 articles each saying where it stands
  ("Waiting, 3 days left", "In Thursday's edition", "Sent Wednesday"); and the earlier editions
  under Today.
- Bring back if, after living with the hop, there's one slice you keep reaching for. That would be a
  far narrower brief than this one was.

### Sections in the paper (taken out)
Taken out in cycle 80, with OPML folders, the reading list's "Saved for later" heading, tt-rss
categories as sections and balancing by section. A 30-minute paper is often 8 articles, so headings
mostly sat over one article each, and they added a setting to every feed's page. Feed readers group
feeds into folders to find them, which is what the inset list on Sources does; the paper doesn't
need it. Bring back if longer papers make the contents hard to scan.

### Local news for your city or country (parked)
Parked: hard to do well, and curated lists go stale. Adding a local paper by its address already works.
Help people find news sources near them: local papers, public broadcasters, city blogs.
- Options include a curated starter pack per country or region, and location-based Google News feeds.
- A "near me" search could use the device's locale, without needing a location permission.
- The hard part is keeping curated lists current and fair. Starter packs must stay public, well-known
  feeds only.

### An iOS app (parked)
Parked: Android comes first. Kept here for the notes.
Possible, but a second app rather than a port:
- `:core` is plain Kotlin, but leans on JVM libraries (jsoup, Readability4J, OkHttp). Kotlin
  Multiplatform would need replacements (Ksoup, Ktor, a Readability port), then Compose
  Multiplatform or SwiftUI for the screens.
- iOS decides when background work runs (`BGAppRefreshTask`), so "ready by 6:30" can't be
  promised the way Android's timers allow; a notification to build on opening may be the honest version.
- Delivery works: the share sheet reaches Send to Kindle, Dropbox (Kobo) and Files; Boox and
  KOReader users are mostly on Android anyway.
- It needs a Mac to build and an Apple developer account ($99 a year) to ship.
A first step, if wanted: move `:core` to Kotlin Multiplatform, which also keeps the logic shared.

## Done

- [x] **A not-sent edition made the real way** *(N1)*: `ListenAppTest.anEditionReleasedByTheNextBuildStillOffersItsBook` builds two editions with the real builder and checks the first's page offers Resume where it was left, Mark as sent, its articles and Save a copy. A note the builder writes that the screens don't know turns it red. The audio line on the page is the next item's
- [x] **Feeds: a linear entity check, and at most 1,000 items** *(H3, #376)*: the check reads the prolog once, past comments, and Android's parser is held to not expanding declared entities (`FeedParserEntityTest`); a feed keeps its 1,000 newest items by date
- [x] **`AccessibilityChecksTest` no longer hangs the device job** *(H1, #374)*: the tap after switching to 200% text landed on the activity Android was about to recreate, and the new one's splash waited on a frame from the test's clock. It now waits for the new activity. 4 failures in 10 runs before, under the same 10-emulator matrix; 0 in 20 after
- [x] **Accessibility tooling** *(a11y)*: Google's accessibility checks (the engine Accessibility Scanner uses) run on every screen a new reader meets, on the emulator in CI (#347). Not in the Robolectric tests: there the checks run on nothing and pass
- [x] **Onboarding's website search says its result to TalkBack** *(approved, #347's review)*: as well as "Checking…" and a saved page, a found feed ("Found Blog. It's ticked below.") and the reason nothing was found are announced, once each, and again for a second search with the same answer. Still to hear on a device: whether submitting with the keyboard's Done also speaks the field's own error over it (G4)
- [x] **A deleted edition's title is reused when it was never sent** *(you asked)*: one deleted while ready and never delivered (`deliveredAt` empty, which "mark as not sent" keeps) gives its title to the next edition, so a remake after a delete has no " (2)". One delivered, released as not sent, or deleted while a folder copy or a share was finishing keeps its title taken. Left: a ready book sent by a route the app can't see (Save a copy, or opened in another app, without Mark as sent) is counted as never sent, as its articles already are; editions deleted before this change don't say whether they were released (G3)
- [x] **A right password just after a wrong one** *(approved)*: tt-rss turns every sign-in away for 5 seconds after a wrong password, and its answer is the same as for a wrong password. A refusal for the same login less than 5 seconds after the last says "wait 5 seconds and try again"; `TtrssLiveTest` holds tt-rss to the 5 seconds (G2)
- [x] **Subscribe from a share** *(you asked, #290 option C)*: every share asks Save, Subscribe or Both; a front page leads with Subscribe; the feed is found after the tap, on the phone or in tt-rss, and the page is saved instead if there's nothing to follow (#356)
- [x] **`PodcastSettingsTest` timing out under load** *(today B9, the flake)*: `show()` waited on the view model's first state by draining the main looper, while the effects that subscribe to it run on the Compose test rule's own clock and dispatcher; under load a frame was resumed on a coroutine worker, either stuck or touching views off the main thread. The wait idles Compose too now. Run side by side under the same load: 4 failures in 20 before (three at that wait, one `CalledFromWrongThreadException`), 0 in 20 after, and 0 in 40 more after on a quieter machine (load 5-11) (#340)
- [x] **Save a copy… of an edition** *(you asked)*: the edition page's ⋮ opens Android's save dialog and copies the EPUB where you pick; a device with no save dialog says so (#338)
- [x] An edition's choices to hear an article in the voice instead of its recording go when its book does; `kindName`/`labelName` are one table and the stored kind has one lookup (19 Oct audits, #337)
- [x] A feed's titles, links, guid and author are capped so a hostile feed or page can't make a row too big to read back (titles 1,000 characters, authors 300, a post with a link or guid over 4,000 isn't kept); database version 17 cuts what's stored (#336)
- [x] **Approved:** every saved link gets a reading time, slowly: twice a day on Wi-Fi while charging, 20 at a time, three passes for a page that won't load (#335)
- [x] **From an article in your paper to its feed** *(you asked)*: **Go to *Quanta Magazine*** in the article preview's ⋮ opens the page of where the article came from — a tt-rss feed's own page, the reading list, or the source's — which is where its cap, its article text, leaving it out and marking it unread are. It follows the page on screen, as Share does. Not in the contents row: it is tight at 48dp, keeps the ☆'s slot, and rows don't take a second icon on e-ink. The views across feeds the item also asked for are in Parked and decided against
- [x] Coverage no longer counts what `ScreenshotTest` draws: the screenshots run as their own task, which Kover doesn't measure; coverage went from 94.73% to 94.32% (#269, your call: merged)
- [x] A smaller APK: one per ABI, as you chose. A phone's release APK is 50.6 MB (arm64) instead of 86.8 MB; older 32-bit e-readers get an 18.6 MB one without the natural voice; Play's bundle is unchanged (today B13)
- [x] A saved link that can never be read (not a web page, a 410, too large) says why in the reading list, can still be opened or removed, and editions stop trying it; saving it again looks again (database version 14, #283)
- [x] Starred in tt-rss goes in the paper: an opt-in, one-way, each star once (today D8, madCode's call on the morning page)
- [x] A quiet tt-rss feed says so under its row: "Nothing new in 3 weeks" (today D4)
- [x] **Send for a look**: ⋮ › Send for a look… on an article's preview says the report is a public GitHub issue, then opens GitHub's form with "Extraction: *title*", the address and the app's version filled in (#251)
- [x] A snackbar on screen as the app goes to the background is shown again in full on its return, Undo and all, on Sources, the reading list, a source's page, Settings and an edition's page; a reading-list removal waiting for Undo is settled then rather than left in the database (#258)
- [x] ~~Stop making and Delete audio run on the screen's scope without `NonCancellable`~~: checked, not a bug. Both are blocking file writes launched straight onto `Dispatchers.IO`, so leaving the page can't cut them short once tapped; `NonCancellable` only matters for a suspending call like Make's (#258)
- [x] Rotation keeps Listen's "Finish X first?" and contents, and the category list of Add a site and Move feeds; after Android ends the app, a restored question is checked again and the category list goes with its dialog (#264)
- [x] "No feed found" in onboarding offers to save the page to the reading list instead, as Sources does (#266)
- [x] A short Kindle how-to in setup (the Kindle app on the same Amazon account, then Library › Docs), and per device, in setup and under Send it myself, when an edition counts as sent and what to do if it never arrives (#270)
- [x] Typed text in Add a site and Settings' tt-rss sign-in survives Android ending the app; never the password (#276)
- [x] A build reads each article's feed text when it reaches it, rather than every waiting article's at once: 300 articles of 50,000 characters held 15 MB before, 82 KB after (today B11)
- [x] Editions with pictures are written about three times faster: pictures go into the book stored, not compressed again, through a buffered stream (today B8)
- [x] Screenshots are drawn without ripples, so two renders match (today B7)
- [x] Test health from the 16 Oct audit: each test's app has its own settings, tt-rss and feed-move stores, stopped at teardown; one way to stop a test store; one shared wait in place of three 5-second loops; source-page view models stopped at a test's end; a cut-short feed through FeedSync; `SourceDetailScreenTest` on a fixed clock; `NotesWorkerTest` able to fail; `PodcastMakerTest`'s fake counting atomically (today B1–B6, B9)
- [x] An edition released because a newer one was made counts as sent when it is sent anyway, from a share sheet, its notification or Today, as Mark as sent already did, so its articles don't go out again; and its Ready notification comes down when it is released (audit and UX pass, 16 Oct)
- [x] A timed edition takes the place of one made by hand that is still waiting for a connection, rather than being dropped, so the paper comes with its Ready notification, audio and retries (audit, 16 Oct)
- [x] The book of a build the process died during is deleted when the next build finds it, rather than staying in the app's storage for good (audit, 16 Oct)
- [x] Leaving the server just as a feed is moved leaves it a phone feed, not paused with no server to come from: the move's check and the pause are one step with leaving (audit, 16 Oct)
- [x] Moving off tt-rss brings the paper's feeds along as phone feeds, with their settings; starred articles stay with the account (today D6)
- [x] tt-rss: an opt-in "Mark read when they expire here"; an old backlog drains on arrival instead of coming through the paper (today D2)
- [x] Book: EPUB Accessibility 1.1 metadata, alt text counted honestly, article headings that never skip a level, the contents list's role; EPUBCheck and Ace pass (today D5)
- [x] tt-rss marks delivered articles read a couple of minutes after delivery, so a quick Mark as not sent usually never reaches it (today D3)
- [x] Signing in as another user on the same tt-rss server starts without the first user's articles and stars, as signing in to another server does, and a sync still running for the first user can't add theirs back (audit, 16 Oct)
- [x] Removing a source, a saved link or the tt-rss account while an edition is being made no longer throws the finished edition away: the removed articles stay in the book and are listed by title, as a removed source's past ones are (audit, 16 Oct)
- [x] Substack Notes in posts from tt-rss come back: the post is read from its page's data, one fetch, only where a Note was (today D1)
- [x] Tapping a notification brings back the app as it was rather than starting it afresh (audit, 16 Oct)
- [x] One feed item too big for Android to read back (a picture inlined as text) no longer stops every edition: feed text is capped when stored, and rows stored before are shrunk once, keeping a post's words (database version 10)
- [x] Audio, video without a poster, and other sites' players (Spotify, SoundCloud, Pocket Casts…) leave a line where they were, linking to the file or the player, instead of vanishing under their lead-in
- [x] Page cleanup takes out French, German and Spanish furniture too: "Lire aussi", "Mehr zum Thema", "Lee también" and the like, as headings over a box of links and as cross-link lines, Le Monde's long ones included
- [x] A bot check served instead of a feed (SiteGround answers with a 202 and a captcha page) says the site turned the app away, when adding the site and on a source already added, rather than "not a feed" or "it may have moved"
- [x] Screenshots render the same on any day and at any time: the screens read the time from one clock, which `ScreenshotTest` fixes at a Tuesday morning, and the README's pictures were redrawn from it
- [x] Test DataStores on a scope that ends with the test, so a write can't outlive its folder (#190)
- [x] Listen: an edition read aloud in the phone's voice, a sentence at a time, with the article's text following along, lock-screen controls, and where you stopped kept (#162) *(you asked)*
- [x] Onboarding asks "Where do your feeds live now?" with three tap cards (pick sites, your own RSS server, another reader app), and another reader app starts with importing its OPML file; Sources with a server is one list, the account only showing when something's wrong, phone feeds still to move under "Still on this phone", and curated lists in a group *(you asked)*
- [x] Moving your phone feeds into tt-rss: a banner on Sources and an offer right after signing in, a sheet with every feed ticked and one category, stepped progress in the background, "Move the other 3" for what didn't move; settings carried over, and a moved feed kept, paused and hidden, until its stars are delivered; step (d) of [research/server-mode](research/server-mode.md) *(you asked)*
- [x] Adding a site with a server subscribes in your tt-rss, in a category you pick (the last one used), with Undo; already there, no feed, or tt-rss refusing each say so and offer the reading list or a curated list; OPML items give way to Where your feeds come from; step (c) of [research/server-mode](research/server-mode.md) *(you asked)*
- [x] Sources for a server: your tt-rss account first, then what's on this phone, then the server's categories as headings with their feeds A to Z, Uncategorized, Not in your paper and Left out last; without a server, nothing of tt-rss; step (b) of [research/server-mode](research/server-mode.md) *(you asked)*
- [x] Server or phone, chosen up front: a question in onboarding, a Settings page for the choice and the tt-rss account, and a "Sign in to your tt-rss" state; step (a) of [research/server-mode](research/server-mode.md) *(you asked)*
- [x] Your tt-rss feeds on Sources: inset under the tt-rss row, folded until you open them, each with its own page (leave out, article text, cap, its articles); a daily feed list from tt-rss names every feed; article text and cap are per publication, so a tt-rss feed has its own
- [x] Article text is checked, not assumed: each edition reads the pages of up to 5 long items, one per publication (a feed here, or one feed in tt-rss), which each learn on their own; pictures the feed's copy lacks count for the page
- [x] Email editions straight to your Kindle: its own address, your mail app opened ready to send *(you asked, #123)*
- [x] Paid posts say they're only the free part; a source can skip the ones with nothing free (a title and a picture), off by default, with a count on its page *(you asked)*
- [x] Skip paid posts is per publication, so each tt-rss feed has its own switch on its page; a tt-rss account's switch carried over to the feeds already seen *(you asked)*
- [x] After a send with the Kindle app, a line says it can take a few minutes to show up in the library *(device)*
- [x] A text size setting for the article preview (Aa in its top bar) *(your brother asked)*
- [x] Comics in the preview: large pictures standing alone fill the width, and the page can be pinched to zoom *(you asked)*
- [x] A webcomic's hover text (xkcd's title text) is a caption under the image in the book *(device)*
- [x] Sync errors on a source's page use the add dialog's plain words, with advice that fits a source already added *(ux)*
- [x] A tt-rss timeout says the server took too long, not that it couldn't be reached *(personas)*
- [x] tt-rss: leave a feed out of the paper from the source's page; it isn't fetched, and its waiting articles go except starred ones *(personas)*
- [x] No Open for Kindle and Kobo readers, who send the book rather than open it on the phone *(personas)*
- [x] tt-rss "Start fresh" for a reader back after a break: marks everything older than two weeks read on the server, after asking, and refuses on servers too old to limit it *(personas)*
- [x] A question to think about on each edition's closing page, from a short list that suits any paper, *(personas)*
- [x] Reading notes saved to a folder of your choice (an Obsidian vault) for every delivered edition, whether it was shared, saved to a folder or opened *(personas; removed before release, see Reading notes above)*
- [x] Link posts (Longreads' picks): a short item whose link to another site carries a referral tag naming its own site is stored as that story, fetched and credited "Equator via Longreads", with its pitch as the fallback; tracking tags come off stored links, so a story two sources picked goes out once *(you asked)*
- [x] Sources take turns starting with the one featured longest ago, not shifting by one each edition, so with many feeds every one gets its turn *(personas)*
- [x] Stars: `☆ Next edition` on a source's articles and on delivered editions puts an article first in the next edition (it replaces bring back); the planner takes stars first, taking turns across sources, within each source's slots and the budget; Today says how many are waiting
- [x] Article rows in a source's list: tap opens the original; "Mark as read" with Undo, marked read in tt-rss at the next sync
- [x] tt-rss sync takes up to five unread articles from each feed instead of the newest 200 overall, so busy feeds can't crowd out quiet ones *(personas)*
- [x] A daily live check of each curated list's page in CI (`live-check.yml`), after Arts & Letters Daily's markup changed under the app
- [x] Saved links in onboarding: import a Pocket or Instapaper export there, and start with saved links alone
- [x] "No feed found" in Sources offers to save the page to the reading list instead of a dead end
- [x] Change the e-reader in Settings after onboarding (it decides Send or Open, and the tips)
- [x] Deleting an edition takes down its "ready" or "delivered" notification, whose Send would have shared a missing file
- [x] Say why there's no edition: a timed run with nothing new sends a quiet "No new edition"; when every source failed it's retried, then a failure that says so, not "nothing new"; a build queued offline shows "Waiting for an internet connection"
- [x] Fastly's bot challenge (Le Monde's "Client Challenge") is recognised: the feed's text is used with a note, and the source learns the site blocks fetching
- [x] Reading time for Chinese and Japanese counts characters (about 350 a minute), so a paragraph isn't one word; the full-text check no longer takes a long Japanese feed for a teaser
- [x] Extraction drops scripts, styles and SVGs before Readability copies the page (a 2.6 MB script-heavy page: 36 MB allocated before, 16 MB after, twice as fast); pages over 5 MB aren't parsed and use the feed's text
- [x] Documentation pass: DESIGN.md describes the app as it is (no SMTP, profiles or reading-speed setting; today's changes in), README and CLAUDE.md updated, fresh screenshots; documentation passes are now part of the cycles
- [x] TalkBack: the build's stage is announced (not every count), onboarding's progress says "Step 2 of 3", earlier editions say "See what's inside"; Add and Save sit below their fields so large fonts leave room to type
- [x] Each article is tagged with its language (`xml:lang`, `dir="rtl"`), detected from its text (writing system, common words) with the page's declared language as a tiebreaker
- [x] The article preview shows at once and reads the book in the background, with one open zip per screen (the "first tap doesn't open it" report). The side-scroll came from the old EPUB's long URL line, already gone
- [x] An HTTP cache: feeds are revalidated (If-None-Match), and unchanged ones answer 304 instead of the whole feed; the background sync runs every 12 hours with the battery not low
- [x] Housekeeping from the resource audit: only the newest 14 editions keep their EPUB; old articles drop their feed text (a month after delivery); the image budget counts as spent when nothing more fits. Streaming images to disk dropped: at most 15 MB, not the real peak
- [x] Reading list: every saved link's page is measured for a reading time (database version 2, the first migration), rows open in the browser, and an untitled row shows a title made from its address
- [x] Source page: a real "Recent articles" heading, a status mark per article (● ✓ ○), stronger titles, lighter inset dividers; "Article text: Automatic" says what it sets
- [x] Webcomics: feeds linked only from the page are found (God Slave's `/comic/rss`), and a page's own comic (`#cc-comic`, `#comic`) beats the feed's thumbnail
- [x] An app icon: a cream newspaper page on navy, after the Termux shortcut's icon; themed icons get the newspaper glyph
- [x] EPUB design, round 1: source kicker, byline and rule, justified hyphenated text, links and rules in the text's colour (KOReader night mode), "Read the original at site.com" (no long URL widening the page), a cleaner contents page
- [x] "See what's inside" on Today, and deleting an edition (its title stays taken, so Send to Kindle doesn't drop a remake)
- [x] Comics and cartoons keep their image: an image-only feed item isn't dropped as empty, and a cartoon page gives its main image instead of its footer
- [x] The per-site cap gives way when there's room: with one source (the New Yorker) an edition held one article
- [x] "Edition ready" makes a sound (its own default-importance channel); "delivered" stays quiet
- [x] Dated edition titles ("Tuesday Morning Edition, Sep 29"), so next week's Tuesday doesn't collide in libraries and folders
- [x] Sending from the "ready" notification counts: choosing an app in the share sheet marks the edition delivered (it was released the next day and its articles repeated)
- [x] Onboarding: import an OPML file or connect tt-rss on the sources step, instead of only after setup
- [x] Research: competing and neighbouring apps, in [docs/research/competitors.md](research/competitors.md)
- [x] Timed editions start 30 minutes before they're due, so Doze's hold on delayed work becomes lead time instead of a late paper
- [x] Extraction: screen-reader-only text ("list 1 of 4") and "Recommended stories" link lists no longer reach the edition (found in a live edition's Al Jazeera article)
- [x] tt-rss: take articles from one category, and a setting to leave delivered articles unread on the server, both on the source's screen
- [x] Per-source article cap on the source screen, replacing the edition's "up to N from each site" for that site (not for tt-rss, which is capped per publication)
- [x] Source detail: tap a source for its health (status, failing for N days, last checked, where its text comes from), its recent articles and what happened to each, pause, article text and remove with a confirmation
- [x] Delivered links are remembered for a year, so removing and re-adding a source, or the same story in two sources, doesn't deliver it twice
- [x] Real-world check: a live edition from the starter feeds passes epubcheck (stray figcaptions fixed; `./gradlew :core:liveEdition`); a source settled on the feed's text keeps probing short items, so a lifted block is noticed
- [x] Tech debt: feed parser smoke-tested on Android's own XmlPullParser; HTML meta-charset and byte-order-mark sniffing
- [x] Curated list sources: a scraper per site (Arts & Letters Daily first), each list its own source, keeping its newest 12 unread links; added from the Add a source dialog
- [x] Notes export: a Markdown notes file per edition (details, citation, reflection prompts) from Edition detail *(removed before release)*
- [x] App shell: splash until settings load, onboarding survives process death, timer re-armed on time-zone change, launch test
- [x] Auto-tune each source's ContentMode from its articles (three in a row), a manual choice that's never overridden, and per-source full-text health on the Sources screen
- [x] Import Pocket (HTML, CSV) and Instapaper (CSV) exports into the reading list; saved links without a title get the page's title in the background
- [x] tt-rss source (one account, all unread, marked read after delivery); in-app article preview (#9)
- [x] M1 Skeleton: Gradle (AGP 9.1, Kotlin 2.3, Compose), `:core` + `:app`, CI, design doc (#1)
- [x] M2 Feeds: parser, finder, OPML, Room, FeedSync, Sources screen (#1)
- [x] OPML import/export in Sources; source freshness ("last new article 2 days ago") instead of unread counts (#8)
- [x] Architecture pass: no stuck editions, timer can't go stale, IO off main; e-ink progress, first-edition moment, Try again (#7)
- [x] UX pass from persona audit: delivery confirmation, notification permission, Boox Open, next-edition line, check chips (#6)
- [x] Edition detail with bring back; generated cover image (#5)
- [x] M6 Reading list: share target, reading list screen, markdown checklist import/export compatible with rss-to-e-reader (#4)
- [x] M5 Onboarding: device, starter packs, paste a site, size and schedule, first edition (#3)
- [x] Images in editions: 1200px JPEG, per-edition allowance so over-budget images aren't downloaded (#3)
- [x] M4 Settings, scheduled editions (timer chain that never skips an overdue edition), folder delivery, notifications (#2)
- [x] M3 The edition: planner, extraction, EPUB writer, EditionBuilder, Today screen, share/open (#1)
