# P2-B2 – Konto- und Antwortbindung vor dem Upload

## B2a: lokale Grenze

P2-B1 ([PR #50](https://github.com/thealextd1337-spec/racketone/pull/50)) ist nach unabhängiger Prüfung und
grüner Ubuntu-/macOS-/Supabase-CI integriert (`cc0466b`). B2a ergänzt die lokale Eigentümer- und
Antwortbindung. Es gibt weiterhin keinen automatischen Netzwerk-Upload und keine Änderung am Login-UI.

`LocalData.cloudUploads` ist die vorgesehene Upload-Grenze für B2b. Die allgemeinen B1-Primitiven unter
`LocalData.cloud` sind keine Autorisierungsschicht und dürfen nicht direkt aus Netzwerkantworten aufgerufen werden.

1. Der Auth-Host aktiviert nach bestätigter, abgeschlossener Anmeldung eine neue `CloudUploadSession`.
   Jede Anmeldung erzeugt eine neue Generation, auch für dasselbe Profil. Die Sitzung ist lokal und enthält
   kein Token. Sie wird nach einem Prozessneustart nicht automatisch aus der DB übernommen.
2. Nur explizit bestätigte **eigene** Quellgeräte werden mit `bindOwnedDevices` gebunden. Niemals Geräte-IDs
   aus empfangenen Matches pauschal übernehmen. Die Bindung ist dauerhaft und nicht überschreibbar.
   Ein anderes Konto benötigt neue eigene Geräte-IDs; die Identitäten alter Events bleiben unverändert.
   Die gesamte Bindungsliste wird bei einem Eigentümerkonflikt zurückgerollt.
3. `capture(session, limit)` wählt nur ausstehende Events dieser gebundenen Geräte, in fester Reihenfolge.
   Ungebundene/fremde Geräte und lokal gelöschte Matches sind ausgeschlossen. Die Auswahl ist unveränderlich;
   ein Aufrufer erhält nur Kopien der Liste.
4. Unmittelbar vor dem Versand erneut `requireCurrent(snapshot)` prüfen. **B2b muss zusätzlich die
   tatsächlichen HTTP-Credentials an genau dieselbe Auth-Sitzung binden.** Eine lokale Prüfung allein
   verhindert keinen Tokenwechsel zwischen Prüfung und HTTP-Aufruf.
5. Antworten nur mit `acknowledge(snapshot, uploaded, rejected)` anwenden. IDs außerhalb der Auswahl,
   eine ungültige Sitzung oder zwischenzeitlich veränderte Eventinhalte verwerfen den gesamten Ack.
   Gelöschte Events bleiben gelöscht; fehlende Antworten bleiben ausstehend. Geräte-Acks bleiben unverändert.
6. Bei Logout, Kontowechsel, Löschantrag oder Session-Verlust sofort `deactivate()` aufrufen und laufende
   HTTP-Aufrufe abbrechen. Die Generation sperrt alte Antworten auch nach Wiederanmeldung und über
   mehrere Repository-Instanzen auf derselben DB hinweg.

Sitzungswechsel und `capture` sind nur außerhalb einer umgebenden `LocalData.transaction` erlaubt:
Ein späterer Rollback darf weder eine bereits ausgegebene Sitzung noch eine versendete Auswahl wiederbeleben.
Die Anwendung einer Antwort darf dagegen Teil einer größeren lokalen Transaktion sein, damit später auch
Kopf-/Spielerrevisionen atomar bestätigt werden können. Alle Zugriffe bleiben wie bisher seriell.

Schema 4 (`3.sqm`) ergänzt `cloud_device_owner` und `cloud_upload_epoch`. Bestehende Events, deren
Cloud-/Gerätebestätigungen und alle Cursor bleiben erhalten. Die Migration bindet keine Geräte automatisch.
Lokale Eigentümerzuordnung ersetzt weder Server-RLS noch einen Nachweis, dass die Host-App das richtige
Gerät bindet; diese Vertrauensentscheidung ist Teil der noch fehlenden Integration.

## B2b: noch umzusetzen

- Auth-Lebenszyklus einschließlich anstehender Kontolöschung mit der lokalen Sitzung verdrahten.
  HTTP-Adapter mit fest zugeordneter Sitzung, Abbruch bei Session-Wechsel und entsprechenden Race-Tests.
- P1-Übernahme: aktuelles eigenes Gerät, verifiziert gekoppelte eigene Geräte und Self-Aliase zuordnen.
  Bei Kontowechsel neue eigene Geräte-/Self-IDs für neue Daten; kein stilles Übertragen alter Daten.
- Match-Köpfe, Spieler und Projektionen mit Revisionen versehen: Bestätigungen alter Snapshots dürfen
  Änderungen während eines Uploads nicht als erledigt markieren. Kopf und Teilnehmer nur nach eigenen
  Ersteller-/Bearbeitungsrechten hochladen; Gerätezuordnung allein erteilt keine Match-Berechtigung.
- `CloudOutboxStore`-Adapter und `CloudBackend` implementieren; aktuelle Plan-Schnittstelle an die
  sitzungsgebundene Auswahl anpassen. Ein Ausschnitt von 500 Events darf noch nicht als letztes Batch
  eines Matches behandelt werden, solange weitere Events ausstehen. Projektion erst im letzten Batch.
- Abnahme: Server-Commit vor Netzabbruch, idempotenter Retry, mehr als 500 Events, Teilantworten,
  Kontowechsel während HTTP, parallele Änderungen, Director-Backup. Erst danach UI/Worker aktivieren.

B3/Pull einschließlich Redaktionen und Sichtbarkeitsentzug sowie B4/Worker folgen gemäß
[Ausführungsplan](p2-next-steps.md). Das Pilot-Gate P2-K bleibt bestehen.
