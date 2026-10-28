package eu.advantly.ui.phone.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.Side
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.ui.phone.auth.AccountHeader
import eu.advantly.ui.phone.auth.AccountRights
import eu.advantly.ui.phone.auth.BackupStatusLine
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.friends.ShareDefaultSection
import eu.advantly.ui.phone.live.WatchMark
import eu.advantly.ui.phone.live.compactScore
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_cancel
import eu.advantly.ui.phone.resources.action_close
import eu.advantly.ui.phone.resources.app_version
import eu.advantly.ui.phone.resources.auth_sign_out
import eu.advantly.ui.phone.resources.auth_sign_out_explanation
import eu.advantly.ui.phone.resources.coming_soon
import eu.advantly.ui.phone.resources.coming_soon_friends
import eu.advantly.ui.phone.resources.consent_terms
import eu.advantly.ui.phone.resources.imprint_url
import eu.advantly.ui.phone.resources.link_copy
import eu.advantly.ui.phone.resources.link_failed_text
import eu.advantly.ui.phone.resources.link_failed_title
import eu.advantly.ui.phone.resources.mini_scorebar_a11y
import eu.advantly.ui.phone.resources.mini_scorebar_more
import eu.advantly.ui.phone.resources.privacy_url
import eu.advantly.ui.phone.resources.profile_account_privacy
import eu.advantly.ui.phone.resources.profile_imprint
import eu.advantly.ui.phone.resources.profile_legal
import eu.advantly.ui.phone.resources.profile_privacy
import eu.advantly.ui.phone.resources.profile_restore_previous
import eu.advantly.ui.phone.resources.support_open
import eu.advantly.ui.phone.resources.tab_friends
import eu.advantly.ui.phone.resources.tab_history
import eu.advantly.ui.phone.resources.tab_home
import eu.advantly.ui.phone.resources.tab_play
import eu.advantly.ui.phone.resources.tab_profile
import eu.advantly.ui.phone.resources.terms_url
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Bottom-Navigation mit fünf Zielen; die Mitte „Spielen (+)“ ist die Primäraktion (04 §2.1). */
@Composable
fun TabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTab.entries.forEach { tab ->
                val label = stringResource(tabLabel(tab))
                val showLabel = LocalDensity.current.fontScale < 1.5f
                val isSelected = tab == selected && tab != AppTab.Play
                Column(
                    Modifier
                        .weight(1f)
                        .testTag("tab_${tab.name.lowercase()}")
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics {
                            this.selected = isSelected
                            if (!showLabel) contentDescription = label
                        }.padding(vertical = Spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (tab == AppTab.Play) {
                        Box(Modifier.size(40.dp).background(colors.brand, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(AdvantlyIcons.Add, contentDescription = null, tint = colors.onBrand)
                        }
                    } else {
                        Icon(tabIcon(tab), contentDescription = null, tint = if (isSelected) colors.brand else colors.text2)
                    }
                    if (showLabel) {
                        Text(
                            label,
                            style = AdvantlyTheme.typography.caption.copy(fontSize = 11.sp),
                            color = if (isSelected) colors.text else colors.text2,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: AppTab): ImageVector =
    when (tab) {
        AppTab.Home -> AdvantlyIcons.Home
        AppTab.History -> AdvantlyIcons.History
        AppTab.Play -> AdvantlyIcons.Add
        AppTab.Friends -> AdvantlyIcons.Group
        AppTab.Profile -> AdvantlyIcons.Person
    }

private fun tabLabel(tab: AppTab): StringResource =
    when (tab) {
        AppTab.Home -> Res.string.tab_home
        AppTab.History -> Res.string.tab_history
        AppTab.Play -> Res.string.tab_play
        AppTab.Friends -> Res.string.tab_friends
        AppTab.Profile -> Res.string.tab_profile
    }

/** Mini-Scorebar über der Tab-Leiste („Now Playing“, 04 §2.1): Tap → Live-Score. */
@Composable
fun MiniScoreBar(
    snapshot: LiveMatchSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    more: Int = 0,
) {
    val colors = AdvantlyTheme.colors
    val teams = teamName(snapshot.setup, Side.A) + " – " + teamName(snapshot.setup, Side.B)
    val score = compactScore(snapshot)
    val a11y = stringResource(Res.string.mini_scorebar_a11y, teams, score)
    Row(
        modifier
            .testTag("mini_scorebar")
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            .heightIn(min = 56.dp)
            .background(colors.surface2, Radii.cardShape)
            .clickable(role = Role.Button, onClickLabel = a11y, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SportIcon(snapshot.setup.sport, size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(teams, style = AdvantlyTheme.typography.label, color = colors.text, maxLines = 1)
            Text(score, style = AdvantlyTheme.typography.scoreS, color = colors.text, modifier = Modifier.testTag("mini_scorebar_score"))
        }
        if (snapshot.onWatch) WatchMark(outdoor = false)
        if (more > 0) {
            Text(
                stringResource(Res.string.mini_scorebar_more, more),
                style = AdvantlyTheme.typography.caption,
                color = colors.text2,
                modifier = Modifier.testTag("mini_scorebar_more"),
            )
        }
        Icon(AdvantlyIcons.ChevronRight, contentDescription = null, tint = colors.text2)
    }
}

/** Platzhalter für Freunde in Builds ohne Backend („kommt bald“). */
@Composable
fun ComingSoonScreen(
    tab: AppTab,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
            .testTag("coming_soon_${tab.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(tabLabel(tab)),
            style = AdvantlyTheme.typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.coming_soon).uppercase(), style = AdvantlyTheme.typography.overline, color = colors.brand)
        Text(stringResource(Res.string.coming_soon_friends), style = AdvantlyTheme.typography.body, color = colors.text2)
    }
}

/**
 * Profil (0.3.0, schlank): oben Name, Benutzername, Freundescode und eine Statuszeile zur Sicherung; darunter Freunde,
 * „Puls & Schritte“, Standard fürs Teilen, Spielerliste (04 §3.2 „Namen & Aliase“), Hilfe und Abmelden. Export, Löschung
 * und Rechtstexte liegen aufklappbar unter „Konto & Datenschutz“. Ohne [account] (Build ohne Backend) nur die lokalen Teile.
 */
@Composable
fun ProfileScreen(
    versionName: String,
    modifier: Modifier = Modifier,
    players: PlayersPort? = null,
    onPlayerHistory: (eu.advantly.core.model.PlayerId) -> Unit = {},
    telemetry: TelemetrySettingsPort? = null,
    buildCommit: String = "",
    buildTimeUtc: String = "",
    account: AuthViewModel? = null,
    google: (suspend () -> GoogleCredential)? = null,
    onFriends: () -> Unit = {},
    onSupport: (() -> Unit)? = null,
    onRestorePrevious: (() -> Unit)? = null,
    onShareExport: (() -> Unit)? = null,
) {
    val colors = AdvantlyTheme.colors
    // Rechtliches ist Pflicht (Play-Freigabe); URLs je Sprache aus den Strings
    val openLink = rememberSafeLinkOpener()
    val privacyUrl = stringResource(Res.string.privacy_url)
    val termsUrl = stringResource(Res.string.terms_url)
    val imprintUrl = stringResource(Res.string.imprint_url)
    var accountOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
            .testTag("profile"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(tabLabel(AppTab.Profile)),
            style = AdvantlyTheme.typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        if (account != null) {
            account.friends?.let { AccountHeader(it) }
            BackupStatusLine(account)
            if (account.friends != null) LinkRow(stringResource(Res.string.tab_friends), "profile_friends", onFriends)
        }
        // Einstellung „Puls & Schritte“ (P2-J) – wirkt auf Uhr und Handy
        if (telemetry != null) TelemetryVisibilitySection(telemetry, Modifier.padding(top = Spacing.s))
        account?.friends?.let { ShareDefaultSection(it) }
        if (players != null) GuestList(players, onPlayerHistory)
        if (onSupport != null) LinkRow(stringResource(Res.string.support_open), "profile_support", onSupport)
        if (onRestorePrevious != null) {
            LinkRow(stringResource(Res.string.profile_restore_previous), "profile_restore_previous", onRestorePrevious)
        }
        if (account != null) {
            TextButton({ confirmSignOut = true }, modifier = Modifier.testTag("auth_sign_out")) {
                Text(stringResource(Res.string.auth_sign_out), color = colors.text)
            }
            Row(
                Modifier
                    .testTag("profile_account_privacy")
                    .fillMaxWidth()
                    .heightIn(min = Spacing.minTouchTarget)
                    .clickable(role = Role.Button) { accountOpen = !accountOpen }
                    .semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.profile_account_privacy).uppercase(),
                    style = AdvantlyTheme.typography.overline,
                    color = colors.text2,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (accountOpen) AdvantlyIcons.ChevronDown else AdvantlyIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.text2,
                )
            }
            if (accountOpen) {
                AccountRights(account, google, onShareExport)
                LinkRow(stringResource(Res.string.profile_privacy), "profile_privacy") { openLink(privacyUrl) }
                LinkRow(stringResource(Res.string.consent_terms), "profile_terms") { openLink(termsUrl) }
            }
        }
        Text(
            stringResource(Res.string.profile_legal).uppercase(),
            style = AdvantlyTheme.typography.overline,
            color = colors.text2,
            modifier = Modifier.padding(top = Spacing.l).semantics { heading() },
        )
        if (account == null) LinkRow(stringResource(Res.string.profile_privacy), "profile_privacy") { openLink(privacyUrl) }
        LinkRow(stringResource(Res.string.profile_imprint), "profile_imprint") { openLink(imprintUrl) }
        if (versionName.isNotBlank()) {
            SelectionContainer {
                Text(
                    stringResource(Res.string.app_version, versionDetails(versionName, buildCommit, buildTimeUtc)),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                    modifier = Modifier.testTag("profile_version"),
                )
            }
        }
    }
    if (confirmSignOut && account != null) {
        // Nach dem Abmelden zählt die App erst nach erneuter Anmeldung weiter (Pflichtkonto, 0.3.0).
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(Res.string.auth_sign_out)) },
            text = { Text(stringResource(Res.string.auth_sign_out_explanation)) },
            confirmButton = {
                TextButton({
                    confirmSignOut = false
                    account.signOut()
                }, modifier = Modifier.testTag("auth_sign_out_confirm")) { Text(stringResource(Res.string.auth_sign_out)) }
            },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text(stringResource(Res.string.account_cancel)) } },
            modifier = Modifier.testTag("auth_sign_out_dialog"),
        )
    }
}

