# Privater Meldeweg und Admin-Inbox

Stand: 16.10.2026. Erste Lieferung aus [Feedback & Admin](plan-feedback-admin.md).
Noch keine Produktionsbereitstellung und kein eingerichteter Betreiberzugang. Die Datenbankmigration,
die Handy-Anbindung und `admin/` werden zusammen ausgeliefert; die folgenden Live-Nachweise bleiben ein Gate.

## Funktionen und Grenzen

Android: Profil → Feedback & Hilfe, auch im Kontobereich erreichbar. Die bestehende Auth-Sitzung wird
für jeden Versand festgehalten. iOS besitzt derzeit keinen Konto-Host und versendet als Gast.
Beide Plattformen: Feedback, Funktionswunsch oder Fehler, Titel/Text, freiwillige Kontaktadresse,
ausdrücklich ausgewählte technische Angaben mit Vorschau, lokaler Entwurf und bestätigte Vorgangsnummer.
Keine Screenshots, automatischen Logs, Match- oder Fitnessdaten. Texteingaben sind privat, keine GitHub-Issues.
Antworten erscheinen im Bereich „Meine Meldungen“ für das angemeldete Besitzerkonto. Gastmeldungen sind
weder für den Gast in der App lesbar noch einem später angemeldeten Konto zugeordnet; Support kann dazu nur
interne Notizen hinterlegen, keine öffentliche Antwort. Eine Kontaktadresse löst keine E-Mail aus. Ein
manueller externer Kontakt durch Support liegt außerhalb dieser App.

Vor dem ersten HTTP-Versand wird der Entwurf unveränderlich mit UUID und Zufallsgeheimnis gespeichert.
Ein Retry verwendet dieselben Werte. Das Geheimnis erlaubt nur die Wiederholung des Empfangsnachweises,
keinen Lesezugriff. Der Server weist abweichende Nutzdaten oder Identitäten zurück. Ein Entwurfswechsel
zieht bereits empfangene Meldungen nicht zurück. Es gibt keinen Versand im Hintergrund.
Android speichert im vorhandenen verschlüsselten App-Speicher, iOS in den privaten App-Einstellungen.
Die lokale Speicherung enthält keine Auth-Tokens. Kontowechsel trennen Entwürfe und unterdrücken verspätete
UI-Antworten; gelöschte App-Daten entfernen lokale Entwürfe.

Admin-Inbox: Suche/Statusfilter (maximal 100 neueste Treffer), Details, Status/Priorität, Zuweisung,
verwandte Meldung, interne Notiz und ausdrücklich bestätigte Nutzerantwort. Fehlerstatus „behoben“ verlangt
eine Versionsangabe. Jede Vorgangsänderung braucht eine Begründung mit 1 bis 500 Zeichen. Sie soll sachlich
bleiben und darf keine personenbezogenen oder sensiblen Angaben enthalten. Optimistische Versionen verhindern
stilles Überschreiben; Nachrichten haben eigene idempotente UUIDs. Kontosperren, Sitzungswiderruf und
Betriebskennzahlen gehören in das Folgepaket.

## Einrichtung auf Staging

1. Bestehendes [P2-C-Auth-Gate](p2c-auth.md) einhalten und Migrationen einschließlich
   `20261016000700_support.sql` anwenden. `ops` und `private` niemals als PostgREST-Schema veröffentlichen.
   Gastversand benötigt eine vom vertrauenswürdigen Gateway gesetzte `cf-connecting-ip`. Ohne diese
   bleibt er geschlossen. `app.settings.trust_xff` nur hinter einer eigenen, überprüften Proxy-Kette
   aktivieren, die eingehende Header entfernt und ersetzt. Request-Body-Limit am Gateway setzen.
2. Android: bestehende Supabase-Buildkonfiguration. iOS: Xcode-Buildsettings `ADVANTLY_SUPABASE_URL` und
   `ADVANTLY_SUPABASE_KEY`; diese landen nur im iOS-Info.plist. Ohne Konfiguration bleibt der lokale
   Entwurf nutzbar, Senden ist sichtbar deaktiviert. Ausschließlich öffentliche/publishable Schlüssel.
3. `admin/`: gemäß dortiger README mit `VITE_SUPABASE_URL` und `VITE_SUPABASE_ANON_KEY` bauen, `dist/`
   auf eigener HTTPS-Adminadresse ausliefern. Keine Service-Schlüssel und keine fremden Skripte einbetten.
   Sitzungen bleiben im Arbeitsspeicher. CSP/Sicherheitsheader des Hostings und Zugriff auf den tatsächlichen
   Supabase-Endpunkt prüfen. Der Build stellt nichts selbst online.
4. Persönliches Betreiberkonto mit verifizierter E-Mail über den bestehenden Altersgate-/Auth-Prozess
   bereitstellen. Für den separaten Admin-Login ein individuelles Passwort über einen kontrollierten
   Supabase-Administrationsweg setzen; niemals in Repository, Chat oder Client-Konfiguration speichern.
   Das Altersgate nicht zur Einrichtung global deaktivieren. Benutzer-UUID unabhängig kontrollieren.
