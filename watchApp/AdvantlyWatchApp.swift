import SwiftUI
import WatchKit

/// watchOS-App (SwiftUI). Logik kommt aus dem KMP-Framework `AdvantlyWatchKit`.
@main
struct AdvantlyWatchApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { _, phase in
            // wieder im Vordergrund: sofort mit dem iPhone abgleichen (Anzeige: frischer Stand)
            if phase == .active { ScoringModel.shared.syncNow() }
        }
    }
}

final class AppDelegate: NSObject, WKApplicationDelegate {
    /// Absturz-Recovery der Workout-Session (05 §1.2); der Spielstand selbst kommt aus dem Event-Log.
    func handleActiveWorkoutRecovery() {
        let model = ScoringModel.shared
        model.workout.recover(matchId: model.scoring.activeMatchId())
        model.resumeIfNeeded()
    }
}
