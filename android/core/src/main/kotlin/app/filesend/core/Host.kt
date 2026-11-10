package app.filesend.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Surface de notification fournie par la plateforme (Android : NotificationManager). */
interface Notifier {
    /** Demande entrante avec Accepter/Refuser (AC-F3-1). */
    fun request(id: String, title: String, detail: String)
    fun clearRequest(id: String)
    fun progress(id: String, title: String, percent: Int?)
    /** `tops` vide : envoi termine (rien a ouvrir) ; sinon elements recus. */
    fun done(id: String, title: String, tops: List<String>)
    fun fail(id: String, title: String)
    /** Retire une notification de progression sans resultat (envoi annule). */
    fun dismiss(id: String)
}

/** Demande en attente d'une reponse, aussi montree dans l'ecran principal (E1). */
data class IncomingUi(val id: String, val title: String, val detail: String)

/** Etat visible dans E1. Si les notifications sont refusees (AC-F4-4), tout passe par cet etat. */
data class HostState(
    val devices: List<Device> = emptyList(),
    val incoming: IncomingUi? = null,
    val status: String? = null,
    val running: Boolean = false,
    val error: String? = null,
    val sending: Boolean = false,
)

class HostConfig(
    val info: DeviceInfo,
    val identity: Identity,
    val sink: ReceiveSink,
    val httpsPort: Int = DEFAULT_PORT,
    val decisionTimeoutMs: Long = 60_000,
    val discovery: (DiscoveryConfig) -> DiscoveryConfig = { it },
    /** Journal technique (Android : logcat). Jamais de contenu de fichier. */
    val log: (String, Throwable?) -> Unit = { _, _ -> },
)

/**
 * Reception + envoi + decouverte + notifications, independants de la plateforme (testables sur la JVM).
 * L'interface Android ne fait que fournir un [Notifier] et afficher [HostState].
 */
