package eu.advantly.wear.glance

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
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
            val state = GlancePublisher.snapshot
            val copy = GlanceText.render(state, AndroidGlanceStrings(this))
            val content =
                LayoutElementBuilders.Column
                    .Builder()
                    .setWidth(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(getString(R.string.app_name), 14f))
                    .addContent(
                        LayoutElementBuilders.Spacer
                            .Builder()
                            .setHeight(dp(6f))
                            .build(),
                    ).addContent(button(copy.title, copy.lines, copy.primary, copy.description))
            if (state.live == null && copy.primary == GlanceAction.REPEAT) {
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
                            .setPadding(
                                ModifiersBuilders.Padding
                                    .Builder()
                                    .setAll(dp(18f))
                                    .build(),
                            ).setBackground(
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
                .addContent(text(title, 16f))
        lines.forEach { column.addContent(text(it, 12f)) }
        return LayoutElementBuilders.Box
            .Builder()
            .setWidth(expand())
            .setHeight(
                dp(
                    if (lines.isEmpty()) {
                        48f
                    } else if (action == GlanceAction.OPEN) {
                        124f
                    } else {
                        80f
                    },
                ),
            ).setModifiers(
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
    ): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text
            .Builder()
            .setText(value)
            .setMaxLines(1)
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
