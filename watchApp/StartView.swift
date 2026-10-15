import AdvantlyWatchKit
import SwiftUI

/// W1 Startliste: „Wie letztes Mal“, fünf Sportarten (Icon + Farbe + Name), danach Preset → Disziplin → Aufschlag.
struct StartView: View {
    @ObservedObject var model: ScoringModel
    /// Debug-Zeile (Store-Smoke, Engine), leer in Release
    var debugInfo: String = ""

    var body: some View {
        NavigationStack {
            List {
                if let last = model.lastSetup {
                    Section {
                        Button(action: model.startLast) {
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
                Section {
                    NavigationLink("link.debug.open") { LinkDebugView() }
                    if !debugInfo.isEmpty {
                        Text(debugInfo).font(.footnote).foregroundStyle(Brand.text2)
                    }
                }
                #endif
            }
            .navigationTitle("Advantly")
        }
    }

    private func setupDetail(_ setup: WatchMatchSetup) -> String {
        let discipline = loc(setup.doubles ? "setup.doubles" : "setup.singles")
        let serve = loc(setup.weServeFirst ? "setup.weServe" : "setup.theyServe")
        return discipline + " · " + serve
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

/// Disziplin (falls wählbar) und Erstaufschläger; ein Tap startet das Match.
struct SetupView: View {
    @ObservedObject var model: ScoringModel
    let preset: WatchPreset
    @State private var doubles: Bool

    init(model: ScoringModel, preset: WatchPreset) {
        self.model = model
        self.preset = preset
        _doubles = State(initialValue: preset.defaultDoubles)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text(LocalizedStringKey("preset." + preset.id))
                    .font(.footnote)
                    .foregroundStyle(Brand.text2)
                    .multilineTextAlignment(.center)
                if preset.singles && preset.doubles {
                    HStack(spacing: 6) {
                        disciplineButton(doubles: false)
                        disciplineButton(doubles: true)
                    }
                }
                Text("setup.whoServes").font(.headline).padding(.top, 4)
                Button {
                    model.start(presetId: preset.id, doubles: doubles, weServeFirst: true)
                } label: {
                    Text("setup.weServe").frame(maxWidth: .infinity)
                }
                .tint(Brand.us)
                .buttonStyle(.borderedProminent)
                Button {
                    model.start(presetId: preset.id, doubles: doubles, weServeFirst: false)
                } label: {
                    Text("setup.theyServe").frame(maxWidth: .infinity)
                }
                .tint(Brand.them)
                .buttonStyle(.borderedProminent)
            }
        }
        .navigationTitle(LocalizedStringKey("sport." + preset.sport))
    }

    private func disciplineButton(doubles value: Bool) -> some View {
        Button {
            doubles = value
        } label: {
            Text(LocalizedStringKey(value ? "setup.doubles" : "setup.singles"))
                .font(.footnote.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .tint(doubles == value ? SportStyle.of(preset.sport).color : Color.gray)
        .buttonStyle(.bordered)
        .accessibilityAddTraits(doubles == value ? .isSelected : [])
    }
}
