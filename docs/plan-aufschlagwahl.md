# Advantly – Plan „Aufschlagwahl auf der Uhr“

Stand: 16.10.2026 · Umsetzung AP1/AP2 im Worktree `H:/racketone-wt/w8-aufschlagwahl`, Branch `w8/aufschlagwahl`, Basis `origin/main` / `f78dc82dd8ee2f83406410e8fc2ddcfad8debcf3`. Analysegrundlage: `H:/racketone`, Branch `main`, Commit `f78dc82dd8ee2f83406410e8fc2ddcfad8debcf3`. Das Arbeitsverzeichnis war zu Umsetzungsbeginn sauber. Die Ist-Analyse unten beschreibt diesen Basis-Commit; AP1/AP2 ergänzen Verträge, Regressionstests und lokale APIs. AP3–AP7 sind im Folge-Worktree `H:/racketone-wt/w8-ui` umgesetzt (lokale Abnahme siehe §6.2). Die jeweiligen Umsetzungs-Commits sind über die Git-Historie dieser Datei und der betroffenen Module nachvollziehbar.

## Entscheidungen 16.10.2026

Alle fünf offenen Fragen aus §7 sind vom Nutzer gemäß Empfehlung entschieden (F30):

1. **„Später“ erlaubt:** Auswahl schließen und den ohne Wahl aufschlagenden Spieler nennen; kein Pflicht-Gate.
2. **Live statt Setup:** „Erster Aufschlag“ entfällt bei Tennis-/Padel-Doppel im Setup/Wizard, auch am Handy. Wahl nur live am zählenden Gerät.
3. **Neuer Satz:** alle vier Spieler anbieten, regelgemäß fälliges Team zuerst; die bestehende Freizeitregel bleibt.
4. **Scope:** ausschließlich Tennis und Padel einschließlich ihrer Tiebreak-Formate. Pickleball, Badminton und Tischtennis bleiben unverändert.
5. **Undo:** bestehende Semantik aus §3.5 beibehalten und sichtbar machen; keine strikte Game-Bindung, kein neues Wire-Feld.

## 1. Ergebnis und Abgrenzung

**Das Feature ist fachlich weitgehend umgesetzt. Das wesentliche Delta ist der Zeitpunkt und die automatische Anzeige der vorhandenen Auswahl.**

Empfohlener Lieferumfang:

- Tennis-/Padel-Doppel: Aufschläger nicht mehr im Setup verbindlich auswählen. Nach Matchstart erscheint auf dem aktiven Zähler automatisch die vorhandene Spielerauswahl, vor der ersten Punkteingabe.
- Erstes Wahlfenster: vier Spieler; die Person bestimmt zugleich das eröffnende Team. Zweites Wahlfenster: zwei Spieler des anderen Teams. Danach vorhandene Rotation verwenden.
- Die Auswahl lässt sich mit „Später“ schließen. Die App zeigt dabei ausdrücklich, welcher Spieler ohne Auswahl aufschlägt. Kein verpflichtendes neues Scoring-Gate in der Engine.
- Dasselbe Verhalten am Handy, wenn das Handy zählt. Anzeige-Geräte dürfen keine Wahl treffen.
- Bestehende Satzanfangs-, Tiebreak-, Korrektur-, Replay- und Sync-Semantik beibehalten; ihre Besonderheiten sichtbar machen und absichern.

**Präzisierung von „Team A/B“:** Die beiden Teams behalten ihre Identitäten. „A“ im Nutzerbeispiel ist sinnvoll als „zuerst aufschlagendes Team“ zu verstehen, nicht als zwingend gespeicherte `Side.A`. Bei Wahl von Gegner 2 beginnt dessen Team; im zweiten Game werden Ich/Partner angeboten. Teams werden nicht umbenannt oder vertauscht.

Beispiel mit stabilen Spielerplätzen: Wahl A2, danach B1 → A2, B1, A1, B2, A2 … . Das entspricht der gewünschten Folge „gewählter Spieler erstes Team, gewählter Spieler zweites Team, jeweilige Partner“.

## 2. Ist-Analyse mit Fundstellen

Alle Pfade beziehen sich auf `H:/racketone`. Für die langen Kotlin-Pfade gelten folgende eindeutige Kürzel:

- **CS:** `shared/core-scoring/src/commonMain/kotlin/eu/advantly/core/scoring/`
- **MS:** `shared/match-session/src/commonMain/kotlin/eu/advantly/match/session/`
- **PR:** `shared/presentation/src/commonMain/kotlin/eu/advantly/presentation/`
- **PH:** `shared/ui-phone/src/commonMain/kotlin/eu/advantly/ui/phone/`
- **WE:** `wearApp/src/main/kotlin/eu/advantly/wear/`
- **AW:** `shared/umbrella-watch/src/commonMain/kotlin/eu/advantly/umbrella/watch/`
- **SP:** `shared/sync-protocol/src/commonMain/kotlin/eu/advantly/sync/protocol/`

### 2.1 Bereits beschlossene Produktregeln

- `docs/PLAN.md:21`, `:25`, `:60`: genau ein Zähler, Standard Uhr; alternativ Handy/Director. Anzeige-Geräte zählen nicht.
- `docs/00-entscheidungen.md:14`: dieselbe Single-Scorer-Entscheidung.
- `docs/02-scoring-regeln.md:672`: bereits spezifizierte Doppelwahl mit vier Spielern in Fenster 1 und zwei Spielern in Fenster 2.
- `docs/02-scoring-regeln.md:693`, `:1242`, `:1255`: nicht blockierend; bisherige Reihenfolge bei Überspringen; erneute Wahl in jedem Satz; letzte gültige Wahl gilt.
- `docs/w2-ui-vertrag.md:12`: Session/UI bieten das zweite Fenster erst an, wenn das zweite Team tatsächlich an der Reihe ist.
- **Wichtig:** `docs/w2-ui-vertrag.md:69` und `docs/02-scoring-regeln.md:678` erlauben ausdrücklich als bestätigte Freizeitregel auch im späteren Satz die Wahl des anderen eröffnenden Teams. Das ist keine versehentliche Engine-Lücke. Dieser Auftrag sollte diese Entscheidung nicht stillschweigend überschreiben.
- `docs/04-ux-design.md:38`, `:47`, `:541`: 44 pt auf Apple, Android-Touchziele 48 dp, kleine Uhren und längere deutsche Texte prüfen.

### 2.2 Engine

