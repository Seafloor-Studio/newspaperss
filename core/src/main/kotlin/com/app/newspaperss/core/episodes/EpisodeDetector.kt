package com.app.newspaperss.core.episodes

import com.app.newspaperss.core.feed.Enclosure
import com.app.newspaperss.core.feed.FeedItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * What a post is, as an edition would treat it (docs/research/episodes-design.md). A write-up
 * or a narrated article is an [ARTICLE]: it reads well and is printed as one.
 *
 * @property label how the corpus's labels.json names it.
 */
enum class EpisodeKind(val label: String) {
    ARTICLE("article"),
    /** The post that comes with an episode: a description, timestamps, sponsors. */
    NOTES("notes"),
    /** An episode's words in full, as its own post or inside the episode's. */
    TRANSCRIPT("transcript"),
    /** A short post carrying the narration of a written post in the same feed. */
    AUDIO_VERSION("audio-version"),
    ;

    companion object {
        fun of(label: String) = entries.first { it.label == label }
    }
}

/** One feed entry, with what detection reads from it. */
data class EpisodePost(
    val guid: String,
    val title: String,
    val link: String,
    val html: String,
    val enclosures: List<Enclosure>,
    /** How long its audio runs, when the feed says. */
    val duration: Int? = null,
) {
}

/**
 * A feed's posts in the order it lists them: detection reads a post beside the others, since
 * notes and their transcript, or a narration and its post, are two entries of one feed.
 *
 * @property podcast the feed says it is one, with Apple Podcasts' channel tags.
 */
data class EpisodeFeed(val posts: List<EpisodePost>, val podcast: Boolean = false) {
    companion object {
        fun of(items: List<FeedItem>, podcast: Boolean = false): EpisodeFeed {
            // A post is known by its guid; a blank or repeated one would merge two posts' verdicts.
            val seen = mutableSetOf<String>()
            val posts = items.mapIndexed { i, item ->
                val guid = item.guid.takeIf { it.isNotBlank() && seen.add(it) } ?: "${item.guid}#$i"
                EpisodePost(guid, item.title, item.url, item.contentHtml.orEmpty(), item.enclosures, item.durationSeconds)
            }
            return EpisodeFeed(posts, podcast)
        }
    }
}

/** A post's kind, why, and the other post of its episode if there is one. */
data class Verdict(val kind: EpisodeKind, val pair: String?, val why: String, val signals: Signals)

data class Signals(
    /** The post's own audio: an attached file or a player of the host's. */
    val audio: String?,
    /** A player for someone's episode, embedded in the post: often a mention, not the post's own. */
    val embed: String?,
    val timestamps: Int,
    /**
     * Turns of a conversation: paragraphs opening with a speaker's name and a colon, a speaker's
     * name on a line of its own, or a question set as a heading.
     */
    val turns: Int,
    val transcriptTitle: Boolean,
    val transcriptHeading: Boolean,
    val spotifyEpisode: Boolean,
    val music: Boolean,
    val video: Boolean,
    val words: Int,
    /** The addresses its links point to, without query or fragment: how a narration names the post it reads. */
    val links: Set<String> = emptySet(),
    /** The videos it embeds, without their query: how a newsletter post names the episode it's about. */
    val videos: List<String> = emptyList(),
) {
    override fun toString() = buildList {
        audio?.let { add("audio: $it") }
        embed?.let { add("episode embed: $it") }
        if (timestamps > 0) add("$timestamps timestamps")
        if (turns > 0) add("$turns speaker turns")
        if (transcriptTitle) add("\"transcript\" in title")
        if (transcriptHeading) add("transcript heading")
        if (spotifyEpisode) add("Spotify episode")
        if (music) add("music embed")
        if (video) add("video embed")
        add("$words words")
    }.joinToString(", ")
}

/**
 * The prototype classifier: the signals measured on a real reading list, combined so that doubt
 * falls on the side of an article. Wrongly dropping reading is worse than printing one stub.
 */
