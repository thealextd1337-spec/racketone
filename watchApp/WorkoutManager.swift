import Foundation
import HealthKit

/// Workout-Session als Laufzeit-Anker (05 §1.2, E2): Solange das Match läuft, hält `HKWorkoutSession` die App bei
/// abgesenktem Handgelenk im Vordergrund (Always-on). Ohne HealthKit-Berechtigung/-Entitlement zählt die App trotzdem,
/// nur ohne Puls und ohne Hintergrund-Garantie (Hinweis in der UI).
///
/// P1: Puls wird nur angezeigt; das Workout wird am Ende verworfen, nicht in Apple Health gespeichert
/// (Speichern und Statistik kommen in P3 → `saveToHealth`).
final class WorkoutManager: NSObject, ObservableObject {
    enum State {
        case idle
        case starting
        case running
        /// Keine Health-Daten, keine Berechtigung oder kein Entitlement (z. B. unsignierter Simulator-Build)
        case unavailable
    }

    static let saveToHealth = false

    @Published private(set) var state: State = .idle
    /// Schläge pro Minute, 0 = noch kein Wert
    @Published private(set) var heartRate: Int = 0

    /// Wird einmal gemeldet, wenn die Session nicht starten kann (UI zeigt den Hinweis).
    var onUnavailable: (() -> Void)?
    /// Session läuft (z. B. um den Action-Button-Intent zu spenden).
    var onRunning: (() -> Void)?
    /// Recovery fand keine Session – der Aufrufer startet ggf. eine neue.
    var onRecoveryFailed: (() -> Void)?

    private let store = HKHealthStore()
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private(set) var matchId: String?

    /// Startet die Session für das Match, falls sie nicht schon dafür läuft. Idempotent.
    func ensureRunning(matchId: String, sport: String, presetId: String, doubles: Bool) {
        if state == .unavailable { return }
        if self.matchId == matchId && (state == .starting || state == .running) { return }
        if session != nil { end() }
        self.matchId = matchId
        guard HKHealthStore.isHealthDataAvailable() else {
            markUnavailable("HealthKit nicht verfügbar")
            return
        }
        state = .starting
        let share: Set<HKSampleType> = [HKObjectType.workoutType()]
        var read: Set<HKObjectType> = []
        if let heartRate = HKObjectType.quantityType(forIdentifier: .heartRate) {
            read.insert(heartRate)
        }
        store.requestAuthorization(toShare: share, read: read) { [weak self] success, error in
            DispatchQueue.main.async {
                guard let self, self.matchId == matchId, self.state == .starting else { return }
                if !success || error != nil {
                    self.markUnavailable(error?.localizedDescription ?? "keine Berechtigung")
                    return
                }
                self.begin(matchId: matchId, sport: sport, presetId: presetId, doubles: doubles)
            }
        }
    }

    /// Beendet die Session (Matchende oder Schließen). Ohne laufende Session ein No-op.
    func end() {
        heartRate = 0
        matchId = nil
        if state != .unavailable { state = .idle }
        guard let session, let builder else {
            self.session = nil
            self.builder = nil
            return
        }
        self.session = nil
        self.builder = nil
        session.end()
        builder.endCollection(withEnd: Date()) { _, _ in
            if WorkoutManager.saveToHealth {
                builder.finishWorkout { _, _ in }
            } else {
                builder.discardWorkout()
            }
        }
    }

    /// Absturz-Recovery (05 §1.2): vom System über `handleActiveWorkoutRecovery()` angestoßen.
    /// Bis die Antwort da ist, gilt die Session als „starting“, damit `ensureRunning` keine zweite startet.
    func recover(matchId: String) {
        guard session == nil, state != .unavailable else { return }
        self.matchId = matchId
        state = .starting
        store.recoverActiveWorkoutSession { [weak self] session, _ in
            DispatchQueue.main.async {
                guard let self, self.session == nil else { return }
                guard let session else {
                    // Nichts wiederherzustellen → normal neu starten lassen
                    self.state = .idle
                    self.matchId = nil
                    self.onRecoveryFailed?()
                    return
                }
                let builder = session.associatedWorkoutBuilder()
                builder.dataSource = HKLiveWorkoutDataSource(healthStore: self.store, workoutConfiguration: session.workoutConfiguration)
                session.delegate = self
                builder.delegate = self
                self.session = session
                self.builder = builder
                self.matchId = matchId.isEmpty ? nil : matchId
                self.state = .running
                self.onRunning?()
            }
        }
    }

    private func begin(matchId: String, sport: String, presetId: String, doubles: Bool) {
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = Self.activityType(sport)
        configuration.locationType = Self.locationType(sport)
        do {
            let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
            session.delegate = self
            builder.delegate = self
            self.session = session
            self.builder = builder
            let start = Date()
            session.startActivity(with: start)
            builder.beginCollection(withStart: start) { _, _ in }
            builder.addMetadata(Self.metadata(matchId: matchId, sport: sport, presetId: presetId, doubles: doubles)) { _, _ in }
            state = .running
            onRunning?()
        } catch {
            markUnavailable(error.localizedDescription)
        }
    }

    private func markUnavailable(_ reason: String) {
        print("ADVANTLY_WORKOUT unavailable reason=\(reason)")
        session = nil
        builder = nil
        state = .unavailable
        onUnavailable?()
    }

    /// 05 §1.1/§0.1: Padel hat keinen eigenen Typ → `.tennis` plus Metadaten; Badminton/Tischtennis nativ.
    static func activityType(_ sport: String) -> HKWorkoutActivityType {
        switch sport {
        case "PICKLEBALL": return .pickleball
        case "BADMINTON": return .badminton
        case "TABLE_TENNIS": return .tableTennis
        default: return .tennis // TENNIS und PADEL
        }
    }

    /// Padel, Badminton, Tischtennis in der Halle; Tennis/Pickleball draußen (nur Kalorienschätzung, kein GPS).
    static func locationType(_ sport: String) -> HKWorkoutSessionLocationType {
        switch sport {
        case "PADEL", "BADMINTON", "TABLE_TENNIS": return .indoor
        default: return .outdoor
        }
    }

    static func metadata(matchId: String, sport: String, presetId: String, doubles: Bool) -> [String: Any] {
        [
            HKMetadataKeyWorkoutBrandName: "Advantly",
            "advantly.sport": sport.lowercased(),
            "advantly.matchId": matchId,
            "advantly.format": presetId,
            "advantly.discipline": doubles ? "doubles" : "singles",
        ]
    }
}

extension WorkoutManager: HKWorkoutSessionDelegate {
    func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {}

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            guard let self, workoutSession === self.session else { return }
            self.markUnavailable(error.localizedDescription)
        }
    }
}

extension WorkoutManager: HKLiveWorkoutBuilderDelegate {
    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        guard let type = HKQuantityType.quantityType(forIdentifier: .heartRate),
              collectedTypes.contains(type),
              let quantity = workoutBuilder.statistics(for: type)?.mostRecentQuantity()
        else { return }
        let bpm = quantity.doubleValue(for: HKUnit.count().unitDivided(by: HKUnit.minute()))
        DispatchQueue.main.async { [weak self] in
            self?.heartRate = Int(bpm.rounded())
        }
    }
}