- **`CS/Events.kt:172`, `:188`:** `ServerChosen(set: Int, side: Side, player: PlayerId)`. `set` ist nullbasiert. Es gibt **keinen Game-Index im Event**.
- **`CS/Core.kt:858`:** Validierung auf Tennis/Padel, Doppel, laufenden Satz, Status IN_PROGRESS/PAUSED, passenden Satzindex und Spielerzugehörigkeit. Nach einem Ballwechsel ist die Wahl nur für ein Team zulässig, das im Satz noch nicht aufgeschlagen hat.
- **`CS/Core.kt:864`:** Vor dem ersten Satzpunkt kann die Wahl das eröffnende Team ändern. Danach werden Teamreihenfolge und Rotationsindex gesetzt.
- **`CS/Core.kt:875`:** `serverChoice()` projiziert das offene UI-Fenster: bei null Ballwechseln vier Spieler, danach zwei Spieler der nächsten noch nicht aufgeschlagenen Side. `game=1/2` ist eine **Wahlphase**, kein universeller tatsächlicher Game-Index.
- Die Event-Validierung ist absichtlich weiter als das UI: Eine Wahl für das zweite Team kann schon während Game 1 wirksam werden. Belegt durch `docs/02-scoring-regeln.md:689` und `shared/core-scoring/src/commonTest/kotlin/eu/advantly/core/scoring/OperationRulesTest.kt:157`.
- **`CS/Core.kt:424`, `:449`, `:492`:** Markierung „Team hat aufgeschlagen“, Standardspielrotation und Tiebreakfolge sind vorhanden. Nach Standardspielen wechselt das Team; die Spieler wechseln innerhalb des Teams. Tiebreak: ein Punkt, anschließend Zweierblöcke.
- **`CS/Core.kt:283`, `:355`:** Wahlzustände werden zum Satzbeginn zurückgesetzt; die bisherige Teamreihenfolge bleibt erhalten.
- **`CS/Core.kt:788`:** Korrekturen können eine nicht mehr passende Wahl aufheben.
- **`CS/ScoringEngine.kt:138`, `:184`, `:200`:** Fold/Replay und inkrementelles Apply sind die gemeinsame Zustandsquelle.
- **`CS/ScoringEngine.kt:359`:** `ServerChosen` ist kein Ziel des normalen UI-Undo. Wiederholtes Wählen ändert die Wahl.
- **`CS/ScoringEngine.kt:408`:** `INITIAL_SERVER` verändert beim Replay zuerst das initiale Lineup; danach werden die Events erneut gefaltet. Eine spätere explizite `ServerChosen`-Wahl kann diese Änderung wieder übersteuern.

### 2.3 Session und Präsentation

- **`MS/MatchSession.kt:643`:** `chooseServer` prüft Zählerrolle, aktuelles Fenster und angebotene Spieler, erzeugt das vorhandene Event; dieselbe noch gültige Wahl ist ein No-op. Der Redo-Stapel bleibt erhalten.
- **`MS/MatchSession.kt:564`, `:575`, `:627`:** Undo, Redo und „Punkt einer Seite abziehen“ arbeiten über Events; keine gesonderte Rücknahme einer Aufschlägerwahl.
- **`MS/MatchSession.kt:749`, `:787`:** Rollenprüfung und gemeinsame Kette Erzeugen → Speichern → Apply → Broadcast.
- **`MS/SessionState.kt:98`**, **`PR/match/MatchPorts.kt:197`, `:348`**: Wahlzustand und Kommando sind bereits verfügbar.
- **`PR/match/SessionMatchStore.kt:249`** delegiert an die Session; **`PR/match/InMemoryMatchStore.kt:209`** bildet den Test-/Preview-Pfad ab.
- **`PR/live/LiveOperationRules.kt:8`** enthält bisher keine gemeinsame Regel für das automatische Öffnen der Spielerauswahl.
- Achtung Modulgrenze: `umbrella-watch` hängt laut `CLAUDE.md` nicht von `presentation` ab. Gemeinsam benötigte reine Wahlregeln gehören in/nahe `match-session`, nicht ausschließlich nach `presentation`.

### 2.4 Wear OS

**Heute:** Sport → Einzel/Doppel → Format → Spielerplätze/Erstaufschläger → Matchstart. Keine zusätzliche Teamwahl vor der Spielerwahl.

- **`WE/ui/SetupScreen.kt:68`, `:173`:** Letzter Setup-Schritt zeigt im Doppel unmittelbar vier Spielerbuttons mit Teambezug.
- **`WE/live/WatchController.kt:226`**, **`WE/match/SetupCatalog.kt:247`**: Tippen legt `Lineup.firstServer` fest und startet das Match. Es ist keine explizite `ServerChosen`-Wahl.
- **`WE/live/Operation.kt:150`**: Das erste Live-Wahlangebot wird als bereits im Setup erledigt behandelt; `prompt=false` in Game 1/Satz 1. Nach einer aufgehobenen expliziten Wahl kann es wieder erscheinen.
- **`WE/live/Operation.kt:168`**: ActionStrip priorisiert Aufschlagstatistik und Rücknahmehinweis vor `ChooseServer`.
- **`WE/ui/OperationViews.kt:159`**, **`WE/live/WatchController.kt:343`**: Das Live-Angebot öffnet die Auswahl erst auf Antippen.
- **`WE/ui/LiveDialogs.kt:413`**: Vier/zwei Spieler, Teamlabel, markierte Auswahl, „Später“ und Zurück sind schon vorhanden.
- **`WE/ui/LiveDialogs.kt:142`, `:146`**: Wahl und „Aufschläger korrigieren“ auch im Steuerungsmenü.
- **`WE/live/WatchController.kt:548`**: Auswahl schließt bei weggefallenem Fenster/Rollenverlust. Ein Wechsel zwischen zwei weiterhin vorhandenen Fenstern wird hier nicht über eine feste Fensteridentität abgesichert.
- Strings: `wearApp/src/main/res/values-de/strings.xml:37`, `:198` ff.; englische Gegenstücke unter `values/`.

### 2.5 Apple Watch: Facade und SwiftUI

**Heute:** Sport/Format → Setup mit Besetzung und vier Aufschlägerbuttons → Matchstart. Auch hier kein separater Teamwahl-Schritt.

- **`watchApp/StartView.swift:122`, `:178`**: Aufschlägerbutton startet das Match, Mindesthöhe bereits 44 pt.
- **`AW/WatchScoring.kt:204`, `:246`**: `firstServerSlot` wird in das Lineup übersetzt.
- **`AW/WatchScoring.kt:392`**: `chooseServer(playerId)` delegiert an die Session.
- **`AW/WatchScoring.kt:536`, `:556`**: Spielerauflösung und dieselbe Unterdrückung des ersten Prompts wie auf Wear.
- **`AW/WatchScoring.kt:938`**: flache Snapshot-Felder für Phase, Side, Wahl, Optionen und Prompt vorhanden. `gameKind` ist ebenfalls bereits verfügbar (`:881`).
- **`watchApp/ScoringView.swift:79`, `:142`, `:632`**: manuelles Öffnen über Mittelband/Steuerung; kein automatisches Sheet.
- **`watchApp/OperationViews.swift:82`**: `ServerChoiceView` zeigt die Optionen; Fensterkennung Match/Satz/Phase, Prüfung bei Eventänderungen, Mindesthöhe 44 pt. Abschluss derzeit „Abbrechen“, kein eigener „Später“-Button.
- **`watchApp/OperationViews.swift:123`**: lokalisierte Platzhalter und Besitzerperspektive vorhanden.
- Strings: `watchApp/de.lproj/Localizable.strings:466` ff. und englisches Gegenstück.

