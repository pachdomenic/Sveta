package com.tuki.sveta.domain

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
enum class EmergencyKind { STARTED, CANCELLED }

/** What a channel post says about emergency outages in our region. */
data class EmergencyNotice(
    val kind: EmergencyKind,
    /** The line of the post that settled [kind], without the leading emoji. */
    val summary: String,
)

/**
 * Reads DTEK's announcements about emergency outages ("екстрені відключення"), during which the
 * published schedule does not apply.
 *
 * The posts follow a stable shape: a headline "<regions>: <what happened>", sometimes a paragraph per
 * district, and the cancellation is often appended to the same post as "UPD: …". So the post is read
 * line by line and the last line that makes a statement about our region wins.
 */
object EmergencyMatcher {

    fun match(text: String): EmergencyNotice? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val headlineOurs = OURS.containsMatchIn(PostMatcher.normalize(lines.first()))

        var found: EmergencyNotice? = null
        lines.forEachIndexed { index, original ->
            val line = PostMatcher.normalize(original)
            val region = OURS.find(line)
            val applies = if (headlineOurs) {
                // Every line is ours, unless it singles out another place ("UPD: у Києві … скасовані").
                index == 0 || region != null || !ELSEWHERE.containsMatchIn(line)
            } else {
                // In a post about something else only a section labelled with our region counts
                // ("Київщина: тривають…", "У Київській області…"); a passing mention deep in an
                // explainer is not an announcement.
                region != null && line.substring(0, region.range.first).count { it.isLetter() } <= 2
            }
            if (!applies) return@forEachIndexed
            val kind = statement(line) ?: return@forEachIndexed
            found = EmergencyNotice(kind, original.trimStart { !it.isLetterOrDigit() })
        }
        return found
    }

    /** What a single line states, if anything. With several verbs in one line the last one is the news. */
    private fun statement(line: String): EmergencyKind? {
        val aboutEmergency = EMERGENCY.containsMatchIn(line)
        val cancel = (if (aboutEmergency) CANCEL else BACK_TO_SCHEDULE).findAll(line).lastOrNull()
        val start = if (aboutEmergency) START.findAll(line).lastOrNull() else null
        return when {
            cancel == null && start == null -> null
            cancel == null -> EmergencyKind.STARTED
            // "поки не скасовані" means they are still on.
            line.substring(0, cancel.range.first).trimEnd().let { it == "не" || it.endsWith(" не") } -> EmergencyKind.STARTED
            start == null || cancel.range.first > start.range.first -> EmergencyKind.CANCELLED
            else -> EmergencyKind.STARTED
        }
    }

    // Lookarounds instead of \b: word boundaries are ASCII-only on recent JDKs and Unicode-aware on Android.
    private const val END = "(?![\\p{L}])"
    private const val BEGIN = "(?<![\\p{L}])"

    /** Kyiv oblast, as opposed to the city: "Київщина", "Київська область", "у Київській області". */
    private val OURS = Regex("київщин|київськ(?:а|ій|ої|у)$END")

    /** Other places DTEK serves; the city of Kyiv has its own grid company and its own announcements. */
    private val ELSEWHERE = Regex("$BEGIN(?:київ|києві|києва|столиц|одес|одещин|дніпр|донеч|донецьк|кривий|кривому|криворі)")

    private val EMERGENCY = Regex("екстрен|аварійн\\p{L}* відключ")
    private val START = Regex("запроваджен|запровадили|застосован|застосовуються|застосували|тривають|триває|продовжуються|введен|діють (?:екстрен|аварійн)")
    private const val BACK = "поверта\\p{L}+ до графік|переходимо до графік"
    private val BACK_TO_SCHEDULE = Regex(BACK)
    private val CANCEL = Regex("скасован|скасували|відмінен|відмінили|$BACK")
}

/** Last known word on emergency outages. Times are epoch milliseconds. */
@Serializable
data class EmergencyStatus(
    val active: Boolean,
    /** When the current state began. */
    val since: Long,
    /** Last time a post confirmed the current state. */
    val confirmedAt: Long,
    val summary: String,
    val channel: String = "",
    val postId: Long = 0,
)

/** A channel post reduced to what the tracker needs. */
data class Bulletin(val id: Long, val at: Instant, val text: String)

/**
 * Folds the latest posts of the announcement channel into an [EmergencyStatus].
 *
 * Posts are edited in place (the "UPD: скасовано" habit), so every post is re-read on each pass and
 * remembered together with the verdict it produced; a changed verdict counts as news that happened now.
 */
object EmergencyTracker {

    /** Older news is recorded but not announced: nobody needs a push about this morning in the evening. */
    val FRESH: Duration = Duration.ofHours(3)

    /**
     * Cancellations are sometimes never posted. DTEK repeats "тривають" about once a day while an
     * emergency lasts, so one that nobody confirmed for this long is treated as over.
     */
    val STALE: Duration = Duration.ofHours(36)

    private const val REMEMBERED_POSTS = 60

    data class Step(
        val status: EmergencyStatus?,
        val seen: Map<Long, EmergencyKind>,
        /** Set when emergency outages began or ended recently enough to tell the user. */
        val event: EmergencyStatus?,
    )

    /** The status as it should be shown at [now]: null unless emergency outages are in effect. */
    fun current(status: EmergencyStatus?, now: Instant): EmergencyStatus? = status?.takeIf {
        it.active && Duration.between(Instant.ofEpochMilli(it.confirmedAt), now) < STALE
    }

    fun step(
        status: EmergencyStatus?,
        seen: Map<Long, EmergencyKind>,
        bulletins: List<Bulletin>,
        channel: String,
        now: Instant,
    ): Step {
        val wasActive = current(status, now) != null
        val known = seen.toMutableMap()
        var latest = status
        for (post in bulletins.sortedBy { it.id }) {
            val notice = EmergencyMatcher.match(post.text) ?: continue
            val before = known[post.id]
            if (before == notice.kind) continue
            known[post.id] = notice.kind
            // A post we already handled was edited just now; a new one carries its own timestamp.
            val at = (if (before != null) now else post.at).toEpochMilli()
            if (latest?.let { at < it.confirmedAt } == true) continue
            // An emergency that went stale is over; hearing about one again is a new one.
            val current = latest?.takeUnless { it.active && at - it.confirmedAt >= STALE.toMillis() }
            val active = notice.kind == EmergencyKind.STARTED
            latest = if (current == null || current.active != active) {
                EmergencyStatus(active, since = at, confirmedAt = at, summary = notice.summary, channel = channel, postId = post.id)
            } else {
                current.copy(confirmedAt = at)
            }
        }
        val settled = latest
        val event = settled?.takeIf {
            it.active != wasActive && Duration.between(Instant.ofEpochMilli(it.since), now) <= FRESH
        }
        val remembered = known.entries.sortedByDescending { it.key }.take(REMEMBERED_POSTS).associate { it.key to it.value }
        return Step(settled, remembered, event)
    }
}
