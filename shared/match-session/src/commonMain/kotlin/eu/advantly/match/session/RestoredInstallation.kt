package eu.advantly.match.session

import eu.advantly.data.LocalData
import eu.advantly.sync.protocol.PeerKind

/**
 * Recovery-Paket: übernommenes Abbild einmalig auf die neue Installation [token] umstellen, bevor eine Session entsteht.
 * Neue aktive Gerätequelle; das Handy erhält eine neue Pairing-Autorität, die Uhr wartet ohne Autorität auf ihre
 * Kopplung. Alter Bestand bleibt erhalten und gesperrt (aktiviertes Replikat): Freigabe nur per Login mit derselben UID,
 * ausdrücklicher Adoption oder – ausschließlich bei einem Abbild ohne jede Kontobindung – mit [releaseUnbound].
 */
fun rekeyRestoredInstallation(
    data: LocalData,
    kind: PeerKind,
    token: String,
    releaseUnbound: Boolean,
    clock: WallClock = SystemWallClock,
    ids: IdGenerator = RandomIdGenerator(),
) = data.transaction {
    val unbound = data.accountIdentities.retireRestored(kind.name)
    val scope = "installation:$token"
    val nowMs = clock.nowMs()
    data.accountIdentities.select(scope, null, nowMs, kind.name) { ids.newUuid7(nowMs).value }
    if (kind == PeerKind.PHONE) {
        data.settings.put("identity.authority", token)
        data.settings.put("identity.epoch", "1")
        data.settings.put("identity.wireScope", scope)
    }
    listOf(MatchSession.WATCH_MATCH_KEY, MatchSession.WATCH_MATCH_AT_KEY, MatchSession.DROPPED_NOTICE_KEY).forEach(data.settings::remove)
    data.replicas.activate(data.replicas.captureContext()) {}
    if (releaseUnbound && unbound) data.accountIdentities.releaseRestored(scope)
}
