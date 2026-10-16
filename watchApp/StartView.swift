import AdvantlyWatchKit
import Foundation
import SwiftUI

/// W1 Startliste: „Wie letztes Mal“, fünf Sportarten (Icon + Farbe + Name), danach Preset → Disziplin → Aufschlag.
struct StartView: View {
    @ObservedObject var model: ScoringModel
    /// Debug-Zeile (Store-Smoke, Engine), leer in Release
    var debugInfo: String = ""

    var body: some View {
        NavigationStack {
            List {
                if !model.updateRequired.isEmpty {
                    UpdateRequiredLabel(code: model.updateRequired)
                }
                if model.storeUnavailable {
                    // Dev-Daten-Reset: Datenbank aus der Zeit vor der Match-Session (Schema ohne Migration)
                    Text("start.storeUnavailable")
                        .font(.footnote)
                        .foregroundStyle(Brand.hint)
                }
                if let last = model.lastSetup {
                    Section {
                        Button {
                            model.startLast()
                        } label: {
                            SportLabel(sport: last.sport) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("start.lastTime").font(.headline)
                                    Text(LocalizedStringKey("preset." + last.presetId))
                                        .font(.footnote)
                                        .foregroundStyle(Brand.text2)
                                    Text(setupDetail(last))
                                        .font(.footnote)
                                        .foregroundStyle(Brand.text2)
                                }
                            }
                        }
                        .accessibilityIdentifier("start.lastTime")
                    }
                }
                Section {
                    ForEach(model.sports, id: \.self) { sport in
                        NavigationLink {
                            PresetListView(model: model, sport: sport)
                        } label: {
                            SportLabel(sport: sport) {
                                Text(LocalizedStringKey("sport." + sport)).font(.headline)
                            }
                        }
                        .listItemTint(SportStyle.of(sport).color.opacity(0.25))
                    }
                } header: {
                    Text("start.newMatch")
                }
                #if DEBUG
                if !debugInfo.isEmpty {
                    Section {
                        Text(debugInfo).font(.footnote).foregroundStyle(Brand.text2)
                    }
                }
                #endif
            }
            .navigationTitle("Advantly")
        }
        .onAppear { model.refreshStartInfo() }
    }

    private func setupDetail(_ setup: WatchMatchSetup) -> String {
        let discipline = loc(setup.doubles ? "setup.doubles" : "setup.singles")
        let serve = loc(setup.weServeFirst ? "setup.weServe" : "setup.theyServe")
        let games = setup.gamesPerSet > 0 ? " · " + loc("setup.games %@", String(setup.gamesPerSet)) : ""
        return discipline + " · " + serve + games
    }
}

/// Sport-Icon im Farbkreis + Inhalt (Sportfarbe nie allein, immer mit Icon + Name).
struct SportLabel<Content: View>: View {
    let sport: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        let style = SportStyle.of(sport)
        HStack(spacing: 10) {
            Image(systemName: style.icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.black)
                .frame(width: 30, height: 30)
                .background(Circle().fill(style.color))
                .accessibilityHidden(true)
            content()
        }
    }
}

/// Presets einer Sportart (Default zuerst, markiert).
struct PresetListView: View {
    @ObservedObject var model: ScoringModel
    let sport: String

    var body: some View {
        let presets = model.presets(for: sport)
        List(presets, id: \.id) { preset in
            NavigationLink {
                SetupView(model: model, preset: preset)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(LocalizedStringKey("preset." + preset.id))
                    if preset.isDefault {
                        Text("start.default")
                            .font(.footnote)
                            .foregroundStyle(SportStyle.of(sport).color)
                    }
                }
            }
        }
        .navigationTitle(LocalizedStringKey("sport." + sport))
    }
}

/// Kurz-Setup: Formatoptionen und Spielerplätze, danach ein Tipp auf den ersten Aufschläger startet das Match.
struct SetupView: View {
    @ObservedObject var model: ScoringModel
    let preset: WatchPreset
    @State private var doubles: Bool
    @State private var gamesPerSet: Int32
    @State private var serveStats = true
    @State private var partnerId = ""
    @State private var opponent1Id = ""
    @State private var opponent2Id = ""

    init(model: ScoringModel, preset: WatchPreset) {
        self.model = model
        self.preset = preset
        _doubles = State(initialValue: preset.defaultDoubles)
        _gamesPerSet = State(initialValue: 0)
    }

    private var tennisLike: Bool { preset.sport == "TENNIS" || preset.sport == "PADEL" }
    private var slots: [String] { doubles ? ["SELF", "PARTNER", "OPPONENT_1", "OPPONENT_2"] : ["SELF", "OPPONENT_1"] }
    private var selected: [String] { [partnerId, opponent1Id, opponent2Id].filter { !$0.isEmpty } }

