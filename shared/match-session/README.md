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

## Sofort-Sync Zähler → Handy (Nutzeranforderung 2026-10-15)

- **Live:** Jedes Event des Zählers geht direkt nach dem Tap an alle erreichbaren Gegenstellen – kein Sammeln, kein
  Debounce. `ReliableDeviceLink` sorgt für Ack und Wiederholung.
- **Offline:** Events bleiben lokal gespeichert; jedes Event trägt im Store das Flag `confirmed` (eine Gegenstelle hat
  es: Ack, `Summary` oder von dort empfangen). Taucht eine Gegenstelle wieder auf, sendet die Session **sofort**
  `Hello`, Löschmarkierungen, ihren `Summary` und den gesamten Rückstand **aller** Matches (je Match `MatchMeta` +
  Events in Teilen) – nicht erst im 30-s-Takt. Der Takt läuft weiter, solange ein Match aktiv ist oder Rückstand besteht.
- **Handy = Hub/Backup:** Jedes empfangene Event wird sofort persistiert (Grundlage für Übergabe und später Cloud).
- **Anzeige:** `SyncStatus.pendingToPeers` = persistenter Rückstand über alle Matches (0 = „synchron“, sonst
  „N ausstehend“; übersteht App-Neustarts). `SyncStatus.lastEventReceivedAtMs` auf dem Handy („Stand vor X s“, zusammen mit
  `scorerConnected`).
- Tests: `ImmediateSyncTest` (Event nach einer Einweg-Latenz persistiert, Ack nach einer Runde; Offline-Puffer nach
  Wiederverbindung in < 200 ms virtueller Zeit vollständig beim Handy).

## Identität des Besitzers über eigene Geräte (W1-I)

- **Das Handy ist Identitätsquelle.** Jede Session schickt im `MatchMeta` ihren eigenen Spieler (`self`) und bekannte
  frühere IDs desselben Besitzers (`selfAliases`).
- **Uhr** (Session mit `kind != PHONE`), Nachricht von einem Handy: `LocalData.adoptSelf` – der Self des Handys wird
  der eigene Self der Uhr (ID + Name), der bisherige Uhr-Self wird Alias und geht sofort per `MatchMeta` ans Handy.
- **Handy:** merkt sich `self`/`selfAliases` anderer eigener Geräte als Aliase (`LocalData.selfAliases()`,
  `isSelf(id)`). Matches, die die Uhr mit ihrem alten Self angelegt hat, zeigen den Besitzer – keinen Gast
  (Phone-Adapter `SessionMatchStore`).
- **Was die Uhr-Apps (Wear OS, watchOS-Facade) tun müssen:** beim Anlegen eines Matches den Self **jedes Mal** aus
  `data.players.self()` lesen (nicht zwischenspeichern – er kann sich nach dem ersten `Hello` ändern) und Spieler-IDs
  mit `data.isSelf(id)` als „Ich“ anzeigen. Sonst nichts; der Austausch läuft in der Session.

## `dataRevision` und `MatchMeta.watchWorkout`

- `SessionState.dataRevision` steigt bei Änderungen außerhalb des offenen Matches (empfangene/eigene Löschung,
  Kopf/Stand anderer Matches, Spieler/Favoriten/Identität per `MatchMeta`). Listen-UIs (Verlauf) laden dann neu.
- `MatchMeta.watchWorkout` (Default `true`, 02 §10): Wunsch „Workout auf meiner Uhr mitlaufen lassen“; kommt mit
  Genesis und Match-Kopf auf die Uhr. Die Uhr-Apps lesen es aus `ActiveMatch.meta`.

## Löschen (Löschmarkierungen)

- `deleteMatch(id)` löscht Kopf und Events und legt eine **dauerhafte Löschmarkierung** (`match_tombstone`) an.
- Die Markierung geht sofort per `Deleted` an die gekoppelten Geräte und bei jedem `Hello` erneut vollständig; dort wird
  das Match ebenfalls gelöscht und ein dort offenes Match geschlossen.
- Empfang, Abgleich (`Summary`), `MatchMeta` und Auto-Follow ignorieren gelöschte Matches dauerhaft; wer noch Events
  dazu sendet, bekommt die Löschung zurück.
- **P2 (geteilte Matches):** Löschen entfernt dann nur die eigene Sicht; die Markierung darf nicht an fremde Teilnehmer
  repliziert werden (Cloud-Modell klären).

## Regeln und Garantien

- **Ein Thread:** alle öffentlichen Aufrufe **und** Callback-Eingänge (Empfang, Peer-Wechsel, Ack, Takt) auf dem Thread
  des `LinkScheduler`; `ThreadGuard` erzwingt das (Android: Main-Looper, Apple: `NSThread.isMainThread`), sonst
  `IllegalStateException`.
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
