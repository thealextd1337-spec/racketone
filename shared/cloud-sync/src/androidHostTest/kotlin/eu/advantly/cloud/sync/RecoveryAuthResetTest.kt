package eu.advantly.cloud.sync

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/** Recovery-Paket Punkt 6: nur ein frischer Login bindet die neue Gerätequelle; nichts wird still neu ausgestellt. */
class RecoveryAuthResetTest {
    private fun prefs(values: MutableMap<String, String>): SharedPreferences {
        val removed = mutableSetOf<String>()
        val editor =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "remove" -> proxy.also { removed += args[0] as String }
                    "commit" -> true.also { values.keys.removeAll(removed) }
                    else -> error("unexpected ${method.name}")
                }
            } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            check(method.name == "edit") { "unexpected ${method.name}" }
            editor
        } as SharedPreferences
    }

    @Test
    fun recoveryDropsSessionAndInstallationButKeepsAgeGateSecretAndDrafts() {
        val values =
            mutableMapOf(
                "session" to "old-session",
                "cloud_installation_id_v1" to "old-installation",
                "cloud_installation_proof_v1" to "old-proof",
                "device_secret" to "age-gate",
                "support_guest" to "draft",
            )
        resetCloudIdentity(prefs(values))
        assertEquals(setOf("device_secret", "support_guest"), values.keys)
    }
}
