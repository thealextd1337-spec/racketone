# `shared/match-session` – Match-Session (Engine + Store + Geräte-Sync)

Orchestriert ein Match auf **einem** Gerät nach dem **Single-Scorer-Modell** (Produktentscheidung 2026-10-15,
02 §7.5, 01 §4.4): Pro Match zählt genau ein Gerät – Standard die Uhr des Spielers, alternativ ein Handy im
Director-Modus. Alle anderen Geräte zeigen an und können „Zählen übernehmen“. Ohne Compose, Ktor, Koin – läuft auf
allen Targets inkl. watchOS. Abhängigkeiten: `core-scoring`, `sync-protocol`, `data`, `device-link`,
`kotlinx-coroutines` (nur `StateFlow`).

| Baustein | Aufgabe |
|---|---|
| `MatchSession` | API für UI/Facades, Event-Erzeugung (nur Zähler), Empfang, Anti-Entropie-Takt, Übergabe |
| `SessionState` | Zustand für die UI: `ActiveMatch` (Engine-Zustand, Rolle, Undo/Redo, `pointsPlayed`) + `SyncStatus` |
| `MatchSessions` | DI-freie Fabrik + stabile Geräte-ID (`session.deviceId` in den Settings) |
| `AndroidMatchSessions` / `AppleMatchSessions` | prozessweite Session mit Link (Wear Data Layer / WatchConnectivity), DB, Scheduler und Thread-Prüfung |

## Ablauf

```mermaid
sequenceDiagram
  participant Z as Zähler (Uhr)
  participant H as Handy (Hub, Anzeige)
  participant A as weitere Anzeige

  Z->>Z: recordPoint(A): Event (Lamport, deviceSeq, parentId) → Store → applyAll
  Z->>H: Events([e])
  H->>H: Store (idempotent) → applyAll(Batch der Nachricht)
  H->>A: Events([e]) weiterleiten (Hub)
  H-->>H: recordPoint(...) = false (Anzeige)

  Note over Z,H: Verbindung (wieder) da oder alle 30 s im aktiven Match
  H->>Z: Hello (deviceId, kind, v, minV)
  Z->>H: Hello + Summary {matchId: {deviceId: Wasserstand}} + MatchMeta
  H->>Z: Events (fehlende deviceSeq-Bereiche)
  H->>Z: Summary(reply=true), falls H etwas fehlt
  Z->>H: Events (Rest)

  Note over H: „Zählen übernehmen“ (UI bestätigt)
  H->>H: takeOverScoring(): ScorerChanged(phone)
  H->>Z: Events([ScorerChanged])
  Z->>Z: isScorer = false; spätere Uhr-Eingaben → rejectedByRole
```

## API-Beispiel

```kotlin
// Android (Main-Thread); Apple: AppleMatchSessions.shared(PeerKind.PHONE)
val session = AndroidMatchSessions.get(context, PeerKind.PHONE)

// Match am Handy anlegen, die Uhr des Spielers zählt
val matchId = session.create(
    format = Presets.TENNIS_SINGLES_BO3.format(),
    lineup = Lineup(a = listOf(me), b = listOf(bea), firstServer = me),
    meta = MatchMeta(surface = Surface.CLAY),
    scorer = ScorerChoice.Device(watchDeviceId),     // oder ScorerChoice.ThisDevice (Director-Modus)
)

// Auf dem Zähler:
session.recordPoint(Side.A)          // auf Anzeige-Geräten: false, nichts wird geschrieben
session.undo(); session.redo()
session.continueDecision(anotherSet = true)
session.endMatch()                   // im Dialog: ContinueDecision(false), sonst MatchEnded(ABANDONED)

// Auf einem Anzeige-Gerät nach Bestätigung:
session.takeOverScoring()

// Zustand: StateFlow (ViewModel) oder Callbacks (Swift-Facade ohne Flows)
session.state.collect { s ->
    val m = s.match ?: return@collect
    render(m.state, m.isScorer, m.scorerKind, m.pointsPlayed, m.rejectedByRoleCount, s.sync.scorerConnected)
}
session.addListener { s -> render(s) }

session.createFollowUp()             // nach Best-of: verknüpftes Folge-Match (OPEN, gleicher Zähler)
session.resume(matchId)              // Resume-Karte
session.deleteMatch(matchId)         // schließt ein offenes Match vorher
```

Tests/Desktop ohne Plattform-Link: `MatchSessions.create(createInMemoryJvmLocalData(), link = null, scheduler, PeerKind.PHONE)`.

## Regeln und Garantien

- **Ein Thread:** alle öffentlichen Aufrufe auf dem Thread des `LinkScheduler`; `ThreadGuard` erzwingt das
  (Android: Main-Looper, Apple: `NSThread.isMainThread`), sonst `IllegalStateException`.
- **Rolle:** Zähl-Kommandos (`recordPoint`, `undo`, `redo`, `continueDecision`, `correct`, `pause`, `resumePlay`,
  `endMatch`) wirken nur auf dem Zähler. Kommen trotzdem Eingaben eines Nicht-Zählers an (z. B. Altzähler offline),
  verwirft sie die Engine deterministisch (`rejectedByRole`, `ActiveMatch.rejectedByRoleCount`).
- **Redo:** Stapel ist gerätelokal; nur Punkt, `ContinueDecision`, `Correction` und Matchende leeren ihn, Pause/Fortsetzen nicht.
- **Empfang:** jedes neue Event wird sofort idempotent gespeichert und mit dem Batch seiner Nachricht angewendet
  (`ScoringEngine.applyAll`, Fold reihenfolgeunabhängig); `Events.more` ist nur ein Hinweis.
- **Konvergenz:** Anti-Entropie bei jedem `Hello` und alle 30 s im aktiven Match; Versionsvektor = lückenloser
  Wasserstand je Gerät (`sync-protocol`). Belegt durch `MultiDeviceSyncTest`, `ScorerHandoverTest`,
  `PermutationConvergenceTest` (alle Zustellreihenfolgen kleiner Szenarien) und `RandomizedConvergenceTest`.
- **Auto-Follow:** nach jeder Zustandsänderung geprüft. Ohne offenes Match folgt die Session dem gerade empfangenen
  Match; ist das offene beendet, dem Folge-Match bzw. dem jüngsten danach angelegten laufenden Match (Folge-Match
  zuerst, dann `createdAt`, dann ID).
- **Folge-Match:** gleiche Besetzung und gleiches Satzformat, Modus `OPEN`, `decidingSet = null`, Erstaufschläger aus
  `state.continuation`, `previousMatchId` gesetzt, gleicher Zähler (02 §5.2).

## Abweichungen von 01 §4

| Thema | 01 | Umsetzung | Grund |
|---|---|---|---|
| `Summary` „maxDeviceSeq“ | höchste Sequenz | **lückenloser Wasserstand** | außer der Reihe angekommene Events dürfen keine Lücke verdecken |
| `Events` in Teilen | – | Feld `more` nur als Hinweis | Fold ist reihenfolgeunabhängig; Puffern bis zum letzten Teil hatte Events dauerhaft ungefaltet gelassen (Review-Befund 1) |
| `sync-protocol` | nur `kotlinx-serialization-core` | zusätzlich `kotlinx-serialization-json` | Wire-Codec braucht JSON (01 §4.3), CLAUDE.md Regel 1 angepasst |