object EpisodeDetector {
    /** Notes run up to about 2,500 words when they carry sponsors, links and timestamps. */
    private const val NOTES_WORDS = 700
    private const val NOTES_WORDS_WITH_TIMESTAMPS = 3_000
    private const val TIMESTAMPS = 10
    /** Shorter than this, a post can only be a transcript if its title says so. */
    private const val TRANSCRIPT_WORDS = 3_000
    /** A "Transcript" heading over a paid post's opening: shorter, but still a transcript. */
    private const val TRANSCRIPT_HEADING_WORDS = 2_500
    private const val STUB_WORDS = 300
    private const val TURNS = 20
    private const val UPLOAD = "substack-upload:"
    private const val PODCAST_SAMPLE = 4

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val AUDIO_FILE = Regex("\\.(mp3|m4a|aac|ogg|oga|opus|wav)(\\?|$)", RegexOption.IGNORE_CASE)
    private val TIMESTAMP = Regex("(?<![\\p{Nd}:])(?:\\p{Nd}{1,2}:)?\\p{Nd}{1,2}:\\p{Nd}{2}(?![\\p{Nd}:])")
    /** A timestamp as a list of them gives it: opening a line, or in brackets. A clock time in a sentence isn't one. */
    private val LISTED = Regex("^\\W{0,3}${TIMESTAMP.pattern}|[\\[(]${TIMESTAMP.pattern}[\\])]")
    private val TRANSCRIPT = Regex("\\btranscripts?\\b|\\btranscription\\b", RegexOption.IGNORE_CASE)
    private val PLAYERS = listOf(
        "anchor.fm", "podcasters.spotify.com", "creators.spotify.com", "embed.podcasts.apple.com", "art19.com",
        "megaphone.fm", "simplecast.com", "buzzsprout.com", "transistor.fm", "libsyn.com", "omny.fm", "captivate.fm",
        "podbean.com", "acast.com", "pinecast.com", "player.fm", "audioboom.com", "spreaker.com",
    )
    /** Podcast hosts and the trackers in front of them, as they appear in an audio file's address. */
    private val HOSTS = listOf(
        "substack.com/feed/podcast", UPLOAD, "libsyn.com", "art19.com", "megaphone.fm", "simplecast", "podtrac.com", "buzzsprout.com",
        "transistor.fm", "anchor.fm", "d3ctxlq1ktw2nl.cloudfront.net", "acast.com", "blubrry.com", "omny.fm", "omnycontent.com",
        "captivate.fm", "podbean.com", "spreaker.com", "audioboom.com", "pdst.fm", "swap.fm", "chrt.fm", "pscrb.fm", "npr.org",
    )
    private val MUSIC = listOf("soundcloud.com", "bandcamp.com", "music.apple.com")
    private val VIDEO = listOf("youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com")
    private val TURN = Regex("^([\\p{Lu}][\\p{L}.'’-]*(?: [\\p{Lu}][\\p{L}.'’-]*){0,2})\\s?[:：]\\s")
    private val NAME_LINE = Regex("[\\p{Lu}][\\p{L}.'’-]*(?: [\\p{Lu}][\\p{L}.'’-]*){0,3}")
    /** Labels and buttons that look like a speaker's name. */
    private val NOT_NAMES = setOf(
        "update", "note", "source", "sources", "editor", "correction", "disclosure", "photo", "credit", "image", "caption",
        "share", "subscribe", "advertisement", "listen", "watch", "read more", "related", "transcript",
    )
    private val EPISODE_TITLE = Regex("\\b(podcast|episode|ep)\\b", RegexOption.IGNORE_CASE)
    private val STOPWORDS = setOf("the", "and", "with", "for", "from", "that", "this", "what", "how", "why", "are", "was", "you", "our", "your")
    private val STUB_CUES = Regex("\\b(listen to this (article|post|essay|story)|(audio|narrated) version of|audio|narrated|voiceover|listen|podcast|version|read by)\\b", RegexOption.IGNORE_CASE)

    /** The first audio file among [enclosures]. Video files are left out: a blog's clip is attached the same way as a show's episode. */
    fun audioOf(enclosures: List<Enclosure>): Enclosure? =
        enclosures.firstOrNull { it.type.startsWith("audio") || (it.type.isBlank() && AUDIO_FILE.containsMatchIn(it.url)) }

