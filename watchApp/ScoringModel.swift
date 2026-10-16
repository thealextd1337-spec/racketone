import AdvantlyWatchKit
import Foundation
import SwiftUI

/// Was die Action-Taste (Ultra) im Match tut – Feature-Flag nach 05 §1.7 (Default: Rückgängig).
/// Risiko R2 (Intent-Spende seit watchOS 26.5 fehlerhaft): der Undo-Button auf dem Display bleibt immer.
enum ActionButtonMode: String, CaseIterable {
    case undo
    case pointUs
    case off

    static let key = "advantly.watch.actionButton"

    static var stored: ActionButtonMode {
        get { ActionButtonMode(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .undo }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }

    var next: ActionButtonMode {
        let all = ActionButtonMode.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

/// Kurzer Hinweis über dem Scoring-Screen (3 s).
enum Banner: Equatable {
    case undo(from: String, to: String)
    case pointRemoved
    case changeEnds
    case receiveOrderSwapped
    case healthUnavailable
    /// Event nicht gespeichert (SQLite-Fehler) – Zustand unverändert, nochmal tippen
    case storeError
    /// Die Engine hat Eingaben eines Nicht-Zählers verworfen (nach einer Übergabe)
    case rejectedInputs(Int)
    /// Rolle gewechselt: diese Uhr zählt jetzt (`true`) bzw. zeigt nur noch an
    case roleChanged(scorer: Bool)
}

/// Zustand der Watch-App. Alle Zähl-Logik liegt in Kotlin (`WatchScoring` über die Match-Session); Swift übernimmt
/// Gesten, Sperrzeit, Haptik, Workout-Session und Anzeige. Single-Scorer: Zählt ein anderes Gerät, ist die Uhr
/// Anzeige (keine Zähl-/Undo-Aktionen, auch nicht über die Action-Taste). Alles läuft auf der Main-Queue.
final class ScoringModel: NSObject, ObservableObject, WatchScoringListener {
    static let shared = ScoringModel()

    /// Sperrzeit nach jedem Punkt für beide Flächen (04 §4.3.3, Entprellung).
    static let lockInterval: TimeInterval = 0.6

    let scoring = AdvantlyWatch.shared.scoring()
    let workout = WorkoutManager()

    @Published private(set) var snapshot: WatchScoreSnapshot?
    @Published private(set) var lockedUntil = Date.distantPast
    /// Kurzes Aufleuchten der getippten Hälfte (120 ms)
    @Published private(set) var flashSideA: Bool?
    @Published private(set) var banner: Banner?
    @Published private(set) var lastSetup: WatchMatchSetup?
    @Published private(set) var recentPeople: [WatchPlayer] = []
    @Published private(set) var updateRequired = ""
    /// Ausschließlich nach einem lokalen Punkt sichtbar; Sync-/Ack-Updates verlängern die Frist nicht.
    @Published private(set) var serveBarPointId = ""
    private var serveBarWork: DispatchWorkItem?
    /// Ausblendzeitpunkt der Aufschlag-Leiste (Restzeit-Anzeige in den Segmenten).
    @Published private(set) var serveBarDeadline = Date.distantPast
    /// Zeiten aus der Kotlin-Facade (gleich wie Wear OS).
    static let serveBarVisible = TimeInterval(AdvantlyWatch.shared.serveBarVisibleMillis()) / 1000
    static let serveBarAfterChoice = TimeInterval(AdvantlyWatch.shared.serveBarAfterChoiceMillis()) / 1000
    static let holdToRemove = TimeInterval(AdvantlyWatch.shared.holdToRemoveMillis()) / 1000
    @Published var actionButtonMode = ActionButtonMode.stored {
        didSet {
            ActionButtonMode.stored = actionButtonMode
            donateActionIntent()
        }
    }

    /// Workout-Session an das Match koppeln. Der CI-Demo-Modus schaltet sie ab, damit kein HealthKit-Dialog
    /// die Screenshots verdeckt.
    var workoutEnabled = true

    /// Voreinstellung „Workout auf meiner Uhr mitlaufen lassen“ (04 §3.1, Standard an) für auf der Uhr gestartete
    /// Matches; landet als `MatchMeta.watchWorkout` im Match. Vom iPhone gestartete Matches bringen dessen Wahl mit.
    @Published var workoutAlongside = UserDefaults.standard.object(forKey: ScoringModel.workoutKey) as? Bool ?? true {
        didSet { UserDefaults.standard.set(workoutAlongside, forKey: Self.workoutKey) }
    }

    /// Umschalten während des Matches (Steuerseite) – nur lokal auf dieser Uhr, je Match.
    @Published private(set) var workoutOverrides: [String: Bool] = [:]

    static let workoutKey = "advantly.watch.workoutAlongside"

    /// Datenbank nicht lesbar (z. B. Schema aus der Zeit vor der Match-Session) → Hinweis auf der Startliste.
    @Published private(set) var storeUnavailable = false

    private var bannerWork: DispatchWorkItem?
    /// Nach dem Aufwecken (Always-on → aktiv) zählen Taps 300 ms lang still nicht (04 §4.3.3 Punkt 4).
    private var silentUntil = Date.distantPast
    @Published private(set) var serverChoicePresented = false
    @Published private(set) var serverChoiceContext: WatchServerChoiceContext?
    private var choiceInteractive = false
    private var handledChoices: Set<String> = []

    static func choiceWindow(_ context: WatchServerChoiceContext) -> String {
        "\(context.matchId):\(context.setIndex):\(context.phase)"
    }

    static func choiceSignature(_ context: WatchServerChoiceContext?) -> String {
        guard let c = context else { return "" }
        return [choiceWindow(c), c.dueSide, c.defaultServer, c.chosen, c.gameKind,
                c.players.joined(separator: "|")].joined(separator: ":")
    }

    func setChoiceInteractive(_ active: Bool) {
        choiceInteractive = active
        updateServerChoice(previous: snapshot)
    }

    func openServerChoice() {
        guard choiceInteractive, let snap = snapshot, snap.isScorer, let context = snap.serverChoiceContext else { return }
        serverChoiceContext = context
        serverChoicePresented = true
    }

    func dismissServerChoice() {
        if let context = serverChoiceContext { handledChoices.insert(Self.choiceWindow(context)) }
        serverChoicePresented = false
        serverChoiceContext = nil
        silentUntil = Date().addingTimeInterval(0.3)
    }

    private func updateServerChoice(previous: WatchScoreSnapshot?) {
        let next = snapshot
        let context = next?.serverChoiceContext
        let old = previous?.serverChoiceContext
        if let context {
            let window = Self.choiceWindow(context)
            let reopened = next?.matchId == previous?.matchId && (next?.pointsPlayed ?? 0) < (previous?.pointsPlayed ?? 0)
            let cleared = old != nil && Self.choiceWindow(old!) == window && !(old?.chosen.isEmpty ?? true) && context.chosen.isEmpty
            if reopened || cleared { handledChoices.remove(window) }
        }
        guard next?.isScorer == true, let context else {
            serverChoicePresented = false
            serverChoiceContext = nil
            return
        }
        if serverChoicePresented && Self.choiceSignature(serverChoiceContext) != Self.choiceSignature(context) {
            serverChoicePresented = false
            serverChoiceContext = nil
        }
        let needsReview = context.chosen.isEmpty || (next?.pointsPlayed == 0 && next?.canRedo == true)
        if choiceInteractive && needsReview && !handledChoices.contains(Self.choiceWindow(context)) {
            serverChoiceContext = context
            serverChoicePresented = true
        }
    }

    override private init() {
        super.init()
        scoring.setListener(listener: self)
        lastSetup = scoring.lastSetup()
        refreshStartInfo()
        workout.onUnavailable = { [weak self] in
            if self?.snapshot != nil { self?.show(.healthUnavailable) }
        }
        workout.onRunning = { [weak self] in self?.donateActionIntent() }
        workout.onRecoveryFailed = { [weak self] in self?.syncWorkout() }
        workout.onSystemPauseChange = { [weak self] paused in
            guard let self, self.snapshot?.isScorer == true else { return }
            if paused { self.pauseMatch() } else { self.resumeMatch() }
        }
    }

    // MARK: WatchScoringListener

    /// Jeder neue Zustand – auch durch das iPhone (Anzeige, Übergabe, Abgleich); `nil` = kein Match offen.
    func onSnapshot(snapshot: WatchScoreSnapshot?) {
        let before = self.snapshot
        self.snapshot = snapshot
        updateServerChoice(previous: before)
        // Mit Match kommt der Versionshinweis direkt aus dem Snapshot; ohne Match liest der Getter nur Session-State.
        updateRequired = snapshot?.updateRequired ?? scoring.updateRequired()
        if snapshot?.matchId != before?.matchId {
            recentPeople = scoring.recentPeople()
        }
        if snapshot?.matchId != before?.matchId || snapshot?.isScorer != true ||
            (!serveBarPointId.isEmpty && snapshot?.lastPointId != serveBarPointId) {
            dismissServeBar()
        }
        if let snapshot, let before, before.matchId == snapshot.matchId {
            // Nur neue Match-Events als Anzeige auswerten. Sync-/Ack-Snapshots tragen dieselben Effekte;
            // der Zähler erhält seine Rückmeldung bereits nach dem lokalen Befehl.
            if !snapshot.isScorer && snapshot.eventCount != before.eventCount {
                afterCommand(snapshot)
            }
            if before.isScorer != snapshot.isScorer {
                show(.roleChanged(scorer: snapshot.isScorer))
                Haptics.play(snapshot.isScorer ? .success : .directionDown)
            } else if snapshot.rejectedByRoleCount > before.rejectedByRoleCount {
                show(.rejectedInputs(Int(snapshot.rejectedByRoleCount)))
                Haptics.play(.notification)
            }
        }
        // Eingaben im früheren Uhr-Match nach der bestätigten Übernahme durchs iPhone verworfen (F22): einmal zeigen, quittieren
        if let snapshot, snapshot.droppedInputs > 0 {
            show(.rejectedInputs(Int(snapshot.droppedInputs)))
            scoring.acknowledgeDroppedInputs()
        }
        if snapshot == nil {
            workout.end()
        } else {
            syncWorkout()
        }
    }

    /// Personenverzeichnis nur beim Betreten des Starts oder beim Matchwechsel aus der Datenbank lesen.
    func refreshStartInfo() {
        recentPeople = scoring.recentPeople()
        updateRequired = scoring.updateRequired()
    }

    /// Workout-Session läuft genau so lange wie das Match (Start mit dem Match, Ende mit dem Matchende) und ist
    /// pausiert, solange das Match unterbrochen ist.
    private func syncWorkout() {
        guard let snap = snapshot else { return }
        if snap.matchFinished || !workoutEnabled || !workoutWanted(snap) {
            workout.end()
        } else {
            workout.ensureRunning(
                matchId: snap.matchId,
                sport: snap.sport,
                presetId: snap.presetId,
                doubles: snap.doubles,
                paused: snap.isScorer ? snap.paused : nil
            )
        }
    }

    /// Workout für dieses Match: lokale Umschaltung, sonst der Wunsch aus `MatchMeta.watchWorkout`.
    func workoutWanted(_ snap: WatchScoreSnapshot) -> Bool {
        workoutOverrides[snap.matchId] ?? snap.watchWorkout
    }

    func setWorkout(_ wanted: Bool, for snap: WatchScoreSnapshot) {
        workoutOverrides[snap.matchId] = wanted
        syncWorkout()
    }

    /// Nach lokalem Befehl oder neuem Event als Anzeige: Hinweise und Haptik aus den Effekten.
    private func afterCommand(_ snap: WatchScoreSnapshot) {
        if snap.changeEnds {
            show(.changeEnds)
            Haptics.after(0.7, .start)
        } else if snap.receiveOrderSwapped {
            show(.receiveOrderSwapped)
            Haptics.after(0.7, .start)
        }
    }

    /// Befehl abgelehnt: Speicherfehler sichtbar machen, sonst nur Ablehnungs-Haptik.
    private func rejected() {
        let error = scoring.lastError()
        if error == "STORE_WRITE" || error == "STORE_READ" {
            print("ADVANTLY_STORE error=\(error)")
            show(.storeError)
        } else if error == "THREAD_VIOLATION" {
            print("ADVANTLY_THREAD WatchScoring nur auf der Main-Queue aufrufen")
        }
        Haptics.rejected()
    }

    // MARK: Start

    /// Nach App-Start: laufendes Match aus dem Event-Log fortsetzen.
    func resumeIfNeeded() {
        storeUnavailable = !scoring.isAvailable()
        if snapshot == nil, scoring.restore() == nil, !scoring.lastError().isEmpty {
            print("ADVANTLY_STORE restore=\(scoring.lastError())")
        }
    }

    /// App wieder im Vordergrund: sofort mit dem iPhone abgleichen.
    func syncNow() {
        scoring.syncNow()
    }

    /// „Zählen übernehmen“ – erst nach Bestätigung in der UI aufrufen (04 §3.2).
    func takeOverScoring() {
        guard scoring.takeOverScoring() != nil else {
            rejected()
            return
        }
        lockedUntil = .distantPast
    }

    var sports: [String] { scoring.sports() }

    /// Presets der Sportart, Default zuerst (Badminton-Default nach heutigem Datum).
    func presets(for sport: String) -> [WatchPreset] {
        let today = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return scoring.presets(
            sport: sport,
            year: Int32(today.year ?? 2026),
            month: Int32(today.month ?? 1),
            day: Int32(today.day ?? 1)
        )
    }

    @discardableResult
    func start(presetId: String, doubles: Bool, weServeFirst: Bool) -> Bool {
        let started = scoring.startConfiguredMatch(
            presetId: presetId, doubles: doubles, watchWorkout: workoutAlongside,
            gamesPerSet: 0, serveStats: true, partnerId: "", opponent1Id: "", opponent2Id: "",
            firstServerSlot: weServeFirst ? "SELF" : "OPPONENT_1"
        )
        guard started != nil else {
            rejected()
            return false
        }
        lastSetup = scoring.lastSetup()
        lockedUntil = .distantPast
        Haptics.play(.start)
        return true
    }

    @discardableResult
    func startConfigured(presetId: String, doubles: Bool, gamesPerSet: Int32, serveStats: Bool,
                         partnerId: String, opponent1Id: String, opponent2Id: String, firstServerSlot: String) -> Bool {
        guard scoring.startConfiguredMatch(
            presetId: presetId, doubles: doubles, watchWorkout: workoutAlongside,
            gamesPerSet: gamesPerSet, serveStats: serveStats, partnerId: partnerId,
            opponent1Id: opponent1Id, opponent2Id: opponent2Id, firstServerSlot: firstServerSlot
        ) != nil else {
            rejected()
            return false
        }
        lastSetup = scoring.lastSetup()
        lockedUntil = .distantPast
        Haptics.play(.start)
        return true
    }

    @discardableResult
    func startLast() -> Bool {
        guard scoring.startLastMatch(watchWorkout: workoutAlongside) != nil else {
            rejected()
            return false
        }
        lockedUntil = .distantPast
        Haptics.play(.start)
        return true
    }

    /// Schnellstart über die Action-Taste (StartWorkoutIntent): letzte Auswahl dieser Sportart, sonst Default-Preset.
    /// `true`, wenn ein Match läuft (neu gestartet oder schon offen).
    func quickStart(sport: String) -> Bool {
        if let snap = snapshot, !snap.matchFinished { return true }
        if snapshot != nil { closeMatch() }
        if let last = lastSetup, last.sport == sport {
            return startLast()
        }
        let presets = presets(for: sport)
        guard let preset = presets.first(where: { $0.isDefault }) ?? presets.first else { return false }
        return start(presetId: preset.id, doubles: preset.defaultDoubles, weServeFirst: true)
    }

    // MARK: Zählen

    /// Tap auf eine Hälfte. `force` umgeht die Sperrzeit (nur Demo-Modus).
    func tap(sideA: Bool, force: Bool = false) {
        guard let before = snapshot, before.isScorer, before.status == "IN_PROGRESS" || before.status == "PAUSED" else { return }
        guard !serverChoicePresented, choiceInteractive else { return }
        let now = Date()
        if !force && now < silentUntil { return }
        if !force && now < lockedUntil {
            Haptics.rejected()
            return
        }
        guard let after = scoring.point(sideA: sideA) else {
            rejected()
            return
        }
        dismissServeBar()
        if after.serveInfoEnabled && !after.lastPointId.isEmpty {
            serveBarPointId = after.lastPointId
            serveBarDeadline = Date().addingTimeInterval(Self.serveBarVisible)
            scheduleServeBarDismiss(after: Self.serveBarVisible)
        }
        if banner == .pointRemoved { banner = nil }
        lockedUntil = now.addingTimeInterval(Self.lockInterval)
        flashSideA = sideA
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in self?.flashSideA = nil }
        Haptics.feedback(after: after, us: sideA, previousHint: before.hintCode)
        afterCommand(after)
    }

    var isLocked: Bool { Date() < lockedUntil }

    /// Display wurde aus dem Always-on-Zustand geweckt: der Aufweck-Tap soll nicht zählen.
    func didWake() {
        dismissServeBar()
        silentUntil = Date().addingTimeInterval(0.3)
    }

    func undo() {
        guard !serverChoicePresented, choiceInteractive else { return }
        dismissServeBar()
        guard let before = snapshot, let after = scoring.undo() else {
            rejected()
            return
        }
        Haptics.undo()
        show(.undo(from: ScoreText.short(before), to: ScoreText.short(after)))
        afterCommand(after)
    }

    func dismissServeBar() {
        serveBarWork?.cancel()
        serveBarWork = nil
        serveBarPointId = ""
    }

    private func scheduleServeBarDismiss(after seconds: TimeInterval) {
        serveBarWork?.cancel()
        serveBarDeadline = min(serveBarDeadline, Date().addingTimeInterval(seconds))
        let work = DispatchWorkItem { [weak self] in self?.serveBarPointId = "" }
        serveBarWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    func canRemovePoint(sideA: Bool) -> Bool {
        guard let snap = snapshot, snap.isScorer else { return false }
        return sideA ? snap.canRemovePointA : snap.canRemovePointB
    }

    /// Aufruf beim Loslassen; dieselbe kurze Entprellung und Aufweck-Sperre wie beim Zählen.
    func removePoint(sideA: Bool) {
        guard !serverChoicePresented, choiceInteractive, canRemovePoint(sideA: sideA), Date() >= silentUntil, !isLocked else { return }
        dismissServeBar()
        guard scoring.removeLastPointOf(sideA: sideA) != nil else {
            rejected()
            return
        }
        lockedUntil = Date().addingTimeInterval(Self.lockInterval)
        Haptics.undo()
        show(.pointRemoved)
    }

    func redoRemoval() {
        guard !serverChoicePresented, choiceInteractive, banner == .pointRemoved, snapshot?.canRedo == true else { return }
        dismissServeBar()
        guard scoring.redo() != nil else {
            rejected()
            return
        }
        banner = nil
        Haptics.play(.click)
    }

    func setServeInfo(pointId: String, value: String) {
        guard let snap = snapshot, snap.isScorer, snap.serveInfoEnabled,
              !pointId.isEmpty, pointId == serveBarPointId, pointId == snap.lastPointId else { return }
        guard scoring.setServeInfo(pointId: pointId, value: value) != nil else {
            rejected()
            return
        }
        Haptics.play(.click)
        // Auswahl kurz markiert lassen; die ursprünglichen 5 s niemals verlängern.
        scheduleServeBarDismiss(after: min(Self.serveBarAfterChoice, max(0, serveBarDeadline.timeIntervalSinceNow)))
    }

    @discardableResult
    func chooseServer(_ id: String, expected: WatchServerChoiceContext) -> Bool {
        guard choiceInteractive, serverChoicePresented,
              Self.choiceSignature(serverChoiceContext) == Self.choiceSignature(expected) else { return false }
        handledChoices.insert(Self.choiceWindow(expected))
        dismissServerChoice()
        guard scoring.chooseServer(playerId: id, expected: expected) != nil else {
            rejected()
            return false
        }
        Haptics.play(.click)
        return true
    }

    func startNextSet(_ kind: String) {
        dismissServeBar()
        guard scoring.startNextSet(kind: kind) != nil else {
            rejected()
            return
        }
        Haptics.play(.start)
    }

    func decide(anotherSet: Bool, matchTiebreak: Bool) {
        dismissServeBar()
        guard let after = scoring.continueDecision(anotherSet: anotherSet, matchTiebreak: matchTiebreak) else {
            rejected()
            return
        }
        Haptics.play(anotherSet ? .start : .stop)
        afterCommand(after)
    }

    func endMatch() {
        dismissServeBar()
        guard let after = scoring.end() else {
            rejected()
            return
        }
        Haptics.play(.stop)
        afterCommand(after)
    }

    /// Unterbrechung (Regen, Verletzung): Match `Paused`, Workout pausiert. Kein Effekt auf den Score.
    func pauseMatch() {
        guard let snap = snapshot, !snap.matchFinished else { return }
        if !snap.isScorer {
            workout.setPaused(true)
            return
        }
        guard snap.status == "IN_PROGRESS" else { return }
        guard let after = scoring.pause() else {
            rejected()
            return
        }
        Haptics.play(.stop)
        afterCommand(after)
    }

    func resumeMatch() {
        guard let snap = snapshot, !snap.matchFinished else { return }
        if !snap.isScorer {
            workout.setPaused(false)
            return
        }
        guard snap.paused else { return }
        guard let after = scoring.resumePlay() else {
            rejected()
            return
        }
        Haptics.play(.start)
        afterCommand(after)
    }

    /// Matchende-Ansicht verlassen bzw. Match aus der Anzeige nehmen (das Log bleibt).
    func closeMatch() {
        scoring.close()
        snapshot = nil
        banner = nil
        workout.end()
        lastSetup = scoring.lastSetup()
    }

    // MARK: Action-Taste

    /// Einziger Einstieg für die Action-Taste: welcher Intent auch gespendet war, es gilt die aktuelle Einstellung
    /// („Aus“ = nichts tun).
    func handleActionButton() {
        // Anzeige: keine Zähl-/Undo-Aktion über Hardware (04 §4.2 Rollengrenze)
        guard snapshot?.isScorer == true else { return }
        switch actionButtonMode {
        case .undo: undo()
        case .pointUs: tap(sideA: true)
        case .off: break
        }
    }

    func donateActionIntent() {
        guard snapshot?.isScorer == true, actionButtonMode != .off else { return }
        ActionIntents.donate(actionButtonMode)
    }

    // MARK: Hinweise

    func show(_ banner: Banner) {
        bannerWork?.cancel()
        self.banner = banner
        let work = DispatchWorkItem { [weak self] in self?.banner = nil }
        bannerWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + (banner == .pointRemoved ? 5 : 3), execute: work)
    }
}

/// Kurztexte für Spielstände (DE „6:4“, EN „6–4“, 04 §9.1).
enum ScoreText {
    static var separator: String { loc("score.separator") }

    static func pair(_ a: String, _ b: String) -> String { a + separator + b }

    static func pair(_ a: Int32, _ b: Int32) -> String { pair(String(a), String(b)) }

    /// Abgeschlossene Sätze aus Sicht „Wir“, z. B. „6:4 3:6“; Satz-Tiebreak des Verlierers in Klammern.
    static func sets(_ s: WatchScoreSnapshot) -> String {
        s.setScoresA.indices.map { i -> String in
            let a = s.setScoresA[i].int32Value
            let b = s.setScoresB[i].int32Value
            let tbA = s.setTiebreaksA[i].int32Value
            let tbB = s.setTiebreaksB[i].int32Value
            if i < s.setKinds.count && s.setKinds[i] == "MATCH_TIEBREAK" {
                return "[" + pair(a, b) + "]"
            }
            if tbA >= 0 && tbB >= 0 {
                return pair(a, b) + "(\(min(tbA, tbB)))"
            }
            return pair(a, b)
        }.joined(separator: " ")
    }

    /// Stand für den Undo-Hinweis, z. B. „3:2 30:15“.
    static func short(_ s: WatchScoreSnapshot) -> String {
        if s.pointsA.isEmpty {
            return sets(s)
        }
        let points = pair(s.pointsA, s.pointsB)
        return s.gamesA >= 0 ? pair(s.gamesA, s.gamesB) + " " + points : points
    }
}
