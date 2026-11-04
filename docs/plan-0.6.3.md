# Plan 0.6.3: „Spieler N“ statt „?“, Upload robuster gegen abgelehnte Matches

> Status: Entwurf 2026-11-04 · Bezug: 0.6.1 (PR #93, Upload-Fix leere Namen), Review GPT-6 Astra zu PR #93,
> 0.6.2 (PR #96) · Version 0.6.3, Build 16 (versionCodes 161/162)

## 1. Ziel

Drei Nacharbeiten aus 0.6.1/0.6.2:

1. **Anzeige:** Plätze ohne Namen stehen in Cloud-Ansichten als „?“. Stattdessen erscheint überall „Spieler 1“, „Spieler 2“ …
   (EN „Player 1“, „Player 2“ …).
2. **Aushungern:** Ein dauerhaft abgelehntes Match darf den Upload der Events anderer Matches nicht aufhalten.
3. **Doppelte Position:** Haben zwei Plätze dieselbe Team-Position, wird nur dieses Match abgelehnt, nicht der ganze
   Upload-Batch.

## 2. Ausgangslage (Stand 0.6.2)

- Server `private.push_participants` ersetzt seit `20261103000100_leere_teilnehmernamen.sql` leere Namen durch `?`.
  Die App (`CloudSyncMetadata.uploadParticipants`) sendet für unbenannte, nicht redigierte Plätze ebenfalls `?`.
- Anzeige von Cloud-Teilnehmern: Feed/geteilte Matches (`ui-phone/.../friends/SharedMatchesUi.kt`), Live- und
  Zuschaueransicht (Handy, Wear), watchApp (`OperationViews.swift`). Leere Namen fallen dort teils auf „Unbekanntes Profil“
  zurück, `?` erscheint unverändert.
- Upload-Auswahl `CloudUploadStore` (`shared/data`): Events werden seitenweise je Match gewählt (`pendingForProfile`,
  Limit 500 je Batch). Gesperrte Matches überspringt sie bereits über `LifecycleProof.admits`. Dauerhaft vom Server
  abgelehnte Matches (`rejected/invalid`) überspringt sie nicht: Ihre Events kommen jedes Mal wieder als `unknown_match`
  zurück. Seit 0.6.1 meldet der Runner dann `BLOCKED` statt im Minutentakt zu wiederholen. Liegen aber ≥ 500 Events eines
  solchen Matches vorne, füllen sie jeden Batch, und spätere Matches kommen nicht dran.
- `match_participants` hat `participant_team_position_uq UNIQUE (match_id, team, position) DEFERRABLE INITIALLY DEFERRED`.
  Die Prüfung läuft erst beim Commit, also außerhalb des Exception-Blocks von `private.push_match`. Ein Verstoß rollt den
  ganzen `cloud_sync_push_v1`-Aufruf zurück (HTTP-Fehler), statt nur dieses Match als `rejected` zu melden.

## 3. Umsetzung

### 3.1 „Spieler N“ (nur Clients)

- **Nummer aus dem festen Platz**, gleich auf allen Geräten und für alle Betrachter:
  Einzel A = 1, B = 2; Doppel A1 = 1, A2 = 2, B1 = 3, B2 = 4.
  Bewusst kein „Gegner“: Das stimmt nur aus Sicht des Erstellers, nicht für Zuschauer oder das andere Team.
- **Wann:** Der Server-Name ist `?`, leer oder fehlt, und der Platz ist weder verknüpft noch redigiert.
  Verknüpfte Konten zeigen weiter ihren Alias, redigierte Plätze „Gelöschter Spieler“.
  Ein echter Gastname „?“ wird mit umgedeutet. Das ist bewusst so, weil er von einem Platzhalter nicht zu unterscheiden ist.
- **Server bleibt beim sprachneutralen `?`.** Ältere Apps zeigen weiter „?“. Keine Migration.
- **Strings DE/EN:** `ui-phone` (`player_slot_n` mit Platzhalter), Wear (`res/values*/strings.xml`), watchApp
  (`Localizable.strings` en/de), falls die watchApp diese Ansichten hat.
- **Eigene Matches bleiben, wie sie sind.** Lokale Lineups zeigen weiter „Du“, Gastnamen bzw. die bisherigen
  Platzhalter-Bezeichnungen.
- Eine gemeinsame Hilfsfunktion (Präsentation, nicht im Pure-Modul), damit Handy und Wear identisch nummerieren.

### 3.2 Abgelehnte Matches nicht mehr vorne einreihen (`shared/data`, `shared/cloud-sync`)

- **Vormerken:** Lehnt der Server ein Match endgültig ab (`rejected` mit Grund `invalid`, `court_invalid`,
  `tournament_forbidden`), merkt sich die App das je Match mit der hochgeladenen Metadaten-Revision.
  `not_owner` verhält sich weiter wie bisher.
- **Überspringen:** Die Event-Auswahl überspringt solche Matches wie gesperrte (gleicher Mechanismus wie
  `LifecycleProof.admits`). Die Events bleiben lokal ausstehend, es wird **nichts gelöscht**.
- **Wieder zulassen:** Sobald sich die Metadaten des Matches ändern (neue Revision), das Konto wechselt oder der Nutzer manuell
  synchronisiert. Ein geänderter Server (wie bei 0.6.1) greift damit spätestens beim nächsten manuellen Abgleich.
  Optional zusätzlich nach 24 h automatisch erneut versuchen.
- **Inventar/Anzeige:** Zurückgestellte Matches zählen als blockiert (wie heute `BLOCKED`), nicht als „hochgeladen“.

### 3.3 Doppelte Position im Match-Block abfangen (Migration)

- In `private.push_match` direkt nach `push_participants`, noch im inneren `begin … exception`:
  `set constraints public.participant_team_position_uq immediate;` und danach wieder `deferred`.
  Ein Verstoß fällt so als `integrity_constraint_violation` in den bestehenden Handler und ergibt
  `rejected/invalid` mit `detail` nur für dieses Match.
- **Positionswechsel in einem Upload** (A1 ↔ A2 tauschen) müssen weiter gehen. Deshalb wird erst nach allen Teilnehmern geprüft,
  nicht je Zeile.
- Umsetzung als Seam-Ersatz wie in den Vormigrationen. Signatur, Owner, ACL, `security definer` und `search_path` bleiben.

## 4. Tests

- **3.1:** Kotlin-Tests für die Nummerierung (Einzel/Doppel, `?`/leer/null, verknüpft, redigiert). UI-Tests DE/EN für
  Feed und Live (`desktopTest`). Wear-Unit-Test für die Live-Ansicht.
- **3.2:** Regressionstest mit zwei Matches. Match 1 ist dauerhaft abgelehnt und hat ≥ 500 Events, Match 2 ist gültig.
  Erwartet: Match 2 wird vollständig hochgeladen, die Events von Match 1 bleiben ausstehend. Nach einer Metadaten-Änderung
  bzw. einem manuellen Abgleich kommt Match 1 wieder dran.
- **3.3:** pgTAP – zwei Matches im selben Batch, eines mit doppelter Position.
  Erwartet: Der Batch ist erfolgreich, das betroffene Match ist `rejected/invalid`, das andere `created`.
  Ein Platztausch A1 ↔ A2 im Update bleibt erlaubt.
  Der Test muss den deferred Constraint wirklich auslösen (nicht nur bis zum Rollback laufen).
- Der Vertragstest App↔Server (Fixture `cloud_sync_push_placeholder_match.json`, pgTAP 450) bleibt grün.

## 5. Release

- Version 0.6.3, Build 16. Handy und Uhr **aus demselben sauberen Commit** bauen, sonst zeigt die Uhr gelb oder
  „Entwicklungsbuild“.
- Die Migration (3.3) geht vor der App auf Staging. Sie ist abwärtskompatibel, alte Apps profitieren mit.
- „Was ist neu?“ (`whats_new_16_*`, 2–4 Punkte, DE/EN) und Release-Notes intern DE/EN.
- Keine neue Fassung der Datenschutzerklärung nötig (keine neuen Daten oder Empfänger).

## 6. Offen

- Ob die watchApp (Apple Watch) Cloud-Teilnehmernamen überhaupt anzeigt. Wenn nicht, entfällt 3.1 dort.
- Automatischer Neuversuch für zurückgestellte Matches (3.2) nach 24 h – ja oder nur manuell.
