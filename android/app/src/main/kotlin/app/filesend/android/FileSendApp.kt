package app.filesend.android

import android.app.Application
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import app.filesend.core.Device
import app.filesend.core.DeviceInfo
import app.filesend.core.Entry
import app.filesend.core.Host
import app.filesend.core.HostConfig
import app.filesend.core.HostState
import app.filesend.core.Identity
import app.filesend.core.Messages
import app.filesend.core.SendNames
import java.io.File

/** Elements en attente du choix de l'appareil (partage, selecteur de fichiers ou de dossier). */
data class Pending(val files: List<Uri> = emptyList(), val tree: Uri? = null) {
    val count: Int get() = files.size + (if (tree != null) 1 else 0)
    val label: String get() = Messages.items(count)
}

/**
 * Possede l'hote (reception + envoi + decouverte). La reception n'existe que tant que
 * l'application est visible, ou jusqu'a la fin d'un transfert en cours (H3).
 */
class FileSendApp : Application() {
    private companion object { const val TAG = "FileSend" }
    val state = mutableStateOf(HostState())
    val pending = mutableStateOf<Pending?>(null)
    val hint = mutableStateOf<String?>(null)

    /** Nom de cet appareil tel qu'il est annonce. */
    val alias: String get() = Build.MODEL.take(32)

    /** Crochet de test des builds debug : alias de l'appareil a qui envoyer des que possible (adb, extra `autosend`). */
    @Volatile var autoSend: String? = null
    var host: Host? = null
        private set
    private var multicastLock: WifiManager.MulticastLock? = null
    private var visible = 0

    @Synchronized
    fun onVisible() {
        visible++
        val h = host ?: create().also { host = it }
        multicastLock?.takeIf { !it.isHeld }?.acquire()
        h.start()
    }

    @Synchronized
    fun onHidden() {
        visible--
        if (visible <= 0) host?.stopWhenIdle()
    }

    /** Build debug uniquement : envoie les elements en attente a l'appareil nomme par `autoSend`, des qu'il est liste. */
    fun maybeAutoSend() {
        val alias = autoSend ?: return
        val d = state.value.devices.firstOrNull { it.info.alias == alias } ?: return
        if (pending.value == null) return
        autoSend = null
        send(d)
    }

    /** Envoie les elements en attente vers `device` (un seul geste : toucher la tuile). */
    fun send(device: Device) {
        val p = pending.value
        if (p == null || p.count == 0) {
            hint.value = "Choisissez des fichiers"
            return
        }
        val src = UriSources(contentResolver, cacheDir)
        val ok = host?.sendTo(device) {
            try {
                val roots = ArrayList<Entry>(src.files(p.files))
                p.tree?.let { roots += src.tree(it) }
                SendNames.flatten(roots).also { Log.i(TAG, "envoi de ${it.size} fichier(s) vers ${device.info.alias}") }
            } catch (e: Exception) {
                Log.w(TAG, "liste d'envoi impossible", e)
                throw e
            }
        } ?: false
        if (ok) pending.value = null else hint.value = "Envoi en cours"
    }

    private fun create(): Host {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("filesend").apply { setReferenceCounted(false) }
        val identity = Identity.loadOrCreate(File(filesDir, "identity"))
        val info = DeviceInfo(Build.MODEL.take(32), identity.fingerprint, "mobile")
        return Host(
            HostConfig(info, identity, MediaStoreSink(contentResolver), log = { m, e -> Log.w(TAG, m, e) }),
            AndroidNotifier(this),
        ) { s ->
            state.value = s
            maybeAutoSend()
            if (!s.running) synchronized(this) { if (visible <= 0) multicastLock?.takeIf { it.isHeld }?.release() }
        }
    }
}
