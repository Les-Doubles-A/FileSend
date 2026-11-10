package app.filesend.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

const val MULTICAST_GROUP = "224.0.0.167"
/** Un appareil muet depuis plus longtemps que cela disparait (AC-F1-2 : moins de 15 s). */
const val DEFAULT_TTL_MS = 10_000L
private const val MAX_DEVICES = 256
private const val MAX_PACKET = 2048

enum class Via { Multicast, Scan }

data class Device(val info: DeviceInfo, val addr: InetSocketAddress, val via: Via, val lastSeenNs: Long)

sealed interface ScanTargets {
    data object Off : ScanTargets
    /** Les 254 adresses du /24 de l'interface principale. */
    data object Subnet : ScanTargets
    /** Adresses precises (tests). */
    data class Explicit(val addrs: List<InetSocketAddress>) : ScanTargets
}

class DiscoveryConfig(
    val info: DeviceInfo,
    /** Port HTTPS de ce receveur, annonce aux autres. */
    val httpsPort: Int,
    val multicast: Boolean = true,
    val group: String = MULTICAST_GROUP,
    val udpPort: Int = DEFAULT_PORT,
    /** null : interface de la route par defaut. */
    val networkInterface: NetworkInterface? = null,
    val announceMs: Long = 3_000,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val scan: ScanTargets = ScanTargets.Subnet,
    val scanInitialDelayMs: Long = 1_500,
    val scanIntervalMs: Long = 10_000,
    val keepaliveMs: Long = 4_000,
)

@Serializable
private data class Announce(val info: DeviceInfo, val port: Int, val announce: Boolean)

/** Adresse locale utilisee pour sortir du reseau (aucun paquet n'est envoye). */
fun primaryAddress(): Inet4Address? = try {
    DatagramSocket().use {
        it.connect(InetAddress.getByName("192.0.2.1"), 9)
        it.localAddress as? Inet4Address
    }?.takeUnless { it.isAnyLocalAddress }
} catch (_: IOException) {
    null
}

/** Interface portant l'adresse de sortie (Wi-Fi sur un telephone), sinon la premiere interface multicast active. */
fun pickInterface(): NetworkInterface? {
    primaryAddress()?.let { a -> runCatching { NetworkInterface.getByInetAddress(a) }.getOrNull()?.let { return it } }
    return NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull {
        it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address }
    }
}

fun clean(alias: String): String = alias.filter { !Character.isISOControl(it) }.take(32).trim()

/**
 * Decouverte des appareils proches (F1) : annonces multicast UDP, repli par sondage du
 * sous-reseau, expiration. Meme comportement que windows/core/src/discovery.rs.
 */
class Discovery(private val cfg: DiscoveryConfig, private val onChange: (List<Device>) -> Unit = {}) {
    private val lock = Any()
    private val map = LinkedHashMap<String, Device>()
    private var visible: List<Device> = emptyList()
    private val pool: ScheduledExecutorService = Executors.newScheduledThreadPool(4) { r ->
        Thread(r, "filesend-discovery").apply { isDaemon = true }
    }
    private var socket: MulticastSocket? = null
    @Volatile private var stopped = false

    /** Message de l'erreur multicast eventuelle (le repli par sondage reste actif). */
    @Volatile var multicastError: String? = null
        private set

    fun devices(): List<Device> = synchronized(lock) { visible }

