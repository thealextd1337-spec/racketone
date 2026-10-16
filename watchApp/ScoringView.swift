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
/// Ein Ziel je Berührung, bestimmt beim Aufsetzen. Das Mittelband bleibt bis zum Loslassen nicht-zählend,
/// auch wenn die Aufschlag-Leiste zwischenzeitlich verschwindet. Halten (1 s, Zeit aus der Facade) zieht den letzten
/// Punkt der Hälfte ab, sobald die Haltezeit erreicht ist – eine Füllung läuft dabei von rechts nach links über die
/// Hälfte und ist genau beim Auslösen voll; früher loslassen oder Bewegen bricht ab (Feldtest 2026-10-16).
private enum ScoreTouchTarget {
    case half(sideA: Bool, canRemove: Bool)
    case ignored
}

struct ScorePage: View {
    @ObservedObject var model: ScoringModel
    @ObservedObject var workout: WorkoutManager
    let snap: WatchScoreSnapshot
    @Environment(\.isLuminanceReduced) private var dimmed
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmTakeOver = false
    @State private var chooseServer = false
    @State private var target: ScoreTouchTarget?
    @State private var touchId: SpatialEventCollection.Event.ID?
    @State private var touchIds: Set<SpatialEventCollection.Event.ID> = []
    @State private var touchMatchId = ""
    @State private var touchStart: TimeInterval = 0
    @State private var touchStartLocation = CGPoint.zero
    @State private var furthestTravel: CGFloat = 0
    /// Laufendes Halten: Start, Hälfte, ausgelöst (Füllung voll bis zum Loslassen)
    @State private var holdStart: Date?
    @State private var holdSideA = true
    @State private var holdFired = false
    @State private var holdWork: DispatchWorkItem?

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            let deadTop = height * 0.12
            let action = !dimmed && OperationStrip.hasAction(model, snap)
            let middle = action ? max(44, height * 0.16) : height * 0.16
            let guardHeight: CGFloat = action ? 4 : 0
            let halfHeight = (height - deadTop - middle - 2 * guardHeight) / 2
            let middleTop = deadTop + halfHeight
            VStack(spacing: 0) {
                StatusStrip(snap: snap, dimmed: dimmed)
                    .frame(height: deadTop)
                    .allowsHitTesting(false)
                half(sideA: true).frame(height: halfHeight)
                Color.clear.frame(height: guardHeight)
                if action {
                    OperationStrip(model: model, snap: snap) { chooseServer = true }
                        .frame(height: middle)
                        .padding(.horizontal, 6)
                } else {
                    MiddleBand(model: model, workout: workout, snap: snap, dimmed: dimmed)
                        .frame(height: middle)
                }
                Color.clear.frame(height: guardHeight)
                half(sideA: false).frame(height: halfHeight)
            }
            .contentShape(Rectangle())
            .simultaneousGesture(
                SpatialEventGesture(coordinateSpace: .local)
                    .onChanged { events in
                        trackTouch(events, deadTop: deadTop, middleTop: middleTop, middleBottom: middleTop + middle + 2 * guardHeight)
                    }
                    .onEnded { events in
                        let fired = holdFired
                        defer {
                            target = nil
                            touchId = nil
                            touchIds = []
                            cancelHold()
                        }
                        // Halten hat schon ausgelöst: Loslassen tut nichts mehr
                        if fired { return }
                        guard touchIds.count == 1, events.count == 1, let event = events.first,
                              event.id == touchId, event.phase == .ended,
                              let target, snap.matchId == touchMatchId, snap.isScorer, !dimmed, scenePhase == .active else { return }
                        guard case let .half(sideA, _) = target else { return }
                        let dx = event.location.x - touchStartLocation.x
                        let dy = event.location.y - touchStartLocation.y
                        if dy > 40 && abs(dx) < dy {
                            model.undo()
                        } else if furthestTravel < 14 && abs(dx) < 14 && abs(dy) < 14 {
                            // kürzer als die Haltezeit = Tap; länger ohne Abzugsmöglichkeit tut nichts (mit Abzug hat
                            // das Halten schon beim Erreichen der Haltezeit ausgelöst)
                            if event.timestamp - touchStart < ScoringModel.holdToRemove {
                                model.tap(sideA: sideA)
                            }
                        }
                    }
            )
            .overlay {
                if let banner = model.banner, banner != .pointRemoved, !dimmed {
                    BannerView(banner: banner).allowsHitTesting(false)
                } else if !snap.isScorer && !dimmed {
                    TakeOverOffer(snap: snap) { confirmTakeOver = true }
                }
            }
        }
        .ignoresSafeArea()
        .background(Color.black)
        .onChange(of: dimmed) { _, _ in
            target = nil
            cancelHold()
        }
        .onChange(of: scenePhase) { _, phase in
            target = nil
            if phase != .active {
                model.dismissServeBar()
            }
        }
        .onChange(of: snap.isScorer) { _, _ in
            target = nil
        }
        .onChange(of: snap.matchId) { _, _ in target = nil }
        .onDisappear {
            target = nil
            cancelHold()
        }
        .sheet(isPresented: $chooseServer) {
            ServerChoiceView(model: model, window: ServerChoiceView.key(snap))
        }
        .takeOverConfirmation(isPresented: $confirmTakeOver, snap: snap, model: model)
    }

    private func trackTouch(_ events: SpatialEventCollection, deadTop: CGFloat, middleTop: CGFloat, middleBottom: CGFloat) {
        guard !events.isEmpty else { return }
        // Neue IDs erkennen auch nach einem Abbruch ohne onEnded eine neue Berührung – selbst am selben Ort.
        if events.allSatisfy({ !touchIds.contains($0.id) }) {
            target = nil
            touchId = nil
            touchIds = []
        }
        touchIds.formUnion(events.map(\.id))
        // Mehrfinger/Handballen sperren die gesamte Folge, auch nachdem der erste Finger losgelassen wurde.
        guard touchIds.count == 1, let event = events.first else {
            target = .ignored
            cancelHold()
            return
        }
        if event.phase == .cancelled {
            target = nil
            cancelHold()
            return
        }
        if touchId != event.id && event.phase == .active {
            touchId = event.id
            touchStart = event.timestamp
            touchStartLocation = event.location
            touchMatchId = snap.matchId
            furthestTravel = 0
            let y = event.location.y
            if snap.isScorer && !dimmed && scenePhase == .active && y >= deadTop && y < middleTop {
                target = .half(sideA: true, canRemove: snap.canRemovePointA)
                if snap.canRemovePointA { startHold(sideA: true) }
            } else if snap.isScorer && !dimmed && scenePhase == .active && y > middleBottom {
                target = .half(sideA: false, canRemove: snap.canRemovePointB)
                if snap.canRemovePointB { startHold(sideA: false) }
            } else {
                target = .ignored
            }
        }
        furthestTravel = max(furthestTravel, max(abs(event.location.x - touchStartLocation.x), abs(event.location.y - touchStartLocation.y)))
        if furthestTravel >= 14 && !holdFired { cancelHold() }
    }

    /// Halten beginnt: Füllung startet, nach der Haltezeit wird abgezogen, solange der Finger still liegt.
    private func startHold(sideA: Bool) {
        cancelHold()
        holdSideA = sideA
        holdFired = false
        holdStart = Date()
        let matchId = snap.matchId
        let work = DispatchWorkItem {
            guard holdStart != nil, !holdFired, furthestTravel < 14, touchIds.count == 1, model.snapshot?.matchId == matchId,
                  case let .half(side, canRemove)? = target, side == sideA, canRemove, !dimmed, scenePhase == .active else { return }
            holdFired = true
            model.removePoint(sideA: sideA)
        }
        holdWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + ScoringModel.holdToRemove, execute: work)
    }

    /// Loslassen/Abbruch: Timer weg, Füllung läuft kurz zurück (ausblenden).
    private func cancelHold() {
        holdWork?.cancel()
        holdWork = nil
        guard holdStart != nil else { return }
        withAnimation(.easeOut(duration: 0.18)) {
            holdStart = nil
            holdFired = false
        }
    }

    @ViewBuilder
    private func half(sideA: Bool) -> some View {
        let team = TeamHalf(sideA: sideA, snap: snap, flash: model.flashSideA == sideA, dimmed: dimmed)
            .overlay {
                if let start = holdStart, holdSideA == sideA, !dimmed {
                    HoldFill(start: start, fired: holdFired, color: Brand.team(sideA: sideA))
                        .transition(.opacity)
                }
            }
        if snap.isScorer && !dimmed {
            team
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint(Text(LocalizedStringKey(sideA ? "a11y.pointUs" : "a11y.pointThem")))
                .accessibilityAction { model.tap(sideA: sideA) }
                .accessibilityAction(named: Text(LocalizedStringKey(sideA ? "action.removeUs" : "action.removeThem"))) {
                    model.removePoint(sideA: sideA)
                }
        } else {
            team.accessibilityElement(children: .combine).accessibilityAddTraits(.updatesFrequently)
        }
    }
}

