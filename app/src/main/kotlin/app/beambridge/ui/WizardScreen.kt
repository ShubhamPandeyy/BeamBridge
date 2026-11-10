package app.beambridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.WizardState
import app.beambridge.WizardStep
import app.beambridge.core.data.DeviceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(vm: MainViewModel, w: WizardState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (w.step) {
                        WizardStep.Type -> "Add a device"
                        WizardStep.Custom -> "Custom device"
                        WizardStep.Brand -> "Pick the brand"
                        WizardStep.Power -> "Is it on right now?"
                        WizardStep.Test -> "Test codes"
                        WizardStep.Name -> "Name it"
                    })
                },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        val steps = WizardStep.entries.filter { it != WizardStep.Custom }
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (w.step != WizardStep.Custom) LinearProgressIndicator(
                progress = { (steps.indexOf(w.step) + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            AnimatedContent(w.step, transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() }, label = "step") { step ->
                when (step) {
                    WizardStep.Type -> TypeStep(vm)
                    WizardStep.Custom -> CustomStep(vm)
                    WizardStep.Brand -> BrandStep(vm, w)
                    WizardStep.Power -> PowerStep(vm, w)
                    WizardStep.Test -> TestStep(vm, w)
                    WizardStep.Name -> NameStep(vm, w)
                }
            }
        }
    }
}

@Composable
private fun TypeStep(vm: MainViewModel) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What are you controlling?", style = MaterialTheme.typography.titleMedium)
        listOf(
            Triple(DeviceType.TV, "TV", "Codes from 70+ brands"),
            Triple(DeviceType.AC, "Air conditioner", "Temperature, mode and fan"),
            Triple(DeviceType.SET_TOP_BOX, "Set-top box", "DTH and cable providers"),
            Triple(DeviceType.OTHER, "Custom device", "Name it and add your own IR codes"),
        ).forEach { (type, title, sub) ->
            OptionRow(deviceIcon(type), title, sub) { vm.pickType(type) }
        }
    }
}

/** Standard-height selectable row used across the wizard. */
@Composable
private fun OptionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(icon, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomStep(vm: MainViewModel) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("Power") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Device name") }, placeholder = { Text("e.g. Bedroom fan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("First key (optional, you can add more later)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(key, { key = it }, label = { Text("Key name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            code, { code = it }, label = { Text("IR code") }, placeholder = { Text("NEC 0x20DF10EF 32") },
            supportingText = { Text("Protocol + hex, raw µs timings, or Pronto hex") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { (label, value) -> AssistChip(onClick = { code = value }, label = { Text(label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.parseCode(code)?.let(vm::sendCode) }, enabled = code.isNotBlank()) { Text("Test") }
            Button(onClick = { vm.createCustom(name, key, code) }, enabled = name.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Create device") }
        }
    }
}

@Composable
private fun BrandStep(vm: MainViewModel, w: WizardState) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        OutlinedTextField(
            w.query, vm::setQuery, leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Search brands or providers") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (w.type != DeviceType.AC) item {
                ListItem(
                    headlineContent = { Text("I don't know the brand") },
                    supportingContent = { Text("Skip: we'll fire every TV code until yours reacts (TV finder)") },
                    leadingContent = { Icon(Icons.Rounded.HelpOutline, null) },
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { vm.unknownBrand() },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            items(w.brands, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.name) },
                    supportingContent = { Text("${b.remotes} remote${if (b.remotes == 1) "" else "s"}") },
                    trailingContent = { Icon(Icons.Rounded.KeyboardArrowRight, null) },
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { vm.pickBrand(b) },
                )
            }
            if (w.brands.isEmpty()) item {
                Text("No matches. Try the TV finder from a custom device, or add codes by hand.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PowerStep(vm: MainViewModel, w: WizardState) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Point the phone at your ${w.brand?.name ?: ""} device. We pick a safe test key based on its state.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OptionRow(Icons.Rounded.PowerSettingsNew, "It's off", "We'll send Power and it should turn on") { vm.setDeviceOn(false) }
        OptionRow(Icons.Rounded.VolumeUp, "It's on", "We'll send Volume up (or another harmless key)") { vm.setDeviceOn(true) }
        if (w.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun TestStep(vm: MainViewModel, w: WizardState) {
    val c = w.candidates.getOrNull(w.index) ?: return
    val key = vm.testKey() ?: "POWER"
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Code ${w.index + 1} of ${w.candidates.size}", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { (w.index + 1f) / w.candidates.size }, modifier = Modifier.fillMaxWidth(0.6f))
        Text(c.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        RemoteKey(key, onPress = vm::sendTest, onLongPress = vm::sendTest, size = 120.dp, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
        Text("Tap to send ${prettyName(key)}", style = MaterialTheme.typography.bodyLarge)
        Text("Did the device respond?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::nextCandidate, modifier = Modifier.weight(1f).height(52.dp)) { Text("No, try next") }
            Button(onClick = vm::confirmWorked, modifier = Modifier.weight(1f).height(52.dp)) { Text("Yes, it worked") }
        }
        if (w.candidates.size > 1 && w.index > 0) OutlinedButton(onClick = { vm.previousCandidate(); vm.sendTest() }) {
            Icon(Icons.Rounded.Replay, null); Spacer(Modifier.width(8.dp)); Text("Test previous code again")
        }
    }
}

@Composable
private fun NameStep(vm: MainViewModel, w: WizardState) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0.2f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f), label = "ok")
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(96.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            Text("It works!", style = MaterialTheme.typography.headlineSmall)
            Text("Code ${w.index + 1} of ${w.candidates.size} for ${w.brand?.name.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(w.name, vm::setName, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = vm::saveWizardDevice, enabled = w.name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save device") }
    }
}
