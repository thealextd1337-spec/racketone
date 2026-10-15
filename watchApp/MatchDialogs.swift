import AdvantlyWatchKit
import SwiftUI

/// W6 Satzende-Dialog (Modus OPEN, 04 §3.3): „Noch ein Satz?“ – Ja primär, Nein sekundär, kein Countdown.
/// Bleibt stehen, bis jemand antwortet (Workout läuft weiter). Undo nimmt den Satzball zurück.
/// Nur der Zähler entscheidet; als Anzeige zeigt die Uhr Satzergebnis und „[Gerät] entscheidet“ (04 §3.3).
struct SetEndView: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot

    var body: some View {
        let setNumber = snap.setWinners.count
        let won = snap.setWinners.last == "A"
        ScrollView {
            VStack(spacing: 8) {
                Text(loc("setend.title %@ %@", String(setNumber), lastSetScore))
                    .font(.headline)
                    .monospacedDigit()
                Text(LocalizedStringKey(won ? "setend.won" : "setend.lost"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(won ? Brand.us : Brand.them)
                Text("setend.question")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                if snap.isScorer {
                    decisionButtons
                } else {
                    Text(loc("setend.decidedBy %@", RoleText.device(snap.scorerKind)))
                        .font(.footnote)
                        .foregroundStyle(Brand.text2)
                        .multilineTextAlignment(.center)
                    StalenessLabel(snap: snap, dimmed: false)
                }
            }
        }
    }

    @ViewBuilder
    private var decisionButtons: some View {
        Button {
            model.decide(anotherSet: true, matchTiebreak: false)
        } label: {
            Text("setend.yes").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(Brand.us)
        .accessibilityIdentifier("setend.yes")
        if snap.offerMatchTiebreak {
            Button {
                model.decide(anotherSet: true, matchTiebreak: true)
            } label: {
                Text("setend.matchTiebreak").frame(maxWidth: .infinity)
            }
        }
        Button {
            model.decide(anotherSet: false, matchTiebreak: false)
        } label: {
            Text("setend.no").frame(maxWidth: .infinity)
        }
        Button(action: model.undo) {
            Label("action.undo", systemImage: "arrow.uturn.backward")
                .font(.footnote)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Brand.text2)
        .padding(.top, 4)
    }

    private var lastSetScore: String {
        guard let a = snap.setScoresA.last, let b = snap.setScoresB.last else { return "" }
        return ScoreText.pair(a.int32Value, b.int32Value)
    }
}

/// W7 Matchende: Ergebnis aus Sicht „Wir“, Satzstände, Fertig (schließt) und Rückgängig.
struct MatchEndView: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                SportLabel(sport: snap.sport) {
                    Text(LocalizedStringKey(resultKey))
                        .font(.title3.weight(.bold))
                        .foregroundStyle(resultColor)
                }
                if !snap.setWinners.isEmpty {
                    Text(ScoreText.sets(snap))
                        .font(.system(size: 22, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(2)
                        .minimumScaleFactor(0.6)
                }
                Button {
                    model.closeMatch()
                } label: {
                    Text("matchend.done").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Brand.us)
                if snap.isScorer {
                    Button(action: model.undo) {
                        Label("action.undo", systemImage: "arrow.uturn.backward")
                            .font(.footnote)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Brand.text2)
                }
            }
        }
    }

    /// Ergebnis-Codes aus Kotlin → Text-Schlüssel (keine UI-Texte aus der Facade).
    private var resultKey: String {
        switch snap.resultReason {
        case "DRAW": return "matchend.draw"
        case "ABANDONED": return "matchend.abandoned"
        default:
            if snap.winner.isEmpty { return "matchend.abandoned" }
            return snap.winner == "A" ? "matchend.won" : "matchend.lost"
        }
    }

    private var resultColor: Color {
        switch resultKey {
        case "matchend.won": return Brand.us
        case "matchend.lost": return Brand.them
        default: return .white
        }
    }
}
