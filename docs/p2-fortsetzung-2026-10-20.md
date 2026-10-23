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

## Vertrag Welle 2 (Integrator, verbindlich vor Dispatch)

Umgesetzt in cloud-sync, noch ohne Aufrufer:

- `CloudHttp`: `restart_required` (SQLSTATE 40001) wird zu
  `CloudException("restart_required", permission = false)`. Bisher wurde der
  Grund zu einem generischen Fehler. Belegt durch einen echten Red ohne Fix.
- `CloudPullPort.pullReadiness(attempt, CloudReadinessRequest)` →
  `cloud_match_readiness_v1(p_match_id, p_generation, p_redaction_epoch,
  p_high_watermark default null)`. Antwort `CloudMatchReadiness`: matchId, G, E,
  H, `serverVersion` des Matchkopfs, `watermarks` je Gerät für Events mit
  `serverSeq <= H`, aufsteigend nach Geräte-ID, höchstens 256 Einträge.
  Clientprüfung: Kontext gleich Anfrage, H gleich angefragtem H (falls gesetzt),
  eindeutige kanonische UUIDs, `eventCount >= 1`, Summe der Events `<= H`.
  Fence vor und nach dem Abruf wie beim Redaktions-RPC.

**Server (Migration 00800):** `cloud_match_readiness_v1` als SECURITY DEFINER mit
leerem search_path. Reihenfolge: `lock_sync`, `require_cloud_binding`,
Mitgliedschaft vor jeder Epochenauskunft (`forbidden`/42501), Parameterprüfung
(`invalid_cursor`), G = `profile_settings.visibility_epoch` und E =
`matches.redaction_epoch` sonst `restart_required`/40001, H fehlt → aktuelles
`max(server_seq)`, H > Maximum → `invalid_cursor`, mehr als 256 Geräte →
`readiness_too_large`. Nur `authenticated` erhält EXECUTE.

**U4 Upload-Beleg** (korrigiert nach U4-Review r1, P1-1/P2-1/P2-2/P2-3):
- G = zuletzt abgeschlossenes `CloudPullProgress.visibilityEpoch` des Profils.
  Eine neu laufende Runde ohne `targetEpoch` macht dieses G nicht ungültig.
  Ist `targetEpoch` gesetzt, wird lokal BLOCKED gemeldet, ohne Versand.
  Ohne jemals abgeschlossene Runde wird `lifecycle = null` gesendet.
  Die targetEpoch-Regel hat Vorrang, auch vor der ersten abgeschlossenen Runde.
  Ohne G werden lokal bekannte Matches mit E > 0 übersprungen statt sicher 40001.
- E-Eintrag für JEDES im Batch referenzierte Match. Mit Access-Zeile gilt:
  MEMBER, Generation = G, E = angewandte Epoche, kein offener Transfer, sonst
  BLOCKED. Ohne Access-Zeile wird E = 0 gesendet. Der Server weist ein falsches E
  ohne Writes ab. Zusätzliche Einträge für dem Server unbekannte Matches sind
  zulässig. Player-only: G und leere Map.
- G und E stammen aus demselben Konto. Weicht `ReplicaContext.accountKey` vom
  Upload-Profil bei bekanntem G ab, ist das Ergebnis BLOCKED. Ohne G gibt es
  kein G/E-Paar; Altinstallationen ohne `identity.profile` laden weiter.
- Prüfung bei Auswahl, unmittelbar vor Versand und vor dem Ack. Bei Abweichung
  wird der Snapshot verworfen, ohne Acks und ohne Revisionsbestätigung.
- `restart_required` sperrt den Upload PROFILWEIT, bis eine danach begonnene
  Pull-Runde abgeschlossen ist. Kein Rechteverlust. Die Sperre ist persistent,
  an das Profil gebunden und bei Fehlern fail-closed, aber wiederherstellbar.
- Filter vor dem SQL-LIMIT. Global redigierte Identitäten werden nie hochgeladen.
- Data wird nur über öffentliche Funktionen gelesen; keine Änderung an
  `CloudPull.sq` oder `ReplicaLifecycle.sq`.
- Release-Gate: 00700 und 00800 erst ausrollen, wenn die App U4 und den
  Pull-Runner tatsächlich betreibt. U4 allein liefert ohne Pull nie ein G.

**D1 Readiness:**
- Ready für (Match, G) erst, wenn alle vier Bedingungen gelten: (1) Access MEMBER
  bei G; (2) für E > 0 ist der Redaktionstransfer (G, E, H) vollständig angewendet,
  bei E = 0 ist H aus `pullReadiness(highWatermark = null)` festgehalten;
  (3) der lokale Eventbestand deckt den Readiness-Vektor bis H exakt ab;
  (4) der materialisierte Snapshot entspricht `serverVersion`.
  Erst dann wird `markReady` unter Fence gesetzt.
- Leere Antworten, `more=false` oder fehlende Wasserstände sind nie Readiness.
- G/E-Wechsel, `restart_required`, A → B → A, Purge und Neustart invalidieren
  laufende Handles. Neustart verlangt erneute Quellenautorisierung.
- ABSENT erst nach vollständigem Katalogabgleich. Eine Cloud-Seite mit bis zu
  500 Events wird in die bestehenden begrenzten Data-Seiten aufgeteilt.
  Die 64-KiB-Wiregrenze gehört zur Gerätezustellung in Welle 3.

Besitz: Server nur `supabase/migrations/20261021000800_*`, `supabase/tests/database/230_*`
und den CI-Aufruf. D1: `CloudPullRunner`, `CloudPullStore`, `CloudPullState`,
`CloudPull.sq` und direkte Tests. U4: `CloudUploadStore`, `CloudUpload.sq`,
Auswahlabfragen in `CloudMetadata.sq`, `CloudUploadRunner` und direkte Tests.
Gemeinsame Wire-/Port-Dateien und `ReplicaLifecycleStore` gehören dem Integrator.

### Gestartet am 21.10.2026

- Draft-PR #74 (`p2/d2d3-completion` → `main`) für echte CI; Stand `ba97663`.
- Gesamt-Review des Integrationsstands: `advantly-20261021-integration-a-review-r1` (auf `4705efd`).
- Welle 2 Server: `advantly-20261021-w2-server-readiness-r1` (Migration 00800, ohne Gradle).
- Welle 2 D1: `advantly-20261021-w2-d1-readiness-r1` (hält den Gradle-Slot).
- U4 folgt, sobald ein Agentenplatz frei wird; Gradle-Slot danach seriell.

### Integrationsreview A (r1) auf `4705efd`

Ergebnis fix-first: kein P1, ein P2, drei P3. Bericht
`review-4705efd/build/review-report.txt`. Alle sechs Nahtstellen sind in Ordnung:
Restore-Fences vor Schema-Migration und Backfill, Fences am Redaktions-RPC,
keine Umgehung der Namensmasken über try*-Pfade, Wire-Feldnamen, CI-Verdrahtung
von 220 und Doku-Aussagen. Eigene SQL-Läufe: 080, 210 und 220 grün, 50 Rennen.

- **P2-1 Release-Gate:** Ohne `lifecycle` lehnt 00700 jeden Batch mit 40001 ab,
  der ein Match mit E > 0 berührt. Das gilt auch für reine Score-Events. Der
  heutige Client sendet keinen Beleg und sortiert nach Match-ID, also blockiert
  ein solches Match alle folgenden Uploads. Entscheidung des Integrators: Die
  Serverregel bleibt, weil sie Teil des Redaktionsvertrags ist. **00700 und 00800
  werden erst zusammen mit einem Client nach staging oder prod ausgerollt, der
  den Beleg sendet (U4).** U4 behebt außerdem den Head-of-Line-Block.
- P3-1: Fixtures und Vertragstests für Redaktions-RPC, `lifecycle` und 40001 fehlen.
  `ba97663` deckt die 40001-Abbildung ab; Fixtures folgen mit Server 00800.
- P3-2: Ein fehlerhafter Alt-Redaktionsbeleg lässt jedes Event-Append scheitern
  und beendet auf Android den Prozess. Das wird erst bei Aktivierung relevant
  und gehört vor V4 in die D1-/Session-Abnahme.
- P3-3: Maskierte leere Namen können über MatchMeta auf das andere Gerät gelangen.
  Das gehört in Welle 3 (Namensauflösung und MatchMeta-Quellautorität).
- U4 läuft jetzt: `advantly-20261021-w2-u4-lifecycle-r1`.

Server-Readiness `112a65b` als `9219316` übernommen: Migration 00800,
230-pgTAP mit 58 Prüfungen, Readiness-Fixture und repariertes `generate.psql`.
Der Zähler in 181 steigt von 33 auf 34 gebundene RPCs. Das ist nötig, und die
Consent-Proben erfassen den neuen RPC dynamisch. Worker: Mutationsprobe mit
8 Reds, EXPLAIN mit Indexnutzung (21 ms bei 10k Events), Python 210 und 220
Exit 0. Root-SQL-Lauf: 38 Dateien, nur die fünf bekannten Plattformlücken rot,
181 257/257, 220 1296/1296, 230 58/58. Log
`%TEMP%/advantly-root-sql-integration-20261020/full-r7-readiness.log`.
`WireFixturesTest.matchReadinessResponse` ist ergänzt, aber noch nicht gelaufen,
weil D1 den Gradle-Slot hält. Nicht nachgewiesen: Lock- und Lasttests speziell
für den Readiness-RPC sowie Matches mit 256 Geräten.

U4 `b659f6c` (Worktree `t3-61493ba9`) liegt vor: Beleg nach Vertrag, Überspringen
blockierter Matches vor dem LIMIT, 40001 als dauerhafte Sperre bis zur nächsten
abgeschlossenen Pull-Runde, redigierte Identitäten in SQL gefiltert, keine neue
Schemanummer, eine Zeile in `LocalData.kt`. Baseline 8/10 rot, final 216 Tests
grün. Der volle Check steht noch aus, weil D1 den Slot hält. Offene Fragen für
die Review `advantly-20261021-w2-u4-review-r1`: kein Beleg während laufender
Pull-Runden, Player-only mit 40001 in Schleife, öffentliches `deferUntilPull()`.

