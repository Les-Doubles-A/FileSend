package app.filesend.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.filesend.core.Device
import app.filesend.core.HostState
import app.filesend.core.Messages
import app.filesend.core.Relay

private enum class Sheet { None, Qr, Bluetooth, More }

/**
 * E1 : interface minimaliste en verre. Toutes les fonctions avancees (QR hors-reseau,
 * Bluetooth, Texte, Historique, Reglages) s'ouvrent dans des panneaux superposes a la demande.
 */
@Composable
fun Home(
    state: HostState,
    pending: Pending?,
    hint: String?,
    myAlias: String,
    trusted: Set<String>,
    history: List<HistoryItem>,
    btReceiving: Boolean,
    customRelay: String,
    colors: GlassColors,
    glass: Boolean,
    notificationsOn: Boolean,
    onSend: (Device) -> Unit,
    onToggleTrusted: (String, String) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onClearPending: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onCancelSend: () -> Unit,
    onRescan: () -> Unit,
    onCopyText: (String) -> Unit,
    onPasteText: () -> String,
    onClearReceivedText: () -> Unit,
    onSendText: (Device, String, (Boolean) -> Unit) -> Unit,
    onShareQr: (String, String, (Result<Relay.QrShare>) -> Unit) -> Unit,
    onStopLocalQr: () -> Unit,
    onReceiveQrUri: (String, (Result<List<String>>) -> Unit) -> Unit,
    onEnsureBt: () -> Boolean,
    onListBt: () -> List<BluetoothTransfer.PairedDevice>,
    onToggleBtReceive: (Boolean) -> Boolean,
    onSendBt: (BluetoothTransfer.PairedDevice, (Result<Unit>) -> Unit) -> Unit,
    onSendSystemBt: () -> Unit,
    onSaveSettings: (String, String) -> Unit,
    onClearHistory: () -> Unit,
) {
    var sheet by remember { mutableStateOf(Sheet.None) }
    val bg = if (glass) colors.surface else colors.solid

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Barre superieure discrete : alias + 3 boutons circulaires (QR, BT, Menu)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    myAlias,
                    color = colors.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderPill("QR", "Partager ou recevoir par QR code", colors) { sheet = Sheet.Qr }
                    HeaderPill("BT", "Bluetooth", colors) {
                        onEnsureBt()
                        sheet = Sheet.Bluetooth
                    }
                    HeaderPill("⋯", "Texte, historique et réglages", colors) { sheet = Sheet.More }
                }
            }

            state.incoming?.let { inc ->
                Card(colors, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
                    Text(inc.title, color = colors.text, fontWeight = FontWeight.SemiBold)
                    Text(inc.detail, color = colors.muted)
                    if (!notificationsOn) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onAnswer(inc.id, false) }) { Text("Refuser", color = colors.text) }
                            TextButton(onClick = { onAnswer(inc.id, true) }) {
                                Text("Accepter", color = colors.accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            state.receivedText?.let { (from, txt) ->
                Spacer(Modifier.height(6.dp))
                Card(colors, Modifier.fillMaxWidth()) {
                    Text("Texte de $from", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(txt, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClearReceivedText) { Text("Fermer", color = colors.muted) }
                        TextButton(onClick = { onCopyText(txt); onClearReceivedText() }) {
                            Text("Copier", color = colors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            pending?.let {
                Row(
                    Modifier
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.stroke, RoundedCornerShape(999.dp))
                        .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(it.label, color = colors.text, fontSize = 13.sp)
                    Text(
                        "×",
                        color = colors.muted,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onClearPending).padding(horizontal = 4.dp),
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.devices.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Aucun appareil. Même Wi-Fi ?",
                            color = colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillAction("Actualiser", colors, onRescan)
                            PillAction("QR hors-réseau", colors) { sheet = Sheet.Qr }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.devices, key = { it.info.fingerprint }) { d ->
                            Tile(
                                d = d,
                                isTrusted = trusted.contains(d.info.fingerprint),
                                colors = colors,
                                onSend = onSend,
                                onLongPress = { onToggleTrusted(d.info.fingerprint, d.info.alias) },
                            )
                        }
                    }
                }
            }

            val line = hint ?: state.error ?: state.status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    line ?: "",
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.sending) {
                    Text(
                        "Annuler",
                        color = colors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onCancelSend),
                    )
                }
            }

            PlusButton(
                colors = colors,
                onFiles = onPickFiles,
                onFolder = onPickFolder,
                onText = { sheet = Sheet.More },
            )
        }

        // Panneaux superposes (QR, Bluetooth, Plus)
        when (sheet) {
            Sheet.None -> {}
            Sheet.Qr -> QrSheet(
                colors = colors,
                hasPending = pending != null && pending.count > 0,
                onPickFiles = onPickFiles,
                onShareQr = onShareQr,
                onCopyText = onCopyText,
                onPasteText = onPasteText,
                onReceiveUri = onReceiveQrUri,
                onClose = { onStopLocalQr(); sheet = Sheet.None },
            )
            Sheet.Bluetooth -> BluetoothSheet(
                colors = colors,
                btReceiving = btReceiving,
                onListBt = onListBt,
                onToggleReceive = onToggleBtReceive,
                onSendBt = { d ->
                    onSendBt(d) {}
                    sheet = Sheet.None
                },
                onSendSystemBt = onSendSystemBt,
                onClose = { sheet = Sheet.None },
            )
            Sheet.More -> MoreSheet(
                colors = colors,
                devices = state.devices,
                history = history,
                myAlias = myAlias,
                customRelay = customRelay,
                onPasteText = onPasteText,
                onSendText = onSendText,
                onSaveSettings = onSaveSettings,
                onClearHistory = onClearHistory,
                onClose = { sheet = Sheet.None },
            )
        }
    }
}

