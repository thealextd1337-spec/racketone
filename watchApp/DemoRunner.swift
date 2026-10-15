#if DEBUG
import AdvantlyWatchKit
import Foundation

/// Nur Debug-Build, für CI-Screenshots im Simulator. Startparameter:
/// `-advantlyDemoStage <start|scoring|setend|matchend>` und optional `-advantlyDemo <sport>` (Default `tennis`;
/// `padel`, `pickleball`, `badminton`, `table_tennis`).
///
/// Die App baut den Zustand der Etappe direkt nach dem Start auf (offenes Match der Sportart, Punkte ohne Pause),
/// bleibt dort stehen und druckt genau einmal `ADVANTLY_DEMO stage=<etappe> ready …`, sobald die Anzeige steht.
/// Die CI startet die App je Etappe neu und fotografiert erst nach diesem Marker aus dem aktuellen Launch-Log.
enum DemoRunner {
    enum Stage: String {
        case start
        case scoring
        case setend
        case matchend
    }

    static func runIfRequested(model: ScoringModel) {
        let args = ProcessInfo.processInfo.arguments
        guard let stageIndex = args.firstIndex(of: "-advantlyDemoStage") else { return }
        // Ohne Workout: Ein HealthKit-Dialog würde die Screenshots verdecken (Workout-Pfad siehe WorkoutManager)
        model.workoutEnabled = false
        let rawStage = stageIndex + 1 < args.count ? args[stageIndex + 1] : ""
        guard let stage = Stage(rawValue: rawStage) else {
            mark("error", "unknown stage \(rawStage)")
            return
        }
        let rawSport = args.firstIndex(of: "-advantlyDemo").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? "tennis"
        let sport = rawSport.uppercased().replacingOccurrences(of: "-", with: "_")
        guard model.sports.contains(sport) else {
            mark("error", "unknown sport \(rawSport)")
            return
        }
        Task { @MainActor in
            await run(stage: stage, sport: sport, model: model)
        }
    }

    @MainActor
    private static func run(stage: Stage, sport: String, model: ScoringModel) async {
        // definierter Ausgangspunkt: ein fortgesetztes Match aus der Anzeige nehmen (Log bleibt)
        model.closeMatch()
        if stage != .start {
            let presetId = sport + "_OPEN"
            let doubles = sport == "PADEL" || sport == "PICKLEBALL"
            guard model.start(presetId: presetId, doubles: doubles, weServeFirst: true) else {
                mark("error", "start failed \(presetId)")
                return
            }
            // Muster Wir, Wir, Gegner – bis ein Break-/Satz-/Matchball ansteht
            var taps = 0
            let attention: Set<String> = ["BREAK_POINT", "SET_POINT", "MATCH_POINT"]
            while let snap = model.snapshot, !snap.pendingDecision, taps < 400 {
                if snap.eventCount >= 12 && attention.contains(snap.hintCode) { break }
                model.tap(sideA: taps % 3 != 2, force: true)
                taps += 1
            }
            if stage == .setend || stage == .matchend {
                while let snap = model.snapshot, !snap.pendingDecision, !snap.matchFinished, taps < 800 {
                    model.tap(sideA: true, force: true)
                    taps += 1
                }
            }
            if stage == .matchend {
                model.decide(anotherSet: false, matchTiebreak: false)
            }
            guard expected(stage, model.snapshot) else {
                mark("error", "stage \(stage.rawValue) not reached after \(taps) taps")
                return
            }
        }
        // Hinweise (Seitenwechsel, Undo) laufen nach 3 s ab; danach steht die Anzeige still
        try? await Task.sleep(nanoseconds: 3_500_000_000)
        let snap = model.snapshot
        let detail = snap.map { s in
            "points=\(s.pointsA):\(s.pointsB) sets=\(ScoreText.sets(s)) hint=\(s.hintCode) status=\(s.status)"
        } ?? "startlist"
        mark(stage.rawValue + " ready", detail)
    }

    private static func expected(_ stage: Stage, _ snap: WatchScoreSnapshot?) -> Bool {
        guard let snap else { return false }
        switch stage {
        case .start: return false
        case .scoring: return snap.status == "IN_PROGRESS"
        case .setend: return snap.pendingDecision
        case .matchend: return snap.matchFinished
        }
    }

    private static func mark(_ stage: String, _ detail: String) {
        print("ADVANTLY_DEMO stage=\(stage) \(detail)")
        fflush(stdout)
    }
}
#endif
