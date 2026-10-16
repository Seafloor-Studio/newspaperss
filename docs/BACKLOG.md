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
- [ ] **Sending an edition a newer build has already released does nothing, and its articles go out again** (checked). The notification's Send and Read, the share sheet's report and Today's Send all mark sent only if READY, without the "or released, with no build running" case `markSentByHand` has; and releasing an edition leaves its Ready notification up
- [ ] Another tt-rss user, or another server, still meets the first one's delivered links: `delivered_urls` is shared, so a story the first user received is skipped for the second, and marked read on their server. Small, but partners often follow the same blogs. Forget the delivered links that came only from the old source when it goes? *(review, 16 Oct)*
- [ ] Restored onto a new phone, the database comes back without the tt-rss login (it's left out of backups), so whoever signs in next keeps the waiting and starred articles, as the same user would. Ask on sign-in whether it's the same account? *(review, 16 Oct)*
- [ ] A timed edition is dropped while a manual one waits for a connection (`EditionWorker` enqueues with KEEP, checked), and the manual run then has no Ready notification, podcast or retries
- [ ] An EPUB written by a build the process died during is never deleted: `failInterrupted` marks it FAILED without removing the file, which has no `fileName` yet (checked)
- [ ] Leaving the server as a feed move finishes can leave a phone feed paused: the move's check and the pause aren't one step (a window of milliseconds)
- [ ] Sources' and the reading list's snackbars are shown while the app is in the background, so a tt-rss subscribe's answer and its Undo can time out unseen. Confirm on a device first
- [ ] Typed text in Settings' tt-rss sign-in and the Add a site dialog is lost if Android kills the app while it's in the background (onboarding keeps it with a SavedStateHandle)
- [ ] Rotation drops Listen's "Finish X first?" and contents dialogs and the category step of Add a site and Move feeds (`remember`, not `rememberSaveable`)
- [ ] Stop making and Delete podcast run on the screen's scope without `NonCancellable`, unlike Make beside them (checked)
- [ ] Coverage counts the pictures `ScreenshotTest` draws (85 tests, 4 assertions) as tested code. Proposed in [#269](https://github.com/madCode/newspaperss/pull/269), your call: the screenshots run as their own task, which Kover doesn't measure; coverage goes from 94.73% to 94.32%
- [ ] About 100 view models built directly in tests (Today, Settings, Sources…) are still never stopped, as the source page's were; `testutil/ViewModels` stops them, so adopt it as those tests are touched *(today B3)*
- [ ] `PodcastSettingsTest.choosingToReadLiveDuringTheDownloadStopsIt` timed out once in six full runs, at its 30 s wait for the settings screen to load, on a machine running other builds. Watch it *(today B9)*
- [ ] CLAUDE.md says its shared sections match claude-playground's; several rules have drifted (tests "waits for the condition", the review's background-work bullet, the PR template, "merge only when asked"). Your call whether to bring them across
- Next audit of this kind: after 30–40 more cycles, or before a release

### Onboarding and setup
- [ ] A short Kindle how-to: same Amazon account, pick the device in Send to Kindle, Library › Docs *(personas)*
- [ ] Explain per device what counts as "delivered" *(personas)*

### Sources and fetching
- [ ] **Platform pass** *(you asked)*: a test corpus of real posts from the big blog and newsletter platforms, from their own feeds and through tt-rss, checked for footnotes, embeds, captions, paywall teasers and link posts; what breaks gets fixed and the corpus keeps it fixed (`PlatformCorpusTest`). Round 1 covered Substack, Ghost, WordPress, Medium, Blogger, Buttondown and Micro.blog; round 2 added a magazine with its own site (New Left Review), Stratechery and a MemberPress site, found by running `extractionSweep` over a real reading list and over `tools/sweep-feeds.opml`, public feeds picked for variety (news, other languages and scripts, static sites, microblogs). Run that list again before a change to extraction. Still to add: Tumblr (it answers 429 to the collector), beehiiv, posts with galleries, tweets and Bluesky embeds, and more footnote styles
- [x] **Show tt-rss's own feed errors** *(you asked)*: tt-rss's `last_error` for each feed, on the feed's row and page and named in a banner on Sources
- [ ] **Proposal: "Send this article for a look"** *(you asked)*. On an article's page in the app, a link that opens GitHub's new-issue form with the article's address, the app's version and an `extraction` label filled in, for the daily issue check to pick up: it compares the result with the page, fixes what's wrong and adds the page to the corpus. No GitHub sign-in inside the app; the browser does that. It must say first that the issue, and so the link, is public. Waiting for your go-ahead
- [ ] Paywalled and summary-only sites: warn when a site is added; keep stubs from eating the budget; drop metered sites from starter packs *(personas)*
- [x] A feed over 10 MB was refused as too large: some static sites (a Hugo `index.xml`) put every post they ever wrote in one feed. Now it's read up to 10 MB, keeping the whole items before the cut *(sweep)*
- [ ] "No feed found" in onboarding: offer the reading list there too (done in Sources) *(personas)*
- [ ] Webtoons: episodes are one long strip of dozens of lazy images (`data-url`), beyond the 20-image cap, and its mobile site hides the feed. Support strips properly *(device)*

### The book
- [ ] EPUB design, round 2: the cover image and a look on real devices (Kindle, Kobo, KOReader) *(device)*
- [ ] Substack Notes when a Substack post is read from its page (PAGE mode, or an excerpt): the page shows a placeholder where the Note goes, so it vanishes. The page's data has the whole post (see `SubstackPost`); a free post could be read from there *(found in D1)*

### Delivery and schedule
- [ ] Dropbox connection (OAuth PKCE) so Kobo delivery is automatic; waiting on an app key ([#18](https://github.com/madCode/newspaperss/issues/18)) *(personas)*
- [ ] Boox: offer folder delivery into the Books folder, so editions stay in the library *(personas)*
- [ ] Folder delivery: tt-rss marks articles read as soon as the file is saved, before Syncthing has synced; old editions pile up in the folder *(personas)*
- [ ] Verify folder delivery and the chooser from the notification on a real device
- [ ] If lead time isn't enough on a real device, wake timed editions with an exact alarm (Doze defers WorkManager; expedited work can silently restart a long build)

### tt-rss, for a returning reader *(personas)*
- [ ] Decide what tt-rss is as a source. Explored ([research/ttrss-backend/](research/ttrss-backend/index.html), open it in a browser): one tt-rss row in Sources with its feeds inset under it, folded, each opening its own page, is built (cycles 77–79). Still open: how this joins "tt-rss and Google Reader API servers as full backends" below. Sections were taken out (cycle 80)
- [ ] A quiet tt-rss feed says so under its row ("Nothing new in 3 weeks"). tt-rss's feed list gives when it last fetched a feed, not when it last posted, so this needs the newest article's date from getHeadlines, once a day
- [ ] Search on the account page's feed list (F4), if scrolling 50+ feeds on Sources gets slow
- [ ] Several categories, and tt-rss's Starred and Published as choices
- [ ] **Your call:** should tt-rss's star feed the ☆? Recommended: an opt-in, one-way "Starred in tt-rss goes in my next edition" setting, not a two-way sync, since the ☆ clears on delivery and syncing that back would unstar articles in tt-rss. On the morning page
- [ ] Moving off tt-rss: **I pick my own sites** brings the feeds along as phone feeds, the reverse of moving phone feeds in. The daily feed list already has each feed's address, title and category. Feeds in the paper come with their settings (cap, article text, skip paid posts), left-out ones stay left out, and a feed already on the phone or without an address yet is skipped and said so. Open: whether starred articles come too (today they go with the account). About a cycle. Until then the leave dialog points to tt-rss's OPML export *(you asked)*
- [ ] Articles that expire in the app stay unread in tt-rss: an opt-in "mark read when they expire here"
- [ ] Read/unread, after trying the toggle *(you asked)*. The status mark on a source's page now marks read and unread, reaching tt-rss at the next sync. If the two toggles don't feel right on the phone, the one-button cycle (option A in the mockups) is the alternative
- [ ] Read sync: an article marked unread in tt-rss further back than its feed's newest five unread only comes back here once it's among them. Ask tt-rss about delivered articles directly if that turns out to matter

### From the UX pass *(ux)*
- [ ] An edition released because another was made by hand keeps its Ready notification (with Send) until the next timed one replaces it
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
- [ ] Saved links that can never be read (a PDF, a video, a page over 5 MB, a 410) wait silently forever. Show them in the reading list as unreadable, with the reason and a way to open or remove them. Not as "couldn't fetch" pages in the edition: they cost no reading time, so a backlog of them could fill one
- [ ] Links saved before database version 2 with a title never get a reading time (no backfill)

### Accessibility *(a11y, you asked for it)*
A full pass over the app and the book, not just spot fixes:
- [ ] App: TalkBack walk-through of every screen (labels, headings, focus order), font scale at 200%, display size, touch targets ≥ 48dp, contrast in light and dark, e-ink readability, nothing carried by colour or animation alone. Confirm the new live regions with TalkBack on a device
- [ ] Book: reading order with a screen reader on a device. The metadata, alt text and heading levels are done, and Ace by DAISY passes a live edition (D5); that run had no pictures, so try one with them *(a11y)*
- [ ] Tooling: Compose accessibility checks in the Robolectric tests, Accessibility Scanner on a device, Ace by DAISY on a live edition
- [ ] Generate alt text for pictures that have none *(you asked)*. Many feeds' images arrive without a description, so a screen reader (and Listen) says nothing or a file name. Do it when the edition is built, so the book gets it too, and on the phone, so nothing leaves it. Cheapest first, stopping at the first that gives something:
  - The article's own alt text or caption (free, every phone). Never replace it
  - Text in the picture, read with ML Kit text recognition (free, offline, every phone): for charts, screenshots and signs, "Image with text: …"
  - ML Kit's on-device GenAI image description, on the newer phones that have it (free, no download for us). Check which phones and whether it's still in preview
  - Otherwise just "An image"; never made up
  - Not worth it: a downloaded vision model (hundreds of MB, slow) or a paid cloud model (an account, a cost, and the pictures leave the phone)
  - Mark generated text as such

### Performance *(resources)*
- [ ] A floor device: Android 8, 2 GB RAM, slow CPU and storage (a 2018 budget phone or an older Boox). Measure on an emulator with that profile how long a 30-minute edition takes, peak memory, whether timed editions still arrive under Doze, and whether long lists and the preview stay smooth; set budgets from the numbers *(you asked)*
- [ ] A feed's title, link, guid and author are stored uncapped: a hostile feed (a `data:` link, a megabyte title) could still make a row too big for the 2 MB Android reads it through. Cap them as feed text now is *(review)*
- [ ] A smaller APK *(you asked)*. It went from 20–40 MB to 94 MB with the podcast, because sherpa-onnx's native code is in it twice, stored uncompressed: about 32 MB for arm64-v8a and 36 MB for x86_64. Three ways, with what each costs:
  - **One APK per ABI** (preferred): about 57 MB for a phone, and nothing stops working. The cost: two files on the release page, CI and the `latest-debug` publish adjusted, and F-Droid's build recipe given the split.
  - **Drop x86_64**: one APK of about 57 MB, but no podcast on emulators and x86 Chromebooks. First check whether the device tests' emulator needs it.
  - **Compress the native code** (`useLegacyPackaging`): about 50 MB to download, but Android unpacks a copy on install, so it takes more room on the phone than now.

### Recurring reviews *(audit)*
Whole-codebase passes for what a review of one PR can't see. Each follows the audit's pattern: fresh-eyes reviewers, each finding checked, a PR per area.
- [ ] **Security and supply chain**, before each release or every 20–30 cycles: hostile feeds and pages (entity expansion, slow regexes, huge inputs), links and intents that leave the app, what CI's jobs can write, signing keys, and pinned actions and dependencies
- [ ] **Architecture and test health** (last: 16 Oct, cycle 6 of that run; its findings are at the top of Next), every 30–40 cycles or before a release: duplication and code that has grown complicated, how features fit together (what one feature deletes that another still uses), tests that can never fail, waits that flake under load, and copied test helpers

### Tech debt
- [ ] Listen sometimes stops at the paragraph instead of scrolling down to the sentence being read: its fine scroll needs the paragraph's text layout, which may not be recorded yet when the scroll runs. Seen as `11d-listen-larger-text` differing between two renders; likely on a phone too *(today B7)*

### Later
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

### Notes: what should they be?
The edition notes export (a markdown checklist of each edition's articles) went in without much thought
about who uses it. Research first: what people do with a read list; whether to import highlights from
Kindle (My Clippings), Kobo or KOReader; where notes should live (the app, or a markdown file elsewhere).

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
  (cycle 86), are built. Next: (e) leaving a server (see "Moving off tt-rss" above).
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
- **A podcast in Kokoro's voice, made ahead** ([mockups and spec](https://claude.ai/artifact/43jwa7iFoBUxnyxVGsiYbE)). Built so far, in debug builds only: the download and the check, making a scheduled edition's podcast while charging, starting scheduled editions early for it, and playing it: Listen plays the podcast where it's made and the phone's voice where it isn't, switching only between articles. The edition page shows what's made and offers **Make the podcast**; the playing screen says when an article plays in the phone's voice. It's made in the foreground, with a quiet notification, so Android doesn't stop it every 10 minutes. Next: trying a real morning on a phone; if it's still slow, asking in Settings › Listening for Unrestricted battery use (the only way Android lets foreground work start overnight), or an exact alarm at the edition's start. Then taking it out of debug-only. Not built from the mockups: "Make the podcast" in the edition's ⋮ menu, saying the podcast is paused off the charger, and the edition-size page's warning when a bigger paper wouldn't fit.
  - **Turning it on:** a second voice in Settings › Listening, "Make a podcast in a natural voice". Kokoro (`kokoro-multi-lang-v1_0` through sherpa-onnx, 384 MB) downloads once; off Wi-Fi, Download asks before using mobile data. No setting for it.
  - **Which phones:** 64-bit only. After the download, a 20-second check estimates how long *your* paper takes to make (× 1.5 for warming up). Up to about 1 hour: "can do it"; up to 2½ hours: "slow, still fine"; beyond: "too slow", offering only Remove.
  - **When it's made:** after a scheduled edition's book is written, from the book, article by article, only while charging. Never on battery (a 30-minute paper would use about 20% of a charge). Unplugged, it stops and keeps what's made.
  - **Starting early:** scheduled editions start earlier by paper length × this phone's pace × 1.25 (× 1.1 once real times come in), shown with the ready time in Settings, Schedule and Today ("starts 5:05 for the podcast; leave your phone charging"). Built; still to do: when the start moves more than 10 minutes, Schedule and Today say why ("the last three podcasts took about 70 minutes").
  - **Editions made by hand:** "Make the podcast" under Listen and in ⋮, waiting for a charger too.
  - **Stored** compressed (AAC, about 7 MB per 30 minutes) with each sentence's start time, beside its edition and deleted with it. English only; other articles play in the phone's voice.
  - **Test with papers that aren't all English:** an article in French or German among English ones plays in the phone's voice, switching only between articles; an English article quoting French or naming people from elsewhere still reads sensibly in Kokoro; an article with no language tag; a paper that's entirely in another language.
- **Podcasts in other languages.** The Kokoro model already downloaded has voices for French, Spanish, Italian, Portuguese, Hindi and Japanese, and their pronunciation data comes with the English download; Chinese needs 17 MB more. Fewer voices than English, of unknown quality: listen first. Each article would pick a voice in its own language, with the phone's voice for languages Kokoro lacks.
  - **Measured on a Pixel 8 (Tensor G3):** the full model makes speech at 0.8× real time cool and 1.4× warm within 7 minutes, so it can't read live; a 30-minute paper takes 40–50 minutes. The compressed int8 model (170 MB) whines at 4.8 and 9.6 kHz and is no faster. 4 threads was fastest; with 1 thread the same text came out longer (17.6 s, not 14 s), which needs explaining.
- **🔊 in the article preview**, starting the same player at that article.
- **A sleep timer**, and a way in from Today's card.
- **Save as audio:** the edition as a file with a chapter per article, for a podcast or audiobook app. With a podcast made, it's already most of the way there.

### Listening in the reading view *(you asked)*
Today the article preview (the book's own page in a WebView, with Aa, pinch zoom and quote sharing)
and the playing screen (the same article redrawn in Compose, one sentence at a time) are separate.
They split because the playing screen tints and taps single sentences, which a WebView with
JavaScript off can't do. Two steps, the first useful alone:

**1. A mini player in the reading view (small, one PR).** The preview's 🎧 starts Listen from that
article without leaving the page.
- A bar at the bottom of the preview while Listen plays: the article playing, play/pause, ↶ ↷, and
  a tap to open the full playing screen. The playing screen stays as it is.
- The page doesn't follow the voice: reading on, or opening another article, leaves the bar playing.
- The same bar could later sit on the edition page and Today.
- Cost: the playing screen's dock, made smaller; the player already outlives screens.
- Open questions: whether ⏮ ⏭ belong on the bar; on e-ink, no progress bar that redraws every second.

**2. One view for both (about two PRs).** The playing screen goes; the preview becomes it.
- While listening, the paragraph being read is tinted on the book's page and the page follows it;
  scrolling away stops following, as now.
- The bar from step 1 is the player, with the playing screen's edition bar and times.
- **Decided: contents stay a pop-up** over the article, opened from the top bar, as on the playing
  screen now: the voice plays on while it's open, and picking an article moves both. The book's own
  contents page would be a page of its own, breaking "the view and the voice stay on one article".
- Gains: one place to read and listen, the book's own look, Aa and quote sharing while listening.
- **Decided: tint the paragraph, not the sentence.** ↶ ↷ still move by sentence. The walk that
  builds `ListenScript` also marks the page, wrapping each piece of text in a `<span>` numbered with
  its paragraph: text never crosses tags, so the wrapping is always valid, and one walk means the page
  and the podcast can't disagree. Sentence tints later only if paragraphs feel too coarse; that needs
  the text split at sentence boundaries too.
- **Decided: JavaScript on, with no bridge.** Moving the tint without reloading needs JavaScript,
  and it is safe here: the preview already answers every request from the book or with nothing, and
  article HTML has its scripts and `on…` attributes stripped. Page code talks to the app only by
  going to a made-up `listen:` address, which the WebView client catches, so even hostile markup
  could only colour text. To confirm when building: `javascript:` links are dropped.
- **Decided: tapping.** While Listen plays, a tap on text plays from its paragraph; real links still
  open. When it isn't playing, taps work as now, and the long-press menu gets "Listen from here"
  beside quote sharing.
- **Decided: on e-ink, turn a whole screen at a time** when the voice reaches the bottom, rather
  than scrolling with it: each scroll there is a full refresh.
- **Decided: the view and the voice stay on the same article.** Scrolling within it is free (it
  stops following until "Back to where it's reading"). Going to another article while listening,
  by "Next", a link in the book or the contents, moves the voice to its start; when the voice
  finishes an article, the view turns to the next. So the bar never has to show an article other
  than the one on screen. Nothing else can move it: the view only shows this edition's book, and
  other links open in the browser.
- **Rotation and restore:** the WebView is rebuilt; it re-tints from the player's line when the page loads.
- **Images:** an image's description is a line with no text on the page; tint the picture's frame.

Recommendation: build step 1, use it for a week, then decide on step 2.

### Podcast episodes in feeds *(you asked)*
Some posts are podcast episodes, and the paper treats them as articles. Measured on one real
reading list (91 feeds, about 1,450 recent posts), about 4% carried podcast audio, in 12 feeds.
They came in these shapes, with what the paper does with each today:
- **An audio-only feed.** The app reads the show's web page instead, which runs several
  episodes' notes together: every episode came out as the same 350-word jumble.
- **An episode post with only notes.** Either a timestamped list of topics (270–660 words) or
  sponsors, links and timestamps (1,200–2,200 words). Each is printed as if it were an article.
- **A transcript.** About 9 posts (0.6%), from 5 feeds: 7,600–25,000 words, so 30–105 minutes
  to read. One alone can be three times a 30-minute paper. Most came from one interview show
  that posts a transcript beside every episode's notes; the rest were one-offs inside a feed's
  usual posts.
- **An episode with a real write-up** (1,700–3,300 words of essay or takeaways). It reads well
  as an article; nothing needs to change.
- **The same episode twice.** Notes and transcript as two posts sharing one audio file; a
  serial's audio version posted beside its written chapter (a 100-word stub next to 2,000
  words); a long write-up and a podcast post on the same subject.
- **Not podcasts at all.** Audio and video embeds were mostly music: Spotify tracks, albums and
  playlists outnumbered its episodes two to one, and a music blog embeds SoundCloud. YouTube
  embeds are video versions of podcasts as often as plain videos.

What the feeds give to tell them apart:
- **Reliable:**
  - An audio file attached to the post, from the big hosts (Substack, art19, Spotify for
    Podcasters).
  - The same audio file on two posts.
  - "Transcript" in a title.
  - A list of ten or more timestamps.
  - A Spotify embed's kind: an episode, not a track.
- **Not reliable:**
  - The episode's length: Substack gives none, so text length can't be compared with audio
    length from the feed alone.
  - Conversation formatting ("Name:" turns). Written Q&A interviews, fiction told in dialogue
    and conference write-ups look the same: 12 such posts against 9 real transcripts, and the
    written interviews are what their readers subscribe for.
- **Neither path keeps attachments today.** The feed parser and the tt-rss sync both drop
  them, so detection starts by storing them (tt-rss reports them with each article).

Proposed:
- **Each post gets a kind when synced:** article, episode notes, episode transcript, or the
  audio version of another post. When in doubt it stays an article: wrongly dropping reading is
  worse than printing one stub.
- **Episode notes, and audio-only feeds, aren't printed as articles.** They go in a short
  "Episodes" list at the end of the edition: show, title, and length when known. It takes almost
  nothing from the edition's length.
- **Pairs keep the readable one.** Of notes and transcript, or a stub and its written chapter,
  the paper takes the transcript or the chapter, and drops the other.
- **A transcript is one only when tied to an episode:** audio attached to the post itself, the
  same audio as another post, or "Transcript" in its title. Conversation formatting alone never
  makes one, so written interviews stay articles.
- **A show that posts notes and a transcript for each episode** gets the episode listed once
  under Episodes; printing its transcripts is that source's opt-in. A one-off transcript among a
  feed's usual posts stays an article, under a long-read cap so it can't take over the paper.
- **Visible on the source's page:** "Posts episodes: notes only", with one control.
- **Test corpus:** made-up feed items in each shape above, with the music-embed and link-roundup
  cases kept as checks against false alarms.
- **Later, if the list proves useful:** Listen plays an episode's own audio, downloaded on Wi-Fi
  while charging. One voice reading a conversation's transcript is the worst of both.
- **Open question:** whether to fetch an episode's length (a quick request for the audio file's
  size) to tell a short write-up from notes for a two-hour show; the shapes above suggest
  timestamps and word counts are enough.

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
- **Close the loop from the device:** finished on the e-reader means archived; KOReader highlights feed the notes export.
- **More importers:** Matter, Readwise, Raindrop and Omnivore exports, for people leaving shut-down apps.
- **An OPDS catalog served from the phone,** for KOReader and jailbroken Kindles.

## Parked and decided against

- **Generated summaries:** not doing. The paper gives whole articles; AI-shortened digests are what
  the competitors do, not what this app is for.
- **A heart for "loved this / keep it", synced to tt-rss:** skipped by madCode, 16 Oct. The ☆ already
  means "put it in my next edition", and tt-rss's own star means "keep". Whether tt-rss's star should
  feed the ☆ is a separate question, on the morning page.

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

- [x] A build reads each article's feed text when it reaches it, rather than every waiting article's at once: 300 articles of 50,000 characters held 15 MB before, 82 KB after (today B11)
- [x] Editions with pictures are written about three times faster: pictures go into the book stored, not compressed again, through a buffered stream (today B8)
- [x] Screenshots are drawn without ripples, so two renders match (today B7)
- [x] Test health from the 16 Oct audit: each test's app has its own settings, tt-rss and feed-move stores, stopped at teardown; one way to stop a test store; one shared wait in place of three 5-second loops; source-page view models stopped at a test's end; a cut-short feed through FeedSync; `SourceDetailScreenTest` on a fixed clock; `NotesWorkerTest` able to fail; `PodcastMakerTest`'s fake counting atomically (today B1–B6, B9)
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
- [x] A question to think about on each edition's closing page, from a short list that suits any paper, and shown in its notes *(personas)*
- [x] Reading notes saved to a folder of your choice (an Obsidian vault) for every delivered edition, whether it was shared, saved to a folder or opened *(personas)*
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
- [x] Notes export: a Markdown notes file per edition (details, citation, reflection prompts) from Edition detail
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
