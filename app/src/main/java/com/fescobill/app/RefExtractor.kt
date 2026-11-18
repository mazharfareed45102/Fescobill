package com.fescobill.app

import kotlin.math.abs
import kotlin.math.max

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = max(1, right - left)
    val height: Int get() = max(1, bottom - top)
    val centerY: Int get() = (top + bottom) / 2
}

/** One line of text found by OCR, with its position in the upright image (null if unknown). */
data class OcrLine(val text: String, val box: Box?)

/** The printed label closest to a number on the bill, e.g. "REFERENCE No." above it. */
enum class LabelKind { REFERENCE, OLD_ACCOUNT, CONSUMER_ID, OTHER }

data class Candidate(
    val digits: String,
    val mode: SearchMode,
    /** The U (urban) / R (rural) letter printed after a reference number, if it was read. */
    val suffix: Char?,
    val score: Int,
    val label: LabelKind?,
) {
    val display: String get() = RefNumber.format(digits) + (suffix?.let { " $it" } ?: "")
    fun toQuery() = BillQuery(digits, mode)
}

/**
 * Finds the bill reference number (or customer ID) in OCR text from a photo of a bill.
 *
 * A FESCO bill has several long numbers: the 14-digit REFERENCE No. (e.g. 07135230628703R),
 * the 14-digit OLD A/C NUMBER, the 10-digit CONSUMER ID, the CNIC, the mobile number...
 * Every 14/10-digit number is scored by the label printed nearest above or before it,
 * by the U/R letter that follows a reference number, and by how cleanly it was read.
 */
object RefExtractor {
    /** A best candidate at least this good, and [CONFIDENT_GAP] ahead of the next, is offered directly. */
    const val CONFIDENT_SCORE = 70
    const val CONFIDENT_GAP = 30

    // Digits plus letters OCR commonly confuses with digits (O->0, I/l->1, S->5, B->8, Z->2).
    private const val DIGITISH = "0-9OoQDIl|SsBZz"
    private val RUN = Regex("[$DIGITISH](?:[$DIGITISH \\-]*[$DIGITISH])?")
    private val GROUP = Regex("[$DIGITISH]+")

    private val LABELS = listOf(
        LabelKind.REFERENCE to Regex("REFER|REFRENCE|\\bREF\\b|REF\\s*NO"),
        LabelKind.OLD_ACCOUNT to Regex("OLD\\s*A\\s*/?\\s*C\\b|A\\s*/\\s*C\\s*N|OLD\\s*ACC"),
        LabelKind.CONSUMER_ID to Regex("CONSUMER\\s*I\\s*D|CUSTOMER\\s*I\\s*D|CONSUMER\\s*NO"),
        LabelKind.OTHER to Regex(
            "CNIC|\\bNTN\\b|MOBILE|\\bMOB\\b|METER|FEEDER|PHONE|\\bGST\\b|TRANSFORMER|PSID|IBAN|BANK"
        ),
    )

    private class Group(val digits: String, val real: Int, val start: Int, val end: Int)

    private class RawNumber(
        val digits: String,
        val mode: SearchMode,
        val suffix: Char?,
        val start: Int,
        val end: Int,
        val groupSizes: List<Int>,
        val mapped: Int,
        val trimmed: Boolean,
    )

    private class Label(val kind: LabelKind, val box: Box, val line: Int)

    fun extract(lines: List<OcrLine>): List<Candidate> {
        // Lines without a position get a synthetic one so "label above number" still works by line order.
        val placed = lines.mapIndexed { i, l -> l.text to (l.box ?: Box(0, i * 40, 1000, i * 40 + 30)) }

        val labels = mutableListOf<Label>()
        placed.forEachIndexed { i, (text, box) ->
            val upper = text.uppercase()
            for ((kind, re) in LABELS) {
                for (m in re.findAll(upper)) {
                    labels += Label(kind, spanBox(box, text, m.range.first, m.range.last + 1), i)
                }
            }
        }

        val best = LinkedHashMap<String, Candidate>()
        placed.forEachIndexed { i, (text, box) ->
            for (n in findNumbers(text)) {
                val label = nearestLabel(spanBox(box, text, n.start, n.end), i, labels)
                val c = Candidate(n.digits, n.mode, n.suffix, score(n, label), label)
                val prev = best[c.digits]
                best[c.digits] = when {
                    prev == null -> c
                    c.score > prev.score -> c.copy(suffix = c.suffix ?: prev.suffix)
                    else -> prev.copy(suffix = prev.suffix ?: c.suffix)
                }
            }
        }
        return rank(best.values)
    }

