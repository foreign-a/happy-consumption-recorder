package com.happy.consumption.capture

import kotlin.math.abs

/** Coordinates are in the original screenshot's pixel space. */
internal data class CandidateHitBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Prefer an actual box; use the expanded touch region only when no box contains the point. */
internal fun selectCandidateIndex(
    boxes: List<CandidateHitBox>, x: Float, y: Float, padding: Float
): Int? {
    fun find(pad: Float): Int? = boxes.indices.asSequence().filter { index ->
        val box = boxes[index]
        x >= box.left - pad && x <= box.right + pad &&
            y >= box.top - pad && y <= box.bottom + pad
    }.minByOrNull { index ->
        val box = boxes[index]
        abs(x - (box.left + box.right) / 2f) + abs(y - (box.top + box.bottom) / 2f)
    }
    return find(0f) ?: find(padding.coerceAtLeast(0f))
}
