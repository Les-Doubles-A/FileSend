package app.filesend.android

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boutons Accepter / Refuser de la notification de demande. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        (context.applicationContext as FileSendApp).host?.answer(id, accept)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ACCEPT = "accept"
    }
}
