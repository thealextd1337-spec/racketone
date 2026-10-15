package eu.advantly.ui.phone.wizard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.advantly.core.model.Side
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.presentation.wizard.PlayerSelection
import eu.advantly.presentation.wizard.WizardUiState
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.wizard_add_guest
import eu.advantly.ui.phone.resources.wizard_add_guest_hint
import eu.advantly.ui.phone.resources.wizard_change_place
import eu.advantly.ui.phone.resources.wizard_choose_place
import eu.advantly.ui.phone.resources.wizard_choose_player
import eu.advantly.ui.phone.resources.wizard_drag_hint
import eu.advantly.ui.phone.resources.wizard_empty_suggestions
import eu.advantly.ui.phone.resources.wizard_new_player
import eu.advantly.ui.phone.resources.wizard_place_action
import eu.advantly.ui.phone.resources.wizard_player_actions
import eu.advantly.ui.phone.resources.wizard_remove_player
import eu.advantly.ui.phone.resources.wizard_swap_order
import eu.advantly.ui.phone.resources.wizard_team_them
import eu.advantly.ui.phone.resources.wizard_team_us
import org.jetbrains.compose.resources.stringResource

private val PlayerPlaceSaver =
    listSaver<PlayerPlace?, String>(
        save = { place -> place?.let { listOf(it.side.name, it.index.toString()) }.orEmpty() },
        restore = { values -> values.takeIf { it.isNotEmpty() }?.let { PlayerPlace(Side.valueOf(it[0]), it[1].toInt()) } },
    )

