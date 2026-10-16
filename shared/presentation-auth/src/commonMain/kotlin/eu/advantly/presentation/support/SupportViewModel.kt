package eu.advantly.presentation.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.SupportDraft
import eu.advantly.cloud.sync.SupportDraftStorage
import eu.advantly.cloud.sync.SupportPort
import eu.advantly.cloud.sync.SupportTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class SupportState(
    val draft: SupportDraft? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val receipt: String? = null,
    val ownerId: String? = null,
    val tickets: List<SupportTicket> = emptyList(),
)

/** Entwürfe pro Konto, keine Tokens. Vor Versand dauerhaft einfrieren; unklare Antworten werden identisch wiederholt. */
class SupportViewModel(
    private val port: SupportPort?,
    private val storage: SupportDraftStorage,
    private val workScope: CoroutineScope? = null,
    private val makeDraft: (String?) -> SupportDraft = ::newSupportDraft,
) : ViewModel() {
    private val mutable = MutableStateFlow(SupportState())
    val state = mutable.asStateFlow()
    val available get() = port != null
    private val scope get() = workScope ?: viewModelScope
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var initialized = false

    init {
        scope.launch {
            try {
                switchOwner(port?.identity()?.ownerId)
                port?.owners?.collect { switchOwner(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = "support_storage_failed")
            }
        }
    }

    private fun switchOwner(owner: String?) {
        if (initialized && mutable.value.ownerId == owner) return
        initialized = true
        generation++
        mutable.value = SupportState(ownerId = owner)
        try {
            val saved = storage.loadDraft(owner)?.let { json.decodeFromString<SupportDraft>(it) }
            check(saved == null || saved.ownerId == owner)
            mutable.value = SupportState(draft = saved ?: makeDraft(owner), receipt = storage.loadReceipt(owner), ownerId = owner)
        } catch (_: Exception) {
            mutable.value = SupportState(ownerId = owner, error = "support_storage_failed")
        }
    }

    fun edit(
        category: String,
        title: String,
        body: String,
        contactEmail: String,
        diagnostics: Map<String, String>,
    ) {
        val current = mutable.value
        val draft = current.draft ?: return
        if (current.busy || draft.submitted) return
        val next =
            draft.copy(
                category = category,
                title = title.take(120),
                body = body.take(8000),
                contactEmail = contactEmail.take(254),
                diagnostics = diagnostics,
            )
        if (persist(next)) mutable.value = current.copy(draft = next, error = null, receipt = null)
    }

    fun discard() {
        if (mutable.value.busy) return
        val owner = mutable.value.ownerId
        try {
            storage.saveDraft(owner, null)
            mutable.value = mutable.value.copy(draft = makeDraft(owner), error = null, receipt = null)
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "support_storage_failed")
        }
    }

    fun submit() {
        val current = mutable.value
        val draft = current.draft ?: return
        if (current.busy) return
        if (port == null) {
            mutable.value = current.copy(error = "support_unavailable")
            return
        }
        if (draft.category !in setOf("feedback", "feature", "bug") || draft.title.isBlank() || draft.body.isBlank()) {
            mutable.value = current.copy(error = "support_invalid")
            return
        }
        val frozen = draft.copy(submitted = true)
        if (!persist(frozen)) return
        val epoch = generation
        mutable.value = current.copy(draft = frozen, busy = true, error = null, receipt = null)
        scope.launch {
            try {
                val identity = port.identity()
                if (identity.ownerId != frozen.ownerId || epoch != generation) throw CloudException("support_identity_changed", false)
                val receipt = port.submit(frozen, identity)
                check(receipt.id == frozen.id)
                try {
                    storage.saveReceipt(frozen.ownerId, receipt.receipt)
                } catch (_: Exception) {
                    if (epoch == generation) mutable.value = mutable.value.copy(error = "support_storage_failed")
                    return@launch
                }
                // Auch der Quell-Entwurf darf erst nach bestätigter identischer Vorgangsnummer entfernt werden.
                val persisted = storage.loadDraft(frozen.ownerId)?.let { json.decodeFromString<SupportDraft>(it) }
                if (persisted?.id == frozen.id) storage.saveDraft(frozen.ownerId, null)
                if (epoch == generation && port.identity().ownerId == frozen.ownerId) {
                    mutable.value = mutable.value.copy(draft = makeDraft(frozen.ownerId), receipt = receipt.receipt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch ==
                    generation
                ) {
                    mutable.value = mutable.value.copy(error = (e as? CloudException)?.reason ?: "support_connection_failed")
                }
            } finally {
                if (epoch == generation) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun refreshTickets() {
        if (port == null || mutable.value.busy) return
        val epoch = generation
        mutable.value = mutable.value.copy(busy = true, tickets = emptyList(), error = null)
        scope.launch {
            try {
                val identity = port.identity()
                val tickets = port.tickets(identity)
                if (epoch == generation &&
                    port.identity().ownerId == identity.ownerId
                ) {
                    mutable.value = mutable.value.copy(tickets = tickets)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == generation) mutable.value = mutable.value.copy(error = "support_connection_failed")
            } finally {
                if (epoch == generation) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    private fun persist(draft: SupportDraft): Boolean =
        try {
            storage.saveDraft(draft.ownerId, json.encodeToString(draft))
            true
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "support_storage_failed")
            false
        }

    fun close() {
        if (workScope == null) viewModelScope.cancel()
        port?.close()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newSupportDraft(owner: String?): SupportDraft =
    SupportDraft(
        id = Uuid.random().toString(),
        receiptSecret = (Uuid.random().toString() + Uuid.random().toString()).replace("-", ""),
        ownerId = owner,
    )
