import AdvantlyWatchKit
import SwiftUI

/// Gemeinsame Leiste zwischen den Zählhälften sowie am Satz-/Matchende. Alle Trefferflächen mindestens 44 pt.
struct OperationStrip: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot
    var chooseServer: () -> Void = {}
    @Environment(\.isLuminanceReduced) private var dimmed

    static func hasServe(_ model: ScoringModel, _ snap: WatchScoreSnapshot) -> Bool {
        snap.isScorer && snap.serveInfoEnabled && !model.serveBarPointId.isEmpty && model.serveBarPointId == snap.lastPointId
    }

    static func hasAction(_ model: ScoringModel, _ snap: WatchScoreSnapshot) -> Bool {
        snap.isScorer && (hasServe(model, snap) || (model.banner == .pointRemoved && snap.canRedo) || snap.serverChoicePrompt)
    }

    var body: some View {
        if snap.isScorer && !dimmed {
            if Self.hasServe(model, snap) {
                HStack(spacing: 2) {
                    serveButton("FIRST_SERVE_IN", short: "serve.first.short", label: "serve.first")
                    serveButton("SECOND_SERVE_IN", short: "serve.second.short", label: "serve.second")
                    if snap.doubleFaultAllowed {
                        serveButton("DOUBLE_FAULT", short: "serve.df.short", label: "serve.df")
                    }
                }
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

    private func serveButton(_ value: String, short: String, label: String) -> some View {
        let selected = snap.lastPointServe == value
        let color = value == "DOUBLE_FAULT" ? Color.red : Color.white
        // pointId wird mit der dargestellten Schaltfläche eingefangen; nie auf einen späteren Punkt umleiten.
        let pointId = snap.lastPointId
        return Button { model.setServeInfo(pointId: pointId, value: value) } label: {
            Text(LocalizedStringKey(short))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(selected ? Color.black : color)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 8).fill(selected ? color : Color(hex: 0x1E2128)))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(label)))
        .accessibilityAddTraits(selected ? .isSelected : [])
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
