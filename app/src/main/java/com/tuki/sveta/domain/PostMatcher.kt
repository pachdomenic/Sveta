package com.tuki.sveta.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SwitchKind { OFF, ON }

data class Watch(val id: String, val name: String, val keywords: List<String>)

data class RuleHit(val watch: Watch, val kind: SwitchKind)

/**
 * Decides whether a channel post says that one of the watched neighbour lines went dark or came back.
 * Matching is plain case-insensitive substring search on word stems, which copes well with Ukrainian
 * inflection ("вимкнули", "вимкнено", "вимкнення").
 *
 * A stem only counts when it states a fact that just happened. Channels that report a line also post
 * schedules, warnings and news, all full of the same stems, so a stem is skipped when it sits in a noun
 * ("графік відключень", "аварійне відключення"), an infinitive or future form ("увімкнуть", "можуть
 * вимкнути"), after "не", in a question, or in a clause that hedges or plans ("може", "буде",
 * "заплановані", "через 15 хв").
 *
 * A watch without keywords stands for a channel that is about one line only: there every post with an
 * outage or restore statement is about that line.
 */
object PostMatcher {

    fun match(text: String, watches: List<Watch>, offWords: List<String>, onWords: List<String>): List<RuleHit> {
        val haystack = normalize(text)
        val offRaw = occurrences(haystack, offWords)
        val onRaw = occurrences(haystack, onWords)
        val off = offRaw.filter { isStatement(haystack, it) }.map { it.first }
        val on = onRaw.filter { isStatement(haystack, it) }.map { it.first }
        if (off.isEmpty() && on.isEmpty()) return emptyList()
        val mentions = (offRaw + onRaw).map { it.first }

        return watches.mapNotNull { watch ->
            val keywords = watch.keywords.map(::normalize).filter { it.isNotBlank() }
            if (keywords.isEmpty()) {
                // Nothing to measure distance from, so the first statement is the news:
                // "Світло зникло. Увімкнуть орієнтовно о 18:00" is an outage.
                val firstOff = off.minOrNull()
                val firstOn = on.minOrNull()
                val kind = if (firstOn == null || (firstOff != null && firstOff <= firstOn)) SwitchKind.OFF else SwitchKind.ON
                return@mapNotNull RuleHit(watch, kind)
            }
            // A post may report both ("Садову увімкнули, Лісову вимкнули"): the statement closest to the
            // keyword is the one that talks about it. When the keyword's own sentence speaks about power
            // but states nothing ("Садову вимкнуть о 18:00"), that mention is about a plan, and borrowing a
            // statement about another line from the next sentence would be wrong.
            var best: Pair<Int, SwitchKind>? = null
            for (keyword in occurrences(haystack, keywords)) {
                val sentence = span(haystack, keyword, SENTENCE_END)
                val local = mentions.any { it in sentence }
                for ((positions, kind) in listOf(off to SwitchKind.OFF, on to SwitchKind.ON)) {
                    for (p in positions) {
                        if (local && p !in sentence) continue
                        val d = kotlin.math.abs(p - keyword.first)
                        // Ties go to the outage, as before.
                        if (best == null || d < best.first || (d == best.first && kind == SwitchKind.OFF)) best = d to kind
                    }
                }
            }
            best?.let { RuleHit(watch, it.second) }
        }
    }

    /**
     * Lower case, without apostrophes (channels and keyboards use half a dozen different ones, so
     * "з'явилось" and "зʼявилось" both become "зявилось"), single spaces, and line breaks kept as
     * single newlines because they separate statements.
     */
    fun normalize(text: String): String = text.lowercase()
        .replace(APOSTROPHES, "")
        .replace('ё', 'е')
        .replace(SPACES, " ")
        .replace(BREAKS, "\n")
        .trim()

    private fun isStatement(h: String, match: IntRange): Boolean {
        // The word the match ends in: the stem's own word, or the last word of a phrase ("без світла").
        var wordStart = match.last
        while (wordStart > 0 && h[wordStart - 1].isLetter()) wordStart--
        var wordEnd = match.last + 1
        while (wordEnd < h.length && h[wordEnd].isLetter()) wordEnd++
        val word = h.substring(wordStart, wordEnd)
        if (NOT_A_FACT.matches(word) || word in ADVERBS) return false

        if (NEGATED.containsMatchIn(h.substring(0, match.first))) return false

        if (h.substring(span(h, match, SENTENCE_END)).trimEnd().endsWith('?')) return false
        return !HEDGE.containsMatchIn(h.substring(span(h, match, CLAUSE_END)))
    }

    /** The stretch of [h] around [match] bounded by [stops], stop included: a stop counts at a line break or before a space. */
    private fun span(h: String, match: IntRange, stops: String): IntRange {
        fun stop(i: Int) = h[i] == '\n' || (h[i] in stops && (i + 1 == h.length || h[i + 1] == ' ' || h[i + 1] == '\n'))
        var from = match.first
        while (from > 0 && !stop(from - 1)) from--
        var to = match.last + 1
        while (to < h.length && !stop(to)) to++
        return from..minOf(to, h.length - 1)
    }

    /** Every place where any of [words] occurs in [haystack]. */
    private fun occurrences(haystack: String, words: List<String>): List<IntRange> =
        words.map(::normalize).filter { it.isNotBlank() }.flatMap { word ->
            val found = ArrayList<IntRange>()
            var i = haystack.indexOf(word)
            while (i >= 0) {
                found += i until i + word.length
                i = haystack.indexOf(word, i + 1)
            }
            found
        }

    private val APOSTROPHES = Regex("['’ʼ`‘´ʹʻ′]")
    private val SPACES = Regex("[\\p{Z}\\t\\x0B\\f\\r]+")
    private val BREAKS = Regex(" ?\\n[\\n ]*")

    // Lookarounds instead of \b: word boundaries are ASCII-only on recent JDKs and Unicode-aware on Android.
    private const val BEGIN = "(?<![\\p{L}])"

    /**
     * Word endings that never report a switch that just happened: verbal nouns ("відключення",
     * "відключень", "відновленням") and infinitives, futures, present tense and imperatives
     * ("увімкнути", "увімкнуть", "відключають", "з'явиться", "увімкніть").
     */
    private val NOT_A_FACT = Regex(".*(?:нн[яіюи]|нням|ннях|ннями|нь|ти|ть|ться|тись|тися|йте)")

    /** "виключно" (only) and "включно" (inclusive) share their stem with "виключили" and "включили". */
    private val ADVERBS = setOf("виключно", "включно")

    private val NEGATED = Regex("${BEGIN}(?:не|ні) ?$")

    /** Words that make a clause a warning, a plan or a guess instead of a report. */
    private val HEDGE = Regex(
        "${BEGIN}(?:може|можуть|можлив|ймовірн|імовірн|буде|будуть|очіку|планов|запланов|прогноз|якщо|чому|обіця|через \\d)",
    )

    private const val SENTENCE_END = ".!?"
    private const val CLAUSE_END = ".!?,;:"
}
