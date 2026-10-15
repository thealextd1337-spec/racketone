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
/// Totzonen: oberste 12 % (Uhrzeit/Systemgesten, dort die Status-Zeile) und das Mittelband zählen nie.
///
/// **Anzeige** (ein anderes Gerät zählt, 04 §4.2 W3): gleiche Darstellung ohne Tap-Flächen, ohne Wisch-Undo und
/// Undo-Button, deutlich „Anzeige · [Gerät] zählt“, „Stand vor X s“; nach > 30 s ohne Zähler das Angebot
/// „Zählen übernehmen“ (mit Bestätigung).
struct ScorePage: View {
    @ObservedObject var model: ScoringModel
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    @Environment(\.isLuminanceReduced) private var dimmed
    @State private var confirmTakeOver = false

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            VStack(spacing: 0) {
                StatusStrip(snap: snap, dimmed: dimmed)
                    .frame(height: height * 0.12)
                    .allowsHitTesting(false)
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
                } else if !snap.isScorer && !dimmed {
                    TakeOverOffer(snap: snap) { confirmTakeOver = true }
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
                if snap.isScorer && !dimmed && dy > 40 && abs(value.translation.width) < dy {
                    model.undo()
                }
            }
        )
        .takeOverConfirmation(isPresented: $confirmTakeOver, snap: snap, model: model)
    }

    @ViewBuilder
    private func half(sideA: Bool) -> some View {
        let team = TeamHalf(
            sideA: sideA,
            snap: snap,
            flash: model.flashSideA == sideA,
            dimmed: dimmed
        )
        if snap.isScorer {
            team
                .contentShape(Rectangle())
                .onTapGesture {
                    if !dimmed { model.tap(sideA: sideA) }
                }
                .allowsHitTesting(!dimmed)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint(Text(LocalizedStringKey(sideA ? "a11y.pointUs" : "a11y.pointThem")))
                .accessibilityAction { model.tap(sideA: sideA) }
        } else {
            // Anzeige: reine Darstellung, Stand als Text für VoiceOver
            team
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }
}

/// Status-Zeile in der oberen Totzone (links, die Uhrzeit rechts bleibt frei).
/// Zähler: „synchron“ / „N ausstehend“ / „nur lokal“. Anzeige: „ANZEIGE · [Gerät] zählt“.
struct StatusStrip: View {
    let snap: WatchScoreSnapshot
    let dimmed: Bool

    var body: some View {
        HStack(spacing: 4) {
            if snap.isScorer {
                Image(systemName: icon)
                    .font(.system(size: 9, weight: .bold))
                Text(scorerText)
            } else if snap.scorerPending {
                // iPhone hat „Meine Uhr“ gewählt; die Übergabe an diese Uhr kommt mit dem nächsten Abgleich
                Text("role.pending")
            } else {
                Text(loc("role.display.short %@", RoleText.device(snap.scorerKind)))
                    .textCase(.uppercase)
            }
            Spacer(minLength: 0)
        }
        .font(.system(size: 11, weight: .semibold))
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .foregroundStyle(color.opacity(dimmed ? 0.65 : 1))
        .padding(.leading, 12)
        .padding(.trailing, 64) // Systemuhrzeit
        .frame(maxHeight: .infinity, alignment: .bottom)
        .accessibilityElement(children: .combine)
    }

    private var scorerText: String {
        if !snap.linkConnected {
            return snap.pendingToPeers > 0 ? loc("sync.localPending %@", String(snap.pendingToPeers)) : loc("sync.local")
        }
        return snap.pendingToPeers > 0 ? loc("sync.pending %@", String(snap.pendingToPeers)) : loc("sync.synced")
    }

    private var icon: String {
        if !snap.linkConnected { return "iphone.slash" }
        return snap.pendingToPeers > 0 ? "arrow.up.circle" : "checkmark.circle"
    }

    private var color: Color {
        if snap.isScorer {
            return snap.linkConnected && snap.pendingToPeers == 0 ? Brand.text2 : Brand.hint
        }
        return Color.white
    }
}

/// Anzeige: „Stand vor X s“ seit dem letzten empfangenen Event; Always-on höchstens minütlich.
struct StalenessLabel: View {
    let snap: WatchScoreSnapshot
    let dimmed: Bool

