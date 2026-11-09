package app.beambridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.core.data.CodeInput
import app.beambridge.core.data.SavedButton
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.ir.IrCode

/** Add a key (pick from the brand's catalog, or type any IR code) or edit an existing one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ButtonEditorSheet(vm: MainViewModel, device: SavedDevice, existing: SavedButton?, onDismiss: () -> Unit) {
    val candidates = remember(device) { vm.candidateButtons(device) }
    var tab by remember { mutableIntStateOf(if (existing != null || candidates.isEmpty()) 1 else 0) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var picked by remember { mutableStateOf<Pair<String, IrCode>?>(null) }
    var text by remember { mutableStateOf(existing?.code?.let(CodeInput::format).orEmpty()) }
    var freq by remember { mutableStateOf(((existing?.code as? IrCode.Raw)?.frequency ?: 38000).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val looksRaw = text.isNotBlank() && text.trim().first().isDigit() && text.contains(',')

    fun build(): IrCode? = when (tab) {
        0 -> picked?.second.also { if (it == null) error = "Pick a key" }
        else -> try {
            if (text.isBlank()) { error = "Enter an IR code"; null } else CodeInput.parseText(text, freq.toIntOrNull()).also { error = null }
        } catch (e: Exception) { error = e.message ?: "Can't read that code"; null }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (existing == null) "New key" else "Edit ${prettyName(existing.name)}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Key name") }, placeholder = { Text("e.g. Netflix") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if (candidates.isNotEmpty()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("From ${device.brand ?: "catalog"}", "IR code").forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i; error = null }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label, maxLines = 1) }
                }
            }

            if (tab == 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { c ->
                    FilterChip(selected = picked?.first == c.first, onClick = { picked = c; if (name.isBlank()) name = prettyName(c.first) }, label = { Text(prettyName(c.first)) })
                }
            } else {
                OutlinedTextField(
                    text, { text = it; error = null },
                    label = { Text("IR code") },
                    placeholder = { Text("NEC 0x20DF10EF 32") },
                    isError = error != null,
                    supportingText = { Text(error ?: "Protocol + hex, raw µs timings, or Pronto hex") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                if (looksRaw) OutlinedTextField(
                    freq, { freq = it.filter(Char::isDigit) }, label = { Text("Carrier Hz") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Text("Examples", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXAMPLES.forEach { (label, value) -> AssistChip(onClick = { text = value; error = null }, label = { Text(label) }) }
                }
            }
            if (tab == 0 && error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { build()?.let(vm::sendCode) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null); Text("Test", Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = { build()?.let { if (vm.saveButton(device.id, name, it, existing?.name)) onDismiss() } },
                    enabled = name.isNotBlank(), modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.Check, null); Text("Save", Modifier.padding(start = 8.dp)) }
            }
        }
    }
}

/** Tap-to-fill examples, one per supported input style. */
val EXAMPLES = listOf(
    "NEC (LG)" to "NEC 0x20DF10EF 32",
    "Samsung" to "SAMSUNG 0xE0E040BF",
    "Sony" to "SONY 0xA90 12",
    "RCA (TCL)" to "RCA 0xF2A0D5",
    "Raw µs" to "9000,4500,560,560,560,1690,560,560",
    "Pronto" to "0000 006D 0002 0000 0157 00AC 0015 0689",
)
