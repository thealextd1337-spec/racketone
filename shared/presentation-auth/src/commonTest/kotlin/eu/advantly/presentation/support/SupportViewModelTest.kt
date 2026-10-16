package eu.advantly.presentation.support

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.SupportDraft
import eu.advantly.cloud.sync.SupportDraftStorage
import eu.advantly.cloud.sync.SupportIdentity
import eu.advantly.cloud.sync.SupportPort
import eu.advantly.cloud.sync.SupportReceipt
import eu.advantly.cloud.sync.SupportTicket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryDrafts : SupportDraftStorage {
    val values = mutableMapOf<String?, String?>()
    val receipts = mutableMapOf<String?, String>()
    var fail = false
    var failReceipt = false

    override fun loadDraft(ownerId: String?) = values[ownerId]

    override fun loadReceipt(ownerId: String?) = receipts[ownerId]

    override fun saveDraft(
        ownerId: String?,
        value: String?,
    ) {
        check(!fail)
        values[ownerId] = value
    }

    override fun saveReceipt(
        ownerId: String?,
        value: String,
    ) {
        check(!failReceipt)
        receipts[ownerId] = value
    }
}

private class FakeSupport : SupportPort {
    override val owners = MutableStateFlow<String?>("alice")
    val sent = mutableListOf<SupportDraft>()
    var failure = false
    var pending: CompletableDeferred<Unit>? = null

    override suspend fun identity() = SupportIdentity(owners.value, null)

    override suspend fun submit(
        draft: SupportDraft,
        identity: SupportIdentity,
    ): SupportReceipt {
        sent += draft
        pending?.await()
        if (failure) throw CloudException("support_connection_failed", false)
        return SupportReceipt(draft.id, "ADV-${draft.id}")
    }

    override suspend fun tickets(identity: SupportIdentity): List<SupportTicket> {
        pending?.await()
        return listOf(SupportTicket("ticket", "Private", "Text", "bug", "new", "today"))
    }
}

class SupportViewModelTest {
    private fun fixture(
        initialOwner: String? = "alice",
        test: (SupportViewModel, FakeSupport, MemoryDrafts, CoroutineScope) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val port = FakeSupport().apply { owners.value = initialOwner }
        val store = MemoryDrafts()
        val model = SupportViewModel(port, store, scope)
        try {
            test(model, port, store, scope)
        } finally {
            scope.cancel()
        }
    }

    @Test fun retryAfterRestartReusesFrozenIdentityAndContent() =
        fixture { model, port, store, scope ->
            model.edit("bug", "Failure", "Steps", "", emptyMap())
            port.failure = true
            model.submit()
            assertNull(model.state.value.receipt)
            assertTrue(
                model.state.value.draft!!
                    .submitted,
            )
            model.edit("feedback", "Changed", "Changed", "", emptyMap())
            assertEquals(
                "Failure",
                model.state.value.draft!!
                    .title,
            )
            val restarted = SupportViewModel(port, store, scope)
            port.failure = false
            restarted.submit()
            assertEquals(port.sent[0], port.sent[1])
            assertEquals("ADV-${port.sent[0].id}", restarted.state.value.receipt)
            assertEquals("ADV-${port.sent[0].id}", store.receipts["alice"])
            assertNull(store.values["alice"])
            assertFalse(
                restarted.state.value.draft!!
                    .submitted,
            )
        }

    @Test fun storageFailurePreventsNetworkAndSuccess() =
        fixture { model, port, store, _ ->
            model.edit("bug", "Failure", "Steps", "", emptyMap())
            store.fail = true
            model.submit()
            assertTrue(port.sent.isEmpty())
            assertEquals("support_storage_failed", model.state.value.error)
            assertNull(model.state.value.receipt)
        }