/** Gemeinsamer Aktionspfad für Ziehen und die barrierearme Platzwahl per Tippen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPlaces(
    wizard: MatchWizard,
    state: WizardUiState,
    doubles: Boolean,
) {
    val colors = AdvantlyTheme.colors
    val current by rememberUpdatedState(state)
    val bounds = remember(state.input.discipline, state.input.sport) { mutableMapOf<PlayerPlace, Rect>() }
    var dragged by remember { mutableStateOf<PlayerPlace?>(null) }
    var target by remember { mutableStateOf<PlayerPlace?>(null) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var selected by rememberSaveable(state.input.discipline, state.input.sport, stateSaver = PlayerPlaceSaver) {
        mutableStateOf<PlayerPlace?>(null)
    }
    var destination by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val changePlace = stringResource(Res.string.wizard_change_place)
    val action = stringResource(Res.string.wizard_place_action)
    val hint = stringResource(Res.string.wizard_drag_hint)
    LaunchedEffect(state.input.discipline, state.input.sport) {
        dragged = null
        target = null
        delta = Offset.Zero
    }

    Side.entries.forEach { side ->
        val teamColor = if (side == Side.A) colors.teamUs else colors.teamThem
        Column(Modifier.zIndex(if (dragged?.side == side) 1f else 0f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    stringResource(if (side == Side.A) Res.string.wizard_team_us else Res.string.wizard_team_them),
                    Modifier.weight(1f),
                )
                if (doubles) {
                    val swap = stringResource(Res.string.wizard_swap_order)
                    TextButton(onClick = { wizard.swapOrder(side) }, modifier = Modifier.testTag("swap_${side.name}")) {
                        Icon(AdvantlyIcons.SwapVert, contentDescription = swap, tint = colors.text2)
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, Radii.cardShape)
                    .border(1.5.dp, teamColor.copy(alpha = 0.6f), Radii.cardShape)
                    .padding(Spacing.xs),
            ) {
                state.input.team(side).forEachIndexed { index, player ->
                    val place = PlayerPlace(side, index)
                    val label = placeLabel(state, place)
                    val moveTargets = PlayerSelection.validTargets(state.input, place, state.selfId)
                    val highlight by animateColorAsState(if (target == place && place != dragged) teamColor else colors.surface)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("slot_${side.name}_$index")
                            .zIndex(if (dragged == place) 1f else 0f)
                            .onGloballyPositioned { bounds[place] = it.boundsInRoot() }
                            .graphicsLayer {
                                if (dragged == place) {
                                    translationX = delta.x
                                    translationY = delta.y
                                    alpha = 0.85f
                                    shadowElevation = 4.dp.toPx()
                                }
                            }.background(highlight, Radii.chipShape)
                            .pointerInput(place, player?.id, doubles, moveTargets) {
                                if (moveTargets.isNotEmpty()) {
                                    var pointer = Offset.Zero

                                    fun targetAtPointer(): PlayerPlace? =
                                        bounds.entries.firstOrNull { it.key in moveTargets && it.value.contains(pointer) }?.key
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { local ->
                                            selected = null
                                            dragged = place
                                            delta = Offset.Zero
                                            pointer = (bounds[place]?.topLeft ?: Offset.Zero) + local
                                            target = targetAtPointer()
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            pointer += amount
                                            target = targetAtPointer()
                                            delta += amount
                                        },
                                        onDragEnd = {
                                            val validTargets = PlayerSelection.validTargets(current.input, place, current.selfId)
                                            val to = targetAtPointer()?.takeIf { it in validTargets }
                                            if (to != null) wizard.exchangePlayers(place, to)
                                            dragged = null
                                            target = null
                                            delta = Offset.Zero
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            target = null
                                            delta = Offset.Zero
                                        },
                                    )
                                }
                            }.clickable(enabled = player?.id != state.selfId || doubles, role = Role.Button, onClickLabel = action) {
                                selected = place
                                destination = false
                                creating = false
                                name = ""
                            }.semantics {
                                contentDescription = label
                                if (moveTargets.isNotEmpty()) {
                                    onLongClick(changePlace) {
                                        selected = place
                                        destination = true
                                        true
                                    }
                                }
                            }.padding(horizontal = Spacing.m, vertical = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        Box(Modifier.size(36.dp).background(teamColor, CircleShape), contentAlignment = Alignment.Center) {
                            if (player == null) {
                                Icon(AdvantlyIcons.Add, null, tint = colors.onBrand)
                            } else {
                                Text(playerName(player).take(1).uppercase(), color = colors.onBrand)
                            }
                        }
                        Text(
                            playerSlotName(state, place),
                            style = AdvantlyTheme.typography.label,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    if (doubles) {
        Text(hint, style = AdvantlyTheme.typography.caption, color = colors.text2, modifier = Modifier.padding(top = Spacing.s))
    }

    val place = selected ?: return
    val player = current.input.team(place.side).getOrNull(place.index)
    val moveTargets = PlayerSelection.validTargets(current.input, place, current.selfId)
    ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = colors.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.m)
                .testTag("player_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (player == null) {
                SectionTitle(stringResource(Res.string.wizard_choose_player))
                val suggestions = wizard.playerSuggestions()
                if (suggestions.isEmpty()) Text(stringResource(Res.string.wizard_empty_suggestions), color = colors.text2)
                suggestions.forEach { suggestion ->
                    TextButton(
                        onClick = {
                            wizard.assignPlayer(place, suggestion)
                            selected = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("suggestion_${suggestion.id.value}"),
                    ) { Text(playerName(suggestion)) }
                }
                if (!creating) {
                    AdvantlyButton(
                        stringResource(Res.string.wizard_new_player),
                        primary = true,
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth().testTag("player_create"),
                    )
                } else {
                    OutlinedTextField(
                        name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.wizard_add_guest_hint)) },
                        modifier = Modifier.fillMaxWidth().testTag("picker_name"),
                    )
                    AdvantlyButton(
                        stringResource(Res.string.wizard_add_guest),
                        primary = true,
                        enabled = name.isNotBlank(),
                        onClick = { if (wizard.addGuestAt(name, place) != null) selected = null },
                        modifier = Modifier.fillMaxWidth().testTag("picker_add"),
                    )
                }
            } else if (!destination) {
                SectionTitle(stringResource(Res.string.wizard_player_actions, playerName(player)))
                if (moveTargets.isNotEmpty()) {
                    AdvantlyButton(
                        changePlace,
                        primary = false,
                        onClick = { destination = true },
                        modifier = Modifier.fillMaxWidth().testTag("player_move"),
                    )
                }
                if (player.id != state.selfId) {
                    TextButton(
                        onClick = {
                            wizard.clearSlot(place.side, place.index)
                            selected = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("player_remove"),
                    ) {
                        Text(stringResource(Res.string.wizard_remove_player))
                    }
                }
            } else {
                SectionTitle(stringResource(Res.string.wizard_choose_place))
                moveTargets.forEach { to ->
                    TextButton(
                        onClick = {
                            wizard.exchangePlayers(place, to)
                            selected = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("destination_${to.side.name}_${to.index}"),
                    ) { Text(placeLabel(current, to)) }
                }
            }
        }
    }
}
