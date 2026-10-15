import AdvantlyWatchKit
import SwiftUI
import WatchKit

/// W3–W5: Live-Match als horizontaler Pager (Muster der Workout-App): links Steuerung, Mitte Score, rechts Fitness.
/// Nach 8 s auf einer Nebenseite kehrt die App zum Score zurück (04 §2.2).
struct MatchPagerView: View {
    @ObservedObject var model: ScoringModel
    let snap: WatchScoreSnapshot
    @State private var page = 1
    @State private var returnWork: DispatchWorkItem?

    var body: some View {
        TabView(selection: $page) {
            ControlsPage(model: model, workout: model.workout, snap: snap)
                .tag(0)
            ScorePage(model: model, workout: model.workout, snap: snap)
                .tag(1)
            FitnessPage(workout: model.workout, snap: snap)
                .tag(2)
        }
        .tabViewStyle(.page)
        .onChange(of: page) { _, newPage in
            returnWork?.cancel()
            guard newPage != 1 else { return }
            let work = DispatchWorkItem { page = 1 }
            returnWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: work)
        }
    }
}

/// Scoring-Screen (04 §4.3): oben „Wir“ (Orange), unten „Gegner“ (Blau), Mittelband mit Undo, Stand und Hinweis-Pill.
/// Tap auf eine Hälfte = Punkt (zählt beim Loslassen), Wischen nach unten = Undo, Krone ohne Funktion.
/// Totzonen: oberste 12 % (Uhrzeit/Systemgesten) und das Mittelband zählen nie.
struct ScorePage: View {
    @ObservedObject var model: ScoringModel
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    @Environment(\.isLuminanceReduced) private var dimmed

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            VStack(spacing: 0) {
                Color.clear
                    .frame(height: height * 0.12)
                half(sideA: true)
                    .frame(height: height * 0.36)
                MiddleBand(model: model, workout: workout, snap: snap, dimmed: dimmed)
                    .frame(height: height * 0.16)
                half(sideA: false)
                    .frame(height: height * 0.36)
            }
            .overlay {
                if let banner = model.banner, !dimmed {
                    BannerView(banner: banner)
                }
            }
        }
        .ignoresSafeArea()
        .background(Color.black)
        .onChange(of: dimmed) { _, isDimmed in
            if !isDimmed { model.didWake() }
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 30).onEnded { value in
                let dy = value.translation.height
                if !dimmed && dy > 40 && abs(value.translation.width) < dy {
                    model.undo()
                }
            }
        )
    }

    private func half(sideA: Bool) -> some View {
        TeamHalf(
            sideA: sideA,
            snap: snap,
            flash: model.flashSideA == sideA,
            dimmed: dimmed
        )
        .contentShape(Rectangle())
        .onTapGesture {
            if !dimmed { model.tap(sideA: sideA) }
        }
        .allowsHitTesting(!dimmed)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(Text(LocalizedStringKey(sideA ? "a11y.pointUs" : "a11y.pointThem")))
        .accessibilityAction { model.tap(sideA: sideA) }
    }
}

/// Eine Team-Hälfte: getönte Fläche + Akzentkante, Label, Aufschlag-Indikator, große Punktziffer.
/// Ambient: nur Kontur, Ziffern gedimmt (04 §4.3.6).
struct TeamHalf: View {
    let sideA: Bool
    let snap: WatchScoreSnapshot
    let flash: Bool
    let dimmed: Bool

