/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

sealed interface StyledStringResource {
    data class ByStringResource(
        val resource: StringResource,
        val style: StyledStringStyle,
    ) : StyledStringResource

    data class ByResource(
        @param:StringRes val resource: Int,
        val style: StyledStringStyle,
        val args: List<Any>
    ) : StyledStringResource

    operator fun plus(
        other: StyledStringResource
    ): StyledStringResource = CompositeStyledStringResource(listOf(this, other))
}

private data class CompositeStyledStringResource(
    val resources: List<StyledStringResource>
) : StyledStringResource

@Stable
fun styledStringResource(
    value: String,
    style: StyledStringStyle = StyledStringStyle(),
): StyledStringResource =
    stringRes(value).withStyle(style)

infix fun StringResource.withStyle(style: StyledStringStyle = StyledStringStyle()): StyledStringResource =
    StyledStringResource.ByStringResource(resource = this, style = style)

@Stable
fun styledStringResource(
    @StringRes resource: Int,
    style: StyledStringStyle,
    vararg args: Any
): StyledStringResource =
    StyledStringResource.ByResource(
        resource = resource,
        style = style,
        args = args.toList()
    )

@Stable
fun styledStringResource(
    @StringRes resource: Int,
    color: StringResourceColor,
    fontWeight: FontWeight?,
    font: StyledStringFont?,
    vararg args: Any
): StyledStringResource =
    StyledStringResource.ByResource(
        resource = resource,
        style =
            StyledStringStyle(
                color = color,
                fontWeight = fontWeight,
                font = font
            ),
        args = args.toList()
    )

@Stable
fun styledStringResource(
    @StringRes resource: Int,
    fontWeight: FontWeight?,
    vararg args: Any
): StyledStringResource =
    StyledStringResource.ByResource(
        resource = resource,
        style =
            StyledStringStyle(
                color = StringResourceColor.PRIMARY,
                fontWeight = fontWeight
            ),
        args = args.toList()
    )

@Stable
fun styledStringResource(
    @StringRes resource: Int,
    color: StringResourceColor,
    vararg args: Any
): StyledStringResource =
    StyledStringResource.ByResource(
        resource = resource,
        style =
            StyledStringStyle(
                color = color,
                fontWeight = null
            ),
        args = args.toList()
    )

@Stable
fun styledStringResource(
    @StringRes resource: Int,
    vararg args: Any
): StyledStringResource =
    StyledStringResource.ByResource(
        resource = resource,
        style = StyledStringStyle(),
        args = args.toList()
    )

@Suppress("SpreadOperator")
@Composable
fun StyledStringResource.getValue() =
    when (this) {
        is StyledStringResource.ByStringResource -> {
            buildAnnotatedString {
                withStyle(style = style.toSpanStyle()) {
                    append(resource.getValue())
                }
            }
        }

        is StyledStringResource.ByResource -> {
            val argsStrings =
                args.map { arg ->
                    when (arg) {
                        is StringResource -> {
                            arg.getValue()
                        }

                        is StyledStringResource -> {
                            when (arg) {
                                is StyledStringResource.ByResource -> {
                                    stringRes(
                                        arg.resource,
                                        *arg.args.map { if (it is StringResource) it.getValue() else it }.toTypedArray()
                                    ).getValue()
                                }

                                is StyledStringResource.ByStringResource -> {
                                    arg.resource.getValue()
                                }

                                is CompositeStyledStringResource -> {
                                    arg.convertComposite()
                                }
                            }
                        }

                        else -> {
                            arg
                        }
                    }
                }

            val fullString = stringRes(resource, *argsStrings.toTypedArray()).getValue()

            buildAnnotatedString {
                withStyle(style = style.toSpanStyle()) {
                    append(fullString)
                }

                argsStrings.forEachIndexed { index, string ->
                    when (val res = args[index]) {
                        is StyledStringResource.ByResource -> {
                            addStyle(
                                fullString = fullString,
                                string = string.toString(),
                                style = res.style.toSpanStyle()
                            )
                        }

                        is StyledStringResource.ByStringResource -> {
                            addStyle(
                                fullString = fullString,
                                string = string.toString(),
                                style = res.style.toSpanStyle()
                            )
                        }
                    }
                }
            }
        }

        is CompositeStyledStringResource -> {
            convertComposite()
        }
    }

@Composable
private fun CompositeStyledStringResource.convertComposite(): AnnotatedString =
    buildAnnotatedString {
        resources.forEach {
            val value: AnnotatedString = it.getValue()
            append(value)
        }
    }

@Composable
private fun AnnotatedString.Builder.addStyle(
    fullString: String,
    string: String,
    style: SpanStyle
) {
    val startIndex = fullString.indexOf(string)

    addStyle(
        style = style,
        start = startIndex,
        end = startIndex + string.length
    )
}
