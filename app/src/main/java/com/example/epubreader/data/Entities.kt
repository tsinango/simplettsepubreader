package com.example.epubreader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val localPath: String,
    val coverPath: String? = null,
    val importedAt: Long,
    val parseState: String = "READY",
)

@Entity(
    tableName = "locators",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class ReadingLocatorEntity(
    @PrimaryKey val bookId: String,
    val chapterPath: String,
    val paragraphIndex: Int,
    val sentenceIndex: Int,
    val characterOffset: Int,
    val context: String,
    val source: String,
    val updatedAt: Long,
)

@Entity(tableName = "settings")
data class ReaderSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.6f,
    val horizontalPadding: Float = 20f,
    val theme: String = "SYSTEM",
    val speechRate: Float = 1f,
    val pitch: Float = 1f,
    val voiceName: String? = null,
    val ttsEngine: String = "SYSTEM",
    val vitsModelId: String = "FANCHEN_WNJ",
    val strongPauseMs: Int = 350,
    val semicolonPauseMs: Int = 220,
    val commaPauseMs: Int = 130,
    val ideographicCommaPauseMs: Int = 80,
    val defaultPauseMs: Int = 40,
)

data class Chapter(
    val path: String,
    val title: String,
    val paragraphs: List<String>,
)

data class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val cover: BookCover? = null,
)

data class BookCover(
    val path: String,
    val mediaType: String,
)

data class SentenceRef(
    val chapterPath: String,
    val paragraphIndex: Int,
    val sentenceIndex: Int,
    val text: String,
)
