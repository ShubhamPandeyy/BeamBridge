package app.beambridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import java.text.DateFormat
import java.util.Date

/** Every command sent (app, web, API, scenes), newest first, with whether the transmitter accepted it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel) {
    val entries by vm.history.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = when (filter) { "failed" -> entries.filter { !it.ok }; "all" -> entries; else -> entries.filter { it.source == filter } }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Command history", style = BeamType.screenTitle, modifier = Modifier.padding(start = 4.dp)) },
                navigationIcon = { FilledTonalIconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 8.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (entries.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear history") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "failed" to "Failed", "app" to "App", "web" to "Web", "api" to "API", "scene" to "Scenes").forEach { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) })
                }
            }
            if (shown.isEmpty()) Text(
                if (entries.isEmpty()) "Nothing sent yet. Every command from the app, web UI and API shows up here." else "No matching commands.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { "${it.time}-${it.button}-${it.source}-${it.hashCode()}" }) { e ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                if (e.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, if (e.ok) "Sent" else "Failed",
                                tint = if (e.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        },
                        headlineContent = { Text(listOfNotNull(prettyName(e.button), e.deviceName ?: e.device).joinToString(" → ")) },
                        supportingContent = {
                            Text(listOfNotNull(fmt.format(Date(e.time)), e.source, e.message).joinToString(" · "))
                        },
                    )
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
