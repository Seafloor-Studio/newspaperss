package com.app.newspaperss.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import java.time.Duration
import java.time.Instant

enum class SourceKind {
    FEED,
    READING_LIST,
    /** A Tiny Tiny RSS account: its url is the API endpoint and its articles come from many feeds. */
    TTRSS,
    /**
     * A curated list read by a scraper ([com.app.newspaperss.core.lists.CuratedList]): its url is
     * [com.app.newspaperss.core.lists.CuratedLists.sourceUrl] and its siteUrl the page read.
     */
    LIST,
}

@Entity(tableName = "sources", indices = [Index(value = ["url"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SourceKind = SourceKind.FEED,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val position: Int = 0,
    /**
     * [ContentMode.PAGE] for the kinds of source that carry no feed text (the reading list and
     * curated lists), otherwise [ContentMode.AUTO]: what the check learns, and what the reader
     * chooses, are kept per [PublicationEntity].
     */
    val contentMode: ContentMode = ContentMode.AUTO,
    val paused: Boolean = false,
    /**
     * Unused, and always null: what these held is kept per [PublicationEntity], or not at all
     * (sections). Kept because dropping a column means rebuilding this table, and dropping it
     * with foreign keys on would delete every article.
     */
    val section: String? = null,
    val contentModeChosen: Boolean = false,
    val fullTextEvidence: FullTextEvidence? = null,
    val fullTextStreak: Int = 0,
    val fullTextDay: Long? = null,
    val maxArticles: Int? = null,
    /** tt-rss only: the category to take unread articles from, null for all of them. */
    val ttrssCategoryId: Int? = null,
    val ttrssCategoryTitle: String? = null,
    /** tt-rss only: sync read status both ways, Settings' "Sync read status with tt-rss". */
    val markReadOnServer: Boolean = true,
    /**
     * tt-rss only, with [markReadOnServer]: an article that waits here past its keep window is
     * marked read, here and so on the server, rather than expiring.
     */
    @ColumnInfo(defaultValue = "0") val markExpiredRead: Boolean = false,
    /** tt-rss only: what the reader stars in tt-rss goes in the next edition, once each ([TtrssStarEntity]). */
    @ColumnInfo(defaultValue = "0") val takeTtrssStars: Boolean = false,
    /**
     * tt-rss only: the stars already there when [takeTtrssStars] was turned on have been noted,
     * and stay out of the paper: a long list of kept stars would otherwise all go in at once.
     */
    @ColumnInfo(defaultValue = "0") val ttrssStarsNoted: Boolean = false,
    /** tt-rss only: the newest article id [FeedSync] has looked at for posts, read or not. */
    val ttrssSeenId: Long? = null,
    /**
     * tt-rss only: when the full list of the account's feeds last filled in its publications
     * ([PublicationEntity.listed]); null before the first, and after the category changes.
     */
    val feedsListedAt: Instant? = null,
    val addedAt: Instant = Instant.now(),
    val lastFetchedAt: Instant? = null,
    /** The last sync error, cleared by the next successful sync. */
    val lastError: String? = null,
    /** When the current run of failed syncs began; null while syncs succeed. */
    val failingSince: Instant? = null,
    /**
     * A problem reporting back to the service (tt-rss not marking delivered articles read).
     * Kept apart from [lastError] so a successful sync doesn't hide it; cleared when reporting
     * back succeeds.
     */
    val serverNote: String? = null,
    /** Unused, and always false: the switch is kept per [PublicationEntity.skipPaidPosts]. Kept as [section] is. */
    @ColumnInfo(defaultValue = "0") val skipPaidPosts: Boolean = false,
)

/** Stored by name, and the DAO queries spell names out as SQL strings ('NEW'): renaming one breaks them. */
enum class ArticleState {
    NEW,
    /** In an edition that hasn't been delivered yet; goes back to NEW if it never is. */
    IN_EDITION,
    DELIVERED,
    /** Read, here or in tt-rss, rather than delivered; marked read in tt-rss when read status syncs. */
    SKIPPED,
    /** Never picked, and no longer waiting: older than the keep window, its feed left out, or a paid post skipped. */
    EXPIRED,
}

@Entity(
    tableName = "articles",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    // sourceId with discoveredAt answers Sources' "last new" per source, and starredAt Today's count
    // of stars waiting, without reading every article: both are watched, so they run on each write.
    indices = [
        Index(value = ["sourceId", "guid"], unique = true), Index("url"), Index("state"),
        Index(value = ["sourceId", "discoveredAt"]), Index("starredAt"),
    ],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val guid: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val published: Instant? = null,
    /** The feed's own HTML for the article, if it has any. */
    val feedHtml: String? = null,
    val discoveredAt: Instant = Instant.now(),
    val state: ArticleState = ArticleState.NEW,
    /**
     * When the reader starred it for the next edition, or null. A flag beside [state], not a
     * state of its own, so unstarring leaves the article as it was. Stars never expire and are
     * cleared on delivery.
     */
    val starredAt: Instant? = null,
    /**
     * The publication an aggregator source (tt-rss) got the article from: its feed id there,
     * so the per-source cap applies per publication, and its title, for the byline. Null for
     * articles from a feed of their own.
     */
    val originId: String? = null,
    val originTitle: String? = null,
    /** Words in a saved link's article, counted when its page is first looked up: the reading list's time estimate. */
    val pageWords: Int? = null,
    /**
     * tt-rss only: the server has it as read, from a sync or delivery. Compared with [state] at each
     * sync to tell the server what the reader marked read or unread here since.
     */
    @ColumnInfo(defaultValue = "0") val reportedRead: Boolean = false,
    /**
     * A link post's own page, when [url] is the story it points to (see
     * [com.app.newspaperss.core.feed.LinkPosts]); null for an ordinary article.
     */
    val viaUrl: String? = null,
    /** A paid post that turned out, when an edition tried it, to have next to nothing free. */
    @ColumnInfo(defaultValue = "0") val paidOnly: Boolean = false,
    /**
     * Left out, as EXPIRED, for its publication's [PublicationEntity.skipPaidPosts] the first time it was found
     * [paidOnly]. Cleared when the reader marks it unread: then they want it, and it isn't skipped again.
     */
    @ColumnInfo(defaultValue = "0") val paidSkipped: Boolean = false,
    /**
     * A saved link that can never be read in an edition, and why in a few words ("not a web
     * page", "error 410"): a PDF, a video, a page gone or too large. Editions leave it out, and
     * the reading list says so, rather than it waiting there for ever.
     */
    val unreadable: String? = null,
    /**
     * The audio file the feed attached to it, if any (a podcast's episode, a narration): its
     * address, type, and size in bytes when the feed gives one.
     */
    val audioUrl: String? = null,
    val audioType: String? = null,
    val audioBytes: Long? = null,
    /**
     * How long its audio plays, in seconds: the feed's own length when it gives one, or measured
     * from the file's first bytes when an edition takes it or the post it reads
     * ([com.app.newspaperss.listen.AudioLengths]). Null until known.
     */
    val audioSeconds: Int? = null,
    /**
     * What it is beside the podcast episodes it may belong to, as the detector judged it
     * ([com.app.newspaperss.core.episodes.EpisodeDetector]), judged again as its pair turns up. Null for an
     * article from before the detector, and for saved links, which it never judges.
     */
    val episodeKind: EpisodeKind? = null,
    /** The guid of the other post of the same episode in the same source: notes and transcript, a narration and its post. */
    val episodePair: String? = null,
) {
    /**
     * When it was published, for showing: a date more than a day after it was fetched is a
     * feed's mistake (scheduled posts, wrong zones, two-digit years), so the fetch date stands in.
     */
    val shownDate: Instant get() = published?.takeUnless { it.isAfter(discoveredAt.plus(Duration.ofDays(1))) } ?: discoveredAt
}

/**
 * Who wrote a source's articles, as against how they arrive: a feed's own publication has [key]
 * "", and each feed in a tt-rss account is one, keyed by its id there ([ArticleEntity.originId]).
 * Settings about the writing live here (article text, cap, left out, paid posts), settings about
 * the connection on the source. A row is written only once there's something to keep, so no
 * row means the defaults.
 */
@Entity(
    tableName = "publications",
    primaryKeys = ["sourceId", "key"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class PublicationEntity(
    val sourceId: Long,
    val key: String,
    /**
     * What the automatic full-text check has learned (see
     * [com.app.newspaperss.core.extract.FullTextCheck]); a mode the reader chose is [chosenMode].
     */
    val contentMode: ContentMode = ContentMode.AUTO,
    /** The latest article's [FullTextEvidence] and the run of days behind it. */
    val fullTextEvidence: FullTextEvidence? = null,
    val fullTextStreak: Int = 0,
    /** The epoch day the last piece of evidence was counted. */
    val fullTextDay: Long? = null,
    /** The epoch day a long item was last checked against its page. */
    val checkedDay: Long? = null,
    /** The name it was last listed under, so a left-out feed stays listed after its articles are gone. */
    val title: String? = null,
    /**
     * The reader left it out of the paper: sync stops fetching it and the planner skips any of its
     * articles still here, unless starred.
     */
    @ColumnInfo(defaultValue = "0") val leftOut: Boolean = false,
    /** The article text the reader chose, which the check leaves alone; null leaves it to the check. */
    val chosenMode: ContentMode? = null,
    /** At most this many articles per edition; null follows the edition setting. */
    val maxArticles: Int? = null,
    /**
     * Leave out paid posts with next to nothing free (see
     * [com.app.newspaperss.core.extract.ExtractedArticle.nothingFree]) instead of giving them a place.
     */
    @ColumnInfo(defaultValue = "0") val skipPaidPosts: Boolean = false,
    /** tt-rss only: the feed's own address, from the account's feed list. */
    val feedUrl: String? = null,
    /** tt-rss only: the category it's in there. */
    val category: String? = null,
    /**
     * tt-rss only: in the latest full list of the feeds the account takes articles from
     * ([SourceEntity.feedsListedAt]). A feed unsubscribed there, or outside the chosen category,
     * isn't.
     */
    @ColumnInfo(defaultValue = "0") val listed: Boolean = false,
    /**
     * tt-rss only: in the account but outside the category the paper takes articles from, in the
     * same list as [listed]. Sources counts these as "Not in your paper".
     */
    @ColumnInfo(defaultValue = "0") val outsideCategory: Boolean = false,
    /**
     * tt-rss only: subscribed there but not fetched by it yet, as of the same list as [listed].
     * A feed added from here has nothing to give until tt-rss's own schedule gets to it.
     */
    @ColumnInfo(defaultValue = "0") val awaitingFirstFetch: Boolean = false,
    /**
     * tt-rss only: why tt-rss's own latest try to fetch the feed failed, as it words it; null
     * when it worked. tt-rss fetches its feeds itself, so without this a dead feed just goes quiet.
     */
    val serverError: String? = null,
    /** tt-rss only: when the feed last posted, of the articles the app has seen there, read or not. */
    val newestPostAt: Instant? = null,
    /** tt-rss only: when the app started watching the feed post, for one with no post seen since. */
    val watchedSince: Instant? = null,
) {
    /**
     * This feed with [other]'s settings: what moving a feed between the phone and tt-rss keeps.
     * A setting added to the feed belongs here, or a move drops it.
     */
    fun withSettingsOf(other: PublicationEntity) = copy(
        contentMode = other.contentMode,
        fullTextEvidence = other.fullTextEvidence,
        fullTextStreak = other.fullTextStreak,
        fullTextDay = other.fullTextDay,
        checkedDay = other.checkedDay,
        chosenMode = other.chosenMode,
        maxArticles = other.maxArticles,
        skipPaidPosts = other.skipPaidPosts,
    )

    companion object {
        /** The key of a source's own feed, and of an article with no [ArticleEntity.originId]. */
        const val OWN = ""

        fun keyOf(article: ArticleEntity) = article.originId ?: OWN
    }
}

/** A feed an aggregator's articles came from, as the source page lists it. */
data class FeedName(val originId: String, val title: String?, val lastSeen: Instant)

/**
 * A link that went out in a delivered edition, or that the reader marked read in a feed since
 * moved to tt-rss. Kept apart from articles, which go when their source is removed, so a source
 * removed and added again, or a story that turns up later in another source, doesn't deliver it
 * a second time.
 */
@Entity(tableName = "delivered_urls")
data class DeliveredUrlEntity(
    @PrimaryKey val url: String,
    val deliveredAt: Instant,
)

/**
 * A star in tt-rss that an account set to [SourceEntity.takeTtrssStars] has seen: taken into the
 * paper, or there when the option was turned on. Kept apart from articles, which are trimmed, so a
 * star kept for months doesn't go in again. Forgotten when the star goes, so starring it again
 * sends it again.
 */
@Entity(
    tableName = "ttrss_stars",
    primaryKeys = ["sourceId", "articleId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class TtrssStarEntity(val sourceId: Long, val articleId: Long)

enum class EditionStatus {
    BUILDING, READY, DELIVERED, FAILED,

    /**
     * Deleted by the reader: only the row stays. One that was delivered, or released as not sent
     * (its error kept), holds its title, so a later edition can't take the same one (Send to
     * Kindle drops a title it has seen). Hidden everywhere else.
     */
    DELETED,
}

@Entity(tableName = "editions")
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Instant = Instant.now(),
    val status: EditionStatus = EditionStatus.BUILDING,
    /** The EPUB, relative to the app's editions directory. */
    val fileName: String? = null,
    val articleCount: Int = 0,
    val minutes: Double = 0.0,
    /**
     * When it was last delivered. Kept when it's marked as not sent: sending it again then knows
     * the work done on first delivery is done.
     */
    val deliveredAt: Instant? = null,
    val error: String? = null,
)

@Entity(
    tableName = "edition_articles",
    foreignKeys = [
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["editionId"], onDelete = ForeignKey.CASCADE),
        // SET_NULL, not CASCADE: removing a source mustn't erase past editions' contents.
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("editionId"), Index("articleId")],
)
data class EditionArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val editionId: Long,
    /** Null once the article's source has been removed. */
    val articleId: Long?,
    val position: Int,
    // Copied so an edition's contents survive its source being deleted.
    val title: String,
    val sourceTitle: String,
    val minutes: Double,
    /** It went in because it was starred. */
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /**
     * The article's state before it went in, which it gets back if the edition is never sent: a
     * starred article that was delivered, marked read or expired stays so once unstarred.
     */
    val stateBefore: ArticleState? = null,
)
