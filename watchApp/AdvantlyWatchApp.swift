import SwiftUI
import WatchKit

/// watchOS-App (SwiftUI). Logik kommt aus dem KMP-Framework `AdvantlyWatchKit`.
@main
struct AdvantlyWatchApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
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
