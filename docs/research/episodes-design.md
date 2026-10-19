# Podcast episodes: design

A proposal, not yet a plan. It says how the app should treat posts that are podcast episodes,
in the paper and in the audio edition. The research behind it, and the labelled corpus that
tests detection, are in [podcast-episodes.md](podcast-episodes.md).

## In short

- **The paper doesn't change.** Every post prints, whatever kind it is.
- **The audio edition uses the real audio where it's clearly better:** a show's episode, or an
  author's reading of their own post. Kokoro reads everything else, as today.
- **Real audio plays in the post's place**, unless it's much longer than the post. Then the post
  gets one line in its place, and the audio plays at the end of the edition.
- **When in doubt, Kokoro reads the post.** A wrong handover plays something the reader didn't
  choose; a missed one only means Kokoro reads.

## What a post can be

The detector gives each post one kind when it syncs.

| Kind | What it is | Examples |
|---|---|---|
| Article | Anything else, including essays and write-ups that carry audio | |
| Notes | The post that comes with an episode: a description, a guest bio, timestamps, sponsors | Every post of an audio-only feed; an episode post in a newsletter |
| Transcript | An episode's words in full, as its own post or inside the episode post | "The Tim Ferriss Show Transcripts: …"; Dwarkesh's episode posts |
| Audio version | A short post carrying the narration of a written post in the same feed | Letters from an American's readings; Racket's "Listen to This Article" |

An article can also be **narrated**: the audio attached to it is the same text read aloud. That
isn't a kind of its own; it's decided by comparing lengths (below).

## How good detection is

Measured on the episode corpus (421 labelled posts from 41 public feeds) and checked on 15
public feeds it hadn't seen and on one reader's own list of 91 feeds:

| Call | Right in the corpus | What it misses |
|---|---|---|
| Notes | 73 of 75 | A long guest introduction with no timestamps; a bare video post |
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
| Notes | The episode | One line in the post's place; the episode at the end |
| Anything else | Kokoro reads it | In the post's place |

### The rules behind the table

1. **Keep the edition's shape.** Real audio plays in a post's place when it's about as long as
   Kokoro reading the post would be. Narrations are the same length as their text. A
   transcript's episode is close to the transcript read aloud (87 minutes against 99 for one
   Tim Ferriss episode, 3 h 44 min against 3 h 22 min for one Lex Fridman episode). Notes are
   the mismatch: a minute or two of text against an episode of 40 minutes to 4 hours.
2. **Notes say one line, not their text.** Timestamps, sponsor codes and link lists don't read
   aloud well. In their place Kokoro says: "Lenny's Newsletter: How Meta designed Muse. An
   episode, 38 minutes, at the end of this edition."
3. **Each recording plays once.** An audio version's stub, a notes post whose transcript is in
   the edition, and a newsletter post about an episode that's already playing are silent in the
   audio edition. They still print. In one simulated edition, four of nine posts shared another
   post's audio.
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
8. **One switch per source:** "Use this source's own audio", on by default, on the source page.
   For a feed the detector gets wrong, or a reader who'd rather hear Kokoro.

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

  So step 1 asks for attachments and unsanitized content, and cleans tt-rss posts with the
  app's own cleaner, as it does feed posts; extraction through tt-rss is re-checked against the
  platform corpus in the same step.
- **Later: the post's page.** Some sites put the player on the page and leave it out of the
  feed, so a transcript there reads as an article. Reading the page finds the player.
- **Later: following a player to its audio.** A player isn't an audio file. Anchor and Apple
  Podcasts players lead to the show's public feed, which has the episode's file; a Spotify link
  leads nowhere playable, since Spotify publishes no audio files.
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
2. **Readings in place:** audio versions and narrated articles (rules 3, 4, 5 and 7). The
   biggest audible gain for the least risk: same words, same length, a human voice.
3. **Episodes:** transcripts in place, notes' episodes at the end, streamed (rules 1, 2, 6
   and 8).
4. **Downloads** for listening offline.
5. **Later:** reading the post's page; following Anchor and Apple players.

## Open questions

- Is 25% the right margin for "narrated"? Measure it on the corpus once lengths are fetched.
- Should episodes download by default?
- The line on an episode post in the paper: worth it, or noise?
- A post of 700–1,000 words with audio and no timestamps (a long guest introduction): read in
  full, or treated as notes?
