# D2/D3, automatischer Sync und Pilot – parallele Fortsetzung

Stand: 20.10.2026. Auftrag: nächste Projektschritte umsetzen und möglichst parallel
bearbeiten. Fachlich gilt [PLAN.md](PLAN.md); Umfang und Abnahmen bleiben im
[Cloud-/Pilot-Lieferplan](p2-cloud-pilot-lieferplan.md) definiert.

**Aktueller Freigabestand:** noch gesperrt. Server `3d1c761` ist vorläufig
übernommen. Review r6 bestätigt den N6-Fix, findet aber einen weiteren
Profilkonflikt nach bereits erfolgter globaler Player-Löschung (N7).
Der Server-Folgefix läuft mit echten Baseline-Reds.
Die Data-/Session-/Rematch-Maskenfixes sind mit Review r8 abgeschlossen.
Der neue D1-Schritt `c172535` erlaubt genaue Manifestbelege vor fehlenden
Rohdaten; seine Review findet noch eine Sequenzkollision zwischen reinen
Belegen. Drei zusätzliche Reprotests sind vorbereitet, noch nicht gelaufen.
Restore ist bis `e3e96e1` integriert. Die Folgestände `a563747` und `e04919a`
bleiben ungefreigegeben: Die jüngste Review findet Fehler im warmen
Auth-/Workout-Abbau und verspäteter Cloud-Antwortanwendung.
Der Restore-Folgefix besitzt den exklusiven Gradle-Slot.
D1-/V4-/Recovery-Aktivierung bleibt offen. Details und Task-IDs stehen am Ende.

## Ausgangspunkt und Wiederaufnahme

