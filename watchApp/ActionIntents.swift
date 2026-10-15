import AppIntents
import Foundation

// Apple Watch Ultra – Action-Taste (05 §1.7, E10):
// - Schnellstart: StartMatchIntent (StartWorkoutIntent) → Einstellungen › Action-Taste › Training › Advantly.
// - Im Match: die zuletzt gespendete Aktion = Undo (Default) bzw. „Punkt für uns“, einstellbar auf der Steuerseite.
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

/// „Letzten Punkt rückgängig“ – Default der Action-Taste im Match.
struct UndoPointIntent: AppIntent {
    static var title: LocalizedStringResource = "intent.undo.title"
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        ScoringModel.shared.undo()
        return .result()
    }
}

/// „Punkt für uns“ – alternative Belegung der Action-Taste.
struct PointUsIntent: AppIntent {
    static var title: LocalizedStringResource = "intent.pointUs.title"
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult {
        ScoringModel.shared.tap(sideA: true)
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

/// Schnellstart eines Matches über die Action-Taste (`suggestedWorkouts` = fünf Sportarten).
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
        ScoringModel.shared.quickStart(sport: workoutStyle.rawValue)
        return .result()
    }
}
