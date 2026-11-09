package com.tuki.sveta.service

import com.tuki.sveta.data.dtek.ScheduleChange
import com.tuki.sveta.data.ecoflow.GridTransition
import com.tuki.sveta.domain.Overview
import com.tuki.sveta.domain.Prediction
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.domain.Texts
import java.time.Duration
import java.time.Instant

data class EventText(val title: String, val text: String)

/** Wording of the one-shot notifications. */
object EventTexts {

    /** @param previousSince when the state that just ended had begun, if known */
    fun power(transition: GridTransition, overview: Overview, previousSince: Instant?): EventText {
        val lasted = previousSince?.let { Duration.between(it, transition.at) }?.takeIf { !it.isNegative && it.toMinutes() >= 1 }
        val next = overview.nextSwitch
        return if (transition.on) {
            val volts = transition.volts?.let { " · ${Texts.volts(it)}" }.orEmpty()
            val parts = listOfNotNull(
                lasted?.let { "Не було ${Texts.duration(it)}." },
                when {
                    overview.expectation?.offSchedule == true -> "За графіком зараз відключення — можуть вимкнути знову."
                    next != null -> "Наступне відключення ${Texts.at(next, transition.at)}."
                    else -> "Відключень за графіком більше не видно."
                },
            )
            EventText("Світло з'явилось$volts", parts.joinToString(" "))
        } else {
            val parts = listOfNotNull(
                when {
                    next != null -> "Увімкнуть за графіком ${Texts.at(next, transition.at)}."
                    else -> "Поза графіком — коли увімкнуть, невідомо."
                },
                overview.batteryPercent?.let { percent ->
                    val left = overview.dischargeRemainMinutes?.takeIf { it > 0 }?.let { ", вистачить на ${Texts.minutes(it)}" }.orEmpty()
                    "Батарея $percent %$left."
                },
            )
            EventText(if (transition.assumed) "Схоже, світло зникло" else "Світло зникло", parts.joinToString(" "))
        }
    }

    fun prediction(p: Prediction): EventText {
        val basis = if (p.window.learned) {
            "За ${p.window.samples} минулими разами наша лінія йде слідом за ${Texts.duration(p.window.min)}–${Texts.duration(p.window.max)}."
        } else {
            "Зазвичай наша лінія йде слідом за ${p.window.min.toMinutes()}–${p.window.max.toMinutes()} хв."
        }
        return when (p.kind) {
            SwitchKind.OFF -> EventText(
                "Скоро можуть вимкнути: ${Texts.range(p.earliest, p.latest)}",
                "«${p.trigger.watchName}» без світла з ${Texts.clock(p.trigger.at)} (@${p.trigger.channel}). $basis",
            )
            SwitchKind.ON -> EventText(
                "Скоро можуть увімкнути: ${Texts.range(p.earliest, p.latest)}",
                "«${p.trigger.watchName}» уже зі світлом з ${Texts.clock(p.trigger.at)} (@${p.trigger.channel}). $basis",
            )
        }
    }

    fun schedule(change: ScheduleChange, now: Instant): EventText {
        val days = (change.changed + change.added).sortedBy { it.date }
        val title = if (change.changed.isNotEmpty()) "Графік відключень змінено" else "З'явився графік на ${Texts.dayName(change.added.first(), now).lowercase()}"
        return EventText(title, days.joinToString("\n") { "${Texts.dayName(it, now)}: ${Texts.outages(it)}" })
    }
}