- Integrationsworktree: `p2-d2d3-completion`, Branch `p2/d2d3-completion`,
  Basis `ed5126b` (`origin/main`, PR #73).
- U1–U4, D1, K1, K2 einschließlich Android-Export und K3-Server sind integriert.
  D2/D3-Servergrundlage, ID-Zuweisungen und Wirevertrag sind mit #71–#73 integriert.
  Protokollversion und Mindestversion stehen weiterhin auf 3.
- Die vorherige Orchestrierung und ihre Data-/Server-Aufträge sind fehlgeschlagen,
  ohne aktive Läufe. Ihre uncommittierten Änderungen werden als ungeprüfte Arbeit
  in neue Worktrees übernommen. Die alten Worktrees bleiben unverändert.
- Data: Commit `85b1f0e` plus fünf uncommittierte Dateien aus `t3-8eab8734`.
  Die letzten Review-Fixes sind noch nicht unabhängig freigegeben.
- Server: neue Migration `20261018000700_redaction_write_guards.sql` und
  `220_redaction_write_guards.test.sql` aus `t3-75141199`; noch keine Freigabe.
- Der uncommittierte Ausführungsplan im alten Integrationsworktree enthält die
  spätere Übergabe und wird als Quelle erhalten. Historische Freigaben werden
  nicht auf diese uncommittierten Änderungen übertragen.

## Arbeitsverteilung

Höchstens drei zusätzliche Agenten neben dem Integrator. Je Implementierung ein
eigener, durch T3 gebundener Worktree. Gemeinsame Schnittstellen besitzt der
Integrator; andere Pakete ändern sie erst nach expliziter Übergabe. Keine
verschachtelte Delegation. Freie Plätze werden für unabhängige Reviews genutzt.

### Welle 1 – gleichzeitig begonnen

1. **Data-Grundlage und Review-Fixes.** Besitz: die 22 Data-Dateien aus `85b1f0e`,
   die fünf begonnenen Korrekturen und direkte Regressionstests.
   Insbesondere `ReplicaLifecycleStore`, `ReplicaLifecycle.sq`, Schema 8,
   `LocalData` und die betroffenen Repository-Sperren.
   Keine CloudRunner-, Server-, Session-, Host- oder Protokolländerungen.
2. **Server-Schreibschutz.** Besitz: neue Migration `20261018000700`, neue
   `220_*`-SQL- und Lockrenntests. Keine Änderungen alter Migrationen.
   CI-Anschluss bleibt beim Integrator.
3. **Lesende Geräte-/Session-Analyse.** Ermittelt die konkrete Trennung zwischen
   Replikatzustellung und Namensauflösung sowie fehlende Data-Schnittstellen.
   Keine Implementierung und kein zweites Review der Data-Arbeit.
4. **Integrator:** gemeinsamer Cloud-Vertrag, Rückfragen zwischen den Paketen,
   Abnahmematrix und spätere Zusammenführung.

Offene Data-Befunde, die vor Freigabe geschlossen sein müssen:

- Sichtbare Gäste aus einem fremden lokalen Scope sind ohne positiven
  Writer-/Quellennachweis nicht beschreibbar. Lesen bleibt möglich; verweigerte
  Schreibversuche verändern weder Namen noch Revisionen oder Acks.
- Redaktion erfasst unterstützte Legacy-Events mit leerem Typfeld.
- Cloud-Download vor dem ersten Lifecycle-Grant erzeugt keinen beschreibbaren
  UNKNOWN-Gast.
- Nachgewiesene Profilaliase werden redigiert; Namen sind kein Identitätsbeweis.

Server-Abnahme: Neue Events, Teilnehmer und Spieler dürfen entfernte Namen nicht
wiederherstellen. Verknüpfte Eigengäste werden vor dem Verlust ihrer Profilbeziehung
bereinigt. Ein Delete-/Push-Lockrennen beweist die atomare Prüfung. Unbetroffene
Legacy-Batches und bestehende Rechte bleiben kompatibel.

### Integrationspunkt A – Vertrag und geprüfte Grundlagen

Der Integrator führt freigegebene Data-/Server-Dateien zusammen, ergänzt die
notwendigen CI-Aufrufe und stellt eine committete gemeinsame Startbasis her.
Ein unabhängiger Review prüft den tatsächlichen Endstand samt vorherigen Befunden.

Gemeinsamer Vertrag:

- `G` ist die kontobezogene `visibilityEpoch`, nicht die Authgeneration oder
  die matchbezogene `accessEpoch`.
- `E` ist die Redaktionsepoche, `H` der für den Transfer festgehaltene
  Event-Wasserstand. IDs und Events besitzen unabhängige Cursor.
- `cloud_pull_match_redaction_v1` liefert den begrenzten G/E/H-Beleg.
  Epoche 0 verlangt Revalidierung, keinen künstlichen Redaktionstransfer.
- Upload-Batch: `lifecycle = {generation: G, redactionEpochs: {matchId: E}}`.
  Der Server prüft den Beleg nach Auth-/Binding-/Consent-/Lockprüfungen und vor
  jeder Mutation. Veraltet bedeutet `40001 restart_required`, ohne Teilwrites.
- Für lokale Erstuploads und reine Spielerbatches ist ausdrücklich festzulegen,
  welche vorhandenen beziehungsweise neuen Match-IDs einen E-Beleg benötigen.
  Ein fehlender Beleg darf weder Rechte erfinden noch gültige Erstuploads
  dauerhaft blockieren.
- Data benötigt einen engen Rohimport für fehlende Eventzeilen und die
  Revalidierung vorhandener Materialisierung. Bestehende Payloads ändern sich
  ausschließlich durch ein autorisiertes vollständiges Redaktionsmanifest.
  Normale Duplikat-/Konfliktprüfungen bleiben streng.
  Konkret: `storeDownloaded` verweigert derzeit bereits die für diesen Pfad
  erwarteten redigierten Duplikate; `applyAccess` entfernt bei neuer Generation
  den bisherigen MATCH-/Materialisierungsnachweis. Beide Übergänge brauchen
  einen expliziten Vertrag statt einer gelockerten allgemeinen Duplikatprüfung.
- Für Geräte fehlen noch öffentliche, kontogebundene Export-/Resume-Schnittstellen
  für Transfer-ID, H, Seitenindex und Redaktionsseiten. Die vorhandenen
  `pendingDeliveries` und Empfangsfunktionen allein genügen nicht.
  `beginRedaction` liefert bislang nur Cursor, nicht den dauerhaft nächsten
  Seitenindex oder die Abschlussflags.
  Der Rohimport muss den sicheren MEMBER-Bootstrap mit `ready=false` erlauben;
  normales `events.appendAll` ist dafür keine zulässige Umgehung der Lesesperre.
- Readiness braucht einen geprüften vollständigen Versionsvektor beziehungsweise
  ein Manifest. `Events.more=false` beweist keinen vollständigen Logbestand.
  Lokale Genesis und Herkunft müssen auch für Tombstones nachweisbar bleiben.
- Neue Kontextauswahl, auch A → B → A, invalidiert alte Arbeit. Globale
  Aufbewahrung für Konto B gewährt Konto A keinen Zugriff.

Besitz des Integrators: `CloudPullPort`, `Wire`, Redaktionsmapping,
`SupabaseCloudBackend` und nötige gemeinsame Data-Schnittstellen. Änderungen
werden vor der folgenden Welle committet; Worktrees übernehmen keine
uncommittierten Elternänderungen.

### Welle 2 – Upload und Download parallel

- **D1 Lifecycle:** `CloudPullRunner`, `CloudPullStore`, die jeweiligen
  `CloudPullState`-Dateien, `CloudPull.sq` und direkte Tests.
  Zugriff vor Materialisierung anwenden. Fehlende Matches erst nach komplettem
  Katalogabgleich auf ABSENT setzen. Vollständiges Manifest dauerhaft übertragen
  und Ready erst nach Revalidierung setzen.
  Abnahme: Neustart, Entzug/Wiederfreigabe, mehr als 200 Matches und 500 Events,
  Seitenabbruch, geänderte Epochen und verspätete, nicht kooperative Antworten.
- **U4 Lifecycle:** `CloudUploadStore`, `CloudUpload.sq`, Auswahlabfragen in
  `CloudMetadata.sq`, `CloudUploadRunner` und direkte Tests.
  Snapshot an Kontext und G/E binden; Filter vor SQL-LIMIT anwenden.
  Vor Versand und Ack erneut prüfen, globale redigierte Identitäten beachten.
  U3-Revisionsbestätigungen bleiben erhalten; blockierte Arbeit ist nicht DRAINED.
  Abnahme: Änderung zwischen Auswahl/Versand/Ack, Retry nach Server-Commit,
  reine Spielerbatches, Erstupload, Kontowechsel und Entzug während HTTP.
- **Freier Agentenplatz:** unabhängige Reviews abgeschlossener Pakete oder
  begrenzte Vorbereitung der Pilot-Abnahmematrix. Keine vorgezogene Änderung
  derselben Adapter-/Data-Dateien.

### Integrationspunkt B – vollständiger Cloud-Vertrag

Beide Pakete werden gegen dieselbe geprüfte Basis zusammengeführt.
Echte Supabase-Stackprüfungen müssen Auth, Gateway und SQL gemeinsam erfassen.
Lokale PostgreSQL-Tests ersetzen weder diesen Nachweis noch Staging-Abnahmen.
Protokoll 4 und Hintergrundbetrieb bleiben bis zur Geräteintegration inaktiv.

### Welle 3 – Gerätepfad und Darstellung

- **Session/Zustellung:** dauerhafte Access- und Redaktionszustellung,
  getrennte Acks, Wiederaufnahme nach Reconnect, Behandlung alter Pakete und
  bestätigter früherer Eventzustellungen. Besitz `shared/match-session/**`,
  nötige Protokollimplementierung und direkte Tests; Versionskonstanten bleiben
  beim Integrator. `MatchSession.kt` hat genau einen Besitzer.
  Änderungen aus Data müssen geöffnete Sessions und Engine-Caches invalidieren;
  eine SQL-Lesesperre allein entfernt keinen bereits sichtbaren Stand.
  Access/Redaktion besitzen ausschließlich vom bestätigten Phone kommende
  Autorität. Acks von Uhren bestätigen Zustellung, keine neuen Rechte.
  Stamps werden vor jeder Datenmutation und jedem Ack geprüft, einschließlich
  Summary, Meta und Tombstones. Cloud-Matches dürfen nicht über einen lokalen
  Stamp legitimiert werden. Die 64-KiB-Grenze gilt inklusive Envelope.
- **Namensauflösung/Präsentation:** ausschließlich nach Übergabe der konkreten
  Schnittstellen; Spieler-ID statt historischer Namen auflösen, redigierte Namen
  aus Verlauf, Vorschlägen und Rematch fernhalten. Präsentation und Facades
  werden getrennt vom Session-Kern zugewiesen. Besitz `shared/presentation/**`,
  `shared/umbrella-watch/**`, `wearApp/**` und direkte Tests.
  Schwerpunkt `SessionMatchStore`, `WatchScoring`, `WatchDirectory`,
  `WearSessions` und Glance-/Tile-Caches. Kein Data-/Session-Edit.
  Vertrag: Lifecycle-/Directory-Revision invalidiert alle abgeleiteten
  Match-/Spielernamen, Lifecycle-Rückstand fließt in den Syncstatus ein.
- **Integrator:** Host-Anschlüsse und abschließende gemeinsame Aktivierung,
  Versionskonstanten und Update-Hinweise. Hier keine konkurrierenden Edits.
  Android braucht neben dem vorhandenen Uploadhost auch den Pull-Anschluss.
  `WearSessions.isSyncSettled` muss Lifecycle-Rückstände einbeziehen;
  `assignPlayer` darf erst koordiniert mit V4 namenfreie Referenzen erzeugen.

Die lesende Analyse aus Welle 1 hat diese Modul- und Dateigrenzen bestätigt.
Die abschließende Liste der tatsächlich betroffenen Dateien und der
Session-Revisions-/Pending-Vertrag werden vor Dispatch festgeschrieben.

Abnahme vor V4-Aktivierung: offline Uhr während Löschung, Neustart/Reconnect,
keine Namenwiederkehr, kein Wiederhochladen alter Payloads, Entzug/Wiederfreigabe,
eigene ungesicherte Daten erhalten, Konto A/B getrennt, unabhängige Zustell-Acks.
3↔4 muss einen verständlichen Updatezustand statt stiller Teilsynchronisierung
ergeben. Kontextloses P1-Verhalten bleibt gemäß dem bestehenden Aktivierungsvertrag
erhalten; nach Aktivierung kein Rückfall auf frühere Rechte.

Session-Testbasis: `AccountOwnershipSessionTest`, `InMemoryLinkNetwork`,
persistente Data-Instanzen, `ManualScheduler` und aufgezeichnete Pakete.
Mehrseitige Redaktion mit verlorenem Seiten-/Abschluss-Ack sowie verspätete
Envelopes nach A → B → A gehören ausdrücklich dazu. Auf dem Phone besteht der
Fence aus Auth-Lease und Data-Kontext, auf der Watch zusätzlich aus gültigem
IdentityEnvelope und Pairing-Revision. Leere Fences sind keine Integration.

### Welle 4 – automatischer Sync und Pilotvorbereitung

Nach erfolgreichem Cloud-/Gerätevertrag können getrennte Pakete parallel laufen:

- **B4 Android:** begrenzte Upload-/Pull-Läufe koordinieren, App-/Netz-Auslöser
  und Hintergrundplanung, Backoff, Wiederaufnahme und sichtbaren Sicherungsstatus
  anbinden. Logout, Kontowechsel und Löschantrag stoppen alte Arbeit sofort.
- **K4/K5 Kontosupport:** vorhandenen Gerätewiderruf durchgängig abnehmen und
  begrenzte Kontoaktionen im Admin ergänzen. Änderungen nur mit serverseitiger
  Rolle, MFA, Begründung und Audit. Genaue Endpunkte vor Dispatch abgleichen.
- **K7 Pilotnachweise:** Testfälle und tatsächlich ausführbare Staging-/Geräte-
  Prüfungen vorbereiten; Export-Edge-Function, Auth-Negativtests, Provider,
  Supportantworten, Widerruf und Löschjob separat nachweisen.

K6 Betriebsansicht folgt erst auf belastbare B4-Betriebsdaten. Apple-Konto-Host,
Export und BGTask bilden ein separates Plattformpaket mit eigener macOS-CI und
Geräteabnahme; Android-Tests belegen keine iOS-Parität.

## Gemeinsame Prüfungen und Freigaben

### Verbindliche Identitätssicherheit bei Wiederherstellung

Ergänzter Nutzerauftrag vom 20.10.2026: Namensgleichheit, Gerätewechsel und
Kontowiederherstellung dürfen niemals fremde Daten zuordnen oder freigeben.

- Ausschließlich bestätigte Konto-UID, unveränderliche Quelleneigentümer und
  geprüfte Gerätebindung begründen Zugriff. Alias, Anzeigename, Gerätename oder
  eine gleiche E-Mail-Anzeige sind kein Eigentumsnachweis.
- Gleiche bestätigte UID mit neuer Sitzung darf den eigenen Bestand wiederfinden;
  eine neue UID mit gleichem Namen erbt weder Daten noch Quellen.
- A → B → A, neu gestartete Geräte und verspätete Login-/Cloud-/Peer-Antworten
  dürfen keine frühere Berechtigung wiederverwenden.
- Alte ungebundene Quellen bleiben ohne positiven Nachweis und ausdrückliche
  zulässige Übernahme ungebunden. Bereits an A gebundene Quellen werden nicht B
  zugeordnet. Gemeinsame Matches bleiben nur im tatsächlich erlaubten Umfang sichtbar.

**Zusätzlicher Freigabeblocker aus der Host-Prüfung:** Android-/Wear-Backupregeln
schließen derzeit die Datenbank nicht aus. `advantly.db` enthält Geräteidentität,
Account-Scope und Pairing-Autorität; die Phone-Session startet vor dem Auth-Host.
Das ist kein nachgewiesener serverseitiger Fremdzugriff: Der Server verweigert
eine neue Installation mit bereits gebundener Geräte-ID. Lokal und im Peer-Pfad
fehlt aber die Trennung von normalem Offline-Neustart und übertragenem DB-Abbild.

Eigenes Recovery-Paket vor Host-/V4-Freigabe:

1. Installationsnachweis und Wiederherstellungsprüfung vor jedem Session-/Link-
   und privaten Anzeigezugriff, einschließlich Hintergrundservices.
2. Übertragenen Bestand erhalten und bis zur bestätigten Identität sperren;
   keine übernommene aktive Geräte-ID oder Pairing-Autorität weiterverwenden.
3. Neue aktive Gerätequelle; Freigabe alter accountgebundener Daten nur für die
   passende verifizierte UID. Neue Uhr darf keine Sequenzidentität der alten klonen.
4. Ausdrücklicher Wiederkopplungsweg, der alte Authority/Nonces invalidiert,
   ohne historische Quellen oder Konten umzuhängen.
5. Einführung bei Altinstallationen gesondert behandeln: Das Fehlen eines neuen
   nicht gesicherten Markers unterscheidet allein nicht zwischen älterer echter
   Installation und Restore. Kein pauschales Löschen, keine Alias-Heuristik.
6. Erste Cloud-Gerätebindung nach längerer Login-Pause sicher behandeln; die
   bestehende Fünf-Minuten-Grenze darf nicht durch heimliche Identitätsrotation
   umgangen werden.

Besitz: separater Android-/Wear-Host-Owner nach abgestimmtem Data-/Session-Vertrag.
Auth-/Pairing-Anschlüsse mit den jeweiligen Besitzern nacheinander integrieren.
Apple-Restore bleibt separat zu prüfen. Neue Daten-Regressionen auf Root prüfen
bereits die UID-/Quellentrennung nach simuliertem geprüftem Login; sie ersetzen
keinen Test einer vom Betriebssystem übertragenen Datenbank vor dem Login.

### Nachweise je Lieferung

- Fachtests je Paket; `gradlew.bat check` vor jedem Commit.
  Kostspielige Gesamtbuilds werden abgestimmt, um Konkurrenzlast zu vermeiden.
- Integrationsstand: gemeinsamer Check plus Phone-/Wear-Debug-APKs,
  SQL-/Stacktests für den tatsächlichen Diff und unabhängiger Review.
- Vor Merge grüne relevante CI einschließlich Apple-Pfaden, soweit betroffen.
  Jede neue Reviewrunde bekommt Originalauftrag, Befunde, Korrekturen und
  verbliebene Einwände. Nur tatsächlich beobachtete Rot-/Grünläufe dokumentieren.
- Keine neue Pilotfreigabe aus einem lokalen Testergebnis. Gehostete Jobs,
  Exportbereitstellung, echte Konten/Geräte und Datenschutz-/Store-Unterlagen
  haben eigene Nachweise. Fehlende Zugänge oder menschliche Abnahmen bleiben
  ausdrücklich offen; keine automatische Produktionsfreigabe.
- P1-Feldtestmatrix einschließlich kleiner Galaxy Watch weiterführen.
  Ein leerer Protokollbogen ist keine bestandene Abnahme.

## Danach

Freunde und Push können nach stabiler Konto-/Sync-Basis in getrennten Paketen
vorbereitet werden; geteilte Matches und Claim bauen darauf auf. Danach folgen
accountübergreifende Zählerübergabe, Visitor, eigenständige Wear-Sitzungen und
Turnierübersicht. P3 beginnt erst nach P2-Gate, öffentliche Produktion erst nach
P3. Diese Folgephasen werden nicht vorzeitig als erledigt oder freigegeben geführt.

## Gestartete Aufträge

T3-Aufträge vom 20.10.2026; Status beim Start: laufend, noch kein Ergebnis:

- Data:
  `node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-data-recovery-r1`
- Server:
  `node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-server-recovery-r1`
- Lesende Session-Analyse: `/root/session_integration_plan`.

Der Integrator übernimmt Ergebnisse und aktualisiert den Stand nach tatsächlicher
Prüfung. Die bloße Rückkehr eines Agenten ist keine fachliche Freigabe.

Data ist lokal als `aaa15cb1833324fd080a2374b90b632c80db0250` auf `ed5126b`
committet und im Integrationsworktree ohne neuen Commit übernommen. Noch nicht
auf `origin/main`, kein PR. Alle 22 wiederhergestellten Dateiinhalte stimmen
abgesehen von CRLF/LF mit dem alten vollständigen Snapshot überein.

Alle vier genannten Review-Ursachen wurden mit den
wiederhergestellten Regressionstests gegen die Produktionsdateien von exakt
`85b1f0e` rot reproduziert. Log und XML sind laut Worker im temporären
Testverzeichnis erhalten. Anschließend wurden alle fünf Fixdateien
wiederhergestellt. Erzwungener Data-JVM-Lauf: 166 Tests, keine Fehler oder Skips;
der Integrator hat die XML-Ergebnisse selbst geprüft. Worker-Gesamtcheck:
704 Tasks grün, einschließlich Migration und Lint. Noch keine Endfreigabe.
Der unabhängige Claude-Review auf exakt `aaa15cb` läuft als
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-data-independent-review-r1`.
Er umfasst ausdrücklich den ergänzten Identitätsschutz bei gleichen Namen und
Wiederherstellung. Die zusätzlichen Root-Regressionen sind ein separater Diff.

### Review und zusätzliche Absicherung

Der unabhängige Review von `aaa15cb` endet mit **fix-first**:

- Ereignisbasierte Spieleranlage kann einen späteren eigenen Cloud-Spieler
  dauerhaft ohne Writer halten. Directory-Anlage aus Zuweisungspayloads wird
  entfernt; echte Legacy-Anzeige bleibt als Fallback erhalten.
- Ein redigierter Gast, der zu einem lebenden Profil verknüpft ist, beweist keine
  Profillöschung. Nur positiv belegte Self-Profilidentität darf globale
  Profilaliase redigieren.
- Ein Batch mit eigener Genesis und anschließendem Fremdquellen-Event muss
  vollständig zurückrollen. Der Worker hat diesen Durchlass zusätzlich reproduziert.
- Globale autorisierte Redaktionsmanifeste bleiben absichtlich global; eine
  Beschränkung auf die aktuelle Besetzung würde historische Löschung verhindern.
  Vertrauenswürdiger Ursprung, G/E und Fence sind Integrationspflicht.
- Watch-Bootstrap und Restore-Erkennung bleiben die bereits benannten
  Integrations-/Host-Blocker, keine stillschweigend gelösten Data-Funktionen.

Korrekturen laufen im bestehenden Data-Worktree als
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Athread-send%3Aadvantly-20261020-data-review-fix-r1`.
Danach neue unabhängige Prüfung des tatsächlichen Fixheads.

Die drei zusätzlichen Root-Tests in `ReplicaIdentityRecoveryTest` bestehen,
einschließlich ktlint: gleiche Namen bei verschiedenen UIDs, Ersatzgerät mit
gleicher beziehungsweise anderer UID und alte Antworten nach Reopen/Rückkehr.
Sie simulieren **bereits geprüften Login** und sind kein Host-Restore-Nachweis.

Der Serverlauf wurde durch Modellkapazität unterbrochen. Seine drei aktuellen
Dateien bleiben erhalten; Fortsetzung in neuem Worktree:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-server-recovery-r2`.
Neue UUID-Schreibweisen und verlorene Teilnehmer-/Slot-Referenzen werden zusätzlich
geprüft. Historische Assertion `080` Nr. 30 erwartet noch einen erhaltenen
Gastnamen nach Profillöschung; der Integrator muss diese Erwartung mit dem
beabsichtigten Redaktionsvertrag abgleichen. Fehlende lokale Cron-/Storage-/
Netzwerkdienste werden nicht als bestandener Supabase-Stack geführt.

Das Android-/Wear-Schutzpaket läuft parallel:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-restore-guard-r1`.
Besitz: Android-DB-Guard, produktiver Android-Sessionstart, minimale Host-
Eingangssperren, getrennte Wear-Caches und ehrlicher DE/EN-Recovery-Hinweis.
Keine commonMain-/SQL-/Protokolländerungen. Installationsbeleg muss an die
aktive Datenbankidentität gebunden sein; Sidecar und Dateiname allein reichen
nicht. Recovery hält den Peer-Transport vollständig an, ohne Queue-Acks.
Vollständige Same-UID-/anonyme Wiederherstellung bleibt von den geprüften
Import-/Revalidierungs- und Wiederkopplungsschnittstellen abhängig.

### Integrationsstand nach den Korrekturläufen

Data-Fix `70e7e3e193b21cdcdf2d91565ce147c83df34888` ist auf `aaa15cb`
committet und im Root-Worktree ohne zusätzlichen Commit übernommen. Zehn
Dateien beheben die beiden Reviewprobes und den gemischten Genesis-Batch.
174 Data-Tests ohne Fehler oder Skips, Gesamtcheck mit 700 Tasks grün.
Neue unabhängige Nachprüfung:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-data-independent-review-r2`.

Server-Fix `b5f30f44f9af4aa389f89fe87747eaf284d0dfb9` ist ebenfalls lokal
übernommen: ausschließlich additive Migration 00700 und die beiden neuen
220-Testdateien. Worker-Nachweise: 100 SQL-Assertions, 14 nachgewiesene
Lock-Rennen, sechs Isolation-Verweigerungen; zusätzlich bestehende 210-Rennen
achtmal grün. Gesamtcheck mit 704 Tasks grün. Neue unabhängige Prüfung:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-server-independent-review-r1`.

Der Integrator hat die veraltete Erwartung in `080` Nr. 30 auf die beabsichtigte
Anonymisierung vor dem Unlink geändert. Alle 37 Assertions dieser Datei laufen
gegen den integrierten Stand auf einer eigenen temporären PostgreSQL-Instanz
grün. Log: `%TEMP%/advantly-root-sql-integration-20261020/080.log`.
Der vollständige Worker-SQL-Lauf hatte zuvor 1589 von 1595 Assertions bestanden:
neben dieser Erwartung fehlen fünf lokale Plattformprüfungen für Cron, Storage
und pg_net. Die zusätzlichen Race-Läufe 181/190/200 waren abgebrochen und sind
ausdrücklich nicht bestanden. Echter Supabase-Stack und CI bleiben erforderlich.

Der endgültige Schreibvertrag fordert bei mitgeliefertem `lifecycle` aktuelles
G und aktuelles E für jedes beim Preflight bereits vorhandene referenzierte
Match, auch E=0. Neue Matches benötigen noch keinen E-Eintrag. Player-only
benötigt aktuelles G und eine leere E-Map. G ist **nicht** die lokale
Auth-Generation. Eigene Writes können G nach dem Preflight erhöhen; die
Push-Antwort liefert keinen neuen G-Wert.

Root ergänzt derzeit Wire-DTOs und den begrenzten Redaktions-RPC:
Antwortbindung an Match/G/E/H, geordnete getrennte Cursor, Seitenlimits sowie
Auth-/Consent-/Binding-Prüfung vor und nach dem Abruf. Diese Änderungen sind
noch nicht gebaut und aktivieren weder Lifecycle noch V4. Der Gradle-Slot
liegt nach Data beim Restore-Worker; danach folgen die Root-Vertragstests.
Die Data-Lücken für Rohimport, Revalidierung und persistenten Resume/Export
bleiben vor produktiver Verdrahtung zu schließen.

### Zweite Reviewbefunde und laufende Korrekturen

Der Server-Review von `b5f30f44` endet mit **fix-first**. Eigene PostgreSQL-Probes
bestätigen zwei P1-Fälle: selbst gewählte Self-/Slot-UUIDs können andere lebende
Profile oder Spieler global als gelöscht markieren; ein historischer Gastlink
kann nach neuer Zuweisung das inzwischen andere lebende Profil anonymisieren.
Auch der Backfill globalisiert mehrdeutige Altbelege. Korrektur:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Athread-send%3Aadvantly-20261020-server-poison-fix-r1`.

Verbindlicher präzisierter Vertrag: gelöschte Profilidentitäten, belegte
Spieleridentitäten und matchlokale Plätze sind verschiedene Beweisarten.
`redactedPlayerIds` wirkt im Client global und darf deshalb ausschließlich
positiv belegte globale Spieleridentitäten enthalten. Keine Übernahme beliebiger
Slot- oder Profil-UUIDs. Historische Ereignisse dürfen gezielt pro Match
anonymisiert werden, ohne den heute lebenden Spieler global zu sperren.
Mehrdeutige Altbelege werden nicht durch Namen oder UUID-Gleichheit legitimiert.
Die passenden alten `210`-Erwartungen, die Profil-UUIDs im Spieler-Manifest
verlangen, muss Root nach der tatsächlichen Serverkorrektur aktualisieren.
E-Inkremente, Ereignisanonymisierung und Zugriffsschranken bleiben erforderlich.

**Neue Data-Integrationspflicht:** `applyRedactedEvent` verlangt bisher eine
globale Spielersperre. Der präzisierte Vertrag benötigt zusätzlich autoritative,
matchlokale Ereignisredaktion ohne globale Spieler-ID. Rohimport und spätere
Duplikate müssen diese belegte Ereignisredaktion dauerhaft respektieren.
Kein gemischter Client-/Serververtrag darf aktiviert werden.

Data-Review `70e7e3e`: vorherige Befunde 1, 2 und 4 behoben; globale Manifestwirkung
als Vertrauensgrenze akzeptiert. Vor Aktivierung offen: ungefilterte Cloud-
Duplikate nach lokaler Redaktion sowie ereignisbasierte Directory-Anlage in
`MatchSession.reconcilePeople`/`onMatchMeta`. Zusätzlich wurde ein bereits
bestehender Fehler gefunden: später materialisierte Spieler machen
PARTICIPANTS-Metadaten scheinbar lokal ausstehend und blockieren weitere
Cloud-Kopfaktualisierungen. Enger Data-Fixauftrag:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Athread-send%3Aadvantly-20261020-data-import-fix-r2`.
Vorher bestehende lokale Änderungen dürfen dabei niemals bestätigt werden.

Root-Nachweis auf dem integrierten Stand: **177 Data-Tests, null Fehler/Skips**.
Cloud: **190 Tests, null Fehler, ein expliziter Real-Stack-Test übersprungen**,
ktlint grün. Der erste Cloud-Lauf scheiterte am Rückgabetyp einer neuen JUnit-
Testmethode; nach `runBlocking<Unit>` wurden die vier neuen Vertragstests
tatsächlich ausgeführt. Logs unter `%TEMP%/advantly-root-cloud-data-integration*.log`.
Die neuen RPCs sind weiterhin noch nicht produktiv verdrahtet.

Restore-Schutz liegt als `fcda92cef6d20ee761783070e63cc553625751b5` vor:
21 Dateien, 15 neue Tests, voller Check grün. Die unabhängige Prüfung läuft als
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-restore-independent-review-r1`.
Kein automatisches Aufheben des Holds durch Login. Echte Play-Services-
DataEventBuffer-/Queue-Delete-Callbacks waren wegen eines Robolectric-
Bytecodefehlers nicht ausführbar; direkte dauerhafte Queue-Prüfungen und
Service-Kaltstarts ersetzen keinen Hardware-Backup-/Restore-Nachweis.

Restore-Review `fcda92c`: **fix-first**. Der Reviewer bestätigt die Reihenfolge
vor Produkt-Open, den Transport-Hold und die Cache-Trennung. Ein temporärer
Kopier-/SQLite-Fehler wird jedoch als Token-Mismatch behandelt und kann den
Nachweis einer gesunden Installation dauerhaft überschreiben. Zudem führen
Erststartabbrüche zu einem dauerhaften Hold; verwaiste Prüfkopien werden nicht
bereinigt. Root hat zusätzlich dieselbe falsche Fehlerklassifikation beim
Lesen kleiner Marker-/Proof-Dateien festgestellt.

Fixauftrag:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Athread-send%3Aadvantly-20261020-restore-review-fix-r1`.
I/O-/Open-Fehler müssen für diesen Start sperren und unverändert weitergeworfen
werden; kein neuer Marker und keine neue DB. Eigene neue Familien benötigen
eine vor Dateierzeugung dauerhaft gespeicherte noBackup-Reservierung, damit ein
frischer Erststart nach Abbruch normal fortgesetzt werden kann. Bereits
vorhandene fremde oder alte Familien müssen auch nach einem solchen Abbruch
im Hold bleiben. Nur private, sicher eingegrenzte Prüfkopien dürfen unter dem
Lock bereinigt werden. Die Vollkopie bei jedem Prozessstart bleibt als
Performance-/Speichergrenze dokumentiert; sie ist keine Hardware-Abnahme.

Data-Importfix `a01194ef484919d0ce4f80c79b45555d59e4d9e3` auf `70e7e3e`
ist lokal übernommen. Fünf Dateien bereinigen eingehende Events vor Insert
und Duplikatvergleich und bestätigen ausschließlich neu entstandene
Download-Revisionen zuvor sauberer, eindeutig zugeordneter Teilnehmer.
Worker-Nachweis: drei echte Fehler auf unverändertem `70e7e3e`, danach acht
zusätzliche Regressionen und 182 Data-Tests grün; voller Check mit 700 Tasks
grün. Neue unabhängige Reviewrunde:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-data-independent-review-r3`.
Der Root-Nachweis von 177 Data-Tests weiter oben betrifft den Stand vor diesem
Importfix; die gemeinsame erneute Prüfung steht noch aus. Der Gradle-Slot
liegt jetzt beim Restore-Fix für Baseline-Reds, Korrektur und Gesamtcheck.

Root ergänzt uncommittet `redactionProgress` (Transfer, Cursor, nächster
Seitenindex und persistierte Abschlussflags) sowie `exportRedactionPage`.
Export ist nur für abgeschlossene Transfers im aktuellen Konto-/G-Kontext
zulässig und liest eine vorhandene begrenzte Seite. Zwei neue Tests prüfen
Reopen, getrennte Cursor, unvollständigen Export und Konto-/Generationswechsel.
Noch nicht ausgeführt; keine Aktivierung. Die Build-Reihenfolge lautet jetzt
Restore, Server-Fixcheck, Root-Integration.

Die unabhängige Reviewrunde zu `a01194ef` gibt den engen Importfix als
**nicht aktivierte Foundation frei**, ohne P1/P2. Die Prüfung war statisch,
kein eigener Gradle-Lauf des Reviewers. Zwei P3-Randfälle werden vor weiterer
Verdrahtung gezielt geprüft: Writerwechsel trotz ausstehender Spieleränderung
kann zuvor saubere Cloud-Teilnehmer blockieren; eigene schreibbare
Cloud-Teilnehmer dürfen bei einem Rechtewechsel nicht versehentlich bestätigt
werden. Enger Folgeauftrag:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Athread-send%3Aadvantly-20261020-data-authority-edge-fix-r3`.
Der Worker darf die neuen Root-Resume-/Export-Dateien nicht ändern.

Server-Fix `ccc3badd492e60e886153d4e8beb2957cd366980` ist lokal übernommen.
Die sechs vereinbarten `210`-Erwartungen wurden auf belegte Player-IDs statt
Profil-/Slot-UUIDs umgestellt. Root hat alle 37 SQL-Dateien gegen eine eigene
temporäre PostgreSQL-Instanz geprüft: `080` 37/37, `210` 191/191 und `220`
129/129 grün. Ausschließlich die fünf fehlenden Cron-/Storage-/pg_net-Prüfungen
schlagen weiterhin fehl; der SQL-Lauf ist deshalb insgesamt nicht grün.
Log: `%TEMP%/advantly-root-sql-integration-20261020/full-final.log`.
Neue unabhängige Server-Nachprüfung:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-server-independent-review-r2`.

Restore-Fix `646407707109d6930bed76c495033088bcaba19d` auf `fcda92c`
ist noch nicht übernommen. Worker: sechs echte Baseline-Fehler, danach
29 Schutztests und voller Check grün. Die Nachprüfung des gesamten Slices läuft:
`node:delegated-task:command%3Amcp%3Ac0261c59-b238-409e-b1c7-f1792b854f6b%3Adelegate-task%3Aadvantly-20261020-restore-independent-review-r2`.
Root führt jetzt den gemeinsamen Check einschließlich Importfix und
Resume-/Export-Ergänzung aus; danach erhält Data den Slot für seine P3-Repros.

## Nachweise und offene Korrekturen nach der zweiten Nachprüfung

Root hat die geprüfte Data-Grundlage einschließlich Resume/Export und
Identitätswechseltests in `fb043a7` sowie die Cloud-Redaktions-RPC-Grenze in
`ce484c1` lokal committed. Der gemeinsame Check war mit `--no-build-cache`
grün: 722 Tasks, davon 690 ausgeführt; Data 187 Tests ohne Fehler, Cloud
190 Tests ohne Fehler mit einem übersprungenen echten Stacktest.
Log: `%TEMP%/advantly-root-integrated-check.log`. Kein Push, keine Aktivierung.

Data-P3-Fix `1d40d24259d6035ebb2ceb48e29dd047ca23df51` ist im Root
übernommen, noch nicht dort committed. Reine Writerwechsel bestätigen
ausschließlich die exakt neu entstandene Revision zuvor sauberer, eindeutig
materialisierter, schreibgeschützter Teilnehmer. Ausstehende Player-Payloads
und schreibbare Teilnehmer bleiben ausstehend. Worker: sieben echte Reds
gegen `a01194ef`, anschließend 23 gezielte / 189 Data-Tests und voller Check
grün. Die frische statische Review
`advantly-20261020-data-independent-review-r4` bestätigt die Korrektur sowie
Root-Resume/Export und Cloud-Fences ohne P1/P2.

Beim späteren Verdrahten muss eine Cloud-Seite mit bis zu 500 Events in
mehrere begrenzte Data-/Wire-Seiten aufgeteilt werden. Die 64-KiB-Grenze
einschließlich Wirehülle darf nicht angehoben werden. Abgelöste Exporttransfers
werden gezielt neu gestartet; ein alter Transfer darf keine Freigabe erhalten.
Die Konfliktentscheidung für ausstehende Namen nach Grant/Revoke bleibt vor
Aktivierung zu konkretisieren.

Restore `6464077` bleibt außerhalb des Root: Der Reviewer fand eine
inkonsistente lexikalische/kanonische Pfadprüfung sowie den automatischen
Android-Korruptionshandler. Der Worker reproduzierte drei echte Reds
einschließlich Elternalias, Checkdir-Junction und real beschädigter
SQLite-Seite. Folgeauftrag:
`advantly-20261020-restore-symlink-fix-r2`.
Ein dauerhafter I/O-Fehler braucht später eine sichere DE/EN-Fehleranzeige
ohne Aufforderung zur Datenlöschung; er darf weder Transport freigeben noch
vorhandene Familien ersetzen.

Server `ccc3badd` bleibt ungefreigegeben: Eine fremde lebende Player-Zeile
mit derselben UUID darf einen bereits belegten gelöschten Profilbezug nicht
entkräften. Gleichzeitig darf Profilredaktion keine fremde lebende
Player-Identität global stilllegen. Die neuen S1/S3/S4-Probes werden gegen
den unveränderten Commit reproduziert und mit matchlokalen, typisierten
Belegen korrigiert. Folgeauftrag:
`advantly-20261020-server-profile-collision-fix-r2`.
Für bereits global stillgelegte zugewiesene Gäste ist eine spätere
Zuweisung/Claim ausdrücklich abzulehnen, statt Erfolg mit anschließend
verlorener Verknüpfung zu melden. Die bewusste Anonymisierung von Kim in
080 bleibt erhalten; die ältere A11-Beschreibung ist vor Freigabe anzupassen.

Root hat die zuvor abgebrochenen Concurrency-Skripte 181 und 190 erneut
ausgeführt: beide Skripte Exit 0, darunter 82 echte Consent-Lock-Rennen.
Der synchrone Node-Unterprozess blockierte den Eventloop des lokalen
PostgreSQL-Harness; mit asynchronem Unterprozess sind dessen Ausgabekanäle
weiter bedienbar. SQL-Zeitbudgets wurden nicht verändert.
Log: `%TEMP%/advantly-root-sql-integration-20261020/regression-races-async.log`.
Dies ersetzt keine echte Supabase-Abnahme.

Root bereitet zwei Regressionen für Eventredaktion ohne globale Player-ID
vor: dauerhafte eventbezogene Autorität nach Reopen/Purge, unveränderte
lebende Directory-Identität und fremde Matches sowie vollständiger Rollback
beim finalen Autoritätsverlust. Bisher nur Teständerungen, kein
Produktionsfix und kein Lauf. Build-Reihenfolge: Restore, Server, Root.

### Lokal integrierter Stand nach gemeinsamem Check

- `8c2286a`: Data-P3 und eventbezogene Redaktion. Die zwei neuen
  Baseline-Tests scheiterten tatsächlich am alten globalen ID-Erfordernis.
  Schema 9 bewahrt einen unveränderlichen bereinigten Eventbeleg über Purge
  und Reimport; die aktuelle Zuweisungsquelle liegt in der Projektion.
  Die erste Umsetzung verursachte Log-Replay in Teilnehmer-Lesepfaden; drei
  vorhandene Performance-Tests fingen dies ab. Die persistierte Quelle
  beseitigt das, ohne deren Anforderungen zu ändern.
- `1d91685`: vollständiger Restore-Schutz bis Worker `b28a6c1`.
  Die dritte unabhängige Review fand keinen P1 und keinen neuen P2 im
  Schutzpfad. Der automatische Guard ist lokal eingebaut, der Build darf
  wegen fehlender Adoption-/Freigabehaken noch nicht ausgerollt werden.
- Gemeinsamer `check --no-build-cache` grün: 1m57s, 704 Tasks
  (158 ausgeführt, 546 aktuell). Data 199, Core 170, Presentation 177,
  Phone 65 und Wear 321 Tests ohne Fehler oder Skips. Cloud 190 Tests,
  davon weiterhin ein echter Stacktest übersprungen.
  Log: `%TEMP%/advantly-root-event-restore-check.log`.
  Redaktion: `%TEMP%/advantly-root-event-only-{red,green}.xml`.
- Server `d661014ec4d2174c4573d991ca9648920a365c1d` ist im Root
  uncommittiert übernommen. Er behebt die neuen Profil-/Player-Kollisionen
  über typisierte Matchkontexte und lehnt Zuweisung/Claim stillgelegter
  Player ausdrücklich ab. Root hat die gesamten SQL-Tests erneut
  ausgeführt: `220` 152/152, `210` 191/191, `080` 37/37 grün.
  Nur die fünf fehlenden Plattformprüfungen bleiben rot.
  Log: `%TEMP%/advantly-root-sql-integration-20261020/full-r2-integrated.log`.
- Root hat auch `200` SQL-Concurrency mit Exit 0 nachgeholt.
  Der optionale Archiv-Rundlauf war nicht enthalten; Deno 2.9.7 fehlt
  lokal und konnte nicht über npm bezogen werden. Die Versionsbindung
  wurde nicht verändert. Der neue `220`-Aufruf ist im vorhandenen
  Supabase-CI-Schritt ergänzt; YAML wurde geparst. Kein CI-Erfolg behauptet.

Review-Ergebnisse und laufende Korrekturen:

- Server-Review r3 bestätigte die früheren Fixes und 152 SQL-Assertions
  sowie die 220-/210-Rennen. Neue echte Gegenbeispiele bleiben:
  Ein fremdes Konto kann eine nach Harddelete freie Gast-ID wiederverwenden
  und beim eigenen Löschen historische fremde Matches global redigieren.
  Außerdem darf ein inzwischen Vera zugewiesener Gast beim Löschen Veras
  keine positiv Bob zugeordneten historischen Events global anonymisieren.
  Folgeauftrag `advantly-20261020-server-identity-reuse-fix-r3`:
  Nichtwiederverwendung aus tatsächlichem Player-DELETE-Beleg, getrennt von
  Redaktionsautorität; bei widersprechendem lebendem Matchkontext nur lokale
  Redaktion und separate Schreibsperre statt globalem Clientmanifest.
  Der Worker hat die sieben neuen Reds auf unverändertem `d661014` bestätigt.
  Die gemessenen zusätzlichen Eventscan-Kosten bleiben ein offenes Volumenrisiko.
  Fixcommit `d21d0de9c5060cd27d03c701d75fe68d38ad9746` ist uncommittiert
  in Root übernommen: 200/200 SQL, echter Backfill, 18 Lockrennen plus
  sechs Isolationdenials; breite 181/190/200/210-Rennen grün gemeldet.
  `deleted_player_identities` bedeutet nur Nichtwiederverwendung;
  `player_write_redactions` sperrt Serverwrites getrennt von globaler
  Clientredaktion. Die frische Claude-Review r4 scheiterte vor Beginn an
  Authentifizierung; Ersatz als unabhängige Codex-Review:
  `advantly-20261020-server-independent-review-r4-codex`.
  Root hat den integrierten SQL-Stand einschließlich der sieben
  Alt-Erwartungskorrekturen selbst ausgeführt: 37 Dateien, 1695 Assertions,
  1690 grün und ausschließlich die fünf bekannten fehlenden Plattformprüfungen
  rot. Log: `%TEMP%/advantly-root-sql-integration-20261020/full-r3-integrated.log`.
  Codex-Review r4 hat diese bisherigen Nachweise selbst bestätigt, aber
  zwei neue P1 ausgeführt: Bei einem Text-Slot geht Bobs positiver
  Player-/Profilbeleg nach dem späteren Owner-Delete verloren; neue Events
  werden fälschlich anonymisiert und vom echten Redaction-Pull ausgeliefert.
  Bei Alice-Self-ID = Dave-Profil-UUID kann AliceDelete die explizit per
  Profil-FK belegten Events des lebenden Dave redigieren. Leere globale
  ID-Manifeste verhindern beide Eventfehler nicht.
  Beide RPCs: 10 rote Assertions von 46, Gegenproben 23/23 grün.
  Repros/Log: `%TEMP%/review-220-a88f082a/probe-n4-manifest.sql`,
  `probe-n5-manifest.sql`, `inverse-both-rpcs.log`.
  Folgeauftrag `advantly-20261020-server-durable-context-fix-r4`;
  positive typisierte Belege müssen den FK-/Player-Cascade überleben,
  ohne überholte Zuordnungen als dauerhafte Freistellung zu behandeln.
  `d21d0de` bleibt ungefreigegeben.
  Worker r4 meldet für den noch nicht übernommenen Folgestand:
  388/388 SQL, Backfill, 22 Lockrennen und sechs Isolationdenials grün;
  beide RPCs mit Text-Slot-/UUID-Kontrollen, direkten Profilbezügen und
  Widerruf durch Unassign, NotMe, Profilwechsel und TeilnehmerDELETE.
  Breite Suite 1871/1883 mit denselben zwölf isolierten Alt-/Plattformabweichungen.
  Neuer Stand im 200k-Probe: Insert 16,66s, unbeteiligter Delete 2,17s;
  N3 bleibt offen. Abschließender Worker-Check `--no-build-cache` grün,
  33s/700 Tasks; Slot zurückgegeben. Commit `7b630fea` ist in Root bytegleich
  vorläufig übernommen, noch nicht committiert oder freigegeben.
  Frische unabhängige Review:
  `advantly-20261020-server-independent-review-r5`.
  Eigener Root-SQL-Lauf mit den sieben korrigierten Alt-Erwartungen:
  37 Dateien, 1883 Assertions, 1878 grün, genau fünf fehlende
  Plattformprüfungen (Storage, pg_cron, pg_net). 080, 210 und 220 vollständig
  grün; kein vollständiges Supabase-Gate. Log:
  `%TEMP%/advantly-root-sql-integration-20261020/full-r4-integrated.log`.
  Review r5 reproduziert N6 auf exakt `7b630fea`: Historische positive
  Participant-FK-Belege werden im Profilkollisionshelper weiterhin nur
  innerhalb eines Joins auf die aktuelle Playerzeile berücksichtigt.
  Nach Player-/Owner-Harddelete und Löschung einer kollidierenden Profil-ID
  werden lebende Bob-Events fälschlich lokal redigiert. Auch die umgekehrte
  Löschreihenfolge ist betroffen. Beide RPCs: 168 Assertions, 22 gezielte
  Fehler; 72 Gegenkontrollen grün. Vorherige 388, Backfill und 22 Rennen
  bleiben grün. Repro/Log: `%TEMP%/review-7b630-a6a7244b/probe-n6.sql`
  und `n6.log`. Folgeauftrag `advantly-20261020-server-profile-provenance-fix-r5`;
  Server startet keinen Gradle-Lauf während des Restore-Slots.
  N6-Folgestand im Worker: Originalprobe 168/168 nach 22 Baseline-Reds,
  eigene Suite 668/668, Backfill und 30 echte Lockrennen plus sechs Denials.
  Die 112 Zusatzassertions prüfen tatsächliche spätere Bob-Löschung und
  beide Löschreihenfolgen; keine dauerhafte Ausnahme nach Widerruf.
  Breite Suite 2151/2163 mit denselben zwölf isolierten Alt-/Plattformabweichungen.
  Nach Root-Slottransfer abschließender Check 35s/700 Tasks grün,
  Slot ausdrücklich zurückgegeben. Commit `3d1c761` ist in Root bytegleich
  vorläufig übernommen. Frische unabhängige Gesamt-Review:
  `advantly-20261020-server-independent-review-r6`.
  Eigener Root-SQL-Lauf mit den sieben korrigierten Erwartungen:
  37 Dateien, 2163 Assertions, 2158 grün, exakt fünf fehlende Plattformprüfungen.
  080, 210 und alle 668 Assertions in 220 sind grün; kein vollständiges Stack-Gate.
  Log `%TEMP%/advantly-root-sql-integration-20261020/full-r5-integrated.log`.
  Review r6 bestätigt N6 und unabhängig 668/668, Backfill, 30 Rennen,
  sechs Denials sowie 176 zusätzliche Kontextwechselprüfungen.
  Neuer P2 N7: Nach globaler Löschung von Player X entsteht erstmals
  Profil Vera mit UUID X. Trotz positivem direkten Profil-FK im anderen Match
  redigiert der unbedingte globale Player-Zweig Veras Events. Beide RPCs:
  110 Prüfungen, sechs echte Reds; 82 Gegenkontrollen grün.
  Repro `%TEMP%/review-3d1c761-independent-20261020/probe-inverse.sql`.
  Folgeauftrag `advantly-20261020-server-later-profile-collision-fix-r6`;
  Worker reproduziert die sechs Reds auf unverändertem `3d1c761`.
  Enges Modell: nur expliziter aktueller Match-Profil-FK mit IDgleichheit
  und lebendem nicht redigiertem Profil begrenzt den globalen Player-Eventzweig.
  Globale Player-Tombstones, lokale Löschbelege, echte Profillöschung,
  Nonreuse und Schreib-/Assignsperren bleiben bestehen.
- Data-Review r5 fand einen P1 im öffentlichen `assignments()`:
  Nach der lokalen Maske wurde erneut der lebende Verzeichnisname geladen.
  Root reproduzierte dies als echten Red und entfernt nur diesen doppelten
  Lookup. Ein Test über `SessionMatchStore.record(...).summary.setup`
  bestätigt die Maske auch in der Match-Ansicht bei unverändertem Verzeichnis.
- Der eigene Materialisierungsbeleg bleibt nun ausschließlich bei
  MEMBER→MEMBER erhalten, während `ready` zurückgesetzt wird. Feed/Entzug
  entfernen ihn weiterhin. Auch dieser Fehler wurde vor dem Produktfix rot
  reproduziert; Neustart, fremder Snapshot und finaler Fence-Rollback sind
  abgedeckt. Gezielte Lifecycle-Suite 52/52 grün; vollständiger Root-Check
  nach Formatkorrektur grün: 55s/700 Tasks, Data 200 und Presentation 178
  ohne Fehler/Skips, Cloud 190 mit einem bekannten Stack-Skip.
  Commit `fba7394`; frische Review
  `advantly-20261020-data-event-independent-review-r6`.
  Logs/XML: `%TEMP%/advantly-root-review-r5-d1-{red,green}.*`,
  vollständiger Check `advantly-root-review-r5-d1-check.log`.
  Review r6 akzeptiert den engen Materialisierungsbeleg-Fix, nennt aber zwei
  D1-Integrationsprüfungen: Eventarbeit muss bei unverändertem Snapshot neu
  angelegt werden können; Teilnehmer-ACKs im nicht-ready-Fenster brauchen
  einen expliziten Test. Keine Readiness-Freigabe daraus ableiten.
  Neuer r6-P1: `MatchSession.reconcilePeople` schrieb die leere Maske zurück
  in einen lebenden Gast oder legte einen leeren Gast an. Root hat beide
  Session-Aktionsfälle gegen unveränderten `fba7394`-Produktcode rot bewiesen.
  Der enge Fix überspringt diesen Namen beim Directory-Upsert; positive
  Identität und Nutzungsmarke bleiben erhalten. Beide Fälle danach grün,
  einschließlich unveränderter PLAYER-Revision/ACKs.
  Belege: `%TEMP%/advantly-root-review-r6-{red,green}.{log,xml}`.
  Fixcommit `ad80c02`; neue unabhängige Codex-Review:
  `advantly-20261020-data-event-independent-review-r7`.
  r7 bestätigt beide Korrekturen und die gespeicherten Red-/Green-Artefakte.
  Ein weiterer statisch belegter P1 liegt in `SessionMatchStore.start`:
  `rememberPlayers(assignedPlayers)` kann die maskierte Person ohne
  Directoryzeile als leeren lokalen Gast anlegen. Root hat zwei separate
  Rematch-Tests für fehlenden beziehungsweise vorhandenen Directoryeintrag
  ergänzt: fehlender Eintrag auf `ad80c02` tatsächlich rot, vorhandener
  als Gegenprobe grün. `rememberPlayers` überspringt nun ausgeblendete Namen.
  Beide Fälle und die vier bisherigen Tests danach grün, vollständiger
  `check --no-build-cache` grün in 1m01s/700 Tasks.
  Commit `ca49664`; Belege `%TEMP%/advantly-root-review-r7-{red,green}.xml`
  und `advantly-root-review-r7-check.log`.
  Frische Nachprüfung `advantly-20261020-data-event-independent-review-r8`.
  r8 ist abgeschlossen: keine neuen Befunde im geprüften Bereich.
  Gemeinsamer Filter für Setup/Rematch/Favoriten und weitere Upsert-Pfade
  geprüft, gespeicherte Reds/Greens und Check-Log bestätigt; keine eigenen
  Gradle-Läufe. Allgemeine Meta-/V4-Autorität bleibt außerhalb dieser Freigabe.
  Der Audit der weiteren Anlage-/Upsert-Aufrufer zeigt: Benannte UI-Anlage
  validiert bereits nichtleere Eingabe; der gemeinsame `rememberPlayers`-
  Eingang wird auch für Favoriten verwendet. `MatchMeta` bezieht seine
  Spielerlisten aus dem Directory, nicht aus der maskierten Zuweisungsmap;
  dessen allgemeine Quellautorität bleibt der bekannte V4-Integrationsauftrag.
- Hostfix `e3e96e1` ist geprüft als `8d8c230` übernommen: erwartete Speicherfehler
  als Android-only `Unavailable`, DE/EN-Retry-Ansicht, inerte Dienste/Provider,
  keine Ersatzinstallation oder Freigabe. Worker-Check 58s/700 Tasks,
  Phone 69 und Wear 325 grün; zwei echte b28-Kaltstart-Reds erhalten.
  Unabhängige Review `advantly-20261020-host-error-independent-review-r1`
  bestätigt sämtliche Einstiegspfade und die XML-/Log-Belege, ohne neuen
  P1/P2 im Diff. Sie identifiziert daneben den vorbestehenden Standardhandler
  der aktiven SQLDelight-DB als Datenverlustrisiko; der werfende Handler galt
  bisher nur für die Prüfkopie. Auch Storage-Fehler nach Ready im Bootstrap
  müssen sicher gehalten werden. Folgeauftrag
  `advantly-20261020-restore-active-db-corruption-fix` umfasst diese beiden
  Übergänge sowie die IO-Wrapper `DirectoryIteratorException`/
  `UncheckedIOException` und die präzise Prozess-Latch-Dokumentation.
  Keine pauschale Unterdrückung von Programmierfehlern.
  Server hat seinen grünen Check beendet und den Slot zurückgegeben.
  Root hat Session-Fix und Hostfix gemeinsam geprüft:
  `check --no-build-cache` grün in 1m54s, 700 Tasks
  (158 ausgeführt, 542 aktuell). XML: Data 200, Presentation 179,
  Phone 69 und Wear 325, jeweils ohne Fehler/Skips.
  Log: `%TEMP%/advantly-root-review-r6-host-check.log`.
  Der Slot wurde explizit an Restore für die neuen e3-Korruptions-/I/O-Reds
  und die anschließenden Checks übertragen.
  Der Worker hat vier echte e3-Reds gesichert. Der zusätzliche aktive
  Eventseiten-Test meldet ausdrücklich keinen Abfragefehler, aber veränderte
  DB-Familie bei weiterhin erlaubtem Transport. Dies belegt den destruktiven
  Defaultpfad und nicht nur eine fehlende Exceptionbehandlung.
  Evidence im Restore-Worktree: `build/bootstrap-evidence/`.
  Folgestand `a563747` auf `e3e96e1` ist fertig, noch nicht integriert:
  produktiver werfender Korruptionshandler, private `quick_check`-Prüfung,
  Bootstrap-Veröffentlichung erst nach Commit, prozessweite Capability und
  Android-only Taskbarriere für ACKs/Queue-Callbacks. Worker meldet
  48s/700 Tasks grün, Phone 78/Wear 326/Data 111/MatchSession 140 im
  isolierten Stand; kein Root-Common-Import.
  Frische unabhängige Review vor Übernahme:
  `advantly-20261020-restore-bootstrap-independent-review-r1`.
  Ergebnis: keine Freigabe, zwei P1 und zwei P2. Die äußere Tasktransaktion
  verschiebt Identitäts-`afterCommit`-Updates, während Antworten bereits mit
  altem Kontext serialisiert werden können. Der Driver-Notifier schützt
  native COMMIT-Fehler außerhalb der Task-/Bootstrap-Grenze noch nicht.
  Ein verspäteter GMS-Sendfehler kann nach Widerruf den Queue-Fallback
  auslösen; bestehende Activity-/Telemetrie-Verbraucher behalten alte Sessions.
  Die Befunde sind statisch belegt, ihre echten Baseline-Repros folgen im
  Auftrag `advantly-20261020-restore-bootstrap-review-fix-r1`.
  Der exklusive Gradle-Slot wurde dafür an Restore übertragen.
  Enges Folgemodell: keine äußere Runtime-Tasktransaktion, damit vorhandene
  lokale `afterCommit`-Identitätswechsel vor weiterer Serialisierung wirken;
  Bootstrap bleibt transaktional. Taskausgaben bleiben bis Erfolg gepuffert,
  bereits abgeschlossene lokale Transaktionen werden bei späterem Taskfehler
  nicht als zurückgerollt ausgegeben. Reliable-/Raw-Transportinstanzen werden
  an den Identitätskontext gebunden und beim Wechsel ersetzt; alte Retries
  und asynchrone Fallbacks müssen inert bleiben. Ein regulärer SQLite-
  OpenHelper-/Database-Decorator schützt auch native Transaktionsabschlüsse.
  Warme Activities und Telemetrieverbraucher beobachten den Widerruf und
  prüfen vor Send/Zugriff unmittelbar die Capability.
  Bestätigt bleiben der werfende Korruptionshandler, private `quick_check`,
  enge Speicherfehlerklassifikation und das Verwerfen fehlgeschlagener Ausgaben.

## Nächster enger D1-Schritt

Die nachgelesene Verdrahtung übergibt bereits `rawEvents` an
`CloudSyncRepository`. Ein zusätzlicher allgemeiner Raw-Import ist unnötig.
Der zyklische Abgleich entsteht dadurch, dass Manifestabschluss vorhandene
Rohzeilen verlangt, während der bereinigte Download ohne Manifestbeleg am
strengen Duplikatvergleich scheitert.

Root hat deshalb drei Regressionstests vorbereitet: Manifestabschluss vor
Rohdownload mit Neustart und weiter gesperrter Readiness; atomarer Rollback
am letzten Fence; ungültige Eventbelege ohne Rohzeile. Der Baseline-Lauf auf
unveränderter `ca49664`-Produktion ergibt drei Tests mit zwei echten Fehlern;
die Negativprobe besteht. Log/XML:
`%TEMP%/advantly-root-d1-before-raw-red.{log,xml}`.
Der enge Produktfix ist als `c172535` committiert. Die gesamte Lifecycle-Klasse
besteht 55/55 Tests; Data JVM insgesamt 203/203 ohne Fehler oder Skips.
Ein erster Gesamtklassenlauf fand eine zusätzliche Kollision: fehlende
Event-ID bei bereits belegter Geräte-Sequenz. Der Produktfix weist diese
zurück, die bestehende Manipulationsprobe blieb unverändert.
Vollständiger `check --no-build-cache` grün in 1m49s/700 Tasks
(126 ausgeführt, 574 aktuell). Log `advantly-root-d1-before-raw-check.log`.
Frische unabhängige Review:
`advantly-20261020-data-manifest-before-raw-review-r1`.
Sie findet einen neuen P2: Die Sequenzprüfung berücksichtigt vorhandene
Rohzeilen, aber nicht andere reine Eventbelege. Zwei IDs können so dieselbe
Match-/Geräte-Sequenz beanspruchen, auch nach Purge. Root hat zwei konkrete
Regressionstests vorbereitet (innerhalb einer Seite und nach Purge/Reopen);
Ein dritter Test prüft normalen Reimport nach Purge mit anderer Event-ID,
einschließlich `PointWon`, sowie erlaubte andere Match-/Gerätekoordinaten.
Noch kein Lauf und kein Produktfix gegen diesen Befund. Nach dem nächsten
Restore-Release beginnt Root mit echten Baseline-Reds auf `c172535`.

Der lesende API-Audit empfiehlt Schema 10: nullable Match-/Geräte-/Sequenz-
Koordinaten neben unverändertem Event-JSON, ein Unique-Index und ein Partial-
Index für noch unindexierte Altbelege. Ein begrenzter Kotlin-Backfill muss
unter Transaktion/Fence sämtliche Altbelege prüfen, bevor der Sequenzindex
als vollständig gilt. Kein Constructor-Backfill, keine JSON-SQL-Abhängigkeit,
kein In-Memory-Erfolgsflag und keine per-Event-Vollscans. Widersprüchliche
Altbelege bleiben erhalten und lassen die betroffene Operation geschlossen
abbrechen. Auch Reimport und Export dürfen diesen Konfliktnachweis nicht umgehen.
Dies ist der geplante Folgeschritt, noch keine umgesetzte Migration.

Der beabsichtigte enge Fix speichert ausschließlich exakt geprüfte,
vollständig autorisierte Eventbelege auch ohne lokale Rohzeile. Er erzeugt
keine Receipts, keine globale Playeridentität und keine Readiness. Vorhandene
Rohzeilen behalten den vollständigen unveränderlichen Feldvergleich; spätere
Downloads verwenden weiterhin den strengen vorhandenen Import.

Das Readiness-Gate bleibt separat: Der aktuelle Wasserstands-RPC liefert
keinen an G/E/H gebundenen Vollständigkeitsbeleg. Eine leere Antwort oder
`more=false` darf nicht zur Freigabe führen. Benötigt werden ein dauerhafter,
kontextgebundener Validierungsversuch, Snapshot-/Materialisierungsnachweis
und autoritative Wasserstände bis H. A→B→A, neuer G/E/H-Stand und Raw-Purge
invalidieren alte Handles; Neustart verlangt erneute Quellenautorisierung.

Restore hat den Slot nach vollständigem Check ausdrücklich an Root gegeben.
Der finale Folgecommit ist `e04919ae55544278bae6fdd74a253ee84405d743`;
zwischen vorläufigem `af868ba` und diesem Hash liegt nur eine Testkommentarkorrektur.
Die kurz zuvor gestartete Review des vorläufigen Hashes wurde abgebrochen;
neuer vollständiger Auftrag für `e3e96e1..e04919a`:
`advantly-20261020-restore-bootstrap-independent-review-r2-final`.
Die beiden Restore-Folgecommits bleiben unintegriert. Worker-Evidence:
`build/a563-review-evidence/`, letzter Check 36s/704 Tasks grün.
Vollsuite-XML meldet über Targets 2411 Fälle, keine Fehler, einen bekannten
D1-Stack-Skip. Runtime-Inventare und die neun ausgewählten externen Artefakte
inklusive SHA sind vor/nach direkter SQLite-Compile-Abhängigkeit identisch.
Nach Roots D1-Check erhielt Server den reservierten Slot für N6 und gab ihn
nach erfolgreichem Check zurück. Root hat ihn inzwischen ausdrücklich an
Restore für die nächste Korrekturrunde übertragen.

Die frische Gesamt-Review auf `e04919a` bestätigt die genannten XML-Zahlen,
findet aber drei weitere P2: Auth-Abbau ruft nach Driver-Close erneut
`CloudUploadStore.deactivate` mit SQL auf; ein bereits registrierter
Workout-Callback greift auf die verworfene Session zu; eine vor Cleanup
eingereihte Cloud-Antwort kann noch ACKs und weitere Batches auslösen.
Folgeauftrag `advantly-20261020-restore-warm-consumers-fix-r2`.
Statische Repros werden zunächst gegen unveränderten `e04919a` ausgeführt.
Für DB-freien Fehlerabbau und unmittelbare Versand-/Antwort-Fences ist
ausdrücklich nur die zusätzliche Common-Schnittstelle
`hostAccessAllowed: () -> Boolean = { true }` in `SupabasePhoneAuth.kt` und
`CloudSessionGate.kt` autorisiert. Android bindet sie an die konkrete Session;
gesunder Logout/Close bleibt persistierend. Background-Widerruf beschränkt
sich auf thread-sichere Capability/Job-Cancellation, übriger mutabler Zustand
bleibt auf dem bisherigen seriellen Kontext. Workout-Abbau bleibt Android-only.

Der letzte Fetch bestätigte `origin/main = ed5126b`. Alle neun Root-Commits
liegen bisher nur lokal; SQL-/CI-/Dokumentationsintegration ist noch
uncommittiert. Rematch-Fix und Tests sind geprüft und committiert.
Keine PR, kein Push, kein Deployment.

### Laufender Nachweisstand N7 und warme Verbraucher

Server meldet für den ersten N7-Entwurf 856/856 SQL-Assertions, Backfill,
38 nachgewiesene Lockrennen und sechs Isolation-Denials. Die externe
Transitionenprobe ist mit 176/176 separat grün. Eine zusätzliche reale
Gleichzeitigkeitsprobe fand anschließend acht Fehler im noch uncommitteten
Entwurf: Im selben Match existieren sowohl Player X mit Profil Grace als auch
ein direkter Profilbezug Vera mit Profil-ID X. Eine bloß vorhandene positive
Profil-FK genügt hier nicht. Die Ausnahme wird auf eindeutige Profilkontexte
ohne widersprechenden gültigen Playerkontext im selben Match begrenzt;
historische Belege nach Player-FK-Verlust bleiben dabei relevant. Lokale
Löschbelege behalten Vorrang. Diese acht Entwurfsfehler sind keine zusätzlichen
Baseline-Reds gegen `3d1c761`. Erneute Ergebnisse und Commit stehen aus;
N3-Performance bleibt offen.

Restore meldete zunächst einen grünen breiten fokussierten Lauf und vollen
Check. Die XML-Nachprüfung korrigierte jedoch einen behaupteten Nachweis:
Der aktive Workout-Test scheiterte wegen Windows-Pfadlänge bereits beim
SQLite-Open, bevor er den Callback erreichte. Der zerstörte Callback lieferte
einen tatsächlichen Storage-Fehler. Restore wiederholt den aktiven Repro mit
kurzem Testnamen gegen exakt `e04919a`, stellt danach die gesicherten Fixbytes
wieder her und führt den Check erneut aus. Bis zum expliziten Release bleibt
der Gradle-Slot bei Restore. Die Folge bleibt RESTORE → ROOT → SERVER.

Restore hat inzwischen `2e7697db73400bf941f093bb942c94f3755be96e`
committiert und den Slot ausdrücklich an Root gegeben. Der korrigierte
kurze Workout-Baseline-Test belegt nun den echten SQLiteDiskIOException im
aktiven Callback. Archivierter Check: 35s/700 Tasks, 351 XML-Suites und
2424 targetübergreifende Reportfälle, keine Fehler, ein bestehender Stack-Skip.
Die frische Gesamt-Review `advantly-20261020-restore-warm-consumers-independent-review-r3`
bestätigt die drei bisherigen Fixes und die Evidence, findet aber zwei neue
statische P2: erster Speicherfehler in der SQL-basierten Identitätsprüfung des
direkten GMS-Polls läuft außerhalb der Auffanggrenze; eine verspätete eigene
Health-Statusantwort kann nach Retry das von einer neuen Servicegeneration
übernommene Workout beenden. Folgeauftrag
`advantly-20261020-restore-poll-health-generation-fix-r3` bereitet eng begrenzte
Repros vor, ohne Gradle. Keine Restore-Folgeintegration.

Root hat die drei Sequenzbeleg-Repros gegen unveränderte Produktion `c172535`
ausgeführt: 3/3 echte Reds; XML und Log unter
`%TEMP%/advantly-root-d1-sequence-red.{xml,log}`, Produktdiff separat leer.
Schema 10 und die transaktionale Indexprüfung sind jetzt implementiert.
Import, vollständige Redaktionsanwendung einschließlich Wiederholung und
Export prüfen den abgeleiteten Index. Alle drei Repros und die übrigen
Verhaltenstests wurden grün; die Schema-Paritätsprüfung normalisiert zusätzlich
rein syntaktischen Leerraum vor Komma/Klammer aus SQLite-ALTER.
Sechs weitere Migrationstests decken Schema 9, 205 Altbelege, einen Konflikt
auf späterer Seite, beschädigte Belege, Raw-Kollisionen, Fence-Rollback,
Neustart sowie widersprüchliche bereits abgeschlossene Transfers ab.
Der vollständige Root-Check läuft; Commit und unabhängige Review stehen aus.

Server meldet den nachgeschärften N7-Stand mit 880/880 eigenen SQL-Assertions,
Backfill und 38 Lockrennen plus sechs Isolation-Denials grün; externe 176
Transitionen separat grün. Neue NotMe-Probe widerruft den widersprechenden
historischen Playerkontext nach Harddelete: neue eindeutige Vera-Events
bleiben benannt, bereits redigierte Events bleiben redigiert.
Der Worker wartet ohne weiteren Gradle-Aufruf auf den Transfer von Root.

Root-Sequenzfix abgeschlossen: `83d2c15976638c433ea253d1ed4b20299cc3c851`,
genau acht Data-Dateien. Final **212/212 Data-JVM-Tests**, 0 Fehler/Skips;
voller `check --no-build-cache` grün in 2m26s, 700 Tasks
(161 ausgeführt, 539 aktuell). Finale XML:
`%TEMP%/advantly-root-d1-sequence-green.xml` und
`advantly-root-d1-sequence-migration-green.xml`; Checklog entsprechend
`advantly-root-d1-sequence-check.log`.
Die falsche Readiness-Assertion eines neu ergänzten Migrationstests vor
Aktivierung wurde auf den gespeicherten `ready`-Wert korrigiert.
Unabhängige Review
`advantly-20261020-data-sequence-proof-independent-review-r1` abgeschlossen:
keine neuen P1/P2 im Kontext `ca49664..83d2c15`. Bestätigt wurden
Sequenzschutz auch für Points, Purge/Reopen, atomarer Backfill und
Konflikterhalt sowie das Ausbleiben zusätzlicher globaler Identität,
Receipts oder Readiness. Die lokal verwendete SQLDelight-Runtime wurde auf
Weitergabe von `afterCommit` an die äußere Transaktion geprüft; keine
vorzeitige Ausführung durch die Sanitizer-Transaktion. Evidence gelesen,
keine eigenen Testläufe des Reviewers. D1/V4-/Readiness-Lücken bleiben
separate Aktivierungsgates.

Nach Root-Transfer bestand auch Server den abschließenden Check:
30s/700 Tasks (47 ausgeführt, 653 aktuell), unveränderte drei Dateien.
Finaler N7-Commit `72fb7d11e599062ed417410e294e7d206ccd475f`,
Parent `3d1c761`. Frische Gesamt-Review:
`advantly-20261020-server-independent-review-r7`.
Noch keine Übernahme des N7-Diffs in Roots gestageten Serverstand.

Root hat den Slot nach Server-Release ausdrücklich an Restore weitergegeben.
R3-Modell: enge Storage-Auffanggrenze im asynchronen Identitäts-/Pollingpfad
mit erneuter Generation-/Freigabeprüfung nach reentrantem Widerruf;
Health-Lease je tatsächlicher Ready-Serviceübernahme und eine gemeinsame
Barriere für bereits gestartete native Endoperationen. Coroutine-Cancellation
hebt diese Barriere nicht auf. Verspätete alte Statusantworten dürfen kein neu
übernommenes Workout beenden; nach terminalem Fehler ist der echte Healthstatus
erneut zu prüfen. Modell akzeptiert, echte Baseline-Reds noch ausstehend.
Keine weiteren Root-/Server-Builds parallel zu Restore.

Root steht nun zehn lokale Commits vor dem zuletzt geprüften `origin/main`.
SQL-/CI-/Dokumentationsintegration bleibt uncommittiert, kein Push/PR/Deploy.

### Review-Abschlüsse und N3 als konkreter Funktionsblocker

Restore R3 ist als `613a92f486463fe76f1c3d9b17f8a6d6626726f3` auf
`2e7697d` committiert, genau fünf Android-Dateien. Baseline gegen unveränderte
Produktion: Poll 5 Fälle/3 echte Reds, Health final 13 Fälle/6 echte Reds;
anfänglicher fehlender Self im Testaufbau ist separat dokumentiert und zählt
nicht als Red. Final 5 Poll- und 14 Health-Fälle grün, Vollcheck 43s/700 Tasks
(81 ausgeführt, 619 aktuell). Archiv: 352 XML-Suites/2439 Reportfälle,
keine Fehler, ein bestehender Skip; mehrere Targets und aktuelle alte Reports
sind enthalten. Evidence: Worker `build/2e7697d-review-evidence/`.
Frische Gesamt-Review `advantly-20261020-restore-runtime-independent-review-r4`
läuft. Slot ausdrücklich an Root zurückgegeben; keine Folgeintegration.

Server-Review R7 auf exakt `72fb7d1` bestätigt keine neuen funktionalen
P1/P2-Befunde. Der Reviewer führte unabhängig 880/880 SQL, 176/176
Transitionen, den echten Backfill, 38 Lockrennen und sechs Isolation-Denials
aus. Original-N7-Probe: Parent 6/110 rot, Ziel 110/110 grün. Zusätzlich
86 eigene Batch-/Kontext-/UUID-/Epoch-/Atomicity-/Profildelitionsproben grün.

N3 ist jetzt durch eine konkrete Timeout-Gegenprobe als Funktionsblocker
belegt: 210 Zeile 152 mit 205 zusätzlichen Self-IDs und 505 Events überschreitet
das unveränderte 10s-Limit in `capture_profile_redactions`/`referenced` und
`redaction_other_profile_context`. Separat gesendete Statements reproduzieren
dies sowohl auf Ziel als auch Parent; 006 benötigt für dieselbe Löschung
85ms und besteht 210 mit 191/191. Damit keine neue N7-Regression, aber
weiterhin keine Freigabe. Der breite unabhängige Lauf bestand 33/37 Dateien;
neben bekannten Erwartungs-/Plattformlücken scheiterte 210 durch Timeout.
Diese Evidenz ersetzt nicht rückwirkend die anders ausgefallenen Workerläufe.
Logs: `%TEMP%/review-72fb-independent-20261020-astra/owned-210-{split,parent-control,006-control}.log`.

Folgeauftrag `advantly-20261020-server-capture-performance-fix-n3` bleibt
auf die drei Serverdateien beschränkt. Zuerst unveränderte Reproduktion und
Ausführungsplan/Aufrufkosten, dann enges Produktmodell; keine Reduktion der
Identitäts-/Rechteprüfungen oder Erhöhung der 10s-/5s-Budgets. Server beginnt
ohne Gradle. Keine Aktivierung, kein Push/PR/Deployment.

Der parallele lesende D1-Audit bestätigt: G ist `visibilityEpoch`, nicht
Match-`accessEpoch` oder lokale Auth-Generation. Der Redaction-RPC liefert
G/E/H auch für E=0, lokale Transfers erlauben bisher nur E>0. Ungebundene
Wasserstands-/Event-Arrays sind bei RLS-Verlust kein Vollständigkeitsbeweis.
Der minimale additive Boundary-Vertrag mit explizitem MEMBER und erwarteten
G/E/H/Snapshot-Version wird weiter präzisiert; noch keine Produktänderung.

### Finale Übergaben 18ca / e54 und neue unabhängige Reviews

Restore-Review R4 auf `613a92f` fand zwei weitere Operationsgrenzen:
queued Worker→Main-Handoffs prüften die Freigabe nicht erneut um die
tatsächliche Operation; direkte UI-Aktionen konnten ihren ersten nativen
SQL-Fehler außerhalb der Storage-Grenze werfen. Der Folgecommit
`18ca1cfd5de32056ec474d8bbd45ad34985b2751` umfasst 23 Dateien.
Listener binden Main-Dispatch und Queue-Verbrauch an die konkrete Session;
Phone-Store und Wear-Controller erhalten eine gemeinsame enge
Operationsgrenze. Retained Controller-/Store-Zugriff bleibt von der
Activity-Lebensdauer getrennt.

Ausdrücklich genehmigte Common-Erweiterung: drei nullable Defaultmethoden
in `MatchPorts.kt` und angepasste Aufrufer in `AppModel.kt`,
`MatchWizard.kt`, `PlaceholderAssignments.kt`. Die Defaults delegieren
ohne Catch an die bestehenden Methoden. Android erfindet bei Ablehnung
keine IDs oder Ergebnisse; abgelehnte Vorgänge dürfen keine nachfolgende
Navigation, Zuweisung oder Synchronisation auslösen.

Workerbelege gegen unverändertes 613: beide UI-Suiten jeweils fünf Fälle
mit drei echten nativen Reds; beide finalen Listener-Suiten jeweils drei
Fälle mit einem echten Red. Die anfänglich falsche SDK-abhängige
`sync.peers`-Kontrolle zählt nicht als Produktfehler. Finale Kontrollen
nutzen das echte Session-Geräteverzeichnis. Focused 52s/204 Tasks und
Vollcheck 1m12s/700 Tasks grün. Archivierte 359 Suites/2465 Reportfälle,
0 Fehler, 1 bestehender CloudPullStackTest-Skip; mehrere Targets und
UP-TO-DATE-Reports sind ausdrücklich eingeschlossen. Evidence:
Restore-Worktree `build/613a92f-review-evidence/`, einschließlich
Quellhashes, Entry-Point-Inventar und Vergleich der Controller-Auslagerung.

Server-N3 ist als `e54cd62d4cc15965cba6a7569bb387d7a07f43e1`
auf `72fb7d1` committiert, genau die drei Serverdateien. Der Fix findet
normalisierte Eventreferenzen über drei additive Ausdrucksindizes,
materialisiert eindeutige Match/ID-Paare und prüft erst danach den
unveränderten typisierten Kontext. Nur reine Payloadnormalisierung ist
IMMUTABLE; Kontext-/Tombstonehelfer bleiben VOLATILE.

Worker reproduzierte den 10s-Abbruch auf 72fb. Im identischen isolierten
Fixture sank die Zahl der Kontextaufrufe von 106704 auf 211 und die Zeit
von 9512ms auf 32ms; vollständiger 210-Delete danach etwa 175–178ms.
899/899 eigene SQL-Assertions, echter Backfill, 38 Lockrennen und sechs
Isolation-Denials grün; externe 176 Transitionen und 86 Reviewerkontrollen
separat grün. Breiter Lauf: 2394 Assertions, 2382 grün, genau die sieben
bereits in Root korrigierten Erwartungen und fünf Plattformlücken offen.
Kein Timeout oder zusätzlicher SQL-Fehler. Vollcheck 44s/700 Tasks grün.
Logs: `%TEMP%/advantly-server-220-419a1f41/n3-*.log`.

Der vergleichbare 200k-Lauf belegt den konkreten Timeout-Fix, keine
allgemeine Lastfreigabe: Inserts bleiben mit rund 18,7s deutlich teurer
als die 006-Referenz mit rund 7,4s. Reale Stack-, Indexaufbau-, größere
Last- und Hardwaregates bleiben offen.

Beide Worker sind fertig und haben den exklusiven Gradle-Slot an Root
zurückgegeben. Neue unabhängige Gesamt-Reviews laufen parallel:

- `advantly-20261020-restore-entrypoint-independent-review-r5`:
  gesamter noch unintegrierter Slice `e3e96e1..18ca1cf`, inklusive
  früherer Findings, Common-Kompatibilität und Evidence.
- `advantly-20261020-server-performance-independent-review-r8`:
  gesamte Migration 007 auf `e54cd62`, inklusive N1–N7,
  unabhängiger N3-Reproduktion und unveränderter Identitätsverträge.

Root steht weiterhin auf `83d2c15`; die neuen Übergaben sind noch nicht
integriert. Der gestagete Serverstand entspricht weiterhin `3d1c761`.
Keine Aktivierung, kein Push/PR/Deployment. Vollständige SAME-UID-
Wiederherstellung, aktuelle D2/D3-Importrechte, explizite anonyme Adoption
und autorisiertes Re-Pairing bleiben eigenständige Folgeintegration;
Login und Retry heben den Verification-Hold nicht auf.

### Ergebnisse der Gesamt-Reviews und enge Folgekorrekturen

Restore-Review R5 auf exakt 18ca ist abgeschlossen: keine neue P1,
zwei durch eigene native Repros bestätigte P2. Bei Link-Aktivierung
kann eine innere Storagegrenze die Runtime verwerfen, während Bootstrap
anschließend trotzdem die Session veröffentlicht und an `work.release()`
mit einem Programmierfehler abbricht. Der zweite Befund betrifft die erste
SQL-Ausnahme im SDK-Auth-Collector nach erfolgreich geprüftem Zugriff:
Job-Cancellation verhindert den ungefangenen Coroutine-Fehler nicht.
Eigene Reviewprobe: sechs Fälle, zwei Storage-Reds, vier gesunde bzw.
Programmierfehler-Kontrollen. Evidence im Review-Worktree
`t3-5cf64479/build/fresh-review-18ca/`.

Folgeauftrag `advantly-20261020-restore-activation-auth-collector-fix-r5`
läuft auf 18ca. Akzeptiertes Modell: gespeicherter Fehler und konkrete
Ressourcengeneration an Bootstrap-Phasengrenzen, insbesondere nach
Aktivierung und vor jeder Veröffentlichung; Invalidierung entfernt nur
die eigene Session. Ein optionaler synchroner Host-Operationshook in
`SupabasePhoneAuth` umfasst die tatsächliche Collectoroperation.
Sein Default delegiert direkt; Android verwendet die vorhandene enge
Storagegrenze. Keine pauschale Fehlerunterdrückung und keine Änderung
an `CloudSessionGate`. Restore hält dafür den exklusiven Gradle-Slot.

Server-Review R8 auf e54 bestätigt N3 durch eigene Ausführung:
210-Delete Parent echter 10s-Abbruch, Ziel 187ms, 006 77ms;
211 eindeutige Paare und Kontextaufrufe bei etwa 30ms.
200k-Ziel-Delete 41ms mit allen drei Indizes. Inserts bleiben teuer
(006 7,49s, Parent 18,66s, Ziel 20,42s).
899 SQL-Assertions, tatsächlicher Backfill, 38 Rennen/sechs Denials,
176 Transitionen und 86 Batchprüfungen selbst erneut grün.
493 zusätzliche Alt-Payload-Varianten bestätigen Kandidatenvollständigkeit
und Indexaufbau. Kein Wiederauftreten der bisherigen Identitätsbefunde.

Neu bestätigt ist jedoch ein P2 im gesamten 007-Slice, bereits im Parent:
Der prooflose Preflight prüft Event-`player.id` und `playerId`, aber nicht
Genesis-`lineup.A/B`. Positiv global gelöschte Player-IDs können dadurch
ohne Lifecycle in neue Genesis-Events gelangen und einen ACK erhalten,
für neue wie bestehende E0-Matches. Beide RPCs, String-/Objektformen:
acht Konstellationen mit 16 roten Ergebnis-/Zustandsassertions.
Kein zusätzlicher Klartext-Leak nachgewiesen. Gesunde Genesis, frischer
Proof und korrekt abgewiesene V2-Referenz sind grüne Kontrollen.
Maßgebliche Evidence:
`%TEMP%/review-e54-independent-20261020-astra/probe-genesis-complete.sql`,
`owned-genesis-complete.log`, `owned-genesis-parent.log`, `review-report.txt`.

Folgeauftrag `advantly-20261020-server-genesis-preflight-fix-r9`
bleibt auf die drei Serverdateien beschränkt. Worker reproduziert zunächst
den vollständigen Gegenbeweis auf unverändertem e54; keine Gradle-Aufrufe
parallel zu Restore. N3 bleibt als unabhängig behoben dokumentiert.
Beide Slices bleiben bis Korrektur und frischer Review unintegriert.

### Orchestrator-Neustart nach Modellausfall

Die bisherige Codex-Orchestrierung (`gpt-6-astra`) ist nicht mehr verfügbar;
ihre beiden letzten Delegationen (Restore-Widerruf-Fix, Server-N8) sind ohne
Ergebnis beendet. Root steht unverändert auf `83d2c15`, gestagete Serverdateien
entsprechen weiterhin `3d1c761`; `85c852f` und `58637f7` bleiben unintegriert.

Review R9 auf `85c852f` bestätigt N3- und Genesis-Fix, findet aber P2 N8:
Ein Batch kann mit `participants:[]` den eigenen legitimierenden Profilkontext
entfernen und im selben Batch betroffene Events ohne Lifecycle-Beleg schreiben.
Bericht: `%TEMP%/review-85c-independent-20261020-astra/review-report.txt`.

Review auf `58637f7` bestätigt zwei P2 bei nebenläufigem Widerruf (ungefangene
`CloudException` im Auth-Collector; `release`/`hold` ersetzt gespeicherten
I/O-Fehler durch `Check failed`). Der Worktree `t3-963a5699` enthält dazu
uncommittete Produkt- und Teständerungen ohne Nachweislauf.

Neu gestartet unter Claude (Opus 5.5), Fortsetzung in denselben Worktrees:
- `advantly-20261020-restore-collector-release-fix-r6-resume` (hält Gradle-Slot)
- `advantly-20261020-server-batch-context-fix-n8-resume` (nur SQL/PG, kein Gradle)

Restore-Fix `6c58267` auf `58637f7` liegt vor: zwei Produktdateien, drei Tests.
Worker: 5 echte Baseline-Reds (Collector 2, Sessionarbeit 2, androidApp 1),
danach 199/146/127 Modultests grün, voller `check --no-build-cache --rerun-tasks`
704 Tasks grün. Evidence `t3-963a5699/build/58637f7-fix-evidence/`.
Der Integrator hat den Produktdiff gegen das Modell gelesen. Frische Gesamt-Review
`advantly-20261020-restore-independent-review-r6` auf `e3e96e1..6c58267` läuft.
Server-N8 arbeitet weiter ohne Gradle. Keine Integration.

Server-N8-Fix `bbc676e` auf `85c852f` liegt vor, genau die drei Serverdateien.
Modell: gemeinsamer Helfer `redaction_push_event_affected`; neues
`guard_push_lifecycle_context` vor der Eventschleife in `sync_push` bewertet
prooflose neue Events für Matches mit mitgesendeter Teilnehmerliste erneut auf
dem Stand nach den Teilnehmerwrites; betroffen → 40001 mit vollständigem Rollback.
Bewusste Abweichung: Prüfung vor dem ersten Event-Write, nicht vor allen Writes;
Atomarität nur durch Rollback. Worker: Original-Probes 12 Reds auf Parent,
danach 115/115; 220 SQL 1296/1296; Python Exit 0 mit 50 Rennen; breite Suite
2779/2791 mit exakt den 12 bekannten Abweichungen; N3-Probe 0,12 s.
Nicht nachgewiesen: eigenes N8-Lockrennen, Python-Regressionen 180–210.
Logs `%TEMP%/n8-impl-154132/`. Frische Gesamt-Review
`advantly-20261020-server-independent-review-r10` läuft. Keine Integration.

Beide Opus-Reviews (Restore r6, Server r10) scheiterten am Claude-Ratelimit
ohne Bericht; Codex ist inzwischen deaktiviert. Neustart mit Sonnet 5.5 in den
bereits angelegten Checkouts `review-6c58267` und `review-bbc676e`:
`advantly-20261020-restore-independent-review-r6-sonnet`,
`advantly-20261020-server-independent-review-r10-sonnet`.

Die Opus-Review R10 wurde nach dem Ratelimit automatisch fortgesetzt und
ist abgeschlossen: **Freigabe für `bbc676e`**, kein P1/P2, zwei P3.
Eigene Ausführung: N8-Probes Parent 103/115, Ziel 115/115; Umgehungsprobe
366/366 (Parent 160 rot); eigenes Lockrennen 24/24; N3 0,13 s; breite Suite
2791 Assertions mit exakt den 12 bekannten Abweichungen; Python 180–220 Exit 0;
SECDEF/ACL/RLS geprüft. Bericht
`%TEMP%/review-bbc676e-independent-20261020/opus55-solo/review-report.txt`.
P3-1: Nachprüfung ruft den Helfer vor dem Matchfilter für jedes prooflose
Event auf (80k Teilnehmer/500 Events: 8,5 s statt 5,7 s bei 10 s Limit).
P3-2: Sequenzlücken nach Rollback sind unschädlich, zu dokumentieren.
Die Sonnet-Ersatzreview wurde abgebrochen. Enger Folgefix
`advantly-20261021-server-n8-prefilter-fix-r11` läuft (Vorfilter, Kommentar).
Restore-Review r6 (Sonnet) läuft weiter.

Restore-Review r6 (Sonnet) auf `e3e96e1..6c58267`: **Freigabe mit Auflagen**,
kein P1, kein funktionaler P2. Eigene Ausführung: alle fünf 6c58267-Reds
reproduziert; Hold-Probe (3× retry, 400 Tage, Neustart, SDK-Login) hebt den
Hold nicht auf; neuer Catch verschluckt keinen echten Fehler; fokussierte
Suiten aller betroffenen Module mit `--rerun-tasks` grün; iOS-Klibs kompilieren.
Bericht `review-6c58267/build/review-report.txt`.
Integrator-Entscheidung zu den beiden Auflagen:
- P2-1 (CloudSessionGate-/close()-Härtung in commonMain): war in Runde r2 für
  den DB-freien Abbau ausdrücklich freigegeben und in r3–r5 geprüft; Normalpfad
  unverändert, Fehler-Aggregation first-wins/suppressed ist beabsichtigt. Akzeptiert.
  `androidx.sqlite` als direkte Abhängigkeit stammt aus `e04919a` (Inventar geprüft).
- P2-2 (DB nicht vom Backup ausgeschlossen): entspricht dem Recovery-Paket
  Punkt 2 (übertragenen Bestand erhalten und sperren, Marker in noBackupFilesDir).
  Keine Änderung; in der Hostdoku festzuhalten.
P3-1 (retry auf gesunder Installation entzieht Bootstrap dauerhaft), P3-2
(send nach hold wirft → Prozessabsturz im Widerrufsrennen) und P3-3 (toter
Wiederverwendungszweig in WearSessions.prepare) gehen als enger Folgefix
`advantly-20261021-restore-p3-fix-r7` an Restore (hält den Gradle-Slot).
P3-4–P3-7 bleiben als Hinweise ohne Blocker dokumentiert.

Server-Vorfilterfix `c967cfb` auf `bbc676e` (nur Migration 00700): Events werden
in einer `MATERIALIZED`-CTE auf Matches mit Teilnehmerliste eingeschränkt, bevor
der Kontext-Helfer läuft; Sequenzlücken nach 40001 sind dokumentiert. Worker:
Helferaufrufe ohne Teilnehmerliste 500 → 0; 80k/500-Upload ohne Köpfe 9,1 s →
5,2 s (Parent 5,9 s); N8-Probes 115/115; 220 SQL 1296/1296; Python 180–220
Exit 0; breite Suite mit exakt den 12 bekannten Abweichungen.
Logs `%TEMP%/p3fix-guardctx-20261021/logs/`.
Offenes Volumenrisiko: Batch mit Teilnehmerliste für dieselben Matches bei 80k
Teilnehmerzeilen ≈ 8,7 s (seit bbc676e unverändert); Abhilfe wäre die Trennung
von `redaction_other_profile_context` nach `p_match` null/nicht null. Kein
CI-Aufrufzähler (track_functions nur als Superuser).
Der Integrator hat den Diff gelesen und die drei Serverdateien aus `c967cfb`
bytegleich (modulo CRLF) in Root übernommen; eigener SQL-Gesamtlauf läuft,
Log `%TEMP%/advantly-root-sql-integration-20261020/full-r6-integrated.log`.

Eigener Root-SQL-Lauf auf dem integrierten Serverstand `c967cfb` (mit den sieben
korrigierten Alt-Erwartungen): 37 Dateien, 18461 geplante Assertions, exakt die
fünf bekannten Plattformlücken rot (110 #15/#16, 200-platform #1–#3);
080 37/37, 210 191/191, 220 1296/1296 grün. Kein Timeout, kein SQL-Fehler.
Log `%TEMP%/advantly-root-sql-integration-20261020/full-r6-integrated.log`.
Server-Integration (Migration, 220, 080/210-Erwartungen, CI-Aufruf) wird nach
dem gemeinsamen Gradle-Check committet; Restore hält den Slot noch.

### Lokal integrierter Stand nach Restore r6 und Server R10

- Restore-P3-Fix `79452e8` auf `6c58267`: retry() nur bei gesperrter
  Installation, send() nach hold() verwirft still, toter Wear-Zweig entfernt.
  Worker: zwei echte Reds, danach Data 111 / Match-Session 147 / Wear 349 /
  Phone 128 grün, voller Check grün. Evidence `t3-963a5699/build/6c58267-p3-evidence/`.
- `b759749`: Serverintegration (Migration 00700 bei `c967cfb`, 220-Tests,
  080/210-Erwartungen, CI-Aufruf des 220-Concurrency-Skripts).
- `14fd09a`: Restore-Slice `e3e96e1..79452e8` als Patch übernommen
  (43 Dateien, `git apply` ohne Konflikt, Basisdateien identisch).
- Gemeinsamer Root-`check --no-build-cache` grün: 2m28s, 704 Tasks
  (670 ausgeführt). XML: Data 326, Match-Session 282, Cloud 288 (1 Stack-Skip),
  Presentation 292, Phone 128, Wear 349 ohne Fehler.
  Log `%TEMP%/advantly-root-restore-server-check.log`.
- Root steht zwölf lokale Commits vor `origin/main` (`ed5126b`). Kein Push,
  kein PR, keine Aktivierung. Dokumentationsänderungen bleiben uncommittiert.

Offen vor Freigabe/Aktivierung (unverändert): Readiness-Gate mit G/E/H-
gebundenem Vollständigkeitsbeleg, D1-/V4-Verdrahtung mit Seitenaufteilung
unter 64 KiB, Welle 3 (Session-Zustellung, Namensauflösung), SAME-UID-
Wiederherstellung, anonyme Adoption und Re-Pairing, echter Supabase-Stack und
CI einschließlich Apple-Pfaden, Hardware-Backup/Restore-Abnahme, Volumenrisiko
der Kontext-Nachprüfung bei Teilnehmerlisten für dieselben Matches.

## Plan nächster Schritt (21.10.2026)

Befund im Code: `CloudUploadRunner` sendet noch keinen `lifecycle`-Beleg
(seine `generation` ist die lokale Auth-Generation). `CloudPullRunner` kennt
Redaktionsrückstände, aber kein an G/E/H gebundenes Readiness-Gate. Der
Wasserstands-RPC liefert keinen Vollständigkeitsbeleg. Welle 2 ist damit offen.

0. Integrationspunkt A abschließen: echte CI auf dem Root-Stand (Supabase-Stack,
   Apple-Pfade) über Push/Draft-PR – braucht Nutzerfreigabe. Parallel eine
   unabhängige Review der Gesamtintegration `ed5126b..14fd09a`.
1. Vertrag vor Dispatch (Integrator, committet): Upload-Wire `lifecycle =
   {generation, redactionEpochs}` und 40001-Abbildung; Form des neuen
   G/E/H-gebundenen Readiness-RPC; Seitenaufteilung 500 Events → ≤64 KiB.
2. Welle 2 parallel, höchstens drei Agenten:
   - Server: Migration 00800 mit Readiness-RPC; optional Trennung von
     `redaction_other_profile_context` nach `p_match` (Volumenrisiko). Kein Gradle.
   - D1: `CloudPullRunner`/`CloudPullStore`/`CloudPull.sq`, Readiness erst nach
     Revalidierung, ABSENT erst nach vollem Katalog.
   - U4: `CloudUploadStore`/`CloudUpload.sq`/`CloudUploadRunner`, Snapshot an
     Kontext und G/E, Prüfung vor Versand und Ack, Retry nach Server-Commit.
   Gradle-Slot seriell zwischen D1 und U4.
3. Integrationspunkt B: beide Pakete auf gemeinsamer Basis, echte Stackprüfung.
   Protokoll 4 und Hintergrundbetrieb bleiben inaktiv.
