package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/**
 * A screen with a title bar.
 *
 * Zodl reaches for a Scaffold and TopAppBar for exactly this; Material's
 * TopAppBar brings its own scroll behaviour, elevation and colour roles, so
 * this is the same idea rebuilt on the tokens. Back is text rather than an icon
 * because there is one of them and it is easier to hit.
 */
@Composable
fun EarthScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(colors.Surfaces.bgPrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.gutter, vertical = dimens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    text = "‹",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.Text.textPrimary,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = dimens.space16),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.Text.textPrimary,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = dimens.gutter)
                .padding(bottom = dimens.space32),
            verticalArrangement = Arrangement.spacedBy(dimens.space8),
        ) { content() }
    }
}

/**
 * A bordered block grouping related figures.
 *
 * Bordered rather than shadowed: on a pure white ground a shadow is the only
 * thing separating a card from the page, and it disappears the moment anyone
 * screenshots it or turns contrast up.
 */
@Composable
fun EarthCard(
    modifier: Modifier = Modifier,
    background: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Box(
        modifier
            .fillMaxWidth()
            .background(
                background ?: colors.Surfaces.bgSecondary,
                RoundedCornerShape(dimens.radiusLg),
            )
            .padding(dimens.space16),
    ) { content() }
}
