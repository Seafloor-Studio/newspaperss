# Podcast episodes: design

A proposal, not yet a plan. It says how the app should treat posts that are podcast episodes,
in the paper and in the audio edition. The research behind it, and the labelled corpus that
tests detection, are in [podcast-episodes.md](podcast-episodes.md).

## In short

- **The paper doesn't change.** Every post prints, whatever kind it is.
- **The audio edition uses the real audio where it's clearly better:** a show's episode, or an
  author's reading of their own post. Kokoro reads everything else, as today.
- **Real audio plays in the post's place**, unless it's much longer than the post. Then Kokoro
  reads the post's own words in its place, and the audio plays at the end of the edition.
- **When in doubt, Kokoro reads the post.** A wrong handover plays something the reader didn't
  choose; a missed one only means Kokoro reads.
- **One tap goes back to Kokoro.** While a recording plays, Listen offers "Read it in Kokoro's
  voice instead", and the log notes it.
- **Debug builds only**, all of it: the labels, the reports and the recordings in Listen. A
  release build keeps no audio and judges nothing until it has been tried out.

## What a post can be

The detector gives each post one kind when it syncs. A post stored without one (from before
detection, or a sync where it failed) is judged at a later sync, if it's among the source's last
14 days of posts and the feed still lists it (through tt-rss: it's still unread) or its audio was
kept. It then keeps the audio the feed shows, as a new post does. Older ones stay unlabelled.

| Kind | What it is | Examples |
|---|---|---|
| Article | Anything else, including essays and write-ups that carry audio | |
| Notes | The post that comes with an episode: a description, a guest bio, timestamps, sponsors | Every post of an audio-only feed; an episode post in a newsletter |
| Transcript | An episode's words in full, as its own post or inside the episode post | "The Tim Ferriss Show Transcripts: …"; Dwarkesh's episode posts |
| Audio version | A short post carrying the narration of a written post in the same feed | Letters from an American's readings; Racket's "Listen to This Article" |

An article can also be **narrated**: the audio attached to it is the same text read aloud. That
isn't a kind of its own; it's decided by comparing lengths (below).

## How good detection is

Measured on the episode corpus (433 labelled posts from 42 public feeds) and checked on 15
public feeds it hadn't seen and on one reader's own list of 91 feeds:

| Call | Right in the corpus | What it misses |
|---|---|---|
| Notes | 76 of 78 | A long guest introduction with no timestamps; a bare video post |
| Transcript | 51 of 53 | A transcript whose audio is only in a paid feed; a solo episode's transcript |
| Audio version | 11 of 11, every pair found | – |
| Narrated article | not measured yet | – |

- **No article has been called anything else**, in the corpus, the unseen feeds or the reader's
  list. Every miss is an episode post treated as an article: the safe way round.
- **The corpus flatters it:** the detector was tuned on it. The unseen feeds are the fairer test.
- **Narration is the weak spot.** It needs the audio's length (below), and the corpus needs
  measuring with it before the rule is trusted.

## The paper

- **Everything prints.** Notes, transcripts and audio versions are printed as they are today.
  Deciding what's in and out of the paper is more cleverness than the gain is worth.
- **Maybe: one line on an episode post**, "Episode · 1 h 27 min", so it's clear on e-ink that
  there's audio behind it. Often the length is unknown, and the line says only "Episode".

## The audio edition

### When the real audio plays

| Post | Plays | Where |
|---|---|---|
| A written post with an audio version | The author's or narrator's reading | In the post's place |
| A narrated article | Its reading | In the post's place |
| A transcript | Its episode: its own audio, or its notes post's | In the post's place |
| Notes | The episode | Kokoro reads the notes' prose in the post's place; the episode at the end |
| An article carrying an episode (a blog post opening with the week's episode) | The episode | Kokoro reads the whole post in its place; the episode at the end |
| Anything else | Kokoro reads it | In the post's place |

### The rules behind the table

1. **Keep the edition's shape.** Real audio plays in a post's place when it's about as long as
   Kokoro reading the post would be. Narrations are the same length as their text. A
   transcript's episode is close to the transcript read aloud (87 minutes against 99 for one
   Tim Ferriss episode, 3 h 44 min against 3 h 22 min for one Lex Fridman episode). Notes are
   the mismatch: a minute or two of text against an episode of 40 minutes to 4 hours.
2. **Notes are read, without their furniture.** Timestamps, sponsor blocks, link lists and
   subscribe lines don't read aloud well, so Kokoro leaves them out and reads the rest, then
   says: "The episode, 38 minutes, is at the end of this edition." Most notes say something the
   episode doesn't replace for someone who never gets to it. In 40 of the corpus's notes posts,
   the prose left once the furniture is out is about 240 words in the middle one, and 14 have 300
   words or more:
   - **a blurb** (The Bulwark, Letters from an American), under about 100 words: a sentence or two;
   - **a teaser and outline** (Lenny's: an opening and "What you'll learn"), 250–400 words: the
     gist of the episode;
   - **a written companion** (The Pragmatic Engineer's "11 takeaways", Ground Truths'
     commentary), 600 to 1,600 words or more: read in full, as the article it is.
3. **Each recording plays once.** A post whose recording another post in the edition already
   plays is still read as rule 2 says, without playing it again. Only an audio version's stub,
   which is just a pointer to the reading, is silent; it still prints. In one simulated edition,
   four of nine posts shared another post's audio.
   - **An article carrying an episode** (Sullivan's weekly post, opening with the episode, then
     other sections) is read in full, and its episode plays at the end. Its paid "Transcript:"
     post is labelled a transcript and paired with it, but plays nothing: what prints of it
     is decided as for any paid post.
4. **A narrated article is one whose audio is within about 25% of its text read aloud** (160
   words a minute, as Listen reads). In the simulations the two narrations came within 15%;
   a write-up beside an interview, a blog post opening with its episode, a magazine story
   carrying a whole episode, and a music session were 3 to 100 times longer.
5. **Say what's coming before a handover.** Kokoro reads the source and title, then "the
   episode itself, one hour twenty-seven minutes" or "read by its author, twelve minutes". The
   switch from Kokoro to a show's theme music is jarring without it.
6. **Episodes at the end don't count toward the edition's length.** The budget covers what Kokoro
   reads and the readings in place. The listen screen says what's extra: "Episodes: 2, 2 h 10 min".
   Counting them would let one long episode fill several editions.
7. **Fall back to Kokoro** when the audio won't load, needs a paid sign-in, is much shorter than
   expected (likely a preview), or wasn't downloaded and the phone is offline.
8. **Back to Kokoro in one tap, where the recording plays.** While a recording plays, the Listen
   screen offers "Read it in Kokoro's voice instead". Kokoro reads that post from the start, and
   the edition goes on as before. Tapping again goes back to the recording. It's in Listen, not
   Settings, because the reason is usually this one recording: it's a preview, or it's mostly ads,
   or the narrator is hard to follow.
   - **The choice sticks for that post**, across restarts, and is made again when the edition's
     audio is made again.
   - **The podcast log records each switch**: the post, its kind, the recording, and how far in.
     That shows which recordings people turn off, and whether some source's always are.
   - **A per-source switch can come later**, if the log shows one source switched off again and
     again.

### Streaming and downloading

- **Readings download with the edition.** They're 5–15 MB.
- **Episodes stream when the phone is online.** Downloading them is an opt-in, on Wi-Fi while
  charging; an edition with three episodes is 150–400 MB.
- **Downloaded audio goes with the edition's audio**, and is deleted with it.

## Getting the audio

- **Keep attachments.** A feed post can name an audio file with its address, type, size and
  often length; it's how podcast apps find episodes. The feed parser and the tt-rss sync both
  drop it today, so the post needs somewhere to keep it: a database change, with no change on
  screen.
- **Detect before cleanup.** Extraction strips players and embeds from an article, and the
  detector reads them (an episode player; the video that pairs a newsletter post with its
  episode). Detection runs on the post as the feed sends it, and stores its verdict.
- **The audio's length.** Many feeds give none (Substack doesn't). One small request for the
  file's size gives it, at the file's bitrate. Only needed for posts with audio.
- **tt-rss.** Checked against a real tt-rss (current version, in Docker) reading 14 corpus
  feeds, with the API asked exactly as the app asks:
  - **Attachments are kept**, but only returned when the app asks for them
    (`include_attachments`), which it doesn't yet. tt-rss keeps the address and type; the file's
    size lands in a field it calls `duration`; the episode's real length (`itunes:duration`) is
    dropped.
  - **Its sanitizer strips every player** (Spotify, YouTube, Anchor, Megaphone) and every
    `data-` attribute, which removes Substack's player for uploaded audio.
  - **Asking for `sanitize=false` returns the post as the feed sent it.**

  | Through tt-rss, 161 labelled posts | Right | Articles misclassed |
  |---|---|---|
  | As the app asks today | 90 | 0 |
  | With attachments | 147 | 0 |
  | With attachments, unsanitized | 159, the same as from the feed | 0 |

  Attachments alone cover what matters for listening: every post the app could hand over to
  real audio has an audio file. What unsanitized content adds is labels for posts with no file
  to play (a transcript whose only audio is a player, a newsletter post paired by a video). So
  step 1 asks for attachments only. Unsanitized content waits until the labels show it matters:
  it would also change how tt-rss posts are cleaned for the paper.
- **Later: the post's page.** Some sites put the player on the page and leave it out of the
  feed, so a transcript there reads as an article. Reading the page finds the player.
- **Later: following a player to its audio.** A player isn't an audio file. Anchor and Apple
  Podcasts players lead to the show's public feed, which has the episode's file; a Spotify link
  leads nowhere playable, since Spotify publishes no audio files.
- **Later: Substack voiceovers.** An author's reading of their own post isn't in the feed, but
  the post's entry in Substack's posts API names it, and it plays. See R7 in
  [podcast-episodes.md](podcast-episodes.md).
- **Not planned: paid audio.** Some publications keep the audio in a members' podcast feed at a
  private address (Stratechery). The only way in is for the reader to add that feed as a
  source, which the app could then match by title.

## What it costs

- **Ads.** A show's audio comes with its sponsor reads, sometimes stitched in per listener.
  Nothing in the feed says where they end, so Listen can't skip them. Say so once.
- **Two kinds of voice.** Kokoro, then a person, then Kokoro. The handover line helps.
- **Misses stay misses.** A transcript with no audio anywhere the app can see is read by Kokoro,
  one voice for two people.
- **Storage and data**, if episodes are downloaded.
- **Work in Listen.** The player mixes Kokoro's pages with the shows' files and keeps the place
  in a three-hour episode across restarts. Media3 can do it; it's the biggest part of the build.

## Build order

1. **Keep attachments, and label each post's kind** at sync, from the feed and from tt-rss.
   Nothing visible changes. Everything else depends on it.
2. **Show the labels, and take corrections.** A post the detector thinks is an episode says so
   in an edition's contents, at the top of the article and in a source's newest articles; a
   source's page says how often it posts episodes. "Send for a look…" gains a second reason,
   "It's labelled X, and it's…", which opens a public issue with the post's address and both
   labels. Each report becomes a corpus case before it's fixed.
3. **Readings in place:** audio versions and narrated articles (rules 3, 4, 5, 7 and 8). The
   biggest audible gain for the least risk: same words, same length, a human voice.
4. **Episodes:** transcripts in place, notes read and their episodes at the end, streamed
   (rules 1, 2, 3 and 6).
5. **Downloads** for listening offline.
6. **Later:** reading the post's page; following Anchor and Apple players; unsanitized content
   from tt-rss.

### Debug builds only

Every step here goes into debug builds only: the kinds stored at sync (step 1), the labels and
reports (step 2), and the recordings in Listen (steps 3–5). A release build keeps no audio and
judges nothing. Each is moved to release builds on its own, once it has been tried out.

### Before readings go into Listen

Step 3 starts once two weeks of labels in real use show:
- no article labelled as anything else, or each one reported, added to the corpus and fixed;
- audio versions and their pairs right, since step 3 swaps Kokoro for exactly those readings;
- the remaining misses of known kinds (a player only on the page, paid audio, a transcript
  whose only audio is a player);
- the same labels through tt-rss as from the feeds;
- the narration margin measured on the corpus, with the audio's length fetched.

## Open questions

- Is 25% the right margin for "narrated"? Measure it on the corpus once lengths are fetched.
- Should episodes download by default?
- **Recorded audio on or off, at three levels:** for everything, per source, and per kind
  (narrations and audio versions, transcripts, episode notes). Transcripts sit between the
  other two: their episode is the same words, but with a show's ads and intros. The UX is
  undecided: where the switches live, and whether they sit with Listen's per-article switch.
- The line on an episode post in the paper: worth it, or noise?
- A post of 700–1,000 words with audio and no timestamps (a long guest introduction) is
  called an article. Now that notes are read too, the only difference is whether its episode
  plays at the end. Keep the line at 700 words until step 2's reports say otherwise.
