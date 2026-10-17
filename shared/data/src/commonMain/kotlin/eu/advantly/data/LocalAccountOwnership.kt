package eu.advantly.data

/** Ausschließlich lokal belegte Quellen; keine aus empfangenen Event-Logs abgeleitete Herkunft. */
data class AdoptionSource(
    val deviceId: String,
    val kind: String,
    val eventCount: Long,
)

/** Serielle Hostgrenze. Aktivierung erfolgt erst nach verifiziertem Profil, Sperre vor Auth-Netzwerkzugriff. */
interface LocalAccountOwnership {
    fun detachAccountSession() {}

    fun leaveAccount()

    fun activateAccount(
        profileId: String,
        session: CloudUploadSession,
    )

    fun adoptionSources(): List<AdoptionSource>

    fun adoptSources(
        session: CloudUploadSession,
        deviceIds: Set<String>,
    )
}
