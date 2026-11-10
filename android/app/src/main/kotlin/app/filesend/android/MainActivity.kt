package app.filesend.android

import android.Manifest
import android.app.NotificationManager
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

/** E1 : appareils proches, verre (flou de fenetre si disponible, sinon fond opaque), themes clair/sombre. */
class MainActivity : ComponentActivity() {
    private val app get() = application as FileSendApp

    /** Flou actif : le fond est translucide devant le flou ; sinon opaque (AC-F5-3). */
    private var glass by mutableStateOf(false)
    private var blurListener: Consumer<Boolean>? = null

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* refus : l'ecran suffit (AC-F4-4) */ }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) app.pending.value = Pending(files = uris)
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) app.pending.value = Pending(tree = uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setUpBlur()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        readShared(intent)
        val nm = getSystemService(NotificationManager::class.java)
        setContent {
            val dark = isSystemInDarkTheme()
            val state by app.state
            val pending by app.pending
            val hint by app.hint
            Home(
                state = state,
                pending = pending,
                hint = hint,
                myAlias = app.alias,
                colors = glassColors(dark),
                glass = glass,
                notificationsOn = nm.areNotificationsEnabled(),
                onSend = { d -> app.hint.value = null; app.send(d) },
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onPickFolder = { pickFolder.launch(null) },
                onAnswer = { id, ok -> app.host?.answer(id, ok) },
            )
        }
    }

    /** Flou derriere la fenetre (Android 12+), suit l'etat du systeme (economie d'energie, option desactivee). */
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
        readShared(intent)
    }

    /** Elements partages depuis une autre application (menu Partager). */
    private fun readShared(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(i))
            Intent.ACTION_SEND_MULTIPLE -> streamsExtra(i)
            else -> return
        }
        if (uris.isNotEmpty()) app.pending.value = Pending(files = uris)
        // Crochet de test, builds debug uniquement.
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