U4-Review r1 auf `b659f6c`: **fix-first**, 1× P1, 4× P2, 5× P3. Bericht
`review-b659f6c/build/review-report.txt`. Der P1 kam aus dem Vertrag selbst:
Matches ohne Access-Zeile bekamen keinen E-Eintrag, der Server verlangt ihn aber
für jedes bekannte Match. Der Integrator hat den U4-Abschnitt oben korrigiert.
Bestätigt in Ordnung: Ack nur für den gesendeten Stand, U3-Bestätigungen bleiben
erhalten, BLOCKED ist von DRAINED getrennt, das Keyset nutzt den Index, die Reds
sind echt. Korrekturrunde `advantly-20261021-w2-u4-review-fix-r2` im selben Worktree.
Release-Gate erweitert: Ohne Pull-Runner in der App gibt es kein G, also müssen
U4 und der D1-Host-Anschluss gemeinsam ausgerollt werden.

U4-Korrektur `3c3640a` auf `b659f6c` (nur CloudUploadStore, CloudUploadRunner und
Test): E-Eintrag für jedes referenzierte Match, Mock mit 00700-Regel, letztes G
auch während laufender Runden, profilweite Restart-Sperre per Rundennummer,
Kontoabgleich bei bekanntem G, P3-Fixes. Baseline 16/21 rot, darunter der
Pflichtfall „zweiter Batch eines neuen Matches“. Final 21/21, cloud-sync 229,
data 212 grün. Voller Check offen (Slot bei D1). Akzeptierte Einwände, im Vertrag
ergänzt: targetEpoch vor erster Runde → BLOCKED; Kontoabgleich nur bei bekanntem G;
ohne G werden lokal bekannte Matches mit E>0 übersprungen; Grundangabe für
BLOCKED folgt mit dem B4-Syncstatus. Frische Review
`advantly-20261021-w2-u4-review-r2`.

D1 `4ea10a1` (Worktree `t3-22027d07`, Schema 11 über `10.sqm`) liegt vor.
Zugriff vor Materialisierung, ABSENT in 200er-Transaktionen nach vollem
Katalog, an Kontext gebundene Läufe, fortsetzbarer Redaktionstransfer mit
Seitenaufteilung, Readiness nach allen vier Bedingungen, `restart_required`
höchstens zweimal pro Lauf neu. 17 echte Baseline-Reds, data 219 / cloud-sync 222
grün, voller Check grün. Gradle-Slot zurückgegeben.
Fehlende Schnittstelle: `ReplicaLifecycleStore.markNotReady`. Steigt E bei
gleichem G, bleibt das Match bis zur ersten Redaktionsseite lesbar. Wird vor
der D1-Review im D1-Worktree mit Integratorfreigabe ergänzt.
`WireFixturesTest.matchReadinessResponse` ist grün (5/5).

U4-Review r2 auf `3c3640a`: fix-first, kein P1. Alle Befunde aus r1 sind behoben.
Bericht `review-3c3640a/build/review-report.txt`. Reds echt, E=0 ohne Access-Zeile
ist sicher (der Server vergleicht E exakt), Sperrfreigabe und Kontotrennung sind korrekt.
- P2-A: Der Restart-Schlüssel wird nie gelöscht. Dadurch blockiert jede spätere
  aktive Runde erneut, und laufende Uploads werden doppelt gesendet. Korrektur
  `advantly-20261021-w2-u4-review-fix-r3`.
- P2-B, Gate für Integrationspunkt B: Ein Match mit Server-E>0 ohne lokale
  Access-Zeile stoppt per profilweiter Sperre alle Uploads in jeder Runde.
  Nachweis: D1 legt für jedes referenzierbare Match Access an und schließt den
  Transfer ab. Dazu ein gemeinsamer Integrationstest D1 + U4.
- Einwand (4), BLOCKED ohne Grund: akzeptiert nur mit B4-Syncstatus als Gate vor
  der Aktivierung.
- Serverbeobachtung: Die Triggerbereinigung bei E=0 erhöht E nicht, der Schreiber
  behält den Klarnamen. Prüfung `advantly-20261021-w2-server-trigger-epoch-r1`.

CI auf `f636fda`: Supabase-pgTAP läuft auf dem echten Stack vollständig grün,
einschließlich 181, 210, 220 und 230. Rot war nur `181_cloud_consent_gate_concurrency.py`:
Sein eigenes RPC-Inventar erwartete noch 33 statt 34 gebundene RPCs. Der
Server-Worker hatte nur das SQL-Gegenstück angepasst, und der lokale SQL-Runner
führt dieses Skript nicht aus. Korrigiert, `cloud_match_readiness_v1` wird
zusätzlich ausdrücklich erwartet. Lokal 181 und 190 Exit 0
(`regression-races-r7.log`). Lehre für neue gebundene RPCs: beide Inventare
(181 SQL und Python) anpassen.

U4-Korrektur r3 `b54d56e` auf `3c3640a`: Die Restart-Sperre greift nur, bis die
erste nach dem 40001 begonnene Runde beendet ist. Zwei echte Reds (Folgerunde
BLOCKED, laufender Upload verlor seinen Ack), Gegenprobe grün; final
CloudUploadLifecycleTest 24/24, cloud-sync 232, data 212 grün. Der Integrator hat
den Diff selbst geprüft: `checkRound` steigt nur beim Rundenstart ohne aktive
Runde. Das gilt unverändert auch auf dem D1-Branch (`beginRun`). Damit ist
`b54d56e` der U4-Endstand für Integrationspunkt B. P3-2 (Seitenrest je blockiertem
Match) ist als `shortcut:` dokumentiert und wird vor B4 nachgezogen.

D1-Folgefix `24cef57` auf `4ea10a1`: `ReplicaLifecycleStore.markNotReady`
(mit Integratorfreigabe), Aufruf im selben Commit wie die Katalogseite, sobald E
bei gleichem G steigt. Ein echter Red (`[true,true]` statt `[false,false]`),
data 220 / cloud-sync 223 grün, voller Check grün, Slot zurückgegeben.
Vom Worker gemeldet: Die cloud-sync-PullFixture aktiviert den Replikat-Store nie,
`canReadMember` ist dort also immer true. Frühere Lesbarkeitstests beweisen deshalb
möglicherweise nichts. Unabhängige Review `advantly-20261021-w2-d1-review-r1` auf
`24cef57`, mit Prüfauftrag bei aktiviertem Store. Probe-Merges D1↔Root, U4↔Root
und D1↔U4 sind konfliktfrei.

Server-Prüfung Triggerbereinigung: Der Befund ist echt. Mit gültigem Beleg und
E=0 bereinigt der Server Namen in neuen Events still, ohne Signal an den Client.
Fix `1af7403` (Worktree `t3-d94eeffc`, Basis `f636fda`): Migration 00900 mit
AFTER-INSERT-Trigger, der E einmal pro Transaktion und Match erhöht, wenn
`redacted_at` gesetzt ist. Nur Events; Teilnehmer bleiben offen, weil der Client
eigene Metadaten gewinnen lässt (Welle 3 / D1). Probe 240: 15/43 rot vorher,
43/43 grün nachher; breite Suite, Python 210/220 und N3 grün. Unabhängige Review
`advantly-20261021-w2-server-trigger-epoch-review-r1` läuft.

### D1-Review r1 auf `24cef57` und Vertragsergänzung

Ergebnis fix-first: kein P1, zwei P2, sechs P3. Bericht `review-24cef57/build/review-report.txt`.
Eigene Prüfung mit aktiviertem Store: Bindung an G/E/H und Snapshot, sofortiger
Leseverlust bei FEED, ABSENT, E-Anstieg und Transferstart, fortsetzbare Transfers,
korrekte Behandlung eigener ungesendeter Events. Mit erzwungener Aktivierung der
ganzen Fixture scheitern 16 von 64 Tests, alle nur am Testaufbau.

**Vertragsergänzung D1 (Integrator):**
- **G-Wechsel (P2-1):** In derselben Transaktion, die einen Scan mit neuem G
  beginnt, verlieren alle Access-Zeilen mit älterem G sofort `ready`
  (neue Sammelfunktion in `ReplicaLifecycleStore`, freigegeben).
- **Übernahme statt neuem RPC (P2-2):** Bestätigt eine Katalogseite unter neuem G
  ein Match als MEMBER mit unverändertem E und unveränderter `serverVersion` des
  zuletzt bewiesenen Snapshots, wird Readiness im selben Commit übernommen. Der
  Readiness-Vektor bis H hängt nicht von G ab. Nur geänderte Matches brauchen
  einen neuen Readiness-Abruf.
- **Endliche Runden:** Jede Runde prüft jedes Match gegen ein fest gehaltenes H.
  Fremde laufende Live-Matches dürfen den Rundenabschluss nicht verhindern.
  Sonst gibt U4 seine Restart-Sperre nie frei (Querbefund der Review).
- **Altbestand (P3-1):** `10.sqm` setzt den Katalogcursor zurück, damit ein
  vollständiger Katalog bei gleichem G die Access-Zeilen anlegt.
- **Tests:** Die Lesesperre (FEED, ABSENT, G-Wechsel, E=0) wird mit aktiviertem
  Store belegt. Dazu kommen direkte Tests für die H-Grenze, veraltete Snapshots
  und die Übernahme, sowohl positiv als auch mit geändertem E bzw. geänderter
  `serverVersion`.
- P3-2: Ein bereits lesbares Match bleibt während einer Lückenreparatur lesbar.
  Das ist vorerst akzeptiert und wird vor V4 erneut bewertet.