@Composable
private fun HeaderPill(label: String, desc: String, colors: GlassColors, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.stroke, RoundedCornerShape(999.dp))
            .clickable(onClickLabel = desc, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PillAction(label: String, colors: GlassColors, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.stroke, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = colors.text, fontSize = 13.sp)
    }
}

@Composable
private fun Card(colors: GlassColors, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.stroke, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    d: Device,
    isTrusted: Boolean,
    colors: GlassColors,
    onSend: (Device) -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val alias = d.info.alias
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.stroke, shape)
            .combinedClickable(
                onClickLabel = "Envoyer à $alias",
                role = Role.Button,
                onClick = { onSend(d) },
                onLongClick = onLongPress,
            )
            .semantics(mergeDescendants = true) { contentDescription = "Envoyer à $alias" }
            .padding(horizontal = 8.dp, vertical = 14.dp),
    ) {
        if (isTrusted) {
            Text("★", color = colors.accent, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd))
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.hsl(hue(alias), 0.6f, 0.30f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(alias.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(alias, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}

private fun hue(s: String): Float {
    var h = 0
    for (c in s) h = (h * 31 + c.code) % 360
    return h.toFloat()
}

@Composable
private fun PlusButton(
    colors: GlassColors,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onText: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 16.dp)) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.stroke, CircleShape)
                .clickable(onClickLabel = "Choisir des fichiers", role = Role.Button) { menu = true }
                .semantics { contentDescription = "Choisir des fichiers" },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = colors.text, fontSize = 28.sp) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Fichiers") }, onClick = { menu = false; onFiles() })
            DropdownMenuItem(text = { Text("Dossier") }, onClick = { menu = false; onFolder() })
            DropdownMenuItem(text = { Text("Envoyer un texte") }, onClick = { menu = false; onText() })
        }
    }
}

// ---------- Panneau QR Code (Hors-reseau E2E, Navigateur, Wi-Fi local, Recevoir) ----------

@Composable
private fun SheetFrame(title: String, colors: GlassColors, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.solid.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            HeaderPill("×", "Fermer", colors, onClose)
        }
        content()
    }
}

