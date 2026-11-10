package app.filesend.android

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Service de premier plan pendant un transfert : evite l'arret du processus ecran eteint. */
class TransferService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, AndroidNotifier.PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Transfert en cours")
            .setOngoing(true)
            .build()
        startForeground(AndroidNotifier.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