/** Zeile, die eine Webseite im Browser öffnet (Rolle Link, ≥ 48 dp). */
@Composable
private fun LinkRow(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Row(
        Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .background(colors.surface, Radii.cardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AdvantlyTheme.typography.label, color = colors.text, modifier = Modifier.weight(1f))
        Icon(AdvantlyIcons.ChevronRight, contentDescription = null, tint = colors.text2)
    }
}

/**
 * Öffnet einen Link im Browser. Ohne Browser wirft Android eine `IllegalArgumentException` (`AndroidUriHandler`) –
 * statt abzustürzen zeigt die App dann einen Hinweis mit der URL zum Kopieren.
 */
@Composable
internal fun rememberSafeLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    var failedUrl by remember { mutableStateOf<String?>(null) }
    failedUrl?.let { url -> LinkFailedDialog(url, onClose = { failedUrl = null }) }
    return remember(uriHandler) {
        { url ->
            try {
                uriHandler.openUri(url)
            } catch (e: IllegalArgumentException) {
                failedUrl = url
            } catch (e: IllegalStateException) {
                failedUrl = url
            }
        }
    }
}

/** Hinweis „Link konnte nicht geöffnet werden“ mit markierbarer URL und „Link kopieren“. */
@Suppress("DEPRECATION") // LocalClipboardManager: setText ist plattformneutral, LocalClipboard braucht plattformeigene ClipEntry
@Composable
private fun LinkFailedDialog(
    url: String,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.link_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(Res.string.link_failed_text))
                SelectionContainer {
                    Text(url, style = AdvantlyTheme.typography.label, modifier = Modifier.testTag("link_failed_url"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(url))
                    onClose()
                },
                modifier = Modifier.testTag("link_copy"),
            ) { Text(stringResource(Res.string.link_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag("link_close")) { Text(stringResource(Res.string.action_close)) }
        },
        modifier = Modifier.testTag("link_failed_dialog"),
    )
}
