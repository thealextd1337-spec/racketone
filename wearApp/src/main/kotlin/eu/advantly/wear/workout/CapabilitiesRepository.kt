package eu.advantly.wear.workout

import android.content.Context
import android.os.Build
import androidx.health.services.client.HealthServices
import androidx.health.services.client.getCapabilities
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.workout.HealthServicesMapping.toView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fragt `ExerciseClient.getCapabilitiesAsync()` beim App-Start ab und cached das Ergebnis pro Prozess
 * (05 §0.1). Nach einem Software-Update startet der Prozess ohnehin neu, damit wird automatisch neu abgefragt;
 * der Build-Fingerprint landet zur Nachvollziehbarkeit im Log.
 */
object CapabilitiesRepository {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<ExerciseCapabilitiesView?>(null)
    val capabilities: StateFlow<ExerciseCapabilitiesView?> = mutable.asStateFlow()

    suspend fun load(context: Context): ExerciseCapabilitiesView =
        mutex.withLock {
            mutable.value ?: run {
                val client = HealthServices.getClient(context.applicationContext).exerciseClient
                val view = client.getCapabilities().toView()
                DiagnosticLog.log(
                    "CAPABILITIES",
                    "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                    "sdk" to Build.VERSION.SDK_INT,
                    "fingerprint" to Build.FINGERPRINT,
                    "types" to view.supportedTypes.joinToString(","),
                )
                view.supportedTypes.forEach { type ->
                    DiagnosticLog.log("CAPABILITIES_TYPE", "type" to type, "metrics" to view.supportedMetrics(type).joinToString(","))
                }
                mutable.value = view
                view
            }
        }
}
