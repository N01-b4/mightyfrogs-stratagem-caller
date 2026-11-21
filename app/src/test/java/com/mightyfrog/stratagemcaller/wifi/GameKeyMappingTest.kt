package com.mightyfrog.stratagemcaller.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameKeyMappingTest {
    @Test
    fun oldDefaultTimingsMigrateButCustomTimingsRemain() {
        assertEquals("55", WifiSettings.migrateDelay(null))
        assertEquals("55", WifiSettings.migrateDelay("40"))
        assertEquals("55", WifiSettings.migrateDelay("90"))
        assertEquals("75", WifiSettings.migrateDelay("75"))
    }

    @Test
    fun defaultMappingIsValid() {
        assertNull(GameKeyMapping().validationError())
        assertEquals("UP", GameKeyMapping().up)
        assertEquals("DOWN", GameKeyMapping().down)
        assertEquals("LEFT", GameKeyMapping().left)
        assertEquals("RIGHT", GameKeyMapping().right)
    }

    @Test
    fun repeatedOrUnsupportedKeysAreRejected() {
        assertEquals(
            "The activation and direction keys must be different.",
            GameKeyMapping(up = "LEFTCTRL").validationError()
        )
        assertEquals(
            "Use letters, numbers, arrows, CTRL, SHIFT, ALT, SPACE, or F1–F12.",
            GameKeyMapping(up = "ENTER").validationError()
        )
    }
}
