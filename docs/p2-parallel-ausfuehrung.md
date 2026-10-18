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
| U3 | `shared/data`, Cloud-Upload-Snapshot-/Gate-/Backend-Seams; keine Auth/UI- oder Backend-SQL-Änderungen | Änderung während Upload bleibt ausstehend; fremde Metadaten werden nicht zurückgeschrieben; Migration ab Schema 5 erhält historische Daten und unabhängige Geräte-/Cloud-Acks | `fbca22d` auf Entwurfs-PR #63; Review 3 `ship`, Android-/Apple-CI läuft |
| Server/K4 | Neue `20261018000100_cloud_device_binding.sql`, eigene `170_cloud_device_binding*`-Tests | Auth-Sitzung, Konto, Installation/Proof und Gerät auf allen Cloud-Wegen geprüft; Widerruf/Tombstone nicht umgehbar; alte RPCs, direkte Tabellen und Realtime geschützt oder geschlossen | Review 3 `ship`, relevante CI grün; PR #62 als `6537ac6` nach main gemergt. Live-Gateway-/Socket-Abnahme bleibt offen |
| K1 | Neue `20261018000200_consent_versions.sql`, `180_consent_versions*`-Tests, Auth-Port/Host, `presentation-auth`, Account-UI DE/EN | Version/Sprache/Zeit/Historie serverautoritativ; bewusste Zustimmung/Ablehnung; Export, Löschung, Recovery und Support erreichbar | `d11da1c` auf Entwurfs-PR #64; Review 3 bestätigt R1/R2, neuer P2 bei verlorener Recovery-Antwort wird korrigiert |

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
- Server/K4 Korrektur 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-server-k4-fix-r2`
- U3 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u3-review-r1`
- U3 Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u3-fix-r1`
- U3 Review 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u3-review-r2`
- U3 Korrektur 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u3-fix-r2`
- K1 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r1`
- K1 Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k1-fix-r1`
- Server/K4 Review 3: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-review-r3`
- U3 Review 3: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u3-review-r3`
- K1 Review 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r2`
- K1 Korrektur 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k1-fix-r2`
- K1 Review 3: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r3`
- K1 Korrektur 3: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k1-fix-r3`
- Server-Consent 003: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-consent-impl-r1`
- Server-Consent 003 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-consent-review-r1`
- U4-Kern: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-impl-r1`
- U4-Kern Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-review-r1`

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
pfadbedingt übersprungen. Review 2 bestätigt die R1-Korrekturen unter READ COMMITTED,
fand aber einen P2: REPEATABLE READ behält trotz erneuter Sperre den alten Snapshot.
Elf von zwölf Fällen waren dabei unsicher. Korrektur 2 erzwingt unterstützte Isolation
auch im RLS-Pfad; andere Modi müssen geschlossen verweigert werden. Kein Merge vor Review 3.

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

U3-Korrektur `ce4e5df` behebt laut Implementierer alle drei Befunde, einschließlich späterer
Herkunfts-/Quellbindung während eines Uploads. Acht ursprüngliche und zwei ergänzende
Regressionen wurden rot→grün gezeigt; 86 data-/53 cloud-Tests und vollständiger Check
bestanden. Öffentliche API und Gate bleiben unverändert. Als `92acc2b` lokal integriert;
der Orchestrator wiederholt Fokus/Migrationsprüfung gegen den gemeinsamen K1-Stand.
Entwurfs-PR [#63](https://github.com/thealextd1337-spec/racketone/pull/63) startet CI parallel
zum unabhängigen Review 2.

Review 2 bestätigt alle drei U3-R1-Korrekturen und erneut 86 data-/53 cloud-Tests samt
Migration. Neuer belegter P2: Nicht uploadbare PLAYER-Platzhalter belegen vor dem Mapping
die begrenzte Metadatenauswahl; ein Platzhalter bei Limit 1 beziehungsweise 500 bei Limit 500
blockiert echte Änderungen dauerhaft. Korrektur 2 muss diese Kandidaten vor LIMIT ausschließen
oder kontrolliert nachfüllen, ohne künstliche Erfolgs-Acks. Regressionen und Review 3 vor Freigabe.

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

K1 präzisiert den Korrekturvertrag um `private.require_account_read_committed()`;
Session-/Zustimmungsprüfung verwenden ihn, `has_current_consents` bleibt bei anderen
Isolationsmodi falsch. K1 meldet 45 Sitzungsfälle, Isolationsfälle und echte Lock-Warterennen
mit entfernten Auth-Sitzungen bestanden. Gate: `captureProfile(canResumeCloud)` erfasst
Profilrevision, alte Cloud-Leases erhalten eine eigene monotone Cloud-Revision.
Die Integration bewahrt den CURRENT-/Consent-Generationsschutz einschließlich Invalidierung
bei Suspend mit unverändertem Status. K1-Korrekturcommit und unabhängige Prüfung stehen noch aus.

Beim U3-Korrekturlauf gegen K1 benötigte die neu hinzugekommene HTTP-Regression ebenfalls
eine explizite CURRENT-Fixture. Der erste gemeinsame Lauf verweigerte korrekt mit
`consent_required`; nach Anpassung sind data-/cloud-Fokus und Migration gegen den
gemeinsamen K1-Stand grün. Das ersetzt nicht den offenen U3-R2-Fortschrittsbefund.

### Aktuelle Review- und Integrationsergebnisse

Server/K4 Review 3 bestätigt `5e15128` ohne offene belegte P0/P1/P2-Befunde:
204 neue pgTAP-Prüfungen, 48 Lock-Rennen in vier Isolationsmodi und fünf Harness-Testgruppen.
Die relevante CI auf genau diesem Stand ist grün, einschließlich echtem Supabase und
Android/Wear. PR #62 wurde als `6537ac6` gemergt. Hosted-Gateway, `private_only` und das
Trennen bestehender WebSockets bleiben gesonderte Nachweise.

U3 Review 3 bestätigt `fbca22d` mit `ship`. Nicht sendbare PLAYER-Platzhalter und
unvollständige Projektionen werden vor LIMIT ausgeschlossen; kein Fake-Ack und keine
unbegrenzte Nachladeschleife. Unabhängig erneut 90 Data- und 53 Cloud-Tests grün,
einschließlich Migration und 502-Event-Fall. PR #63 wartet auf relevante CI.

K1-Korrektur `21c6d20` wurde mit den CURRENT-/Consent-Generationen integriert.
`captureProfile` behält die Consent-Generation; Suspend invalidiert Profil-, Cloud- und
Consent-Revisionen, ohne unabhängige Kontorechte pauschal abzubrechen. Die zentrale SQL-Fixture
verwendet eine kanonische Auth-Sitzung und legt widerrufene Sitzungen nicht wieder an.
Gemeinsamer Gradle-Check mit 690 Tasks grün; gemeinsame SQL-Suite bis auf die beiden
bekannten fehlenden lokalen pg_cron-Nachweise grün. Lokaler PG17 ersetzt keinen echten Stack.

K1 Review 2 bestätigt die bisherigen Korrekturen, reproduziert aber zwei neue Befunde:

- P1: Suspend verliert die Information über eine lokal behaltene Kontoidentität.
  Löschbeginn oder echter Kontowechsel müssen auch nach Suspend beziehungsweise frischem
  Host die lokale Identität trennen und das aktuelle Gerät wechseln.
- P2: Eine ältere erfolgreiche Zustimmung kann eine neuere fehlgeschlagene Ablehnung
  quittieren und Cloud-Arbeit erneut freigeben. Monotone Intent-Revision vor jedem
  Zustimmung-/Lösch-/Recovery-Aufruf; Altantworten quittieren keine neueren Sperren.

K1 korrigiert beide Befunde mit realen Ownership- und Parallelitätsregressionen. Freigegeben
ist dafür ausschließlich ein lesender U2-Seam: `LocalAccountOwnership.retainedProfileId`
(Default null), in `MatchSession` unter `checkThread` aus `accountIdentities.profile` gelesen.
Die Cloud-JVM-Tests dürfen `match-session` als reine Testabhängigkeit nutzen. Keine Herkunfts-,
Payload- oder Metadatenänderung. Neue unabhängige Reviewrunde vor Freigabe.

K1-Korrektur 2 deckt außerdem den belegten SameAccount-Fall ab: Nach Sitzungswechsel darf
ein Profillease mit `canResumeCloud=false` auch bei zurückgesetztem `cloudSuspended` weder
Ownership noch Uploads aktivieren. Parent bewahrt diesen Schutz neben CURRENT und
Consent-Generation; der Callback-Fehlervertrag bleibt erhalten.

K1 meldet für die finalen 13 Real-Ownership-/Adapter-Regressionen: Gegen die ursprünglichen
Gate-/Auth-Quellen aus `21c6d20` scheitern neun Fälle, vier Kontrollen bestehen; mit Fix
bestehen 13/13. Fokusprüfungen für Cloud/Auth/Match sind grün, der finale vollständige Check
läuft nach Bereinigung eines lokalen Kotlin-Inkrementalcachefehlers. SQL bleibt unverändert:
674/676 Assertions grün, nur 110/Cron 15–16 fehlen im lokalen Harness; echte Request-/Recovery-
und Sessionwiderruf-Lock-Rennen unter RC/RR/Serializable bestanden laut Implementierer.
Dies ist noch keine unabhängige Reviewfreigabe.

PR #64 kollidiert nach dem Server/K4-Merge mit `main` in der zentralen SQL-Testfixture.
Nach dem separaten Fix2-Commit übernimmt K1 den ausdrücklich freigegebenen `origin/main`-Merge
in einem eigenen Commit. Die geprüften Vorlagen aus `fbbeefb` für 000/170/180 erhalten beide
Sitzungsverträge. Keine U3-/CURRENT-Übernahme auf den eigenständigen K1-Branch. Anschließend
erneut prüfen und neue unabhängige Reviewrunde auf dem tatsächlich mergebaren Stand starten.
Profilrace-Regressionen akzeptieren sichere Cancellation oder `cloud_session_changed`;
beide müssen veraltete Veröffentlichung und lokale Quittierung verhindern.

Fix2 ist als `0edd992` geliefert: lesender Retention-Seam, behaltene Identität unabhängig
von Uploadfreigabe und monotone Mutationsrevision. Implementierer meldet 73 Cloud-,
25 Auth-, 135 Match- und 217 UI-Tests sowie vollständigen Check mit 700 Tasks grün.
Der separate `main`-Merge läuft; Review 3 wartet ausdrücklich auf dessen getesteten Head.
Parent übernimmt Fix2 unter Erhalt des Request-Typs (Cloud/Kontorecht), der CURRENT-Prüfung,
Consent-Generation und der U3-Metadatenquittierung; gemeinsame Prüfung folgt.

K1-Mergehead `d11da1c` ist fertig, sauber und auf PR #64 gepusht. Er verbindet Fix2 mit
Server/K4 aus `main`; die drei gemeinsamen SQL-Fixtures entsprechen exakt der geprüften
Parent-Auflösung. Worker meldet vollständigen Merge-Check mit 686 Tasks grün und SQL
878/880 (nur die zwei lokalen Cron-Lücken). Review 3 ist als neuer unabhängiger Task
auf genau diesem Head gestartet; die PR-CI startet ebenfalls neu.

Parent hat den tatsächlichen Fix2-Diff geprüft und integriert die Retention gemeinsam
mit dem bestehenden typisierten Request-Verzeichnis und Consent-Generationsschutz.
Fokus gegen den gemeinsamen Stand: 82 Cloud-, 25 Auth- und 135 Match-Tests ohne Fehler,
SQLDelight-Migrationsprüfung ebenfalls grün. Der vollständige Parent-Check läuft.

Der vollständige Parent-Check nach Fix2 ist mit 690 Tasks grün (3:38 Minuten).
Damit sind die gemeinsame CURRENT-/Retention-/Intent-Integration und die vorhandenen
U3-Regressionen lokal ausführbar geprüft; unabhängige Freigabe steht weiterhin aus.

Server-Consent 003 ist als `20f0e43` aus `t3/server-consent-gate` geliefert. Der Worker
meldet 248 neue SQL-Assertions und 80 echte Lock-Warterennen bestanden; Gesamtlauf
1.126/1.128 (nur lokale Cron-Lücken), vollständiger Gradle-Check grün. Neben den beiden
Cloud-Seams prüft `require_current_consents` die unabhängige Auth-Sitzung nach dem Lock
erneut. Historische Produktionsmigrationen 001/002 und die 180-K1-Tests bleiben unverändert.
Positive Legacy-Suites erhalten explizite TESTzustimmung. Parent prüft den tatsächlichen
Diff und beauftragt unabhängiges Review vor Übernahme/Freigabe.

Das unabhängige Server-Consent-Review ist auf exakt `20f0e43` gestartet, einschließlich
der bekannten Statement-Snapshot-/Isolations-/Sitzungswiderruf-Risiken aus früheren Reviews.
Währenddessen läuft U4 weiter. Neue Umsetzungspakete werden erst nach stabilen Verträgen
und ohne Überschneidung mit den laufenden Review-/Uploaddateien gestartet.

K1 Review 3 bestätigt alle R1/R2-Korrekturen, bewertet den Stand aber als `fix-first`:
Bei serverseitig erfolgreicher Recovery mit verlorener Antwort bleibt `unconfirmedDeletion`
gesetzt. Retry liefert `no_deletion_pending`; ein frisches aktives Profil löst die Sperre
nicht auf. Der Konto-ViewModel-Pfad verliert zudem Consent/Recovery-Anzeige, wenn die noch
gesperrte Adoption wirft. Export bleibt erreichbar. Der Implementierer korrigiert getrennte
Delete-/Recovery-Intents und die Profilbestätigung unter neuester Mutationsrevision, ohne
eine spätere Delete-/Decline-Sperre zu quittieren. Reale VM-/Adapter-Regressionen und Review 4
sind vor Merge erforderlich. Supabase-CI auf `d11da1c` ist grün; Android/Apple laufen noch.

U4-Kern ist als `dda679d` geliefert: expliziter serialisierter Runner mit maximal vier
Batches à 500 Events als Standard, eingefrorenem prozesslokalem Retry und ehrlichem
Pending-/Rejected-/Metadatenstatus. Sieben geänderte Dateien, keine Gate-/Auth-/SQL-Änderung.
Worker meldet 83 Cloud- und 90 Data-Tests (14 neue Runner-Integrationstests) und vollständigen
Check mit 686 Tasks grün. Parent hat den Produktivdiff gelesen; unabhängiges Review 1 läuft.
Android-Bedienung und echter Supabase-Lauf stehen weiter aus. Die Bestandszählung liest aktuell
den lokalen Bestand vollständig; das Review prüft die Kosten und Fortschrittsgarantien.

Der Integrationszweig ist mit Server/K4 aus `origin/main` abgeglichen. Die beiden
Testfixture-Konflikte erhalten die bereits gemeinsam geprüften kanonischen Sitzungen,
JWT-Ablaufclaims und die relative Sessionzählung; keine Sitzung wird nach Widerruf neu angelegt.
Danach vollständiger Gradle-Check mit 686 Tasks grün und SQL-Gesamtlauf erneut bis auf
die zwei bekannten lokalen pg_cron-Nachweise grün.

### Nächste isolierte Umsetzungspakete

Der gemeinsame Stand nach U3-Korrektur 2 und K1-Korrektur 1 besteht den vollständigen
Gradle-Check mit 686 Tasks. Die neu hinzugekommene U3-HTTP-Fixture setzt `CURRENT` ausdrücklich.

| Paket | Besitz | Begrenztes Ergebnis und Abhängigkeit |
|---|---|---|
| Server-Consent-Integration | Neue Migration `20261018000300_cloud_consent_gate.sql`, neue `181_cloud_consent_gate*`-Tests, minimale explizite positive SQL-Fixtures | Nach 001/002 verlangen zentrale Cloud-Seams aktuelle Zustimmung, auch auf alten RPC-/Tabellenwegen. Lock-Rennen und unabhängige Kontorechte prüfen; Produktionsseed bleibt UNAVAILABLE. Keine Kotlin-/Auth-/Uploadänderung. |
| U4-Kern | Neue Runner-/Uploadzustände und Tests in `cloud-sync`, Backend-/Mapping-Seams, nötigenfalls schmale Upload-Statusprimitive in `data` | Expliziter endlicher Lauf über `captureUpload`/`pushSelected`, ≤500 Events je Batch, begrenzte Batches, Retry/Teilantworten/Paralleländerungen und ehrlicher Restbestand. Kein Gate-/Auth-/Ownership-/SQL-Besitz. Android-Bedienung und echter Supabase-Lauf sind anschließende eigene Integrationsschritte. |

Beide Pakete laufen isoliert ab dem geprüften lokalen Integrationscommit `6ba0386`. K1 behält Gate/Auth
und den freigegebenen lesenden U2-Seam. Der Orchestrator integriert die Diffs nach separatem
Review; keine gemeinsame Datei wird parallel durch mehrere Implementierer bearbeitet.
U4 startet keine Gerätebindung, Reauthentifizierung, Proofrotation oder Hintergrundplanung.

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
