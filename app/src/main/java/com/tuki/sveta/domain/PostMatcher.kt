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
 */
object PostMatcher {

    fun match(text: String, watches: List<Watch>, offWords: List<String>, onWords: List<String>): List<RuleHit> {
        val haystack = normalize(text)
        val off = positions(haystack, offWords)
        val on = positions(haystack, onWords)
        if (off.isEmpty() && on.isEmpty()) return emptyList()

        return watches.mapNotNull { watch ->
            val at = watch.keywords
                .map(::normalize).filter { it.isNotBlank() }
                .flatMap { occurrences(haystack, it) }
                .ifEmpty { return@mapNotNull null }
            // A post may report both ("Садову увімкнули, Лісову вимкнули"): the verb closest to the
            // keyword is the one that talks about it.
            val nearestOff = distance(at, off)
            val nearestOn = distance(at, on)
            val kind = when {
                nearestOff == null -> SwitchKind.ON
                nearestOn == null -> SwitchKind.OFF
                nearestOff <= nearestOn -> SwitchKind.OFF
                else -> SwitchKind.ON
            }
            RuleHit(watch, kind)
        }
    }

    fun normalize(text: String): String = text.lowercase()
        .replace('ʼ', '\'').replace('’', '\'').replace('`', '\'')
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun positions(haystack: String, words: List<String>): List<Int> =
        words.map(::normalize).filter { it.isNotBlank() }.flatMap { occurrences(haystack, it) }

    private fun occurrences(haystack: String, needle: String): List<Int> {
        val found = ArrayList<Int>()
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            found += i
            i = haystack.indexOf(needle, i + 1)
        }
        return found
    }

    private fun distance(a: List<Int>, b: List<Int>): Int? =
        if (b.isEmpty()) null else a.minOf { x -> b.minOf { y -> kotlin.math.abs(x - y) } }
}
