package app.filesend.android

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import app.filesend.core.Device
import app.filesend.core.DeviceInfo
import app.filesend.core.Entry
import app.filesend.core.Host
import app.filesend.core.HostConfig
import app.filesend.core.HostState
import app.filesend.core.Identity
import app.filesend.core.Messages
import app.filesend.core.Relay
import app.filesend.core.SendNames
import app.filesend.core.SendSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Elements en attente du choix de l'appareil (partage, selecteur de fichiers ou de dossier). */
data class Pending(val files: List<Uri> = emptyList(), val tree: Uri? = null) {
    val count: Int get() = files.size + (if (tree != null) 1 else 0)
    val label: String get() = Messages.items(count)
}

data class HistoryItem(
    val direction: String, // "send" | "receive" | "text"
    val mode: String,      // "wifi" | "qr" | "bluetooth"
    val peer: String,
    val summary: String,
    val detail: String,
)

/**
 * Possede l'hote (reception + envoi + decouverte + QR relais E2E + Bluetooth).
 */
class FileSendApp : Application() {
    private companion object {
        const val TAG = "FileSend"
        const val PREFS = "filesend_prefs"
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    val state = mutableStateOf(HostState())
    val pending = mutableStateOf<Pending?>(null)
    val hint = mutableStateOf<String?>(null)
    val aliasState = mutableStateOf(Build.MODEL.take(32))
    val customRelay = mutableStateOf("")
    val trusted = mutableStateOf<Set<String>>(emptySet())
    val history = mutableStateOf<List<HistoryItem>>(emptyList())
    val btReceiving = mutableStateOf(false)

    val alias: String get() = aliasState.value

    @Volatile var autoSend: String? = null
    var host: Host? = null
        private set
    private var multicastLock: WifiManager.MulticastLock? = null
    private var localQr: Relay.LocalQrServer? = null
    private var btServer: BluetoothTransfer.ReceiverServer? = null
    private var visible = 0

    override fun onCreate() {
        super.onCreate()
        aliasState.value = prefs.getString("alias", null)?.takeIf { it.isNotBlank() } ?: Build.MODEL.take(32)
        customRelay.value = prefs.getString("custom_relay", "") ?: ""
        trusted.value = prefs.getStringSet("trusted", emptySet()).orEmpty().toSet()
        loadHistory()
    }

    private fun loadHistory() {
        val raw = prefs.getString("history", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<HistoryItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += HistoryItem(
                    o.optString("d", "send"),
                    o.optString("m", "wifi"),
                    o.optString("p", ""),
                    o.optString("s", ""),
                    o.optString("dt", ""),
                )
            }
            history.value = list
        }
    }

    fun addHistory(item: HistoryItem) {
        main.post {
            val next = (listOf(item) + history.value).take(30)
            history.value = next
            val arr = JSONArray()
            for (h in next) {
                arr.put(JSONObject().apply {
                    put("d", h.direction); put("m", h.mode); put("p", h.peer)
                    put("s", h.summary); put("dt", h.detail)
                })
            }
            prefs.edit().putString("history", arr.toString()).apply()
        }
    }

    fun clearHistory() {
        history.value = emptyList()
        prefs.edit().remove("history").apply()
    }

    fun saveSettings(newAlias: String, newRelay: String) {
        val clean = newAlias.filter { !Character.isISOControl(it) }.take(32).trim()
        if (clean.isNotEmpty()) aliasState.value = clean
        customRelay.value = newRelay.trim()
        prefs.edit()
            .putString("alias", aliasState.value)
            .putString("custom_relay", customRelay.value)
            .apply()
    }

    fun toggleTrusted(fingerprint: String): Boolean {
        val cur = trusted.value.toMutableSet()
        val added = if (cur.contains(fingerprint)) { cur.remove(fingerprint); false } else { cur.add(fingerprint); true }
        trusted.value = cur
        prefs.edit().putStringSet("trusted", cur).apply()
        return added
    }

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

    fun maybeAutoSend() {
        val alias = autoSend ?: return
        val d = state.value.devices.firstOrNull { it.info.alias == alias } ?: return
        if (pending.value == null) return
        autoSend = null
        send(d)
    }

    fun buildSources(p: Pending): List<SendSource> {
        val src = UriSources(contentResolver, cacheDir)
        val roots = ArrayList<Entry>(src.files(p.files))
        p.tree?.let { roots += src.tree(it) }
        return SendNames.flatten(roots)
    }

    /** Envoie les elements en attente vers `device` (un seul geste : toucher la tuile). */
    fun send(device: Device) {
        val p = pending.value
        if (p == null || p.count == 0) {
            hint.value = "Choisissez des fichiers"
            return
        }
        val label = p.label
        val ok = host?.sendTo(device) {
            try {
                buildSources(p).also { Log.i(TAG, "envoi de ${it.size} fichier(s) vers ${device.info.alias}") }
            } catch (e: Exception) {
                Log.w(TAG, "liste d'envoi impossible", e)
                throw e
            }
        } ?: false
        if (ok) {
            pending.value = null
            addHistory(HistoryItem("send", "wifi", device.info.alias, label, "Wi-Fi"))
        } else {
            hint.value = "Envoi en cours"
        }
    }

