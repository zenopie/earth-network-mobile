package network.erth.wallet.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.concurrent.thread
import network.erth.wallet.notify.Notifications
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Which local notifications to post. While the system blocks them the
 * switches are disabled: a switch that can be on with nothing ever arriving
 * says something untrue.
 */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dimens = EarthTheme.dimens
    var allowed by remember { mutableStateOf(Notifications.systemAllowed(context)) }
    var anml by remember { mutableStateOf(Notifications.anmlEnabled(context)) }
    var proposals by remember { mutableStateOf(Notifications.proposalsEnabled(context)) }

    // Coming back from system settings is a resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = Notifications.systemAllowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .padding(horizontal = dimens.gutter),
    ) {
        network.erth.wallet.ui.components.HeroColumn {
            network.erth.wallet.ui.components.HeroIcon(network.erth.wallet.R.drawable.ic_notifications, muted = !allowed)
            network.erth.wallet.ui.components.HeroGap()
            network.erth.wallet.ui.components.HeroTitle("Notifications")
            network.erth.wallet.ui.components.HeroLine(if (allowed) "Only from this phone" else "Off")
        }
        Spacer(Modifier.height(dimens.space24))
        if (!allowed) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(EarthAccent.warnTint)
                    .padding(16.dp),
            ) {
                Text(
                    "Notifications are off for Earth Wallet in system settings.",
                    style = EarthTypography.textSm,
                    color = EarthAccent.warnInk,
                )
                Spacer(Modifier.height(dimens.space12))
                EarthButton(
                    text = "Open settings",
                    onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                        }
                        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = EarthButtonDefaults.secondaryColors(borderColor = androidx.compose.ui.graphics.Color.Unspecified),
                )
            }
            Spacer(Modifier.height(dimens.space16))
        }
        ToggleRow("ANML ready", "When the day's claim opens", anml, allowed) { on ->
            anml = on
            // Re-enabling opens the private wallet for its claim time.
            thread(isDaemon = true, name = "anml-alarms") { runCatching { Notifications.setAnmlEnabled(context, on) } }
        }
        ToggleRow("New proposals", "When a proposal opens for voting", proposals, allowed) { on ->
            proposals = on
            Notifications.setProposalsEnabled(context, on)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) EarthColors.Text.textPrimary else EarthColors.Text.textTertiary,
            )
            Text(detail, style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = EarthAccent.ink),
        )
    }
}