    var body: some View {
        let color = Brand.team(sideA: sideA)
        let side = sideA ? "A" : "B"
        let serving = snap.serveSide == side
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 12)
                .fill(dimmed ? Color.clear : color.opacity(flash ? 0.38 : 0.14))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(color.opacity(dimmed ? 0.6 : 0), lineWidth: 1.5)
                )
                .overlay(alignment: .leading) {
                    if !dimmed {
                        Rectangle().fill(color).frame(width: 4)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(spacing: 0) {
                HStack(spacing: 4) {
                    Text(LocalizedStringKey(sideA ? "team.us" : "team.them"))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(color)
                    Spacer(minLength: 2)
                    if serving {
                        ServeIndicator(snap: snap, color: color, dimmed: dimmed)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.top, 3)
                Text(sideA ? snap.pointsA : snap.pointsB)
                    .font(.system(size: 120, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.2)
                    .lineLimit(1)
                    .foregroundStyle(Color.white.opacity(dimmed ? 0.65 : 1))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentTransition(.numericText())
            }
        }
        .padding(.horizontal, 4)
    }
}

/// Aufschlag: gefüllter Ball-Punkt + Feld („R“/„L“), Tischtennis: verbleibende Aufschläge als Punkte,
/// Pickleball-Doppel: Aufschlägernummer.
struct ServeIndicator: View {
    let snap: WatchScoreSnapshot
    let color: Color
    let dimmed: Bool

    var body: some View {
        HStack(spacing: 3) {
            if snap.sport == "TABLE_TENNIS" {
                ForEach(0..<max(Int(snap.servesRemaining), 1), id: \.self) { _ in ball }
            } else {
                ball
                if snap.serveCourt == "RIGHT" || snap.serveCourt == "LEFT" {
                    Text(LocalizedStringKey("serve.court." + snap.serveCourt))
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(color)
                }
                if snap.serverNumber > 0 {
                    Text(String(snap.serverNumber))
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(color)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("a11y.serving"))
    }

    private var ball: some View {
        Circle()
            .fill(dimmed ? Color.clear : Brand.hint)
            .overlay(Circle().stroke(Brand.hint, lineWidth: 1))
            .frame(width: 8, height: 8)
    }
}

/// Mittelband: Undo-Button, Sätze/Games (bzw. Pickleball-Ansage), Hinweis-Pill oder Puls.
struct MiddleBand: View {
    @ObservedObject var model: ScoringModel
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    let dimmed: Bool

    var body: some View {
        HStack(spacing: 6) {
            Button(action: model.undo) {
                Image(systemName: "arrow.uturn.backward")
                    .font(.system(size: 13, weight: .semibold))
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Color.white.opacity(dimmed ? 0 : 0.16)))
            }
            .buttonStyle(.plain)
            .disabled(!snap.canUndo || dimmed)
            .accessibilityLabel(Text("action.undo"))
            summary
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(Color.white.opacity(dimmed ? 0.65 : 1))
            Spacer(minLength: 2)
            if !snap.hintCode.isEmpty {
                HintPill(snap: snap, dimmed: dimmed)
            } else if workout.heartRate > 0 {
                Label(String(workout.heartRate), systemImage: "heart.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .labelStyle(.titleAndIcon)
                    .foregroundStyle(Color.red.opacity(dimmed ? 0.65 : 1))
            }
        }
        .padding(.horizontal, 6)
    }

    /// Tennis/Padel: frühere Sätze grau + laufende Games; Pickleball: Ansage „4-2-1“; Badminton/TT: Satzstand.
    private var summary: Text {
        let previous = ScoreText.sets(snap)
        let prefix = previous.isEmpty ? Text(verbatim: "") : Text(verbatim: previous + " ").foregroundStyle(Brand.text2)
        if snap.sport == "PICKLEBALL" {
            return prefix + Text(snap.scoreCall)
        }
        if snap.gamesA >= 0 {
            return prefix + Text(ScoreText.pair(snap.gamesA, snap.gamesB))
        }
        return Text(ScoreText.pair(snap.setsWonA, snap.setsWonB))
    }
}

/// Hinweis-Pill (Amber, Text statt nur Farbe): Kontur für neutrale Hinweise, gefüllt für Break-/Satz-/Matchball.
struct HintPill: View {
    let snap: WatchScoreSnapshot
    let dimmed: Bool

    var body: some View {
        let filled = !dimmed && ["BREAK_POINT", "SET_POINT", "MATCH_POINT"].contains(snap.hintCode)
        Text(Self.text(snap))
            .font(.system(size: 11, weight: .bold))
            .textCase(.uppercase)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .foregroundStyle(filled ? Color.black : Brand.hint)
            .background(Capsule().fill(filled ? Brand.hint : Color.clear))
            .overlay(Capsule().stroke(Brand.hint, lineWidth: 1.5))
    }

    /// Schlüssel `hint.<CODE>[_PB|_RALLY][.n %@]` – Pickleball-Satzball heißt Spielball, B/TT-Einstand „Verlängerung“.
    static func text(_ s: WatchScoreSnapshot) -> String {
        var key = s.hintCode
        if key == "SET_POINT" && s.sport == "PICKLEBALL" {
            key += "_PB"
        }
        if (key == "DEUCE" || key == "DECIDING_POINT") && SportStyle.isRally(s.sport) {
            key += "_RALLY"
        }
        if s.hintCount > 1 {
            return loc("hint." + key + ".n %@", String(s.hintCount))
        }
        return loc("hint." + key)
    }
}

struct BannerView: View {
    let banner: Banner

    var body: some View {
        Text(text)
            .font(.system(size: 14, weight: .semibold))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color(hex: 0x1E2128)))
            .overlay(Capsule().stroke(Color.white.opacity(0.2), lineWidth: 1))
            .padding(.horizontal, 6)
            .transition(.opacity)
            .accessibilityAddTraits(.updatesFrequently)
    }

    private var text: String {
        switch banner {
        case let .undo(from, to): return loc("banner.undo %@ %@", from, to)
        case .changeEnds: return loc("banner.changeEnds")
        case .healthUnavailable: return loc("banner.health")
        }
    }
}

