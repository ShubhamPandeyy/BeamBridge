package app.beambridge.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.beambridge.MainViewModel
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val INTRO = listOf(
    Page(Icons.Rounded.SettingsRemote, "Your phone is the remote", "BeamBridge uses this phone's IR blaster to control TVs, ACs and set-top boxes. Pair a device in under a minute."),
    Page(Icons.Rounded.Lan, "Control it from anywhere on Wi-Fi", "Turn on the bridge and every key gets a URL. Use it from the web dashboard, Home Assistant, Tasker or any script on your network."),
)

/** First-run only: intro pages, then a permissions page that says what each permission is for, then add the first device. */
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val total = INTRO.size + 1
    val pager = rememberPagerState { total }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var notif by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == 0) }
    var loc by remember { mutableStateOf(vm.hasLocationPermission()) }
    var notifBlocked by remember { mutableStateOf(false) }
    // If Android refuses to show the dialog (denied earlier, or "don't ask again"), the result comes back denied
    // immediately; from then on the button opens the app's notification settings instead of doing nothing.
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it; if (!it) notifBlocked = true }
    val askLoc = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { loc = it }
    val openNotifSettings = {
        ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    // Coming back from system settings: re-read the real state.
    LifecycleResumeEffect(Unit) {
        notif = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == 0
        loc = vm.hasLocationPermission()
        onPauseOrDispose {}
    }
    val last = pager.currentPage == total - 1

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.finishOnboarding(addDevice = false) }) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                if (i < INTRO.size) {
                    val p = INTRO[i]
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        IconBadge(p.icon, size = 120.dp)
                        Spacer(Modifier.height(32.dp))
                        Text(p.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(p.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("What BeamBridge asks for", style = MaterialTheme.typography.headlineMedium)
                    Text("Nothing here is required. Say no and the app still works, you just lose the feature next to it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PermissionCard(
                        Icons.Rounded.Notifications, "Notifications",
                        "Keeps the bridge running in the background (Android requires a visible notification for that) and shows a short \"Power on beamed to Living room TV\" confirmation when a command is sent.",
                        granted = notif, available = Build.VERSION.SDK_INT >= 33,
                        actionLabel = if (notifBlocked) "Open notification settings" else "Allow",
                    ) { if (notifBlocked) openNotifSettings() else askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    PermissionCard(
                        Icons.Rounded.Wifi, "Location (to read your Wi-Fi name)",
                        "Android only reveals the name of the Wi-Fi network you're on to apps that hold location permission. We use it for one thing: showing \"Wi-Fi: HomeNet\" on the dashboard. We never read your position, and nothing leaves your phone.",
                        granted = loc, available = true,
                    ) { askLoc.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                    PermissionCard(
                        Icons.Rounded.SettingsRemote, "Infrared transmitter",
                        "Sending IR needs no prompt: it's a normal permission granted at install. We only use it when you press a key, run a scene, or call the API.",
                        granted = true, available = false, always = true,
                    ) {}
                    PermissionCard(
                        Icons.Rounded.Lan, "Network access",
                        "Lets the phone host the web dashboard and API on your local network. Also granted at install. Turn on a password in Settings to restrict who can use it.",
                        granted = true, available = false, always = true,
                    ) {}
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(total) { i ->
                    val w by animateDpAsState(if (pager.currentPage == i) 24.dp else 8.dp, label = "dot")
                    Box(Modifier.padding(4.dp).height(8.dp).width(w).background(
                        if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                    ))
                }
            }
            Button(
                onClick = { if (last) vm.finishOnboarding(addDevice = true) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (last) "Add my first device" else "Next") }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector, title: String, why: String, granted: Boolean, available: Boolean, always: Boolean = false, actionLabel: String = "Allow", onAllow: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(icon, size = 40.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (granted) Icon(Icons.Rounded.CheckCircle, if (always) "Granted at install" else "Allowed", tint = MaterialTheme.colorScheme.primary)
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted && available) FilledTonalButton(onClick = onAllow) { Text(actionLabel) }
        }
    }
}
