# Plan 0.6.3: „Spieler N“, Gast mit Freund verknüpfen, Upload robuster

> Status: Entwurf 2026-11-04 · Bezug: 0.6.1 (PR #93, Upload-Fix leere Namen), Review GPT-6 Astra zu PR #93,
> 0.6.2 (PR #96) · Version 0.6.3, Build 16 (versionCodes 161/162)

## 1. Ziel

Nacharbeiten aus 0.6.1/0.6.2 und Nutzerwünsche vom 04.11.2026:

1. **Anzeige:** Plätze ohne Namen stehen in Cloud-Ansichten als „?“. Stattdessen erscheint überall „Spieler 1“, „Spieler 2“ …
   (EN „Player 1“, „Player 2“ …).
2. **Aushungern:** Ein dauerhaft abgelehntes Match darf den Upload der Events anderer Matches nicht aufhalten.
3. **Doppelte Position:** Haben zwei Plätze dieselbe Team-Position, wird nur dieses Match abgelehnt, nicht der ganze
   Upload-Batch.
4. **Platzhalter zuweisen:** In der nachträglichen Zuweisung stehen auch Freunde aus der Freundesliste und „Du“ zur Wahl.
5. **Gast mit Freund verknüpfen:** Einmalig alle Matches eines Gasts (z. B. „Test1“) einem Freund (z. B. Anja) zuordnen.
6. **Kontolöschung:** Ergebnisse bleiben bei den anderen erhalten, Namen werden zu „Gelöschter Spieler“; mit 3.1 und 3.5
   abgestimmt und getestet. Der Feldtest folgt mit 0.6.3.

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
- **Wieder zulassen**, wenn eines davon eintritt:
  - Die Metadaten des Matches ändern sich (neue Revision).
  - Das Konto wechselt.
  - Der Nutzer synchronisiert manuell.
  - Seit der Ablehnung sind **24 h** vergangen. Dafür wird der Zeitpunkt der Ablehnung mitgespeichert.
    Den nächsten Versuch löst der nächste reguläre Anlass aus (App im Vordergrund, periodischer Job), es gibt keinen eigenen Wecker.
    Lehnt der Server erneut ab, beginnen die 24 h von vorn.
  Eine Server-Korrektur wie in 0.6.1 kommt damit spätestens nach einem Tag bei allen an.
- **Inventar/Anzeige:** Zurückgestellte Matches zählen als blockiert (wie heute `BLOCKED`), nicht als „hochgeladen“.

### 3.3 Doppelte Position im Match-Block abfangen (Migration)

- In `private.push_match` direkt nach `push_participants`, noch im inneren `begin … exception`:
  `set constraints public.participant_team_position_uq immediate;` und danach wieder `deferred`.
  Ein Verstoß fällt so als `integrity_constraint_violation` in den bestehenden Handler und ergibt
  `rejected/invalid` mit `detail` nur für dieses Match.
- **Positionswechsel in einem Upload** (A1 ↔ A2 tauschen) müssen weiter gehen. Deshalb wird erst nach allen Teilnehmern geprüft,
  nicht je Zeile.
- Umsetzung als Seam-Ersatz wie in den Vormigrationen. Signatur, Owner, ACL, `security definer` und `search_path` bleiben.

### 3.4 Platzhalter-Auswahl: Freunde und „Du“ (`ui-phone`, `presentation`)

- `PlaceholderAssignments` bietet heute nur zuletzt gesehene Mitspieler, Gäste und den eigenen Spieler an. Personen ohne Namen
  filtert es aus, und der eigene Spieler hat lokal keinen Namen, fällt also immer weg.
- **Neu:**
  - Freunde aus der Freundesliste (Spieler-Art FRIEND, wie im Match-Assistenten) werden angeboten.
  - „Du“ wird lokalisiert angezeigt (wie im Assistenten) und nicht mehr wegen des leeren Namens ausgefiltert.
  - Bereits belegte Personen bleiben ausgeschlossen.
- Wird ein Freund zugewiesen, verknüpft der nächste Upload den Platz mit seinem Konto (`profileId` aus `friendPlayerQueries`).
  Der Server benachrichtigt ihn wie gehabt (`match_shared`).

### 3.5 Gast mit Freund verknüpfen (`cloud-sync`, `presentation`, `ui-phone`)

- **Server:** Bereits vorhanden (Migration `20261015000700_rpc_zuweisung_claim.sql`, mit Rate-Limit-Fassung).
  - `public.assign_player(player, profile)` ordnet alle bisherigen Teilnehmer-Zeilen des Gasts dem Freund zu.
    Übersprungen werden Matches, in denen er schon steht oder die er abgelehnt hat. Die Funktion benachrichtigt ihn
    (`player_assigned`) und gibt die Anzahl der Matches zurück.
  - `public.unassign_player(player)` nimmt die Zuordnung zurück.
  - `public.reject_player_assignment(player)` ist „Das war ich nicht“ des Freundes.
  - Künftige Uploads mit diesem Gast werden automatisch verknüpft (`push_participants`: `players.profile_id` → `assigned`).
  - Zuerst prüfen: Sind diese RPCs mit dem Cloud-Binding und der Versionssperre des Clients aufrufbar, wie die übrigen
    `cloud_*_v1`? Wenn nicht, schlanke `cloud_*_v1`-Hüllen per Migration ergänzen. Keine fachliche Änderung.
- **App, Anleger:**
  - In der Gästeverwaltung (Profil bzw. Personen) bekommt jeder Gast die Aktion „Mit Freund verknüpfen“, danach die
    Freundeswahl.
  - Bestätigung: „Alle Matches mit Test1 werden Anja zugeordnet. Anja wird benachrichtigt und kann widersprechen.“
  - Danach ein Hinweis mit der Anzahl verknüpfter Matches.
  - Ein verknüpfter Gast zeigt „verknüpft mit Anja“ und die Aktion „Verknüpfung lösen“.
  - In den eigenen Matches erscheint statt des Gastnamens der Name des Freundes.
- **App, Freund:**
  - Push und Text gibt es schon (`PLAYER_ASSIGNED`). Die Matches erscheinen über den normalen Pull in seinem Verlauf.
  - Dort kann er für die Zuordnung „Das war ich nicht“ wählen (`reject_player_assignment`). Danach bekommt er diesen Gast
    nie wieder zugeordnet.
- **Fehlerfälle mit eigenem Text (DE/EN):**
  - nicht (mehr) befreundet
  - schon verknüpft
  - früher abgelehnt
  - offline bzw. Konto nicht bereit
- Nur mit Konto und aktueller Zustimmung. Ohne Cloud ist die Aktion ausgeblendet.
- **Datenschutz:** Keine neuen Datenarten. Die Verknüpfung von Teilnehmern mit Freundeskonten steht schon in der
  Datenschutzerklärung (Freunde/geteilte Matches). Vor dem Release mit §4.3 der Erklärung abgleichen. Falls ein Satz fehlt,
  den Nutzer fragen (neue Fassung = neue Bestätigung).

### 3.6 Kontolöschung: Verhalten prüfen und absichern (keine neue Fachlogik)

Heutiger Stand laut `20261015000800_rpc_konto_export.sql` und `20261022000200_staged_account_deletion.sql`, der beim Bauen
per Test zu bestätigen ist:
- **Löscht ein Mitspieler sein Konto** (z. B. Anja):
  - Bei den anderen bleiben das Match, der Spielstand, die Sätze, der Sieger und die Statistik unverändert.
  - Ihr Platz heißt danach „Gelöschter Spieler“, auch in den Event-Payloads (Redaktionsepoche, Clients laden neu).
  - Ihr Konto, ihr Verlauf und ihre Statistik sind weg.
- **Löscht der Ersteller sein Konto:**
  - Matches mit weiteren verknüpften Konten gehen an den ersten verknüpften Teilnehmer über.
  - Matches nur mit Gästen werden gelöscht.
  - Seine eigenen Gäste werden in gemeinsamen Matches zu „Gelöschter Spieler“.
- **Gast, der einem Freund zugeordnet ist (3.5), und der Freund löscht sein Konto:**
  - In den Matches steht „Gelöschter Spieler“, nicht wieder „Test1“. Das ist datenschutzkonform, weil der Gast mit ihrer
    Identität verknüpft war.
  - Die Ergebnisse bleiben.
  - Den Gast lässt die App nicht erneut verknüpfen (Redaktions-Schreibsperre).
- **Löscht der Freund während einer laufenden Zuordnung** (Status `deleting`), scheitert `assign_player` sauber mit eigenem
  Fehlertext.

Umsetzung in 0.6.3:
- **„Gelöschter Spieler“ lokalisiert anzeigen:** EN „Deleted player“. Der Server liefert dazu das Flag `deleted_profile`.
  Es hat Vorrang vor „Spieler N“ (3.1) und vor Gast- bzw. Freundesnamen.
- **Gästeliste (3.5):** Ein Gast, dessen Freund gelöscht wurde, wird nicht mehr als „verknüpft mit …“ geführt. Die Aktion
  „Mit Freund verknüpfen“ fehlt, wenn er redigiert ist.
- **Tests:** pgTAP-Szenario: Gast zuordnen, Freund löscht sein Konto. Erwartet: Beim Anleger ist das Match erhalten, das
  Ergebnis unverändert, der Name „Gelöschter Spieler“, `players.profile_id` leer, eine erneute Zuordnung abgelehnt.
  Dazu Client-Tests für Anzeige und Gästeliste.

## 4. Tests

- **3.1:** Kotlin-Tests für die Nummerierung (Einzel/Doppel, `?`/leer/null, verknüpft, redigiert). UI-Tests DE/EN für
  Feed und Live (`desktopTest`). Wear-Unit-Test für die Live-Ansicht.
- **3.2:** Regressionstest mit zwei Matches. Match 1 ist dauerhaft abgelehnt und hat ≥ 500 Events, Match 2 ist gültig.
  Erwartet: Match 2 wird vollständig hochgeladen, die Events von Match 1 bleiben ausstehend. Nach einer Metadaten-Änderung,
  einem manuellen Abgleich oder 24 h (fester Testzeitpunkt) kommt Match 1 wieder dran. Bei erneuter Ablehnung beginnt die
  Frist von vorn.
- **3.3:** pgTAP – zwei Matches im selben Batch, eines mit doppelter Position.
  Erwartet: Der Batch ist erfolgreich, das betroffene Match ist `rejected/invalid`, das andere `created`.
  Ein Platztausch A1 ↔ A2 im Update bleibt erlaubt.
  Der Test muss den deferred Constraint wirklich auslösen (nicht nur bis zum Rollback laufen).
- **3.4:** UI-Test (`desktopTest`): Freund und „Du“ stehen in der Auswahl, belegte Personen nicht. Die Zuweisung eines
  Freundes setzt beim Upload `profileId`.
- **3.5:** HTTP-Tests für die drei Aufrufe inkl. Fehlercodes (`cloud-sync`). ViewModel-Tests (Bestätigen, Anzahl, Lösen,
  Fehlertexte). UI-Tests DE/EN. pgTAP nur für neue Hüllen-RPCs (Binding, Rechte).
- Der Vertragstest App↔Server (Fixture `cloud_sync_push_placeholder_match.json`, pgTAP 450) bleibt grün.

## 5. Release

- Version 0.6.3, Build 16. Handy und Uhr **aus demselben sauberen Commit** bauen, sonst zeigt die Uhr gelb oder
  „Entwicklungsbuild“.
- Die Migration (3.3) geht vor der App auf Staging. Sie ist abwärtskompatibel, alte Apps profitieren mit.
- „Was ist neu?“ (`whats_new_16_*`, 2–4 Punkte, DE/EN) und Release-Notes intern DE/EN.
- Keine neue Fassung der Datenschutzerklärung nötig (keine neuen Daten oder Empfänger).

## 6. Offen

- Ob die watchApp (Apple Watch) Cloud-Teilnehmernamen überhaupt anzeigt. Wenn nicht, entfällt 3.1 dort.