/// W4 Steuerung: Rückgängig, Wassersperre, Belegung der Action-Taste, Match beenden (mit Rückfrage).
struct ControlsPage: View {
    @ObservedObject var model: ScoringModel
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    @State private var confirmEnd = false

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Button(action: model.undo) {
                    Label("action.undo", systemImage: "arrow.uturn.backward")
                        .frame(maxWidth: .infinity)
                }
                .disabled(!snap.canUndo)
                // Echte Water Lock nur während des Workouts (05 §1.7); entsperren über die Krone
                Button {
                    WKInterfaceDevice.current().enableWaterLock()
                } label: {
                    Label("controls.waterLock", systemImage: "drop.fill")
                        .frame(maxWidth: .infinity)
                }
                .disabled(workout.state != .running)
                Button {
                    model.actionButtonMode = model.actionButtonMode.next
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("controls.actionButton")
                        Text(LocalizedStringKey("controls.actionButton." + model.actionButtonMode.rawValue))
                            .font(.footnote)
                            .foregroundStyle(Brand.hint)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button(role: .destructive) {
                    confirmEnd = true
                } label: {
                    Label("controls.end", systemImage: "xmark")
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .alert("controls.end.confirm", isPresented: $confirmEnd) {
            Button("controls.end", role: .destructive) { model.endMatch() }
            Button("common.cancel", role: .cancel) {}
        }
    }
}

/// W5 Fitness: Puls groß, Dauer. Puls nur anzeigen (P1), ohne Health-Zugriff ein Hinweis.
struct FitnessPage: View {
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    @Environment(\.isLuminanceReduced) private var dimmed

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "heart.fill")
                    .foregroundStyle(Color.red)
                Text(workout.heartRate > 0 ? String(workout.heartRate) : "--")
                    .font(.system(size: 44, weight: .bold, design: .rounded))
                    .monospacedDigit()
            }
            Text("fitness.heartRate")
                .font(.footnote)
                .foregroundStyle(Brand.text2)
            // Always-on: höchstens 1 Aktualisierung pro Minute
            TimelineView(.periodic(from: .now, by: dimmed ? 60 : 1)) { context in
                Text(Self.duration(since: snap.startedAtMs, now: context.date))
                    .font(.system(size: 22, weight: .semibold, design: .rounded))
                    .monospacedDigit()
            }
            if workout.state == .unavailable {
                Text("fitness.noHealth")
                    .font(.footnote)
                    .foregroundStyle(Brand.text2)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 6)
    }

    static func duration(since startedAtMs: Int64, now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince1970 - Double(startedAtMs) / 1000))
        return String(format: "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }
}
