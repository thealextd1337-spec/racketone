package eu.advantly.presentation.support

import eu.advantly.cloud.sync.SupabaseSupport
import eu.advantly.cloud.sync.SupportDraftStorage
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/** iOS besitzt noch keinen Konto-Host: Meldungen werden ausdrücklich als Gast versendet. */
fun createAppleSupport(): SupportViewModel {
    fun setting(name: String) =
        (NSBundle.mainBundle.objectForInfoDictionaryKey(name) as? String)
            .orEmpty()
            .takeUnless { it.startsWith("$(") }
            .orEmpty()
    val url = setting("AdvantlySupabaseURL")
    val key = setting("AdvantlySupabaseKey")
    val port = if (url.isNotBlank() && key.isNotBlank()) runCatching { SupabaseSupport(url, key) }.getOrNull() else null
    return SupportViewModel(port, AppleSupportDraftStorage())
}

/** App-privater Entwurf; enthält keine Auth-Tokens. */
private class AppleSupportDraftStorage : SupportDraftStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun loadDraft(ownerId: String?): String? = defaults.stringForKey("support_${ownerId ?: "guest"}")

    override fun loadReceipt(ownerId: String?): String? = defaults.stringForKey("support_receipt_${ownerId ?: "guest"}")

    override fun saveDraft(
        ownerId: String?,
        value: String?,
    ) {
        val key = "support_${ownerId ?: "guest"}"
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
        check(defaults.synchronize())
    }

    override fun saveReceipt(
        ownerId: String?,
        value: String,
    ) {
        defaults.setObject(value, forKey = "support_receipt_${ownerId ?: "guest"}")
        check(defaults.synchronize())
    }
}