Server-Review zu `1af7403`: **Freigabe**, kein P1/P2, fünf P3. Bericht
`%TEMP%/review-1af7403-20261021/review-report.txt`. Eigene Ausführung: 240 Parent
15/43 rot, Ziel 43/43; Lockrennen zweier Geräte (B wartet, dann 40001 ohne Writes,
E steigt genau einmal, kein Deadlock); Marker transaktionslokal, auch bei
Savepoint-Rollback und mehreren Matches; Transfer liefert bei E+1 genau das
bereinigte Event; keine messbaren Zusatzkosten ohne Bereinigung. Mit D1 und U4
bleibt kein Gerät hängen; U4 allein ohne D1-Access-Zeilen würde hängen (Gate P2-B).
Übernommen in Root als Cherry-Pick. Root-Lauf: 39 SQL-Dateien, nur die fünf
Plattformlücken rot; 240 43/43; Python 181, 190 und 220 mit Exit 0 (50 Rennen).
Log `full-r8-trigger.log`. Offene P3:
- E steigt auch bei bereits kanonischem Namen.
- Ein privilegiert vorbelegter Marker unterdrückt den Schritt.
- Das Lockrennen und der Marker-Fall gehören als Python-Rennen ins Repo.
- Der D1+U4-Integrationstest muss „eigenes Match, Server bereinigt, E 0→1“ enthalten.
- 00900 wird nur zusammen mit 00700 ausgerollt.

