package com.mightyfrog.stratagemcaller

import com.mightyfrog.stratagemcaller.macro.MacroDirection
import com.mightyfrog.stratagemcaller.macro.MacroEngine
import com.mightyfrog.stratagemcaller.macro.MacroOutput
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroEngineTest {
    private class RecordingOutput : MacroOutput {
        val events = mutableListOf<String>()
        override suspend fun activate() { events += "LB" }
        override suspend fun direction(direction: MacroDirection) { events += direction.name }
        override suspend fun releaseDirection(direction: MacroDirection) { events += "REL_${direction.name}" }
        override suspend fun releaseAll() { events += "RELEASE_ALL" }
    }

    @Test
    fun sequencesAndReleasesLb() = runTest {
        val output = RecordingOutput()
        val engine = MacroEngine(output)
        engine.execute(this, listOf('U', 'D', 'R'), 1) {}
        advanceUntilIdle()
        assertEquals("LB", output.events.first())
        assertEquals("RELEASE_ALL", output.events.last())
        assertEquals(listOf("UP", "DOWN", "RIGHT"), output.events.filter { it in setOf("UP", "DOWN", "LEFT", "RIGHT") })
    }

    @Test
    fun rejectsUnknownDirectionsWithoutCrash() = runTest {
        val output = RecordingOutput()
        val engine = MacroEngine(output)
        engine.execute(this, listOf('X'), 1) {}
        advanceUntilIdle()
        assertEquals(listOf("LB", "RELEASE_ALL"), output.events)
    }
}