    var body: some View {
        TimelineView(.periodic(from: .now, by: dimmed ? 60 : 1)) { context in
            Text(RoleText.staleness(snap, now: context.date))
                .font(.system(size: 11, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundStyle(snap.scorerConnected ? Brand.text2 : Brand.hint)
        }
    }
}

/// Angebot „Zählen übernehmen“, sobald der Zähler > 30 s nicht erreichbar ist (04 §3.2).
struct TakeOverOffer: View {
    let snap: WatchScoreSnapshot
    let onTap: () -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            if RoleText.offerTakeOver(snap, now: context.date) {
                Button(action: onTap) {
                    Label("action.takeOver", systemImage: "hand.raised.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Brand.us))
                        .foregroundStyle(Color.black)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Texte zur Rolle (Gerätart des Zählers, Staleness, Übernahme-Angebot).
enum RoleText {
    /// Schwelle für das Übernahme-Angebot (04 §3.2, PLAN F15 offen)
    static let offerAfterSeconds: Double = 30

    static func device(_ kind: String) -> String {
        loc("role.kind." + (kind.isEmpty ? "OTHER" : kind))
    }

    static func staleness(_ s: WatchScoreSnapshot, now: Date) -> String {
        let last = max(s.lastEventReceivedAtMs, s.lastSyncAtMs)
        guard last > 0 else { return loc(s.scorerConnected ? "sync.connected" : "sync.noData") }
        let seconds = max(0, Int(now.timeIntervalSince1970 - Double(last) / 1000))
        if s.scorerConnected && seconds < 5 { return loc("sync.live") }
        return seconds < 120 ? loc("sync.ageSeconds %@", String(seconds)) : loc("sync.ageMinutes %@", String(seconds / 60))
    }

    static func offerTakeOver(_ s: WatchScoreSnapshot, now: Date) -> Bool {
        guard !s.isScorer, !s.matchFinished, s.scorerDisconnectedSinceMs > 0 else { return false }
        return now.timeIntervalSince1970 - Double(s.scorerDisconnectedSinceMs) / 1000 > offerAfterSeconds
    }
}

extension View {
    /// Bestätigung „Zählen übernehmen“ (04 §3.2): „Auf dieser Uhr zählen? Bisher zählt [Gerät] …“.
    func takeOverConfirmation(isPresented: Binding<Bool>, snap: WatchScoreSnapshot, model: ScoringModel) -> some View {
        alert(Text("takeOver.title"), isPresented: isPresented) {
            Button("takeOver.confirm") { model.takeOverScoring() }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text(loc("takeOver.message %@", RoleText.device(snap.scorerKind)))
        }
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
                    if let positions {
                        Text(positions)
                            .font(.system(size: 11, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(Brand.text2)
                            .accessibilityLabel(Text(positionsA11y))
                    }
                    Spacer(minLength: 2)
                    if serving {
                        ServeIndicator(snap: snap, sideA: sideA, dimmed: dimmed)
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

    /// Doppel mit Feldpositionen (Pickleball, Badminton, 02 §8 `positions`): „L2 R1“ = wer links/rechts steht.
    private var positions: String? {
        let right = sideA ? snap.rightNoA : snap.rightNoB
        let left = sideA ? snap.leftNoA : snap.leftNoB
        guard snap.doubles, right > 0, left > 0 else { return nil }
        return loc("serve.court.LEFT") + String(left) + " " + loc("serve.court.RIGHT") + String(right)
    }

    private var positionsA11y: String {
        let right = sideA ? snap.rightNoA : snap.rightNoB
        let left = sideA ? snap.leftNoA : snap.leftNoB
        return loc("a11y.positions %@ %@", String(left), String(right))
    }
}

/// Aufschlag: gefüllter Ball-Punkt + Feld („R“/„L“); Tischtennis: verbleibende Aufschläge als Punkte.
/// Doppel: Aufschläger → Rückschläger als Spielernummern in Teamfarben („2 → 1“, 02 §8.4).
struct ServeIndicator: View {
    let snap: WatchScoreSnapshot
    let sideA: Bool
    let dimmed: Bool

    var body: some View {
        let color = Brand.team(sideA: sideA)
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
            }
            if snap.doubles && snap.serverNo > 0 {
                Text(String(snap.serverNo))
                    .foregroundStyle(color)
                if snap.receiverNo > 0 {
                    Text(verbatim: "→")
                        .foregroundStyle(Brand.text2)
                    Text(String(snap.receiverNo))
                        .foregroundStyle(Brand.team(sideA: !sideA))
                }
            }
        }
        .font(.system(size: 11, weight: .bold))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(a11y))
    }

    private var a11y: String {
        guard snap.doubles && snap.serverNo > 0 else { return loc("a11y.serving") }
        let receiver = snap.receiverNo > 0 ? String(snap.receiverNo) : "?"
        return loc("a11y.serveFromTo %@ %@", String(snap.serverNo), receiver)
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
            if snap.isScorer {
                Button(action: model.undo) {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 13, weight: .semibold))
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(Color.white.opacity(dimmed ? 0 : 0.16)))
                }
                .buttonStyle(.plain)
                .disabled(!snap.canUndo || dimmed)
                .accessibilityLabel(Text("action.undo"))
            } else {
                StalenessLabel(snap: snap, dimmed: dimmed)
            }
            summary
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(Color.white.opacity(dimmed ? 0.65 : 1))
            Spacer(minLength: 2)
            if snap.paused {
                Text("status.paused")
                    .font(.system(size: 11, weight: .bold))
                    .textCase(.uppercase)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .foregroundStyle(Color.white)
                    .overlay(Capsule().stroke(Color.white.opacity(0.7), lineWidth: 1.5))
            } else if !snap.hintCode.isEmpty {
                HintPill(snap: snap, dimmed: dimmed)
            } else if heartRate > 0 {
                Label(String(heartRate), systemImage: "heart.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .labelStyle(.titleAndIcon)
                    .foregroundStyle(Color.red.opacity(dimmed ? 0.65 : 1))
            }
        }
        .padding(.horizontal, 6)
    }

    /// Always-on: Puls höchstens minütlich (04 §4.3.6)
    private var heartRate: Int { dimmed ? workout.heartRateAmbient : workout.heartRate }

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
        case .receiveOrderSwapped: return loc("banner.receiveOrder")
        case .healthUnavailable: return loc("banner.health")
        case .storeError: return loc("banner.storeError")
        case let .rejectedInputs(count): return loc("banner.rejectedInputs %@", String(count))
        case let .roleChanged(scorer): return loc(scorer ? "banner.nowScorer" : "banner.nowDisplay")
        }
    }
}

/// W4 Steuerung. Zähler: Rückgängig, Pause, Wassersperre, Action-Taste, Match beenden (mit Rückfrage).
/// Anzeige (04 §2.2): Rolleninfo und „Zählen übernehmen“ (mit Bestätigung) statt der Zähl-Steuerung.
/// Beide: Wassersperre und „Workout auf meiner Uhr“ (unabhängig von der Rolle).
struct ControlsPage: View {
    @ObservedObject var model: ScoringModel
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    @State private var confirmEnd = false
    @State private var confirmTakeOver = false

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                if snap.isScorer {
                    scorerControls
                } else {
                    displayControls
                }
                // Echte Water Lock nur während des Workouts (05 §1.7); entsperren über die Krone
                Button {
                    WKInterfaceDevice.current().enableWaterLock()
                } label: {
                    Label("controls.waterLock", systemImage: "drop.fill")
                        .frame(maxWidth: .infinity)
                }
                .disabled(workout.state != .running && workout.state != .paused)
                Toggle(isOn: Binding(get: { model.workoutWanted(snap) }, set: { model.setWorkout($0, for: snap) })) {
                    Text("controls.workoutAlongside")
                        .font(.footnote)
                }
            }
        }
        .alert("controls.end.confirm", isPresented: $confirmEnd) {
            Button("controls.end", role: .destructive) { model.endMatch() }
            Button("common.cancel", role: .cancel) {}
        }
        .takeOverConfirmation(isPresented: $confirmTakeOver, snap: snap, model: model)
    }

    @ViewBuilder
    private var scorerControls: some View {
        Button(action: model.undo) {
            Label("action.undo", systemImage: "arrow.uturn.backward")
                .frame(maxWidth: .infinity)
        }
        .disabled(!snap.canUndo)
        // Unterbrechung (Regen, Verletzung) – pausiert auch das Workout; Seitenwechsel ist keine Pause
        Button {
            if snap.paused { model.resumeMatch() } else { model.pauseMatch() }
        } label: {
            Label(
                LocalizedStringKey(snap.paused ? "controls.resume" : "controls.pause"),
                systemImage: snap.paused ? "play.fill" : "pause.fill"
            )
            .frame(maxWidth: .infinity)
        }
        .disabled(!snap.paused && snap.status != "IN_PROGRESS")
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

    @ViewBuilder
    private var displayControls: some View {
        VStack(spacing: 2) {
            Text("role.display")
                .font(.headline)
                .textCase(.uppercase)
            Text(snap.scorerPending ? loc("role.pending") : loc("role.scorer %@", RoleText.device(snap.scorerKind)))
                .font(.footnote)
                .foregroundStyle(Brand.text2)
            StalenessLabel(snap: snap, dimmed: false)
        }
        .multilineTextAlignment(.center)
        Button {
            confirmTakeOver = true
        } label: {
            Label("action.takeOver", systemImage: "hand.raised.fill")
                .frame(maxWidth: .infinity)
        }
        .disabled(snap.matchFinished)
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
                // Always-on: höchstens minütlich aktualisiert (04 §4.3.6)
                let bpm = dimmed ? workout.heartRateAmbient : workout.heartRate
                Text(bpm > 0 ? String(bpm) : "--")
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
