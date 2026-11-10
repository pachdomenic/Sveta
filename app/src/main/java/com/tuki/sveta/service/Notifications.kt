package com.tuki.sveta.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.tuki.sveta.MainActivity
import com.tuki.sveta.R
import com.tuki.sveta.domain.Texts
import java.time.Instant
import kotlin.math.roundToInt

/** Channels, the ongoing Live Update and one-shot event notifications. */
class Notifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                // DEFAULT, not LOW: promoted Live Updates need a channel that is allowed to show in the status bar.
                NotificationChannel(CHANNEL_LIVE, "Поточний стан", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Постійна картка: чи є світло, вольтаж, коли наступне перемикання"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                // A foreground service cannot run without a notification; this is the least visible one allowed.
                NotificationChannel(CHANNEL_BACKGROUND, "Робота у фоні", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Тихий рядок замість живої картки, коли її сховано"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_POWER, "Світло зникло / з'явилось", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Момент, коли EcoFlow помітив зміну напруги в мережі"
                },
                NotificationChannel(CHANNEL_PREDICT, "Прогноз за сусідніми лініями", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Попередження з Telegram-каналів, що скоро вимкнуть"
                },
                NotificationChannel(CHANNEL_SCHEDULE, "Зміни графіка", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "ДТЕК опублікував, змінив або скасував графік для вашої черги"
                },
                NotificationChannel(CHANNEL_EMERGENCY, "Екстрені відключення", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "ДТЕК запровадив або скасував екстрені відключення на Київщині"
                },
                NotificationChannel(CHANNEL_PLANNED, "Вимкнення й увімкнення за графіком", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Нагадування перед вимкненням та увімкненням світла за графіком"
                },
            )
        )
    }

    val canPost: Boolean
        get() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 16+ with the user's (default-on) consent: the card is pinned and gets a status bar chip. */
    val liveUpdatesAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()

    /** @param hideMinutes how long the "Сховати" button will put the card away for, shown on the button */
    fun live(content: LiveContent, hideMinutes: Int): Notification {
        val accent = ContextCompat.getColor(
            context,
            when (content.tone) {
                Tone.ON -> R.color.power_on
                Tone.OFF -> R.color.power_off
                Tone.WARNING -> R.color.power_maybe
                Tone.UNKNOWN -> R.color.power_unknown
            },
        )
        val icon = when (content.tone) {
            Tone.OFF -> R.drawable.ic_stat_bolt_off
            Tone.WARNING -> R.drawable.ic_stat_warning
            else -> R.drawable.ic_stat_bolt
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(icon)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.detail)
            .setColor(accent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .setRequestPromotedOngoing(true)
            .addAction(0, "Сховати на ${Texts.minutes(hideMinutes)}", LiveCardReceiver.intent(context, show = false))

        val countdown = content.countdownTo
        if (countdown != null) {
            // A future "when" makes the system render a live countdown in the header, and in the chip too
            // unless there is short critical text, which the chip prefers.
            builder.setWhen(countdown.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        content.chip?.let(builder::setShortCriticalText)

        val progress = content.progress
        if (progress != null) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX).setColor(accent)))
                    .setProgress((progress * PROGRESS_MAX).roundToInt())
                    .setProgressTrackerIcon(IconCompat.createWithResource(context, icon))
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
        }
        return builder.build()
    }

    fun updateLive(content: LiveContent, hideMinutes: Int) {
        if (canPost) notifySafely(ID_LIVE, live(content, hideMinutes))
    }

    /**
     * What the service shows while the live card is hidden: silent, collapsed, off the lock screen and
     * not pinned, so on Android 13+ it can simply be swiped away.
     *
     * @param until when the card comes back on its own, null when it was switched off for good
     */
    fun background(until: Instant?): Notification = NotificationCompat.Builder(context, CHANNEL_BACKGROUND)
        .setSmallIcon(R.drawable.ic_stat_bolt)
        .setContentTitle("Свєта стежить за світлом")
        .setContentText(
            if (until != null) "Жива картка повернеться ${Texts.at(until, Instant.now())}, сповіщення надходять як завжди"
            else "Живу картку сховано, сповіщення надходять як завжди"
        )
        .setOngoing(false)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .addAction(0, "Показати картку", LiveCardReceiver.intent(context, show = true))
        .build()

    /** Replaces the live card with the quiet placeholder under the same id, as the service needs one or the other. */
    fun showBackground(until: Instant?) {
        if (canPost) notifySafely(ID_LIVE, background(until))
    }

    fun powerEvent(title: String, text: String, on: Boolean) = event(
        id = ID_POWER,
        channel = CHANNEL_POWER,
        icon = if (on) R.drawable.ic_stat_bolt else R.drawable.ic_stat_bolt_off,
        color = if (on) R.color.power_on else R.color.power_off,
        title = title,
        text = text,
    )

    fun prediction(title: String, text: String) = event(ID_PREDICT, CHANNEL_PREDICT, R.drawable.ic_stat_warning, R.color.power_maybe, title, text)

    fun scheduleChanged(title: String, text: String) = event(ID_SCHEDULE, CHANNEL_SCHEDULE, R.drawable.ic_stat_bolt, R.color.power_maybe, title, text)

    fun emergency(title: String, text: String, active: Boolean) = event(
        id = ID_EMERGENCY,
        channel = CHANNEL_EMERGENCY,
        icon = if (active) R.drawable.ic_stat_warning else R.drawable.ic_stat_bolt,
        color = if (active) R.color.power_off else R.color.power_on,
        title = title,
        text = text,
    )

    fun planned(title: String, text: String, off: Boolean) = event(
        id = ID_PLANNED,
        channel = CHANNEL_PLANNED,
        icon = if (off) R.drawable.ic_stat_bolt_off else R.drawable.ic_stat_bolt,
        color = if (off) R.color.power_off else R.color.power_on,
        title = title,
        text = text,
    )

    fun cancelPrediction() = manager.cancel(ID_PREDICT)

    /** A reminder about a schedule that no longer applies would only mislead. */
    fun cancelPlanned() = manager.cancel(ID_PLANNED)

    private fun event(id: Int, channel: String, icon: Int, color: Int, title: String, text: String) {
        if (!canPost) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setColor(ContextCompat.getColor(context, color))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp())
            .build()
        notifySafely(id, notification)
    }

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_LIVE = "live"
        const val CHANNEL_BACKGROUND = "background"
        const val CHANNEL_POWER = "power"
        const val CHANNEL_PREDICT = "predict"
        const val CHANNEL_SCHEDULE = "schedule"
        const val CHANNEL_EMERGENCY = "emergency"
        const val CHANNEL_PLANNED = "planned"
        const val ID_LIVE = 1
        const val ID_POWER = 2
        const val ID_PREDICT = 3
        const val ID_SCHEDULE = 4
        const val ID_EMERGENCY = 5
        const val ID_PLANNED = 6
        private const val PROGRESS_MAX = 1000
    }
}
