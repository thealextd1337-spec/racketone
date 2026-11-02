import AdvantlyWatchKit
import SwiftUI

/// Gemeinsame Leiste zwischen den Zählhälften sowie am Satz-/Matchende. Alle Trefferflächen mindestens 44 pt.
/// Die Aufschlag-Auswahl steht im Score als große Tasten rechts neben dem Spielstand (`ServePanel`, Plan 0.5.1 §4a),
/// am Satz-/Matchende als Liste (`ServeChoiceList`).
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
                ServeChoiceList(model: model, snap: snap)
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
}

/// Geometrie der Aufschlag-Auswahl im Score (wie Wear OS `ServePanel`): Tasten je 45 % der Breite und mindestens 56 pt
/// bzw. ein Viertel der Höhe, rechts übereinander; links davon (abzüglich Schutzstreifen) zählen die Hälften weiter.
enum ServePanelLayout {
    static let widthFraction: CGFloat = 0.45
    static let heightFraction: CGFloat = 0.25
    static let minButton: CGFloat = 56
    static let gap: CGFloat = 4
    /// Abstand der Tasten zum rechten Displayrand
    static let edge: CGFloat = 6
    /// Schutzstreifen zwischen Zählflächen und Tasten: Berührungen dort tun nichts
    static let guardWidth: CGFloat = 6

    static func buttonWidth(_ width: CGFloat) -> CGFloat { (width * widthFraction).rounded(.down) }

    static func buttonHeight(_ height: CGFloat) -> CGFloat { max(minButton, (height * heightFraction).rounded(.down)) }

    /// Rechts freigehaltene Breite: Tasten, Rand und Schutzstreifen. Spielstand und Zählflächen liegen links davon.
    static func scoreInset(_ width: CGFloat) -> CGFloat { buttonWidth(width) + edge + guardWidth }

    /// Höhe der Tastenspalte bei `count` Tasten.
    static func columnHeight(_ height: CGFloat, count: Int) -> CGFloat {
        CGFloat(count) * buttonHeight(height) + CGFloat(max(0, count - 1)) * gap
    }
}

/// Ein Wert der Aufschlag-Auswahl: Facade-Code, Label-Schlüssel (ausgeschrieben, für Liste und VoiceOver) und
/// Schlüssel der großen Taste (zweizeilig mit fester Trennstelle: „2. Auf-“ / „schlag“, „Doppel-“ / „fehler“).
struct ServeOption {
    let value: String
    let label: String
    let panelLabel: String

    /// Wie Wear OS (Modus „Ja“): „2. Aufschlag“, „Doppelfehler“ nur bei Punkt der Rückschlagseite. Ohne Eingabe gilt
    /// „1. Aufschlag“ (die Session setzt ihn spätestens mit dem nächsten Punkt bzw. am Matchende).
    static func options(_ snap: WatchScoreSnapshot) -> [ServeOption] {
        var options = [ServeOption(value: "SECOND_SERVE_IN", label: "serve.second", panelLabel: "serve.panel.second")]
        if snap.doubleFaultAllowed {
            options.append(ServeOption(value: "DOUBLE_FAULT", label: "serve.df", panelLabel: "serve.panel.df"))
        }
        return options
    }
}

/// Eine Aufschlag-Taste: gewählter Wert hell, „Doppelfehler“ rot, unten die Restzeit. `pointId` wird mit der
/// dargestellten Taste eingefangen; nie auf einen späteren Punkt umleiten.
private struct ServeButton: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot
    let option: ServeOption
    let text: String
    let fontSize: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let selected = snap.lastPointServe == option.value
        let color = option.value == "DOUBLE_FAULT" ? Color.red : Color.white
        let pointId = snap.lastPointId
        Button { model.setServeInfo(pointId: pointId, value: option.value) } label: {
            ServeLabel(text: text, fontSize: fontSize)
                .foregroundStyle(selected ? Color.black : color)
                .padding(.horizontal, 3)
                .padding(.bottom, 6)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(RoundedRectangle(cornerRadius: 12).fill(selected ? color : Color(hex: 0x2A2F38)))
                .overlay(alignment: .bottomLeading) {
                    ServeCountdownBar(deadline: model.serveBarDeadline, reduceMotion: reduceMotion,
                                      color: selected ? Color.black.opacity(0.35) : Color.white.opacity(0.45))
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(option.label)))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Aufschlag-Auswahl im Score (Plan 0.5.1 §4a): große Tasten übereinander, Größe aus `ServePanelLayout`.
struct ServePanel: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot
    let buttonHeight: CGFloat

    var body: some View {
        VStack(spacing: ServePanelLayout.gap) {
            ForEach(ServeOption.options(snap), id: \.value) { option in
                ServeButton(model: model, snap: snap, option: option, text: loc(option.panelLabel), fontSize: 22)
                    .frame(height: buttonHeight)
            }
        }
        .accessibilityIdentifier("serve.bar")
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("serve.bar"))
    }
}