    fun start() {
        if (cfg.multicast) {
            try {
                openMulticast()
            } catch (e: Exception) {
                multicastError = e.message ?: e.javaClass.simpleName
            }
        }
        pool.scheduleWithFixedDelay({ reap() }, 500, 500, TimeUnit.MILLISECONDS)
        if (cfg.scan !is ScanTargets.Off) {
            pool.schedule({ scanLoop() }, cfg.scanInitialDelayMs, TimeUnit.MILLISECONDS)
            pool.scheduleWithFixedDelay({ keepalive() }, cfg.keepaliveMs, cfg.keepaliveMs, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    // ---------- multicast ----------

    private fun openMulticast() {
        val group = InetAddress.getByName(cfg.group)
        val iface = cfg.networkInterface ?: pickInterface()
        val s = MulticastSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(cfg.udpPort))
        if (iface != null) {
            s.networkInterface = iface
            s.joinGroup(InetSocketAddress(group, 0), iface)
        } else {
            @Suppress("DEPRECATION")
            s.joinGroup(group)
        }
        s.timeToLive = 1
        socket = s
        val packet = ProtocolJson.encodeToString(Announce(cfg.info, cfg.httpsPort, true)).toByteArray()
        val dest = InetSocketAddress(group, cfg.udpPort)
        pool.scheduleWithFixedDelay({
            runCatching { s.send(DatagramPacket(packet, packet.size, dest)) }
        }, 0, cfg.announceMs, TimeUnit.MILLISECONDS)
        Thread({
            val buf = ByteArray(MAX_PACKET + 1)
            while (!stopped && !s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: IOException) {
                    if (stopped || s.isClosed) break
                    continue
                }
                if (p.length > MAX_PACKET) continue
                val a = try {
                    ProtocolJson.decodeFromString<Announce>(String(p.data, 0, p.length, Charsets.UTF_8))
                } catch (_: Exception) {
                    continue
                }
                if (!a.announce || a.port <= 0 || a.port > 65535) continue
                // L'adresse vient du paquet recu, jamais du contenu annonce.
                upsert(a.info, InetSocketAddress(p.address, a.port), Via.Multicast)
            }
        }, "filesend-discovery-rx").apply { isDaemon = true }.start()
    }

    // ---------- liste ----------

    private fun publish() {
        val snapshot = synchronized(lock) {
            val v = map.values.sortedWith(compareBy({ it.info.alias }, { it.addr.toString() }))
            val same = v.size == visible.size && v.zip(visible).all { (a, b) -> a.info == b.info && a.addr == b.addr }
            visible = v
            if (same) null else v
        }
        if (snapshot != null) runCatching { onChange(snapshot) }
    }

    private fun upsert(raw: DeviceInfo, addr: InetSocketAddress, via: Via) {
        if (raw.fingerprint == cfg.info.fingerprint) return // soi-meme
        val info = raw.copy(alias = clean(raw.alias))
        if (info.alias.isEmpty()) return
        synchronized(lock) {
            if (!map.containsKey(info.fingerprint) && map.size >= MAX_DEVICES) return
            val keep = if (map[info.fingerprint]?.via == Via.Multicast) Via.Multicast else via
            map[info.fingerprint] = Device(info, addr, keep, System.nanoTime())
        }
        publish()
    }

    private fun reap() {
        val removed = synchronized(lock) {
            map.values.removeAll { (System.nanoTime() - it.lastSeenNs) / 1_000_000 > cfg.ttlMs }
        }
        if (removed) publish()
    }

    // ---------- repli : sondage ----------

    private fun probe(addr: InetSocketAddress): DeviceInfo? = try {
        val c = URL("https://${addr.hostString}:${addr.port}$API_PREFIX/info").openConnection() as HttpsURLConnection
        try {
            c.sslSocketFactory = trustAllSslContext().socketFactory
            c.setHostnameVerifier { _, _ -> true }
            c.connectTimeout = 800
            c.readTimeout = 2_000
            if (c.responseCode == 200) ProtocolJson.decodeFromString<DeviceInfo>(String(c.inputStream.readBytes(), Charsets.UTF_8)) else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun scanLoop() {
        while (!stopped) {
            // Repli : on ne sonde que si aucun appareil n'est connu.
            if (synchronized(lock) { map.isEmpty() }) {
                val targets = when (val t = cfg.scan) {
                    is ScanTargets.Explicit -> t.addrs
                    ScanTargets.Subnet -> subnetCandidates(cfg.httpsPort)
                    ScanTargets.Off -> emptyList()
                }
                val workers = Executors.newFixedThreadPool(32) { r -> Thread(r).apply { isDaemon = true } }
                try {
                    targets.map { a -> workers.submit<Pair<DeviceInfo, InetSocketAddress>?> { probe(a)?.let { it to a } } }
                        .forEach { f -> runCatching { f.get(5, TimeUnit.SECONDS) }.getOrNull()?.let { upsert(it.first, it.second, Via.Scan) } }
                } finally {
                    workers.shutdownNow()
                }
            }
            try {
                Thread.sleep(cfg.scanIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Adresses du /24 de l'interface principale dont le port est ouvert. */
    private fun subnetCandidates(port: Int): List<InetSocketAddress> {
        val me = primaryAddress()?.address ?: return emptyList()
        val hosts = (1..254).filter { it != (me[3].toInt() and 0xFF) }
            .map { InetSocketAddress(InetAddress.getByAddress(byteArrayOf(me[0], me[1], me[2], it.toByte())), port) }
        val workers = Executors.newFixedThreadPool(64) { r -> Thread(r).apply { isDaemon = true } }
        try {
            return hosts.map { a ->
                workers.submit<InetSocketAddress?> {
                    try {
                        Socket().use { it.connect(a, 300) }
                        a
                    } catch (_: IOException) {
                        null
                    }
                }
            }.mapNotNull { f -> runCatching { f.get(5, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            workers.shutdownNow()
        }
    }

    /** Les appareils trouves par sondage n'annoncent rien : on les reverifie. */
    private fun keepalive() {
        val targets = synchronized(lock) { map.values.filter { it.via == Via.Scan }.map { it.info.fingerprint to it.addr } }
        for ((fp, addr) in targets) {
            if (probe(addr)?.fingerprint == fp) {
                synchronized(lock) { map[fp]?.let { map[fp] = it.copy(lastSeenNs = System.nanoTime()) } }
            }
        }
    }
}
