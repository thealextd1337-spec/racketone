package eu.advantly.ui.phone.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Player
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.wizard.DecidingSetChoice
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.SetsChoice
import eu.advantly.presentation.wizard.TennisMatchChoice
import eu.advantly.presentation.wizard.TiebreakAtChoice
import eu.advantly.presentation.wizard.WizardStep
import eu.advantly.presentation.wizard.WizardUiState
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.SegmentedChoice
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.ToggleRow
import eu.advantly.ui.phone.common.TopBar
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.common.sportColor
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_back
import eu.advantly.ui.phone.resources.action_close
import eu.advantly.ui.phone.resources.setup_one_game_hint
import eu.advantly.ui.phone.resources.setup_serve_stats
import eu.advantly.ui.phone.resources.wizard_add_guest
import eu.advantly.ui.phone.resources.wizard_add_guest_hint
import eu.advantly.ui.phone.resources.wizard_advanced
import eu.advantly.ui.phone.resources.wizard_coin_toss
import eu.advantly.ui.phone.resources.wizard_continue
import eu.advantly.ui.phone.resources.wizard_doubles
import eu.advantly.ui.phone.resources.wizard_empty_slot
import eu.advantly.ui.phone.resources.wizard_environment
import eu.advantly.ui.phone.resources.wizard_first_server
import eu.advantly.ui.phone.resources.wizard_format_invalid
import eu.advantly.ui.phone.resources.wizard_guests
import eu.advantly.ui.phone.resources.wizard_indoor
import eu.advantly.ui.phone.resources.wizard_mixed
import eu.advantly.ui.phone.resources.wizard_mixed_hint
import eu.advantly.ui.phone.resources.wizard_outdoor
import eu.advantly.ui.phone.resources.wizard_partner
import eu.advantly.ui.phone.resources.wizard_save_favorite
import eu.advantly.ui.phone.resources.wizard_save_favorite_hint
import eu.advantly.ui.phone.resources.wizard_scorer_phone
import eu.advantly.ui.phone.resources.wizard_scorer_watch
import eu.advantly.ui.phone.resources.wizard_sets_required
import eu.advantly.ui.phone.resources.wizard_singles
import eu.advantly.ui.phone.resources.wizard_skip
import eu.advantly.ui.phone.resources.wizard_start
import eu.advantly.ui.phone.resources.wizard_step_of
import eu.advantly.ui.phone.resources.wizard_summary
import eu.advantly.ui.phone.resources.wizard_summary_scorer
import eu.advantly.ui.phone.resources.wizard_swap_order
import eu.advantly.ui.phone.resources.wizard_team_order_hint_badminton
import eu.advantly.ui.phone.resources.wizard_team_order_hint_pickleball
import eu.advantly.ui.phone.resources.wizard_team_order_hint_tabletennis
import eu.advantly.ui.phone.resources.wizard_team_order_hint_tennis
import eu.advantly.ui.phone.resources.wizard_team_them
import eu.advantly.ui.phone.resources.wizard_team_us
import eu.advantly.ui.phone.resources.wizard_title_format
import eu.advantly.ui.phone.resources.wizard_title_players
import eu.advantly.ui.phone.resources.wizard_title_scorer
import eu.advantly.ui.phone.resources.wizard_title_sport
import eu.advantly.ui.phone.resources.wizard_title_surface
import eu.advantly.ui.phone.resources.wizard_title_venue
import eu.advantly.ui.phone.resources.wizard_you
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Match-Assistent H2–H5 (04 §3.1/§4.1): Sportart → Belag (Tennis) bzw. Halle/Draußen (Badminton) → Spieler →
 * Format → Wer zählt? → Start. Defaults und Abbildung auf `MatchFormat` liegen im [MatchWizard] (presentation).
 */
