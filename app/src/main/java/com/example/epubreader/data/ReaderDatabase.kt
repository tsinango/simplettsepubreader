package com.example.epubreader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
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
}

@Database(
    entities = [BookEntity::class, ReadingLocatorEntity::class, ReaderSettingsEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun dao(): ReaderDao

    companion object {
        fun create(context: Context): ReaderDatabase = Room.databaseBuilder(
            context,
            ReaderDatabase::class.java,
            "reader.db",
        ).addMigrations(MIGRATION_1_2).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN ttsEngine TEXT NOT NULL DEFAULT 'SYSTEM'")
            }
        }
    }
}
