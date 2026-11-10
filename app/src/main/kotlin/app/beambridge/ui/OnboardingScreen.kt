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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val PAGES = listOf(
    Page(Icons.Rounded.SettingsRemote, "Your phone is the remote", "BeamBridge uses this phone's IR blaster to control TVs, ACs and set-top boxes. Pair a device in under a minute."),
    Page(Icons.Rounded.Lan, "Control it from anywhere on Wi-Fi", "Turn on the bridge and every key gets a URL. Use it from the web dashboard, Home Assistant, Tasker or any script."),
    Page(Icons.Rounded.NotificationsActive, "Stay in the loop", "Allow notifications so the bridge can keep running in the background and confirm each command it beams."),
)

/** First-run only: three short pages, notification permission on the last one, then add the first device. */
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val last = pager.currentPage == PAGES.lastIndex

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.finishOnboarding(addDevice = false) }) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val p = PAGES[i]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    IconBadge(p.icon, size = 120.dp)
                    Spacer(Modifier.height(32.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(p.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (i == PAGES.lastIndex && Build.VERSION.SDK_INT >= 33) {
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Icon(Icons.Rounded.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Allow notifications")
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(PAGES.size) { i ->
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
