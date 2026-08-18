package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** One option in an allocation stream, and the share this wallet gives it. */
data class AllocationSlice(
    val name: String,
    val percent: Int,
)

/**
 * Allocations: where this wallet's two votes send their share of the emission.
 *
 * Two streams, shown as two stacked bars rather than two pie charts. A stacked
 * bar reads left to right at a glance and stays readable at three options or
 * ten; a pie needs a legend to say which wedge is which, and a legend is a
 * second thing to read.
 *
 * The eligibility line under each bar says why a stream is inactive, because
 * "0%" and "you have no say here yet" look identical otherwise.
 */
@Composable
fun AllocationScreen(
    humanShare: List<AllocationSlice>,
    capitalShare: List<AllocationSlice>,
    registered: Boolean,
    stakedUerth: Long,
    modifier: Modifier = Modifier,
    onEditHuman: () -> Unit = {},
    onEditCapital: () -> Unit = {},
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        Text(
            text = "Two of Earth's four emission streams are directed by vote. " +
                "One counts people, one counts stake — you can hold a say in both.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space24))
        StreamSection(
            title = "Human stream",
            detail = "One verified human, one vote.",
            eligibility = if (registered) null else "Register your identity to take part.",
            slices = humanShare,
            accent = EarthTheme.domain.anmlFg,
            onEdit = onEditHuman,
        )

        Spacer(Modifier.height(dimens.space24))
        StreamSection(
            title = "Capital stream",
            detail = "Weighted by the ERTH you have staked.",
            eligibility = if (stakedUerth > 0) null else "Stake ERTH to take part.",
            slices = capitalShare,
            accent = EarthTheme.domain.stakingFg,
            onEdit = onEditCapital,
        )
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun StreamSection(
    title: String,
    detail: String,
    eligibility: String?,
    slices: List<AllocationSlice>,
    accent: Color,
    onEdit: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                EarthColors.Surfaces.bgSecondary,
                RoundedCornerShape(EarthDimensions.Radius.radius3xl),
            )
            .padding(dimens.space16),
    ) {
        Text(
            text = title,
            style = EarthTypography.textMd,
            color = EarthColors.Text.textPrimary,
        )
        Text(
            text = detail,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        Spacer(Modifier.height(dimens.space12))

        if (eligibility != null) {
            Text(
                text = eligibility,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            return@Column
        }

        if (slices.isEmpty()) {
            Text(
                text = "Nothing allocated yet — your share sits unassigned.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            return@Column
        }

        // The stacked bar. Alpha steps the slices apart rather than a second
        // hue, so the section keeps one colour and the streams stay telling
        // apart from each other rather than within themselves.
        Row(
            Modifier
                .fillMaxWidth()
                .height(dimens.space12)
                .background(
                    EarthColors.Surfaces.bgPrimary,
                    RoundedCornerShape(dimens.space8),
                ),
        ) {
            slices.forEachIndexed { i, slice ->
                Box(
                    Modifier
                        .weight(slice.percent.toFloat().coerceAtLeast(0.01f))
                        .fillMaxSize()
                        .background(accent.copy(alpha = 1f - (i * 0.18f).coerceAtMost(0.7f))),
                )
            }
        }

        Spacer(Modifier.height(dimens.space12))
        slices.forEach { slice ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = slice.name,
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${slice.percent}%",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textSecondary,
                )
            }
        }
    }
}
