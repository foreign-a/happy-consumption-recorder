package com.happy.consumption.capture

/** Character geometry is kept independent of Android so the repair can be tested on the JVM. */
internal data class OcrSymbol(
    val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val wordStart: Boolean
)

/** Rejoin a number split at a word boundary only when a small baseline-aligned mark is present. */
internal fun repairSplitDecimals(input: List<OcrSymbol>): List<OcrSymbol> {
    val symbols = input.toMutableList()
    var start = 0
    while (start < symbols.size) {
        var end = start + 1
        while (end < symbols.size && !symbols[end].wordStart) end++
        var nextEnd = end + 1
        while (nextEnd < symbols.size && !symbols[nextEnd].wordStart) nextEnd++
        if (end >= symbols.size || end - start < 2 || nextEnd - end !in 2..3) { start = end; continue }
        val integer = symbols.subList(start, end - 1)
        val point = symbols[end - 1]
        val fraction = symbols.subList(end, nextEnd)
        fun digit(s: OcrSymbol) = s.text.length == 1 && s.text[0] in '0'..'9'
        if (!integer.all(::digit) || !fraction.all(::digit) || point.text !in listOf(".", ",") ||
            (fraction.size == 3 && point.text != ",")) { start = end; continue }
        val digits = integer + fraction
        val height = digits.map { it.bottom - it.top }.sorted()[digits.size / 2]
        val baseline = digits.map { it.bottom }.sorted()[digits.size / 2]
        val width = point.right - point.left
        val pointHeight = point.bottom - point.top
        val gapBefore = point.left - integer.last().right
        val gapAfter = fraction.first().left - point.right
        val smallRoundPoint = width > 0 && pointHeight > 0 && width <= height * .35 &&
            pointHeight <= height * .35 && width <= pointHeight * 1.6 && pointHeight <= width * 1.6
        val aligned = digits.all { kotlin.math.abs(it.bottom - baseline) <= height * .2 } &&
            kotlin.math.abs(point.bottom - baseline) <= height * .2
        val close = gapBefore >= 0 && gapAfter >= 0 && gapBefore <= height * .4 && gapAfter <= height * .4
        if (height <= 0 || !smallRoundPoint || !aligned || !close) { start = end; continue }
        symbols[end] = symbols[end].copy(wordStart = false)
        start = nextEnd
    }
    return symbols
}

internal data class OcrCandidate(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Interpret decimal commas and grouping commas consistently, regardless of OCR word breaks. */
internal fun normalizeOcrNumber(value: String): String? = when {
    value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) -> value
    value.matches(Regex("[0-9]+,[0-9]{1,2}")) -> value.replace(',', '.')
    value.matches(Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?")) -> value.replace(",", "")
    else -> null
}

internal fun extractOcrCandidates(input: List<OcrSymbol>): List<OcrCandidate> {
    val output = mutableListOf<OcrCandidate>()
    val pieces = mutableListOf<OcrSymbol>()
    fun flush() {
        val word = pieces.joinToString("") { it.text }
        Regex("[0-9]+(?:[.,][0-9]+)*").findAll(word).forEach { match ->
            val normalized = normalizeOcrNumber(match.value) ?: return@forEach
            var offset = 0
            val included = pieces.filter { piece ->
                val from = offset
                offset += piece.text.length
                from <= match.range.last && offset > match.range.first
            }
            if (included.isNotEmpty()) {
                val left = included.minOf { it.left }
                val top = included.minOf { it.top }
                val right = included.maxOf { it.right }
                val bottom = included.maxOf { it.bottom }
                if (right > left && bottom > top) output += OcrCandidate(normalized, left, top, right, bottom)
            }
        }
        pieces.clear()
    }
    repairSplitDecimals(input).forEach { symbol ->
        if (symbol.wordStart && pieces.isNotEmpty()) flush()
        pieces += symbol
    }
    if (pieces.isNotEmpty()) flush()
    return output
}
