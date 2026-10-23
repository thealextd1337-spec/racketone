package eu.advantly.match.session

import eu.advantly.data.LocalData
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.IdentityEnvelope
import eu.advantly.sync.protocol.OwnerContext
import eu.advantly.sync.protocol.PeerKind

/** Persistente Autorität und Epochen; die Nonces gelten nur für diese laufende Instanz. */
internal class AccountPairing(
    private val data: LocalData,
    private val kind: PeerKind,
    private val newId: () -> String,
) {
    var nonce: String = newId()
        private set
    var context: OwnerContext? = loadContext()
        private set
    var responseRevision: Long = 0
        private set
    var revision: Long = 0
        private set
    private val confirmed = mutableMapOf<String, String>()
    private val candidates = mutableMapOf<String, Hello>()
    private val localNonces = mutableMapOf<String, String>()
    private val retired = mutableMapOf<String, MutableSet<String>>()

    /** Uhr: ein vertrautes Handy mit anderer Autorität wurde abgelehnt; nur [repair] koppelt neu. */
    var conflict: Boolean = false
        private set

    fun candidate(peer: String): Hello? = candidates[peer]

    /**
     * Ausdrückliche Neukopplung. Handy: neue Autorität – jede bisher gekoppelte Uhr muss selbst neu koppeln. Uhr: Autorität,
     * Epoche und Scope-Bindung vergessen; das nächste vertraute Handy koppelt neu. Beide verwerfen alle Nonces, Sitzungen und
     * Kandidaten. Quellen, Scopes und Konten bleiben unverändert. `false` ohne bestehende Kopplung.
     */
    fun repair(): Boolean {
        val current = context ?: return false
        data.transaction {
            if (kind == PeerKind.PHONE) {
                persist(OwnerContext(newId(), 1, current.scope))
            } else {
                listOf("identity.authority", "identity.epoch", "identity.wireScope").forEach(data.settings::remove)
                data.afterCommit { context = null }
            }
            data.afterCommit {
                nonce = newId()
                confirmed.clear()
                candidates.clear()
                localNonces.clear()
                retired.clear()
                conflict = false
                revision++
            }
        }
        return true
    }

    fun rotate(scope: String) {
        check(kind == PeerKind.PHONE)
        val authority = data.settings.getOrPut("identity.authority", newId)
        val epoch = data.settings.get("identity.epoch")?.toLongOrNull() ?: 0
        check(epoch < Long.MAX_VALUE)
        data.transaction {
            persist(OwnerContext(authority, epoch + 1, scope))
            data.afterCommit {
                nonce = newId()
                confirmed.clear()
                candidates.clear()
                localNonces.clear()
            }
        }
    }

    /** Ein bestehender Kanal wird durch ein verspätetes, unadressiertes Hello niemals zurückgesetzt. */
    fun observe(
        peer: String,
        hello: Hello,
        trusted: Boolean,
        selectScope: (String) -> Unit,
    ): Boolean {
        if (hello.identityProtocol != 1 || hello.session.isNullOrBlank() || !trusted) return false
        if (hello.session!!.length > 64 || hello.localSources.size > 256 || hello.adoptedSources.size > 256) return false
        if (kind == PeerKind.PHONE && hello.kind != PeerKind.WATCH) return false
        if (kind != PeerKind.PHONE && hello.kind != PeerKind.PHONE) return false
        hello.ownerContext?.let {
            if (it.authority.isBlank() || it.authority.length > 64 || it.scope.isBlank() || it.scope.length > 128 ||
                it.epoch <= 0
            ) {
                return false
            }
        }
        if (hello.session in retired[peer].orEmpty()) return false
        val incoming = hello.ownerContext
        val current = context
        if (kind == PeerKind.PHONE) {
            if (incoming != current || hello.recipientSession != nonce) {
                if (candidates[peer]?.session != hello.session && confirmed[peer] != hello.session) {
                    candidates[peer] = hello
                    nonce = newId()
                    responseRevision++
                }
                return false
            }
        } else {
            if (incoming != null && current != null && incoming.authority != current.authority) {
                conflict = true
                return false
            }
            if (incoming == null || hello.recipientSession != nonce || hello.kind != PeerKind.PHONE) {
                if (candidates[peer]?.session != hello.session && confirmed[peer] != hello.session) {
                    candidates[peer] = hello
                    responseRevision++
                }
                return false
            }
            if (current != null && (
                    incoming.authority != current.authority || incoming.epoch < current.epoch ||
                        (incoming.epoch == current.epoch && incoming != current)
                )
            ) {
                return false
            }
            if (incoming != current) {
                data.transaction {
                    selectScope(incoming.scope)
                    persist(incoming)
                }
                nonce = newId()
                confirmed.clear()
            }
        }
        val previous = confirmed[peer]
        if (previous != null && previous != hello.session) {
            retired.getOrPut(peer) { mutableSetOf() }.add(previous)
            if (kind != PeerKind.PHONE && incoming == current) nonce = newId()
        }
        if (confirmed[peer] != hello.session || localNonces[peer] != nonce) revision++
        conflict = false
        candidates[peer] = hello
        confirmed[peer] = checkNotNull(hello.session)
        localNonces[peer] = nonce
        return true
    }

    fun envelope(peer: String): IdentityEnvelope? =
        context?.let { owner ->
            confirmed[peer]?.let { IdentityEnvelope(owner, localNonces[peer] ?: nonce, it) }
        }

    fun accepts(
        peer: String,
        envelope: IdentityEnvelope?,
    ): Boolean =
        envelope != null && envelope.context == context && envelope.recipientSession == localNonces[peer] &&
            envelope.senderSession == confirmed[peer]

    private fun persist(value: OwnerContext) {
        data.settings.put("identity.authority", value.authority)
        data.settings.put("identity.epoch", value.epoch.toString())
        data.settings.put("identity.wireScope", value.scope)
        data.afterCommit { context = value }
    }

    private fun loadContext(): OwnerContext? {
        val authority = data.settings.get("identity.authority") ?: return null
        val epoch = data.settings.get("identity.epoch")?.toLongOrNull() ?: return null
        val scope = data.settings.get("identity.wireScope") ?: return null
        return OwnerContext(authority, epoch, scope)
    }
}
