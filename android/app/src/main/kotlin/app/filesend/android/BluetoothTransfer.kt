package app.filesend.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.filesend.core.ReceiveSink
import app.filesend.core.SendSource
import app.filesend.core.sanitizeRelativePath
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transfert Bluetooth RFCOMM (protocole FSBT v1 partage avec windows/core/src/bluetooth.rs)
 * + repli Bluetooth systeme Android (OBEX) pour les appareils sans FileSend.
 */
object BluetoothTransfer {
    val SERVICE_UUID: UUID = UUID.fromString("9f4b2c10-7e3a-4d1b-8c5f-2a6e9d0b1f34")
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte(), 1)
    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    data class PairedDevice(val name: String, val address: String, val device: BluetoothDevice)

    @SuppressLint("MissingPermission")
    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    fun listPaired(context: Context): List<PairedDevice> {
        val a = adapter(context) ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return a.bondedDevices.orEmpty()
            .map { PairedDevice(it.name?.takeIf { n -> n.isNotBlank() } ?: it.address, it.address, it) }
            .sortedBy { it.name.lowercase() }
    }

    /** Envoie des fichiers via Bluetooth RFCOMM (FSBT v1) vers un appareil FileSend. */
    @SuppressLint("MissingPermission")
    fun send(
        device: BluetoothDevice,
        sources: List<SendSource>,
        myAlias: String,
        onProgress: (done: Long, total: Long) -> Unit,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ) {
        if (sources.isEmpty()) throw IOException("Aucun fichier")
        val total = sources.sumOf { it.size }
        val filesJson = JSONArray()
        for (s in sources) {
            val sha = if (s.size <= 32L * 1024 * 1024) {
                val md = MessageDigest.getInstance("SHA-256")
                s.open().use { ins ->
                    val b = ByteArray(32 * 1024)
                    while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) }
                }
                toHex(md.digest())
            } else null
            filesJson.put(JSONObject().apply {
                put("relativePath", s.relativePath)
                put("size", s.size)
                if (sha != null) put("sha256", sha)
            })
        }
        val hdrBytes = JSONObject().apply {
            put("alias", myAlias)
            put("files", filesJson)
        }.toString().toByteArray(Charsets.UTF_8)

        val sock: BluetoothSocket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        sock.use { s ->
            s.connect()
            val out = DataOutputStream(BufferedOutputStream(s.outputStream, 16 * 1024))
            val ins = DataInputStream(s.inputStream)

            out.write(MAGIC)
            out.writeInt(hdrBytes.size)
            out.write(hdrBytes)
            out.flush()

            val decision = ins.read()
            if (decision != 0x01) throw IOException("Refusé par le destinataire")

            var done = 0L
            val buf = ByteArray(16 * 1024)
            for (src in sources) {
                src.open().use { fis ->
                    var rem = src.size
                    while (rem > 0) {
                        if (cancel.get()) throw IOException("Annulé")
                        val n = fis.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
                        if (n < 0) throw IOException("Lecture interrompue")
                        out.write(buf, 0, n)
                        rem -= n
                        done += n
                        onProgress(done, total)
                    }
                    out.flush()
                }
                val ack = ins.read()
                if (ack != 0x06) throw IOException("Erreur d'intégrité côté destinataire")
            }
        }
    }

    /** Serveur de reception Bluetooth RFCOMM (FSBT v1). */
    class ReceiverServer(
        private val context: Context,
        private val sink: ReceiveSink,
        private val onStatus: (String) -> Unit,
        private val onDone: (fromAlias: String, tops: List<String>) -> Unit,
    ) {
        private var serverSocket: BluetoothServerSocket? = null
        private val running = AtomicBoolean(false)

        val isRunning: Boolean get() = running.get()

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            if (running.get()) return true
            val a = adapter(context) ?: return false
            if (!a.isEnabled) return false
            val ss = a.listenUsingRfcommWithServiceRecord("FileSend", SERVICE_UUID)
            serverSocket = ss
            running.set(true)
            Thread({
                while (running.get()) {
                    val client = try { ss.accept() } catch (_: IOException) { break }
                    handleClient(client)
                }
                running.set(false)
            }, "filesend-bt-rx").apply { isDaemon = true }.start()
            return true
        }

        fun stop() {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
        }

        private fun handleClient(sock: BluetoothSocket) {
            sock.use { s ->
                try {
                    val ins = DataInputStream(s.inputStream)
                    val out = s.outputStream
                    val magic = ByteArray(5)
                    ins.readFully(magic)
                    if (!magic.contentEquals(MAGIC)) return
                    val hlen = ins.readInt()
                    if (hlen <= 0 || hlen > 2 * 1024 * 1024) return
                    val hbuf = ByteArray(hlen)
                    ins.readFully(hbuf)
                    val hdr = JSONObject(String(hbuf, Charsets.UTF_8))
                    val fromAlias = hdr.optString("alias", "Bluetooth")
                    val files = hdr.getJSONArray("files")
                    if (files.length() == 0) {
                        out.write(0x00); out.flush(); return
                    }
                    // Accepte directement car l'utilisateur a explicitement active le mode reception BT
                    out.write(0x01); out.flush()
                    onStatus("Réception Bluetooth de $fromAlias…")

                    val topMap = LinkedHashMap<String, String>()
                    val buf = ByteArray(32 * 1024)
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val rel = f.getString("relativePath")
                        val size = f.getLong("size")
                        val expSha = f.optString("sha256", "").takeIf { it.isNotBlank() }
                        val comps = sanitizeRelativePath(rel)
                        val top = topMap.getOrPut(comps[0]) { sink.uniqueTopName(comps[0]) }
                        val partial = sink.create(listOf(top) + comps.drop(1))
                        val md = MessageDigest.getInstance("SHA-256")
                        try {
                            BufferedOutputStream(partial.out, 64 * 1024).use { fout ->
                                var rem = size
                                while (rem > 0) {
                                    val n = ins.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
                                    if (n < 0) throw IOException("Flux Bluetooth coupé")
                                    md.update(buf, 0, n)
                                    fout.write(buf, 0, n)
                                    rem -= n
                                }
                                fout.flush()
                            }
                            if (expSha != null && !expSha.equals(toHex(md.digest()), ignoreCase = true)) {
                                throw IOException("SHA-256 invalide")
                            }
                            partial.commit()
                            out.write(0x06); out.flush()
                        } catch (e: Exception) {
                            partial.abort()
                            runCatching { out.write(0x15); out.flush() }
                            throw e
                        }
                    }
                    val tops = topMap.values.toList()
                    onStatus("Reçu en Bluetooth de $fromAlias")
                    onDone(fromAlias, tops)
                } catch (e: Exception) {
                    onStatus("Bluetooth interrompu : ${e.message ?: "erreur"}")
                }
            }
        }
    }

    /** Partage via le Bluetooth systeme Android (OBEX) pour les appareils sans FileSend. */
    fun shareViaSystemBluetooth(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Repli vers le selecteur systeme si le paquet Bluetooth differe selon le constructeur
            intent.setPackage(null)
            context.startActivity(Intent.createChooser(intent, "Envoyer via Bluetooth").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
