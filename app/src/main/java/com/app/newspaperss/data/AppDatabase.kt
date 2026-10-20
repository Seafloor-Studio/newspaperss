package com.app.newspaperss.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.app.newspaperss.core.episodes.EpisodeKind
import java.time.Instant

class Converters {
    @TypeConverter fun instantToLong(i: Instant?): Long? = i?.toEpochMilli()
    @TypeConverter fun longToInstant(l: Long?): Instant? = l?.let(Instant::ofEpochMilli)

    // By label, not the Kotlin name, and an unknown one reads as unjudged: renaming a kind, or
    // dropping one, mustn't make every article unreadable.
    @TypeConverter fun episodeKindToText(k: EpisodeKind?): String? = k?.label
    @TypeConverter fun textToEpisodeKind(s: String?): EpisodeKind? = EpisodeKind.entries.firstOrNull { it.label == s }
}

@Database(
    entities = [SourceEntity::class, ArticleEntity::class, EditionEntity::class, EditionArticleEntity::class, DeliveredUrlEntity::class, PublicationEntity::class, TtrssStarEntity::class],
    version = 17,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun articles(): ArticleDao
    abstract fun editions(): EditionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN pageWords INTEGER")
            }
        }

        /**
         * `articles.broughtBack` becomes `starredAt` (a brought-back article is a starred one,
         * starred when it was found), and edition articles remember whether they were starred.
         * SQLite before 3.35 (Android before 14) can't drop a column, so articles is rebuilt.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room turns foreign keys on only after migrating, but if they were on, dropping
                // articles would set every past edition's articleId to null. Kept aside to be sure.
                db.execSQL("CREATE TEMP TABLE edition_article_links AS SELECT id, articleId FROM edition_articles WHERE articleId IS NOT NULL")
                db.execSQL(
                    "CREATE TABLE articles_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, " +
                        "`guid` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `published` INTEGER, " +
                        "`feedHtml` TEXT, `discoveredAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `starredAt` INTEGER, " +
                        "`originId` TEXT, `originTitle` TEXT, `pageWords` INTEGER, `reportedRead` INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "INSERT INTO articles_new (id, sourceId, guid, url, title, author, published, feedHtml, discoveredAt, state, starredAt, originId, originTitle, pageWords) " +
                        "SELECT id, sourceId, guid, url, title, author, published, feedHtml, discoveredAt, state, " +
                        "CASE WHEN broughtBack != 0 THEN discoveredAt END, originId, originTitle, pageWords FROM articles",
                )
                db.execSQL("DROP TABLE articles")
                db.execSQL("ALTER TABLE articles_new RENAME TO articles")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_articles_sourceId_guid` ON `articles` (`sourceId`, `guid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_url` ON `articles` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_state` ON `articles` (`state`)")
                db.execSQL(
                    "UPDATE edition_articles SET articleId = (SELECT articleId FROM edition_article_links WHERE edition_article_links.id = edition_articles.id) " +
                        "WHERE id IN (SELECT id FROM edition_article_links)",
                )
                db.execSQL("DROP TABLE edition_article_links")
                db.execSQL("ALTER TABLE edition_articles ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE edition_articles ADD COLUMN `stateBefore` TEXT")
                // An article in an unsent edition whose link was already delivered was brought back
                // (version 2 cleared the flag once it went in). It stays starred, so if the edition is
                // never sent it keeps its place and returns to delivered rather than being lost.
                db.execSQL(
                    "UPDATE articles SET starredAt = discoveredAt WHERE state = 'IN_EDITION' AND url != '' " +
                        "AND url IN (SELECT url FROM delivered_urls)",
                )
                db.execSQL(
                    "UPDATE edition_articles SET starred = 1, stateBefore = 'DELIVERED' " +
                        "WHERE editionId IN (SELECT id FROM editions WHERE status = 'READY') " +
                        "AND articleId IN (SELECT id FROM articles WHERE state = 'IN_EDITION' AND starredAt IS NOT NULL)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN viaUrl TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `left_out_feeds` (`sourceId` INTEGER NOT NULL, `originId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `originId`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN skipPaidPosts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE articles ADD COLUMN paidOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE articles ADD COLUMN paidSkipped INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Sources carry, publications write: a new table holds the settings about each
         * publication's writing (what the text check learned, the article text the reader chose,
         * the cap, leaving a tt-rss feed out, skipping paid posts) and what tt-rss's feed list says
         * about it. They move off the source row, and the left-out table goes. Sections aren't
         * kept: the paper has none.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `publications` (`sourceId` INTEGER NOT NULL, `key` TEXT NOT NULL, `contentMode` TEXT NOT NULL, " +
                        "`fullTextEvidence` TEXT, `fullTextStreak` INTEGER NOT NULL, `fullTextDay` INTEGER, `checkedDay` INTEGER, `title` TEXT, " +
                        "`leftOut` INTEGER NOT NULL DEFAULT 0, `chosenMode` TEXT, `maxArticles` INTEGER, `feedUrl` TEXT, `category` TEXT, " +
                        "`listed` INTEGER NOT NULL DEFAULT 0, `skipPaidPosts` INTEGER NOT NULL DEFAULT 0, `outsideCategory` INTEGER NOT NULL DEFAULT 0, " +
                        "`awaitingFirstFetch` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`sourceId`, `key`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                // What the check learned about a feed the reader left to it. With no checkedDay, a
                // feed settled on its text has a long item checked against its page in the next edition.
                db.execSQL(
                    "INSERT INTO publications (sourceId, `key`, contentMode, fullTextEvidence, fullTextStreak, fullTextDay) " +
                        "SELECT id, '', contentMode, fullTextEvidence, fullTextStreak, fullTextDay FROM sources " +
                        "WHERE kind = 'FEED' AND contentModeChosen = 0 AND (contentMode != 'AUTO' OR fullTextEvidence IS NOT NULL)",
                )
                // The reader's chosen article text and cap. tt-rss never took a cap of its own: its
                // feeds were capped one by one by the edition's number.
                db.execSQL("UPDATE sources SET maxArticles = NULL WHERE kind = 'TTRSS'")
                val chosen = "kind = 'FEED' AND contentModeChosen = 1 AND contentMode != 'AUTO'"
                db.execSQL(
                    "INSERT OR IGNORE INTO publications (sourceId, `key`, contentMode, fullTextStreak) " +
                        "SELECT id, '', 'AUTO', 0 FROM sources WHERE ($chosen) OR maxArticles IS NOT NULL",
                )
                db.execSQL(
                    "UPDATE publications SET " +
                        "chosenMode = (SELECT s.contentMode FROM sources s WHERE s.id = publications.sourceId AND s.$chosen), " +
                        "maxArticles = (SELECT s.maxArticles FROM sources s WHERE s.id = publications.sourceId) " +
                        "WHERE `key` = ''",
                )
                // Left-out tt-rss feeds.
                db.execSQL(
                    "INSERT OR IGNORE INTO publications (sourceId, `key`, contentMode, fullTextStreak) " +
                        "SELECT sourceId, originId, 'AUTO', 0 FROM left_out_feeds",
                )
                db.execSQL(
                    "UPDATE publications SET leftOut = 1, title = (SELECT l.title FROM left_out_feeds l " +
                        "WHERE l.sourceId = publications.sourceId AND l.originId = publications.`key`) " +
                        "WHERE EXISTS (SELECT 1 FROM left_out_feeds l WHERE l.sourceId = publications.sourceId AND l.originId = publications.`key`)",
                )
                // Skipping paid posts. A tt-rss account's switch covered all its feeds; it goes to each
                // one seen so far, and a feed first seen later starts with it off.
                db.execSQL(
                    "INSERT OR IGNORE INTO publications (sourceId, `key`, contentMode, fullTextStreak) " +
                        "SELECT id, '', 'AUTO', 0 FROM sources WHERE skipPaidPosts = 1 AND kind != 'TTRSS'",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO publications (sourceId, `key`, contentMode, fullTextStreak) " +
                        "SELECT DISTINCT a.sourceId, a.originId, 'AUTO', 0 FROM articles a JOIN sources s ON s.id = a.sourceId " +
                        "WHERE s.kind = 'TTRSS' AND s.skipPaidPosts = 1 AND a.originId IS NOT NULL",
                )
                db.execSQL(
                    "UPDATE publications SET skipPaidPosts = 1 WHERE sourceId IN (SELECT id FROM sources WHERE skipPaidPosts = 1) " +
                        "AND (`key` != '' OR sourceId NOT IN (SELECT id FROM sources WHERE kind = 'TTRSS'))",
                )
                db.execSQL("DROP TABLE left_out_feeds")
                db.execSQL("ALTER TABLE sources ADD COLUMN `feedsListedAt` INTEGER")
                db.execSQL("UPDATE sources SET contentMode = 'AUTO' WHERE kind IN ('FEED', 'TTRSS')")
                db.execSQL(
                    "UPDATE sources SET contentModeChosen = 0, maxArticles = NULL, section = NULL, skipPaidPosts = 0, " +
                        "fullTextEvidence = NULL, fullTextStreak = 0, fullTextDay = NULL",
                )
            }
        }

        /** Indexes for the per-source activity and starred counts the screens watch (see [ArticleEntity]). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_sourceId_discoveredAt` ON `articles` (`sourceId`, `discoveredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_starredAt` ON `articles` (`starredAt`)")
            }
        }

        /** tt-rss's own error fetching each of its feeds. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE publications ADD COLUMN serverError TEXT")
            }
        }

        /**
         * Feed text stored too big to read back, before [FeedSync.storable] capped it: Android
         * reads a row through a 2 MB window, and one row over it fails every query loading it,
         * an edition's and a source's page included. Read in pieces that fit the window and
         * stored as a new row would be, keeping a post's words when an inlined picture was the
         * bulk. Picked by characters, which SQLite counts without reading the row through the
         * window; a lower bar than the cap, as one character can take four bytes.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val oversized = db.query("SELECT id, length(feedHtml) FROM articles WHERE length(feedHtml) > ?", arrayOf<Any>(FeedSync.MAX_FEED_HTML_CHARS * 2 / 3)).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) }
                }
                for ((id, length) in oversized) {
                    // Too big for its pictures to be all of it, and too big to read into a small
                    // e-reader's memory: the migration would run out at every launch.
                    if (length > OVERSIZED_UNREAD) {
                        db.execSQL("UPDATE articles SET feedHtml = NULL WHERE id = ?", arrayOf<Any>(id))
                        continue
                    }
                    val text = StringBuilder(length)
                    for (start in 1..length step OVERSIZED_PIECE) {
                        db.query("SELECT substr(feedHtml, ?, ?) FROM articles WHERE id = ?", arrayOf<Any>(start, OVERSIZED_PIECE, id)).use { c ->
                            if (c.moveToFirst()) text.append(c.getString(0))
                        }
                    }
                    db.execSQL("UPDATE articles SET feedHtml = ? WHERE id = ?", arrayOf<Any?>(FeedSync.storable(text.toString()), id))
                }
            }
        }
        /** tt-rss's opt-in to mark articles read there when they expire here. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN markExpiredRead INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** When each tt-rss feed last posted, for the quiet ones' note on Sources. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE publications ADD COLUMN newestPostAt INTEGER")
                db.execSQL("ALTER TABLE publications ADD COLUMN watchedSince INTEGER")
                db.execSQL("ALTER TABLE sources ADD COLUMN ttrssSeenId INTEGER")
            }
        }

        /** tt-rss's opt-in to take what's starred there into the next edition. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN takeTtrssStars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sources ADD COLUMN ttrssStarsNoted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ttrss_stars` (`sourceId` INTEGER NOT NULL, `articleId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `articleId`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /** Why a saved link can never be read, so editions stop trying it and the reading list says so. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN unreadable TEXT")
            }
        }

        /** What a post is beside podcast episodes, and the audio it carries; older articles stay unjudged. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN audioUrl TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN audioType TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN audioBytes INTEGER")
                db.execSQL("ALTER TABLE articles ADD COLUMN episodeKind TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN episodePair TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN audioSeconds INTEGER")
            }
        }

        /**
         * Rows a feed made too big to read back, before [RowLimits]: cut to it, or gone where a
         * link or guid is too long to keep, as a new row would be. One in an unsent edition stays,
         * since it was read back to get there. SQL alone, which never reads a row through the window.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val link = RowLimits.MAX_LINK
                val tooLong = "state != 'IN_EDITION' AND (length(url) > $link OR length(guid) > $link)"
                // Foreign keys are off during a migration, so their ON DELETE SET NULL wouldn't run.
                db.execSQL("UPDATE edition_articles SET articleId = NULL WHERE articleId IN (SELECT id FROM articles WHERE $tooLong)")
                db.execSQL("DELETE FROM articles WHERE $tooLong")
                db.execSQL("UPDATE articles SET viaUrl = NULL WHERE length(viaUrl) > $link")
                db.execSQL("UPDATE articles SET audioUrl = NULL, audioType = NULL, audioBytes = NULL, audioSeconds = NULL WHERE length(audioUrl) > $link")
                fun cut(table: String, column: String, max: Int) =
                    db.execSQL("UPDATE $table SET $column = rtrim(substr($column, 1, ${max - 1})) || '…' WHERE length($column) > $max")
                cut("articles", "title", RowLimits.MAX_TITLE)
                cut("articles", "author", RowLimits.MAX_AUTHOR)
                cut("articles", "originTitle", RowLimits.MAX_TITLE)
                cut("articles", "audioType", RowLimits.MAX_AUTHOR)
                cut("sources", "title", RowLimits.MAX_TITLE)
                cut("publications", "title", RowLimits.MAX_TITLE)
                cut("edition_articles", "title", RowLimits.MAX_TITLE)
                cut("edition_articles", "sourceTitle", RowLimits.MAX_TITLE)
                db.execSQL("UPDATE articles SET episodePair = NULL WHERE length(episodePair) > $link")
                db.execSQL("UPDATE sources SET siteUrl = NULL WHERE length(siteUrl) > $link")
                db.execSQL("UPDATE publications SET feedUrl = NULL WHERE length(feedUrl) > $link")
            }
        }

        // Characters per piece: at four bytes each, well inside the window.
        private const val OVERSIZED_PIECE = 200_000
        private const val OVERSIZED_UNREAD = 5_000_000

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "newspaperss.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).build()
    }
}