/// Halte-Fortschritt über einer Hälfte: Füllung von rechts nach links (wie der Restzeit-Balken der Aufschlag-Leiste),
/// voll genau nach der Haltezeit bzw. sobald ausgelöst. „Bewegung reduzieren“: Schritte statt flüssiger Animation.
struct HoldFill: View {
    let start: Date
    let fired: Bool
    let color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: reduceMotion ? 0.25 : 1.0 / 30)) { context in
            let fraction = fired ? 1 : min(1, max(0, context.date.timeIntervalSince(start) / ScoringModel.holdToRemove))
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 12)
                    .fill(color.opacity(0.35))
                    .frame(width: geo.size.width * fraction)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
            }
        }
        .padding(.horizontal, 4)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Status-Zeile in der oberen Totzone (links, die Uhrzeit rechts bleibt frei).
/// Zähler: „synchron“ / „N ausstehend“ / „nur lokal“. Anzeige: „ANZEIGE · [Gerät] zählt“.
struct StatusStrip: View {
    let snap: WatchScoreSnapshot
    let dimmed: Bool

    var body: some View {
        HStack(spacing: 4) {
            if !snap.updateRequired.isEmpty {
                Text(LocalizedStringKey(snap.updateRequired == "THIS_DEVICE" ? "sync.updateWatch.short" : "sync.updatePhone.short"))
            } else if snap.isScorer {
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
                if tennisDoubles, let server = snap.serverPlayer {
                    // Tennis/Padel-Doppel: Aufschläger je Game mit Namen, dahinter das Feld R/L – kein „auf wen“
                    Text(verbatim: PlayerText.name(server, doubles: true))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(color)
                }
                if snap.serveCourt == "RIGHT" || snap.serveCourt == "LEFT" {
                    Text(LocalizedStringKey("serve.court." + snap.serveCourt))
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(color)
                }
            }
            if tennisDoubles && snap.serverPlayer != nil {
                EmptyView()
            } else if snap.doubles && snap.serverNo > 0 {
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

    private var tennisDoubles: Bool { snap.doubles && (snap.sport == "TENNIS" || snap.sport == "PADEL") }

    private var a11y: String {
        if tennisDoubles, let server = snap.serverPlayer {
            let name = PlayerText.name(server, doubles: true)
            if snap.serveCourt == "RIGHT" || snap.serveCourt == "LEFT" {
                return loc("a11y.serverFrom." + snap.serveCourt + " %@", name)
            }
            return loc("a11y.serverOnly %@", name)
        }
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

    /// Schlüssel `hint.<CODE>[_PB|_RALLY|_PADEL][.n %@]` – Pickleball-Satzball heißt Spielball, B/TT-Einstand
    /// „Verlängerung“, Padel-Match-Tiebreak „Super-TB“.
    static func text(_ s: WatchScoreSnapshot) -> String {
        var key = s.hintCode
        if key == "SET_POINT" && s.sport == "PICKLEBALL" {
            key += "_PB"
        }
        if (key == "DEUCE" || key == "DECIDING_POINT") && SportStyle.isRally(s.sport) {
            key += "_RALLY"
        }
        // Tennis: Champions-Tiebreak, Padel: Super-Tiebreak (gleicher Hinweis-Code der Facade)
        if key == "MATCH_TIEBREAK" && s.sport == "PADEL" {
            key += "_PADEL"
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
        case .pointRemoved: return loc("banner.pointRemoved")
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
    @State private var chooseServer = false
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
        .sheet(isPresented: $chooseServer) {
            ServerChoiceView(model: model, window: ServerChoiceView.key(snap))
        }
        .alert("controls.end.confirm", isPresented: $confirmEnd) {
            Button("controls.end", role: .destructive) { model.endMatch() }
            Button("common.cancel", role: .cancel) {}
        }
        .takeOverConfirmation(isPresented: $confirmTakeOver, snap: snap, model: model)
    }

    @ViewBuilder
    private var scorerControls: some View {
        UpdateRequiredLabel(code: snap.updateRequired)
        RemovePointButtons(model: model, snap: snap)
        if model.banner == .pointRemoved && snap.canRedo {
            Button("action.restorePoint", action: model.redoRemoval)
        }
        if snap.serverChoiceGame > 0 {
            Button("serve.choose") { chooseServer = true }
        }
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
        UpdateRequiredLabel(code: snap.updateRequired)
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