    /**
     * What detection reads of a post, measured from one parse of its HTML that isn't kept: a
     * feed's posts are judged together, and holding every parsed page at once costs megabytes.
     * Nothing here copies an element's text for each element it's nested in, which a post of
     * deeply nested tags would turn into gigabytes.
     */
    fun signals(post: EpisodePost): Signals {
        val body = Jsoup.parseBodyFragment(post.html).body()
        val text = body.text()
        val embeds = body.select("iframe[src], embed[src], a[href]").map { it.attr("src").ifBlank { it.attr("href") } }
        val iframes = body.select("iframe[src], embed[src], audio[src], audio source[src]").map { it.attr("src") }
        val enclosure = audioOf(post.enclosures)
        // Substack's own player, for audio uploaded into a post rather than attached to it.
        val upload = body.selectFirst("[data-component-name=AudioPlaceholder]")?.attr("data-attrs")
            ?.let { Regex("\"mediaUploadId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let { "$UPLOAD$it" }
        val player = iframes.firstOrNull { src -> AUDIO_FILE.containsMatchIn(src) } ?: upload
        val embed = iframes.firstOrNull { src -> PLAYERS.any { it in src } || ("spotify.com" in src && ("/episode/" in src || "/show/" in src)) }
        val spotify = embeds.filter { "open.spotify.com" in it }
        val headings = body.select("h1, h2, h3, h4, h5, h6, strong, b, p").mapNotNull { shortText(it, 30) }
        val turns = turns(body)
        return Signals(
            audio = enclosure?.url ?: player,
            embed = embed,
            // Last: it marks line ends in the parsed page.
            timestamps = timestamps(body),
            turns = turns,
            transcriptTitle = TRANSCRIPT.containsMatchIn(post.title),
            transcriptHeading = headings.any { TRANSCRIPT.containsMatchIn(it) && it.split(' ').size <= 4 },
            spotifyEpisode = spotify.any { "/episode/" in it || "/show/" in it },
            music = iframes.any { src -> MUSIC.any { it in src } || ("spotify.com" in src && listOf("/track/", "/album/", "/playlist/", "/artist/").any { it in src }) },
            video = iframes.any { src -> VIDEO.any { it in src } },
            words = WORD.findAll(text).count(),
            links = body.select("a[href]").map { urlKey(it.attr("href")) }.toSet(),
            videos = iframes.filter { src -> VIDEO.any { it in src } }.map { it.substringBefore('?') },
        )
    }

    /** [element]'s text if it's at most [max] characters, found without building the text of a long one. */
    private fun shortText(element: Element, max: Int): String? {
        val out = StringBuilder()
        val stack = ArrayDeque<Node>(element.childNodes())
        while (stack.isNotEmpty()) {
            when (val node = stack.removeFirst()) {
                is TextNode -> {
                    out.append(node.wholeText)
                    if (out.length > max * 4) return null
                }
                is Element -> node.childNodes().asReversed().forEach(stack::addFirst)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().takeIf { it.length <= max }
    }

    /** All of [p]'s words are inside bold: an interviewer's question set in bold. */
    private fun allBold(p: Element): Boolean {
        var bold = false
        val stack = ArrayDeque<Pair<Node, Boolean>>(p.childNodes().map { it to false })
        while (stack.isNotEmpty()) {
            val (node, inBold) = stack.removeFirst()
            when (node) {
                is TextNode -> if (node.isBlank.not()) { if (!inBold) return false else bold = true }
                is Element -> {
                    val b = inBold || node.normalName() == "strong" || node.normalName() == "b"
                    node.childNodes().asReversed().forEach { stack.addFirst(it to b) }
                }
            }
        }
        return bold
    }

    private fun timestamps(body: Element): Int {
        // Lines, not elements: show notes often run a list of times through one paragraph with
        // <br>s, and a block nested in another would be counted twice.
        body.select("br").forEach { it.replaceWith(TextNode("\n")) }
        body.select("p, li, ul, ol, h1, h2, h3, h4, h5, h6, div, blockquote, tr").forEach {
            it.prependChild(TextNode("\n"))
            it.appendChild(TextNode("\n"))
        }
        return body.wholeText().lines().sumOf { LISTED.findAll(it.trim()).count() }
    }

    private fun turns(body: Element): Int {
        val paragraphs = body.select("p").map { it.text().trim() }
        val named = paragraphs.count { p -> TURN.find(p)?.groupValues?.get(1)?.lowercase()?.let { it !in NOT_NAMES } == true }
        // "Dwarkesh Patel" alone above what he says: a name only once it recurs.
        val nameLines = paragraphs.filter { NAME_LINE.matches(it) && it.lowercase() !in NOT_NAMES }.groupingBy { it }.eachCount().values.filter { it >= 3 }.sum()
        val questions = body.select("h2, h3, h4, h5, h6").count { it.text().trim().endsWith("?") }
        // An interviewer's questions in bold, whole paragraphs of them.
        val bold = body.select("p").count { p -> p.text().length > 40 && allBold(p) }
        return named + nameLines + questions + bold
    }

    fun classify(feed: EpisodeFeed): Map<String, Verdict> {
        val signals = feed.posts.associate { it.guid to signals(it) }
        // A blog's own mp3 is as often a song or a session as an episode; a podcast host's isn't.
        // From a few posts (tt-rss sends only what's unread), half of them with audio says little.
        val podcast = feed.podcast || (feed.posts.size >= PODCAST_SAMPLE && feed.posts.count { signals.getValue(it.guid).audio != null } * 2 >= feed.posts.size)
        val audio = feed.posts.associate { p -> p.guid to signals.getValue(p.guid).audio?.takeIf { podcast || HOSTS.any { h -> h in it } } }
        val verdicts = mutableMapOf<String, Verdict>()

        // The same audio on two posts: notes and transcript, or a magazine's episode and the stories it reads.
        val shared = feed.posts.filter { audio[it.guid] != null }.groupBy { audioKey(audio.getValue(it.guid)!!) }.values.filter { it.size > 1 }
        // A magazine attaching each episode to the stories it reads: one post of the group is titled
        // as the episode, and at least two stories aren't. A rerun ("Encore episode: X" beside X)
        // is only two posts.
        val readsStories = shared.any { group ->
            group.none { signals.getValue(it.guid).transcriptTitle || signals.getValue(it.guid).words >= TRANSCRIPT_WORDS } &&
                group.count { EPISODE_TITLE.containsMatchIn(it.title) } == 1 && group.count { !EPISODE_TITLE.containsMatchIn(it.title) } >= 2
        }
        // In any such group, the posts not titled as the episode carry its audio without being it:
        // a story it reads, or the original of an encore. Doubt falls on the side of an article.
        val carriers = shared.filter { group -> group.any { EPISODE_TITLE.containsMatchIn(it.title) } }
            .flatMap { group -> group.filterNot { EPISODE_TITLE.containsMatchIn(it.title) || signals.getValue(it.guid).transcriptTitle } }
            .map { it.guid }.toSet()
        for (posts in shared) {
            val readable = posts.maxBy { signals.getValue(it.guid).words }
            val s = signals.getValue(readable.guid)
            if (!s.transcriptTitle && s.words < TRANSCRIPT_WORDS) continue
            verdicts[readable.guid] = Verdict(EpisodeKind.TRANSCRIPT, other(posts, readable), "shares its audio with a shorter post", s)
            posts.filter { it !== readable }.forEach { verdicts[it.guid] = Verdict(EpisodeKind.NOTES, readable.guid, "shares its audio with a longer post", signals.getValue(it.guid)) }
        }

        // A transcript posted without audio, named after its episode's post.
        for (p in feed.posts) {
            val s = signals.getValue(p.guid)
            if (p.guid in verdicts || !s.transcriptTitle || audio[p.guid] != null) continue
            val episode = feed.posts.filter { it.guid !in verdicts && audio[it.guid] != null }
                .mapNotNull { e -> overlap(p.title, e.title)?.let { e to it } }.maxByOrNull { it.second }?.first ?: continue
            // The episode's post stays what it is otherwise (notes, or a post that opens with the episode).
            val own = withAudio(feed, episode, signals, audio)
            if (own.pair != null) continue
            verdicts[p.guid] = Verdict(EpisodeKind.TRANSCRIPT, episode.guid, "\"transcript\" in its title, named after an episode post", s)
            verdicts[episode.guid] = own.copy(pair = p.guid)
        }

        // The video an episode post carries, embedded again in a newsletter about the episode.
        val episodeVideos = feed.posts.filter { audio[it.guid] != null }.flatMap { p -> signals.getValue(p.guid).videos.map { it to p } }.toMap()

        for (p in feed.posts) {
            if (p.guid in verdicts) continue
            val s = signals.getValue(p.guid)
            val videos = s.videos
            verdicts[p.guid] = when {
                audio[p.guid] != null && (p.guid in carriers || readsStories && !EPISODE_TITLE.containsMatchIn(p.title)) ->
                    Verdict(EpisodeKind.ARTICLE, null, "a story carrying the episode that reads it", s)
                audio[p.guid] != null -> withAudio(feed, p, signals, audio)
                videos.isNotEmpty() && videos.all { it in episodeVideos } -> {
                    val episodes = videos.mapNotNull { episodeVideos[it] }.distinct()
                    Verdict(EpisodeKind.NOTES, episodes.singleOrNull()?.guid, "embeds the video of episode posts in the feed, with no audio of its own", s)
                }
                s.transcriptTitle && s.words >= TRANSCRIPT_WORDS -> Verdict(EpisodeKind.TRANSCRIPT, null, "\"transcript\" in its title", s)
                s.embed != null && s.turns >= TURNS && s.words >= TRANSCRIPT_WORDS ->
                    Verdict(EpisodeKind.TRANSCRIPT, null, "an episode player and $TURNS or more speaker turns", s)
                s.embed != null && s.timestamps >= TIMESTAMPS && s.words < NOTES_WORDS_WITH_TIMESTAMPS ->
                    Verdict(EpisodeKind.NOTES, null, "an episode player with a list of timestamps", s)
                s.video && s.timestamps >= TIMESTAMPS && s.words < NOTES_WORDS_WITH_TIMESTAMPS ->
                    Verdict(EpisodeKind.NOTES, null, "a video with a list of timestamps", s)
                else -> Verdict(EpisodeKind.ARTICLE, null, when {
                    s.audio != null -> "audio, but not from a podcast host, in a feed that isn't a podcast"
                    s.embed != null -> "only someone's episode embedded, without timestamps"
                    s.music -> "music, not an episode"
                    else -> "no episode audio"
                }, s)
            }
        }
        // A pair is a pair from both ends: the written post knows its audio stub.
        for ((guid, v) in verdicts.toMap()) {
            val other = v.pair ?: continue
            verdicts[other]?.takeIf { it.pair == null }?.let { verdicts[other] = it.copy(pair = guid) }
        }
        return verdicts
    }

    private fun withAudio(feed: EpisodeFeed, p: EpisodePost, signals: Map<String, Signals>, audio: Map<String, String?>): Verdict {
        val s = signals.getValue(p.guid)
        if (s.transcriptTitle) return Verdict(EpisodeKind.TRANSCRIPT, null, "audio, and \"transcript\" in its title", s)
        if (s.transcriptHeading && s.words >= TRANSCRIPT_HEADING_WORDS) return Verdict(EpisodeKind.TRANSCRIPT, null, "audio, and a transcript heading", s)
        // Turns alone make a written interview look like one; with the episode's audio it is one.
        if (s.turns >= TURNS && s.words >= TRANSCRIPT_WORDS) return Verdict(EpisodeKind.TRANSCRIPT, null, "audio, and $TURNS or more speaker turns", s)
        if (s.words < STUB_WORDS) {
            chapter(feed, p, signals, audio)?.let { return Verdict(EpisodeKind.AUDIO_VERSION, it.guid, "an audio stub beside a written post of the same title", s) }
            linked(feed, p, signals, audio)?.let { return Verdict(EpisodeKind.AUDIO_VERSION, it.guid, "an audio stub linking to a written post in the feed", s) }
        }
        // Audio uploaded into a post is a reading or a song as often as a show: not an episode on its own.
        if (s.audio!!.startsWith(UPLOAD)) return Verdict(EpisodeKind.ARTICLE, null, "audio uploaded into the post, not a show's episode", s)
        if (notesSized(s)) return Verdict(EpisodeKind.NOTES, null, if (s.timestamps >= TIMESTAMPS) "audio, timestamps and under ${NOTES_WORDS_WITH_TIMESTAMPS} words" else "audio and under $NOTES_WORDS words", s)
        return Verdict(EpisodeKind.ARTICLE, null, "audio, with a long write-up", s)
    }

    private fun notesSized(s: Signals) = s.words < NOTES_WORDS || (s.timestamps >= TIMESTAMPS && s.words < NOTES_WORDS_WITH_TIMESTAMPS)

    /** A written post without audio whose title is the stub's, less words like "audio". */
    private fun chapter(feed: EpisodeFeed, stub: EpisodePost, signals: Map<String, Signals>, audio: Map<String, String?>): EpisodePost? {
        val key = titleKey(stub.title)
        if (key.isBlank()) return null
        // Only a few words: a magazine's feed may carry only a story's opening lines. But some: a
        // bare video post of the same title is another recording, not the written post.
        return feed.posts.firstOrNull { it !== stub && signals.getValue(it.guid).words >= 20 && audio[it.guid] == null && titleKey(it.title) == key }
    }

    /** A written post without audio that the stub links to: "To read this letter, click here". */
    private fun linked(feed: EpisodeFeed, stub: EpisodePost, signals: Map<String, Signals>, audio: Map<String, String?>): EpisodePost? {
        val hrefs = signals.getValue(stub.guid).links
        return feed.posts.firstOrNull { it !== stub && audio[it.guid] == null && signals.getValue(it.guid).words >= 4 * STUB_WORDS && urlKey(it.link) in hrefs }
    }

    /**
     * How much one title holds of the other's words, when it holds most of them: "Transcript for
     * X | Show #12" and "#12 – X". Null when it doesn't, or when their episode numbers differ.
     */
    private fun overlap(a: String, b: String): Double? {
        fun words(t: String) = Regex("[\\p{L}\\p{N}]+").findAll(t.lowercase()).map { it.value }
            .filter { (it.length > 2 || it.all(Char::isDigit)) && it !in STOPWORDS && !TRANSCRIPT.matches(it) }.toSet()
        fun numbers(w: Set<String>) = w.filter { it.all(Char::isDigit) }.toSet()
        val (x, y) = words(a) to words(b)
        val numbered = numbers(x).isNotEmpty() && numbers(y).isNotEmpty()
        if (numbered && (numbers(x) intersect numbers(y)).isEmpty()) return null
        val shorter = minOf(x.size, y.size)
        val share = (x intersect y).size.toDouble() / shorter.coerceAtLeast(1)
        // A shared episode number carries most of the weight; without one, a series' titles share
        // their stock words ("Video and transcript of talk on …") and need three in four.
        return share.takeIf { shorter >= 3 && it >= if (numbered) 0.5 else 0.75 }
    }


    private fun urlKey(url: String) = url.substringBefore('?').substringBefore('#').substringAfter("://").removePrefix("www.").trimEnd('/')

    private fun titleKey(title: String) = STUB_CUES.replace(title.lowercase(), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // Trackers wrap the host's file in their own path (podtrac, chartable): keep only the end of the
    // path. The query stays, since some hosts name the file in it (download?id=123), but isn't
    // searched: it can hold any address (?ref=https://…).
    private fun audioKey(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val query = url.substringBefore('#').substringAfter('?', "")
        return path.substringAfterLast("://").substringAfterLast("/https/").substringAfterLast("/http/") + if (query.isEmpty()) "" else "?$query"
    }

    private fun other(posts: List<EpisodePost>, p: EpisodePost) = posts.first { it !== p }.guid
}
