package com.example.epubreader.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSwitchGateTest {

    @Test
    fun candidateReturnsSameEngineWhenIdMatches() {
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ"))
        gate.markBuilt(first)
        val second = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ"))
        assertSame(first, second)
        assertTrue(gate.isBuilt(first))
    }

    @Test
    fun candidateReleasesOldAndCreatesNewWhenIdChanges() {
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ")) as FakeEmbeddedTtsEngine
        gate.markBuilt(first)
        assertFalse(first.released)
        val second = gate.candidate("KOKORO_ZH", "KOKORO_ZH", FakeEmbeddedTtsEngine.factory("KOKORO_ZH")) as FakeEmbeddedTtsEngine
        assertSame(second, gate.active)
        assertTrue(first.released)
        assertEquals("New engine must replace the old one", 1, first.releaseCalls)
        assertEquals("Old engine must be released exactly once", 1, first.releaseCalls)
        assertFalse("built flag must reset on switch", gate.isBuilt(first))
        assertFalse("new engine starts not-built", gate.isBuilt(second))
    }

    @Test
    fun markBuiltOnlyMarksWhenEngineIsStillActive() {
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ"))
        // Switch before marking built: factory had to release first.
        gate.candidate("MEL", "MEL", FakeEmbeddedTtsEngine.factory("MEL"))
        gate.markBuilt(first as FakeEmbeddedTtsEngine)
        assertNull(gate.builtId)
        assertFalse(gate.isBuilt(first))
    }

    @Test
    fun releaseClearsBothActiveAndBuiltId() {
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ")) as FakeEmbeddedTtsEngine
        gate.markBuilt(first)
        gate.release()
        assertTrue(first.released)
        assertNull(gate.active)
        assertNull(gate.builtId)
    }

    @Test
    fun releaseIsIdempotent() {
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ")) as FakeEmbeddedTtsEngine
        gate.release()
        gate.release()
        // The released engine is detached from the gate after the first call,
        // so the second call must not bump its release counter.
        assertEquals(1, first.releaseCalls)
    }

    @Test
    fun releaseIfIdOnlyReleasesWhenIdMatches() {
        val gate = EngineSwitchGate()
        val kokoro = gate.candidate("KOKORO_ZH", "KOKORO_ZH", FakeEmbeddedTtsEngine.factory("KOKORO_ZH")) as FakeEmbeddedTtsEngine
        // Cancelling WNJ must not release a Kokoro engine.
        gate.releaseIfId("WNJ")
        assertFalse(kokoro.released)
        assertSame(kokoro, gate.active)
        // Deleting Kokoro must release it.
        gate.releaseIfId("KOKORO_ZH")
        assertTrue(kokoro.released)
        assertNull(gate.active)
    }

    @Test
    fun freshGateHasNoActiveOrBuiltEngine() {
        val gate = EngineSwitchGate()
        assertNull(gate.active)
        assertNull(gate.builtId)
    }

    @Test
    fun candidateReleasesPreviousWhenExistingIsNullIsSafe() {
        // Sanity: a fresh gate (no existing engine) must still produce a candidate.
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ"))
        assertNotNull(first)
        assertEquals("WNJ", first.id)
    }

    @Test
    fun isBuiltIsFalseBeforeMarkBuilt() {
        val gate = EngineSwitchGate()
        val first = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ"))
        assertFalse(gate.isBuilt(first))
    }

    @Test
    fun switchingBackToOriginalReleasesAndRecreates() {
        val gate = EngineSwitchGate()
        val wnj = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ")) as FakeEmbeddedTtsEngine
        gate.markBuilt(wnj)
        // switch away
        val kokoro = gate.candidate("KOKORO_ZH", "KOKORO_ZH", FakeEmbeddedTtsEngine.factory("KOKORO_ZH"))
        assertTrue(wnj.released)
        // switch back to WNJ → produces a brand-new instance (release of Kokoro too)
        val wnjAgain = gate.candidate("WNJ", "WNJ", FakeEmbeddedTtsEngine.factory("WNJ"))
        assertNotSameButSameId(wnj, wnjAgain)
        assertTrue((kokoro as FakeEmbeddedTtsEngine).released)
    }

    private fun assertNotSameButSameId(a: EmbeddedTtsEngine, b: EmbeddedTtsEngine) {
        assertTrue(a !== b)
        assertEquals(a.id, b.id)
    }
}