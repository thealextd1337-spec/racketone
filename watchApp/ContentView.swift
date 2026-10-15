import AdvantlyWatchKit
import SwiftUI

/// Wurzel: ohne Match die Startliste, sonst Live-Match, Satzende-Dialog oder Matchende.
/// Ein laufendes Match wird nach dem App-Start aus dem Event-Log fortgesetzt.
struct ContentView: View {
    @ObservedObject private var model = ScoringModel.shared
    @Environment(\.isLuminanceReduced) private var dimmed
    @Environment(\.scenePhase) private var scenePhase
    /// `onAppear` an der Group feuert bei jedem Wechsel des Inhalts erneut – Startlogik nur einmal je Prozess
    @State private var launched = false
    #if DEBUG
    /// Ergebnis des Store-Smoke-Tests (Spike S1), -1 = nicht gelaufen.
    @State private var storeCount: Int64 = -1
    #endif

    var body: some View {
        Group {
            if let snap = model.snapshot {
                if snap.matchFinished {
                    MatchEndView(model: model, snap: snap)
                } else if snap.pendingDecision {
                    SetEndView(model: model, snap: snap)
                } else {
                    MatchPagerView(model: model, snap: snap)
                }
            } else {
                StartView(model: model, debugInfo: debugInfo)
            }
        }
        .allowsHitTesting(!dimmed && scenePhase == .active)
        .onChange(of: dimmed) { _, reduced in
            if reduced { model.dismissServeBar() } else { model.didWake() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.dismissServeBar() }
        }
        .onAppear {
            guard !launched else { return }
            launched = true
            #if DEBUG
            // vor dem Fortsetzen: der Demo-Modus schaltet die Workout-Session ab
            DemoRunner.runIfRequested(model: model)
            runStoreSmokeIfRequested()
            #endif
            model.resumeIfNeeded()
        }
    }

    private var debugInfo: String {
        #if DEBUG
        let engine = loc("watch.engine %@", AdvantlyWatch.shared.engineModule())
        return storeCount >= 0 ? engine + "\n" + loc("watch.store %@", String(storeCount)) : engine
        #else
        return ""
        #endif
    }

    #if DEBUG
    /// Nur Debug-Build UND Startparameter `-advantlySmoke` (setzt die CI beim `simctl launch`): öffnet den
    /// SQLDelight-Store (native-driver, Datei advantly.db), schreibt idempotent ein Smoke-Event und druckt die
    /// Zeile `ADVANTLY_SMOKE`, die die CI in der Konsole sucht. Release-Builds und normale Starts schreiben nichts.
    private func runStoreSmokeIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-advantlySmoke") else { return }
        let count = AdvantlyWatch.shared.store().smokeCheck()
        storeCount = count
        print("ADVANTLY_SMOKE eventstore=ok count=\(count)")
        fflush(stdout)
    }
    #endif
}
