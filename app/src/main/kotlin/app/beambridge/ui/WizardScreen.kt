package app.beambridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.WizardState
import app.beambridge.WizardStep
import app.beambridge.core.data.DeviceType
import app.beambridge.core.data.Datasets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(vm: MainViewModel, wizard: WizardState, datasets: Datasets, snackbar: SnackbarHostState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(wizard.type?.label ?: "New device") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            targetState = wizard.step,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "wizard-step",
        ) { step ->
            when (step) {
                WizardStep.Type -> TypeStep(vm)
                WizardStep.Brand -> BrandStep(vm, wizard, datasets)
                WizardStep.Test -> TestStep(vm, wizard)
                WizardStep.Name -> NameStep(vm, wizard)
            }
        }
    }
}

@Composable
private fun TypeStep(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(DeviceType.TV, DeviceType.AC, DeviceType.SET_TOP_BOX).forEach { type ->
            ElevatedCard(onClick = { vm.pickType(type) }, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    IconBadge(deviceIcon(type), size = 72.dp)
                    Text(type.label, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun BrandStep(vm: MainViewModel, wizard: WizardState, datasets: Datasets) {
    val providers by vm.stbProviders.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            wizard.query, vm::setQuery,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val q = wizard.query.trim()
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            when (wizard.type) {
                DeviceType.AC -> items(datasets.ac.manufacturers.filter { m -> m.name.contains(q, true) && m.remotes.any { it.supported } }, key = { it.id }) { m ->
                    BrandRow(m.name, m.remotes.count { it.supported }) { vm.pickAcBrand(m.id, m.name) }
                }
                DeviceType.SET_TOP_BOX -> items(providers, key = { it.id }) { p ->
                    BrandRow(p.title, null) { vm.pickProvider(p) }
                }
                else -> items(datasets.tv.manufacturers.filter { it.name.contains(q, true) }, key = { it.id }) { m ->
                    BrandRow(m.name, m.remotes.size) { vm.pickTvBrand(m.id, m.name) }
                }
            }
        }
    }
}

@Composable
private fun BrandRow(title: String, count: Int?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { if (count != null && count > 1) Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    )
}

@Composable
private fun TestStep(vm: MainViewModel, wizard: WizardState) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(wizard.brandName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TestButton(onClick = vm::sendTest)
                Text("${wizard.index + 1} / ${wizard.candidates.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::confirmWorked, modifier = Modifier.weight(1f).height(64.dp)) {
                Icon(Icons.Rounded.Check, null); Text("Yes, it worked", Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = vm::nextCandidate, modifier = Modifier.weight(1f).height(64.dp)) {
                Icon(Icons.Rounded.SkipNext, null); Text("Try Next Code", Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Oversized test button; its corners tighten with a spring while pressed. */
@Composable
private fun TestButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 40.dp else 96.dp, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "test-corner")
    Box(
        Modifier
            .size(192.dp)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = source, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.PowerSettingsNew, "Test", Modifier.size(88.dp), tint = MaterialTheme.colorScheme.onPrimary) }
}

@Composable
private fun NameStep(vm: MainViewModel, wizard: WizardState) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        IconBadge(deviceIcon(wizard.type ?: DeviceType.TV), size = 88.dp)
        OutlinedTextField(
            wizard.name, vm::setName, singleLine = true, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        )
        ExtendedFloatingActionButton(
            onClick = vm::saveWizardDevice,
            icon = { Icon(Icons.Rounded.Check, null) },
            text = { Text("Save") },
        )
    }
}