5. Als Datenbankbetreiber gezielt provisionieren, mit der tatsächlich kontrollierten UUID:

   ```sql
   insert into ops.support_members(id,role)
   values ('<geprüfte Betreiber-UUID>'::uuid,'admin');
   ```

   `support` und `admin` erlauben in dieser Lieferung dieselben Inbox-Aktionen. Noch keine zusätzlichen
   Kontoverwaltungsrechte aus der Rollenbezeichnung ableiten. Profil-Metadaten vergeben keine Rechte.
6. Im Admin-Login TOTP einrichten und verifizieren. Erst ein MFA-JWT (`aal2`) plus aktive Mitgliedschaft
   öffnet die Inbox. Recovery: zweiter separat provisionierter Betreiber; bei Geräteverlust kontrollierter
   Identitätsnachweis und Faktor-Reset über den Supabase-Betreiberzugang, anschließend neue TOTP-Einrichtung.
   Kein MFA-Bypass in der App. Mitgliedschaft widerrufen:

   ```sql
   update ops.support_members set revoked_at=now() where id='<geprüfte Betreiber-UUID>'::uuid;
   ```

   Das wirkt beim nächsten RPC trotz bestehendem JWT. Zusätzlich Auth-Sitzungen über den Betreiberzugang
   widerrufen. Provisionierung, Recovery und Widerruf im geschützten Betriebsprotokoll dokumentieren.

## Datenschutz und Aufbewahrung

- Nur RPC-Zugriff: normale Konten lesen ausschließlich eigene Meldungen und öffentliche Antworten.
  Interne Notizen, Zuweisungen und verwandte Vorgänge fehlen im Nutzerergebnis und im Kontoexport.
- `export_my_data()` enthält alle eigenen Meldungen (auch über das Inbox-Limit hinaus). Der bestehende
  Kontolöschprozess mit Karenz entfernt beim endgültigen Löschen Tickets und Nachrichten per Kaskade.
- Geschlossene/behobene Meldungen werden 180 Tage nach letzter Änderung gelöscht, Nachrichten mitgelöscht.
  Auditaktionen bleiben ein Jahr. Vorgangsänderungen speichern die kurze Begründung und nur geänderte
  Status-, Prioritäts-, Zuweisungs-, Verknüpfungs- und Versionswerte mit vorher/nachher; Titel, Nachrichtentext,
  Kontaktadresse, Diagnose und vollständige Ticketdaten werden nicht kopiert. Nachrichtenaktionen speichern
  nur die feste Einordnung „interne Notiz“ oder „Nutzerantwort“. Gelöschte Konten/Ziele werden entkoppelt.
  Offene Vorgänge regelmäßig fachlich prüfen und abschließen, keine unbegrenzte Ablage ungenutzter Meldungen.
- `private.purge_support()` räumt stündlich per `advantly-support-cleanup` auf (maximal 500 Tickets pro Lauf).
  Ohne `pg_cron` muss ein externer Betreiberjob dieselbe Funktion ausführen. Erfolg/Fehler überwachen.
  IP-/Kontobudgets werden gehasht gespeichert und nach zwei Stunden entfernt. Grenzen: zehn neue
  Meldungen je IP bzw. Konto pro Stunde, global 500; identische Retries zählen nicht erneut.
- Gast-Auskunft/Löschanfragen benötigen einen kontrollierten Identitäts-/Besitznachweis durch Support.
  Vorgangsnummer oder eingegebene Kontaktadresse allein beweisen keine Identität. Kein öffentlicher
  Suchendpunkt. Ausnahmen und Fristen vor Pilot in die Datenschutzhinweise aufnehmen.

## Abnahme vor dem Online-Pilot

- Migration und SQL-Tests im echten Supabase-Stack; Jobs in `cron.job` aktiv und erfolgreiche Läufe in
  `cron.job_run_details` nachweisen. Lokales Windows-Postgres besitzt kein `pg_cron` und ersetzt diesen Test nicht.
- Android und iOS: jede Kategorie einreichen, Diagnosen einmal aus/ein; Payload kontrollieren, Empfang
  im Admin sehen. Gast-IP vom tatsächlichen Gateway prüfen. Netzabbruch nach Commit: Retry mit gleicher UUID,
  genau ein Ticket. Kleine Displays/große Schrift, dunkles/helles Systemdesign und Tastatur bedienen.
- Eigene/fremde Konten, Logout während Anfrage, Neustart mit Entwurf, interner Kommentar versus Nutzerantwort.
  Separat direkt die Admin-RPCs ohne Mitgliedschaft, ohne MFA und nach Widerruf aufrufen: alle verweigert.
- Zwei Betreiber ändern denselben Vorgang: Versionskonflikt statt Überschreiben. Antwort-Retry erzeugt eine
  Nachricht. Zwei-Faktor-Einrichtung, Abmeldung und Recovery mit dem vorgesehenen Betreiber prüfen.
- Kontoexport/-löschung und Aufräumjob mit Testdaten nachweisen. Auth-, Gateway- und Admin-Hosting-Konfiguration,
  Betreiberzugang und ein funktionsfähiger iOS-Konto-Host sind noch keine durch lokale Tests belegten Ergebnisse.

Lokale Prüfungen: `gradlew check`, `supabase test db`, in `admin/` jeweils `npm ci`, `npm test`, `npm run build`.
