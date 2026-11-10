package app.beambridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.data.StandardKeys

/**
 * Brute-force code finder: pick a key, every code in the database is fired one after another,
 * tap STOP when the device reacts, then try the last 5 codes (and the 5 before) to pin it down.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FinderScreen(vm: MainViewModel, device: SavedDevice) {
    val s by vm.finder.state.collectAsStateWithLifecycle()
    var keyName by remember(s.key) { mutableStateOf(prettyName(s.key ?: "")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV finder") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (s.key == null) {
                Text("Pick the key to find. Keep the phone pointed at the TV; we'll fire every known code for it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StandardKeys.FINDER_KEYS.forEach { k ->
                        FilledTonalButton(onClick = { vm.startFinder(k) }) {
                            buttonIcon(k)?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(prettyName(k))
                        }
                    }
                }
                return@Column
            }

            Text("Finding ${prettyName(s.key!!)}", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.sent.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
            Text("${s.sent} of ${s.total} codes sent", color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (s.running) {
                Button(
                    onClick = vm::stopFinder, modifier = Modifier.fillMaxWidth().height(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shape = MaterialTheme.shapes.extraLarge,
                ) { Icon(Icons.Rounded.Stop, null, Modifier.size(36.dp)); Spacer(Modifier.width(12.dp)); Text("STOP", style = MaterialTheme.typography.headlineMedium) }
                Text("Tap STOP the moment the TV reacts.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                return@Column
            }

            Text(
                if (s.done) "All codes sent. Did one work? Test the recent ones below." else "Stopped. Test the most recent codes one by one:",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(keyName, { keyName = it }, label = { Text("Save as key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val window = remember(s.sent, s.page) { vm.finderWindow() }
            window.forEach { (index, code) ->
                ListItem(
                    headlineContent = { Text("#${index + 1} · ${code.label}") },
                    supportingContent = { Text(app.beambridge.core.data.CodeInput.format(code.code)) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { vm.testFinder(index) }) { Icon(Icons.AutoMirrored.Rounded.Send, "Test") }
                            FilledTonalButton(onClick = { vm.acceptFinder(device.id, keyName, index); vm.finder.reset() }) { Text("Use") }
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.finderPage(s.page - 1) }, enabled = s.page > 0) { Text("Newer 5") }
                TextButton(onClick = { vm.finderPage(s.page + 1) }, enabled = (s.page + 1) * 5 < s.sent) { Text("5 before") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!s.done) OutlinedButton(onClick = vm::resumeFinder) { Icon(Icons.Rounded.PlayArrow, null); Text("Keep going") }
                OutlinedButton(onClick = { vm.finder.reset() }) { Text("Another key") }
            }
        }
    }
}
