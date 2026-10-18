# Cloud und Pilot – parallele Ausführung

Stand: 18.10.2026. Auftrag: planen und möglichst parallel umsetzen. Basis `c85ad78`
(U2, PR #61). Fachlich gelten [PLAN](PLAN.md) und der
[Lieferplan](p2-cloud-pilot-lieferplan.md); dieser Ausführungsplan ersetzt keine Pilotabnahme.

## Ausgangspunkt und Arbeitsweise

U1 und U2 sind integriert. Drei Implementierer arbeiten in getrennten Git-Worktrees;
der Integrationszweig heißt `p2/cloud-pilot-parallel`. Der Orchestrator besitzt Dokumentation,
Integrationsänderungen, Reviews und PRs. Umsetzung primär mit GPT-6.1 Sol, unabhängige Reviews
nach tatsächlichem Diff. Keine gehosteten Deployments, automatische Cloudaktivierung oder
behaupteten realen Geräte-/Datenschutzabnahmen aus diesem Auftrag ableiten.

Jedes Paket liefert Commit, Worktree, Schnittstellen und Testnachweise. Vor Commit gilt
`gradlew check`; zusätzlich fachlich passende SQL-/HTTP-/UI-Tests. Reviewer implementieren
ihre eigenen Befunde nicht. Nach Korrekturen neue unabhängige Reviewrunde; Integration nur
nach bestandener Prüfung und relevanter CI. Gemeinsame Dateigrenzen zuerst abstimmen.

## Welle 1 – parallel gestartet

| Paket | Besitz | Abnahme | Status |
|---|---|---|---|
| U3 | `shared/data`, Cloud-Upload-Snapshot-/Gate-/Backend-Seams; keine Auth/UI- oder Backend-SQL-Änderungen | Änderung während Upload bleibt ausstehend; fremde Metadaten werden nicht zurückgeschrieben; Migration ab Schema 5 erhält historische Daten und unabhängige Geräte-/Cloud-Acks | Kern `751bca8` geliefert, Review 1 `fix-first`; Korrektur der drei Befunde läuft |
| Server/K4 | Neue `20261018000100_cloud_device_binding.sql`, eigene `170_cloud_device_binding*`-Tests | Auth-Sitzung, Konto, Installation/Proof und Gerät auf allen Cloud-Wegen geprüft; Widerruf/Tombstone nicht umgehbar; alte RPCs, direkte Tabellen und Realtime geschützt oder geschlossen | Korrektur `0912e27` auf Entwurfs-PR #62; Sicherheitsreview 2 und neue CI laufen; K4-App-Bedienung folgt |
| K1 | Neue `20261018000200_consent_versions.sql`, `180_consent_versions*`-Tests, Auth-Port/Host, `presentation-auth`, Account-UI DE/EN | Version/Sprache/Zeit/Historie serverautoritativ; bewusste Zustimmung/Ablehnung; Export, Löschung, Recovery und Support erreichbar | `41f3702` geliefert, Review 1 `fix-first`; Korrektur läuft, freigegebene Dokumente separat offen |

Historische Migrationen werden nicht umgeschrieben. Zwei neue Migrationen haben bewusst
getrennte Namen und Tests. Der lokale Datenbank-/Upload-Besitz liegt vollständig bei U3.
K1 besitzt in `Ports.kt` den Auth-Vertrag sowie `SupabasePhoneAuth.kt`; U3 besitzt
`CloudSessionGate.kt` und `SupabaseCloudBackend.kt`. Zusätzliche gemeinsame Änderungen
werden als Integrationsarbeit behandelt.

Freigegebene additive Erweiterung: K1 ergänzt in `CloudHttp.kt` ausschließlich die
Fehler-Whitelist um `consent_required`, `consent_unavailable`, `consent_version_changed`,
`consent_documents_invalid`, `consent_language_invalid`, `no_deletion_pending` samt
gezieltem Mapping-Test. Der übrige HTTP-Vertrag bleibt unverändert.

K1 darf außerdem die bestehenden positiven Onboarding-Fixtures in
`020_onboarding_profile.test.sql` und `140_auth_age_gate.test.sql` minimal um testlokal
freigegebene Dokumente und bewusste Zustimmung ergänzen. Transaktionaler Rollback,
Altersgate-Negativtests und die Verweigerung ohne Zustimmung bleiben erhalten;
Produktionsdokumente werden dadurch nicht freigegeben. Der lokale PG17-Harness ersetzt
keine Supabase-/HTTP-/Cron-Abnahme. Fehlende `pg_cron`-Prüfungen bleiben offene Nachweise.

### Abgestimmte Zustimmungsschnittstelle

- K1 liefert `ConsentStatus` mit `UNAVAILABLE`, `REQUIRED`, `DECLINED`, `CURRENT` und
  `CloudSession.consentStatus` mit sicherem Default `UNAVAILABLE`.
- `CloudAuthPort.consent(language)` liest einen `ConsentSnapshot`;
  `decideConsent(snapshot, accept)` speichert die bewusste Entscheidung.
  Snapshot und Dokumente enthalten Profil, Status, Dokumenttyp, Version, Sprache, URL,
  Freigabe, Annahme und Entscheidungszeit. K1 liefert die Typen in eigener `Consent.kt`.
- U3/Integration erlauben Capture, Cloud-Requests und Ack nur bei `CURRENT`. Profilprüfung,
  Zustimmung, Export, Löschung und Support behalten ihre unabhängigen Berechtigungen.
  Historische gültige Testfixtures bekommen ausdrücklich `CURRENT`.
- K1 liefert `private.has_current_consents(uuid)` und `private.require_current_consents()`.
  Die 001-Migration darf noch nicht von 002 abhängen. Nach beiden Paketen ergänzt eine
  neue `20261018000300_cloud_consent_gate.sql` den zentralen Server-Cloud-Gate-Seam und
  prüft das Zusammenspiel einschließlich alter Cloud-Zugänge.
- Ohne fachlich freigegebene Terms-/Privacy-Versionen bleibt die Zustimmung nicht erfüllbar.
  Keine Rechtstexte, Versionsfreigaben oder fehlenden Terms-Links erfinden.
- K1 darf bestehende Export-/Lösch-RPCs minimal erreichbar machen; ZIP-Auslieferung und
  tatsächlicher Löschjob bleiben K2/K3. Ein Rohdatenabruf ist keine K2-Abnahme.
- Unabhängige Kontorechte gelten auch für eigene noch nicht vollständig eingerichtete
  Profile. K1 darf dafür `deletion_requested` ohne Alias zulassen; aktive Profile bleiben
  aliaspflichtig. Eine private atomare Ursprungsmarkierung stellt bei Löschrücknahme den
  tatsächlichen vorherigen Onboarding-Status wieder her. Wiederholte Anträge ändern weder
  Ursprung noch laufende Karenzfrist; Recovery umgeht weder Onboarding/Zustimmung noch
  Kontosperren. Löschjob und Anonymisierung bleiben im bestehenden K3-Folgeumfang.

### Aufgabenregister

Die folgenden T3-IDs gehören zu diesem Hauptthread. Ergebnisse wecken den Orchestrator;
keine Poll-/Watcher-Schleifen. Neue Reviewrunde erhält eine neue Task-ID und den vollständigen
Brief einschließlich früherer Befunde und Antworten.

- U3: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u3-impl-r1`
- Server/K4: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server_k4-impl-r1`
- K1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-impl-r1`
- Server/K4 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-k4-review-r1`
- Server/K4 Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-server-k4-fix-r1`
- Server/K4 Review 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-k4-review-r2`
- U3 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u3-review-r1`
- U3 Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u3-fix-r1`
- K1 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r1`
- K1 Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k1-fix-r1`

### Server/K4 – erste Übergabe

Entwurfs-PR [#62](https://github.com/thealextd1337-spec/racketone/pull/62), Commit `2721f84`.
Gradle-Check bestanden; lokaler PG17-Ersatzharness laut Implementierer mit 196 neuen Tests
und Parallelitätsprüfungen bestanden. Gesamtlauf 747/749, zwei fehlende `pg_cron`-Prüfungen
auf der Basis reproduziert. Echter Supabase-/HTTP-/WebSocket-Nachweis steht aus; Docker/Podman
sind lokal nicht verfügbar. Realtime-App-Broadcasts/private Kanäle/Cloud-Publikationen sind
geschlossen, die Control-Plane verlangt zusätzlich `private_only` und das Trennen vorhandener
Sockets. Kein Deployment oder Cloud-/Pilotfreigabe. Die Consent-Seams für 003 heißen
`private.require_cloud_profile()` und `private.cloud_profile_allowed(uuid)`.

Review 1: `fix-first`. Zwei P1-Rennen sind vor Integration zu reproduzieren und zu beheben:
direkte Tabellenmutation mit altem Statement-Snapshot nach wartendem Widerruf sowie Legacy-Pull
nach wartendem Löschantrag ohne frische Profilprüfung. P2: effektives libpq-Ziel inklusive
`hostaddr`/Umgebung auf Loopback begrenzen und HTTP-Redirects im Live-Harness abschalten.
Echter Supabase-CI-Lauf `39125048131`: 748/749 Tests bestanden; 170/Test 176 (direkte
Realtime-Tabelle, erwarteter SQLSTATE 42501 blieb aus) fehlgeschlagen. Die vorher im lokalen
Harness fehlenden Cron-Checks waren dort grün. Sicherheitskorrektur läuft beim Implementierer;
kein Merge vor neuer unabhängiger Reviewrunde und grüner CI.

Korrektur `0912e27`: VOLATILE-Statement-Trigger prüft direkte Writes nach Lock erneut;
Cloud-Profilprüfung nimmt Lock vor Aktivstatusprüfung. Realtime-Test prüft vorhandene
Nachrichten trotz effektiver Grants und permissiver Gegenpolicy. Harness pinnt numerisches
Loopback, weist Service-/Umgebungs-Overrides zurück und folgt weder Redirects noch Proxys.
Implementierer: zehn unsichere Rennen auf altem Stand reproduziert, nach Korrektur alle zwölf
wartenden Fälle verweigert; 202 neue pgTAP-Prüfungen und vollständiger Gradle-Check bestanden.
Orchestrator hat die fünf Harness-Testgruppen eigenständig erfolgreich wiederholt.
Zusätzlich im separaten lokalen PostgreSQL-Harness: alle 202 neuen pgTAP-Prüfungen,
SQL-Parallelitätsprüfung und zwölf nachweislich wartende Widerruf-/Löschrennen bestanden.
Dies bleibt lokale PostgreSQL-Evidenz, kein HTTP-/WebSocket-/Hosted-Nachweis.
PR #62 ist aktualisiert. CI `39126397938` für exakt `0912e27` ist grün, einschließlich
echter Supabase-Migrationen/pgTAP und Gradle-/Android-/Wear-Builds; Admin und Apple wurden
pfadbedingt übersprungen. Unabhängiges Review 2 läuft noch, daher kein Merge.

### U3/K1 – gemeinsame lokale Integration

Kerne als `ba7fdb1` und `0764bd1` auf den Integrationszweig übernommen, noch keine Integration
nach `main`. U3 meldet 77 data-/51 cloud-Tests und Migration/Gradle-Check grün. K1 meldet
55 cloud-/22 AuthVM-/217 Phone-Tests, vollständigen Check und 62 neue SQL-Tests grün;
Gesamtlauf im isolierten PG17-Harness 613/615 (nur zwei fehlende Cron-Nachweise).
Echte parallele PG-Verbindungen für Löschantrag/-rücknahme bestanden laut K1.
Der separate U3-K1-Patch wird nun gegen beide tatsächlichen Commits kompiliert und getestet;
seine zuvor reine Patchprüfung ist kein gemeinsamer Laufnachweis.

Gemeinsamer fokussierter Lauf nach Integration bestanden: `data:jvmTest`,
`cloud-sync:jvmTest`, `presentation-auth:jvmTest` und `ui-phone:desktopTest`.
Drei ältere Auth-Lifecycle-Fixtures erhielten ausdrücklich `CURRENT`; ein zusätzlicher
Regressionstest belegt, dass eine verifizierte Profilantwort ohne Zustimmungsstatus
Cloud-Zugriffe weiterhin verweigert und unabhängige Kontorechte nicht pauschal sperrt.
Vollständiger integrierter Gradle-Check mit 690 Tasks bestanden. Dies ist keine
Freigabe der noch offenen U3-/K1-Reviewbefunde.

U3 Review 1 (`fix-first`) reproduzierte zusätzlich mit kompilierten Klassen:

- P1: Ein nachgelieferter Spieler ändert den dynamischen Teilnehmernamen ohne neue
  Teilnehmerrevision; ein alter Ack kann die Änderung verlieren. Insert-Trigger und Migration korrigieren.
- P2: Ungeklärte Altgäste werden als `playerId` referenziert, ohne zulässigen Spieler-Upload.
  Das führt zu `player_not_owned` und dauerhaftem Retry. Ohne belegte Cloud-Berechtigung
  nur Slot/Snapshotname übertragen; keine pauschale Eigentumsübernahme.
- P2: Matchübernahme erkennt belegte eigene Self-Aliase anderer eigener Quellen nicht.
  Beide Quellen nachweisen, fremde und lediglich behauptete Aliase weiter ausschließen.

Der ursprüngliche Implementierer ergänzt Fail→Pass-Regressionen und einen separaten
Korrekturcommit. Neue unabhängige Reviewrunde vor Freigabe erforderlich.

K1 Review 1 (`fix-first`) fand einen P1 und zwei reproduzierte P2:

- Server-Consent und Kontorechte prüfen noch keine weiterhin gültige Auth-Sitzungszeile.
  Ein unabhängiger Prüfer muss gültige, fremde/fehlende und global widerrufene Sitzungen
  unterscheiden, ohne Gerätebindung oder aktuelle Zustimmung vorauszusetzen.
- Nach fehlgeschlagener Ablehnung verliert das ViewModel die Identität für angezeigte
  unabhängige Kontorechte. Identität getrennt von der Cloud-Sperre erhalten, echte Wechsel verwerfen.
- Während der Löschkarenz verhindert die lokale Gate-Sperre weitere verifizierte Profilabfragen.
  Sicheren eigenen Abfrageweg ergänzen, ohne Upload/Logout/Consent-Schutz aufzuheben.

Für den letzten Punkt darf K1 `CloudSessionGate.kt` minimal erweitern; U3-Korrektur
bearbeitet diese Datei nicht. Der Orchestrator vereinigt den neuen Profilweg anschließend
mit der bereits getesteten CURRENT-/Consent-Generation-Integration aus `e4188cc`.

Abgestimmter K1-Korrekturvertrag: eigene serververifizierte `accountSession` und synchrone
Kontositzungsprüfung für unabhängige Rechte; im Gate begrenzte Cloud-Sperre und eigener
Profilabfrageweg mit monotoner Profilrevision. Login/Logout-Übergänge bleiben getrennt.
Veraltete Profilantworten dürfen weder Konto- noch Cloud-Zustand wiederherstellen.
K1 darf die zentrale Testfixture `000-setup-tests-hooks.test.sql` minimal um echte
Testsitzungen und Claims mit `session_id`/`exp` ergänzen. Server/K4 hat dort bereits
Gerätebindungs-Fixtures; beide Deltas werden vom Orchestrator mit konsistenter Auth-Sitzung
vereinigt. Fehlende/fremde/widerrufene/abgelaufene Sitzungen bleiben echte Negativfälle.

Konkrete zentrale Fixture: `tests.account_sessions(profile_id, session_id)`,
`tests.create_auth_session(uuid)` und `tests.authenticate_session(uuid, uuid)`.
`create_user` erstellt einmal die Testsitzung, `authenticate_as` verwendet ausschließlich
deren gespeicherte ID und Claims einschließlich `exp`; keine Ersatzsitzung nach Widerruf.
`authenticate_as_id` bleibt für Negativfälle ohne Sitzung. Bei Integration muss
`tests.cloud_context.session_id` diese kanonische ID verwenden; `prepare_cloud_context`
darf eine gelöschte Auth-Sitzung nicht wieder anlegen.

## Folgewellen – nach erfüllten Abhängigkeiten starten

| Welle | Parallel nutzbare Pakete | Voraussetzung und Abnahme |
|---|---|---|
| 2 | U4 Upload; K2 Export; K3 Server-/Redaktionsvertrag und Löschablauf | U4 nach U3 und stabilem Serververtrag; K2/K3 nach K1-Vertrag. Eigene Dateibesitze festlegen. Upload begrenzt, idempotent, Retry nach verlorener Antwort, mehr als 500 Events, parallele Änderung. Export private ZIP-Ausgabe mit Historie, Ablauf und Aufräumen. Löschung mit 14 Tagen Karenz, Rücknahme und überprüfbarem Job; Vertrag früh für D3 bereitstellen. |
| 3 | D1 Pull; K4 Konto-/Gerätebedienung; K5 Kontosupport | D1 nach U4: atomare Seiten/Cursor, Wasserstände, Neustart, Epochenwechsel und getrennte Konten. K4 nutzt geprüfte Server-RPCs, globale Auth-Abmeldung bleibt getrennt. K5 minimale Suche/Aktionen mit AAL2, serverseitiger Rolle und Audit, kein Match-/Health-Zugriff. |
| 4 | D2 Entzug, danach D3 Redaktion; B4 vorbereiten; K6 Betriebsansicht | D2/D3 teilen Datenhaltung/Protokoll und werden nacheinander integriert. D3 benötigt K3-Vertrag. Offline-Uhr verliert entzogene/redigierte Inhalte dauerhaft, alte Pakete beleben sie nicht wieder. B4 nutzt stabile U4/D1-Schnittstellen; Freigabe erst nach D2/D3. K6 verwendet echte Betriebsdaten, fehlende Telemetrie bleibt unbekannt. |
| 5 | Gemeinsame Regressionen und K7 Pilotnachweise | Integrierten Kandidaten prüfen, Konto-/Geräte-/Support-Live-Matrix und externe Nachweise getrennt dokumentieren. Keine Freigabe aus Mock-/Simulator-/lokalen SQL-Tests ableiten. |

U4 und D1 dürfen nicht vor vollständiger K4-Abnahme freigegeben werden. Eine lokale
Implementierung kann bis dahin fertiggestellt und getestet werden, ohne Cloud-Betrieb
zu aktivieren. Zustimmungsversionen und Geräteschutz werden gemeinsam geprüft, ohne
Export/Recovery/Löschung/Support versehentlich mitzuschließen.

## Konkrete gemeinsame Integrationsprüfungen

1. U3 allein gegen seine Basis prüfen; K1 allein gegen seine Basis; Server/K4 allein gegen
   seinen tatsächlichen Stack. Gemeinsame Änderungen dann in kontrollierter Reihenfolge übernehmen.
2. K1 und U3: nicht aktuelle Zustimmung vor/während Request, Ablehnung, verspätete Antwort,
   Konto A→B und A→A, keine falsche lokale Quittierung. Lokale Matches weiter nutzbar.
3. Server/K4 und K1: Migrationen 001→002→003 in frischem echten Supabase; gültiger Token ohne
   aktuelle Zustimmung oder nach Gerätewiderruf auf sämtlichen Cloud-Wegen verweigert.
   Profil/Zustimmung/Export/Löschantrag/Recovery/Support bleiben nach eigener Prüfung erreichbar.
4. Gesamter Gradle-Check, passende pgTAP-/HTTP-Tests und unabhängiges Review vor PR/Merge.
   Apple-Linking und native Ausführung separat über macOS-CI. Kein Teststub ersetzt Hosted-Abnahme.
5. Nach jedem Merge Folgepakete auf aktualisierter Basis starten. Nicht auf veralteter
   Integrationsbasis parallel neue Migrationen oder gemeinsame UI-/Protokolldateien ändern.

## Verbleibende externe Abnahmen

- Fachlich freigegebene Terms-/Privacy-Texte DE/EN samt belastbarem Versionsbezug;
  Anbieter-/Datenflussnachweise, Data safety, VVT und Prüferzugang gemäß P2-Gate.
- Gehosteter Auth-/Gateway-Negativtest, echte Provider-Anmeldung, Cron-Ausführung,
  Backup-/Wiederherstellung sowie vollständige Support-Live-Matrix.
- Reale Geräte mit Commit/OS/Soll/Ist: Pixel und kleine Galaxy Watch, DE/EN und große Schrift;
  Kontowechsel, Offline/Reconnect, Datenübernahme und später Entzug/Redaktion.
- iOS-Konto-Host, Apple-Developer-Account und reale iPhone-/Apple-Watch-Abnahme getrennt.
  Öffentliche Produktion bleibt erst nach P3 und den bestehenden Release-Gates vorgesehen.

Freunde/Cloud-Live/eigenständige Wear-Sitzungen bauen anschließend auf dem belastbaren Cloud-Pfad
auf; P3 Health/Tracker und P4 Garmin bleiben in ihrer bestehenden Phasenreihenfolge.
