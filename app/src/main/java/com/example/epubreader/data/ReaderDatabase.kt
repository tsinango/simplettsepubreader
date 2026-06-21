package com.example.epubreader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderDao {
    @Query("SELECT * FROM books ORDER BY importedAt DESC")
    fun books(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBook(book: BookEntity)

    @Query("SELECT * FROM locators WHERE bookId = :bookId")
    suspend fun locator(bookId: String): ReadingLocatorEntity?

    @Query("SELECT * FROM locators")
    fun locators(): Flow<List<ReadingLocatorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLocator(locator: ReadingLocatorEntity)

    @Query("SELECT * FROM settings WHERE id = 1")
    fun settings(): Flow<ReaderSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: ReaderSettingsEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("DELETE FROM locators WHERE bookId = :bookId")
    suspend fun deleteLocator(bookId: String)

    @Transaction
    suspend fun deleteBookWithLocator(bookId: String) {
        deleteLocator(bookId)
        deleteBook(bookId)
    }
}

@Database(
    entities = [BookEntity::class, ReadingLocatorEntity::class, ReaderSettingsEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun dao(): ReaderDao

    companion object {
        fun create(context: Context): ReaderDatabase = Room.databaseBuilder(
            context,
            ReaderDatabase::class.java,
            "reader.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN ttsEngine TEXT NOT NULL DEFAULT 'SYSTEM'")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM locators WHERE bookId NOT IN (SELECT id FROM books)")
                db.execSQL("CREATE TABLE IF NOT EXISTS locators_new (" +
                    "bookId TEXT NOT NULL PRIMARY KEY," +
                    "chapterPath TEXT NOT NULL," +
                    "paragraphIndex INTEGER NOT NULL," +
                    "sentenceIndex INTEGER NOT NULL," +
                    "characterOffset INTEGER NOT NULL," +
                    "context TEXT NOT NULL," +
                    "source TEXT NOT NULL," +
                    "updatedAt INTEGER NOT NULL," +
                    "FOREIGN KEY (bookId) REFERENCES books(id) ON DELETE CASCADE" +
                    ")")
                db.execSQL("INSERT INTO locators_new SELECT * FROM locators")
                db.execSQL("DROP TABLE locators")
                db.execSQL("ALTER TABLE locators_new RENAME TO locators")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locators_bookId ON locators(bookId)")
            }
        }
    }
}
