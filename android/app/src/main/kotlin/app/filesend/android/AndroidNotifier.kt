package app.filesend.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.filesend.core.Notifier

/**
 * Notifications systeme : demande (Accepter/Refuser), progression, resultat.
 * Si la permission est refusee, `notify` est sans effet : l'ecran principal prend le relais (AC-F4-4).
 */
class AndroidNotifier(private val ctx: Context) : Notifier {
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var serviceStarted = false

    init {
        nm.createNotificationChannel(NotificationChannel(REQUESTS, "Demandes", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(PROGRESS, "Transferts", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(RESULTS, "Résultats", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun builder(channel: String, title: String, text: String?) =
        Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .apply { if (!text.isNullOrEmpty()) setContentText(text) }

    private fun action(id: String, accept: Boolean, label: String): Notification.Action {
        val i = Intent(ctx, ActionReceiver::class.java)
            .putExtra(ActionReceiver.EXTRA_ID, id)
            .putExtra(ActionReceiver.EXTRA_ACCEPT, accept)
        val pi = PendingIntent.getBroadcast(
            ctx, (id + accept).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    override fun request(id: String, title: String, detail: String) {
        val n = builder(REQUESTS, title, detail)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setTimeoutAfter(60_000)
            .addAction(action(id, false, "Refuser"))
            .addAction(action(id, true, "Accepter"))
            .build()
        nm.notify(id.hashCode(), n)
    }

    override fun clearRequest(id: String) = nm.cancel(id.hashCode())

    override fun progress(id: String, title: String, percent: Int?) {
        startService()
        val n = builder(PROGRESS, title, null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
        nm.notify(PROGRESS_ID, n)
    }

    override fun done(id: String, title: String, tops: List<String>) {
        endProgress()
        val b = builder(RESULTS, title, null).setAutoCancel(true)
        if (tops.isNotEmpty()) { // fichiers recus : toucher ouvre Telechargements ; envoi termine : rien a ouvrir
            b.setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0, Intent("android.intent.action.VIEW_DOWNLOADS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        nm.notify(RESULT_ID, b.build())
    }

    override fun dismiss(id: String) = endProgress()

    override fun fail(id: String, title: String) {
        Log.i("FileSend", "echec: $title")
        endProgress()
        nm.notify(RESULT_ID, builder(RESULTS, title, null).setAutoCancel(true).build())
    }

    private fun startService() {
        if (serviceStarted) return
        serviceStarted = true
        try {
            ctx.startForegroundService(Intent(ctx, TransferService::class.java))
        } catch (_: Exception) {
            // Demarrage refuse (application en arriere-plan) : le transfert continue sans service.
        }
    }

    private fun endProgress() {
        nm.cancel(PROGRESS_ID)
        if (serviceStarted) ctx.stopService(Intent(ctx, TransferService::class.java))
        serviceStarted = false
    }

    companion object {
        const val REQUESTS = "requests"
        const val PROGRESS = "progress"
        const val RESULTS = "results"
        const val PROGRESS_ID = 2
        const val RESULT_ID = 3
    }
}