/// Aufschlag-Auswahl am Satz-/Matchende: je Wert eine Taste über die volle Breite, mindestens 56 pt hoch.
private struct ServeChoiceList: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot

    var body: some View {
        VStack(spacing: ServePanelLayout.gap) {
            ForEach(ServeOption.options(snap), id: \.value) { option in
                ServeButton(model: model, snap: snap, option: option, text: loc(option.label), fontSize: 18)
                    .frame(minHeight: ServePanelLayout.minButton)
            }
        }
        .accessibilityIdentifier("serve.bar")
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("serve.bar"))
    }
}

/// Restzeit der Aufschlag-Auswahl als dünner Balken unten in der Taste (links verankert, das rechte Ende läuft nach
/// links): aus dem echten Ausblendzeitpunkt berechnet, startet also bei neuem Punkt oder Auswahl korrekt neu.
/// Animiert nur, solange die Tasten sichtbar sind (im Always-on sind sie ausgeblendet); „Bewegung reduzieren“: 1×/s.
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

/// Label einer Aufschlag-Taste: feste Zeilen (Zeilenumbruch im Text) bzw. zweizeilig an der ersten Leerstelle
/// („Double“ / „fault“). Jede Zeile ist einzeilig und schrumpft notfalls – nie mitten im Wort umbrochen oder abgeschnitten.
private struct ServeLabel: View {
    let text: String
    let fontSize: CGFloat

    var body: some View {
        let lines = text.contains("\n")
            ? text.split(separator: "\n").map(String.init)
            : text.split(separator: " ", maxSplits: 1).map(String.init)
        VStack(spacing: 0) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(verbatim: line)
                    .font(.system(size: fontSize, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.4)
            }
        }
        .multilineTextAlignment(.center)
    }
}

/// Automatische, nicht blockierende Wahl; Kontext und Platz-IDs stammen unverändert aus der Facade.
struct ServerChoiceView: View {
    @ObservedObject var model: ScoringModel
    let context: WatchServerChoiceContext

    var body: some View {
        List {
            if let snap = model.snapshot, snap.isScorer,
               ScoringModel.choiceSignature(snap.serverChoiceContext) == ScoringModel.choiceSignature(context) {
                Text(LocalizedStringKey(context.phase == "FIRST" ? "serve.choice.first" :
                     context.dueSide == "A" ? "serve.choice.second.us" : "serve.choice.second.them"))
                    .font(.headline)
                Text(contextText).font(.footnote).foregroundStyle(Brand.text2)
                ForEach(snap.serverChoicePlayers, id: \.id) { player in
                    Button {
                        model.chooseServer(player.id, expected: context)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(PlayerText.name(player, doubles: snap.doubles)).lineLimit(2)
                                Text(LocalizedStringKey(player.side == "A" ? "team.us" : "team.them"))
                                    .font(.footnote).foregroundStyle(Brand.text2)
                            }
                            Spacer()
                            if player.id == context.chosen { Image(systemName: "checkmark") }
                        }
                        .frame(minHeight: 44)
                    }
                    .accessibilityLabel(Text(verbatim: "\(PlayerText.name(player, doubles: snap.doubles)), \(loc(player.side == "A" ? "team.us" : "team.them")), \(player.number)"))
                    .accessibilityAddTraits(player.id == context.chosen ? .isSelected : [])
                }
                if let player = snap.serverChoicePlayers.first(where: { $0.id == context.defaultServer }) ?? snap.serverPlayer {
                    Text(loc("serve.choice.current %@", PlayerText.name(player, doubles: snap.doubles)))
                        .font(.footnote).foregroundStyle(Brand.text2)
                }
            }
            Button("common.later") { model.dismissServerChoice() }.frame(minHeight: 44)
        }
    }

    private var contextText: String {
        if context.gameKind == "STANDARD" {
            return loc("serve.choice.game %@ %@", String(context.setIndex + 1), context.phase == "FIRST" ? "1" : "2")
        }
        return loc(context.phase == "FIRST" ? "serve.choice.tb.first %@" : "serve.choice.tb.second %@", String(context.setIndex + 1))
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
