package com.tuki.sveta.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tuki.sveta.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Сховати" / "Показати картку" buttons on the service notification. Hiding is for the time chosen
 * in settings; showing undoes any kind of hiding. It only changes the settings: the service watches
 * them and swaps the notification itself.
 */
class LiveCardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val show = when (intent.action) {
            ACTION_SHOW -> true
            ACTION_HIDE -> false
            else -> return
        }
        val app = context.container
        val pending = goAsync()
        app.scope.launch {
            try {
                withTimeoutOrNull(5_000) { app.settings.update { if (show) it.liveCardShown() else it.liveCardHidden(System.currentTimeMillis()) } }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SHOW = "com.tuki.sveta.action.LIVE_CARD_SHOW"
        private const val ACTION_HIDE = "com.tuki.sveta.action.LIVE_CARD_HIDE"

        fun intent(context: Context, show: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context,
            if (show) 1 else 2,
            Intent(context, LiveCardReceiver::class.java).setAction(if (show) ACTION_SHOW else ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
