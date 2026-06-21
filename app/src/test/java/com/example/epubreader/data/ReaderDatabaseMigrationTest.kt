package com.example.epubreader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReaderDatabaseMigrationTest {
    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("test-migrate.db", "test-migrate.db-wal", "test-migrate.db-shm").forEach {
            File(context.filesDir, it).delete()
        }
    }

    @Test
    fun migration2To3RemovesOrphanedLocators() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = File(context.filesDir, "test-migrate.db").absolutePath

        // Create raw v2 database (no FK on locators)
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        raw.execSQL("CREATE TABLE IF NOT EXISTS books (" +
            "id TEXT NOT NULL PRIMARY KEY," +
            "title TEXT NOT NULL," +
            "author TEXT NOT NULL," +
            "localPath TEXT NOT NULL," +
            "coverPath TEXT," +
            "importedAt INTEGER NOT NULL," +
            "parseState TEXT NOT NULL DEFAULT 'READY')")
        raw.execSQL("CREATE TABLE IF NOT EXISTS locators (" +
            "bookId TEXT NOT NULL PRIMARY KEY," +
            "chapterPath TEXT NOT NULL," +
            "paragraphIndex INTEGER NOT NULL," +
            "sentenceIndex INTEGER NOT NULL," +
            "characterOffset INTEGER NOT NULL," +
            "context TEXT NOT NULL," +
            "source TEXT NOT NULL," +
            "updatedAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE IF NOT EXISTS settings (" +
            "id INTEGER NOT NULL PRIMARY KEY," +
            "fontSize REAL NOT NULL DEFAULT 20," +
            "lineHeight REAL NOT NULL DEFAULT 1.6," +
            "horizontalPadding REAL NOT NULL DEFAULT 20," +
            "theme TEXT NOT NULL DEFAULT 'SYSTEM'," +
            "speechRate REAL NOT NULL DEFAULT 1," +
            "pitch REAL NOT NULL DEFAULT 1," +
            "voiceName TEXT," +
            "ttsEngine TEXT NOT NULL DEFAULT 'SYSTEM')")
        raw.execSQL("INSERT OR IGNORE INTO settings (id) VALUES (1)")
        raw.execSQL("INSERT OR REPLACE INTO books (id, title, author, localPath, importedAt) " +
            "VALUES ('book1', 'B1', 'A1', '/tmp/b1.epub', 1000)")
        raw.execSQL("INSERT OR REPLACE INTO books (id, title, author, localPath, importedAt) " +
            "VALUES ('orphaned', 'Orphan', 'O1', '/tmp/o.epub', 1000)")
        raw.execSQL("INSERT OR REPLACE INTO locators VALUES " +
            "('book1', 'ch1', 0, 0, 0, 'hello', 'test', 1000)")
        raw.execSQL("INSERT OR REPLACE INTO locators VALUES " +
            "('orphaned', 'ch1', 0, 0, 0, 'orphan', 'test', 1000)")
        raw.execSQL("DELETE FROM books WHERE id = 'orphaned'")
        raw.version = 2
        raw.close()

        // Open with Room and migrate to v3
        val migrated = Room.databaseBuilder(context, ReaderDatabase::class.java, dbPath)
            .addMigrations(ReaderDatabase.MIGRATION_2_3)
            .build()
        val locators = migrated.dao().locators().first()
        assertEquals(1, locators.size)
        assertEquals("book1", locators.first().bookId)
        migrated.close()
    }
}