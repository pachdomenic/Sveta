package com.tuki.sveta.service

import com.tuki.sveta.domain.GridState
import com.tuki.sveta.domain.Overview
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.domain.Texts
import java.time.Duration
import java.time.Instant

enum class Tone { ON, OFF, WARNING, UNKNOWN }

/** What the ongoing notification shows; kept free of Android types so it can be unit tested. */
data class LiveContent(
    val title: String,
    val text: String,
    val detail: String?,
    /** Status bar chip text; keep under 7 characters or the system drops it. */
    val chip: String?,
    /** When set, the chip counts down to this moment instead of showing [chip]. */
    val countdownTo: Instant?,
    val progress: Float?,
    val tone: Tone,
)

object LiveContentBuilder {

    fun build(o: Overview): LiveContent {
        val prediction = o.activePrediction
        return when {
            prediction != null && prediction.kind == SwitchKind.OFF -> warning(o)
            // Nothing measured and the schedule is suspended: there is honestly nothing to count down to.
            o.emergency != null && !o.measured -> LiveContent(
                title = "Екстрені відключення",
                text = "Графік не діє — світло можуть вимкнути й увімкнути будь-коли",
                detail = battery(o, charging = false),
                chip = null, countdownTo = null, progress = null, tone = Tone.WARNING,
            )
            o.effectiveOn == true -> on(o)
            o.effectiveOn == false -> off(o)
            else -> LiveContent(
                title = "Немає даних",
                text = "Графік ще не завантажено, станція не відповідає",
                detail = null, chip = null, countdownTo = null, progress = null, tone = Tone.UNKNOWN,
            )
        }
    }

    private fun on(o: Overview): LiveContent {
        val next = o.nextSwitch
        val title = when {
            !o.measured -> "За графіком світло є"
            o.volts != null -> "Світло є · ${Texts.volts(o.volts)}"
            else -> "Світло є"
        }
        val text = when {
            o.emergency != null -> "Екстрені відключення: графік не діє, можуть вимкнути будь-якої миті"
            o.expectation?.offSchedule == true -> "За графіком уже відключення — можуть вимкнути будь-якої миті"
            next != null -> "Вимкнуть ${Texts.at(next, o.now)} · через ${Texts.duration(Duration.between(o.now, next))}"
            else -> "Відключень за графіком не видно"
        }
        return LiveContent(
            title = title,
            text = text,
            detail = battery(o, charging = true),
            chip = o.volts?.takeIf { o.measured }?.let(Texts::volts),
            countdownTo = null,
            progress = o.progress.takeIf { o.emergency == null },
            tone = if (o.measured) Tone.ON else Tone.UNKNOWN,
        )
    }

    private fun off(o: Overview): LiveContent {
        val next = o.nextSwitch
        val restoreHint = o.activePrediction?.takeIf { it.kind == SwitchKind.ON }
        val title = when {
            !o.measured -> "За графіком світла немає"
            o.assumed -> "Схоже, світла немає"
            else -> "Світла немає"
        }
        val text = when {
            restoreHint != null -> "«${restoreHint.trigger.watchName}» уже зі світлом — чекай ${Texts.range(restoreHint.earliest, restoreHint.latest)}"
            o.emergency != null -> "Екстрені відключення: графік не діє, коли увімкнуть — невідомо"
            next != null -> "Увімкнуть ${Texts.at(next, o.now)} · через ${Texts.duration(Duration.between(o.now, next))}"
            else -> "Поза графіком — коли увімкнуть, невідомо"
        }
        return LiveContent(
            title = title,
            text = text,
            detail = battery(o, charging = false),
            chip = "Нема",
            countdownTo = restoreHint?.latest ?: next.takeIf { o.emergency == null },
            progress = o.progress.takeIf { o.emergency == null },
            tone = Tone.OFF,
        )
    }

    private fun warning(o: Overview): LiveContent {
        val p = o.activePrediction!!
        val late = o.now.isAfter(p.earliest)
        val volts = o.volts?.takeIf { o.grid == GridState.ON }?.let { " · ${Texts.volts(it)}" }.orEmpty()
        val basis = if (p.window.learned) {
            "за ${p.window.samples} минулими разами"
        } else {
            "зазвичай ${p.window.min.toMinutes()}–${p.window.max.toMinutes()} хв після сусідів"
        }
        return LiveContent(
            title = "Скоро можуть вимкнути$volts",
            text = "«${p.trigger.watchName}» без світла з ${Texts.clock(p.trigger.at)}. Наша черга: ${Texts.range(p.earliest, p.latest)}",
            detail = listOfNotNull("@${p.trigger.channel}, $basis", battery(o, charging = true)).joinToString(" · "),
            chip = null,
            countdownTo = if (late) p.latest else p.earliest,
            progress = run {
                val total = Duration.between(p.trigger.at, p.latest).toMillis().toFloat()
                if (total <= 0) null else (Duration.between(p.trigger.at, o.now).toMillis() / total).coerceIn(0f, 1f)
            },
            tone = Tone.WARNING,
        )
    }

    private fun battery(o: Overview, charging: Boolean): String? {
        val percent = o.batteryPercent ?: return null
        val tail = when {
            !charging && (o.dischargeRemainMinutes ?: 0) > 0 -> " · вистачить на ${Texts.minutes(o.dischargeRemainMinutes!!)}"
            charging && percent < 100 && (o.chargeRemainMinutes ?: 0) > 0 && (o.inputWatts ?: 0) > 0 ->
                " · до повного ${Texts.minutes(o.chargeRemainMinutes!!)}"
            else -> ""
        }
        return "Батарея $percent %$tail"
    }
}
