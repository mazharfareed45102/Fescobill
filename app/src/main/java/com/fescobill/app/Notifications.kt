package com.fescobill.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CHANNEL = "bills"

    fun billReady(context: Context, q: BillQuery, month: String) {
        val title = context.getString(R.string.notif_ready_title)
        val what = if (month.isNotBlank()) "$month • ${q.display}" else q.display
        post(context, q, title, context.getString(R.string.notif_ready_text, what))
    }

    fun notFound(context: Context, q: BillQuery, message: String) {
        val text = q.display + if (message.isNotBlank()) " — $message" else ""
        post(context, q, context.getString(R.string.notif_notfound_title), text)
    }

    fun needsCode(context: Context, q: BillQuery) {
        post(context, q, context.getString(R.string.notif_code_title), context.getString(R.string.notif_code_text, q.display))
    }

    fun gaveUp(context: Context, q: BillQuery) {
        post(context, q, context.getString(R.string.notif_gaveup_title), context.getString(R.string.notif_gaveup_text))
    }

    private fun post(context: Context, q: BillQuery, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = BillActivity.intent(context, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getActivity(context, q.digits.hashCode(), open, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bill)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(q.digits.hashCode(), notification)
        } catch (e: SecurityException) {
            // notifications not allowed; the bill is still saved and shows when the app is opened
        }
    }
}
