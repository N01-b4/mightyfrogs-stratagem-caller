package com.mightyfrog.stratagemcaller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadoutTest {
    private fun validPersonal(ids: List<String>): Boolean =
        ids.size == 4 && ids.distinct().size == 4

    private fun validMission(ids: List<String?>): Boolean =
        ids.size == 6 && ids.filterNotNull().distinct().size == ids.filterNotNull().size

    @Test fun exactlyFourUniquePersonalRequired() {
        assertFalse(validPersonal(listOf("a", "b", "c")))
        assertFalse(validPersonal(listOf("a", "b", "c", "c")))
        assertTrue(validPersonal(listOf("a", "b", "c", "d")))
    }

    @Test fun zeroThroughSixUniqueMissionAllowed() {
        assertTrue(validMission(listOf(null, null, null, null, null, null)))
        assertTrue(validMission(listOf("a", null, "b", null, null, null)))
        assertFalse(validMission(listOf("a", "a", null, null, null, null)))
    }
}
