package app.filesend.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Types du protocole FileSend v1 (voir docs/protocol.md). Memes noms de champs que cote Rust. */
const val DEFAULT_PORT = 53318
const val API_PREFIX = "/api/filesend/v1"

@Serializable
data class DeviceInfo(val alias: String, val fingerprint: String, val deviceType: String)

@Serializable
data class FileEntry(
    val id: String,
    val relativePath: String,
    val size: Long,
    val sha256: String? = null,
)

@Serializable
data class PrepareUploadRequest(val info: DeviceInfo, val files: Map<String, FileEntry>)

@Serializable
data class PrepareUploadResponse(val sessionId: String, val files: Map<String, String>)

internal val ProtocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false // sha256 absent plutot que null, comme cote Rust
}

/** Raisons d'echec affichables (AC-F4-3). Exactement quatre. */
enum class FailReason(val label: String) {
    Refused("Refusé"),
    NotFound("Introuvable"),
    Interrupted("Interrompu"),
    DiskFull("Disque plein");

    val message: String get() = "Échec : $label"
}

/** Evenements emis par le receveur vers l'interface (F4). */
sealed interface Event {
    data class Started(val session: String, val from: DeviceInfo, val count: Int, val total: Long) : Event
    data class Progress(val session: String, val done: Long, val total: Long) : Event
    /** `tops` : elements de premier niveau enregistres. */
    data class Completed(val session: String, val from: DeviceInfo, val tops: List<String>) : Event
    data class Failed(val session: String, val reason: FailReason) : Event
}

/** Demande entrante presentee a l'utilisateur (AC-F3-1). */
data class IncomingRequest(val from: DeviceInfo, val count: Int, val total: Long)