class Host(
    private val cfg: HostConfig,
    private val notifier: Notifier,
    private val onState: (HostState) -> Unit = {},
) {
    private val lock = Any()
    private var state = HostState()
    private var receiver: Receiver? = null
    private var discovery: Discovery? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val nextId = AtomicLong(1)
    private var recvAlias = ""

    /** Transferts en cours (reception ou envoi) : l'arret differe attend qu'ils soient finis. */
    private val active = AtomicInteger(0)
    private val sendCancel = AtomicBoolean(false)

    /** Port TCP reellement ecoute (0 tant que non demarre). */
    @Volatile var port: Int = 0
        private set
    @Volatile private var stopWhenIdle = false

    val current: HostState get() = synchronized(lock) { state }

    private fun update(f: (HostState) -> HostState) {
        val s = synchronized(lock) { f(state).also { state = it } }
        runCatching { onState(s) }
    }

    /** Demarre reception et decouverte. Retourne false si le port est occupe (`error` renseigne). */
    fun start(): Boolean {
        stopWhenIdle = false
        if (receiver != null) return true
        val r = Receiver(
            ReceiverConfig(cfg.info, cfg.sink, decisionTimeoutMs = cfg.decisionTimeoutMs),
            cfg.identity,
            ::decide,
            ::onEvent,
        )
        val port = try {
            r.start(cfg.httpsPort)
        } catch (e: Exception) {
            update { it.copy(running = false, error = "Port occupé") }
            return false
        }
        receiver = r
        this.port = port
        val d = Discovery(cfg.discovery(DiscoveryConfig(cfg.info, port)), { devs -> update { it.copy(devices = devs) } })
        discovery = d
        d.start()
        update { it.copy(running = true, error = null, devices = d.devices()) }
        return true
    }

    /** Arrete tout, ou des la fin du transfert en cours (reception uniquement app ouverte, H3). */
    fun stopWhenIdle() {
        if (active.get() > 0) stopWhenIdle = true else stop()
    }

    fun stop() {
        discovery?.stop()
        receiver?.stop()
        discovery = null
        receiver = null
        pending.values.forEach { it.complete(false) }
        update { it.copy(running = false, devices = emptyList(), incoming = null) }
    }

    /** Reponse de l'utilisateur (notification ou bouton de l'ecran). */
    fun answer(id: String, accept: Boolean) {
        pending[id]?.complete(accept)
    }

    // ---------- envoi (F2) ----------

    /**
     * Envoie vers `device` en arriere-plan. `sources` est evalue dans le fil d'envoi (le parcours
     * d'un gros dossier ne bloque pas l'interface). Retourne false si un envoi est deja en cours.
     */
    fun sendTo(device: Device, sources: () -> List<SendSource>): Boolean {
        synchronized(lock) {
            if (state.sending) return false
            state = state.copy(sending = true)
        }
        sendCancel.set(false)
        active.incrementAndGet()
        val id = "s${nextId.getAndIncrement()}"
        val alias = device.info.alias
        val title = Messages.sending(alias)
        update { it.copy(status = title) }
        notifier.progress(id, title, null) // en attente de la reponse de l'autre appareil
        Thread({
            var last = 0L
            val progress: Progress = { done, total ->
                val now = System.nanoTime()
                if (now - last > 150_000_000L || done >= total) {
                    last = now
                    notifier.progress(id, title, Messages.percent(done, total))
                }
            }
            try {
                send(device.addr, sources(), SendOptions(cfg.info), progress, sendCancel)
                val t = Messages.sent(alias)
                update { it.copy(status = t) }
                notifier.done(id, t, emptyList())
            } catch (_: SendCancelled) {
                update { it.copy(status = null) }
                notifier.dismiss(id)
            } catch (e: SendException) {
                cfg.log("envoi echoue (${e.reason.label})", e.cause)
                update { it.copy(status = e.reason.message) }
                notifier.fail(id, e.reason.message)
            } catch (e: Exception) {
                cfg.log("envoi echoue", e)
                val m = FailReason.Interrupted.message
                update { it.copy(status = m) }
                notifier.fail(id, m)
            } finally {
                update { it.copy(sending = false) }
                finished()
            }
        }, "filesend-send").apply { isDaemon = true }.start()
        return true
    }

    fun cancelSend() = sendCancel.set(true)

    // ---------- reception (F3) ----------

    private fun decide(req: IncomingRequest): Boolean {
        val id = "r${nextId.getAndIncrement()}"
        val answer = CompletableFuture<Boolean>()
        pending[id] = answer
        val (title, detail) = Messages.request(req.from.alias, req.count, req.total)
        update { it.copy(incoming = IncomingUi(id, title, detail)) }
        notifier.request(id, title, detail)
        return try {
            answer.get()
        } catch (_: Exception) {
            false // delai depasse (interruption par le receveur) : refus
        } finally {
            pending.remove(id)
            update { it.copy(incoming = null) }
            notifier.clearRequest(id)
        }
    }

    private fun onEvent(ev: Event) {
        when (ev) {
            is Event.Started -> {
                active.incrementAndGet()
                recvAlias = ev.from.alias
                val t = Messages.receiving(ev.from.alias)
                update { it.copy(status = t) }
                notifier.progress(ev.session, t, 0)
            }
            is Event.Progress -> notifier.progress(ev.session, Messages.receiving(recvAlias), Messages.percent(ev.done, ev.total))
            is Event.Completed -> {
                val t = Messages.received(ev.from.alias)
                update { it.copy(status = t) }
                notifier.done(ev.session, t, ev.tops)
                finished()
            }
            is Event.Failed -> {
                val t = ev.reason.message
                update { it.copy(status = t) }
                notifier.fail(ev.session, t)
                finished()
            }
        }
    }

    private fun finished() {
        if (active.decrementAndGet() > 0) return
        active.set(0)
        // Differe : arreter ici couperait la reponse HTTP du dernier fichier encore en cours d'envoi.
        if (stopWhenIdle) Thread({ Thread.sleep(500); if (active.get() == 0) stop() }, "filesend-stop").apply { isDaemon = true }.start()
    }
}