CI auf `10dbb6f` (PR #74): alle fünf Jobs grün, darunter Gradle check mit
Android/Wear-APKs, Kotlin/Native mit iOS/watchOS-Simulator-Builds sowie Supabase
mit Migrationen, pgTAP, Lockrennen, Export-Rundlauf, echten Auth/HTTP/Storage-Diensten,
Vault-Scheduler und Datenbank-Lint. Erstmals ist der Stand damit auch auf dem echten
Stack und den Apple-Pfaden belegt. Integrationspunkt A ist nachgewiesen.

### Integrationspunkt B (begonnen)

- D1-Korrektur `9bea930`: G-Wechsel entzieht Readiness sofort, unveränderte
  Matches werden ohne RPC übernommen, endliche Runden, Neuscan aus 10.sqm,
  wiederhergestellter Altbeleg. 7 Reds, 6 erkannte Mutationen, data 221 /
  cloud-sync 233 grün. 205 Matches sind nach einem G-Wechsel wieder lesbar nach
  1 statt 61 Läufen; COMPLETE weiterhin nach 61. Migrationstest vom Integrator
  geprüft: weiterhin exakt bis Schema 10, danach werden nur die zwei
  zurückgesetzten Tabellen getrennt und exakt geprüft. Frische Review
  `advantly-20261021-w2-d1-review-r2`.
- Lokaler Branch `p2/d2d3-integration-b` auf `10dbb6f`: Merges von D1 und U4
  ohne Konflikt. Voller Check grün in 3m11s (data 221, cloud-sync 258, 1 Stack-Skip).
- Integratorentscheid: Die U4-Restart-Sperre wird mit dem abgeschlossenen
  Katalog einer danach begonnenen Runde frei, nicht erst mit dem Rundenabschluss.
  Gemeinsamer D1+U4-Test mit Mock-Serverregeln 00700/00800/00900:
  `advantly-20261021-integration-b-joint-r1` (hält den Gradle-Slot).
- D1-Review r2 auf `9bea930`: **Freigabe**, kein P1/P2, sechs P3. Bericht
  `review-9bea930/build/review-report.txt`. Die Übernahme ist sicher, weil jede
  E-Änderung auf dem Server über `touch_row` auch `serverVersion` erhöht. Der
  Altbeleg ist eng begrenzt, Runden enden endlich, `restart_required` führt in
  keinen Dauerzustand. Für die U4-Kopplung an metadataComplete gilt: Das
  angewandte E steigt erst mit dem abgeschlossenen Transfer. U4 muss daher ein
  Match blockieren, solange das Katalog-E größer ist als das angewandte E. Und
  die nächste Runde beginnt erst nach der laufenden. Beides wurde an den
  Integrationsagenten weitergegeben.
  Offene P3:
  - Turnier-Staff-Wechsel ohne Beobachtung.
  - Übernahme prüft weder Tombstone noch `materialization_pending`.
  - Vollreparaturen durch fremde Live-Matches beim Neuscan.
  - Spaltenprüfung von `cloud_pull_profile` im Migrationstest.
  - Keine eigene Mutation für den Altbeleg und keine positive Übernahme bei E>0.
  - Kein aktiver Test für ein eigenes ungesendetes Event an einem bereiten Match.
- Gemeinsamer D1+U4-Test `b245a63` (Worktree `t3-87683ce2`) per Fast-Forward
  auf `p2/d2d3-integration-b` übernommen. Mock-Server mit den Regeln aus
  00700/00800/00900, sechs Szenarien bei aktiviertem Store grün (a/b waren
  rot gegen `9beefa6`). U4: Ist das Katalog-E größer als das angewandte E,
  wird nur dieses Match gesperrt. Die Restart-Sperre endet mit dem Katalog
  (`roundCatalogReady`) einer danach begonnenen Runde und wird dabei gelöscht.
  Der Integrator hat den Produktdiff geprüft. Messung bei 205 Matches: Uploads
  wieder möglich nach 1 statt 62 Läufen bei 40001 zwischen Runden und nach
  60 statt 120 bei 40001 mitten in einer Runde. Für ~2 Läufe bräuchte es einen
  monotonen Katalogzähler (nicht gebaut, Vorschlag im Bericht).
  data 221 / cloud-sync 267 grün, voller Check grün.
- **Neuer P1 vor Aktivierung:** Ein eigenes LOCAL-Match wird nach dem ersten
  Pull dauerhaft unlesbar und nimmt keine Events mehr an.
  `materializePullMatch` überschreibt keinen LOCAL-Kopf, `materializedVersion`
  bleibt leer, Readiness endet mit STALE_SNAPSHOT. Ohne aktivierten Store gibt
  es heute keine Nutzerwirkung, die Replikataktivierung bleibt aber gesperrt.
  Folgeauftrag an D1.
- P1-Fix `51ca91b` (Worktree `t3-a33c2e30`, Basis `b245a63`): Ein eigener
  LOCAL/PEER-Kopf wird nur dann vom Cloud-Kopf übernommen, wenn die Quelle dem
  Konto gehört, Kopf, Teilnehmer und Projektion per Ack bestätigt sind und kein
  anderes Konto materialisiert hat. Ursache war allein der fehlende Snapshotbeleg;
  die Eventabdeckung war bereits exakt. Joint 13/13 (vorher 8 rot),
  CloudPullOwnMatchTest 5/5 (vorher 2 rot), data 226 / cloud-sync 273,
  voller Check grün. Review `advantly-20261021-integration-b-local-match-review-r1`.
- **Aktivierungsblocker (Vertrag Welle 3):** Zwischen Katalogseite und Readiness
  ist auch das eigene Match nicht lesbar und nimmt keine Events an. Mit
  unbestätigten Kopfänderungen, etwa einer laufenden Projektion, dauert die
  Sperre bis zum Upload und zum nächsten Katalog. Ein eigenes Live-Match wäre
  während eines Pulls nicht zählbar. Vor V4 braucht es ein Modell, das Live-Zählen
  an eigenen Matches nie blockiert und trotzdem keine fremden oder unredigierten
  Daten freigibt.
- Review zu `51ca91b`: fix-first, 1× P1, 2× P2, 3× P3. Bericht
  `review-51ca91b/build/review-report.txt`. Bestätigt sind der Fix für ruhende
  eigene Matches, die UID-basierte Eigentumsprüfung und die Ack-Logik (ein älteres
  Ack bestätigt nie einen neueren Stand). Die Reds sind echt.

**Vertragsergänzung eigene Matches (Integrator):**
- **Schreibspur (P1-1):** Die aktive eigene Quelle darf an eigenen Matches
  unabhängig von `ready` anhängen und Projektion oder Kopf aktualisieren.
  Voraussetzung: Die Quelle gehört per UID dem aktuellen Konto, und Access
  fehlt oder ist MEMBER bei aktuellem G. FEED, ABSENT, ein fremdes Konto und
  der Verification-Hold sperren weiterhin.
- **Lesespur:** Eigene Matches sind ohne `ready` nur dann lesbar, wenn keine
  Redaktion aussteht, also angewandtes E ≥ Katalog-E und kein offener Transfer.
  Abdeckung und Snapshot betreffen dort die Integrität, nicht die Vertraulichkeit.
- **Kopf nicht überschreiben (P2-1/P2-2):** Im own-Pfad schreibt der Pull den Kopf
  nicht. Der Snapshot gilt nur dann als materialisiert, wenn die vom Server
  getragenen Felder dem bestätigten lokalen Stand entsprechen. Eine veraltete
  Katalogseite dreht nie zurück, und lokale Kopffelder (`firstServer`,
  `receiveRight`, `startEnds`, `updated_at_ms`) bleiben erhalten.
- **Host-Gate (P3):** Pull läuft nie vor abgeschlossener Restore-Prüfung, weil
  der Eigentumsnachweis aus `cloud_device_owner` stammt.
- CI auf PR #75 (`16d20b8`, Integrationsstand D1+U4 vor dem P1-Fix): alle fünf
  Jobs grün, einschließlich Kotlin/Native/Apple und Supabase-Stack. Die
  Korrektur für eigene Matches (`advantly-20261021-integration-b-local-match-fix-r2`)
  folgt nach ihrer Review.
- Korrektur eigene Matches `ef23ad6` auf `51ca91b` (nach einer Ratelimit-
  Unterbrechung automatisch fortgesetzt): Der Pull schreibt eigene Köpfe nie;
  ein Beleg entsteht nur bei Ack-Bestätigung und feldgenauem Vergleich
  (`carriesOwnHeader`). Schreib- und Lesespur nach Vertrag, Migration 11.sqm
  legt die Sichten neu an, Schema 12. Reds: Lane 10/11, Joint 4/16, OwnMatch 1/5.
  Danach data 237, cloud-sync 276, match-session 135 grün, voller Check grün.
  Der Verification-Hold ist im Data-Layer nicht sichtbar, also Host-Gate.
  Offene Punkte:
  - `appendAll` mehrerer eigener Geräte scheitert bei gesperrtem Lesen an der
    Genesis-Sperre (Live-Zählen mit Uhr).
  - Die Lesespur nimmt Events jeder Quelle an.
  - Die Schreibspur ist nach neuem G bis zur Katalogseite gesperrt.
  - Möglicher Mock-/Server-Unterschied bei einem nach Kontolöschung hochgeladenen
    Gast.
  Review `advantly-20261021-integration-b-local-match-review-r2` und Server-Probe
  `advantly-20261021-server-late-guest-probe-r1` laufen.

### Vertragsänderung: späte Referenzen auf global redigierte Spieler

Server-Probe `advantly-20261021-server-late-guest-probe-r1` (Belege
`%TEMP%/advantly-late-guest-probe-20261021/`): Ein Upload, der Gast X erst nach
der Löschung des verknüpften Profils referenziert, trägt X nie in
`match_redacted_players` des neuen Matches ein. Der Transfer liefert dann
`redactedPlayerIds: []`, obwohl der Server X als global redigiert kennt. Der
Client behält den Verzeichnisnamen, weil nur Manifest-IDs das Verzeichnis
bereinigen. So ist es heute gewollt (00700 füllt das Manifest nur beim Löschen;
220 Z. 1190 und 240 erwarten event-only), es verfehlt aber das Löschziel.

**Entscheidung des Integrators:** Beim INSERT von `match_events` (Legacy, V2,
Genesis-Lineup) und `match_participants` wird (match, X) genau dann in
`match_redacted_players` (NICHT in `match_redacted_names`) eingetragen und E einmal pro
Transaktion erhöht, wenn der globale Zweig gilt:
`redacted_player_identity(X) and not redaction_live_profile_context(match, X)`.
Reine `player_write_redactions`, Profil-IDs, matchlokale Namen und
ID-Kollisionen (220 Z. 1214) bleiben event-only. Das Manifest enthält damit
weiterhin nur positiv belegte globale Spieler-IDs, unabhängig vom Uploadzeitpunkt.
Der Client bleibt unverändert. Die Erwartungen in 220 und 240 werden gezielt
angepasst, alle übrigen bleiben bestehen.

Review r2 zu `ef23ad6`: fix-first, 1× P1, 3× P2, 4× P3. Bericht
`review-ef23ad6/build/review-report.txt`. Bestätigt: Der Kopf wird nicht
überschrieben, kein Zurückdrehen, Eigentum per UID, Sperren bei FEED, ABSENT,
fremden Konten und A→B→A, 11.sqm wiederholbar, Reds echt.

**Vertragsergänzung eigene Matches, Teil 2 (Integrator):**
- **P1-1:** Im Fenster mit offener Schreibspur und geschlossener Lesespur folgen
  `maxLamport`, `maxDeviceSeq` und `markConfirmed*` der Schreibspur
  (`readable || ownWritable`). Sonst vergibt die Session seq 1, der Store meldet
  SEQUENCE_CONFLICT, und der Punkt geht still verloren.
- **P2-1:** Die Genesis-Sperre in `ScopedEventStore` wird nur nach einem echten
  Genesis-Durchlauf gesetzt. Gemischte Batches von Uhr und Handy funktionieren.
- **P2-2:** Während eines laufenden Neuscans akzeptiert die Schreibspur auch
  MEMBER beim zuletzt vollständigen G. Die Lesespur bleibt streng. Begründung:
  Ersteller bleiben Mitglied ihrer eigenen Matches; „nie blockieren“ hat beim
  Live-Zählen Vorrang.
- **P2-3:** Abgeleitete Kopffelder (Status, Summary, ein vom Server verworfener
  Vorgänger) werden aus dem Cloud-Kopf übernommen, wenn drei Dinge gelten: die
  serverVersion ist strikt neuer als der zuletzt bestätigte bzw. belegte Stand,
  es gibt keine unbestätigten lokalen Änderungen, und lokale Felder
  (`firstServer`, `receiveRight`, `startEnds`, `updated_at_ms`, meta) bleiben
  unberührt. Es wird nie zurückgedreht, und unbestätigte Änderungen gehen nie
  verloren.
- P3: Fremde Peer-Events an eigenen Matches sind kein Leck, aber eine
  Integritätslücke; beide Spuren hängen am Host-Gate für den Restore;
  ein alter Beleg kann nach einer lokalen Kopfänderung stehen bleiben.

Server-Paket `4a5b64c` (Worktree `t3-11e329d8`): Migration 01000 mit
AFTER-Triggern auf Events und Teilnehmern, gemeinsamer Marker mit 00900, Test
250 (87 von 293 rot vorher) und ein Rennen mit 16 Läufen ohne Deadlock. Keine
messbaren Kosten (N3 0,124 s). Jede geänderte Alt-Erwartung ist begründet.
Integratorentscheid zur Nebenfolge 220 Z. 1235: nur `match_redacted_players`.
Ein `names`-Eintrag wäre ein dauerhafter matchlokaler Beleg und würde spätere
Events einer lebenden Person mit eindeutigem Profilkontext redigieren, also
genau die Klasse aus N1–N7. Korrektur samt roter Gegenprobe läuft im selben
Worktree. Offen (P3): Ein fremder Gast als Teilnehmer wird vom Writer mit
`player_not_owned` verworfen und bekommt keinen Eintrag. Altbestand vor 01000
wird nicht nachgetragen.

Eigene Matches, Korrekturrunde 3 `0e3d6d2` auf `ef23ad6` (Schema 13 über
`12.sqm`): Sequenz, Lamport und Bestätigung folgen der Schreibspur. Die
Genesis-Sperre greift nur nach echter Genesis. Die Schreibspur gilt während
des Neuscans beim zuletzt vollständigen G. Abgeleitete Kopffelder werden nur
übernommen, wenn `own_version`/`own_revisions` am Snapshot es zulassen.
Reds: Lane 7/18, Joint 1/17, Session-Test (Handy-Seqs `[1,2]` statt `[1,2,3,4]`).
Danach data 244, cloud-sync 277, match-session 136 grün, voller Check grün.

**Für Welle 3 (Session, Besitzer `MatchSession.kt`):**
- `emit` muss `AppendResult` auswerten; ein SEQUENCE_CONFLICT darf nicht stumm bleiben.
- `onMessage` muss die erwarteten `IllegalStateException`-Fälle des Stores
  behandeln (fremde Quelle, FEED während eines laufenden Matches).
- Im Fenster „schreiben ja, lesen nein“ liefert die Anti-Entropie der Uhr keine
  verpassten Handy-Events.
- Nach einer Kopfübernahme braucht ein offenes Match einen `refreshHeader`-Hook.

**Host-Vertrag B4:** Pull und U4 laufen je Konto streng nacheinander. Sonst kann
eine vor einem eigenen Ack geholte, danach committete Seite eine ältere Fassung
übernehmen (Restlücke P2-3; der nächste Lauf korrigiert sie). Das Restore-Gate
bleibt Pflicht, weil beide Spuren nur aus der Datenbank aufgehen.
- Review r3 zu `0e3d6d2`: fix-first nur wegen eines kleinen P2, sonst
  freigabefähig. Bericht `review-0e3d6d2/build/review-report.txt`. Eigene
  fokussierte Läufe grün, Reds echt. Kein stiller Punktverlust mehr (auch nach
  Neustart, bei Uhr-Echo und bei gemischten Batches). Übernahme und Quittieren
  verlieren auch ohne Host-Serialisierung nie eine unbestätigte Änderung.
  `current_g` wird bei jedem Lesen neu berechnet.
  - P2-A: Der vom Server beim Anlegen verworfene Vorgänger überschreibt einen
    lokal vorhandenen, noch nicht hochgeladenen Vorgänger dauerhaft.
  - P3-A: Ohne Ack seit dem Beleg wird jede höhere serverVersion übernommen,
    auch mit weniger Events.
  Beides geht in die enge Korrekturrunde 4 im selben Worktree.
  - P3-C geht an Welle 3: Ändert ein zweites eigenes Gerät Meta oder Teilnehmer,
    entsteht kein Beleg mehr und der Pull meldet dauerhaft BLOCKED (das gehört zur
    MatchMeta-Quellautorität). Lesbar bleibt das Match über die Lesespur.

CI auf PR #75 (`0531c60`): Gradle rot in genau einem Test,
`emptyPullOverLargeHistoryReadsNeitherGlobalPayloadsNorUnboundedMatchIds`, mit
`DEADLINE_REACHED` statt `COMPLETE` nach 15,4 s. Auf `16d20b8` brauchte derselbe
Test auf CI schon 10,9 s, lokal unverändert 3,4–4,2 s. Ursache ist die implizite
Produktionsfrist von 15 s auf geteilten Runnern, keine Produktregression. Der
Test prüft Lesemuster und erhält deshalb eine explizite Frist von 120 s. Das
Fristverhalten decken eigene Tests ab. Der ABSENT-Pfad mit 20 000 Feed-Snapshots
bleibt als Volumenbeobachtung offen: Er braucht auf CI mehrere Läufe, setzt aber
korrekt fort.

Server-Review zu `77b6317`: **Freigabe**, keine P1/P2 im Diff. Bericht
`%TEMP%/review-77b6317-20261021/review-report.txt`. Eigene Prüfung: 250 auf dem
Parent 70/303 rot, gegen 4a5b64c 22 rot (echte Gegenprobe), Ziel grün. Alle
geänderten Alt-Assertions liegen im globalen Zweig. Elf eigene Gegenproben ohne
Wiederöffnung einer N-Klasse. Kein E-Anstieg durch unveränderte Upserts. Der
01000-Trigger feuert vor 00900, also kein doppelter Anstieg beim Upload. Inserts
ohne Treffer kosten etwa 4 % mehr. Per Merge `fee71b3` übernommen. Root-SQL-Lauf
grün bis auf die fünf Plattformlücken; 250 303/303; 181/190 Exit 0.

**Entscheidungen des Integrators:**
- P3-1/P3-2: Der UPDATE-Trigger auf Teilnehmern feuert nur, wenn `player_id` auf
  einen Nicht-null-Wert wechselt. Der Teilnehmer-Extraktor folgt exakt der
  Löscherfassung in 00700 (Z. 285–286). Die Migration 01000 ist unveröffentlicht
  und wird direkt korrigiert. P3-3: Genesis-Manifestinhalt in 220 prüfen.
- **Vorbestehender P1 (Produktionsrisiko für den Löschjob):** Eine Kontolöschung
  läuft bei vielen Textslot-Teilnehmern ins 10-s-Limit (2 Self-IDs: 6 s bei 20 000
  Zeilen, Timeout bei 50 000). Vermutete Ursache sind globale Scans mit
  `redaction_uuid(slot_id)` in 00700 Z. 285/303/558. Wird ein eigener
  Performance-Auftrag nach dem N3-Muster (Ausdrucksindex/Vorfilter, Budgets
  unverändert).
- 01000-Nachschärfung `d1f0722` (Worktree `t3-1497f727`), vom Integrator geprüft
  und per Fast-Forward übernommen: Der UPDATE-Trigger feuert nur bei einem
  Wechsel von `player_id` auf einen Wert ungleich null, und der Teilnehmerpfad
  folgt der 00700-Regel (lebender Profil-FK schließt aus). Kein zweiter E-Schritt
  bei der Kontolöschung. Der Genesis-Manifestinhalt ist in 220 geprüft (1324).
  6 von 318 neuen 250-Assertions waren gegen 77b6317 rot. Root-SQL-Lauf
  `full-r10-late-p3.log`.
- CI auf PR #75 (`7799f1c`): alle fünf Jobs grün. Das gilt für D1, U4, eigene
  Matches, 00800, 00900 und 01000 samt Nachschärfung, für den Lesemustertest mit
  expliziter Frist, für Kotlin/Native/Apple und für den Supabase-Stack mit pgTAP
  und allen Rennskripten einschließlich 250.
- Löschpfad-Performance `b854b9d` (Worktree `t3-b90a697f`): Ursache belegt.
  `redaction_uuid` lief 100 027-mal im Ausnahmepfad (6,1 von 6,2 s) mit Seq Scans
  in 00700 Z. 285/303/558, dazu globale Kontextaufrufe je pensionierter ID.
  Migration 01100: partieller Ausdrucksindex auf UUID-förmige Slots, UNION-Zweige
  statt OR, Kandidatensuche vor dem unveränderten Kontext-Helfer. DELETE mit
  2 Self-IDs ~11 ms und mit 205 ~106 ms bis 100 000 Textslots, vorher Timeout ab
  20 000/50 000. Schattenvergleich alt/neu: 100 Löschungen, 0 Abweichungen, zwei
  Mutationen erkannt. Keine Erwartungsänderung in 210/220/240/250. Probe-Merge
  mit `d1f0722` konfliktfrei. Unabhängige Review
  `advantly-20261021-server-delete-textslot-perf-review-r1` läuft.
- Review zu `b854b9d`: **Freigabe**, kein P1/P2, vier optionale P3. Bericht
  `%TEMP%/review-b854b9d-20261021/review-report.txt`. Eigener Schattenvergleich
  mit 26 Schreibweisen und Grenzfällen (N7, nur über Events erreichbare IDs, leere
  Matches, Profil-FK auf das Opfer): 180 Löschungen, 360 Vergleiche, 0
  Abweichungen, mit freiem Planer und mit erzwungenem Index. Mutationen erkannt.
  Indexgrenze 32–41 per 200 000 Fuzz-Mutationen belegt. Rechte identisch, keine
  messbaren Upload-Kosten. Per Merge `b238556` übernommen. Root-SQL-Lauf grün bis
  auf die Plattformlücken; 260 18/18; 181/190 Exit 0.
- Folgeauftrag mit einem Worker nacheinander:
  - P3-1 Join-Patch im Epochen-Update von 01100 (unveröffentlicht, direkt korrigiert)
    sowie die Kommentare P3-2/P3-3;
  - **vorbestehendes Löschrisiko:** `anonymize_profile` (00800) skaliert mit der
    eigenen Historie, 7,7 s bei 25 000 eigenen Teilnehmerzeilen, gesamt 10,9 s.
- Folgeauftrag erledigt (Worktree `t3-b90a697f`), noch nicht übernommen:
  - `a780e7c`: 01100-Feinschliff. Epochen-Update über `id = any(array(select distinct …))`,
    stabil auch bei veralteter Statistik (1,5 s → 0,12 s). 260 hat 22 Fälle.
    Schattenvergleich 638/0.
  - `0780489`: Migration 20261022000100. Die Kontolöschung wuchs **quadratisch** mit
    der eigenen Historie: Kontext-Helfer ohne Index, wiederholtes `bump_visibility`
    auf derselben Zeile, fehlender Index auf `previous_match_id`. Jetzt linear:
    10k 20 s → 10 s, 25k 102 s → 24 s, 50k Abbruch → 46 s. Schattenvergleich der
    ganzen Löschung 48/0, neue Datei 270 mit Lesezählungen.
  - Löschjob: Er geriet in einen dauerhaften Stillstand (Abbruch nach 60 s, Rollback,
    dieselben Konten im nächsten Lauf). Jetzt beginnt er nach 30 s keine weitere
    Löschung mehr.
  - **Offen und Architekturentscheidung:** Konten mit mehr als ~10 000 eigenen
    Teilnehmerzeilen überschreiten 10 s, Konten über ~60 000 auch die 60 s des Jobs.
    Ein einzelnes solches Konto blockiert die Warteschlange. Rund 1 ms je Zeile
    entfallen auf die Zeilentrigger; mit identischer Semantik gibt es keinen engen
    Weg darunter. Die Optionen (gestaffelte Löschung, jobspezifische Frist, …)
    bewertet die Review `advantly-20261022-server-delete-history-review-r1`.
- Review zu `a780e7c`/`0780489`: A und die Semantik von B sind freigegeben.
  Bericht `%TEMP%/review-0780489-20261022/review-report.txt`. Eigener
  Schattenvergleich 48/0. Ein dynamischer Differenztest über Suite und Rennen
  verglich 3 865 Helferaufrufe ohne Abweichung. Alle 2 096 übersprungenen
  `bump_visibility` lagen innerhalb einer laufenden Profillöschung; kein anderes
  Konto verliert eine Erhöhung. Rechte identisch.
  - **P2-1:** Die 30-s-Grenze garantiert keinen Fortschritt (A 20 s + B 45 s ergibt
    einen Dauerstillstand). Enge Korrektur im selben Worktree: Unterblock je
    Löschung, Fehlversuchszähler, gescheiterte Konten ans Ende, Alarm. Dazu P3-1:
    Neuanlage einer Profil-ID mit Grabstein ablehnen.
  - **P2-2, vorbestehend und grundsätzlich:** `lock_sync` ist EIN globaler Lock.
    Jede Kontolöschung blockiert für ihre ganze Dauer Lesen und Schreiben aller
    Nutzer. Bei 15 s Löschdauer brachen fremde Pulls nach 8 s ab.

**Integratorentscheid, Zielarchitektur Kontolöschung (vor der Pilot-Freigabe):**
gestaffelte Löschung (Option a der Review). Zuerst ein kurzer atomarer Schritt:
Status, Sitzungen, Gerätebindungen und alle Grabsteine bzw. Schreibsperren. Ab da
ist das Konto nicht mehr nutzbar, und keine Identität ist wiederherstellbar.
Danach folgen kurze Batches über mehrere Transaktionen, jeder Batch mit nur kurzem
`lock_sync`. Zum Schluss das kurze DELETE. Zwischenzustände sind für Clients nie
als halb gelöscht sichtbar und sind fortsetzbar. Gründe: Es ist die einzige Option,
die den globalen Stillstand (P2-2) beseitigt, sie hat keine Größengrenze und wirkt
sofort im Sinn der DSGVO. Ein jobspezifisch höheres statement_timeout bleibt
höchstens eine Notlösung im Wartungsfenster. Das wird ein eigenes Server-Paket mit
eigener Review; die Zwischenzustände sind neu zu testen.

**Übernahme `3701ab9` (Folgeauftrag P2-1/P3-1), gemergt nach `p2/d2d3-integration-b`:**
- Löschjob: Auswahl vorab als Liste unter `lock_sync`, sortiert nach Fehlversuchen,
  Frist und ID. Jede Kontolöschung läuft in einem eigenen Unterblock, die Schleife
  endet beim ersten Fehler. Abgeschlossene Löschungen bleiben; die abgebrochene rollt
  vollständig zurück. Neue Tabelle `private.account_deletion_failures`, die mit dem
  Profil kaskadiert. `account_deletion_job_status_v1` meldet `stalledAccountCount`
  (Konten ab drei Fehlversuchen), nur als Anzahl.
- Fristmechanik (gemessen, PG 17): `statement_timeout` feuert einmal je Statement.
  `SET LOCAL` stellt keinen neuen Timer, eine Frist je Konto ist also nicht setzbar.
- P3-1: `handle_new_user` lehnt getilgte Profil-IDs ab (`profile_id_retired`), sonst
  identisch mit 00500.
- Tests:
  - 190: erwartet keinen atomaren Batch mehr.
  - 220: prüft die Ablehnung statt der Wiederbelebung.
  - 280: neu, Maßstab 1:12; gegen `0780489` sind 14 von 19 Prüfungen rot.
- Eigene Durchsicht (Integrator): Diff schmal und schlüssig, kein Client liest das
  Status-RPC, keine neue gebundene RPC (181 unverändert).
- Bleibt offen: Konten über dem Limit, aber jetzt sichtbar und nicht mehr blockierend.
  Ebenso Verhungern bei ständigem Zulauf und P2-2. Alles wird mit der gestaffelten
  Löschung erledigt.
- Root-SQL-Suite auf dem Merge: Rot sind nur die 5 Plattformlücken (110, 200). 260, 270 und 280 sind grün,
  181 und 190 enden mit Exit 0. Log: `%TEMP%/advantly-root-sql-integration-20261020/suite-3701ab9.log`.

**Nutzerentscheide 22.10.2026:** Endgültiges Löschen allein beseitigt den globalen
Stillstand nicht, weil die Arbeit unter `lock_sync` bleibt. Ein nächtlicher Lauf wurde
erwogen; entschieden ist „gestaffelt weiterbauen“, Variante **„pro Match atomar“**:
- **A, Sperren:** kurz und atomar.
- **B, Abtragen:** je Job-Aufruf (~1 s Budget) werden Blöcke von Matches vollständig
  redigiert, mit Epochenanstieg in derselben Transaktion.
- **C, Abschluss:** kurzes `purge_account`.
- Der Cron läuft alle 5 s.

**Übernahme gestaffelte Kontolöschung (`2bba3c8..b18aa2e`), Merge `904a7c2`:**
- Neue Migration `20261022000200_staged_account_deletion.sql`, neue Tests 290 (pgTAP,
  90 Prüfungen) und `290_…_concurrency.py` (in CI). 190 und 280 sind angepasst.
- **Runde r2** (`2bba3c8`, `2ecefd1`): Grundumsetzung. Ein 100k-Konto hält die Sperre
  höchstens etwa 1,2 s je Aufruf statt 398–822 s am Stück.
- **Review r1:** freigegeben mit Auflagen.
  - P2-1: Ein scheiterndes A hielt alle Löschungen an.
  - P2-2: Staff verlor in A den Zugriff ohne neues G; der Client wirft bei gleichem G
    und anderer Stufe.
  - P3: Zwei gleichzeitige Löschungen mit gemeinsamen Matches wichen im Endzustand ab.
- **Runde r3** (`c27ecae..bb5b6a2`):
  - Fehler isoliert, mit gemeinsamer Reihenfolge nach Fehlversuchen.
  - `bump_visibility` für Staff und Co-Leitung in A.
  - A wartet auf überlappende laufende Löschungen; `settle` im Dashboard-Weg.
  - Die Blockgröße halbiert sich bei Überlauf und wächst wieder bis 50.
- **Review r2:** nicht freigegeben.
  - P1: `account_deletion_blocker` war quadratisch, weil der Planner einen Nested Loop
    über `unnest` des Parameters wählte. Zwei überlappende größere Löschungen hielten
    `lock_sync` bis zu 60 s.
  - Der Hänger der vollen Schatten-Suite war ein Harness-Artefakt (Ballast in einer
    stundenlangen Transaktion), kein Produktfehler.
- **Runde r4** (`9bc754d..b18aa2e`):
  - Blocker mit `enable_nestloop = off`: 30k × 30k sinkt von 41,5 s auf 14 ms.
  - Szenario 100k in B, daneben wird ein 20k-Konto fällig: Die längste Sperre je
    Aufruf sinkt von 21,8 s auf 1,7 s.
  - Wartestelle `private.account_deletion_waits`; zurückgestellte A zählen aufs Budget.
  - `settle` arbeitet nur bis zum letzten gemeinsamen Match.
  - Das Budget zählt ab Erhalt der Sperre, nicht ab dem Aufruf. Diese Änderung war
    nicht beauftragt; sie ist übernommen, weil sie die Sperrhaltezeit misst, um die es
    geht.
- **Review r3:** **freigegeben mit Auflagen.**
  - Kein P1 und kein P2.
  - Drei überlappende Konten gleichen `purge_account`; die Gegenprobe ohne Wartestelle
    weicht ab.
- **Schatten je Testdatei:** Endzustand gleich `purge_account`, 0 G-Verletzungen in
  6 011 Prüfschritten.
- **Integratorentscheid, Ausnahme vom identischen Endzustand:** Hat die überlappende
  andere Löschung drei oder mehr Fehlversuche, wartet A nicht mehr (Anti-Verhungern).
  - Garantiert sind dann nur: keine Klarnamen, jede Namensänderung mit höherer
    Epoche, G-Invariante.
  - Nicht garantiert sind einzelne Zeilen (`match_redacted_names`, Höhe von
    `redaction_epoch`).
  - Der Fall steht unter Alarm (`stalledAccountCount`). Tests 290/82–85.
- **Für das Betriebshandbuch:**
  - Ein Dashboard- oder Admin-DELETE neben einer großen laufenden Löschung schließt
    deren Matches bis zum letzten gemeinsamen Match in EINER Transaktion unter
    `lock_sync` ab. Gemessen: 9,2 s bei 10k; bei 100k sind Minuten bzw. das
    60-s-Limit möglich.
  - Das DELETE scheitert, solange der Block der anderen Löschung scheitert und diese
    unter drei Fehlversuchen hat.
  - `admin_purge_due_accounts` bleibt ungestaffelt und sperrt global. Kein Client ruft
    es auf.
  - Der Index `match_events_author_idx` entsteht ohne `CONCURRENTLY`; dafür ein
    Wartungsfenster einplanen.
- **Bekannte Restpunkte (P3, nicht blockierend):**
  - Eine veraltete Wartestelle nach Abbruch und Neuantrag verzögert A bis zur alten
    Position, nie früher. Möglicher Fix: `cancel_account_deletion` räumt die
    Wartestelle ab.
  - Kein automatisierter Test für das Budget ab der Sperre.
  - Kein Test mit drei Konten im Ausnahmepfad.
  - `tournament_overview` hat keinen Kotlin-Client, Realtime ist seit `20261018000100`
    geschlossen. Beim Wiederöffnen müssen A und `purge_account` `access_epoch` der
    Turniermatches mit erhöhen.
- **Auflage vor der Pilot-Freigabe:** CI auf dem Supabase-Stack muss grün sein. Test 290
  nutzt im Aufbau `alter table public.matches disable trigger`; das ist lokal belegt,
  in CI noch nicht.
- **Release-Gate:** 20261022000200 gehört zum Gate von 00700/00800/00900/01000.
- Root-SQL-Suite auf `904a7c2`: Rot sind nur die 5 Plattformlücken (110 ×2, 200 ×3).
  290 hat 90/90, 181 und 190 enden mit Exit 0. Log:
  `%TEMP%/advantly-root-sql-integration-20261020/root-suite-904a7c2.log`.
- CI auf PR #75 (`826d29b`, Lauf 39840815447): alle 5 Checks grün. pgTAP 44 Dateien,
  3 397 Tests; 290 grün inklusive `disable trigger`. Die CI-Auflage ist erfüllt.

## Welle 3 – festgeschriebener Vertrag (22.10.2026)

Grundlage ist die lesende Analyse `advantly-20261022-w3-contract-analysis-r1` auf
`826d29b`. Bericht mit Datei:Zeile-Belegen: `%TEMP%/advantly-w3-20261022/contract-r1.md`.

**Ist-Stand:**
- V4 ist modelliert, aber ganz aus (`ProtocolVersion.CURRENT = MIN_SUPPORTED = 3`).
- Die Data-Zustell-API (`registerPeer`, `pendingDeliveries`, `ackAccess`/`ackRedaction`,
  Redaktionsseiten, `activate`) hat keinen Produktionsaufrufer.
- Die Session hört nicht auf Data-Änderungen, `emit` ignoriert `AppendResult`,
  und `refreshHeader` gilt nur für nicht offene Matches.
- **Neuer Befund:** `pendingToPeers` zählt nur Events der Lesespur. Eigene
  ungesendete Punkte im Fenster „schreiben ja, lesen nein“ sind unsichtbar
  (`ScopedEventStore.kt:67–71`).
- Eine Data-Redaktionsseite (64 KiB ohne Hülle) ist als Wire-Paket mit Envelope zu groß,
  und `SyncCodec.encode` wirft dann.

**Schnittstellen-Commit des Integrators** (vor dem Dispatch, `SessionState.kt`):
- `SessionState.lifecycleRevision: Long`: ein Zähler im Speicher, der nur auf
  Ungleichheit verglichen wird. Er steigt bei jeder Access-, Redaktions- oder
  Directory-Änderung aus Data. Konsumenten verwerfen dann alle abgeleiteten Namen.
- `SyncStatus.lifecyclePendingToPeers: Int` (Handy), `SyncStatus.lifecycleBlocked:
  Boolean` und `SyncStatus.settled`.
- Alle Felder haben Defaults. S füllt sie, P konsumiert sie. Ändern darf sie nur der
  Integrator.

**Entscheidungen des Integrators** (Empfehlungen der Analyse übernommen, wenn nicht anders vermerkt):
- **E1:** S darf `SyncCodec.decode` und die Session um einen Parameter mit Default
  für den zulässigen Versionsbereich ergänzen, damit V4 in Tests lesbar ist. Die
  Konstanten bleiben beim Integrator und auf 3, bis aktiviert wird. Abweichung von der
  Analyse: kein Vorab-Commit des Integrators nötig.
- **E2:** Die Uhr aktiviert ihr Replikat in derselben Transaktion wie die erste
  gültige Access-Zustellung vom bestätigten Handy (S, mit Test). Den Host-Schalter, ob
  V4 überhaupt läuft, setzt der Integrator.
- **E3:** Den Seitenstand hält das Handy im Speicher. Nach einem Neustart sendet es ab
  Index 0 neu; die Uhr verarbeitet wiederholte Seiten idempotent. Keine Wire-Erweiterung.
- **E4:** `WearSessions.isSyncSettled` setzt P um, über `SyncStatus.settled`.
- **E5:** Der Glance-Cache speichert keine Namen mehr; der Kaltstart löst sie neu auf.
- **E6:** P3-C: Zuerst schreibt S einen roten Repro-Test. Danach Variante (a): Teilnehmer-
  autorität bei Handy bzw. Genesis-Quelle, ohne Data-Edit.
- **E7:** `registerPeer` erhält die Watch-`deviceId` aus dem bestätigten Hello.
- **E8:** Bei E=0 bestätigt das Handy die Redaktion mit dem `AccessAck` (MEMBER bei G)
  oder sobald `Summary.lifecycle.redactionApplied >= E` meldet.
- **E9:** Die Uhr setzt `markReady(G,E)` erst, wenn drei Bedingungen erfüllt sind:
  MEMBER bei G, angewandter Stand ≥ gemeldetem E und kein offener Transfer.
- **D-2** (öffentliches `canAppend`) entfällt; S fängt die erwarteten ISE.

**Pakete** (exklusive Dateien, laut Analyse §7):
- **S – Session/Zustellung:**
  - Besitz: `shared/match-session/**`, darin allein `MatchSession.kt`.
    Neue Logik kommt in eigene Dateien (`ReplicaDelivery.kt`, `ReplicaReceiver.kt`).
  - Ausnahmen in `sync-protocol`: der E1-Parameter und reine Byte-Batching-Helfer in
    `AntiEntropy.kt`.
  - Inhalt:
    - Zustellung von Access und Redaktion mit Wire-Aufteiler und Acks je Seite.
    - Stamps und Envelope-Prüfung.
    - Invalidierung über `observeDirectoryChanges` mit Fingerabdruck je offenem Match;
      `refreshHeader` auch für offene Matches.
    - Live-Zählen: schließen nur bei zugesperrter Schreibspur.
    - `AppendResult`/ISE in `emit` und `receive`.
    - MatchMeta-Quellautorität (P3-3) und P3-C.
    - Byte-Batching für Events.
- **D – Data-Ergänzung D-1:**
  - `unconfirmedCountByMatch`, `unconfirmedMatches`, `unconfirmedEvents` und
    `sequences` folgen bei eigenen Quellen der Schreibspur.
  - Fremde Quellen bleiben streng an der Lesespur.
  - Besitz: `ScopedEventStore.kt`, nötige `.sq`-Abfragen, internes `ownWritable` in
    `ReplicaLifecycleStore` (vom Integrator freigegeben) und Data-Tests.
- **P – Namensauflösung/Präsentation:**
  - Besitz: `shared/presentation/**`, `shared/umbrella-watch/**`, `wearApp/**` und
    `watchApp/ScoringModel.swift`.
  - `lifecycleRevision` leert alle Namens-Caches (`SessionMatchStore`, Glance,
    `WatchScoring`/`WatchDirectory`).
  - Keine Namen in Verlauf, Vorschlägen und Rematch.
  - Kein Data- oder Session-Edit.
- **Integrator:**
  - Merge.
  - Pull-Host Android (`CloudPullRunner` hat bisher keinen Aufrufer).
  - `activate` auf dem Handy und Host-Schalter.
  - `assignPlayer` namenfrei, zusammen mit V4 (nach S).
  - Versionssprung 3 → 4 und Update-Hinweis.

**Reihenfolge:** S, D und P starten parallel auf dem Schnittstellen-Commit. S schaltet
die Backlog-Tests im Schreibfenster erst nach dem Merge von D-1 scharf. Gradle läuft
nacheinander: D → S → P. Die Abnahmefälle stehen in Bericht §9.

**D-1 übernommen** (`967e79b` + `a720d301`, Merge `1f2def72`, voller Check grün):
- Review r1: Freigabe mit Auflagen, kein Datenschutzleck, Reds echt.
- P2-1 behoben: Ohne Lesespur liest die Zählung nur noch Index und Zähler, keine Payloads
  mehr. In der Probe (300 fremde Matches × 200 Events) kostet das ca. 70 ms statt 180 ms;
  die Basis lag bei 40 ms.
- P3-3 behoben: Tests für mehrere eigene Geräte und ein Gerät eines fremden Kontos.
- **Hinweis an S:** Bei ausstehender Redaktion meldet der Zähler einen Wert größer 0,
  `unconfirmedEvents` bleibt aber leer. S behandelt das als „wartet auf Redaktion“, nicht
  als Fehler.
- **Hinweis an S (P3-1):** Verborgene Events verursachen ein Ping-Pong aus Events und
  `Summary(reply)`. Mit D-1 betrifft das nur noch fremde Quellen und ausstehende Redaktion.
- **Akzeptiertes Restrisiko (P3-4):** Ein neues E wird erst im neuen G sichtbar. Bis dahin
  gibt das Neuscan-Fenster eigene Events nach dem bekannten E frei, und zwar nur an
  Gegenstellen desselben Kontos. Das entspricht der normalen Lesespur.

**P übernommen** (`29e2b1c8`..`2dcb267c`, Merge `1bafbe96`, voller Check grün):
- **Review r1:** Freigabe mit Auflagen.
  - Der vermutete Namensrückweg über `legacy`/`player_json` ist für redigierte Personen
    nicht erreichbar: Die Redaktion setzt `player_json = NULL`.
  - Der Wear-Flake `DirectUiBoundaryTest.healthyPointStillCommitsOnce` ließ sich in
    6 Läufen nicht reproduzieren und ist vorbestehend.
- **Runde 2:**
  - P-1: Ein aufgeschobenes Glance-Update übersteht das Prozessende.
  - P-2: Wizard-Plätze werden per ID neu aufgelöst.
  - P-4: „Synchron“ gilt auf Wear und watchOS nur bei `SyncStatus.settled`.
- **Offen beim Integrator (P-3):** Ein blockierter Rückstand bzw. reiner Rückstand anderer
  Matches erscheint als „1 ausstehend“. Ein eigener DE/EN-Text folgt nach S.
- **Offen für Data:** `MatchWizard.start()` wirft eine ISE, wenn ein Verzeichniseintrag
  vorhanden, aber nicht schreibbar ist (`rememberPlayers` → `addGuest`). Das wird erst
  mit V4 relevant.
- Swift (`ScoringModel.swift`) ist nur in der CI prüfbar.

**S übernommen** (`51faecf`, `b7a3b71`, `90e2746`, Merge `8c8bde95`; `WriteWindowBacklogTest`
nach D-1 eingeschaltet in `b71f2390`). Der Baum ist identisch mit dem Probe-Merge
`00850345`, auf dem der volle Check grün lief.

Review r1: Freigabe mit Auflagen. Kein Rechte- oder Namensleck gefunden. 23 von 23
neuen Tests sind gegen die Basis fachlich rot.

**Vor der V4-Aktivierung Pflicht (Runde 2 `advantly-20261023-w3-session-r2`):**
- **P2-1:** Ein Handy mit Konto und Bereich 3..4 handelte mit einer v3-Uhr Version 3 aus.
  Danach flossen nur Events, Access und Redaktion kamen nie an, und das Handy meldete
  trotzdem `settled`.
  - **Entscheidung:** Mit Konto sendet das Hello `minV = 4`. Gegenüber v3 entsteht ein
    Updatezustand, und es fließt nichts mehr.
  - Ohne Konto bleibt P1 unverändert.
- **P2-2:** Der Auth-Lease-Teil des Fence ist ungetestet. Die Mutationsproben der Review
  bleiben grün, wenn die Fence leer ist oder ganz entfällt. Fehlende Tests:
  - Neustart ohne `activateAccount`;
  - Wechsel zwischen Empfang und Mutation.

**P3 in Runde 2:**
- Ein übergroßes Einzelelement wird als blockiert gemeldet.
- `Summary.lifecycle` läuft im periodischen Takt mit, wegen Probe B (beendetes Match
  bleibt auf der Uhr bis zum nächsten Hello unbereit).
- P3-C-Repro und Prüfung der Nebenwirkung „neu installiertes Handy kann in älteren
  eigenen Matches keine Spieler zuweisen“.
- Stamp-Logik wandert nach `MatchStamps.kt`.

**Beim Integrator:**
- Data-Konstante 64 KiB öffentlich machen.
- UI-Hinweis bei scheiterndem `emit` (mit P-3).
- `activateAccount` bei jedem App-Start.
- Der LinkFrame-Overhead liegt außerhalb der 64 KiB. Der Envelope-Spielraum deckt ihn
  praktisch ab, garantiert ist das aber nicht.

**D-2** (`0d1fdd8e`, Uhr-Match am aktivierten Handy, bestätigt durch Probe D der S-Review)
ist in Review: `advantly-20261023-w3-data-d2-review-r1`.

**D-2 übernommen** (`0d1fdd8e` + `c5a00389`, Merge `53753d2a`, voller Check grün):
- Ein Match der eigenen, per `cloud_device_owner` gebundenen Uhr wird am aktivierten
  Handy angenommen, in beiden Reihenfolgen.
- Ein vorab gekommener Kopf muss zur Genesis passen (Format, Lineup, Meta,
  Erzeugungszeit).
- **Akzeptierter Rest:** `previous_match_id` eines solchen Kopfs wird nicht geprüft. Das
  ergibt höchstens eine falsche Folge-Verknüpfung, das Vorgänger-Match bleibt gesperrt.

**S Runde 2 übernommen** (`cf3adbed`..`84250307`, Merge `c68aacae`; match-session
173 Tests, 0 übersprungen):
- Mit Konto gilt `minV = 4`, dadurch Updatezustand statt Teilsync.
- Fence-Tests: Die Mutationsproben „leere Fence“ und „ohne Kontositzung“ sind jetzt rot.
- Ein übergroßes Element wird als blockiert gemeldet. Die Lifecycle-Meldung läuft im Takt
  mit.
- P3-C: BLOCKED heilt mit dem nächsten eigenen Upload. Ob der Server den
  Teilnehmer-Upload annimmt, ist erst gegen den Cloud-Stack prüfbar.
- **Entscheidung:** Regel (a) wird gelockert. Neben der Genesis-Quelle darf auch ein
  eigenes Handy desselben Kontos Spieler zuweisen. Umsetzung in Runde 3 zusammen mit
  dem Hook `eventsCommitted`: `advantly-20261023-w3-session-r3`.

**H übernommen** (`04b84124` + `559e0c8c`, Merge `b3d2fe91`, voller Check grün):
- Android-Pull-Host mit Kontosperre, prozessweit je Profil. Pull und U4 laufen
  nacheinander.
- Der Statusabschnitt zeigt BLOCKED-Gründe und Verbindungsfehler, DE und EN.
- **Ein Schalter `PHONE_REPLICA_ACTIVATION` (aus) steuert Pull und `activate`
  gemeinsam.** Ohne Aktivierung gibt es keinen Pull und keinen Button.
- Die Fence der Aktivierung prüft auch den Auth-Lease.
- `activateAccount` läuft bei jedem App-Start nach erfolgreicher Profilprüfung; offline
  erst beim nächsten `refresh()`.
- Review r1: Freigabe mit Auflagen, alle erledigt.
- **Offen:**
  - P3-5 und P3-6 (kosmetisch).
  - `eventsCommitted` anschließen, nach S Runde 3.
  - Die Reihenfolge „erst Pull, dann U4“ ist nicht erzwungen. Das gehört zu B4.

**S Runde 3 und 4 übernommen** (Merges `0d7894b1`, `91c23684`; match-session 186 Tests):
- Runde 3:
  - `eventsCommitted`-Hook; der Pull-Host verdrahtet ihn in `fe71ffa6`.
  - Atomare Löschung.
- Review r2 zu Runde 2+3 verlangte vor V4 zwei Fixes, Runde 4 hat beide umgesetzt:
  - **P2-1:** Mit Konto gehen vor der Aushandlung nur Hellos raus. Eine Sende- und eine
    Empfangssperre gelten für Gegenstellen unter v4.
  - **P2-2:** Die Teilnehmerregel ist an das Match gebunden. Belege: die Genesis-Quelle des
    Kontos oder der Server-Ersteller im Pull-Snapshot. Das Payload-`createdBy` reicht
    nicht.
- Review r3: `advantly-20261023-w3-session-review-r3`.
- **Bekannter Rest:** Unter V4↔V4 bleibt eine Seite nach einem verlorenen Hello bis zum
  nächsten Link-Up unausgehandelt; kein Wiederholungs-Hello.
- **`shortcut:`** `createdBy` wird roh aus dem Snapshot-JSON gelesen. Später durch eine
  Data-Abfrage ersetzen.

**Integrator-Commits:**
- `78d5684b`: Die Wear-Uhr zeigt „Sync blockiert“ statt „1 ausstehend“ (P-3). Auf watchOS
  steht weiter „N ausstehend“; ein eigener Text bräuchte Swift.
- `32141c3b`: `ReplicaLifecycleStore.MAX_REDACTION_PAGE_BYTES` ist die eine Grenze für
  Pull, `stageRedaction` und Gerätezustellung.
- `66c2d81c`: `assignPlayer` erzeugt mit Konto und V4 `PlayerAssigned.reference`
  (assignmentVersion 2, ohne Namen). Ohne Konto oder unter v3 bleibt es wie bisher.
- **Scheiterndes `emit`:** Handy und Wear quittieren es bereits mit Ablehnungs-Haptik
  (`LiveScoreController.kt:314`, `WatchControllerCore.kt:307`). Kein zusätzliches
  Statusfeld.

**Protokoll 4 aktiviert** (Paket A `f00e948e`..`8990ed0d` + D-3 `0e9e4e79`, `97eb2b9e`;
Merge `edeb2bee`, voller Check grün):
- **Versionen:** `CURRENT = 4`, `MIN_SUPPORTED = 3`.
  - Mit Konto gilt nur v4. Gegenüber v3 entsteht der Updatezustand, Hello ist die
    einzige Nachricht.
  - Ohne Konto (P1) spricht die Session nur v3, auch zwischen zwei neuen Geräten.
  - Ein Gerät mit Konto bleibt dauerhaft kontogebunden (`managed`), auch nach dem
    Abmelden.
- **Session-Fixes:**
  - `Summary(reply)` für ein ganz fehlendes Match trägt den geprüften Stamp zurück.
  - Bei ausstehender Redaktion gibt es keine Events-/Summary-Schleife mehr: vorher
    ~6100 Pakete/min, jetzt ~2 je Takt.
  - Hello wird im Takt wiederholt.
- **Schalter:** `PHONE_REPLICA_ACTIVATION = true`. Die Uhr aktiviert sich selbst (E2).
- **D-3:** Der Matchstart überspringt nicht schreibbare Verzeichniseinträge; keine ISE
  mehr. `CloudPullStore.matchCreatedBy` ersetzt den `shortcut:`.
- **Review A r1: Freigabe mit Auflagen**, kein P1/P2.
  - Belegt sind: keine v4-only-Inhalte an v3, auch nach Gast/Bob/Alice; nur ein
    Sendeweg; keine ungegateten Pfade.
  - A1 (Bestätigungslücke nach Matchende) und A3 (`MatchStamps.echo` je Gegenstelle)
    werden behoben in `advantly-20261023-w3-activation-r2`.
- **A2 im Vertrag (akzeptiert):** Unter V4 erreichen Live-Punkte des Handys im Fenster
  „schreiben ja, lesen nein“ die Uhr erst nach angewandter Redaktion. Übernimmt die Uhr
  in diesem Fenster das Zählen, zählt sie auf altem Stand. Die Aussage der D-1-Notiz
  („gibt eigene Events … frei“) gilt damit für den Draht unter V4 nicht. Data gibt den
  Inhalt erst nach der Redaktion frei.
- **iOS** hat noch keinen Konto-Host und bleibt bis zum Apple-Paket bei P1/v3.
- **Nur auf Geräten, macOS oder dem Cloud-Stack prüfbar (Abnahme vor Release):**
  - echte alte v3-App aus dem Store, auch im gestaffelten Rollout von Handy- und
    Wear-App;
  - Kotlin/Native, watchOS und Swift;
  - Verluste auf Wear Data Layer und WatchConnectivity;
  - Pull gegen Supabase mit Unterbrechung zwischen Katalog und Redaktion;
  - Server-Annahme von `PlayerAssigned.reference` und Teilnehmer-Uploads.
- **CI:** Test 290 hängt zeitweise auf dem Supabase-Stack (zwei Läufe nach 280 bis zum
  Abbruch nach ~55 min). Unter `supabase/` gab es keine Änderung. Untersuchung:
  `advantly-20261023-server-290-hang-r1`.

**Aktivierung Runde 2 übernommen** (`c4cba036`, `d4fc4a64`, Merge `faceddf1`):
- A1: Das Handy bestätigt die Takt-Summary einer Gegenstelle auch ohne eigenen Rückstand,
  höchstens einmal je Takt und nicht für aktive Matches.
- A3: `MatchStamps.echo` wird je Gegenstelle geführt.

**Hänger pgTAP 290 behoben** (`8863499a`, `8bf27e99`, Merge `067c028e`):
- **Ursache:** Es ist keine Wartekette und nicht der Cron-Job. 260/270 füllen Tabellen in
  zurückgerollten Transaktionen, ihr `ANALYZE` bleibt aber stehen. Autovacuum räumt
  danach, ohne zu kürzen. Ergebnis ist `reltuples = 0` bei vielen Seiten für `matches`
  und `match_participants`.
- **Folge:** Der Planner wählt in `private.anonymize_profile()` geschachtelte Schleifen
  über ganze Tabellen, das dauert bei 20 000 Matches Stunden. Lokal ist der Hänger mit
  gesetzter Statistik jedes Mal reproduzierbar. In der CI hingen 5 von 8 Läufen.
- **Fix:**
  - `vacuum` der drei Tabellen vor 290;
  - `transaction_timeout = '5min'`;
  - `timeout-minutes: 15` am pgTAP-Schritt.
  
  Ein `statement_timeout` greift hier nicht, weil die Job-Einheiten den Abbruch selbst
  fangen.
- **Kein Produktfehler,** weil Auto-Analyze die Statistik in Produktion nach wenigen
  Änderungen korrigiert.
- **Restrisiko (P3, Betriebshandbuch):** Die Übergabe-Anweisung in `anonymize_profile`
  ist bei 1-Zeilen-Schätzungen kubisch. Eine robustere Form wäre `LATERAL` je eigenem
  Match. Das ist nicht umgesetzt.

## Welle 4 – gestartet 23.10.2026

Aufträge:
- Recovery-Rest: `advantly-20261023-recovery-rest-r1`.
- B4 Android: `advantly-20261023-b4-android-r1`.
- K7: `advantly-20261023-k7-pilot-r1`.

**K7 übernommen** (`f575fb20`, `cf0fe8af`, Merge `80c56a2e`):
- Lieferumfang:
  - `docs/p2-pilot-nachweise.md` mit Nachweismatrix und Geräte-Bögen;
  - Prüfskripte unter `tools/pilot-checks/`.
- CI-Anschluss `29c266ee`: Die Skripte laufen seitdem im Supabase-Job gegen den echten
  lokalen Stack. Das ist ihr erster Lauf überhaupt.
- Skriptfix `f7e1a141`: Freundschafts-RPCs brauchen den Installationsnachweis.
- Ergebnis CI 39921434622 und 39922481919, sechs von sieben grün:
  - Release-Gate,
  - Support,
  - Widerruf,
  - Export,
  - Cloud-Upload: `PlayerAssigned.reference` und Teilnehmer vom zweiten eigenen Gerät
    werden vom Server angenommen, damit belegt,
  - Auth-Negativtests.
- **Rot ist der Löschjob, ein echter Produktbefund.** Nach der gestaffelten Löschung von B
  sieht A den Namen eines von B angelegten Gasts weiter: in `participants[].displayName`
  und in `PlayerAssigned.player.displayName`. `anonymize_profile` löscht die eigene
  Gastzeile, lässt den Namen im gemeinsamen Match aber stehen; das galt schon für das
  alte `purge_account`. B selbst ist korrekt redigiert.

**Nutzerentscheid 23.10.2026:** Gäste des gelöschten Kontos werden auch in gemeinsamen
Matches entfernt. Sie erscheinen als „Gelöschter Spieler“ und sind in Teilnehmern und
Verlauf redigiert, E steigt. Beanspruchte Gäste gehen wie bisher über. Die ID wird
stillgelegt. Umsetzung: `advantly-20261023-server-guest-redaction-r1`.
