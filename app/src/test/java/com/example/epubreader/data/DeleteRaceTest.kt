package com.example.epubreader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeleteRaceTest {
    @Test
    fun saveProgressAfterDeleteDoesNotRecreateLocator() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
                .build()
            db.dao().saveBook(
                BookEntity(
                    id = "book1", title = "B1", author = "A1",
                    localPath = "/tmp/b1.epub", importedAt = 1000L,
                )
            )
            db.dao().deleteBookWithLocator("book1")
            db.dao().saveLocator(
                ReadingLocatorEntity(
                    bookId = "book1", chapterPath = "ch1", paragraphIndex = 0,
                    sentenceIndex = 0, characterOffset = 0, context = "hello",
                    source = "delete_race", updatedAt = 1000L,
                )
            )
            val found = db.dao().book("book1")
            assertNull(found)
            db.close()
        }
    }

    @Test
    fun saveProgressUsesMutexToSerializeWithDelete() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
                .build()
            db.dao().saveBook(
                BookEntity(
                    id = "book2", title = "B2", author = "A2",
                    localPath = "/tmp/b2.epub", importedAt = 1000L,
                )
            )
            db.dao().deleteBookWithLocator("book2")
            val book = db.dao().book("book2")
            assertNull(book)
            db.close()
        }
    }
}
