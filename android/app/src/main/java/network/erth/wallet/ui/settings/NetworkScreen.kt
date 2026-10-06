package network.erth.wallet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.NodeConfig
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.component.EarthTextField
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Which node the wallet uses: Earth's, or the user's own.
 *
 * A node is saved only after it answers as earth-1, so a typo cannot leave
 * the wallet pointed at nothing (or at another chain) until the next launch.
 */
@Composable
fun NetworkScreen(onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = EarthTheme.dimens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val node by NodeConfig.node.collectAsStateWithLifecycle()

    var lcd by remember { mutableStateOf(if (node.isDefault) "" else node.lcd) }
    var rpc by remember { mutableStateOf(if (node.isDefault) "" else node.rpc) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // The node in use, as it answers now.
    LaunchedEffect(node) {
        status = null
        status = withContext(Dispatchers.IO) {
            runCatching { NodeConfig.probe(node) }.fold(
                { "Connected · ${it.chainId} · height ${it.height}" },
                { "Not answering: ${it.message}" },
            )
        }
    }

    val lcdUrl = NodeConfig.normalize(lcd)
    val rpcUrl = if (rpc.isBlank()) "" else NodeConfig.normalize(rpc)
    val lcdError = when {
        lcd.isBlank() -> null
        lcdUrl == null -> "Enter a URL, for example https://node.example.com"
        else -> NodeConfig.problem(lcdUrl)
    }
    val rpcError = when {
        rpc.isBlank() -> null
        rpcUrl == null -> "Enter a URL, or leave this empty"
        else -> NodeConfig.problem(rpcUrl)
    }
    val cleartext = (lcdUrl != null && lcdError == null && NodeConfig.isCleartext(lcdUrl)) ||
        (!rpcUrl.isNullOrEmpty() && rpcError == null && NodeConfig.isCleartext(rpcUrl))

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space24))
        Text(
            text = if (node.isDefault) "Earth's node" else "Your node",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = node.lcd,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = status ?: "Checking…",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        Spacer(Modifier.height(dimens.space16))
        Text(
            text = "Every balance, chain query and transaction goes through this node. " +
                "Run your own and nobody else sees which account asks what, or when. " +
                "The handle directory, the private-note index, the registration gas grant " +
                "and passport circuit downloads still come from api.erth.network.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        Spacer(Modifier.height(dimens.space24))
        EarthLabel("LCD (REST) URL")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = lcd,
            onValueChange = { lcd = it.trim(); error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://node.example.com") },
            error = lcdError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(dimens.space16))
        EarthLabel("RPC URL (optional)")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = rpc,
            onValueChange = { rpc = it.trim(); error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://rpc.example.com") },
            error = rpcError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = "Only the explorer uses the RPC, to read blocks in ranges. Without one it reads them from the LCD.",
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
        )

        if (cleartext) {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = "This is plain http://. Anyone on the same network can read and alter what the wallet " +
                    "sends and receives, including the balances it shows you. A payment to a @handle then goes " +
                    "ahead only when Earth's own directory confirms the address. Use it only for a node on this " +
                    "phone or on a network you control.",
                style = EarthTypography.textSm,
                color = EarthColors.Utility.ErrorRed.utilityError700,
            )
        }
        error?.let {
            Spacer(Modifier.height(dimens.space12))
            Text(text = it, style = EarthTypography.textSm, color = EarthColors.Utility.ErrorRed.utilityError700)
        }

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Check and use this node",
            enabled = lcdUrl != null && lcdError == null && rpcUrl != null && rpcError == null && !checking,
            isLoading = checking,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val candidate = NodeConfig.Node(lcdUrl ?: return@EarthButton, rpcUrl ?: return@EarthButton)
                checking = true
                error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { NodeConfig.probe(candidate) } }
                    checking = false
                    r.onSuccess {
                        NodeConfig.save(context, candidate)
                        onChanged()
                    }.onFailure { error = it.message ?: "That node did not answer." }
                }
            },
        )
        if (!node.isDefault) {
            Spacer(Modifier.height(dimens.space8))
            EarthButton(
                text = "Reset to default",
                modifier = Modifier.fillMaxWidth(),
                colors = EarthButtonDefaults.secondaryColors(),
                onClick = {
                    NodeConfig.reset(context)
                    lcd = ""
                    rpc = ""
                    error = null
                    onChanged()
                },
            )
        }
        Spacer(Modifier.height(dimens.space32))
    }
}