@Composable
fun WizardScreen(
    wizard: MatchWizard,
    onClose: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by wizard.state.collectAsState()
    // Uhr installiert/entfernt → Standard „Meine Uhr“ bzw. Rückfall auf „Dieses Handy“ (04 §3.1), reaktiv
    val watchStatus by wizard.watchStatus.collectAsState()
    LaunchedEffect(watchStatus) { wizard.setWatchStatus(watchStatus) }
    val colors = AdvantlyTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            // Statusleiste, Navigationsleiste und Tastatur (safeDrawing enthält ime)
            .safeContentPadding()
            .testTag("wizard"),
    ) {
        val first = state.stepIndex <= 0
        TopBar(
            title = stringResource(Res.string.wizard_step_of, state.stepIndex + 1, state.steps.size),
            onBack = { if (!wizard.back()) onClose() },
            backLabel = stringResource(if (first) Res.string.action_close else Res.string.action_back),
            backIcon = if (first) AdvantlyIcons.Close else AdvantlyIcons.ArrowBack,
        )
        Progress(state)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.s),
        ) {
            when (state.step) {
                WizardStep.SPORT -> SportStep(wizard)
                WizardStep.SURFACE -> SurfaceStep(wizard, state)
                WizardStep.PLAYERS -> PlayersStep(wizard, state)
                WizardStep.FORMAT -> FormatStep(wizard, state)
                WizardStep.SCORER -> ScorerStep(wizard, state)
            }
        }
        Footer(wizard, state, onStart)
    }
}

@Composable
private fun Progress(state: WizardUiState) {
    val colors = AdvantlyTheme.colors
    val accent = state.input.sport?.let { sportColor(it) } ?: colors.brand
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screenEdgeCompact).height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        state.steps.indices.forEach { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(if (i <= state.stepIndex) accent else colors.surface2, Radii.pillShape),
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = AdvantlyTheme.typography.headline,
        color = AdvantlyTheme.colors.text,
        modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.m).semantics { heading() },
    )
}

// --- H2 Sportart ---

@Composable
private fun SportStep(wizard: MatchWizard) {
    StepTitle(stringResource(Res.string.wizard_title_sport))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Sport.entries.forEach { sport ->
            SportCard(sport) { wizard.selectSport(sport) }
        }
    }
}

