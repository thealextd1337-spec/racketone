package eu.advantly.ui.phone.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing

/** Knopf: primär (Markenfarbe, dunkles Label, 6.8:1) oder sekundär (Fläche). Mindesthöhe 52 dp, wächst mit der Schrift. */
@Composable
fun AdvantlyButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AdvantlyTheme.colors
    val shape = Radii.pillShape
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(
                when {
                    !enabled -> colors.surface2.copy(alpha = 0.5f)
                    primary -> colors.brand
                    else -> colors.surface2
                },
                shape,
            ).border(1.dp, if (primary && enabled) Color.Transparent else colors.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AdvantlyTheme.typography.label.copy(fontSize = 16.sp),
            color =
                when {
                    !enabled -> colors.text3
                    primary -> colors.onBrand
                    else -> colors.text
                },
            textAlign = TextAlign.Center,
        )
    }
}

/** Kopfzeile mit Zurück-/Schließen-Knopf und Titel. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    backLabel: String,
    modifier: Modifier = Modifier,
    backIcon: ImageVector = AdvantlyIcons.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .testTag("top_back")
                    .size(Spacing.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(backIcon, contentDescription = null, tint = AdvantlyTheme.colors.text)
            }
        }
        Text(
            title,
            style = AdvantlyTheme.typography.title,
            color = AdvantlyTheme.colors.text,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.s).semantics { heading() },
            maxLines = 2,
        )
        actions()
    }
}

/** Überschrift einer Gruppe (Versalien, sekundär). */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        style = AdvantlyTheme.typography.overline,
        color = AdvantlyTheme.colors.text2,
        modifier = modifier.padding(top = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

/** Karte (Radius 14, analog Icon-Rundung), optional antippbar. */
@Composable
fun AdvantlyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, Radii.cardShape)
            .then(if (border != null) Modifier.border(1.5.dp, border, Radii.cardShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content,
    )
}

/**
 * Segment-Control (04 §5.6): 2–5 Optionen, gefüllte Auswahl in [accent] mit dunklem Label. Optionen umbrechen bei
 * großer Schrift, statt abgeschnitten zu werden.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    accent: Color = AdvantlyTheme.colors.brand,
) {
    val colors = AdvantlyTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface2, Radii.chipShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .testTag("${tagPrefix}_$index")
                    .heightIn(min = Spacing.minTouchTarget)
                    .background(if (isSelected) accent else Color.Transparent, Radii.chipShape)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = AdvantlyTheme.typography.label,
                    color = if (isSelected) colors.onBrand else colors.text,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Regel-Zeile (04 §5.6): Titel + 1-Satz-Erklärung + Schalter. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .testTag(tag)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text)
            if (subtitle != null) Text(subtitle, style = AdvantlyTheme.typography.caption, color = AdvantlyTheme.colors.text2)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = AdvantlyTheme.colors.brand),
        )
    }
}

/** Auswahl-Chip (Filter, Spieler, Erstaufschläger). */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    role: Role = Role.Checkbox,
) {
    val colors = AdvantlyTheme.colors
    Row(
        modifier
            .testTag(tag)
            .heightIn(min = Spacing.minTouchTarget)
            .background(if (selected) colors.brand.copy(alpha = 0.18f) else colors.surface2, Radii.pillShape)
            .border(1.5.dp, if (selected) colors.brand else Color.Transparent, Radii.pillShape)
            .clickable(role = role, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        leading?.invoke()
        Text(text, style = AdvantlyTheme.typography.label, color = colors.text)
    }
}

/** Stat-Kachel (04 §5.6): Wert groß, Label klein. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(AdvantlyTheme.colors.surface, Radii.cardShape)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = AdvantlyTheme.typography.scoreM, color = AdvantlyTheme.colors.text)
        Text(
            label,
            style = AdvantlyTheme.typography.caption,
            color = AdvantlyTheme.colors.text2,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Sport-Chip: Symbol + Name (Farbe nie allein). */
@Composable
fun SportBadge(
    sport: eu.advantly.core.model.Sport,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(sportColor(sport).copy(alpha = 0.14f), Radii.pillShape)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SportIcon(sport, size = 16.dp)
        Text(text.uppercase(), style = AdvantlyTheme.typography.overline, color = AdvantlyTheme.colors.text)
    }
}