### 2.6 Handy: Wizard und Live-Screen

- **`PH/wizard/PlayersStep.kt:73`, `:76`**: „Erster Aufschlag“ mit Spielerchips und Münzwurf, sobald die Spieler vollständig sind.
- **`PR/wizard/MatchWizard.kt:216`, `:591`**: Auswahl oder Fallback auf eigenen Spieler/ersten Spieler von Team A wird in `Lineup.firstServer` gespeichert.
- **`PH/live/LiveOperations.kt:81`, `:119`**: Bereits vorhandene Auswahl direkt im Live-Infobereich, nur auf dem Zähler. Anders als auf den Uhren wird Game 1 nicht wegen des Wizards unterdrückt. Keine automatische modale Auswahl.
- **`PH/live/LiveScoreController.kt:161`**: Prüfung und Delegation des Wahlkommandos.
- **`PR/match/SessionMatchStore.kt:283`**: Korrektur kann rückwirkend oder ab jetzt erfolgen.

### 2.7 Statistik und bestehende Tests

- **`CS/ScoringEngine.kt:54`, `:89`**: Spieler vor dem Punkt wird bereits als Aufschläger erfasst/projiziert.
- **`PR/stats/MatchStatistics.kt:134`**: Aufschlagstatistik nach Team und Spieler vorhanden.
- **`PR/stats/MatchStatistics.kt:136`**: Unzugewiesene Platzhalter werden aktuell aus der persönlichen Spielerliste herausgefiltert. „Partner“ kann also zählen, ohne dort eine eigene persönliche Statistikzeile zu erhalten.
- Bestehende Tests: `OperationRulesTest.kt:72` ff. in core-scoring; `OperationSessionTest.kt:60` in match-session; `WatchControllerOperationTest.kt:198` in wearApp; `WatchScoringTest.kt:724` in umbrella-watch; `LiveOperationsUiTest.kt`, `OperationPortTest.kt`, `MatchStatisticsTest.kt`.
- Die Uhrentests erwarten heute ausdrücklich **keinen** Prompt im ersten Game. Diese Erwartung muss gezielt geändert werden.

## 3. Soll-Ablauf und Bedienung

### 3.1 Gemeinsame Regeln

Die Auswahl wird beim Eintritt in ein noch unentschiedenes Wahlfenster automatisch gezeigt, sofern das Gerät Zähler, im Vordergrund und entsperrt ist. Ein Antippen eines Spielers übernimmt sofort; kein zusätzlicher Bestätigungsdialog.

„Vor dem ersten Punkt“ bedeutet **vor der ersten Punkteingabe in der App**. Den tatsächlichen Beginn eines Ballwechsels kann die App nicht erkennen.

Während die Auswahl sichtbar ist, gelangen Touches, Hardware- und Bedienungshilfe-Aktionen nicht an die darunterliegende Punkteingabe. Nach „Später“ darf sofort gezählt werden. Session und Engine akzeptieren weiterhin Punkte ohne explizite Wahl.

Automatisches Öffnen darf nicht mit jedem Snapshot erneut erfolgen:

- UI-Fenster identifizieren durch Match-ID, Satzindex und Wahlphase; zusätzlich Side/zulässige Spieler zur Prüfung veralteter Aktionen verwenden.
- Wahl, Überspringen, Fensterwechsel, Rollenwechsel und eine die Wahl aufhebende Korrektur getrennt behandeln.
- „Später“ merkt sich die lokale Präsentation für das aktuelle Fenster. Kein neues Match-Event.
- Nach normalem Recompose/Refresh/Ambient-Rückkehr keine Wiederholung. Bei Prozessneustart darf ein weiterhin unentschiedenes Fenster einmal erneut erscheinen.
- Ein neuer Zähler darf eine offene, bisher nur auf einem anderen Gerät übersprungene Wahl erneut sehen. Eine replizierte gültige Wahl wird dagegen nicht erneut verlangt.
- Ein Tap aus einem inzwischen veralteten Fenster wird verworfen, selbst wenn derselbe Spieler zufällig im neuen Fenster zulässig wäre. Die Session prüft dafür einen erwarteten Fensterkontext; das ist ein lokaler API-Parameter, kein Wire-Feld.
- Aufschlagstatistik zum letzten Punkt darf die neue Auswahl nicht auf unbestimmte Zeit verdrängen. Nach Schließen der Auswahl bleibt die Erfassung des letzten Punktes zugänglich.

### 3.2 Wear OS: Bildschirmfolge

1. **Setup:** Sport → Disziplin → Format/Besetzung/Optionen → „Match starten“. Bei Tennis-/Padel-Doppel entfällt die verbindliche Erstaufschlägerwahl in diesem Setup. Spielerbesetzung und Workout-/Statistikoptionen bleiben erreichbar.
2. **Erste Auswahl:** „Wer schlägt zuerst auf?“; kleine Kontextzeile „Satz 1 · Spiel 1“. Vier vollbreite Zeilen in einer scrollbaren Wear-Liste:
   - eigener Anzeigename bzw. „Ich“, darunter „Wir“;
   - Name bzw. „Partner“, darunter „Wir“;
   - Name bzw. „Gegner 1“, darunter „Gegner“;
   - Name bzw. „Gegner 2“, darunter „Gegner“.
3. **Wahl:** Antippen schreibt `ServerChosen`, kurze Bestätigungshaptik, Live-Score mit sichtbarem Aufschläger. Der Tap zählt keinen Punkt.
4. **Alternativ „Später“:** Text „Aktuell: [Name]. Du kannst bis zum ersten Punkt wählen.“ Zurück/Wischen bedeutet ebenfalls Überspringen. Im Live-Screen bleibt der vorhandene Einstieg „Aufschlag wählen“ verfügbar.
5. **Nach Ende von Game 1:** automatisch dieselbe Auswahl mit nur zwei Zeilen: „Wer schlägt bei uns auf?“ bzw. „Wer schlägt bei den Gegnern auf?“ Kontext „Spiel 2“. Team folgt dem Engine-Zustand, nicht fest „Gegner“.
6. **Ab Game 3:** normales Zählen, Aufschläger automatisch. Keine weitere Nachfrage innerhalb dieses regulären Satzes.
7. **Nächster Satz:** erneute zwei Wahlphasen entsprechend der bestehenden Produktregel, siehe Abschnitt 4.

**Kleines/rundes Display:** Keine vier Quadranten. Scrollbare Liste mit sicheren oberen/unteren Rundungsabständen; Rotary-Navigation. Alle Spielerzeilen und „Später“ mindestens 48 dp hoch, also über der geforderten 40-dp-Grenze. Teamzugehörigkeit durch Text plus Farbe. Namen bis zwei Zeilen, vollständiger Name im TalkBack-Label; keine Unterscheidung ausschließlich durch abgeschnittene Namen. Gleichnamige Spieler zusätzlich über Team und stabilen Platz unterscheiden.

### 3.3 Apple Watch: Bildschirmfolge