    var body: some View {
        List {
            Text(LocalizedStringKey("preset." + preset.id)).font(.footnote).foregroundStyle(Brand.text2)
            if preset.singles && preset.doubles {
                Button {
                    doubles.toggle()
                    partnerId = ""
                    opponent2Id = ""
                } label: {
                    Text(LocalizedStringKey(doubles ? "setup.doubles" : "setup.singles"))
                }
            }
            if tennisLike {
                if preset.tiebreakOnly == 0 {
                    NavigationLink {
                        GamesPerSetView(value: $gamesPerSet, presetGames: preset.gamesPerSet, tennis: preset.sport == "TENNIS")
                    } label: {
                        Text(loc("setup.games %@", String(gamesPerSet == 0 ? preset.gamesPerSet : gamesPerSet)))
                    }
                    if gamesPerSet == 1 {
                        Text("setup.oneGameHint").font(.footnote).foregroundStyle(Brand.text2)
                    }
                }
                Toggle("setup.serveStats", isOn: $serveStats)
            }
            Section {
                Text("setup.player.self")
                if doubles { playerSlot("PARTNER", selection: $partnerId) }
                playerSlot("OPPONENT_1", selection: $opponent1Id)
                if doubles { playerSlot("OPPONENT_2", selection: $opponent2Id) }
            } header: {
                Text("setup.players")
            }
            Toggle("controls.workoutAlongside", isOn: $model.workoutAlongside)
            if tennisLike && doubles {
                Button("setup.startMatch") { start("SELF") }
                    .frame(minHeight: 44)
            } else {
            Section {
                ForEach(slots, id: \.self) { slot in
                    Button {
                        start(slot)
                    } label: {
                        Text(name(slot)).frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .tint(slot == "SELF" || slot == "PARTNER" ? Brand.us : Brand.them)
                }
            } header: {
                Text("setup.whoServes")
            }
            }
        }
        .navigationTitle(LocalizedStringKey("sport." + preset.sport))
    }

    private func start(_ slot: String) {
        model.startConfigured(
            presetId: preset.id, doubles: doubles, gamesPerSet: gamesPerSet, serveStats: serveStats,
            partnerId: doubles ? partnerId : "", opponent1Id: opponent1Id,
            opponent2Id: doubles ? opponent2Id : "", firstServerSlot: slot
        )
    }

    private func name(_ slot: String) -> String {
        let id = slot == "PARTNER" ? partnerId : slot == "OPPONENT_1" ? opponent1Id : slot == "OPPONENT_2" ? opponent2Id : ""
        return model.recentPeople.first { $0.id == id }?.displayName.nonBlank ?? SetupSlotText.label(slot, doubles: doubles)
    }

    private func playerSlot(_ slot: String, selection: Binding<String>) -> some View {
        NavigationLink {
            PlayerSelectionView(model: model, selection: selection, occupied: selected.filter { $0 != selection.wrappedValue },
                                placeholder: SetupSlotText.label(slot, doubles: doubles))
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(SetupSlotText.label(slot, doubles: doubles)).font(.footnote).foregroundStyle(Brand.text2)
                Text(name(slot))
            }
        }
    }
}

struct GamesPerSetView: View {
    @Binding var value: Int32
    let presetGames: Int32
    let tennis: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(tennis ? [1, 2, 3, 4, 5, 6, 8, 10] : [1, 2, 3, 4, 5, 6], id: \.self) { games in
                Button {
                    value = Int32(games) == presetGames ? 0 : Int32(games)
                    dismiss()
                } label: {
                    HStack {
                        Text(String(games))
                        Spacer()
                        if Int32(games) == (value == 0 ? presetGames : value) { Image(systemName: "checkmark") }
                    }
                }
            }
        }
        .navigationTitle("setup.gamesPerSet")
    }
}

struct PlayerSelectionView: View {
    @ObservedObject var model: ScoringModel
    @Binding var selection: String
    let occupied: [String]
    let placeholder: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Button {
                selection = ""
                dismiss()
            } label: {
                Text(loc("setup.placeholder %@", placeholder))
            }
            ForEach(model.recentPeople.filter { !occupied.contains($0.id) }, id: \.id) { person in
                Button {
                    selection = person.id
                    dismiss()
                } label: {
                    HStack {
                        Text(person.displayName.nonBlank ?? placeholder)
                        Spacer()
                        if person.id == selection { Image(systemName: "checkmark") }
                    }
                }
            }
            if model.recentPeople.isEmpty {
                Text("setup.noRecentPeople").font(.footnote).foregroundStyle(Brand.text2)
            }
        }
        .navigationTitle("setup.recentPeople")
    }
}

enum SetupSlotText {
    static func label(_ slot: String, doubles: Bool) -> String {
        switch slot {
        case "SELF": return loc("setup.player.self")
        case "PARTNER": return loc("setup.player.partner")
        case "OPPONENT_2": return loc("setup.player.opponent2")
        default: return loc(doubles ? "setup.player.opponent1" : "setup.player.opponent")
        }
    }
}

private extension String {
    var nonBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