    @Test fun receiptWriteFailureKeepsFrozenDraftForIdempotentRetry() =
        fixture { model, port, store, _ ->
            model.edit("bug", "Failure", "Steps", "", emptyMap())
            store.failReceipt = true
            model.submit()

            assertEquals(1, port.sent.size)
            assertTrue(store.values["alice"]!!.let { "\"submitted\":true" in it })
            assertEquals("support_storage_failed", model.state.value.error)
            assertNull(store.receipts["alice"])

            store.failReceipt = false
            model.submit()

            assertEquals(port.sent[0], port.sent[1])
            assertEquals("ADV-${port.sent[0].id}", store.receipts["alice"])
            assertNull(store.values["alice"])
            assertEquals("ADV-${port.sent[0].id}", model.state.value.receipt)
        }

    @Test fun guestReceiptSurvivesOwnerSwitchAndRestartWithoutLeaking() =
        fixture(initialOwner = null) { model, port, store, scope ->
            model.edit("bug", "Guest issue", "Steps", "", emptyMap())
            val pending = CompletableDeferred<Unit>()
            port.pending = pending
            model.submit()
            val expectedReceipt = "ADV-${model.state.value.draft!!.id}"

            port.owners.value = "alice"
            assertNull(model.state.value.receipt)
            pending.complete(Unit)

            assertNull(model.state.value.receipt)
            assertEquals(expectedReceipt, store.receipts[null])
            assertNull(store.receipts["alice"])

            port.owners.value = null
            assertEquals(expectedReceipt, model.state.value.receipt)
            val restarted = SupportViewModel(port, store, scope)
            assertEquals(expectedReceipt, restarted.state.value.receipt)
        }

    @Test fun ownerSwitchHidesOldDraftAndLateAcknowledgment() =
        fixture { model, port, _, _ ->
            model.edit("bug", "Alice secret", "Steps", "", emptyMap())
            val pending = CompletableDeferred<Unit>()
            port.pending = pending
            model.submit()
            port.owners.value = "bob"
            assertEquals(
                "",
                model.state.value.draft!!
                    .title,
            )
            model.edit("feedback", "Bob draft", "New", "", emptyMap())
            pending.complete(Unit)
            assertEquals(
                "Bob draft",
                model.state.value.draft!!
                    .title,
            )
            assertNull(model.state.value.receipt)
            assertFalse(model.state.value.busy)
        }

    @Test fun lateAckDoesNotEraseNewDraftAfterReturningToSameAccount() =
        fixture { model, port, store, scope ->
            model.edit("bug", "Old", "Steps", "", emptyMap())
            val pending = CompletableDeferred<Unit>()
            port.pending = pending
            model.submit()
            val oldId =
                model.state.value.draft!!
                    .id
            port.owners.value = "bob"
            port.owners.value = "alice"
            model.discard()
            model.edit("feedback", "New", "Keep", "", emptyMap())
            assertNotEquals(
                oldId,
                model.state.value.draft!!
                    .id,
            )
            pending.complete(Unit)
            val restarted = SupportViewModel(port, store, scope)
            assertEquals(
                "New",
                restarted.state.value.draft!!
                    .title,
            )
            assertEquals("ADV-$oldId", restarted.state.value.receipt)
            assertEquals("ADV-$oldId", store.receipts["alice"])
            assertNull(model.state.value.receipt)
        }

    @Test fun lateTicketListNeverAppearsForAnotherOwner() =
        fixture { model, port, _, _ ->
            val pending = CompletableDeferred<Unit>()
            port.pending = pending
            model.refreshTickets()
            port.owners.value = "bob"
            pending.complete(Unit)
            assertTrue(
                model.state.value.tickets
                    .isEmpty(),
            )
        }

    @Test fun diagnosticsAreOnlySentAfterExplicitSelection() =
        fixture { model, port, _, _ ->
            model.edit("feedback", "Title", "Body", "", emptyMap())
            model.submit()
            assertTrue(
                port.sent
                    .single()
                    .diagnostics
                    .isEmpty(),
            )
            model.edit("feature", "Title", "Body", "", mapOf("os" to "Android"))
            model.submit()
            assertEquals(mapOf("os" to "Android"), port.sent.last().diagnostics)
        }
}
