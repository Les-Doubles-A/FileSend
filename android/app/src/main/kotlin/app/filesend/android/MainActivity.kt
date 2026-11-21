package app.filesend.android

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.function.Consumer

/** E1 : appareils proches, verre (flou de fenetre si disponible, sinon fond opaque), QR hors-reseau, Bluetooth. */
class MainActivity : ComponentActivity() {
    private val app get() = application as FileSendApp

    private var glass by mutableStateOf(false)
    private var blurListener: Consumer<Boolean>? = null
    private var btPermissionGranted by mutableStateOf(false)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val askBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            btPermissionGranted = map.values.all { it }
            if (!btPermissionGranted) app.hint.value = "Autorisation Bluetooth requise"
        }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) app.pending.value = Pending(files = uris)
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) app.pending.value = Pending(tree = uri)
        }

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBtPermission(): Boolean {
        if (hasBtPermission()) {
            btPermissionGranted = true
            return true
        }
        if (Build.VERSION.SDK_INT >= 31) {
            askBluetooth.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setUpBlur()
        btPermissionGranted = hasBtPermission()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        readIncomingIntent(intent)
        val nm = getSystemService(NotificationManager::class.java)
        val clipboard = getSystemService(ClipboardManager::class.java)

        setContent {
            val dark = isSystemInDarkTheme()
            val state by app.state
            val pending by app.pending
            val hint by app.hint
            val myAlias by app.aliasState
            val trusted by app.trusted
            val history by app.history
            val btReceiving by app.btReceiving
            val customRelay by app.customRelay

            Home(
                state = state,
                pending = pending,
                hint = hint,
                myAlias = myAlias,
                trusted = trusted,
                history = history,
                btReceiving = btReceiving,
                customRelay = customRelay,
                colors = glassColors(dark),
                glass = glass,
                notificationsOn = nm.areNotificationsEnabled(),
                onSend = { d -> app.hint.value = null; app.send(d) },
                onToggleTrusted = { fp, alias ->
                    val on = app.toggleTrusted(fp)
                    app.hint.value = if (on) "$alias : auto-accepté ★" else "$alias : confirmation requise"
                },
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onPickFolder = { pickFolder.launch(null) },
                onClearPending = { app.pending.value = null },
                onAnswer = { id, ok -> app.host?.answer(id, ok) },
                onCancelSend = { app.host?.cancelSend() },
                onRescan = { app.rescan() },
                onCopyText = { txt ->
                    clipboard?.setPrimaryClip(ClipData.newPlainText("FileSend", txt))
                    app.hint.value = "Copié dans le presse-papiers"
                },
                onPasteText = {
                    clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                },
                onClearReceivedText = { app.host?.clearReceivedText() },
                onSendText = { dev, txt, cb -> app.sendText(dev, txt, cb) },
                onShareQr = { mode, exp, cb -> app.shareQr(mode, exp, cb) },
                onStopLocalQr = { app.stopLocalQr() },
                onReceiveQrUri = { uri, cb -> app.receiveQrUri(uri, cb) },
                onEnsureBt = { ensureBtPermission() },
                onListBt = { if (hasBtPermission()) BluetoothTransfer.listPaired(this) else emptyList() },
                onToggleBtReceive = { on -> if (ensureBtPermission()) app.toggleBtReceiver(on) else false },
                onSendBt = { dev, cb -> app.sendViaBluetooth(dev, cb) },
                onSendSystemBt = {
                    val uris = app.pending.value?.files.orEmpty()
                    if (uris.isEmpty()) app.hint.value = "Choisissez des fichiers d'abord"
                    else BluetoothTransfer.shareViaSystemBluetooth(this, uris)
                },
                onSaveSettings = { a, r ->
                    app.saveSettings(a, r)
                    app.hint.value = "Réglages enregistrés"
                },
                onClearHistory = { app.clearHistory() },
            )
        }
    }

    private fun setUpBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        val apply = { enabled: Boolean ->
            val radius = GlassPolicy.blurRadius(Build.VERSION.SDK_INT, enabled)
            window.setBackgroundBlurRadius(radius)
            glass = radius > 0
        }
        apply(windowManager.isCrossWindowBlurEnabled)
        val l = Consumer<Boolean> { apply(it) }
        blurListener = l
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, l)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) blurListener?.let { windowManager.removeCrossWindowBlurEnabledListener(it) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIncomingIntent(intent)
    }

    private fun readIncomingIntent(i: Intent?) {
        if (i == null) return
        // Deep link filesend://r?... depuis l'appareil photo systeme
        if (i.action == Intent.ACTION_VIEW && i.data?.scheme == "filesend") {
            val raw = i.dataString ?: return
            app.hint.value = "Téléchargement QR…"
            app.receiveQrUri(raw) {}
            return
        }
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(i))
            Intent.ACTION_SEND_MULTIPLE -> streamsExtra(i)
            else -> return
        }
        if (uris.isNotEmpty()) app.pending.value = Pending(files = uris)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            i.getStringExtra("autosend")?.let { app.autoSend = it }
            app.maybeAutoSend()
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamsExtra(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    override fun onStart() {
        super.onStart()
        app.onVisible()
    }

    override fun onStop() {
        app.onHidden()
        super.onStop()
    }
}
