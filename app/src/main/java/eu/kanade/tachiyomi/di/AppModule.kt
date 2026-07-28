package eu.kanade.tachiyomi.di

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.storage.AndroidStorageFolderProvider
import tachiyomi.core.storage.UniFileTempFileManager
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AppModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        addSingletonFactory<SqlDriver> {
            AndroidSqliteDriver(
                schema = Database.Schema,
                context = app,
                name = "tachiyomi.db",
                factory = if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Support database inspector in Android Studio
                    FrameworkSQLiteOpenHelperFactory()
                } else {
                    RequerySQLiteOpenHelperFactory()
                },
                callback = object : AndroidSqliteDriver.Callback(Database.Schema) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        repairInterimCustomTitleMigration(db)
                        setPragma(db, "foreign_keys = ON")
                        setPragma(db, "journal_mode = WAL")
                        setPragma(db, "synchronous = NORMAL")
                    }

                    private fun repairInterimCustomTitleMigration(db: SupportSQLiteDatabase) {
                        fun hasColumn(table: String, column: String): Boolean {
                            return db.query("PRAGMA table_info($table)").use { cursor ->
                                val nameIndex = cursor.getColumnIndexOrThrow("name")
                                generateSequence { cursor.takeIf { it.moveToNext() } }
                                    .any { it.getString(nameIndex) == column }
                            }
                        }

                        // An interim debug build used migration 28 for chapter-title editing.
                        // Repair those databases before SQLDelight queries the current schema.
                        if (!hasColumn("mangas", "custom_title")) {
                            db.execSQL("ALTER TABLE mangas ADD COLUMN custom_title TEXT")
                        }
                        if (hasColumn("libraryView", "custom_title")) return

                        db.execSQL("DROP VIEW IF EXISTS libraryView")
                        db.execSQL(
                            """
                            CREATE VIEW libraryView AS
                            SELECT
                                M.*,
                                coalesce(C.total, 0) AS totalCount,
                                coalesce(C.readCount, 0) AS readCount,
                                coalesce(C.latestUpload, 0) AS latestUpload,
                                coalesce(C.fetchedAt, 0) AS chapterFetchedAt,
                                coalesce(C.lastRead, 0) AS lastRead,
                                coalesce(C.bookmarkCount, 0) AS bookmarkCount,
                                coalesce(MC.category_id, 0) AS category
                            FROM mangas M
                            LEFT JOIN(
                                SELECT
                                    chapters.manga_id,
                                    count(*) AS total,
                                    sum(read) AS readCount,
                                    coalesce(max(chapters.date_upload), 0) AS latestUpload,
                                    coalesce(max(history.last_read), 0) AS lastRead,
                                    coalesce(max(chapters.date_fetch), 0) AS fetchedAt,
                                    sum(chapters.bookmark) AS bookmarkCount
                                FROM chapters
                                LEFT JOIN excluded_scanlators
                                ON chapters.manga_id = excluded_scanlators.manga_id
                                AND chapters.scanlator = excluded_scanlators.scanlator
                                LEFT JOIN history
                                ON chapters._id = history.chapter_id
                                WHERE excluded_scanlators.scanlator IS NULL
                                GROUP BY chapters.manga_id
                            ) AS C
                            ON M._id = C.manga_id
                            LEFT JOIN mangas_categories AS MC
                            ON MC.manga_id = M._id
                            WHERE M.favorite = 1
                            """.trimIndent(),
                        )
                    }

                    private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                        val cursor = db.query("PRAGMA $pragma")
                        cursor.moveToFirst()
                        cursor.close()
                    }
                },
            )
        }
        addSingletonFactory {
            Database(
                driver = get(),
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
        }
        addSingletonFactory<DatabaseHandler> { AndroidDatabaseHandler(get(), get()) }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory {
            XML {
                defaultPolicy {
                    ignoreUnknownChildren()
                }
                autoPolymorphic = true
                xmlDeclMode = XmlDeclMode.Charset
                indent = 2
                xmlVersion = XmlVersion.XML10
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { UniFileTempFileManager(app) }

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }

        addSingletonFactory { NetworkHelper(app, get()) }
        addSingletonFactory { JavaScriptEngine(app) }

        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get()) }
        addSingletonFactory { ExtensionManager(app) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }

        addSingletonFactory { ImageSaver(app) }

        addSingletonFactory { AndroidStorageFolderProvider(app) }
        addSingletonFactory { LocalSourceFileSystem(get()) }
        addSingletonFactory { LocalCoverManager(app, get()) }
        addSingletonFactory { StorageManager(app, get()) }

        // Asynchronously init expensive components for a faster cold start
        ContextCompat.getMainExecutor(app).execute {
            get<NetworkHelper>()

            get<SourceManager>()

            get<Database>()

            get<DownloadManager>()
        }
    }
}
