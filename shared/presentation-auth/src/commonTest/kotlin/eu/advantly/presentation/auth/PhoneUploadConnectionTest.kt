package eu.advantly.presentation.auth

import androidx.lifecycle.ViewModelStore
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneUploadConnectionTest {
    private class Upload : PhoneUploadPort {
        override val state = MutableStateFlow(PhoneUploadState(available = true, busy = true, phase = PhoneUploadPhase.UPLOADING))
        var stopped = false
        var drained: (() -> Unit)? = null

        override fun uploadNow() = Unit

        override fun refreshStatus() = Unit

        override fun close(onDrained: () -> Unit) {
            stopped = true
            drained = onDrained
        }
    }

    @Test fun busyUploadLeavesExportDeletionAndLogoutAvailableWithoutChangingAuthBusy() {
        val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
        val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), upload = Upload())
        assertFalse(model.state.value.busy)
        model.exportData()
        model.requestDeletion()
        model.cancelDeletion()
        model.signOut()
        assertTrue(auth.calls.containsAll(listOf("export", "delete", "recover", "logout")))
        assertFalse(model.state.value.busy)
    }

    @Test fun viewModelClearDelegatesAuthCloseUntilUploadHasFullyDrainedAndDefaultStillClosesDirectly() {
        for (withUpload in listOf(true, false)) {
            var closed = false
            val auth =
                object : CloudAuthPort by FakePhoneAuth() {
                    override fun close() {
                        closed = true
                    }
                }
            val upload = if (withUpload) Upload() else null
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), upload = upload)
            val store = ViewModelStore()
            store.put("account", model)
            store.clear()
            assertEquals(!withUpload, closed)
            if (upload != null) {
                assertTrue(upload.stopped)
                upload.drained!!.invoke()
                assertTrue(closed)
            }
        }
    }
}
