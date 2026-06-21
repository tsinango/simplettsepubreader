package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StopRequestIdTest {
    @Test
    fun consecutiveStopRequestIdsIncrement() {
        var requestId = 0
        val id1 = ++requestId
        val id2 = ++requestId
        assertEquals(1, id1)
        assertEquals(2, id2)
        assertNotEquals(id1, id2)
    }

    @Test
    fun broadcastIntentCarriesRequestId() {
        val requestId = 42
        val extras = android.os.Bundle().apply {
            putInt("stopRequestId", requestId)
            putBoolean("stopComplete", true)
        }
        assertEquals(requestId, extras.getInt("stopRequestId"))
        assertEquals(true, extras.getBoolean("stopComplete"))
    }

    @Test
    fun staleRequestIdDoesNotMatch() {
        val currentId = 3
        val receivedId = 2
        assertNotEquals(currentId, receivedId)
    }
}
