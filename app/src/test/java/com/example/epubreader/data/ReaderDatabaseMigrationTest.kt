package com.example.epubreader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderDatabaseMigrationTest {
    private val dbName = "test-migrate.db"

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration2To3RemovesOrphanedLocatorsAndCreatesIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Build a true v2 schema (no FK, no index on locators) with raw SQLite.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS books (" +
                    "id TEXT NOT NULL PRIMARY KEY," +
                    "title TEXT NOT NULL," +
                    "author TEXT NOT NULL," +
                    "localPath TEXT NOT NULL," +
                    "coverPath TEXT," +
                    "importedAt INTEGER NOT NULL," +
                    "parseState TEXT NOT NULL DEFAULT 'READY')",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS locators (" +
                    "bookId TEXT NOT NULL PRIMARY KEY," +
                    "chapterPath TEXT NOT NULL," +
                    "paragraphIndex INTEGER NOT NULL," +
                    "sentenceIndex INTEGER NOT NULL," +
                    "characterOffset INTEGER NOT NULL," +
                    "context TEXT NOT NULL," +
                    "source TEXT NOT NULL," +
                    "updatedAt INTEGER NOT NULL)",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS settings (" +
                    "id INTEGER NOT NULL PRIMARY KEY," +
                    "fontSize REAL NOT NULL DEFAULT 20," +
                    "lineHeight REAL NOT NULL DEFAULT 1.6," +
                    "horizontalPadding REAL NOT NULL DEFAULT 20," +
                    "theme TEXT NOT NULL DEFAULT 'SYSTEM'," +
                    "speechRate REAL NOT NULL DEFAULT 1," +
                    "pitch REAL NOT NULL DEFAULT 1," +
                    "voiceName TEXT," +
                    "ttsEngine TEXT NOT NULL DEFAULT 'SYSTEM')",
            )
            raw.execSQL("INSERT OR IGNORE INTO settings (id) VALUES (1)")
            raw.execSQL(
                "INSERT OR REPLACE INTO books (id, title, author, localPath, importedAt) " +
                    "VALUES ('book1', 'B1', 'A1', '/tmp/b1.epub', 1000)",
            )
            raw.execSQL(
                "INSERT OR REPLACE INTO books (id, title, author, localPath, importedAt) " +
                    "VALUES ('orphaned', 'Orphan', 'O1', '/tmp/o.epub', 1000)",
            )
            raw.execSQL(
                "INSERT OR REPLACE INTO locators VALUES " +
                    "('book1', 'ch1', 0, 0, 0, 'hello', 'test', 1000)",
            )
            // Orphan locator: its book is deleted next; v2 has no CASCADE so it survives pre-migration.
            raw.execSQL(
                "INSERT OR REPLACE INTO locators VALUES " +
                    "('orphaned', 'ch1', 0, 0, 0, 'orphan', 'test', 1000)",
            )
            raw.execSQL("DELETE FROM books WHERE id = 'orphaned'")
            raw.version = 2
        }

        // Open with Room; the 2->3 and 3->4 migrations must run and pass schema validation.
        val migrated = Room.databaseBuilder(context, ReaderDatabase::class.java, dbName)
            .addMigrations(ReaderDatabase.MIGRATION_2_3, ReaderDatabase.MIGRATION_3_4, ReaderDatabase.MIGRATION_4_5)
            .build()
        val locators = migrated.dao().locators().first()
        assertEquals(1, locators.size)
        assertEquals("book1", locators.first().bookId)

        // Verify the index Room expects actually exists after migration.
        val indexNames = mutableListOf<String>()
        migrated.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='locators'")
            .use { cursor ->
                while (cursor.moveToNext()) indexNames += cursor.getString(0)
            }
        assertTrue("index_locators_bookId missing after migration", "index_locators_bookId" in indexNames)
        migrated.close()
    }

    @Test
    fun settingsRoundTripPersistsVitsModelId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        db.dao().saveSettings(
            ReaderSettingsEntity(ttsEngine = "VITS", vitsModelId = "MELO_TTS_ZH_EN"),
        )
        val saved = db.dao().settings().first()
        assertNotNull(saved)
        assertEquals("VITS", saved!!.ttsEngine)
        assertEquals("MELO_TTS_ZH_EN", saved.vitsModelId)
        db.close()
    }

    @Test
    fun settingsDefaultVitsModelIdIsFanchenWnj() {
        val defaults = ReaderSettingsEntity()
        assertEquals("FANCHEN_WNJ", defaults.vitsModelId)
        assertEquals("SYSTEM", defaults.ttsEngine)
    }
}
