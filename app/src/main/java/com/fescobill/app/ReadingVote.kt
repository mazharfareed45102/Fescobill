package com.fescobill.app

/**
 * Combines several readings of the same number (from the photo as taken and from enlarged,
 * sharpened copies) into one, digit by digit. Digits the readings disagree on, or that were
 * read with low confidence, are reported so the person can check them.
 */
object ReadingVote {

    /** One reading of the number. [confidence] has one value (0..1) per digit, if known. */
    class Reading(val digits: String, val confidence: FloatArray? = null)

    class Consensus(
        val digits: String,
        /** Share of the (confidence-weighted) votes that went to the chosen digit, per position. */
        val agreement: FloatArray,
        /** Positions (0-based) to double-check. */
        val uncertain: Set<Int>,
        /** Readings that were used (same number, maybe with a few digits read differently). */
        val readings: Int,
        /** Readings that gave exactly the chosen number. */
        val agreeing: Int,
    ) {
        /** Read several times, always the same, no doubtful digit. */
        val sure: Boolean get() = readings >= 2 && agreeing >= 2 && uncertain.isEmpty()
    }

    private const val UNKNOWN_CONFIDENCE = 0.7f
    private const val MIN_AGREEMENT = 0.75f
    private const val MIN_CONFIDENCE = 0.5f

    fun hamming(a: String, b: String): Int =
        if (a.length != b.length) Int.MAX_VALUE else a.indices.count { a[it] != b[it] }

    /**
     * Votes over [readings] that are the same length as [primary] and differ from it in at most
     * [maxDifferent] digits (so another number on the bill, like the old account number, is ignored).
     */
    fun combine(primary: String, readings: List<Reading>, maxDifferent: Int = 3): Consensus {
        val same = readings.filter { hamming(it.digits, primary) <= maxDifferent }
            .ifEmpty { listOf(Reading(primary)) }
        val n = primary.length
        val chosen = CharArray(n)
        val agreement = FloatArray(n)
        val uncertain = HashSet<Int>()
        for (pos in 0 until n) {
            val weight = HashMap<Char, Float>()
            val confidenceSum = HashMap<Char, Float>()
            val count = HashMap<Char, Int>()
            for (r in same) {
                val c = r.digits[pos]
                val conf = r.confidence?.getOrNull(pos)?.takeIf { it > 0f } ?: UNKNOWN_CONFIDENCE
                weight[c] = (weight[c] ?: 0f) + conf.coerceAtLeast(0.05f)
                confidenceSum[c] = (confidenceSum[c] ?: 0f) + conf
                count[c] = (count[c] ?: 0) + 1
            }
            // Ties go to the primary reading's digit.
            val best = weight.entries.maxWithOrNull(
                compareBy<Map.Entry<Char, Float>> { it.value }.thenBy { if (it.key == primary[pos]) 1 else 0 }
            )!!.key
            chosen[pos] = best
            val total = weight.values.sum()
            agreement[pos] = if (total > 0f) weight[best]!! / total else 0f
            val meanConfidence = confidenceSum[best]!! / count[best]!!
            if (agreement[pos] < MIN_AGREEMENT || meanConfidence < MIN_CONFIDENCE) uncertain += pos
        }
        val digits = String(chosen)
        return Consensus(digits, agreement, uncertain, same.size, same.count { it.digits == digits })
    }
}
