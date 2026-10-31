package com.app.newspaperss.data

import com.app.newspaperss.core.episodes.EpisodeDetector
import com.app.newspaperss.core.episodes.EpisodeFeed
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.episodes.Verdict
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.feed.Enclosure
import com.app.newspaperss.core.feed.FeedItem
import com.app.newspaperss.core.feed.FeedParseException
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.feed.FeedTooLargeException
import com.app.newspaperss.core.feed.LinkPosts
import com.app.newspaperss.core.net.withoutTracking
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.core.lists.ListLayoutChangedException
import com.app.newspaperss.core.net.ErrorAnswers
import com.app.newspaperss.core.net.HttpClient
import androidx.room.withTransaction
import org.jsoup.nodes.Entities
import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import com.app.newspaperss.core.ttrss.TtrssFeed
import com.app.newspaperss.core.ttrss.TtrssHeadline
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration

/** @property sources how many sources were synced (paused ones and the reading list aren't). */
data class SyncResult(val newArticles: Int, val failedSources: Int, val sources: Int)

class FeedSync(
    private val db: AppDatabase,
    private val http: HttpClient,
    private val clock: Clock = Clock.systemUTC(),
    /** Unpicked feed articles older than this expire, so there's never a backlog to feel behind on. */
    private val keepFor: Duration = KEEP_WAITING,
    private val ttrssAccounts: TtrssAccountStore? = null,
    /** How many unpicked links a curated list keeps; older ones expire as new ones arrive. */
    private val listKeep: Int = 12,
    /**
     * Given the ids of curated-list links that came without a title, to look up in the background
     * (see [ReadingListTitles]): Arts & Letters Daily gives only a teaser, and a source's page
     * would otherwise list its picks by site name. Must return quickly.
     */
    private val onUntitled: (articleIds: List<Long>) -> Unit = {},
    /** Whether to keep posts' audio and judge their episode kind: debug builds only, while it's tried out. */
    private val episodesOn: Boolean = true,
) {
    suspend fun syncAll(): SyncResult = coroutineScope {
        val feeds = db.sources().all().filter { it.kind != SourceKind.READING_LIST && !it.paused }
        val limit = Semaphore(4)
        val results = feeds.map { source -> async { limit.withPermit { sync(source) } } }.awaitAll()
        db.articles().expireOlderThan(clock.instant().minus(keepFor))
        db.articles().forgetDeliveredBefore(clock.instant().minus(REMEMBER_DELIVERED))
        val trimmed = db.articles().dropOldFeedHtml(clock.instant().minus(KEEP_FEED_TEXT))
        // Freed pages stay in the file until a VACUUM, and Auto Backup copies the file (25 MB quota).
        if (trimmed >= VACUUM_AFTER) runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        SyncResult(results.sumOf { it ?: 0 }, results.count { it == null }, feeds.size)
    }

    /** Returns the number of new articles, or null if the source failed. */
    suspend fun sync(source: SourceEntity): Int? {
        if (source.kind == SourceKind.TTRSS) return syncTtrss(source)
        if (source.kind == SourceKind.LIST) return syncList(source)
        val now = clock.instant()
        var body: String? = null
        val error = try {
            val response = http.getFeed(source.url)
            if (response.isSuccessful) {
                body = response.body
                val feed = FeedParser.parse(response.body, response.finalUrl, response.truncated)
                val episodes = episodes(source, null, feed.items, feed.podcast, now)
                val added = db.articles().insertFetched(
                    feed.items.map {
                        ArticleEntity(
                            sourceId = source.id, guid = it.guid, url = it.url, title = it.title,
                            author = it.author, published = it.published, feedHtml = storable(it.contentHtml), discoveredAt = now,
                        ).episode(it.enclosures, episodes[it.guid], it.durationSeconds).linkedToStory(feed.siteUrl ?: source.siteUrl) to it.url
                    },
                )
                // A title that is still the host-name placeholder gives way to the feed's own.
                db.sources().recordSuccess(
                    source.id, now, feed.title?.let { RowLimits.cut(it, RowLimits.MAX_TITLE) }, RowLimits.link(feed.siteUrl), SourceRepository.hostOf(source.url),
                )
                return added
            }
            ErrorAnswers.message(response.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            "Couldn't reach the site."
        } catch (e: FeedTooLargeException) {
            e.message ?: "This feed is too large to read."
        } catch (e: FeedParseException) {
            // Asked only once the parser has given up, so a feed that merely mentions a bot check
            // (a post about Cloudflare) still reads.
            if (body?.let(ArticleExtractor::isBotCheck) == true) ErrorAnswers.botCheck()
            else "We can't get new articles from this site any more. It may have moved; try adding it again."
        } catch (e: SQLiteConstraintException) {
            // The source was removed while it was being fetched.
            return null
        } catch (e: Exception) {
            // One bad feed mustn't stop the others from syncing.
            "Something went wrong reading this site."
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }

    /**
     * The newest few unread articles of each feed. One feed tt-rss can't serve (a 500 on a bad
     * article, a timeout) is skipped rather than failing the others; a problem with the login
     * or the whole server still fails the sync, and so does every feed failing.
     */
    private suspend fun fromEachFeed(client: TtrssClient, feeds: List<TtrssFeed>): List<TtrssHeadline> {
        var failure: Exception? = null
        val headlines = feeds.flatMap { feed ->
            try {
                client.unreadHeadlines(feedId = feed.id, limit = TTRSS_PER_FEED, newestFirst = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is TtrssException && e.failsWholeAccount) throw e
                failure = failure ?: e
                emptyList()
            }
        }
        failure?.let { if (headlines.isEmpty()) throw it }
        return headlines
    }

    /**
     * Keeps read and unread the same here and in tt-rss, the newest change winning. tt-rss doesn't say
     * when a flag changed, so "newest" is told by what changed since the last sync: the app's own
     * changes are sent first and read back, and a flag that differs from what tt-rss last confirmed
     * was changed in tt-rss. Changed on both sides between two syncs, the app's change wins. A star
     * here beats a read in tt-rss, which is often just opening it; an article in an unsent edition is
     * left alone. [fetched] are this sync's unread headlines and the articles made of them.
     */
    private suspend fun syncReadState(
        client: TtrssClient,
        source: SourceEntity,
        fetched: List<Pair<TtrssHeadline, ArticleEntity>>,
        now: Instant,
    ) {
        val articles = db.articles()
        fun ids(refs: List<TtrssRef>) = refs.mapNotNull { it.guid.removePrefix(TTRSS_GUID_PREFIX).toLongOrNull() }
        val toRead = articles.unreportedRead(source.id, limit = 500)
        val toUnread = articles.unreportedUnread(source.id, limit = 500)
        client.markRead(ids(toRead))
        client.markUnread(ids(toUnread))

        // Waiting here and unread in tt-rss at the last sync: missing from this sync's few unread
        // headlines per feed, it may have been read there, or just not be among the newest. Asked,
        // not inferred: the headlines don't say how many unread a feed has left.
        val unreadNow = fetched.map { it.second.guid }.toSet()
        val unsure = articles.waitingUnread(source.id).filter { it.guid !in unreadNow }
        // Read here and unread in this sync's headlines: marked unread in tt-rss since, unless the
        // delivery's own marking landed after the headlines were fetched. Asked again below, with
        // everything else, after the headlines.
        val unreadThere = unreadNow.toList().chunked(500).flatMap { articles.confirmedReadAmong(source.id, it) }
        val states = client.readStates(toRead + toUnread + unsure + unreadThere)
        // Chunked: SQLite before 3.32 (Android before 11) allows at most 999 query parameters.
        states.confirmed(toRead, read = true).chunked(500).forEach { articles.setReportedRead(source.id, it, read = true) }
        states.confirmed(toUnread, read = false).chunked(500).forEach { articles.setReportedRead(source.id, it, read = false) }
        unsure.filter { states.unread[it.guid] == false }.map { it.guid }.chunked(500).forEach { articles.readOnServer(source.id, it) }
        // Only from this sync's few unread per feed: one marked unread in tt-rss further back waits
        // until it's among them.
        unreadThere.filter { states.unread[it.guid] == true }.map { it.guid }.chunked(500).forEach { articles.unreadOnServer(source.id, it, now) }

        // A link already delivered (from a feed, or before the account was reconnected) is skipped
        // like any other, and tt-rss is told it's read: otherwise it would sit unread there for good.
        // Not one unread here: it may be one the reader marked unread after it went out. The
        // headline's own link as well as the stored one (a link post's story, tracking removed):
        // delivered_urls holds whichever went out.
        fun linksOf(h: TtrssHeadline, a: ArticleEntity) = listOf(h.link, a.url)
        val delivered = fetched.flatMap { (h, a) -> linksOf(h, a) }.distinct().chunked(500).flatMap { articles.deliveredAmong(it) }.toSet()
        val unreadHere = fetched.map { it.second.guid }.chunked(500).flatMap { articles.unreadGuids(source.id, it) }.toSet()
        client.markRead(fetched.filter { (h, a) -> a.guid !in unreadHere && linksOf(h, a).any { it in delivered } }.map { it.first.id })
    }

    private suspend fun syncTtrss(source: SourceEntity): Int? {
        val now = clock.instant()
        val account = ttrssAccounts?.ready()
        val error = if (account == null || account.apiUrl != source.url) {
            SIGN_IN_AGAIN
        } else {
            account.client(http).session { client ->
                try {
                    val category = source.ttrssCategoryId
                    // A few from each feed rather than the newest 200 overall: busy news feeds would
                    // fill those 200, and a feed that posts monthly would never reach the paper.
                    val leftOut = db.sources().leftOut(source.id).map { it.key }.toSet()
                    val withUnread = client.unreadFeeds(category).filter { it.unread > 0 }
                    // A feed with articles has been fetched, without waiting for tomorrow's feed list to say so,
                    // and its fetching error is current: one fixed in tt-rss stops showing as soon as it has posts.
                    db.sources().fetchedByServer(source.id, withUnread.map { it.id.toString() })
                    db.withTransaction { withUnread.forEach { db.sources().setServerError(source.id, it.id.toString(), it.lastError) } }
                    val unreadFeeds = withUnread.filter { it.id.toString() !in leftOut }
                    val headlines = fromEachFeed(client, unreadFeeds)
                    val episodes = judged(source, headlines, now)
                    val articles = headlines.map { it to article(source, it, now).episode(it.attachments, episodes["$TTRSS_GUID_PREFIX${it.id}"]) }
                    if (source.markReadOnServer) syncReadState(client, source, articles, now)
                    val guids = articles.map { it.second.guid }
                    val known = guids.chunked(500).flatMap { db.articles().knownGuids(source.id, it) }.toSet()
                    val added = db.articles().insertFetched(articles.map { (h, a) -> a to h.link })
                    if (source.markReadOnServer && source.markExpiredRead) {
                        (guids - known).chunked(500).forEach { db.articles().readOnArrival(source.id, it, now.minus(keepFor)) }
                    }
                    // tt-rss answers a deleted (or another user's) category with no articles and no error.
                    if (headlines.isEmpty() && category != null && client.categories().none { it.id == category }) {
                        CATEGORY_GONE
                    } else {
                        db.sources().recordSuccess(source.id, now, null, null, source.title)
                        listFeeds(client, source, now)
                        watchPosts(client, source, now)
                        return added + if (source.takeTtrssStars) takeStars(client, source, now) else 0
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TtrssException) {
                    e.reason
                } catch (e: IOException) {
                    // The periodic sync, and every edition, tries again.
                    ttrssUnreachable(e) + if (tooSlow(e)) " It'll be tried again at the next sync." else ""
                } catch (e: SQLiteConstraintException) {
                    return null
                } catch (e: Exception) {
                    "Something went wrong reading from tt-rss."
                }
            }
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }

    /** Lists the account's feeds once a day (see [listTtrssFeeds]). */
    private suspend fun listFeeds(client: TtrssClient, source: SourceEntity, now: Instant) {
        if (source.feedsListedAt?.let { Duration.between(it, now) < LIST_FEEDS_EVERY } == true) return
        listTtrssFeeds(db, client, source, now)
    }

    /**
     * Notes when each of the account's feeds last posted, for the quiet ones' line on Sources.
     * One look at every article since the last sync's, read or not: a feed read on the laptop
     * leaves nothing unread here. The first looks back over the newest few hundred. A feed with
     * nothing seen counts from when the app started watching it, since tt-rss deletes old
     * articles and a long-dead feed has none left to date it by. Best effort: a failure waits
     * for the next sync, which looks from the same place.
     */
    private suspend fun watchPosts(client: TtrssClient, source: SourceEntity, now: Instant) {
        val posted = try {
            client.postedSince(source.ttrssSeenId, WATCH_PAGES)
        } catch (e: TtrssException) {
            return
        } catch (e: IOException) {
            return
        }
        db.withTransaction {
            val sources = db.sources()
            posted.posts.groupBy { it.feedId }.forEach { (feed, its) ->
                // Not later than now: a server clock ahead would keep a feed from ever seeming quiet.
                its.mapNotNull { it.updated }.maxOrNull()?.let { sources.posted(source.id, feed, minOf(Instant.ofEpochSecond(it), now)) }
            }
            sources.watching(source.id, now)
            posted.upTo?.let { sources.setTtrssSeenId(source.id, maxOf(it, source.ttrssSeenId ?: 0)) }
        }
    }

    /**
     * What each of [items] is beside podcast episodes, judged with the source's recent articles
     * too ([EPISODE_CONTEXT] of them, found in the last [EPISODE_CONTEXT_DAYS] days): a narration
     * often arrives a sync after the post it reads, and through tt-rss, which sends only what's
     * unread, it may arrive alone. An earlier article gets the new post as its pair, one judged
     * an episode post can turn out an article, and one never judged is judged now, with its pair
     * among the others. Detection is best effort: a failure leaves the posts unjudged rather than
     * failing the sync.
     */
    private suspend fun episodes(source: SourceEntity, originId: String?, items: List<FeedItem>, podcast: Boolean, now: Instant): Map<String, Verdict> {
        if (!episodesOn) return emptyMap()
        val guids = items.map { it.guid }.toSet()
        // A feed lists the same posts sync after sync; judging them again would only be thrown away.
        val known = guids.chunked(500).flatMap { db.articles().knownGuids(source.id, it) }.toSet()
        val fresh = guids - known
        val since = now.minus(Duration.ofDays(EPISODE_CONTEXT_DAYS))
        // A stored post never judged (from before detection, or a sync where it failed) is judged
        // once a copy shows its audio: the feed's, or its own if it kept one. Those stored before
        // detection kept none, and judged from what's left an episode would be an article. A blank
        // guid gets no verdict under its own name, so waiting for one would never end.
        fun waiting(guid: String, hasAudio: Boolean) = guid.isNotBlank() && (guid in guids || hasAudio)
        if (fresh.isEmpty() && db.articles().unjudgedForEpisodes(source.id, originId, since, EPISODE_CONTEXT).none { waiting(it.guid, it.hasAudio) }) return emptyMap()
        // Stored posts the feed still lists are judged from the feed's copy but updated here like
        // the rest: they're the ones a new narration or written post most often pairs with.
        val earlier = db.articles().recentForEpisodes(source.id, originId, since, EPISODE_CONTEXT)
        val unjudged = earlier.filter { it.episodeKind == null && waiting(it.guid, it.audioUrl != null) }.map { it.guid }.toSet()
        val verdicts = try {
            EpisodeDetector.classify(EpisodeFeed.of(items + earlier.filter { it.guid !in guids }.map { it.asFeedItem() }, podcast))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }
        val listed = items.associateBy { it.guid }
        val stored = earlier.associateBy { it.guid }
        for (old in earlier) {
            val verdict = verdicts[old.guid] ?: continue
            val pair = verdict.pair
            // Paired with a new post, or one being judged now; or, for one being judged now, a stored
            // post still free whose verdict names it back, and so is paired to it below or above.
            val pairsBack = pair != null && (
                pair in fresh || pair in unjudged ||
                    old.guid in unjudged && stored[pair]?.let { it.episodePair == null } == true && verdicts[pair]?.pair == old.guid
                )
            when {
                // Pairing makes a narration an audio version; it doesn't make an article notes.
                pairsBack && old.episodePair == null -> db.articles().pairEpisode(
                    source.id, old.guid, pair,
                    if (verdict.kind == EpisodeKind.AUDIO_VERSION || verdict.kind == EpisodeKind.ARTICLE) verdict.kind else old.episodeKind ?: verdict.kind,
                )
                // Only toward an article: a magazine's story carrying its episode's audio looks like
                // notes until the next episode shows the pattern, but a post judged an article
                // isn't turned into an episode by what came after it.
                verdict.kind == EpisodeKind.ARTICLE && old.episodeKind != null && old.episodeKind != EpisodeKind.ARTICLE && old.episodePair == null ->
                    db.articles().setEpisodeKind(source.id, old.guid, EpisodeKind.ARTICLE)
                old.guid in unjudged -> db.articles().judgeStored(source.id, old.guid, verdict.kind)
            }
            // The audio it was judged by, kept as a new post's is: judged again from a copy without
            // it, once the feed no longer lists it, an episode would be turned into an article.
            val item = listed[old.guid]
            if (old.guid in unjudged && old.audioUrl == null && item != null) {
                RowLimits.storable(old.withEpisode(item.enclosures, verdict, item.durationSeconds))?.takeIf { it.audioUrl != null }?.let {
                    db.articles().keepAudio(source.id, old.guid, it.audioUrl!!, it.audioType, it.audioBytes, it.audioSeconds)
                }
            }
        }
        // A pair is kept only if it holds both ways: it names a new post, or an earlier one above
        // that's been paired back. Not one already paired with another (which keeps its own), one
        // stored but older than those (never paired back), nor a guid no post has (the detector's
        // name for a repeated guid).
        val real = fresh + earlier.map { it.guid }
        val taken = earlier.mapNotNull { old -> old.episodePair?.let { old.guid to it } }.toMap()
        return verdicts.filterKeys { it in fresh }.mapValues { (guid, verdict) ->
            val pair = verdict.pair ?: return@mapValues verdict
            if (pair !in real || taken[pair]?.let { it != guid } == true) verdict.copy(pair = null) else verdict
        }
    }

    /** [headlines] judged feed by feed, as a feed's own posts are: pairs are two posts of one publication. */
    private suspend fun judged(source: SourceEntity, headlines: List<TtrssHeadline>, now: Instant): Map<String, Verdict> =
        headlines.groupBy { it.feedId }.flatMap { (feedId, posts) ->
            episodes(source, feedId, posts.map { it.asFeedItem() }, podcast = false, now).entries
        }.associate { it.key to it.value }

    private fun ArticleEntity.episode(enclosures: List<Enclosure>, verdict: Verdict?, seconds: Int? = null) =
        if (episodesOn) withEpisode(enclosures, verdict, seconds) else this

    private fun ArticleEntity.asFeedItem() = FeedItem(
        guid = guid, url = viaUrl ?: url, title = title, contentHtml = feedHtml, author = author, published = published,
        enclosures = listOfNotNull(audioUrl?.let { Enclosure(it, audioType.orEmpty(), audioBytes ?: 0) }),
    )

    private fun TtrssHeadline.asFeedItem() = FeedItem(
        guid = "$TTRSS_GUID_PREFIX$id", url = link, title = title, contentHtml = content, author = author, published = null, enclosures = attachments,
    )

    private fun article(source: SourceEntity, h: TtrssHeadline, now: Instant, starredAt: Instant? = null) = ArticleEntity(
        sourceId = source.id, guid = "$TTRSS_GUID_PREFIX${h.id}", url = h.link, title = h.title,
        author = h.author, published = h.updated?.let(Instant::ofEpochSecond), feedHtml = storable(h.content),
        discoveredAt = now, originId = h.feedId, originTitle = h.feedTitle, starredAt = starredAt,
    ).linkedToStory(siteUrl = null)

    /**
     * Stars the articles newly starred in tt-rss, so they go in the next edition, and returns how
     * many it added. Each star once ([TtrssStarEntity]): the star stays in tt-rss, which this
     * never changes. The first look after the option is turned on only notes the stars already
     * there. From any feed, in the account's category or not, left out or not: a star is asked
     * for. Best effort, like [watchPosts].
     *
     * tt-rss lists stars by the article's date, not when it was starred, so a new star can be
     * anywhere in the list: it's listed whole, or nothing is taken. Cut short, a kept star never
     * noted could look new.
     */
    private suspend fun takeStars(client: TtrssClient, source: SourceEntity, now: Instant): Int {
        val sources = db.sources()
        return try {
            val starred = client.starredIds(STAR_PAGES)
            if (!starred.complete) return 0
            val seen = sources.ttrssStars(source.id).toSet()
            val unseen = starred.ids.filter { it !in seen }
            // One in an unsent edition can't be starred yet; it's tried again until that edition goes
            // out or goes back, without holding up the rest.
            val held = unseen.map { "$TTRSS_GUID_PREFIX$it" }.chunked(500).flatMap { db.articles().inEditionAmong(source.id, it) }.toSet()
            val new = if (source.ttrssStarsNoted) unseen.filter { "$TTRSS_GUID_PREFIX$it" !in held }.take(STARS_PER_SYNC) else emptyList()
            // Only what was asked for: a server answering with more would add them all, starred.
            val starredHeadlines = client.articles(new).filter { it.id in new }
            val episodes = judged(source, starredHeadlines, now)
            val articles = starredHeadlines.map { it to article(source, it, now, starredAt = now).episode(it.attachments, episodes["$TTRSS_GUID_PREFIX${it.id}"]) }
            db.withTransaction {
                // Turned off, or off and on again, during this sync: what was seen belongs to the old setting.
                val current = sources.byId(source.id)
                if (current?.takeTtrssStars != true || current.ttrssStarsNoted != source.ttrssStarsNoted) return@withTransaction 0
                val added = db.articles().insertFetched(articles.map { (h, a) -> a to h.link })
                val guids = articles.map { it.second.guid }
                guids.chunked(500).forEach { db.articles().starGuids(source.id, it, now) }
                // Taken into an edition since the check above: left for the next sync, like the others held.
                val heldNow = guids.chunked(500).flatMap { db.articles().inEditionAmong(source.id, it) }.toSet()
                val noting = if (source.ttrssStarsNoted) new.filter { "$TTRSS_GUID_PREFIX$it" !in heldNow } else starred.ids
                noting.chunked(500).forEach { ids -> sources.addTtrssStars(ids.map { TtrssStarEntity(source.id, it) }) }
                sources.notedTtrssStars(source.id)
                // Unstarred there: starring it again sends it again. An unstar while the pages are read
                // can shift one kept star out of the list and send it once more; rare, and only once.
                (seen - starred.ids.toSet()).toList().chunked(500).forEach { sources.dropTtrssStars(source.id, it) }
                added
            }
        } catch (e: TtrssException) {
            0
        } catch (e: IOException) {
            0
        }
    }

    private suspend fun syncList(source: SourceEntity): Int? {
        val now = clock.instant()
        val list = CuratedLists.forSourceUrl(source.url)
        val error = if (list == null) {
            LIST_UNSUPPORTED
        } else {
            try {
                val response = http.get(list.pageUrl)
                if (response.isSuccessful) {
                    val links = list.links(response.body, response.finalUrl)
                    val added = db.withTransaction {
                        db.articles().insertFetched(
                            links.map {
                                ArticleEntity(
                                    sourceId = source.id, guid = it.url, url = withoutTracking(it.url, LinkPosts.siteNames(list.pageUrl)),
                                    title = it.title.orEmpty(),
                                    // The teaser is what the edition shows if the page can't be fetched.
                                    feedHtml = storable(it.summary?.let { s -> "<p>${Entities.escape(s)}</p>" }), discoveredAt = now,
                                ) to it.url
                            },
                        ).also { db.articles().keepNewest(source.id, listKeep) }
                    }
                    db.sources().recordSuccess(source.id, now, null, null, source.title)
                    // Not just new ones, so a lookup that failed is tried again, but only for a couple of
                    // days: a paywalled page or a PDF never gives a title, and would be fetched every sync.
                    val untitled = db.articles().untitledWaiting(source.id, now.minus(UNTITLED_LOOKUP_FOR))
                    // Scheduling the lookup failing mustn't turn a good sync into a failed one.
                    if (untitled.isNotEmpty()) runCatching { onUntitled(untitled) }
                    return added
                }
                ErrorAnswers.message(response.code, curatedList = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "Couldn't reach the site."
            } catch (e: ListLayoutChangedException) {
                LIST_LAYOUT_CHANGED
            } catch (e: SQLiteConstraintException) {
                return null
            } catch (e: Exception) {
                "Something went wrong reading this list."
            }
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }

    companion object {
        private val UNTITLED_LOOKUP_FOR: Duration = Duration.ofDays(2)
        private val LIST_FEEDS_EVERY: Duration = Duration.ofDays(1)

        /** How many of a source's recent articles a new post is judged beside, and how recent. */
        private const val EPISODE_CONTEXT = 30
        private const val EPISODE_CONTEXT_DAYS = 14L

        /** A feed in the paper with nothing new for this long says so on Sources. */
        val QUIET_AFTER: Duration = Duration.ofDays(21)

        /** Pages of 200 articles looked at per sync; more wait for the next. */
        private const val WATCH_PAGES = 5

        /** Pages of 200 starred articles listed per sync: 10,000 stars, past which none are taken. */
        private const val STAR_PAGES = 50
        /** Newly starred articles fetched per sync, with their text; more wait for the next. */
        private const val STARS_PER_SYNC = 50

        /** How long an unpicked article waits, from when it was found, before it expires. */
        val KEEP_WAITING: Duration = Duration.ofDays(7)

        const val LIST_LAYOUT_CHANGED =
            "This page has changed its layout, so NewspapeRSS can't tell which links are new and took none. An app update should fix it."
        const val LIST_UNSUPPORTED = "This version of NewspapeRSS can't read this list any more. Remove it or update the app."

        val REMEMBER_DELIVERED: Duration = Duration.ofDays(365)
        // A month: long enough to bring an article back from a recent edition with its text.
        private val KEEP_FEED_TEXT: Duration = Duration.ofDays(30)

        /**
         * The most feed text an article keeps: well under the 2 MB Android reads a row through,
         * even at three bytes a character.
         */
        const val MAX_FEED_HTML_CHARS = 500_000
        // A picture inlined in the feed as text, the usual reason an item is that big.
        private val INLINE_IMAGE = Regex("data:[^\"'\\s>]{1000,}")

        /**
         * [html] as it can be stored: without its inlined pictures if it's too big, or not at all
         * if it's too big even then, when the article's page is fetched as for a feed that sends
         * no text.
         */
        internal fun storable(html: String?): String? {
            if (html == null || html.length <= MAX_FEED_HTML_CHARS) return html
            return html.replace(INLINE_IMAGE, "").takeIf { it.length <= MAX_FEED_HTML_CHARS }
        }
        private const val VACUUM_AFTER = 500

        /** tt-rss articles are stored with this guid prefix followed by their tt-rss id. */
        const val TTRSS_GUID_PREFIX = "ttrss:"
        /** Unread articles taken from each tt-rss feed per sync; a paper takes one or two per feed. */
        const val TTRSS_PER_FEED = 5
        const val CATEGORY_GONE = "Your chosen tt-rss category isn't there any more. Choose another in Settings › Where your feeds live."
        const val SIGN_IN_AGAIN = "Sign in to tt-rss again: Settings › Where your feeds live."

        /**
         * What to tell the reader when tt-rss couldn't be read. A slow answer gets its own words: a
         * home server on a slow line answers eventually, and "couldn't reach" sends people to check a
         * connection that works. A connect timeout isn't slowness: a switched-off VPN or a wrong
         * address behind a firewall drops packets rather than refusing them.
         */
        fun ttrssUnreachable(e: IOException): String =
            if (tooSlow(e)) "tt-rss took too long to answer." else "Couldn't reach tt-rss."

        /** A read or whole-call timeout; OkHttp reports a connect timeout as one too, told apart by its message. */
        fun tooSlow(e: IOException): Boolean =
            e is InterruptedIOException && e.message?.contains("connect", ignoreCase = true) != true
    }
}

/**
 * Fills in the account's publications from its full list of feeds: their names, addresses and
 * categories, and which are still there to show. Only unread feeds are fetched, so the articles
 * alone would miss a feed read in tt-rss itself. With a category chosen, the account's other feeds
 * are listed too, as outside it, so Sources can say what isn't in the paper; they're asked for apart
 * because a category takes in its subcategories' feeds. Returns whether it listed them; a failure
 * is left for the next try.
 *
 * [source] must be as read before asking tt-rss: a new category or sign-in since then clears
 * [SourceEntity.feedsListedAt], and this list would be for the wrong feeds, or another user's.
 */
internal suspend fun listTtrssFeeds(db: AppDatabase, client: TtrssClient, source: SourceEntity, now: Instant): Boolean {
    val category = source.ttrssCategoryId
    val feeds: List<TtrssFeed>
    val outside: List<TtrssFeed>
    val categories: Map<Int, String>
    try {
        feeds = client.allFeeds(category)
        val ids = feeds.map { it.id }.toSet()
        outside = if (category == null) emptyList() else client.allFeeds().filter { it.id !in ids }
        categories = client.categories().associate { it.id to it.title }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    val sources = db.sources()
    return db.withTransaction {
        val current = sources.byId(source.id) ?: return@withTransaction false
        if (current.ttrssCategoryId != category || current.feedsListedAt != source.feedsListedAt) return@withTransaction false
        sources.unlistPublications(source.id)
        for ((feed, inPaperCategory) in feeds.map { it to true } + outside.map { it to false }) {
            val key = feed.id.toString()
            val publication = sources.publication(source.id, key) ?: PublicationEntity(source.id, key)
            sources.savePublication(
                publication.copy(
                    title = RowLimits.cut(feed.title, RowLimits.MAX_TITLE).ifBlank { publication.title.orEmpty() }.ifBlank { null },
                    feedUrl = RowLimits.link(feed.feedUrl) ?: publication.feedUrl,
                    // Category 0 is tt-rss's Uncategorized, under whatever name the server's language gives it.
                    category = when (val id = feed.categoryId) {
                        null -> publication.category
                        0 -> null
                        else -> categories[id] ?: publication.category
                    },
                    listed = inPaperCategory,
                    outsideCategory = !inPaperCategory,
                    awaitingFirstFetch = feed.lastUpdated == 0L,
                    serverError = feed.lastError,
                ),
            )
        }
        sources.setFeedsListed(source.id, now)
        true
    }
}

/**
 * With the audio the feed attached and what the detector made of the post; a post it didn't judge stays unjudged.
 *
 * @param seconds the feed's length for the audio (`itunes:duration`), when it gives one.
 */
internal fun ArticleEntity.withEpisode(enclosures: List<Enclosure>, verdict: Verdict?, seconds: Int? = null): ArticleEntity {
    val audio = EpisodeDetector.audioOf(enclosures)
    return copy(
        audioUrl = audio?.url, audioType = audio?.type?.ifEmpty { null }, audioBytes = audio?.length?.takeIf { it > 0 },
        audioSeconds = seconds?.takeIf { audio != null && it > 0 },
        episodeKind = verdict?.kind, episodePair = verdict?.pair,
    )
}

/**
 * The article as stored: a link post's url becomes the story it points to, with its own page kept
 * as [ArticleEntity.viaUrl]. Tracking parameters come off the stored url either way, so the same
 * story from two sources is one link to the planner and to the delivered-links memory. The guid
 * is left as the feed gave it: it's how the feed's next copy of the item is recognised.
 */
internal fun ArticleEntity.linkedToStory(siteUrl: String?): ArticleEntity {
    val story = LinkPosts.storyUrl(url, feedHtml, siteUrl)
    return if (story != null) copy(url = story, viaUrl = url) else copy(url = withoutTracking(url, LinkPosts.siteNames(url, siteUrl)))
}

/**
 * A tt-rss session for [block], logged out however it ends. Inline, so a `return` in [block]
 * returns from the caller, as it would from the `try` this stands for.
 */
internal suspend inline fun <T> TtrssClient.session(block: (TtrssClient) -> T): T = try {
    block(this)
} finally {
    logOut(this)
}

/**
 * Ends a tt-rss session; the server expires it anyway if this fails. NonCancellable: it runs
 * in `finally` blocks, where a cancelled caller would otherwise skip it.
 */
internal suspend fun logOut(client: TtrssClient) = withContext(NonCancellable) {
    try {
        client.logout()
    } catch (e: Exception) {
    }
}
