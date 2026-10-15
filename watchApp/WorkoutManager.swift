import Foundation
import HealthKit

/// Workout-Session als Laufzeit-Anker (05 §1.2, E2): Solange das Match läuft, hält `HKWorkoutSession` die App bei
/// abgesenktem Handgelenk im Vordergrund (Always-on). Ohne HealthKit-Berechtigung/-Entitlement zählt die App trotzdem,
/// nur ohne Puls und ohne Hintergrund-Garantie (Hinweis in der UI).
///
/// - Eine Unterbrechung des Matches (`Paused`) pausiert die Session, `Resumed` setzt sie fort; Seitenwechsel und
///   Satzende-Dialog sind keine Workout-Pause.
/// - Jede asynchrone Antwort (Berechtigung, Recovery) ist an eine Generation gebunden: Kommt sie nach `end()` oder
///   nach dem Start eines anderen Matches an, wird sie verworfen – eine dabei wiederhergestellte Session wird beendet.
/// - P1: Puls wird nur angezeigt; das Workout wird am Ende verworfen, nicht in Apple Health gespeichert
///   (Speichern und Statistik kommen in P3 → `saveToHealth`).
final class WorkoutManager: NSObject, ObservableObject {
    enum State {
        case idle
        case starting
        case running
        case paused
        /// Keine Health-Daten, keine Berechtigung oder kein Entitlement (z. B. unsignierter Simulator-Build)
        case unavailable
    }

    static let saveToHealth = false

    @Published private(set) var state: State = .idle
    /// Schläge pro Minute, 0 = noch kein Wert
    @Published private(set) var heartRate: Int = 0
    /// Puls für Always-on: höchstens einmal pro Minute aktualisiert (04 §4.3.6)
    @Published private(set) var heartRateAmbient: Int = 0

    /// Wird gemeldet, wenn die Session nicht starten kann (UI zeigt den Hinweis).
    var onUnavailable: (() -> Void)?
    /// Session läuft (z. B. um den Action-Button-Intent zu spenden).
    var onRunning: (() -> Void)?
    /// Recovery fand keine (brauchbare) Session – der Aufrufer startet ggf. eine neue.
    var onRecoveryFailed: (() -> Void)?
    /// Das System hat die Session selbst pausiert (`true`) bzw. fortgesetzt – als Zähler zieht das Match nach.
    var onSystemPauseChange: ((Bool) -> Void)?

    private let store = HKHealthStore()
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private(set) var matchId: String?
    /// Gewünschte Pause: als Zähler laut Match, als Anzeige lokal an dieser Uhr.
    private var wantsPaused = false
    /// Zählt bei jedem Start/Ende/Recovery hoch; asynchrone Antworten älterer Generationen werden verworfen.
    private var generation = 0
    private var ambientUpdatedAt = Date.distantPast
    /// Von uns angefragter Übergang (pause/resume), damit er nicht als System-Pause gedeutet wird
    private var requested: HKWorkoutSessionState?

