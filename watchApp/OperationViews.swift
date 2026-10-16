import AdvantlyWatchKit
import SwiftUI

/// Gemeinsame Leiste zwischen den Zählhälften sowie am Satz-/Matchende. Alle Trefferflächen mindestens 44 pt.
struct OperationStrip: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot
    var chooseServer: () -> Void = {}
    @Environment(\.isLuminanceReduced) private var dimmed
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static func hasServe(_ model: ScoringModel, _ snap: WatchScoreSnapshot) -> Bool {
        snap.isScorer && snap.serveInfoEnabled && !model.serveBarPointId.isEmpty && model.serveBarPointId == snap.lastPointId
    }

    static func hasAction(_ model: ScoringModel, _ snap: WatchScoreSnapshot) -> Bool {
        snap.isScorer && (hasServe(model, snap) || (model.banner == .pointRemoved && snap.canRedo) || snap.serverChoicePrompt)
    }

    var body: some View {
        if snap.isScorer && !dimmed {
            if Self.hasServe(model, snap) {
                // Ausgeschriebene Labels („1. Aufschlag“, „Doppelfehler“, Feldtest 2026-10-16); „Doppelfehler“ ist ein
                // langes Wort ohne Umbruch und bekommt mehr Breite (wie Wear OS: 1 : 1 : 1,3)
                GeometryReader { geo in
                    let options = serveOptions
                    let spacing: CGFloat = 2
                    let total = options.reduce(CGFloat(0)) { $0 + $1.weight }
                    let unit = max(0, geo.size.width - spacing * CGFloat(options.count - 1)) / max(total, 1)
                    HStack(spacing: spacing) {
                        ForEach(options, id: \.value) { option in
                            serveButton(option.value, label: option.label)
                                .frame(width: unit * option.weight)
                        }
                    }
                }
                .frame(minHeight: 44, maxHeight: .infinity)
                .accessibilityIdentifier("serve.bar")
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("serve.bar"))
            } else if model.banner == .pointRemoved && snap.canRedo {
                Button(action: model.redoRemoval) {
                    VStack(spacing: 1) {
                        Text("banner.pointRemoved").font(.system(size: 10))
                        Text("action.restorePoint").font(.system(size: 13, weight: .bold)).foregroundStyle(Brand.hint)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else if snap.serverChoicePrompt {
                Button(action: chooseServer) {
                    HStack(spacing: 6) {
                        let sets = ScoreText.sets(snap)
                        if !sets.isEmpty {
                            Text(sets).font(.system(size: 12)).foregroundStyle(Color.white)
                        }
                        Text("serve.choose")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Brand.hint)
                    }
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// Werte der Leiste mit Label-Schlüssel und relativer Breite; Doppelfehler nur bei Punkt der Rückschlagseite.
    private var serveOptions: [ServeOption] {
        var options = [
            ServeOption(value: "FIRST_SERVE_IN", label: "serve.first", weight: 1),
            ServeOption(value: "SECOND_SERVE_IN", label: "serve.second", weight: 1),
        ]
        if snap.doubleFaultAllowed {
            options.append(ServeOption(value: "DOUBLE_FAULT", label: "serve.df", weight: 1.3))
        }
        return options
    }

    private func serveButton(_ value: String, label: String) -> some View {
        let selected = snap.lastPointServe == value
        let color = value == "DOUBLE_FAULT" ? Color.red : Color.white
        // pointId wird mit der dargestellten Schaltfläche eingefangen; nie auf einen späteren Punkt umleiten.
        let pointId = snap.lastPointId
        return Button { model.setServeInfo(pointId: pointId, value: value) } label: {
            ServeLabel(text: loc(label))
                .foregroundStyle(selected ? Color.black : color)
                .padding(.horizontal, 2)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .frame(minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 8).fill(selected ? color : Color(hex: 0x1E2128)))
                .overlay(alignment: .bottomLeading) {
                    ServeCountdownBar(deadline: model.serveBarDeadline, reduceMotion: reduceMotion,
                                      color: selected ? Color.black.opacity(0.35) : Color.white.opacity(0.45))
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(label)))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Restzeit der Aufschlag-Leiste als dünner Balken unten im Segment (links verankert, das rechte Ende läuft nach
/// links): aus dem echten Ausblendzeitpunkt berechnet, startet also bei neuem Punkt oder Auswahl korrekt neu.
/// Animiert nur, solange die Leiste sichtbar ist (im Always-on ist sie ausgeblendet); „Bewegung reduzieren“: 1×/s.
private struct ServeCountdownBar: View {
    let deadline: Date
    let reduceMotion: Bool
    let color: Color

    var body: some View {
        TimelineView(.animation(minimumInterval: reduceMotion ? 1 : 1.0 / 30)) { context in
            let fraction = min(1, max(0, deadline.timeIntervalSince(context.date) / ScoringModel.serveBarVisible))
            GeometryReader { geo in
                Capsule()
                    .fill(color)
                    .frame(width: max(0, geo.size.width - 8) * fraction, height: 2.5)
                    .padding(.leading, 4)
                    .padding(.bottom, 4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Ein Wert der Aufschlag-Leiste: Facade-Code, Label-Schlüssel, relative Breite.
private struct ServeOption {
    let value: String
    let label: String
    let weight: CGFloat
}

/// Langlabel zweizeilig an der ersten Leerstelle („1.“ / „Aufschlag“, „Double“ / „fault“), ein Wort („Doppelfehler“)
/// einzeilig. Jede Zeile ist einzeilig und schrumpft notfalls – so wird nie mitten im Wort umbrochen oder abgeschnitten.
private struct ServeLabel: View {
    let text: String

    var body: some View {
        let lines = text.split(separator: " ", maxSplits: 1).map(String.init)
        VStack(spacing: 0) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(verbatim: line)
                    .font(.system(size: 13, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.55)
            }
        }
        .multilineTextAlignment(.center)
    }
}

/// Wahlfenster aus der Session; nur auf Wunsch öffnen, das Weiterzählen bleibt jederzeit möglich.
struct ServerChoiceView: View {
    @ObservedObject var model: ScoringModel
    let window: String
    @Environment(\.dismiss) private var dismiss

    static func key(_ snap: WatchScoreSnapshot) -> String {
        "\(snap.matchId):\(snap.setIndex):\(snap.serverChoiceGame)"
    }

    var body: some View {
        List {
            if let snap = model.snapshot, snap.isScorer, snap.serverChoiceGame > 0, Self.key(snap) == window {
                Text(LocalizedStringKey(snap.serverChoiceGame == 1 ? "serve.choice.first" :
                     snap.serverChoiceSide == "A" ? "serve.choice.second.us" : "serve.choice.second.them"))
                    .font(.headline)
                ForEach(snap.serverChoicePlayers, id: \.id) { player in
                    Button {
                        if model.chooseServer(player.id) { dismiss() }
                    } label: {
                        HStack {
                            Text(PlayerText.name(player, doubles: snap.doubles))
                            Spacer()
                            if player.id == snap.serverChoiceChosen { Image(systemName: "checkmark") }
                        }
                        .frame(minHeight: 44)
                    }
                }
                Text("serve.choice.hint").font(.footnote).foregroundStyle(Brand.text2)
            }
            Button("common.cancel") { dismiss() }
        }
        .onChange(of: model.snapshot?.eventCount) { _, _ in
            guard let snap = model.snapshot, snap.isScorer, snap.serverChoiceGame > 0, Self.key(snap) == window else {
                dismiss()
                return
            }
        }
    }
}

/// Namen bleiben Daten; neutrale, nicht gespeicherte Platznamen übersetzt ausschließlich Swift.
enum PlayerText {
    static func name(_ player: WatchPlayer, doubles: Bool) -> String {
        if player.isSelf { return loc("setup.player.self") }
        if !player.displayName.isEmpty { return player.displayName }
        if player.side == "A" { return loc("setup.player.partner") }
        if !doubles { return loc("setup.player.opponent") }
        return loc(player.number == 2 ? "setup.player.opponent2" : "setup.player.opponent1")
    }
}

struct RemovePointButtons: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot

    var body: some View {
        Button { model.removePoint(sideA: true) } label: {
            Text("action.removeUs").frame(maxWidth: .infinity, minHeight: 44)
        }
        .disabled(!snap.isScorer || !snap.canRemovePointA)
        Button { model.removePoint(sideA: false) } label: {
            Text("action.removeThem").frame(maxWidth: .infinity, minHeight: 44)
        }
        .disabled(!snap.isScorer || !snap.canRemovePointB)
    }
}

struct UpdateRequiredLabel: View {
    let code: String

    var body: some View {
        if !code.isEmpty {
            Text(LocalizedStringKey(code == "THIS_DEVICE" ? "sync.updateWatch" : "sync.updatePhone"))
                .font(.footnote)
                .foregroundStyle(Brand.hint)
                .multilineTextAlignment(.center)
        }
    }
}
