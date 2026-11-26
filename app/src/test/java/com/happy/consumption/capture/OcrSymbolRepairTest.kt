package com.happy.consumption.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSymbolRepairTest {
    private fun split(point: String = ",") = listOf(
        OcrSymbol("4", 10, 20, 30, 60, true),
        OcrSymbol(point, 34, 55, 39, 60, false),
        OcrSymbol("2", 43, 20, 63, 60, true),
        OcrSymbol("7", 67, -15, 87, 60, false)
    )

    @Test fun joinsPunctuationSeparatedFractionWithoutTrimmingCharacterBoxes() {
        val fixed = repairSplitDecimals(split())
        assertEquals(",", fixed[1].text)
        assertFalse(fixed[2].wordStart)
        assertEquals(-15, fixed[3].top)
        assertEquals(20, fixed[0].top)
    }

    @Test fun recognizesAnActualPointToo() {
        assertFalse(repairSplitDecimals(split("."))[2].wordStart)
    }

    @Test fun doesNotJoinWithoutPointOrWhenPointDoesNotLookLikeOne() {
        assertEquals(split(":"), repairSplitDecimals(split(":")))
        val tall = split().toMutableList().apply { this[1] = this[1].copy(top = 42) }
        assertEquals(tall, repairSplitDecimals(tall))
    }

    @Test fun doesNotJoinDistantOrDifferentLineNumbers() {
        val far = split().toMutableList().apply { this[2] = this[2].copy(left = 90, right = 110) }
        assertEquals(far, repairSplitDecimals(far))
        val otherLine = split().toMutableList().apply { this[2] = this[2].copy(top = 45, bottom = 85) }
        assertEquals(otherLine, repairSplitDecimals(otherLine))
    }

    @Test fun doesNotJoinThreeDigitsAfterPointOrAnExistingWord() {
        val threeDigits = split(".") + OcrSymbol("3", 90, 20, 110, 60, false)
        assertEquals(threeDigits, repairSplitDecimals(threeDigits))
        val existingWord = split().map { if (it.text == "2") it.copy(wordStart = false) else it }
        assertEquals(existingWord, repairSplitDecimals(existingWord))
    }

    @Test fun preservesOrdinaryCommaSeparatedWord() {
        val sameWord = split().map { it.copy(wordStart = false) }.toMutableList()
        sameWord[0] = sameWord[0].copy(wordStart = true)
        assertEquals(sameWord, repairSplitDecimals(sameWord))
        assertTrue(repairSplitDecimals(emptyList()).isEmpty())
    }

    @Test fun fullCandidateConversionIsConsistentAcrossWordBreaks() {
        val separated = split()
        val sameWord = separated.mapIndexed { i, s -> if (i == 2) s.copy(wordStart = false) else s }
        assertEquals("4.27", extractOcrCandidates(separated).single().text)
        assertEquals(extractOcrCandidates(sameWord), extractOcrCandidates(separated))
        assertEquals(-15, extractOcrCandidates(separated).single().top)
        val grouping = separated + OcrSymbol("3", 90, 20, 110, 60, false)
        val groupingSameWord = grouping.mapIndexed { i, s -> if (i == 2) s.copy(wordStart = false) else s }
        assertEquals("4273", extractOcrCandidates(grouping).single().text)
        assertEquals(extractOcrCandidates(groupingSameWord), extractOcrCandidates(grouping))
    }

    @Test fun commaAndMalformedNumbersAreHandledExplicitly() {
        assertEquals("1234", normalizeOcrNumber("1,234"))
        assertEquals("1234.56", normalizeOcrNumber("1,234.56"))
        assertEquals("1.23", normalizeOcrNumber("1,23"))
        assertEquals(null, normalizeOcrNumber("1,23,45"))
        assertEquals(null, normalizeOcrNumber("1.2.3"))
        val distant = split().mapIndexed { i, s -> if (i >= 2) s.copy(left = s.left + 60, right = s.right + 60) else s }
        assertEquals(2, extractOcrCandidates(distant).size)
    }
}