1. Bestehendes `SetupView` behält Format/Besetzung/Optionen; für Tennis-/Padel-Doppel ersetzt „Match starten“ die vier Startbuttons.
2. Sobald die Session das Match auf dieser Uhr als Zähler bereitstellt, automatisch `ServerChoiceView` als Sheet öffnen.
3. Dieselben vier/zwei Optionen, Texte und Übergänge wie auf Wear. Vorhandene Swift-Liste und Crown-Scroll verwenden.
4. „Abbrechen“ bei dieser Auswahl durch eindeutiges „Später“ ersetzen; Sheet-Dismiss hat dieselbe Wirkung.
5. Spielerzeilen mit Name und Team, mindestens 44 pt hoch. VoiceOver liest Name, Team und Auswahlzustand. Keine Schriftverkleinerung, um alle vier Spieler gleichzeitig sichtbar zu machen.
6. Rollenverlust, Matchwechsel oder abgelaufenes Fenster schließen das Sheet. Nach Sperre/Dimmen erst bei aktiver Bedienung automatisch präsentieren.
7. Die Facade liefert weiterhin Daten und stabile IDs; Swift lokalisiert die Texte. Neue Facade-Daten nur als einfache Werte/Klassen, ohne Compose/presentation-Abhängigkeit.

### 3.4 Handy, Einzel und Startvarianten

**Handy als Zähler:** Gleiche automatische Vier-/Zweierwahl im Live-Screen als Sheet/Dialog. Vorhandene `ServerChoiceBar` bleibt als Einstieg zum Ändern oder nach „Später“. Das vermeidet unterschiedliche Fachlogik zwischen Director-Handy und Uhr.

**Handy als Anzeige:** Kein Auswahl-Sheet, keine editierbaren Chips. Aktuellen Aufschläger anzeigen; bei Bedarf Hinweis „Aufschlagwahl am Zähler“. Die Uhr darf nicht zusätzlich wählen, wenn das Handy Zähler ist.

**Wizard „Erster Aufschlag“:** Für Tennis-/Padel-Doppel durch „Aufschlagwahl beim Start am Zähler“ ersetzen. Keinen zusätzlichen Münzwurf oder verbindlichen Spielerentscheid im Wizard erzwingen. Das Pflichtfeld `Lineup.firstServer` bleibt technisch mit dem bisherigen Default belegt; es ist der offen angezeigte Fallback bei „Später“, keine bestätigte Wahl.

**Schnellstart/Folgematch/Handy→Uhr:** Alle Eintrittspfade verwenden denselben Live-Ablauf. Eine übernommene alte Reihenfolge ist lediglich der Default. Keine Unterdrückung allein aufgrund von Satzindex 0 oder vorhandener `firstServer`-ID.

**Einzel und übrige Sportarten:** Bisherigen Setup-Ablauf einschließlich Erstaufschläger/Teamwahl beibehalten. Keine neue Doppel-Auswahl über `ServerChosen`.

### 3.5 Ändern, Korrigieren und Undo

- **Vor dem ersten Punkt des betreffenden Fensters:** Auswahl erneut öffnen und anderen Spieler antippen; letztes gültiges `ServerChosen` gilt.
- **Normales Undo:** Nimmt weiterhin den letzten Punkt bzw. das bisherige undo-fähige Kommando zurück, nicht bloß die Aufschlägerwahl. Die UI darf nach einer Wahl nicht „Undo macht die Wahl rückgängig“ suggerieren.
- **Nach Schließen des Wahlfensters:** „Aufschläger korrigieren“ bleibt erreichbar. Korrektur klar als „ab jetzt“ oder tatsächlich rückwirkend kennzeichnen; vorhandene Punkte bleiben bei einer Vorwärtskorrektur unverändert.
- **Wichtige Korrekturkollision:** Nach einer expliziten ersten `ServerChosen`-Wahl kann `INITIAL_SERVER` beim Replay wieder überschrieben werden. Im Basis-Commit statisch aus `ScoringEngine.kt:408` und `Core.kt:858` abgeleitet; AP1 belegt dies jetzt mit `explicitChoiceOverridesInitialServerButForwardCorrectionPreservesHistory` in `OperationRulesTest`. Für dieses Delta empfehlen sich im offenen Fenster erneute Wahl und nach dessen Schließung eine korrekt beschriftete `Correction(SERVER)` „ab jetzt“. Den bisherigen rückwirkenden Weg nur anbieten, wenn keine übersteuernde Wahl besteht. Historische Punkte können über die vorhandenen Punkt-Undos zurückgenommen und nach richtiger Wahl neu gezählt werden. Kein falsches Erfolgsversprechen.
- **Korrektur hebt Wahl auf:** Wird `chosen=null`, Auswahl erneut anbieten; ein früheres „Später“ darf diese neue Situation nicht dauerhaft verdecken.

**Undo über die Game-Grenze – ausdrücklich bestehende Replay-Semantik:**

1. Ersten Punkt von Game 2 zurücknehmen → Fenster 2 wieder offen. Eine noch wirksame Wahl bleibt markiert; keine unnötige Pflichtneuwahl. Ohne Wahl wird erneut angeboten.
2. Gameball von Game 1 zurücknehmen, nachdem für Game 2 gewählt wurde → laufendes Game 1. Fenster 2 verschwindet. Die Wahl des anderen Teams kann als frühzeitige Wahl gültig bleiben und beim erneuten Erreichen von Game 2 wieder gelten.
3. Satzball zurücknehmen → eine Wahl für den dadurch entfallenden Folgesatz wird wegen ihres Satzindex ignoriert. Redo kann sie wieder wirksam machen.
4. **Sonderfall Rückkehr auf null Ballwechsel:** Eine spätere Wahl des zweiten Teams kann beim erneuten Fold zur eröffnenden Wahl werden, weil das Event keinen Phasenanker besitzt. Bei Tiebreak-only kann dafür bereits Undo des ersten Punktes genügen. AP1 belegt dies mit `secondChoiceBecomesOpeningChoiceWhenAllRalliesAreUndone` und `tiebreakFirstPointUndoReinterpretsSecondChoiceAndRedoRestoresRotation` in `OperationRulesTest`. Empfehlung unter der geforderten Altgerätekompatibilität: Fold nicht ändern; bei Rückkehr zum Satzanfang die Auswahl auch bei bestehender Markierung einmal zur Überprüfung zeigen, mit dem tatsächlich errechneten Aufschläger. Nicht behaupten, Undo stelle immer die frühere Aufschlagwahl wieder her.

Eine neue Semantik „Wahl gehört unabänderlich zu genau diesem Game“ wäre ein eigener fachlicher Ausbau. Sie wird nicht versteckt in diese UI-Änderung aufgenommen.

### 3.6 Sync und Single-Scorer

`ServerChosen` läuft durch dieselbe persistierte Eventkette wie Punkte. Keine zusätzliche Bluetooth-/WatchConnectivity-Nachricht und kein direktes Schreiben aus einem Auswahl-Sheet.

