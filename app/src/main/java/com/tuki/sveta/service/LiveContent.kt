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

    /** @param watts also show the battery's charge or discharge power. */
    fun build(o: Overview, watts: Boolean = false): LiveContent {
        val prediction = o.activePrediction
        return when {
            prediction != null && prediction.kind == SwitchKind.OFF -> warning(o, watts)
            o.effectiveOn == true -> on(o, watts)
            o.effectiveOn == false -> off(o, watts)
            else -> LiveContent(
                title = "Немає даних",
                text = "Графік ще не завантажено, станція не відповідає",
                detail = null, chip = null, countdownTo = null, progress = null, tone = Tone.UNKNOWN,
            )
        }
    }

    private fun on(o: Overview, watts: Boolean): LiveContent {
        val next = o.nextSwitch
        val title = when {
            !o.measured -> "За графіком світло є"
            o.volts != null -> "Світло є · ${Texts.volts(o.volts)}"
            else -> "Світло є"
        }
        val text = when {
            o.expectation?.offSchedule == true -> "За графіком уже відключення — можуть вимкнути будь-якої миті"
            next != null -> "Вимкнуть ${Texts.at(next, o.now)} · через ${Texts.duration(Duration.between(o.now, next))}"
            else -> "Відключень за графіком не видно"
        }
        return LiveContent(
            title = title,
            text = text,
            detail = battery(o, charging = true, watts),
            chip = o.volts?.takeIf { o.measured }?.let(Texts::volts),
            countdownTo = null,
            progress = o.progress,
            tone = if (o.measured) Tone.ON else Tone.UNKNOWN,
        )
    }

    private fun off(o: Overview, watts: Boolean): LiveContent {
        val next = o.nextSwitch
        val restoreHint = o.activePrediction?.takeIf { it.kind == SwitchKind.ON }
        val title = when {
            !o.measured -> "За графіком світла немає"
            o.assumed -> "Схоже, світла немає"
            else -> "Світла немає"
        }
        val text = when {
            restoreHint != null -> "«${restoreHint.trigger.watchName}» уже зі світлом — чекай ${Texts.range(restoreHint.earliest, restoreHint.latest)}"
            next != null -> "Увімкнуть ${Texts.at(next, o.now)} · через ${Texts.duration(Duration.between(o.now, next))}"
            else -> "Поза графіком — коли увімкнуть, невідомо"
        }
        return LiveContent(
            title = title,
            text = text,
            detail = battery(o, charging = false, watts),
            chip = "Нема",
            countdownTo = restoreHint?.latest ?: next,
            progress = o.progress,
            tone = Tone.OFF,
        )
    }

    private fun warning(o: Overview, watts: Boolean): LiveContent {
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
            detail = listOfNotNull("@${p.trigger.channel}, $basis", battery(o, charging = true, watts)).joinToString(" · "),
            chip = null,
            countdownTo = if (late) p.latest else p.earliest,
            progress = run {
                val total = Duration.between(p.trigger.at, p.latest).toMillis().toFloat()
                if (total <= 0) null else (Duration.between(p.trigger.at, o.now).toMillis() / total).coerceIn(0f, 1f)
            },
            tone = Tone.WARNING,
        )
    }

    private fun battery(o: Overview, charging: Boolean, watts: Boolean): String? {
        val percent = o.batteryPercent ?: return null
        val power = if (watts) power(o, percent) else null
        val tail = when {
            !charging && (o.dischargeRemainMinutes ?: 0) > 0 -> " · вистачить на ${Texts.minutes(o.dischargeRemainMinutes!!)}"
            charging && percent < 100 && (o.chargeRemainMinutes ?: 0) > 0 && (o.inputWatts ?: 0) > 0 ->
                " · до повного ${Texts.minutes(o.chargeRemainMinutes!!)}"
            else -> ""
        }
        return "Батарея $percent %${power?.let { " · $it" }.orEmpty()}$tail"
    }

    /**
     * What flows into or out of the battery: total input minus total output, so a load running through
     * from the grid is not counted as charging. Small differences are inverter losses, not real flow.
     */
    private fun power(o: Overview, percent: Int): String? {
        val net = (o.inputWatts ?: return null) - (o.outputWatts ?: return null)
        return when {
            net >= POWER_NOISE_WATTS && percent < 100 -> "заряд ${Texts.watts(net)}"
            net <= -POWER_NOISE_WATTS -> "розряд ${Texts.watts(-net)}"
            else -> null
        }
    }

    private const val POWER_NOISE_WATTS = 10
}
