import AdvantlyWatchKit
import SwiftUI

/// W6 Satzende-Dialog (Modus OPEN, 04 §3.3): „Noch ein Satz?“ – Ja primär, Nein sekundär, kein Countdown.
/// Bleibt stehen, bis jemand antwortet (Workout läuft weiter). Undo nimmt den Satzball zurück.
/// Nur der Zähler entscheidet; als Anzeige zeigt die Uhr Satzergebnis und „[Gerät] entscheidet“ (04 §3.3).
struct SetEndView: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot
    @State private var confirmTakeOver = false

    var body: some View {
        let setNumber = snap.setWinners.count
        let won = snap.setWinners.last == "A"
        ScrollView {
            VStack(spacing: 8) {
                UpdateRequiredLabel(code: snap.updateRequired)
                if OperationStrip.hasServe(model, snap) || model.banner == .pointRemoved {
                    OperationStrip(model: model, snap: snap)
                }
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
                    TakeOverOffer(snap: snap) { confirmTakeOver = true }
                }
            }
        }
        .takeOverConfirmation(isPresented: $confirmTakeOver, snap: snap, model: model)
    }

    @ViewBuilder
    private var decisionButtons: some View {
        ForEach(snap.nextSetOptions, id: \.self) { kind in
            Button {
                model.startNextSet(kind)
            } label: {
                Text(LocalizedStringKey(optionKey(kind))).frame(maxWidth: .infinity, minHeight: 44)
            }
            .tint(kind == "REGULAR" ? Brand.us : Color.gray)
            .accessibilityIdentifier(kind == "REGULAR" ? "setend.yes" : "nextset-" + kind)
        }
        Button {
            model.endMatch()
        } label: {
            Text(LocalizedStringKey(tennisLike ? "setend.finish" : "setend.no"))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        Button(action: model.undo) {
            Label("action.undo", systemImage: "arrow.uturn.backward").font(.footnote)
        }
        .disabled(!snap.canUndo)
        RemovePointButtons(model: model, snap: snap)
    }

    private var tennisLike: Bool { snap.sport == "TENNIS" || snap.sport == "PADEL" }

    private func optionKey(_ kind: String) -> String {
        switch kind {
        case "TIEBREAK_7": return "setend.tiebreak7"
        case "MATCH_TIEBREAK_10": return snap.sport == "PADEL" ? "setend.super10" : "setend.champions10"
        default: return tennisLike ? "setend.regular" : "setend.yes"
        }
    }

    private var lastSetScore: String {
        guard let a = snap.setScoresA.last, let b = snap.setScoresB.last else { return "" }
        let score = ScoreText.pair(a.int32Value, b.int32Value)
        return snap.setKinds.last == "MATCH_TIEBREAK" ? "[" + score + "]" : score
    }
}

/// W7 Matchende: Ergebnis aus Sicht „Wir“, Satzstände, Fertig (schließt) und Rückgängig.
struct MatchEndView: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                UpdateRequiredLabel(code: snap.updateRequired)
                if OperationStrip.hasServe(model, snap) || model.banner == .pointRemoved {
                    OperationStrip(model: model, snap: snap)
                }
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
                if !snap.pointsA.isEmpty {
                    Text(ScoreText.short(snap))
                        .font(.headline)
                        .monospacedDigit()
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
                    .disabled(!snap.canUndo)
                    RemovePointButtons(model: model, snap: snap)
                }
            }
        }
    }

    /// Ergebnis-Codes aus Kotlin → Text-Schlüssel (keine UI-Texte aus der Facade).
    private var resultKey: String {
        switch snap.resultReason {
        case "DRAW": return "matchend.draw"
        case "STOPPED" where snap.winner.isEmpty: return "matchend.draw"
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
