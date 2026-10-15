#if DEBUG
import AdvantlyWatchKit
import Foundation

/// Nur Debug-Build: spielt mit dem Startparameter `-advantlyDemo <sport>` (z. B. `tennis`, `padel`, `pickleball`,
/// `badminton`, `table_tennis`) ein kurzes offenes Match automatisch durch – für CI-Screenshots im Simulator.
/// Druckt je Etappe `ADVANTLY_DEMO stage=<start|scoring|setend|matchend|done>` und hält den Bildschirm
/// `holdSeconds` lang, damit die CI per `simctl io screenshot` aufnehmen kann.
enum DemoRunner {
    static let holdSeconds: UInt64 = 8

    static func runIfRequested(model: ScoringModel) {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-advantlyDemo") else { return }
        let raw = index + 1 < args.count ? args[index + 1] : "tennis"
        let sport = raw.uppercased().replacingOccurrences(of: "-", with: "_")
        guard model.sports.contains(sport) else {
            mark("error", "unknown sport \(raw)")
            return
        }
        // Ohne Workout: Ein HealthKit-Dialog würde die Screenshots verdecken (Workout-Pfad siehe WorkoutManager)
        model.workoutEnabled = false
        Task { @MainActor in
            await run(sport: sport, model: model)
        }
    }

    @MainActor
    private static func run(sport: String, model: ScoringModel) async {
        // frischer Start: ein evtl. fortgesetztes Match aus der Anzeige nehmen (Log bleibt)
        model.closeMatch()
        await hold()
        mark("start", "sport=\(sport)")
        await hold()

        let presetId = sport + "_OPEN"
        let doubles = sport == "PADEL" || sport == "PICKLEBALL"
        guard model.start(presetId: presetId, doubles: doubles, weServeFirst: true) else {
            mark("error", "start failed \(presetId)")
            return
        }
        // Phase 1: Muster Wir, Wir, Gegner – bis ein Break-/Satz-/Matchball ansteht
        var taps = 0
        let attention: Set<String> = ["BREAK_POINT", "SET_POINT", "MATCH_POINT"]
        while let snap = model.snapshot, !snap.pendingDecision, !snap.matchFinished, taps < 400 {
            if snap.eventCount >= 12 && attention.contains(snap.hintCode) { break }
            model.tap(sideA: taps % 3 != 2, force: true)
            taps += 1
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        await hold(seconds: 1)
        let scoring = model.snapshot
        mark("scoring", "points=\(scoring?.pointsA ?? ""):\(scoring?.pointsB ?? "") hint=\(scoring?.hintCode ?? "")")
        await hold()

        // Phase 2: Satz zu Ende spielen → Satzende-Dialog (offener Modus)
        while let snap = model.snapshot, !snap.pendingDecision, !snap.matchFinished, taps < 800 {
            model.tap(sideA: true, force: true)
            taps += 1
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
        await hold(seconds: 1)
        mark("setend", "sets=\(model.snapshot.map { ScoreText.sets($0) } ?? "")")
        await hold()

        model.decide(anotherSet: false, matchTiebreak: false)
        await hold(seconds: 1)
        mark("matchend", "winner=\(model.snapshot?.winner ?? "") reason=\(model.snapshot?.resultReason ?? "")")
        await hold()

        model.closeMatch()
        mark("done", "taps=\(taps)")
    }

    private static func hold(seconds: UInt64 = holdSeconds) async {
        try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
    }

    private static func mark(_ stage: String, _ detail: String) {
        print("ADVANTLY_DEMO stage=\(stage) \(detail)")
        fflush(stdout)
    }
}
#endif
