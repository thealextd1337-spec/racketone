package eu.advantly.presentation.auth

import androidx.lifecycle.ViewModelStore
import eu.advantly.cloud.sync.CloudAuthPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhonePullConnectionTest {
    private class Pull : PhonePullPort {
        override val state = MutableStateFlow(PhonePullState(available = true, busy = true))
        var drained: (() -> Unit)? = null

        override fun pullNow() = Unit

        override fun close(onDrained: () -> Unit) {
            drained = onDrained
        }
    }

    @Test fun viewModelClearClosesAuthOnlyAfterThePullHasFullyDrained() {
        var closed = false
        val auth =
            object : CloudAuthPort by FakePhoneAuth() {
                override fun close() {
                    closed = true
                }
            }
        val pull = Pull()
        val store = ViewModelStore()
        store.put("account", AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), pull = pull))
        store.clear()
        assertFalse(closed)
        pull.drained!!.invoke()
        assertTrue(closed)
    }
}