@Composable
private fun SegmentedRow(options: List<Pair<String, String>>, selected: String, colors: GlassColors, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.stroke, RoundedCornerShape(999.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((key, label) in options) {
            val active = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) colors.accent else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) colors.onAccent else colors.muted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun QrSheet(
    colors: GlassColors,
    hasPending: Boolean,
    onPickFiles: () -> Unit,
    onShareQr: (String, String, (Result<Relay.QrShare>) -> Unit) -> Unit,
    onCopyText: (String) -> Unit,
    onPasteText: () -> String,
    onReceiveUri: (String, (Result<List<String>>) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf("send") }
    var mode by remember { mutableStateOf("relay-e2e") }
    var expiry by remember { mutableStateOf("1h") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var share by remember { mutableStateOf<Relay.QrShare?>(null) }
    var qrBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var uriInput by remember { mutableStateOf("") }

    SheetFrame("QR Code", colors, onClose) {
        SegmentedRow(listOf("send" to "Partager", "recv" to "Recevoir"), tab, colors) { tab = it }

        if (tab == "send") {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SegmentedRow(
                    listOf("relay-e2e" to "Internet (Chiffré)", "relay-direct" to "Navigateur", "lan" to "Wi-Fi"),
                    mode,
                    colors,
                ) { mode = it }

                if (mode != "lan") {
                    SegmentedRow(listOf("1h" to "1 h", "24h" to "24 h", "72h" to "72 h"), expiry, colors) { expiry = it }
                }

                if (!hasPending) {
                    PillAction("Choisir des fichiers…", colors, onPickFiles)
                }

                Button(
                    onClick = {
                        busy = true
                        status = if (mode == "lan") "Préparation Wi-Fi…" else "Chiffrement et envoi…"
                        onShareQr(mode, expiry) { res ->
                            busy = false
                            res.onSuccess { s ->
                                share = s
                                qrBmp = runCatching { QrBitmap.encode(s.uri) }.getOrNull()
                                status = when (mode) {
                                    "relay-e2e" -> "Chiffré AES-256-GCM — scannez sur n'importe quel réseau"
                                    "relay-direct" -> "Scannez avec l'appareil photo de n'importe quel téléphone"
                                    else -> "Scannez sur le même Wi-Fi"
                                }
                            }.onFailure { e ->
                                status = e.message ?: "Erreur"
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Génération…" else "Générer le QR code") }

                if (status.isNotEmpty()) {
                    Text(status, color = colors.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }

                val bmp = qrBmp
                val sh = share
                if (bmp != null && sh != null) {
                    Card(colors, Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp)) {
                                Image(bmp, contentDescription = "QR Code", modifier = Modifier.size(200.dp))
                            }
                            Text(sh.name, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Messages.formatSize(sh.size)} · ${sh.expires}", color = colors.muted, fontSize = 12.sp)
                            PillAction("Copier le lien", colors) { onCopyText(sh.uri) }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Scannez un QR FileSend avec votre appareil photo (il s'ouvrira ici), ou collez un lien filesend:// / https:// :",
                    color = colors.muted,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = uriInput,
                    onValueChange = { uriInput = it },
                    placeholder = { Text("filesend://r?u=...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillAction("Coller", colors) { uriInput = onPasteText() }
                    Button(
                        onClick = {
                            if (uriInput.isBlank()) return@Button
                            busy = true
                            status = "Téléchargement & déchiffrement…"
                            onReceiveUri(uriInput.trim()) { r ->
                                busy = false
                                status = r.fold(
                                    onSuccess = { "Reçu : ${it.firstOrNull() ?: "OK"}" },
                                    onFailure = { it.message ?: "Erreur" },
                                )
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "En cours…" else "Télécharger") }
                }
                if (status.isNotEmpty()) Text(status, color = colors.muted, fontSize = 12.sp)
            }
        }
    }
}

// ---------- Panneau Bluetooth ----------

@Composable
private fun BluetoothSheet(
    colors: GlassColors,
    btReceiving: Boolean,
    onListBt: () -> List<BluetoothTransfer.PairedDevice>,
    onToggleReceive: (Boolean) -> Boolean,
    onSendBt: (BluetoothTransfer.PairedDevice) -> Unit,
    onSendSystemBt: () -> Unit,
    onClose: () -> Unit,
) {
    var paired by remember { mutableStateOf(onListBt()) }
    SheetFrame("Bluetooth", colors, onClose) {
        Card(colors, Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recevoir en Bluetooth (FileSend)", color = colors.text, fontSize = 13.sp)
                Switch(checked = btReceiving, onCheckedChange = { onToggleReceive(it) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Appareils appairés", color = colors.muted, fontSize = 12.sp)
            TextButton(onClick = { paired = onListBt() }) { Text("Actualiser", color = colors.accent, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (paired.isEmpty()) {
                item {
                    Text(
                        "Aucun appareil Bluetooth appairé ou Bluetooth désactivé.",
                        color = colors.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(paired, key = { it.address }) { dev ->
                Card(colors, Modifier.fillMaxWidth().clickable { onSendBt(dev) }) {
                    Text(dev.name, color = colors.text, fontWeight = FontWeight.Medium)
                    Text(dev.address, color = colors.muted, fontSize = 11.sp)
                }
            }
        }
        Button(
            onClick = onSendSystemBt,
            colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.text),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Partager via Bluetooth système (OBEX)") }
    }
}

// ---------- Panneau Plus (Texte, Historique, Reglages) ----------

@Composable
private fun MoreSheet(
    colors: GlassColors,
    devices: List<Device>,
    history: List<HistoryItem>,
    myAlias: String,
    customRelay: String,
    onPasteText: () -> String,
    onSendText: (Device, String, (Boolean) -> Unit) -> Unit,
    onSaveSettings: (String, String) -> Unit,
    onClearHistory: () -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf("text") }
    var text by remember { mutableStateOf("") }
    var textQr by remember { mutableStateOf<ImageBitmap?>(null) }
    var aliasEdit by remember { mutableStateOf(myAlias) }
    var relayEdit by remember { mutableStateOf(customRelay) }

    SheetFrame("FileSend", colors, onClose) {
        SegmentedRow(listOf("text" to "Texte", "history" to "Historique", "settings" to "Réglages"), tab, colors) { tab = it }

        when (tab) {
            "text" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; textQr = null },
                    placeholder = { Text("Collez un lien, une note…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillAction("Coller", colors) { text = onPasteText() }
                    PillAction("En QR code", colors) {
                        if (text.isNotBlank()) textQr = runCatching { QrBitmap.encode(text.trim()) }.getOrNull()
                    }
                }
                textQr?.let { bmp ->
                    Box(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp)) {
                        Image(bmp, contentDescription = "QR texte", modifier = Modifier.size(180.dp))
                    }
                }
                if (devices.isNotEmpty()) {
                    Text("Envoyer à :", color = colors.muted, fontSize = 12.sp)
                    for (d in devices) {
                        PillAction("→ ${d.info.alias}", colors) {
                            if (text.isNotBlank()) {
                                onSendText(d, text.trim()) { ok -> if (ok) { text = ""; onClose() } }
                            }
                        }
                    }
                }
            }
            "history" -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Transferts récents", color = colors.muted, fontSize = 12.sp)
                    TextButton(onClick = onClearHistory) { Text("Effacer", color = colors.accent, fontSize = 12.sp) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (history.isEmpty()) {
                        item { Text("Aucun transfert récent", color = colors.muted, fontSize = 13.sp) }
                    }
                    items(history) { it ->
                        val arrow = when (it.direction) { "receive" -> "↓"; "text" -> "💬"; else -> "↑" }
                        Card(colors, Modifier.fillMaxWidth()) {
                            Text("$arrow ${it.summary}", color = colors.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${it.peer} · ${it.detail} · ${it.mode.uppercase()}", color = colors.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            "settings" -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = aliasEdit,
                    onValueChange = { aliasEdit = it.take(32) },
                    label = { Text("Nom de cet appareil") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = relayEdit,
                    onValueChange = { relayEdit = it },
                    label = { Text("Relais Internet personnalisé (optionnel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Astuce : appui long sur un appareil pour activer l'acceptation automatique (★).", color = colors.muted, fontSize = 12.sp)
                Button(
                    onClick = { onSaveSettings(aliasEdit, relayEdit); onClose() },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer") }
            }
        }
    }
}