- Offline darf der aktuelle Zähler wählen und zählen; Synchronisation nach Wiederverbindung über bestehenden Eventabgleich.
- Nur der Zähler erzeugt die Wahl; die Rolle wird beim Kommando und im Fold geprüft.
- Bei Übernahme bleibt eine wirksame Wahl erhalten. Anzeige-Geräte öffnen keine Auswahl.
- Veraltete Touches nach Rollen-/Match-/Fensterwechsel werden abgewiesen.
- „Später“ ist lokal und wird nicht synchronisiert. Der Default-Aufschläger ist dennoch auf allen Geräten identisch aus dem Log ableitbar.
- Eine alte Protokoll-3-Uhr zeigt weiterhin ihren bisherigen optionalen Ablauf, verarbeitet aber die gleichen Events. Auf ihr kann die neue automatische Oberfläche nicht nachgerüstet werden, ohne ihre App zu aktualisieren.

## 4. Sportarten und Formate

### Tennis – Feature anwenden

Regelgrundlage: Innerhalb eines Satzes legen die beiden Teams ihre ersten Aufschläger vor ihren ersten Aufschlagspielen fest; danach rotieren die Partner. Ein normaler Satz-Tiebreak führt die Reihenfolge fort: ein Punkt, dann Zweierblöcke. Beim folgenden Satz eröffnet das Team, das im Tiebreak zuerst zurückschlug. Beim satzersetzenden Match-Tiebreak darf die teaminterne Reihenfolge neu bestimmt werden; die Teamfolge bleibt erhalten. No-Ad verändert nicht die Aufschlagrotation. [ITF Rules of Tennis 2026, Regeln 5/14 und Anhang VI](https://www.itftennis.com/media/7221/2026-rules-of-tennis-english.pdf).

Produktentscheidung:

- Reguläre Sätze, Short Sets, No-Ad und Advantage: bestehende Fenster 1/2 verwenden.
- Normaler Tiebreak bei Gleichstand: keine neue Wahl; beide Teams haben bereits aufgeschlagen.
- Match-/Champions-Tiebreak und Nur-Tiebreak bis 7/10: erste Wahl vor Punkt 1, zweite vor Punkt 2. Danach automatisch. Beispiel A2 → B1: A2 | B1,B1 | A1,A1 | B2,B2 … .
- UI sagt dort „Erster Aufschlag“ und „Erster Aufschlag [Team]“, nicht „Game 2“. `gameKind` verwenden; keine Formatkennung aus Text ableiten.
- Zu jedem neuen Satz erneut fragen: **ja**, entsprechend der bereits getroffenen Entscheidung U2.
- Die bestehende freie Teamwahl am späteren Satzanfang ist eine dokumentierte Freizeitabweichung. Empfehlung für dieses Delta: erhalten, regelgemäß fälliges Team zuerst anzeigen. Ein künftig strikt regelgebundener Modus würde hier nur dessen zwei Spieler anbieten; er braucht eine gesonderte Produktentscheidung.

### Padel – Feature anwenden

FIP erlaubt die Festlegung der teaminternen Aufschlagreihenfolge vor jedem Satz; der normale Tiebreak behält die Reihenfolge und den Rhythmus 1–2–2. Golden-/Star-Point verändern die Spielerrotation nicht. [FIP Rules of Padel, Regeln 1 und 6.8](https://www.padelfip.com/wp-content/uploads/2025/12/FIP_Rules-of-Padel-1.pdf).

Empfehlung: dieselbe Bedienung wie Tennis, bestehende Rotation wiederverwenden. Super-Tiebreak/Nur-Tiebreak in der App als vorhandene Tiebreak-Sätze behandeln. Die aktuelle FIP-Fassung enthält bei den satzersetzenden Alternativen nicht dieselbe ausdrückliche Neuordnungsnotiz wie ITF Anhang VI; die App-Regel deshalb als bestehende Produktfestlegung dokumentieren, nicht als wörtlich belegte identische FIP-Vorschrift ausgeben. Kein zusätzlicher Regelumbau in diesem Paket.

### Pickleball-Doppel – Feature nicht übertragen

Side-Out-Doppel hat Aufschlägernummern und grundsätzlich zwei Aufschlaggelegenheiten je Team, mit Ausnahme der ersten Sequenz eines Spiels. Das ist kein Wechsel ganzer Tennis-Games nach A1/B1/A2/B2. [USA Pickleball: Regelübersicht](https://usapickleball.org/rules/summary/).

Empfehlung: vorhandene Startspieler-/Positionswahl und Korrekturen behalten; Side-Out und Rally weiterhin nach ihren eigenen Engine-Regeln. Keine zweite Nachfrage nach dem vermeintlichen „Game 1“ und kein `ServerChosen`. Ein späteres Pickleball-UX-Paket muss ausdrücklich beide Zählweisen abdecken.

### Badminton-Doppel – Feature nicht übertragen

Aufschlag folgt Ballwechselgewinn, Aufstellung und Aufschlagfeldern; zum nächsten Spiel darf jeder Spieler der siegreichen Seite zuerst aufschlagen. [BWF Laws of Badminton, insbesondere 11.3–11.6](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf).

Empfehlung: vorhandene Erstaufschläger-/Aufstellungslogik behalten. Ein späterer Satzbeginn-Dialog müsste auch den ersten Rückschläger bzw. die Aufstellung berücksichtigen. Keine Tennis-Gamefolge einführen; weder 21- noch 15-Punkte-Formate gehören in dieses Delta.

### Tischtennis-Doppel – Feature nicht übertragen

Aufschlag- und Rückschlagfolge sind gekoppelt. Der erste Aufschläger wird je Satz gewählt; der erste Rückschläger ist nur im ersten Satz frei, später aus dem Vorsatz abgeleitet. Aufschlagwechsel erfolgen innerhalb des Satzes, regulär nach zwei Punkten, bei Einstand nach einem; hinzu kommt der Rückschlagtausch im Entscheidungssatz. [ITTF Statutes 2026, 2.13.3–2.13.6](https://documents.ittf.sport/sites/default/files/public/2026-02/2026_Statutes_v1_consolidated_clean.pdf).

Empfehlung: bestehende Aufschläger-/Rückschlägerlogik unverändert. Eine passende Oberfläche wäre ein eigenes Feature. Auch das Freizeitformat bis 21 darf nicht versehentlich die Tennis-Auswahl erhalten.

## 5. Datenmodell, Protokoll und Statistik

### 5.1 Reicht ServerChosen?

**Ja, für den empfohlenen Lieferumfang.** Bestehenden Payload `{set, side, player}` und die heutige Fold-Validierung beibehalten. Kein neues Event, kein neues Pflichtfeld, keine Schema-/Formatversion.

Nicht neu erfinden:

- Rotation nicht in Wear/Swift/Phone berechnen.
- Keine Namen im Wahl-Event speichern: stabile Spieler-/Platz-ID verwenden.
- Kein `ServerChoiceSkipped`: Überspringen verändert keine Spielregel.
- Kein Event bei bloßem Öffnen des Sheets.
- `Lineup.firstServer` nicht nullable machen.
- `serverChoice.game` nicht als tatsächlichen Game-Zähler behandeln.

Für UI-Kommandos einen erwarteten Fensterkontext vorsehen und unmittelbar vor dem Schreiben prüfen. Die gemeinsame, plattformfreie Ableitung kann in einer kleinen neuen Datei in match-session liegen; Darstellung und „Später“-Merker bleiben lokal. Facade-Felder nur ergänzen, wenn sie sich nicht sauber aus bestehenden Snapshot-Daten ableiten lassen.

**Kein optionaler Game-Index als vermeintlicher Gratis-Fix:** Ein neues `game`-/`phase`-/Punktanker-Feld, das neue Engines zur Ablehnung eines Events nutzen, wäre trotz optionalem JSON semantisch inkompatibel: alte Engines ignorieren den Anker und könnten nach Undo einen anderen Aufschläger berechnen. Null-Default bewahrt alte Logs, löst aber nicht den Mischbetrieb. Für strikte Bindung wären eine eigene kompatible Kommandostrategie oder ein ausgehandelter neuer Regelvertrag nötig. Beides liegt außerhalb dieses Deltas.

### 5.2 Protokoll 3 und alte Matches

`SP/ProtocolVersion.kt:35` ff. setzt CURRENT und MIN_SUPPORTED auf 3. Version 1/2 sind bereits heute für Live-Sync ausgeschlossen; „alte Uhren kompatibel“ bedeutet hier **ältere App-Stände mit Protokoll 3**, nicht Wiederherstellung von v1/v2-Sync.

`SP/SyncCodec.kt:39` ignoriert unbekannte Felder. Das ist nur syntaktische Vorwärtskompatibilität, keine Garantie gleicher Regelauswertung.

Empfehlung:

- Protokoll 3 unverändert.
- Alte Protokoll-3-Uhr und neues Handy sowie umgekehrte Kombination mit echten alten Payload-Fixtures prüfen.
- Historische Logs ohne `ServerChosen` weiter aus Lineup/Korrekturen falten. Keine nachträglichen Wahl-Events in alte Matches schreiben.
- Vorhandene Logs mit freiem Teamwechsel, frühzeitiger zweiter Wahl und Undo müssen denselben Zustand und dieselbe Statistik behalten.
- Bei einem wieder aufgenommenen alten Match bei 0:0 darf die neue UI die Wahl anbieten; das verändert ohne Nutzereingabe keine Events.

### 5.3 Aufschlagstatistik

Vorhandene Statistik verwenden: Aufschläger aus dem Zustand **vor** jedem wirksamen Punkt, nicht aus dem neuesten Auswahlbutton.

Abzusichern:

- Punkte werden nach Wahl/Rotation den richtigen vier Spieler-IDs zugeordnet.
- Tiebreak-Zweierblöcke, Undo/Redo und Korrekturen ergeben dieselbe Zuordnung bei Replay und inkrementeller Verarbeitung.
- Aufschlagspiele folgen der bestehenden Definition: Standardspiel dem Aufschläger des letzten Punktes zuordnen.
- Ohne erfasste Aufschlagdetails keine erfundene Quote für ersten/zweiten Aufschlag.
- Unzugewiesene Platzhalter bleiben gemäß heutiger Statistikdarstellung ausgefiltert; die Punktzuordnung zur Platz-ID bleibt erhalten. Nach Personenzuweisung darf die bestehende Projektion diese Statistik anzeigen. Eine neue persönliche Statistikliste für „Partner/Gegner 1/2“ ist nicht Teil des Aufschlagwahl-Deltas.

## 6. Arbeitspakete, Reihenfolge und Abnahme

Umsetzung AP1/AP2: ursprünglicher Auftrag. AP3–AP7: Folgeauftrag im Worktree `H:/racketone-wt/w8-ui`, Branch `w8/aufschlagwahl-ui`, Basis `d6b04f5`. Die lokale Abnahme und die noch offenen Plattformprüfungen stehen in §6.2.

### AP1 – Vertrag und Regressionen festhalten

**Stand 16.10.2026:** umgesetzt in `d7dfe3e`; fünf zusätzliche Engine-Regressionen, `:shared:core-scoring:jvmTest` und vollständiges `./gradlew check --console=plain` grün (JDK 21, Windows/Git Bash). Keine Änderung der Fold-Semantik. Die vorhergesagten Undo-/Korrekturbesonderheiten bestätigt; kein zusätzlicher Engine-Bug festgestellt.

**Dateien:** `docs/02-scoring-regeln.md`, `docs/w2-ui-vertrag.md`, `docs/04-ux-design.md`; core-scoring `OperationRulesTest.kt`, `ScorerRulesTest.kt`.

**Arbeit:** Automatisch anzeigen, „Später“, Game-/Tiebreak-Texte und Scope festschreiben. Vorhandene Tests erweitern um Undo über Gamegrenzen, Tiebreak-Punkt-1-Undo und Kollision `INITIAL_SERVER`/explizite Wahl. Keine Änderung der historischen Fold-Semantik.

**Abnahme:** Erwartete Zustände sind für alle genannten Fälle explizit belegt; Dokumentation unterscheidet Standardregel, bestehende Freizeitregel und UI-Verhalten.

### AP2 – Gemeinsamer Wahlkontext und sichere Kommandos

**Stand 16.10.2026:** umgesetzt; `ServerChoiceContext.kt` in match-session, kompatible sichere Auswahl-Overloads in Session/Ports/Apple-Facade und Schutz gegen übersteuerte rückwirkende Korrekturen. API-Übergabe: [w2-ui-vertrag §6.1](w2-ui-vertrag.md#61-api-für-ap3ap6-ap2). Tests in `OperationSessionTest`, `OperationPortTest`, `SessionMatchStoreTest` und `WatchScoringTest`; gezielte JVM-Tests und vollständiges `./gradlew check --console=plain` unter Windows/Git Bash grün (JDK 21). Keine Änderung an Fold, Protokoll 3, Eventtypen oder den UI-Dateien von AP3–AP6. Die bisherige Apple-Prompt-Unterdrückung bleibt ausdrücklich bis AP5 erhalten.

**Auffälligkeiten:** AP1 bestätigt die bekannte Null-Ballwechsel-Umdeutung und INITIAL_SERVER-Kollision; kein zusätzlicher Engine-Bug gefunden. AP2 weist die Kollision lokal vor dem Schreiben ab und bietet einen Verfügbarkeitshinweis. Redo eines im Log vor der Wahl stehenden Punkts behält dessen historischen Aufschläger; der spätere Wahl-Event kann wieder als frühzeitige zweite Wahl gelten. Das ist bestehende Replay-Semantik, keine rückwirkende Neuzuordnung durch die Auswahl. Apple-Linken, Swift-Build und Simulatorprüfung sind weiterhin macOS-/Folgepaket-Aufgaben.

**Dateien:** `MS/MatchSession.kt`, kleine neue Datei für Wahlkontext; `PR/match/MatchPorts.kt`, `SessionMatchStore.kt`, `InMemoryMatchStore.kt`; `AW/WatchScoring.kt`.

**Arbeit:** Fensterkontext aus Enginezustand ableiten; veraltete Auswahlkommandos vor Eventerzeugung abweisen. Vorhandene API-Aufrufe kompatibel halten, soweit benötigt; keine Wire-Änderung. Korrekturpfade auf tatsächliche Wirkung und Beschriftung abstimmen.

**Tests:** `OperationSessionTest.kt`, `OperationPortTest.kt`, `WatchScoringTest.kt`: falsche Rolle, gleiche Wahl, veraltetes Fenster, fehlende Personenauflösung, aufgehobene Wahl, Vorwärtskorrektur nach expliziter Wahl.

**Abnahme:** Gleiche zulässige Auswahl auf allen Frontends; kein Event durch stale Tap; kein „erfolgreich korrigiert“, wenn die resultierende Wahl die gewünschte Korrektur übersteuert.

### AP3 – Wear-Setup auf Live-Auswahl umstellen

**Dateien:** `WE/ui/SetupScreen.kt`, `WE/live/WatchController.kt`, `WE/live/WatchState.kt`, gegebenenfalls `WE/match/SetupCatalog.kt`; DE/EN-Strings.

**Arbeit:** Für Tennis-/Padel-Doppel separaten Start ohne verbindliche Aufschlägerwahl anbieten. Besetzungs- und Matchoptionen beibehalten. Alte Setupwege für Einzel/andere Sportarten erhalten.

**Pflicht – Korrektur-UI:** Wear (`WE/live/LiveModel.kt:134–140`, `WE/ui/LiveDialogs.kt:373`) bietet „ab Beginn“ nur bei `canCorrectServerRetroactively`, sonst Label „ab jetzt“; abgewiesene Korrektur zeigt Hinweis statt stumm zu schließen.

**Tests:** `WatchControllerTest.kt`, `WatchControllerOperationTest.kt`, `SetupCatalogTest.kt`.

**Abnahme:** Neu-/Schnell-/Folgestart erzeugen ein gültiges Match und landen vor Punkteingabe im ersten Wahlfenster; keine doppelte Setup- und Live-Abfrage.

### AP4 – Wear-Auswahl automatisch präsentieren

**Dateien:** `WE/live/Operation.kt`, `LiveModel.kt`, `WatchController.kt`; `WE/ui/LiveScreen.kt`, `LiveDialogs.kt`, `OperationViews.kt`.

**Arbeit:** Erste-Prompt-Unterdrückung entfernen; Fensterübergänge und lokale Dismiss-Merker steuern; vorhandenen Auswahlbildschirm wiederverwenden; Mindestziele/zweizeilige Namen und Tiebreaktexte absichern. Overlay gegen durchgereichte Punkteingaben schützen.

**Tests:** `OperationRulesTest.kt`, `WatchControllerOperationTest.kt`, `LiveModelTest.kt`; gezielte UI-/Geräteprüfung.

**Abnahme:** Vier → zwei → automatische Rotation; „Später“ ohne Wiederöffnungsschleife; kein Punkt durch Auswahl-Tap; Lock/Resume, Undo, veraltete Fenster und Rollenwechsel funktionieren.

### AP5 – Apple-Watch-Parität

**Dateien:** `AW/WatchScoring.kt`, `WatchScoringTest.kt`; `watchApp/StartView.swift`, `ScoringModel.swift`, `ScoringView.swift`, `OperationViews.swift`; DE/EN-`Localizable.strings`.

**Arbeit:** Startbutton statt Setup-Aufschlägerwahl für den Scope; automatische Sheet-Steuerung, „Später“, Teamlabels, Fensterprüfung und Eingabeschutz. Facade-Prompt nicht mehr pauschal im ersten Satz unterdrücken.

**Tests:** Facade-Tests; watchOS-Simulator-/UI-Prüfung auf macOS, kleine Apple Watch, Crown/VoiceOver, Hintergrund/Rückkehr.

**Abnahme:** Gleiche fachliche Zustände und Auswahlzeitpunkte wie Wear; mindestens 44 pt; keine automatische Auswahl auf Anzeige-Geräten.

### AP6 – Handy-Wizard und Live-Screen angleichen

**Dateien:** `PH/wizard/PlayersStep.kt`, gegebenenfalls `PR/wizard/MatchWizard.kt`; `PH/live/LiveOperations.kt`, `LiveSheets.kt`, `LiveScoreScreen.kt`, `LiveScoreController.kt`; DE/EN-Ressourcen.

**Arbeit:** Erstaufschlag im Doppel zum aktiven Zähler verlagern; automatische Live-Auswahl am Handy-Zähler; vorhandene Bar als Wiedereinstieg behalten. Auf Anzeige-Handy nur Status.

**Pflicht – Korrektur-UI:** Phone (`PH/live/LiveSheets.kt:355–380`) bietet „ab Beginn“ nur bei `canCorrectServerRetroactively`, sonst Label „ab jetzt“; abgewiesene Korrektur zeigt Hinweis statt stumm zu schließen.

**Tests:** `LiveOperationsUiTest.kt`, `LiveScoreScreenTest.kt`, vorhandene Wizard-Tests und `StringResourcesParityTest`.

**Abnahme:** Handy-Director kann dieselben vier/zwei Spieler wählen; bei Uhr-Zähler keine Doppelabfrage; Einzel und andere Sportarten unverändert.

### AP7 – Mischbetrieb, Statistik und Endabnahme

**Dateien:** `shared/sync-protocol/src/commonTest/.../SyncCodecTest.kt`; `OperationSessionTest.kt`; `PR`-Tests `OperationPortTest.kt`, `stats/MatchStatisticsTest.kt`; bestehende Geräte-/Release-Testdokumentation.

**Tests/Abnahme:**

- Neues Handy ↔ alte v3-Uhr und neue Uhr ↔ alter v3-Handy-Stand: gleiche Events ergeben gleichen Stand, Aufschläger und Statistik.
- Offline-Wahl, mehrfach zugestellte Events, verspätete Events und bestätigte Rollenübernahme.
- Tennis und Padel; Standard, No-Ad/Golden/Star, kurze Sätze, regulärer Tiebreak, Match-Tiebreak, Nur-Tiebreak 7/10; beide eröffnenden Teams und alle Spieler.
- Undo/Redo über Game- und Satzgrenzen einschließlich der ausdrücklich dokumentierten Null-Ballwechsel-Besonderheit.
- Platzhalter, echte Namen, gleiche/lange Namen, DE/EN, vergrößerte Schrift, kleine/runde Displays.
- Negative Fälle: Einzel, Pickleball Side-Out/Rally, Badminton, Tischtennis öffnen keine neue Auswahl.
- Historische Logs und aktuelle Statistik bleiben ohne Nutzereingriff unverändert.

**Reihenfolge:** AP1 → AP2 → AP3/AP4 → AP5 → AP6 → AP7. AP3/AP4 können gemeinsam umgesetzt, aber getrennt geprüft werden. Kein neues Scoringmodell vor den UI-Arbeiten.

**Spätere technische Prüfungen:** betroffene JVM-/Desktop-/Wear-Tests; anschließend `gradlew.bat check` und Android-/Wear-Debug-Builds gemäß `CLAUDE.md`. Apple-Framework-Linken, Swift-Build und watchOS-Simulatorprüfung auf macOS/CI. AP1 hat `:shared:core-scoring:jvmTest` und den vollständigen Windows-Check ausgeführt; die UI-/Geräteprüfungen bleiben den Folgepaketen vorbehalten.

### 6.2 Lokale Abnahme AP3–AP7 (16.10.2026)

- **AP3 / `80c442b`:** Wear-Setup zeigt im Tennis-/Padel-Doppel „Match starten“, ohne Erstaufschlagwahl. Besetzung und Optionen bleiben erreichbar. Korrektur nur mit Session-Freigabe rückwirkend, sonst „ab jetzt“; abgewiesene Korrektur bleibt mit Hinweis offen. Controller-/Modelltests und vollständiger Check grün.
- **AP4 / `deffc23`:** Wear präsentiert vier/zwei Spieler automatisch. Lokale Fenstermerker, sichere Kontextübergabe, Lock-/Hintergrundschutz, Undo und Eingabeschutz. Scrollbare Rotary-Liste mit zweizeiligen Namen, Team/Platz im TalkBack-Label, Tiebreakkontext und tatsächlichem Default. Robolectric prüft 206 dp rund in DE/EN, Schrift 1,0/1,3, Kreisgrenzen und mindestens 48 dp; vorhandene Rotary-Tests bleiben grün.
- **AP5 / `8d985f2`:** Apple-Watch-Start ohne Setup-Wahl, automatische Auswahl am Zähler, „Später“, Team-/Platzlabels und mindestens 44 pt. Sheet am gemeinsamen Wurzelview, damit Steuerseite, Satzende-Undo und Hintergrundwechsel denselben Zustand verwenden. Facade-Tests für Tennis/Padel und Tiebreak-only sowie String-Prüfung grün. Swift bleibt ausschließlich für Darstellung und lokale Präsentation zuständig.
- **AP6 / `eea78ca`:** Handy-Wizard ohne Erstaufschlagwahl für den Scope; automatisches Live-Sheet nur am aktiven Handy-Zähler. Vorhandene Bar bleibt zugänglich; Hintergrundsemantik und Controller sperren Punkte während der Auswahl. Korrekturgrenze und sichtbare Ablehnung geprüft. Wizard-, Schnellstart-, Folgesatz-, Rollen- und UI-Tests sowie String-Parität grün.
- **AP7:** 64 Session-Szenarien (acht Tennis-/Padel-Formate × zwei ursprüngliche Zählergeräte × vier Eröffnungsspieler) prüfen Offline-Wahl, doppelte Zustellung, Rollenübernahme und Zustandsgleichheit mit Replay. Eingefrorene v3-Payloads prüfen die unveränderte Serialisierung in beiden Richtungen. Statistiktests sichern Spielerzuordnung, Tiebreak-Zweierblock, Vorwärtskorrektur und Undo/Redo; die unabhängig laufende Matchdauer wird beim Statistikvergleich ausgeklammert. UI-Regressionen prüfen die markierte Wahl nach Undo am Beginn eines späteren Satzes, Hintergrund und Touch-Sperre; lange gleichnamige Wear-Spieler bleiben per Team und Platznummer barrierefrei unterscheidbar. Platzhalter behalten die Punktzuordnung zur Platz-ID, persönliche Statistikzeilen erscheinen erst nach Personenzuweisung. Die Aufschlag-Info des letzten Punktes bleibt nach einer längeren Auswahl erreichbar.

Verifikation: `JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot`, `GRADLE_USER_HOME=H:/dev/gradle`, `./gradlew check --console=plain`. Zusätzlich `./gradlew :androidApp:assembleDebug :wearApp:assembleDebug --console=plain` und `python .github/scripts/check_watch_strings.py` (tatsächlicher Skriptpfad in diesem Checkout). Kein neuer Eventtyp, kein neues Wire-Feld, unverändert Protokoll 3 und unveränderte Fold-/Replay-Implementierung.

Screenshots: `ADVANTLY_SCREENSHOT_DIR=H:/dev/tmp/shots-aufschlagwahl`, Dateien `choice-*.png` (Start, mittig gescrollte Spieler und „Später“; 206 dp). Diese lokalen Dateien werden nicht eingecheckt.

**Offene Plattformabnahmen:** Swift-Build, Framework-Linken, kleiner watchOS-Simulator, Crown/VoiceOver und reale Sperr-/Hintergrundwechsel auf macOS/CI bzw. Geräten. Android-/Wear-Prüfung lokal per Robolectric und Debug-Build; kein physischer Uhrentest. Die v3-Tests verwenden eingefrorene Payloadformen und den kompatiblen bisherigen Kommandoaufruf; ein separater historischer App-Binary-Stand wurde nicht auf Geräten ausgeführt. Diese Prüfungen werden nicht als bestanden ausgegeben.

## 7. Risiken und inzwischen entschiedene Nutzerfragen

Größtes Risiko ist nicht die Vierer-Auswahl, sondern eine unbeabsichtigte Änderung bestehender Replay-Regeln. Weitere Risiken: doppelte Setup-/Live-Abfrage, wiederholt öffnende Sheets, ein alter Tap im neuen Fenster, falsche Wir/Gegner-Zuordnung und als rückwirkend bezeichnete Korrekturen ohne entsprechende Wirkung.

Die ursprünglichen fünf Fragen sind seit 16.10.2026 entschieden (siehe oben); die Empfehlungen gelten als verbindliche Antworten:

1. **Darf mit „Später“ ohne Wahl weitergezählt werden?** Empfehlung: ja. Automatisch anzeigen, aber den vorhandenen Default offen benennen; kein Pflicht-Gate.
2. **Soll „Erster Aufschlag“ für Tennis-/Padel-Doppel vollständig aus dem Setup zum Live-Zähler wandern, auch am Handy?** Empfehlung: ja; so erfolgt die Wahl genau einmal am zuständigen Gerät.
3. **Bei neuen Sätzen: bestehende freie Teamwahl oder nur das regelgemäß aufschlagende Team?** Empfehlung für dieses Delta: erneute Nachfrage und bestehende bestätigte Freizeitregel erhalten. Ein strikter Modus ist eine bewusste Folgeentscheidung.
4. **Soll die Auswahl auch für Pickleball, Badminton oder Tischtennis gelten?** Empfehlung: nein; ausschließlich Tennis-/Padel-Doppel, einschließlich ihrer Tiebreak-Formate.
5. **Ist die bestehende Undo-Semantik akzeptabel, insbesondere die Überprüfung der Wahl nach Rückkehr auf null Ballwechsel?** Empfehlung: für dieses kompatible UI-Delta beibehalten und sichtbar machen. Eine strikt an Game/Punkt gebundene Wahl samt komfortabler rückwirkender Korrektur separat planen; sie darf nicht als bloßes optionales Wire-Feld eingeführt werden.

