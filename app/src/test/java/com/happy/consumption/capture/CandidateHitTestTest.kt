package com.happy.consumption.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateHitTestTest {
    @Test fun actualLongBoxBeatsNearbyShortBoxExpandedRegion() {
        val boxes = listOf(CandidateHitBox(0f, 0f, 100f, 20f), CandidateHitBox(105f, 0f, 115f, 20f))
        assertEquals(0, selectCandidateIndex(boxes, 99f, 10f, 15f))
        assertEquals(0, selectCandidateIndex(boxes, 100f, 10f, 15f))
    }

    @Test fun overlappingActualBoxesChooseNearestCenterWithStableTie() {
        val boxes = listOf(CandidateHitBox(0f, 0f, 30f, 20f), CandidateHitBox(20f, 0f, 40f, 20f))
        assertEquals(1, selectCandidateIndex(boxes, 29f, 10f, 10f))
        assertEquals(0, selectCandidateIndex(boxes, 22.5f, 10f, 10f))
    }

    @Test fun expansionAppliesOnlyOutsideAllActualBoxes() {
        val boxes = listOf(CandidateHitBox(10f, 10f, 20f, 20f), CandidateHitBox(30f, 10f, 40f, 20f))
        assertEquals(1, selectCandidateIndex(boxes, 27f, 15f, 5f))
        assertNull(selectCandidateIndex(boxes, 24f, 15f, 3f))
        assertNull(selectCandidateIndex(boxes, 9f, 15f, -1f))
    }

    @Test fun sameValueAtDifferentPositionsRemainsIndependentlySelectable() {
        val values = listOf("12.34", "12.34")
        val boxes = listOf(CandidateHitBox(0f, 0f, 40f, 10f), CandidateHitBox(0f, 30f, 40f, 40f))
        val first = requireNotNull(selectCandidateIndex(boxes, 5f, 5f, 20f))
        val second = requireNotNull(selectCandidateIndex(boxes, 5f, 35f, 20f))
        assertEquals(0, first)
        assertEquals(1, second)
        assertEquals(values[first], values[second])
    }

    @Test fun emptyListHasNoHit() {
        assertNull(selectCandidateIndex(emptyList(), 0f, 0f, 10f))
    }
}
