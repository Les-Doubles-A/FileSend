package app.filesend.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import app.filesend.core.Device
import app.filesend.core.HostState

/**
 * E1 : appareils proches, elements en attente, statut. Un seul bouton de selection ("+") en dehors
 * des tuiles ; les boutons Accepter/Refuser de la demande n'apparaissent que si les notifications
 * sont refusees (sinon la notification porte ces boutons, AC-F4-4).
 */
@Composable
fun Home(
    state: HostState,
    pending: Pending?,
    hint: String?,
    myAlias: String,
    colors: GlassColors,
    glass: Boolean,
    notificationsOn: Boolean,
    onSend: (Device) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
) {
    // Verre : fond translucide devant le flou de la fenetre ; sinon fond opaque (AC-F5-3).
    val bg = if (glass) colors.surface else colors.solid
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                myAlias, color = colors.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
            )
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
            pending?.let {
                Text(
                    it.label, color = colors.text,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.stroke, RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.devices.isEmpty()) {
                    Text(
                        "Aucun appareil. Même Wi-Fi ?", color = colors.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.devices, key = { it.info.fingerprint }) { d -> Tile(d, colors, onSend) }
                    }
                }
            }
            val line = hint ?: state.error ?: state.status
            Text(
                line ?: "", color = colors.muted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            PlusButton(colors, onPickFiles, onPickFolder)
        }
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun Tile(d: Device, colors: GlassColors, onSend: (Device) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val alias = d.info.alias
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.stroke, shape)
            .clickable(onClickLabel = "Envoyer à $alias", role = Role.Button) { onSend(d) }
            .semantics(mergeDescendants = true) { contentDescription = "Envoyer à $alias" }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.hsl(hue(alias), 0.6f, 0.30f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(alias.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(alias, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun hue(s: String): Float {
    var h = 0
    for (c in s) h = (h * 31 + c.code) % 360
    return h.toFloat()
}

@Composable
private fun PlusButton(colors: GlassColors, onFiles: () -> Unit, onFolder: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 20.dp)) {
        Box(
            Modifier
                .size(56.dp)
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
        }
    }
}
