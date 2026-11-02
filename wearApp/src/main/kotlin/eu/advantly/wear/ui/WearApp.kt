package eu.advantly.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.live.WatchUiState
import eu.advantly.wear.sync.ReceivedLiveViews
import eu.advantly.wear.workout.WorkoutPermissions
import eu.advantly.wear.workout.WorkoutUiState

/** Alle UI-Aktionen der App (Startliste, Wizard, Live). */
interface WatchActions :
    HomeActions,
    SetupActions,
    LiveActions

/** Wurzel der Wear-UI (Compose for Wear OS, Material 3). Texte aus res/values[-de]/strings.xml. */
@Composable
fun WearApp(
    state: WatchUiState,
    workout: WorkoutUiState,
    permissions: WorkoutPermissions,
    heartRate: Int?,
    isAmbient: Boolean,
    powerSave: Boolean,
    toast: WatchToast?,
    actions: WatchActions,
    glanceLaunch: Int = 0,
    onRestorePrevious: (() -> Unit)? = null,
    liveViews: ReceivedLiveViews? = null,
    subscribeLiveViews: () -> Unit = {},
) {
    MaterialTheme {
        AppScaffold {
            when (val screen = state.screen) {
                is WatchScreen.Home -> {
                    // Zuschauen hängt an der Startliste: wechselt der Controller den Screen, endet es (und damit das Abo).
                    var spectating by rememberSaveable { mutableStateOf(false) }
                    if (spectating) {
                        SpectateScreen(liveViews, isAmbient, subscribeLiveViews, onClose = { spectating = false })
                    } else {
                        HomeScreen(screen, permissions, powerSave, actions, onRestorePrevious, onSpectate = { spectating = true })
                    }
                }

                is WatchScreen.Setup -> {
                    SetupScreen(screen, actions)
                }

                is WatchScreen.Live -> {
                    LiveScreen(screen.live, workout, heartRate, isAmbient, toast, actions, glanceLaunch)
                }
            }
        }
    }
}
