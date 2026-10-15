import AppIntents
import Foundation

// Apple Watch Ultra – Action-Taste (05 §1.7, E10; Apple „Responding to the Action button“):
// - Schnellstart: StartMatchIntent (StartWorkoutIntent) → Einstellungen › Action-Taste › Training › Advantly.
// - Im Match: die zuletzt gespendete Aktion. Beide Aktions-Intents laufen zentral über
//   ScoringModel.handleActionButton(), es gilt also immer die aktuelle Einstellung (Undo = Default, „Punkt für uns“, Aus).
// - Action + Seitentaste: PauseMatchIntent/ResumeMatchIntent (Pause/ResumeWorkoutIntent) – ohne sie ignoriert das System
//   die Tastenkombination.
// Risiko R2: Die Intent-Spende ist seit watchOS 26.5 laut Forum fehlerhaft – deshalb Feature-Flag (ActionButtonMode)
// und der Undo-Button auf dem Display bleibt immer. Verifikation nur auf echter Ultra möglich (TestFlight).

/// Spendet die Aktion für den nächsten Druck auf die Action-Taste.
enum ActionIntents {
    static func donate(_ mode: ActionButtonMode) {
        Task {
            switch mode {
            case .undo:
                _ = try? await IntentDonationManager.shared.donate(intent: UndoPointIntent())
            case .pointUs:
                _ = try? await IntentDonationManager.shared.donate(intent: PointUsIntent())
            case .off:
                break
            }
        }
    }
}

/// Fehler der Intents, lokalisiert über Localizable.strings.
enum MatchIntentError: Error, CustomLocalizedStringResourceConvertible {
    case startFailed
    case workoutUnavailable

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .startFailed: return "intent.error.startFailed"
        case .workoutUnavailable: return "intent.error.workout"
        }
    }
}

/// „Letzten Punkt rückgängig“ – Default der Action-Taste im Match.
struct UndoPointIntent: AppIntent {
    static var title: LocalizedStringResource = "intent.undo.title"
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        ScoringModel.shared.handleActionButton()
        return .result()
    }
}

/// „Punkt für uns“ – alternative Belegung der Action-Taste.
struct PointUsIntent: AppIntent {
    static var title: LocalizedStringResource = "intent.pointUs.title"
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        ScoringModel.shared.handleActionButton()
        return .result()
    }
}

/// Action + Seitentaste: Match unterbrechen (Workout pausiert mit).
struct PauseMatchIntent: PauseWorkoutIntent {
    static var title: LocalizedStringResource = "intent.pause.title"

    @MainActor
    func perform() async throws -> some IntentResult {
        ScoringModel.shared.pauseMatch()
        return .result()
    }
}

/// Action + Seitentaste in der Pause: Match fortsetzen.
struct ResumeMatchIntent: ResumeWorkoutIntent {
    static var title: LocalizedStringResource = "intent.resume.title"

    @MainActor
    func perform() async throws -> some IntentResult {
        ScoringModel.shared.resumeMatch()
        return .result()
    }
}

/// Sportart für den Schnellstart; Rohwerte = Sport-Codes aus Kotlin.
enum MatchSport: String, AppEnum {
    case tennis = "TENNIS"
    case padel = "PADEL"
    case pickleball = "PICKLEBALL"
    case badminton = "BADMINTON"
    case tableTennis = "TABLE_TENNIS"

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "intent.sport.type")
    static var caseDisplayRepresentations: [MatchSport: DisplayRepresentation] = [
        .tennis: DisplayRepresentation(title: "sport.TENNIS"),
        .padel: DisplayRepresentation(title: "sport.PADEL"),
        .pickleball: DisplayRepresentation(title: "sport.PICKLEBALL"),
        .badminton: DisplayRepresentation(title: "sport.BADMINTON"),
        .tableTennis: DisplayRepresentation(title: "sport.TABLE_TENNIS"),
    ]
}

/// Schnellstart eines Matches über die Action-Taste (`suggestedWorkouts` = fünf Sportarten). Meldet Erfolg erst,
/// wenn Berechtigung und Sessionstart entschieden sind. Nur bei gewünschtem Workout ist eine fehlende Session ein
/// Fehler (die App zählt trotzdem, zeigt aber den Health-Hinweis).
struct StartMatchIntent: StartWorkoutIntent {
    static var title: LocalizedStringResource = "intent.start.title"
    static var openAppWhenRun: Bool = true

    @Parameter(title: "intent.sport.type")
    var workoutStyle: MatchSport

    static var suggestedWorkouts: [StartMatchIntent] {
        MatchSport.allCases.map { StartMatchIntent(style: $0) }
    }

    var displayRepresentation: DisplayRepresentation {
        MatchSport.caseDisplayRepresentations[workoutStyle] ?? DisplayRepresentation(title: "intent.start.title")
    }

    init() {}

    init(style: MatchSport) {
        workoutStyle = style
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let model = ScoringModel.shared
        guard model.quickStart(sport: workoutStyle.rawValue) else {
            throw MatchIntentError.startFailed
        }
        if let snap = model.snapshot, model.workoutWanted(snap) {
            let state = await model.workout.waitUntilSettled()
            // Während der Berechtigungsabfrage kann der Nutzer das Workout abwählen.
            if let current = model.snapshot, model.workoutWanted(current),
               state != .running && state != .paused {
                throw MatchIntentError.workoutUnavailable
            }
        }
        return .result()
    }
}
