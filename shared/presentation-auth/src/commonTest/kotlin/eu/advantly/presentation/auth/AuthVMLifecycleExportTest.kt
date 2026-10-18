package eu.advantly.presentation.auth

import androidx.lifecycle.ViewModelStore
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.SupportDraft
import eu.advantly.cloud.sync.SupportDraftStorage
import eu.advantly.cloud.sync.SupportIdentity
import eu.advantly.cloud.sync.SupportPort
import eu.advantly.cloud.sync.SupportReceipt
import eu.advantly.cloud.sync.SupportTicket
import eu.advantly.presentation.support.SupportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthVMLifecycleExportTest {
    private class Drain(
        private val scope: CoroutineScope,
        private val name: String,
        private val events: MutableList<String>,
        private val synchronous: Boolean,
    ) {
        val release = CompletableDeferred<Unit>()
        var callback: (() -> Unit)? = null

        fun close(onDrained: () -> Unit) {
            events += "$name:close"
            callback = onDrained
            if (synchronous) {
                onDrained()
            } else {
                scope.launch {
                    release.await()
                    onDrained()
                }
            }
            events += "$name:return"
        }
    }

    private class Upload(
        private val drain: Drain,
    ) : PhoneUploadPort {
        override val state = MutableStateFlow(PhoneUploadState())

        override fun uploadNow() = Unit

        override fun refreshStatus() = Unit

        override fun close(onDrained: () -> Unit) = drain.close(onDrained)
    }

    private class Export(
        private val drain: Drain,
    ) : PhoneExportPort {
        override val state = MutableStateFlow(PhoneExportState(PhoneExportPhase.DOWNLOADING))

        override fun requestExport() = Unit

        override fun retry() = Unit

        override fun refreshStatus() = Unit

        override fun download() = Unit

        override fun cancel() = Unit

        override fun discard() = Unit

        override fun close(onDrained: () -> Unit) = drain.close(onDrained)
    }

    @Test fun authWaitsForBothRealDrainsInEitherOrderAndIgnoresDuplicateCallbacks() {
        for (exportFirst in listOf(false, true)) {
            fixture { scope ->
                val events = mutableListOf<String>()
                val upload = Drain(scope, "upload", events, false)
                val export = Drain(scope, "export", events, false)
                val auth = closingAuth(events)
                clear(AuthViewModel(auth, scope, upload = Upload(upload), export = Export(export)))
                assertEquals(listOf("upload:close", "upload:return", "export:close", "export:return"), events)
                val first = if (exportFirst) export else upload
                val last = if (exportFirst) upload else export
                first.release.complete(Unit)
                first.callback!!.invoke()
                assertFalse("auth:close" in events)
                last.release.complete(Unit)
                first.callback!!.invoke()
                last.callback!!.invoke()
                assertEquals(1, events.count { it == "auth:close" })
                assertEquals("auth:close", events.last())
            }
        }
    }

    @Test fun synchronousCallbacksCannotCloseAuthBeforeBothPortsHaveBeenStopped() {
        for ((syncUpload, syncExport) in listOf(true to false, false to true, true to true)) {
            fixture { scope ->
                val events = mutableListOf<String>()
                val upload = Drain(scope, "upload", events, syncUpload)
                val export = Drain(scope, "export", events, syncExport)
                clear(AuthViewModel(closingAuth(events), scope, upload = Upload(upload), export = Export(export)))
                assertEquals(syncUpload && syncExport, "auth:close" in events)
                upload.release.complete(Unit)
                export.release.complete(Unit)
                upload.callback!!.invoke()
                export.callback!!.invoke()
                assertEquals(
                    listOf("upload:close", "upload:return", "export:close", "export:return", "auth:close"),
                    events,
                )
            }
        }
    }

    @Test fun absentAndSinglePortsKeepLegacyCloseBehavior() {
        for ((withUpload, withExport) in listOf(false to false, true to false, false to true)) {
            fixture { scope ->
                val events = mutableListOf<String>()
                val upload = Drain(scope, "upload", events, false)
                val export = Drain(scope, "export", events, false)
                clear(
                    AuthViewModel(
                        closingAuth(events),
                        scope,
                        upload = if (withUpload) Upload(upload) else null,
                        export = if (withExport) Export(export) else null,
                    ),
                )
                assertEquals(!withUpload && !withExport, "auth:close" in events)
                upload.release.complete(Unit)
                export.release.complete(Unit)
                upload.callback?.invoke()
                export.callback?.invoke()
                assertEquals(1, events.count { it == "auth:close" })
            }
        }
    }

    @Test fun busyExportLeavesAccountMutationsAndSupportFunctionalAndSupportStillCloses() =
        fixture { scope ->
            val events = mutableListOf<String>()
            val fake =
                FakePhoneAuth().also {
                    it.session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT)
                }
            val supportPort =
                object : SupportPort {
                    override val owners = MutableStateFlow<String?>("alice")

                    override suspend fun identity() = SupportIdentity("alice", null)

                    override suspend fun tickets(identity: SupportIdentity) = emptyList<SupportTicket>()

                    override suspend fun submit(
                        draft: SupportDraft,
                        identity: SupportIdentity,
                    ): SupportReceipt {
                        events += "support:submit"
                        return SupportReceipt(draft.id, "receipt")
                    }

                    override fun close() {
                        events += "support:close"
                    }
                }
            val storage =
                object : SupportDraftStorage {
                    override fun loadDraft(ownerId: String?): String? = null

                    override fun loadReceipt(ownerId: String?): String? = null

                    override fun saveDraft(
                        ownerId: String?,
                        value: String?,
                    ) = Unit

                    override fun saveReceipt(
                        ownerId: String?,
                        value: String,
                    ) = Unit
                }
            val support = SupportViewModel(supportPort, storage, scope)
            val drain = Drain(scope, "export", events, false)
            val model = AuthViewModel(closingAuth(events, fake), scope, support, export = Export(drain))
            assertTrue(
                model.export!!
                    .state.value.busy,
            )
            assertFalse(model.state.value.busy)
            model.decideConsent(false)
            model.requestDeletion()
            model.cancelDeletion()
            model.signOut()
            support.edit("bug", "Failure", "Steps", "", emptyMap())
            support.submit()
            assertEquals("receipt", support.state.value.receipt)
            assertTrue(fake.calls.containsAll(listOf("consent:false", "delete", "recover", "logout")))
            assertFalse(model.state.value.busy)
            clear(model)
            assertEquals(listOf("support:submit", "support:close", "export:close", "export:return"), events)
            drain.release.complete(Unit)
            assertEquals("auth:close", events.last())
        }

    private fun closingAuth(
        events: MutableList<String>,
        fake: FakePhoneAuth = FakePhoneAuth(),
    ): CloudAuthPort =
        object : CloudAuthPort by fake {
            override fun close() {
                events += "auth:close"
            }
        }

    private fun clear(model: AuthViewModel) {
        ViewModelStore().apply {
            put("account", model)
            clear()
        }
    }

    private fun fixture(test: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            test(scope)
        } finally {
            scope.cancel()
        }
    }
}