    /// Startet die Session für das Match, falls sie nicht schon dafür läuft, und setzt Pause/Fortsetzen durch. Idempotent.
    /// `paused == nil`: Anzeige – die lokale Workout-Pause bleibt bei eingehenden Snapshots erhalten.
    func ensureRunning(matchId: String, sport: String, presetId: String, doubles: Bool, paused: Bool?) {
        if state == .unavailable && self.matchId == matchId { return }
        if self.matchId == matchId && (state == .starting || state == .running || state == .paused) {
            if let paused { wantsPaused = paused }
            applyPause()
            return
        }
        end()
        wantsPaused = paused ?? false
        generation += 1
        let current = generation
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
                guard let self, self.generation == current else { return }
                if !success || error != nil {
                    self.markUnavailable(error?.localizedDescription ?? "keine Berechtigung")
                    return
                }
                self.begin(matchId: matchId, sport: sport, presetId: presetId, doubles: doubles)
            }
        }
    }

    /// Beendet die Session (Matchende oder Schließen). Ohne laufende Session ein No-op; offene Antworten verfallen.
    func end() {
        generation += 1
        heartRate = 0
        heartRateAmbient = 0
        matchId = nil
        wantsPaused = false
        // Abwählen/erneut Anwählen ist ein ausdrücklicher neuer Versuch, auch nach transientem Fehler.
        state = .idle
        let session = self.session
        let builder = self.builder
        self.session = nil
        self.builder = nil
        requested = nil
        if let session {
            Self.finish(session: session, builder: builder)
        }
    }

    /// Nur das lokale Workout pausieren/fortsetzen (Anzeige erzeugt keine Match-Events).
    func setPaused(_ paused: Bool) {
        wantsPaused = paused
        applyPause()
    }

    /// Wartet (für den Schnellstart-Intent), bis der Start entschieden ist: `.running`/`.paused` oder `.unavailable`.
    @MainActor
    func waitUntilSettled(timeout: TimeInterval = 15) async -> State {
        let deadline = Date().addingTimeInterval(timeout)
        while state == .starting && Date() < deadline {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return state
    }

    /// Absturz-Recovery (05 §1.2): vom System über `handleActiveWorkoutRecovery()` angestoßen. Bis die Antwort da ist,
    /// gilt die Session als „starting“, damit `ensureRunning` keine zweite startet. Übernimmt den tatsächlichen
    /// Sessionstatus; beendete oder überholte Sessions werden beendet und verworfen.
    func recover(matchId: String) {
        guard !matchId.isEmpty, session == nil, state != .unavailable else {
            // Kein Match (mehr) offen oder schon eine Session: eine wiederhergestellte Session nur noch abräumen
            store.recoverActiveWorkoutSession { session, _ in
                DispatchQueue.main.async {
                    if let session, session !== self.session { Self.finish(session: session, builder: session.associatedWorkoutBuilder()) }
                }
            }
            return
        }
        generation += 1
        let current = generation
        self.matchId = matchId
        state = .starting
        store.recoverActiveWorkoutSession { [weak self] session, _ in
            DispatchQueue.main.async {
                guard let self, self.generation == current, self.matchId == matchId, self.session == nil else {
                    // überholt (end() oder anderes Match dazwischen)
                    if let session { Self.finish(session: session, builder: session.associatedWorkoutBuilder()) }
                    return
                }
                guard let session, session.state == .running || session.state == .paused else {
                    if let session { Self.finish(session: session, builder: session.associatedWorkoutBuilder()) }
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
                self.wantsPaused = session.state == .paused
                self.state = session.state == .paused ? .paused : .running
                self.onRunning?()
                self.applyPause()
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
            applyPause()
        } catch {
            markUnavailable(error.localizedDescription)
        }
    }

    /// Setzt den gewünschten Pausenzustand des Matches an der Session durch.
    private func applyPause() {
        guard let session else { return }
        guard requested == nil else { return } // Antwort abwarten, danach wird erneut abgeglichen
        if wantsPaused && session.state == .running {
            requested = .paused
            session.pause()
        } else if !wantsPaused && session.state == .paused {
            requested = .running
            session.resume()
        }
    }

    private func markUnavailable(_ reason: String) {
        print("ADVANTLY_WORKOUT unavailable reason=\(reason)")
        generation += 1
        if let session {
            Self.finish(session: session, builder: builder)
        }
        session = nil
        builder = nil
        requested = nil
        heartRate = 0
        heartRateAmbient = 0
        state = .unavailable
        onUnavailable?()
    }

    private static func finish(session: HKWorkoutSession, builder: HKLiveWorkoutBuilder?) {
        session.end()
        guard let builder else { return }
        builder.endCollection(withEnd: Date()) { _, _ in
            if WorkoutManager.saveToHealth {
                builder.finishWorkout { _, _ in }
            } else {
                builder.discardWorkout()
            }
        }
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
    ) {
        DispatchQueue.main.async { [weak self] in
            guard let self, workoutSession === self.session else { return }
            if let requested = self.requested, requested == toState {
                // eigener Übergang: Zustand übernehmen und mit dem aktuellen Wunsch abgleichen
                self.requested = nil
                self.state = toState == .paused ? .paused : .running
                self.applyPause()
                return
            }
            switch toState {
            case .running:
                self.state = .running
                // vom System fortgesetzt (paused → running), obwohl das Match pausiert ist → Match fortsetzen.
                // Der erste Start (notStarted/prepared → running) zählt nicht; applyPause() setzt dann die Pause durch.
                if fromState == .paused && self.wantsPaused {
                    self.wantsPaused = false
                    self.onSystemPauseChange?(false)
                } else {
                    self.applyPause()
                }
            case .paused:
                self.state = .paused
                // vom System pausiert (z. B. Action + Seitentaste) → Match pausieren
                if fromState == .running && !self.wantsPaused {
                    self.wantsPaused = true
                    self.onSystemPauseChange?(true)
                }
            default:
                break
            }
        }
    }

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
        let bpm = Int(quantity.doubleValue(for: HKUnit.count().unitDivided(by: HKUnit.minute())).rounded())
        DispatchQueue.main.async { [weak self] in
            guard let self, workoutBuilder === self.builder else { return }
            self.heartRate = bpm
            let now = Date()
            if self.heartRateAmbient == 0 || now.timeIntervalSince(self.ambientUpdatedAt) >= 60 {
                self.heartRateAmbient = bpm
                self.ambientUpdatedAt = now
            }
        }
    }
}