    fun sendText(device: Device, text: String, onResult: (Boolean) -> Unit) {
        host?.sendTextTo(device, text) { r ->
            main.post {
                if (r.isSuccess) addHistory(HistoryItem("send", "wifi", device.info.alias, text.take(60), "Texte"))
                onResult(r.isSuccess)
            }
        }
    }

    fun rescan() {
        host?.rescan()
        hint.value = "Recherche sur le réseau…"
    }

    // ---------- QR Code (Hors reseau E2E, Navigateur, Wi-Fi local) ----------

    fun stopLocalQr() {
        localQr?.stop()
        localQr = null
    }

    fun shareQr(mode: String, expiry: String, onDone: (Result<Relay.QrShare>) -> Unit) {
        val p = pending.value
        if (p == null || p.count == 0) {
            onDone(Result.failure(IllegalStateException("Choisissez des fichiers d'abord")))
            return
        }
        Thread({
            val res = runCatching {
                val sources = buildSources(p)
                if (mode == "lan") {
                    stopLocalQr()
                    val srv = Relay.LocalQrServer.start(sources, cacheDir)
                    localQr = srv
                    srv.share
                } else {
                    Relay.shareViaRelay(
                        sources = sources,
                        cacheDir = cacheDir,
                        encrypted = mode != "relay-direct",
                        expiry = expiry,
                        customRelay = customRelay.value.takeIf { it.isNotBlank() },
                    )
                }
            }
            main.post {
                res.onSuccess {
                    addHistory(HistoryItem("send", "qr", if (mode == "lan") "QR Wi-Fi" else "QR Internet", it.name, Messages.formatSize(it.size)))
                }
                onDone(res)
            }
        }, "filesend-qr-share").apply { isDaemon = true }.start()
    }

    fun receiveQrUri(uri: String, onDone: (Result<List<String>>) -> Unit) {
        Thread({
            val res = runCatching {
                Relay.downloadFromUri(uri, MediaStoreSink(contentResolver), cacheDir)
            }
            main.post {
                res.onSuccess { tops ->
                    val name = tops.firstOrNull() ?: "Fichier reçu"
                    hint.value = "Reçu : $name"
                    addHistory(HistoryItem("receive", "qr", "Lien / QR", name, Messages.items(tops.size)))
                }.onFailure { e ->
                    hint.value = e.message ?: "Téléchargement QR échoué"
                }
                onDone(res)
            }
        }, "filesend-qr-dl").apply { isDaemon = true }.start()
    }

    // ---------- Bluetooth ----------

    fun toggleBtReceiver(enable: Boolean): Boolean {
        if (!enable) {
            btServer?.stop()
            btServer = null
            btReceiving.value = false
            return false
        }
        val srv = BluetoothTransfer.ReceiverServer(
            context = this,
            sink = MediaStoreSink(contentResolver),
            onStatus = { msg -> main.post { hint.value = msg } },
            onDone = { from, tops ->
                addHistory(HistoryItem("receive", "bluetooth", from, tops.firstOrNull() ?: "Fichier", Messages.items(tops.size)))
            },
        )
        val ok = runCatching { srv.start() }.getOrDefault(false)
        if (ok) {
            btServer = srv
            btReceiving.value = true
        } else {
            btReceiving.value = false
            hint.value = "Activez le Bluetooth pour recevoir"
        }
        return ok
    }

    fun sendViaBluetooth(device: BluetoothTransfer.PairedDevice, onDone: (Result<Unit>) -> Unit) {
        val p = pending.value
        if (p == null || p.count == 0) {
            onDone(Result.failure(IllegalStateException("Choisissez des fichiers d'abord")))
            return
        }
        val label = p.label
        pending.value = null
        hint.value = "Bluetooth → ${device.name}…"
        Thread({
            val res = runCatching {
                val sources = buildSources(p)
                BluetoothTransfer.send(device.device, sources, alias, { done, total ->
                    val pct = Messages.percent(done, total)
                    main.post { hint.value = "Bluetooth → ${device.name} ($pct %)" }
                })
            }
            main.post {
                res.onSuccess {
                    hint.value = "Envoyé en Bluetooth à ${device.name}"
                    addHistory(HistoryItem("send", "bluetooth", device.name, label, "Bluetooth"))
                }.onFailure { e ->
                    hint.value = e.message ?: "Échec Bluetooth"
                }
                onDone(res)
            }
        }, "filesend-bt-tx").apply { isDaemon = true }.start()
    }

    private fun create(): Host {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("filesend").apply { setReferenceCounted(false) }
        val identity = Identity.loadOrCreate(File(filesDir, "identity"))
        val info = DeviceInfo(alias, identity.fingerprint, "mobile")
        return Host(
            HostConfig(
                info = info,
                identity = identity,
                sink = MediaStoreSink(contentResolver),
                isTrusted = { fp -> trusted.value.contains(fp) },
                log = { m, e -> Log.w(TAG, m, e) },
            ),
            AndroidNotifier(this),
        ) { s ->
            state.value = s
            maybeAutoSend()
            if (!s.running) synchronized(this) { if (visible <= 0) multicastLock?.takeIf { it.isHeld }?.release() }
        }
    }
}
