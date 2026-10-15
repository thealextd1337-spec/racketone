package eu.advantly.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.live.WatchUiState
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
) {
    MaterialTheme {
        AppScaffold {
            when (val screen = state.screen) {
                is WatchScreen.Home -> HomeScreen(screen, permissions, powerSave, actions)
                is WatchScreen.Setup -> SetupScreen(screen, actions)
                is WatchScreen.Live -> LiveScreen(screen.live, workout, heartRate, isAmbient, toast, actions, glanceLaunch)
            }
        }
    }
}