    fun rank(candidates: Collection<Candidate>): List<Candidate> =
        candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.mode.ordinal })

    /** True when the first candidate is clearly the right one and can be offered without a list. */
    fun isConfident(ranked: List<Candidate>): Boolean {
        val best = ranked.firstOrNull() ?: return false
        if (ranked.size == 1) return best.score >= 40
        return best.score >= CONFIDENT_SCORE && best.score - ranked[1].score >= CONFIDENT_GAP
    }

    private fun toDigit(c: Char): Char = when (c) {
        in '0'..'9' -> c
        'O', 'o', 'Q', 'D' -> '0'
        'I', 'l', '|' -> '1'
        'S', 's' -> '5'
        'B' -> '8'
        'Z', 'z' -> '2'
        else -> c
    }

    private fun findNumbers(text: String): List<RawNumber> {
        val out = mutableListOf<RawNumber>()
        for (run in RUN.findAll(text)) {
            val base = run.range.first
            val groups = GROUP.findAll(run.value).map { g ->
                Group(
                    digits = g.value.map(::toDigit).joinToString(""),
                    real = g.value.count { it in '0'..'9' },
                    start = base + g.range.first,
                    end = base + g.range.last + 1,
                )
            }.toList()

            for (i in groups.indices) {
                var len = 0
                var real = 0
                for (j in i until groups.size) {
                    len += groups[j].digits.length
                    real += groups[j].real
                    val mapped = len - real
                    if (len > 15 || mapped > 2) break
                    val parts = groups.subList(i, j + 1)
                    val digits = parts.joinToString("") { it.digits }
                    val start = parts.first().start
                    val end = parts.last().end
                    val sizes = parts.map { it.digits.length }
                    when {
                        len == RefNumber.REFERENCE_LENGTH -> out += RawNumber(
                            digits, SearchMode.REFERENCE, suffixAfter(text, end), start, end, sizes, mapped, false
                        )
                        len == RefNumber.CUSTOMER_ID_LENGTH -> out += RawNumber(
                            digits, SearchMode.CUSTOMER_ID, null, start, end, sizes, mapped, false
                        )
                        // One stray character glued to a reference number, e.g. the R read as "8",
                        // or a table border read as "1": offer both 14-digit halves, ranked low.
                        len == 15 && i == j -> {
                            out += RawNumber(
                                digits.substring(0, 14), SearchMode.REFERENCE, null, start, end, listOf(14), mapped, true
                            )
                            out += RawNumber(
                                digits.substring(1), SearchMode.REFERENCE, null, start, end, listOf(14), mapped, true
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    /** The U/R letter right after a number ("07135230628703R", "... 0628703 R"), if present. */
    private fun suffixAfter(text: String, end: Int): Char? {
        var k = end
        var spaces = 0
        while (k < text.length && text[k] == ' ' && spaces < 2) {
            k++
            spaces++
        }
        val c = text.getOrNull(k)?.uppercaseChar() ?: return null
        if (c != 'R' && c != 'U') return null
        val next = text.getOrNull(k + 1)
        return if (next == null || !next.isLetter()) c else null
    }

    /** Approximate box of characters [start, end) inside a line, assuming even character widths. */
    private fun spanBox(line: Box, text: String, start: Int, end: Int): Box {
        val n = max(1, text.length)
        val left = line.left + line.width * start / n
        val right = line.left + line.width * end / n
        return Box(left, line.top, max(left + 1, right), line.bottom)
    }

    private fun nearestLabel(number: Box, lineIndex: Int, labels: List<Label>): LabelKind? {
        var bestKind: LabelKind? = null
        var bestDist = Double.MAX_VALUE
        for (l in labels) {
            val h = max(number.height, l.box.height)
            val sameRow = l.line == lineIndex || abs(l.box.centerY - number.centerY) < h * 0.6
            val dist = if (sameRow) {
                // Label printed before the number on the same row, e.g. "REFERENCE No. 0713...".
                if (l.box.left > number.left) continue
                val gap = number.left - l.box.right
                if (gap > h * 15) continue
                max(0, gap) * 0.5
            } else {
                // Label printed above the number, e.g. the table header of its cell.
                if (l.box.centerY > number.centerY) continue
                if (number.top - l.box.bottom > h * 5) continue
                val slack = max(l.box.width / 2, h)
                if (l.box.right + slack < number.left || l.box.left - slack > number.right) continue
                (number.centerY - l.box.centerY) + 0.35 * abs(number.left - l.box.left)
            }
            if (dist < bestDist) {
                bestDist = dist
                bestKind = l.kind
            }
        }
        return bestKind
    }

    private fun score(n: RawNumber, label: LabelKind?): Int {
        val isRef = n.mode == SearchMode.REFERENCE
        var s = if (isRef) 30 else 0
        val g = n.groupSizes
        s += if (isRef) {
            when {
                g == listOf(14) || g == listOf(2, 5, 7) -> 20
                g.size <= 3 -> 0
                else -> -20
            }
        } else {
            if (g == listOf(10)) 10 else -20
        }
        if (n.suffix != null) s += 35
        s -= 10 * n.mapped
        if (n.trimmed) s -= 25
        s += when (label) {
            LabelKind.REFERENCE -> if (isRef) 40 else -10
            LabelKind.OLD_ACCOUNT -> -40
            LabelKind.CONSUMER_ID -> if (isRef) -20 else 45
            LabelKind.OTHER -> -25
            null -> 0
        }
        return s
    }
}