/** Sport-Karte (04 §5.6): Symbol + Name + Kurzinfo, Akzentkante – Tap = Auswahl + weiter. */
@Composable
private fun SportCard(
    sport: Sport,
    onClick: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    val accent = sportColor(sport)
    Row(
        Modifier
            .testTag("sport_${sport.name}")
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(accent.copy(alpha = 0.10f), Radii.sheetShape)
            .border(1.5.dp, accent.copy(alpha = 0.5f), Radii.sheetShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        SportIcon(sport, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(sportName(sport)), style = AdvantlyTheme.typography.title, color = colors.text)
            Text(stringResource(sportTagline(sport)), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
        Icon(AdvantlyIcons.ChevronRight, contentDescription = null, tint = colors.text2)
    }
}

// --- H3 Belag / Umgebung ---

@Composable
private fun SurfaceStep(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    val tennis = state.input.sport == Sport.TENNIS
    StepTitle(stringResource(if (tennis) Res.string.wizard_title_surface else Res.string.wizard_title_venue))
    SectionTitle(stringResource(Res.string.wizard_environment))
    SegmentedChoice(
        options = listOf(Environment.OUTDOOR, Environment.INDOOR),
        selected = state.input.environment,
        label = { stringResource(if (it == Environment.INDOOR) Res.string.wizard_indoor else Res.string.wizard_outdoor) },
        onSelect = wizard::setEnvironment,
        tagPrefix = "environment",
    )
    if (tennis) {
        Spacer(Modifier.height(Spacing.l))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TENNIS_SURFACES.forEach { surface ->
                SurfaceTile(surface, selected = state.input.surface == surface) { wizard.selectSurface(surface) }
            }
        }
    }
}

private val TENNIS_SURFACES = listOf(Surface.CLAY, Surface.HARD, Surface.GRASS, Surface.CARPET, Surface.ARTIFICIAL_GRASS)

/** Belag-Kachel: Textur-Swatch + Name + Kurzbeschreibung (04 §5.6, Swatches nur Illustration). */
@Composable
private fun SurfaceTile(
    surface: Surface,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Row(
        Modifier
            .testTag("surface_${surface.name}")
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(colors.surface, Radii.cardShape)
            .border(1.5.dp, if (selected) colors.brand else Color.Transparent, Radii.cardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(Modifier.size(40.dp).background(surfaceSwatch(surface), Radii.chipShape))
        Column(Modifier.weight(1f)) {
            Text(stringResource(surfaceName(surface)), style = AdvantlyTheme.typography.label, color = colors.text)
            Text(stringResource(surfaceDescription(surface)), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
        if (selected) Icon(AdvantlyIcons.Check, contentDescription = null, tint = colors.brand)
    }
}

// --- H4 Spieler ---

@Composable
private fun PlayersStep(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    val input = state.input
    val sport = input.sport ?: return
    StepTitle(stringResource(Res.string.wizard_title_players))
    if (state.options.disciplineSelectable) {
        SegmentedChoice(
            options = listOf(Discipline.SINGLES, Discipline.DOUBLES),
            selected = input.discipline,
            label = { stringResource(if (it == Discipline.SINGLES) Res.string.wizard_singles else Res.string.wizard_doubles) },
            onSelect = wizard::setDiscipline,
            tagPrefix = "discipline",
        )
    }
    val doubles = input.discipline == Discipline.DOUBLES
    for (side in Side.entries) {
        TeamBlock(wizard, state, side, doubles)
    }
    if (doubles) {
        Text(
            stringResource(teamOrderHint(sport)),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (state.options.mixedSelectable) {
            ToggleRow(
                title = stringResource(Res.string.wizard_mixed),
                subtitle = stringResource(Res.string.wizard_mixed_hint),
                checked = input.mixed,
                onChange = wizard::setMixed,
                tag = "toggle_mixed",
            )
        }
    }
    GuestPicker(wizard, state)
    if (state.playersComplete) FirstServerPicker(wizard, state)
}

@Composable
private fun TeamBlock(
    wizard: MatchWizard,
    state: WizardUiState,
    side: Side,
    doubles: Boolean,
) {
    val colors = AdvantlyTheme.colors
    val teamColor = if (side == Side.A) colors.teamUs else colors.teamThem
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(if (side == Side.A) Res.string.wizard_team_us else Res.string.wizard_team_them), Modifier.weight(1f))
        if (doubles) {
            val label = stringResource(Res.string.wizard_swap_order)
            Box(
                Modifier
                    .testTag("swap_${side.name}")
                    .size(Spacing.minTouchTarget)
                    .clickable(role = Role.Button) { wizard.swapOrder(side) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AdvantlyIcons.SwapVert, contentDescription = null, tint = colors.text2)
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, Radii.cardShape)
            .border(width = 1.5.dp, color = teamColor.copy(alpha = 0.6f), shape = Radii.cardShape)
            .padding(Spacing.xs),
    ) {
        state.input.team(side).forEachIndexed { index, player ->
            PlayerSlot(
                player = player,
                isSelf = side == Side.A && index == 0,
                isPartner = side == Side.A && index == 1,
                teamColor = teamColor,
                tag = "slot_${side.name}_$index",
                onClear = { wizard.clearSlot(side, index) },
            )
        }
    }
}

/** Spieler-Slot (04 §5.6): Initialen + Name; leer = „+“. Antippen leert ihn (außer dem eigenen). */
@Composable
private fun PlayerSlot(
    player: Player?,
    isSelf: Boolean,
    isPartner: Boolean,
    teamColor: Color,
    tag: String,
    onClear: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    val plainName = playerName(player)
    val name =
        when {
            player == null -> stringResource(Res.string.wizard_empty_slot)
            isSelf -> stringResource(Res.string.wizard_you, plainName)
            else -> plainName
        }
    Row(
        Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = player != null && !isSelf, role = Role.Button, onClick = onClear)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(
            Modifier.size(36.dp).background(if (player == null) colors.surface2 else teamColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (player == null) "+" else plainName.take(1).uppercase(),
                style = AdvantlyTheme.typography.label,
                color = if (player == null) colors.text2 else colors.onBrand,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = AdvantlyTheme.typography.label, color = if (player == null) colors.text2 else colors.text)
            if (isPartner && player != null) {
                Text(stringResource(Res.string.wizard_partner), style = AdvantlyTheme.typography.caption, color = colors.text2)
            }
        }
        if (player != null && !isSelf) Icon(AdvantlyIcons.Close, contentDescription = null, tint = colors.text3)
    }
}

/** Gäste: zuletzt verwendete als Chips, neue per Name (nur Name, später verknüpfbar). */
@Composable
private fun GuestPicker(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    if (state.playersComplete) return
    var name by rememberSaveable { mutableStateOf("") }
    val players by wizard.guests.collectAsState()
    SectionTitle(stringResource(Res.string.wizard_guests))
    val inLineup =
        state.input.players
            .map { it.id }
            .toSet()
    players.filter { it.id !in inLineup }.take(MAX_GUEST_CHIPS).chunked(2).forEach { row ->
        Row(Modifier.padding(bottom = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            row.forEach { guest ->
                ChoiceChip(
                    text = playerName(guest),
                    selected = false,
                    onClick = { wizard.pickPlayer(guest) },
                    tag = "guest_${guest.displayName}",
                )
            }
        }
    }
    val add = {
        wizard.addGuest(name)
        name = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.wizard_add_guest_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).testTag("guest_name_input"),
        )
        AdvantlyButton(
            text = stringResource(Res.string.wizard_add_guest),
            primary = false,
            enabled = name.isNotBlank(),
            onClick = add,
            modifier = Modifier.testTag("guest_add"),
        )
    }
}

private const val MAX_GUEST_CHIPS = 6

/** Erster Aufschlag: Chips je Spieler + Münzwurf (02 §5.1: Default „Ich“). */
@Composable
private fun FirstServerPicker(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    SectionTitle(stringResource(Res.string.wizard_first_server))
    val current = state.setup?.lineup?.firstServer ?: state.selfId
    val all = state.input.players
    all.chunked(2).forEach { row ->
        Row(Modifier.padding(bottom = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            row.forEach { player ->
                ChoiceChip(
                    text = playerName(player),
                    selected = player.id == current,
                    onClick = { wizard.setFirstServer(player.id) },
                    tag = "server_${player.id.value}",
                )
            }
        }
    }
    ChoiceChip(
        text = stringResource(Res.string.wizard_coin_toss),
        selected = false,
        onClick = { wizard.coinToss() },
        tag = "coin_toss",
    )
}

// --- H5 Format ---

@Composable
private fun FormatStep(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    val input = state.input
    val sport = input.sport ?: return
    val options = state.options
    val accent = sportColor(sport)
    StepTitle(stringResource(Res.string.wizard_title_format))
    if (sport.isTennisLike) TennisFormatChoices(wizard, state)

    if (input.tennisMatch == TennisMatchChoice.REGULAR || !sport.isTennisLike) {
        SectionTitle(stringResource(setsTitle(sport)))
        SegmentedChoice(
            options = options.sets,
            selected = input.sets,
            label = { setsLabel(it, sport) },
            onSelect = wizard::setSets,
            tagPrefix = "sets",
            accent = accent,
        )
        if (input.sets == null) {
            Text(
                stringResource(Res.string.wizard_sets_required),
                style = AdvantlyTheme.typography.caption,
                color = AdvantlyTheme.colors.hint,
                modifier = Modifier.padding(top = Spacing.xs).testTag("sets_required"),
            )
        }
    }
    if (options.gamesPerSet.isNotEmpty() && input.tennisMatch == TennisMatchChoice.REGULAR) {
        SectionTitle(stringResource(gamesTitle()))
        GamesPerSetChoices(wizard, state)
        if (input.gamesPerSet == 1) RuleHint(Res.string.setup_one_game_hint)
    }
    if (options.points.isNotEmpty()) {
        SectionTitle(stringResource(pointsTitle(sport)))
        SegmentedChoice(
            options = options.points,
            selected = input.pointsToWin.takeIf { it in options.points },
            label = { pointsLabel(it, sport) },
            onSelect = wizard::setPoints,
            tagPrefix = "points",
            accent = accent,
        )
    }
    if (sport == Sport.PICKLEBALL) {
        SectionTitle(stringResource(pickleballScoringTitle()))
        SegmentedChoice(
            options = options.pickleballScoring,
            selected = input.pickleballScoring,
            label = { pickleballScoringLabel(it) },
            onSelect = wizard::setPickleballScoring,
            tagPrefix = "pb_scoring",
            accent = accent,
        )
        RuleHint(pickleballScoringHint())
    }
    if (sport == Sport.PADEL && input.tennisMatch == TennisMatchChoice.REGULAR && options.deuceModes.isNotEmpty()) {
        DeuceChoice(wizard, state, accent)
    }

    AdvancedSection(wizard, state, accent)

    ToggleRow(
        title = stringResource(Res.string.wizard_save_favorite),
        subtitle = stringResource(Res.string.wizard_save_favorite_hint),
        checked = input.saveAsFavorite,
        onChange = wizard::setSaveAsFavorite,
        tag = "toggle_favorite",
        modifier = Modifier.padding(top = Spacing.l),
    )
    if (state.format != null && !state.canStart) {
        Text(
            stringResource(Res.string.wizard_format_invalid),
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.danger,
        )
    }
}

@Composable
private fun DeuceChoice(
    wizard: MatchWizard,
    state: WizardUiState,
    accent: Color,
) {
    SectionTitle(stringResource(deuceTitle()))
    SegmentedChoice(
        options = state.options.deuceModes,
        selected = state.input.deuceMode,
        label = { deuceLabel(it, state.input.sport) },
        onSelect = wizard::setDeuceMode,
        tagPrefix = "deuce",
        accent = accent,
    )
    RuleHint(deuceHint(state.input.deuceMode, state.input.sport))
}

@Composable
private fun RuleHint(text: StringResource) {
    Text(
        stringResource(text),
        style = AdvantlyTheme.typography.caption,
        color = AdvantlyTheme.colors.text2,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/** „Erweitert“ (eingeklappt): alle Varianten aus 02 §5.1 bzw. die nur-lesenden Regeln für B/TT (§5.1.1). */
@Composable
private fun AdvancedSection(
    wizard: MatchWizard,
    state: WizardUiState,
    accent: Color,
) {
    val input = state.input
    val sport = input.sport ?: return
    val options = state.options
    val colors = AdvantlyTheme.colors
    Row(
        Modifier
            .testTag("advanced_toggle")
            .fillMaxWidth()
            .padding(top = Spacing.l)
            .heightIn(min = Spacing.minTouchTarget)
            .clickable(role = Role.Button) { wizard.toggleAdvanced() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.wizard_advanced),
            style = AdvantlyTheme.typography.label,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (input.advancedExpanded) AdvantlyIcons.ChevronDown else AdvantlyIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text2,
        )
    }
    if (!input.advancedExpanded) return
    Column(Modifier.testTag("advanced_content")) {
        if (sport.isTennisLike) {
            ToggleRow(
                title = stringResource(Res.string.setup_serve_stats),
                subtitle = null,
                checked = state.input.serveStats,
                onChange = wizard::setServeStats,
                tag = "toggle_serve_stats",
            )
            if (input.tennisMatch != TennisMatchChoice.REGULAR) return@Column
        }
        when (sport) {
            Sport.TENNIS -> {
                DeuceChoice(wizard, state, accent)
                TennisLikeAdvanced(wizard, state, accent)
            }

            Sport.PADEL -> {
                TennisLikeAdvanced(wizard, state, accent)
            }

            Sport.PICKLEBALL -> {
                SectionTitle(stringResource(pickleballWinByTitle()))
                SegmentedChoice(
                    options = options.pickleballWinBy,
                    selected = input.pickleballWinBy,
                    label = { pickleballWinByLabel(it) },
                    onSelect = wizard::setPickleballWinBy,
                    tagPrefix = "pb_winby",
                    accent = accent,
                )
                SectionTitle(stringResource(pickleballMidTitle()))
                SegmentedChoice(
                    options = options.pickleballMidGame,
                    selected = input.pickleballMidGame,
                    label = { pickleballMidLabel(it) },
                    onSelect = wizard::setPickleballMidGame,
                    tagPrefix = "pb_mid",
                    accent = accent,
                )
            }

            Sport.BADMINTON -> {
                SectionTitle(stringResource(pointsTitle(sport)))
                SegmentedChoice(
                    options = options.points + options.advancedPoints,
                    selected = input.pointsToWin,
                    label = { pointsLabel(it, sport) },
                    onSelect = wizard::setPoints,
                    tagPrefix = "points_all",
                    accent = accent,
                )
                ReadOnlyRules(rallyRules(state))
            }

            Sport.TABLE_TENNIS -> {
                ReadOnlyRules(rallyRules(state))
            }
        }
    }
}

@Composable
private fun TennisLikeAdvanced(
    wizard: MatchWizard,
    state: WizardUiState,
    accent: Color,
) {
    val input = state.input
    val options = state.options
    if (input.sets != SetsChoice.Open && input.sets != SetsChoice.BestOf(1)) {
        SectionTitle(stringResource(decidingTitle(input.sport)))
        SegmentedChoice(
            options = options.decidingSets,
            selected = input.decidingSet,
            label = { decidingLabel(it, input.sport) },
            onSelect = wizard::setDecidingSet,
            tagPrefix = "deciding",
            accent = accent,
        )
    }
    if (options.tiebreakAt.isNotEmpty()) {
        SectionTitle(stringResource(tiebreakTitle()))
        SegmentedChoice(
            options =
                options.tiebreakAt.filter {
                    input.gamesPerSet > 1 ||
                        it != TiebreakAtChoice.AT_GAMES_MINUS_ONE
                },
            selected = input.tiebreakAt,
            label = { tiebreakLabel(it, input.gamesPerSet) },
            onSelect = wizard::setTiebreakAt,
            tagPrefix = "tiebreak",
            accent = accent,
        )
    }
}

@Composable
private fun ColumnScope.ReadOnlyRules(lines: List<String>) {
    lines.forEach { line ->
        Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text("•", style = AdvantlyTheme.typography.body, color = AdvantlyTheme.colors.text2)
            Text(line, style = AdvantlyTheme.typography.body, color = AdvantlyTheme.colors.text2)
        }
    }
}

// --- Wer zählt? ---

@Composable
private fun ScorerStep(
    wizard: MatchWizard,
    state: WizardUiState,
) {
    StepTitle(stringResource(Res.string.wizard_title_scorer))
    ScorerChooser(
        selected = state.input.effectiveScorer,
        watchStatus = state.input.watchStatus,
        watchWorkout = state.input.watchWorkout,
        onSelect = wizard::setScorer,
        onWatchWorkout = wizard::setWatchWorkout,
        onRecheck = wizard::refreshWatchStatus,
    )
}

/** „Meine Uhr“ / „Dieses Handy (Director)“ für Zusammenfassungen. */
@Composable
internal fun scorerLabel(option: ScorerOption): String =
    stringResource(if (option == ScorerOption.MY_WATCH) Res.string.wizard_scorer_watch else Res.string.wizard_scorer_phone)

// --- Fußleiste ---

@Composable
private fun Footer(
    wizard: MatchWizard,
    state: WizardUiState,
    onStart: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    if (state.step == WizardStep.SPORT) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        when (state.step) {
            WizardStep.SCORER -> {
                Text(stringResource(Res.string.wizard_summary).uppercase(), style = AdvantlyTheme.typography.overline, color = colors.text2)
                Text(
                    formatSummary(state),
                    style = AdvantlyTheme.typography.label,
                    color = colors.text,
                    modifier = Modifier.testTag("wizard_summary"),
                )
                Text(
                    stringResource(Res.string.wizard_summary_scorer, scorerLabel(state.input.effectiveScorer)),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                    modifier = Modifier.testTag("wizard_summary_scorer"),
                )
                AdvantlyButton(
                    text = stringResource(Res.string.wizard_start),
                    primary = true,
                    enabled = state.canStart,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().testTag("wizard_start"),
                )
            }

            WizardStep.SURFACE -> {
                val skip = state.input.sport == Sport.TENNIS && state.input.surface == null
                AdvantlyButton(
                    text = stringResource(if (skip) Res.string.wizard_skip else Res.string.wizard_continue),
                    primary = !skip,
                    onClick = { wizard.next() },
                    modifier = Modifier.fillMaxWidth().testTag("wizard_next"),
                )
            }

            else -> {
                AdvantlyButton(
                    text = stringResource(Res.string.wizard_continue),
                    primary = true,
                    enabled = state.canContinue,
                    onClick = { wizard.next() },
                    modifier = Modifier.fillMaxWidth().testTag("wizard_next"),
                )
            }
        }
    }
}

/** Bedeutung der Reihenfolge im Doppel je Sportart (02 §3.3). */
private fun teamOrderHint(sport: Sport) =
    when (sport) {
        Sport.TENNIS, Sport.PADEL -> Res.string.wizard_team_order_hint_tennis
        Sport.BADMINTON -> Res.string.wizard_team_order_hint_badminton
        Sport.PICKLEBALL -> Res.string.wizard_team_order_hint_pickleball
        Sport.TABLE_TENNIS -> Res.string.wizard_team_order_hint_tabletennis
    }
