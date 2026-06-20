package com.example.epubreader

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {
    @Test
    fun formatterRedactsPrivateRootsAndHashesBookIds() {
        val value = DiagnosticLogFormatter.redact(
            "model=/data/user/0/app/files/models/vits.onnx",
            listOf("/data/user/0/app/files"),
        )

        assertEquals("model=<app-private>/models/vits.onnx", value)
        assertEquals(12, DiagnosticLogFormatter.shortHash("private-book-id").length)
        assertFalse(DiagnosticLogFormatter.shortHash("private-book-id").contains("private"))
    }

    @Test
    fun lineContainsMetadataWithoutChangingMessage() {
        val line = DiagnosticLogFormatter.line(
            category = "VITS",
            message = "chunk=2 length=80",
            wallTimeMillis = 0,
            uptimeMillis = 123,
            threadName = "tts-worker",
        )

        assertTrue(line.contains("uptime=123"))
        assertTrue(line.contains("thread=tts-worker"))
        assertTrue(line.contains("[VITS] chunk=2 length=80"))
    }

    @Test
    fun fileStoreRotatesAndExportsOldestFirst() {
        val directory = Files.createTempDirectory("diagnostic-log-test").toFile()
        try {
            val store = DiagnosticFileStore(
                directory = directory,
                currentName = "current.log",
                previousName = "previous.log",
                maxBytes = 12,
            )

            store.append("old-entry\n")
            store.append("new-entry\n")
            val output = StringBuilder()
            store.exportTo(output)
            val exported = output.toString()

            assertTrue(exported.indexOf("old-entry") < exported.indexOf("new-entry"))
            assertTrue(exported.contains("===== previous.log ====="))
            assertTrue(exported.contains("===== current.log ====="))
        } finally {
            directory.deleteRecursively()
        }
    }
}
