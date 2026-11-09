package app.beambridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.core.data.SavedButton
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.ir.IrCode
import app.beambridge.core.ir.IrCodeEncoder

private val PROTOCOLS = listOf("NEC", "SAMSUNG", "SONY", "RC5", "RC6", "JVC", "PANASONIC", "SHARP")

/** Add a custom button (catalog pick, hex code, or raw/Pronto pulses) or edit an existing custom one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ButtonEditorSheet(vm: MainViewModel, device: SavedDevice, existing: SavedButton?, onDismiss: () -> Unit) {
    val candidates = remember(device) { vm.candidateButtons(device) }
    val code = existing?.code
    var tab by remember { mutableStateOf(if (code is IrCode.Raw || code is IrCode.Pronto) 2 else if (existing != null || candidates.isEmpty()) 1 else 0) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var picked by remember { mutableStateOf<Pair<String, IrCode>?>(null) }
    var protocol by remember { mutableStateOf((code as? IrCode.Hex)?.protocol?.takeIf { it in PROTOCOLS } ?: "NEC") }
    var hex by remember { mutableStateOf((code as? IrCode.Hex)?.code.orEmpty()) }
    var bits by remember { mutableStateOf((code as? IrCode.Hex)?.bits?.takeIf { it > 0 }?.toString().orEmpty()) }
    var freq by remember { mutableStateOf((code as? IrCode.Raw)?.frequency?.toString() ?: "38000") }
    var pattern by remember {
        mutableStateOf(
            when (code) {
                is IrCode.Raw -> code.pattern.joinToString(",")
                is IrCode.Pronto -> code.pronto
                else -> ""
            },
        )
    }

    fun build(): IrCode? = try {
        when (tab) {
            0 -> picked?.second
            1 -> if (hex.isBlank()) null else IrCode.Hex(protocol, hex.trim(), bits.toIntOrNull() ?: 0)
            else -> {
                val text = pattern.trim()
                when {
                    text.isEmpty() -> null
                    text.startsWith("0000") && text.contains(' ') -> IrCode.Pronto(text)
                    else -> IrCode.Raw(freq.toIntOrNull() ?: 38000, IrCodeEncoder.parsePattern(text))
                }
            }
        }
    } catch (e: Exception) {
        vm.toast(e.message ?: "Bad code"); null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            val labels = if (candidates.isEmpty()) listOf("Hex", "Raw") else listOf("Catalog", "Hex", "Raw")
            val offset = if (candidates.isEmpty()) 1 else 0
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i + offset, onClick = { tab = i + offset },
                        shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    ) { Text(label) }
                }
            }

            when (tab) {
                0 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { c ->
                        FilterChip(
                            selected = picked?.first == c.first,
                            onClick = { picked = c; if (name.isBlank()) name = c.first },
                            label = { Text(prettyName(c.first)) },
                        )
                    }
                }
                1 -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PROTOCOLS.forEach { p -> FilterChip(selected = protocol == p, onClick = { protocol = p }, label = { Text(p) }) }
                    }
                    OutlinedTextField(hex, { hex = it }, label = { Text("0x…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        bits, { bits = it.filter(Char::isDigit) }, label = { Text("Bits") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    OutlinedTextField(
                        freq, { freq = it.filter(Char::isDigit) }, label = { Text("Hz") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(pattern, { pattern = it }, label = { Text("9000,4500,560,… or Pronto") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { build()?.let(vm::sendCode) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Send, null); Text("Test", Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = { build()?.let { vm.saveButton(device.id, name, it, existing?.name); onDismiss() } },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.Check, null); Text("Save", Modifier.padding(start = 8.dp)) }
            }
        }
    }
}
