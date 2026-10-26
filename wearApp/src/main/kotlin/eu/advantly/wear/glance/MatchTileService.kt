package eu.advantly.wear.glance

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.SCREEN_SHAPE_ROUND
import androidx.wear.protolayout.DimensionBuilders.WrappedDimensionProp
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import eu.advantly.wear.MainActivity
import eu.advantly.wear.R

/** Statisches ProtoLayout aus einem kleinen Cache; kein Sync, DB-Replay oder FGS-Start im Request. */
class MatchTileService : TileService() {
    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { result ->
            // Kaltstart: Prüfung asynchron, die Kachel folgt danach (kein Warten auf dem Main-Thread).
            installationGlance(this) { state, copy ->
                val device = requestParams.deviceConfiguration
                val metrics =
                    TileLayoutMetrics(
                        device.screenWidthDp,
                        device.screenHeightDp,
                        device.screenShape == SCREEN_SHAPE_ROUND,
                        device.fontScale,
                    )
                val live = state.live
                val lines =
                    if (live == null) {
                        copy.lines.take(metrics.setupLines)
                    } else {
                        (listOfNotNull(copy.status) + copy.tileLines).distinct().take(metrics.liveLines)
                    }
                val content =
                    LayoutElementBuilders.Column
                        .Builder()
                        .setWidth(dp(metrics.contentWidth))
                        .setHeight(wrap())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(
                            button(
                                copy.score ?: copy.title,
                                lines,
                                copy.primary,
                                copy.description,
                                metrics,
                                role = copy.title.takeIf { live != null },
                            ),
                        )
                if (live == null && copy.primary == GlanceAction.REPEAT) {
                    content.addContent(
                        LayoutElementBuilders.Spacer
                            .Builder()
                            .setHeight(dp(6f))
                            .build(),
                    )
                    content.addContent(
                        button(
                            getString(R.string.home_new_match),
                            emptyList(),
                            GlanceAction.NEW,
                            getString(R.string.home_new_match),
                            metrics,
                        ),
                    )
                }
                val root =
                    LayoutElementBuilders.Box
                        .Builder()
                        .setWidth(expand())
                        .setHeight(expand())
                        .setModifiers(
                            ModifiersBuilders.Modifiers
                                .Builder()
                                .setBackground(
                                    ModifiersBuilders.Background
                                        .Builder()
                                        .setColor(argb(BACKGROUND))
                                        .build(),
                                ).build(),
                        ).addContent(content.build())
                        .build()
                val layout =
                    LayoutElementBuilders.Layout
                        .Builder()
                        .setRoot(root)
                        .build()
                result.set(
                    TileBuilders.Tile
                        .Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setTileTimeline(
                            TimelineBuilders.Timeline
                                .Builder()
                                .addTimelineEntry(
                                    TimelineBuilders.TimelineEntry
                                        .Builder()
                                        .setLayout(layout)
                                        .build(),
                                ).build(),
                        ).build(),
                )
            }
            "Advantly tile"
        }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { result ->
            result.set(
                ResourceBuilders.Resources
                    .Builder()
                    .setVersion(RESOURCES_VERSION)
                    .build(),
            )
            "Advantly tile resources"
        }

    private fun button(
        title: String,
        lines: List<String>,
        action: GlanceAction,
        description: String,
        metrics: TileLayoutMetrics,
        role: String? = null,
    ): LayoutElementBuilders.Box {
        val launch =
            ActionBuilders.LaunchAction
                .Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity
                        .Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .addKeyToExtraMapping(
                            GlanceLaunch.EXTRA_ACTION,
                            ActionBuilders.AndroidStringExtra
                                .Builder()
                                .setValue(action.name)
                                .build(),
                        ).build(),
                ).build()
        val column =
            LayoutElementBuilders.Column
                .Builder()
                .setWidth(expand())
                .setHeight(wrap())
                .addContent(
                    text(
                        title,
                        if (role == null) TileLayoutMetrics.TITLE_SIZE else TileLayoutMetrics.SCORE_SIZE,
                        if (role == null) metrics.titleLines else 1,
                    ),
                )
        if (role != null) column.addContent(text(role, TileLayoutMetrics.DETAIL_SIZE))
        lines.forEach { column.addContent(text(it, TileLayoutMetrics.DETAIL_SIZE)) }
        return LayoutElementBuilders.Box
            .Builder()
            .setWidth(expand())
            .setHeight(WrappedDimensionProp.Builder().setMinimumSize(dp(48f)).build())
            .setModifiers(
                ModifiersBuilders.Modifiers
                    .Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable
                            .Builder()
                            .setId(action.name)
                            .setOnClick(launch)
                            .build(),
                    ).setSemantics(
                        ModifiersBuilders.Semantics
                            .Builder()
                            .setContentDescription(description)
                            .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON)
                            .build(),
                    ).setPadding(
                        ModifiersBuilders.Padding
                            .Builder()
                            .setAll(dp(6f))
                            .build(),
                    ).setBackground(
                        ModifiersBuilders.Background
                            .Builder()
                            .setColor(argb(SURFACE))
                            .setCorner(
                                ModifiersBuilders.Corner
                                    .Builder()
                                    .setRadius(dp(24f))
                                    .build(),
                            ).build(),
                    ).build(),
            ).addContent(column.build())
            .build()
    }

    private fun text(
        value: String,
        size: Float,
        maxLines: Int = 1,
    ): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text
            .Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setLineHeight(sp(size * TileLayoutMetrics.LINE_HEIGHT))
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle
                    .Builder()
                    .setSize(sp(size))
                    .setColor(argb(FOREGROUND))
                    .build(),
            ).build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val BACKGROUND = 0xFF000000.toInt()
        const val SURFACE = 0xFF29201A.toInt()
        const val FOREGROUND = 0xFFFFDDC8.toInt()
    }
}
