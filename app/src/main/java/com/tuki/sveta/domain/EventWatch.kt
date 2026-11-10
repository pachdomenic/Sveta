package com.tuki.sveta.domain

/**
 * Notices the moments when "something happened" between two looks at the [Overview]: the light went
 * off or came back (measured, or by the schedule when there is no station), emergency outages began or
 * ended, or a new warning about the neighbours appeared.
 *
 * Stateful: feed it every overview in order. The first one only sets the baseline.
 */
class EventWatch {

    private var on: Boolean? = null
    private var emergency: Boolean? = null
    private var warning: String? = null
    private var primed = false

    /** @return true when [overview] differs from the previous one in a way worth calling an event */
    fun happened(overview: Overview): Boolean {
        var event = false

        // The state can be unknown for a while (schedule not loaded, station silent); what counts is
        // the last known state against the new one, so "on, unknown, off" is still the light going off.
        val nowOn = overview.effectiveOn
        if (nowOn != null) {
            if (on != null && on != nowOn) event = true
            on = nowOn
        }

        val nowEmergency = overview.emergency != null
        if (emergency != null && emergency != nowEmergency) event = true
        emergency = nowEmergency

        // A warning appearing is an event; one quietly expiring is not.
        val nowWarning = overview.activePrediction?.let { "${it.trigger.key}@${it.trigger.at.epochSecond}" }
        if (primed && nowWarning != null && nowWarning != warning) event = true
        warning = nowWarning

        primed = true
        return event
    }
}
