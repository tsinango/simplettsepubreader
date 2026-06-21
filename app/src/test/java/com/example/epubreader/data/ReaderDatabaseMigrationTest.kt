package com.example.epubreader.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderDatabaseMigrationTest {
    @Test
    fun migration2To3RemovesOrphanedLocators() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
            .build()
        db.dao().saveBook(
            BookEntity(
                id = "book1", title = "B1", author = "A1",
                localPath = "/tmp/b1.epub", importedAt = 1000L,
            )
        )
        db.dao().saveBook(
            BookEntity(
                id = "book2", title = "B2", author = "A2",
                localPath = "/tmp/b2.epub", importedAt = 1000L,
            )
        )
        db.dao().saveLocator(
            ReadingLocatorEntity(
                bookId = "book1", chapterPath = "ch1", paragraphIndex = 0,
                sentenceIndex = 0, characterOffset = 0, context = "hello",
                source = "test", updatedAt = 1000L,
            )
        )
        db.dao().saveLocator(
            ReadingLocatorEntity(
                bookId = "book2", chapterPath = "ch2", paragraphIndex = 0,
                sentenceIndex = 0, characterOffset = 0, context = "world",
                source = "test", updatedAt = 1000L,
            )
        )
        db.dao().deleteBookWithLocator("book2")
        assertNull(db.dao().book("book2"))
        val allLocators = db.dao().locators().first()
        assertEquals(1, allLocators.size)
        assertEquals("book1", allLocators.first().bookId)
        db.close()
    }
}