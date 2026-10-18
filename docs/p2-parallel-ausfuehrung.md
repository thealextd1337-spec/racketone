# Cloud und Pilot – parallele Ausführung

Stand: 18.10.2026. Auftrag: planen und möglichst parallel umsetzen. Basis `c85ad78`
(U2, PR #61). Fachlich gelten [PLAN](PLAN.md) und der
[Lieferplan](p2-cloud-pilot-lieferplan.md); dieser Ausführungsplan ersetzt keine Pilotabnahme.

## Aktueller Integrationsstand und laufende Pakete

### K2-Phone: geprüfte Basis und Android-Endkomposition

Die gemeinsame Konto-UI `01a3e6f` ist unabhängig ohne Befunde freigegeben (39 frische Tests,
neun Aufrufformen, vier DE/EN-Hell/Dunkel-Bilder bei 200 Prozent). Root integriert sie unverändert
als `cbc4022`; Gesamtcheck samt Phone-/Wear-APKs: 767 Tasks grün. Die A+B-Dateien bleiben
unverändert, der gemergte K2-Server bleibt auf der bestehenden Mainbasis.

Android-Host einschließlich Fix `b6f5c25` ist im vollständigen Review R2 ohne Befund freigegeben:
29 Repository-Exporttests plus vier unveränderte externe Reproduktionen frisch grün, null Skips.
Worker-Gesamtcheck samt beiden APKs: 767 Tasks grün. Der frühere Provider-P1 (alter Grant vor
Collectorfortschritt) ist durch drei rote, danach grüne Regressionen und den seriellen
`ExportAccessGateway` geschlossen. Der R1-P2 (Abbruch vor LAZY-Start blockiert den Host) ist durch
identitätsgebundenen Abschluss vor Publication behoben; auch nie gestartete Jobs und tatsächliche
Kindjob-Enden sind berücksichtigt. Der enge Provider öffnet nur registrierte private Dateien nach
aktueller SDK-/Kontoprüfung; der Windows-Befund wird nicht als Android-Defekt ausgegeben.
Root übernimmt alle zehn C-Dateien bytegleich zu `b6f5c25`; gemeinsame Konto-UI, Cloudadapter,
Server und CI unverändert. Gemeinsamer Gesamtcheck samt beiden APKs: 771 Tasks grün (1m2s).

K2-D2 ist einschließlich Fix `0019d83` umgesetzt, ausschließlich auf `PhoneAccount.kt`, `MainActivity.kt` und einem neuen
direkten Kompositionstest. Dieselbe Auth-Instanz liefert den Exportport; die aktuelle Activity
besitzt den Sharecallback, der langlebige Host hält sie nicht. C-Dateien bleiben unberührt;
spätere C-Korrekturen übernimmt Root. Vier neue Kompositionstests und Worker-Gesamtcheck samt APKs
771 Tasks grün. Der Test-P2 aus R1 ist mit expliziten Factory-Testwerten geschlossen;
öffentliche Nullargument-API und Produktionsdefaults aus BuildConfig bleiben erhalten.
Review R2 gibt frei: vier Kompositions- und 27 Hosttests mit nichtleeren Fake-Properties,
zusätzlich 23 Auth-/Lifecycle- und 13 Konto-UI-Tests frisch grün, keine Skips.
Root übernimmt die drei Kompositionsdateien bytegleich zu `0019d83`, C weiterhin bytegleich
zum freigegebenen `b6f5c25`. Gemeinsamer Gesamtcheck samt beiden APKs: 767 Tasks grün (47s),
54 App-Tests ohne Fehler oder Skips. Frischer Gesamtintegrationsreview und PR-CI stehen aus.
Kein Push/PR aus den Umsetzungsagenten,
keine automatische Exportabfrage. Gesamtfreigabe erst nach Kompositionsreview, gemeinsamer
Integration und relevanter CI. PR #68 ist inzwischen als `6c45ac5` auf main; dessen Baum ist
bytegleich zur bereits enthaltenen Basis `868a3a2`. Squash-Abgleich `78c4301` erhält alle Exportdateien.

- C-Gesamtreview: `advantly-20261018-k2-phone-host-review-r1`.
- C-Review nach Fix: `advantly-20261018-k2-phone-host-review-r2`.
- D2-Umsetzung: `advantly-20261018-k2-android-composition-impl-r1`.
- D2-Review: `advantly-20261018-k2-android-composition-review-r1`.
- D2-Review nach Fix: `advantly-20261018-k2-android-composition-review-r2`.

Diese IDs haben den vollständigen T3-Taskpräfix, der unterhalb dieser Übersicht dokumentiert ist.

- K2-Serverexport ist mit PR #67 als `8660716` nach main gemergt; alle fünf Checks grün,
  einschließlich echtem lokalen Supabase-/Storage-/Scheduler-Lauf. Keine Hosted-Freigabe.
- Android-Upload PR #68 ist auf `8981791` mit diesem Mainstand abgeglichen. Upload-Kotlin,
  Android und Wear bleiben unverändert; Gesamtcheck plus beide APKs erneut 761 Tasks grün.
  Die CI des aktuellen Heads bleibt Mergebedingung.
- D1 ist im abhängigen Draft [PR #69](https://github.com/thealextd1337-spec/racketone/pull/69),
  Head `6d9d651`. Vier Fachreviewrunden, Root-Integrationsreview und Stack-Harness-Review sind
  ohne offene Befunde abgeschlossen. Der echte Auth-/PostgREST-Pull läuft erstmals in dieser CI;
  das Gate verlangt genau einen bestandenen Test ohne Skip. Erst #68, dann D1 integrieren.
- K2-A und K2-B sind ohne Produktabweichung im isolierten Root-Zweig
  `p2/k2-phone-integration` als `6b798fa` kombiniert: 767 Tasks samt Phone-/Wear-APKs grün.
  Android besitzt nachgewiesenen nativen HTTP-Abschluss; iOS/JVM-ZIP melden bis zu einem
  eigenen Plattformnachweis vor Netzwerkzugriff ausdrücklich `export_unavailable`.

| Laufendes Paket | Exklusiver Besitz | Abhängigkeit und prüfbares Ende | Task-ID |
|---|---|---|---|
| K2-C Android-Exporthost | Neue `PhoneExportHost`, `PrivateExportCache`, `ExportShare` und direkte Tests; dedizierter Manifest-Provider/Path; nur nötige bestehende Robolectric-Testkonfiguration | Geprüfte A+B-Basis übernehmen; exakte Länge, SHA und EOF vor privater atomarer Promotion; nur Lesefreigabe; Kontogrenzen, Ablauf und tatsächlicher Ressourcenabschluss; Gesamtcheck/APKs | `advantly-20261018-k2-phone-host-impl-r1` |
| K2-D1 gemeinsame Konto-UI | `AuthViewModel`, `AccountScreen`, `AdvantlyApp` und direkte neue Tests | Dieselbe A+B-Basis; optionaler Exportport; Authclose erst nach Upload- und Exportdrain; echte Kontoaktionen und Export unabhängig bedienbar, DE/EN bei 200 Prozent; Gesamtcheck/APKs | `advantly-20261018-k2-phone-account-impl-r1` |
| Root-Endintegration | Danach ausschließlich Android-`PhoneAccount`/`MainActivity`, Zusammenführung und unabhängige Reviews | Erst C und D1 prüfen, dann aktuellen Activity-Sharecallback und gemeinsamen Lifecycle integrieren; keine gehaltene Activity, keine automatische Exportabfrage | Noch nicht gestartet |

Die beiden laufenden IDs verwenden den vollständigen Präfix
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3A`.
Ihre Dateigrenzen überschneiden sich nicht. Root besitzt Dokumentation, Push/PR/CI und die spätere
Android-Komposition; Worker verändern diese Integrationsdateien nicht. D2/D3 sind weiterhin
vorbereitete Folgepakete ohne aktivierte Protokolländerung; B4 folgt erst auf belastbarem Pull und
Rechte-/Redaktionsvertrag. Echte Sharesheet-, Geräte-, Apple- und Rechtsnachweise bleiben offen.

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
| U3 | `shared/data`, Cloud-Upload-Snapshot-/Gate-/Backend-Seams; keine Auth/UI- oder Backend-SQL-Änderungen | Änderung während Upload bleibt ausstehend; fremde Metadaten werden nicht zurückgeschrieben; Migration ab Schema 5 erhält historische Daten und unabhängige Geräte-/Cloud-Acks | Review 3 `ship`, Android-/Apple-CI grün auf `fbca22d`; PR #63 als `1575d58` nach main gemergt |
| Server/K4 | Neue `20261018000100_cloud_device_binding.sql`, eigene `170_cloud_device_binding*`-Tests | Auth-Sitzung, Konto, Installation/Proof und Gerät auf allen Cloud-Wegen geprüft; Widerruf/Tombstone nicht umgehbar; alte RPCs, direkte Tabellen und Realtime geschützt oder geschlossen | Review 3 `ship`, relevante CI grün; PR #62 als `6537ac6` nach main gemergt. Live-Gateway-/Socket-Abnahme bleibt offen |
| K1 | Neue `20261018000200_consent_versions.sql`, `180_consent_versions*`-Tests, Auth-Port/Host, `presentation-auth`, Account-UI DE/EN | Version/Sprache/Zeit/Historie serverautoritativ; bewusste Zustimmung/Ablehnung; Export, Löschung, Recovery und Support erreichbar | Review 6 Ship, CI auf `025efc5` vollständig grün; PR #64 als `d9f055c` nach main gemergt |

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
- K1 Review 4: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r4`
- K1 Korrektur 4: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k1-fix-r4`
- Server-Consent 003: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-consent-impl-r1`
- Server-Consent 003 Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-consent-review-r1`
- Server-Consent 003 Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-server-consent-fix-r1`
- Server-Consent 003 Review 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-server-consent-review-r2`
- U4-Kern: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-impl-r1`
- U4-Kern Review 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-review-r1`
- U4-Kern Korrektur 1: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u4-core-fix-r1`
- U4-Kern Review 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-review-r2`
- U4-Kern Korrektur 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u4-core-fix-r2`
- K3-Server: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k3-server-impl-r1`

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

Server-Consent Review 1: `fix-first`, ein belegter P2 in der Testintegration, kein belegter
P0/P1 im Produktionsdiff. Die älteren Python-Nachweise `170_cloud_device_binding_live.py`
(`--sql-only`) und `170_cloud_device_binding_races.py` liefern im positiven Bindungssetup
noch keine `exp`-Claims und keine TESTzustimmung. Beide brechen vor ihren eigentlichen
Parallelitäts-/Widerrufprüfungen ab; die neuen 181-Rennen ersetzen diese Fälle nicht.
Der Implementierer darf ausschließlich diese beiden zusätzlichen Testdateien minimal
anpassen. Standalone-Verwendbarkeit, Loopback-/DSN-/Redirectschutz und alle Negativassertions
bleiben erhalten; Produktionsmigrationen unverändert. Danach vollständige Wiederholung und
neues unabhängiges Review 2. Reviewer wiederholte 1.126/1.128 SQL-Assertions, 248/248 neue
Consenttests, 80 Lock-Rennen und die unabhängigen K1-Rennen erfolgreich (nur lokale Cron-Lücken).

Für K1 Fix3 zusätzlich freigegeben: reine JVM-Testabhängigkeit auf `match-session` in
`presentation-auth/build.gradle.kts` und `RecoveryIntentIntegrationTest.kt` für echte
VM-/Adapter-/lokale Ownership-Regressionen. Unerwartete Adoptionsfehler dürfen nicht pauschal
verschluckt werden; erwartete lokale Cloud-Sperre darf Kontostatus/Consent nicht verstecken.

PR #63 ist nach Review 3 und CI auf genau `fbca22d` als `1575d58` gemergt. Gradle,
Android/Wear sowie native Apple-Tests und iOS/watchOS-Simulator-Builds bestanden;
Admin/Supabase wurden pfadbedingt übersprungen. Die echte Geräteabnahme bleibt offen.
Parent gleicht diesen Mainstand ab und erhält bei den beiden neuen HTTP-Testfixtures
ausdrückliches CURRENT. K1 übernimmt den neuen Mainstand nach seinem separaten Fix3-Commit,
bevor Review 4 beginnt; U3-Metadaten-Acks und seine Retention-/Intent-Prüfungen bleiben erhalten.
Der vollständige Parent-Check nach diesem Mainabgleich ist mit 686 Tasks grün.

CI von PR #64 auf `d11da1c`: Supabase bestanden; Ubuntu scheitert an einem einzelnen
AndroidHost-Test in `ConsentAccountIntegrationTest`, nach simuliertem Widerruf erwartetes
`accountSession=null`, tatsächlich noch Alice/Generation 1/REQUIRED (Zeile 120).
JVM-Gegenstück und übrige AndroidHost-Tests bestanden. Parent hat Joblog und XML-Artefakt
gelesen und an K1 zur deterministischen Ursachenprüfung gegeben; kein blindes Retry und
keine Assertionschwächung. Der Fix3-Pfad und dessen getrennte Mainintegration laufen weiter.

U4 Review 1: `fix-first`, belegter P2 zur Main-Thread-Arbeit. Bei 20.000 bereits quittierten
Historienköpfen braucht `refresh()` laut unabhängigem Repro rund 2.001 statt 3,6 ms, obwohl
keine Arbeit aussteht. Vollständiges Materialisieren plus N+1-Abfragen und doppelte Zählung
umgehen das Batchbudget. Korrektur 1 darf schmale kontogebundene SQL-Aggregatqueries in
CloudUpload/CloudMetadata und direkte einmalige Inventory-Lesung ergänzen; keine neue
Schema-/Ownershipänderung. Fachregeln und alle 14 Runnerregressionen bleiben erhalten.

Server-Consent-Korrektur `2efa021` verändert ausschließlich die beiden freigegebenen
Python-Fixtures (+58/−8). Gültige Ablaufclaims, explizite TESTzustimmung und Cleanup ohne
000-Schema ergänzt; positive unabhängige Kontorechte verwenden ihren RC-Vertrag.
Worker meldet beide ursprünglichen Repros rot→grün, Standalone ohne `tests`-Schema,
vier Isolationsmodi, 5 Harnessgruppen, 248 Consentassertions/80 Lock-Rennen und vollständigen
Gradle-Check grün; SQL nur die bekannten zwei Cron-Lücken. Parent hat den Korrekturdiff
gelesen und neues unabhängiges Review 2 auf genau `2efa021` gestartet.

U4-Korrektur 1 darf zusätzlich ausschließlich die interne Gate-Lesemethode
`uploadInventory(lease)` ergänzen: Cloud-/CURRENT-/Generationsprüfung, skalare Zählung mit
der aktiven vom Gate ausgestellten Uploadsession, danach erneute Prüfung. Keine Ausgabe
einer ungebundenen Uploadsession und kein Profillease als Cloudfreigabe; Backend prüft die
Gerätebindung zusätzlich. So entfällt die bisherige Snapshotauswahl zur bloßen Zählung.
K1 Fix3 bearbeitet das Gate nicht; die übrige Gate-/Auth-/Retention-Logik bleibt bei Parent/K1.

K1 grenzt den CI-Widerruf auf einen Busy-Zustandsfehler ein: `showSession` veröffentlichte
`busy=false` vor dem `finally` der laufenden Aktion. Ein StateFlow-Beobachter startet darauf
den Folge-Export, dessen `busy=true` durch das alte `finally` wieder gelöscht wird. Das
Test-`settled()` kann damit vor der tatsächlichen Widerrufsantwort zurückkehren. Die Korrektur
erhält den laufenden Busy-Zustand; deterministische gemeinsame JVM-/AndroidHost-Regression
startet den Export genau an dieser Veröffentlichung und hält die Antwort per Deferred zurück.
Keine Sleeps oder abgeschwächten Widerrufsassertions. Rot→Grün-Nachweis steht noch aus;
Busy-/Recovery-Deltas bleiben in Diff und Nachweisen getrennt erkennbar.

Server-Consent Review 2 auf `2efa021`: `ship`, keine belegten offenen P0/P1/P2.
Reviewer wiederholte beide ursprünglichen Python-Repros vollständig, alle vier Isolationsmodi,
Standalone ohne `tests`-Schema, Cleanup-Ausnahmen und Fremddatenerhalt. AST-Vergleich bestätigt
alle 34 Assertions und fünf Verbindungsschutzfunktionen unverändert; 248 Consentassertions,
80 Lock-Rennen und Gesamtsuite bis auf lokale Cron-Lücken grün. Parent übernimmt 003 und
Fixturekorrektur nun gemeinsam; vollständiger Gradle-Check mit 686 Tasks grün, eigene
SQL-/Legacy-/Consent-Rennen laufen. Echt-Supabase-CI für 003 steht noch aus.

K1 hat den CI-Busyfehler deterministisch nachgewiesen: Original-ViewModel und Adapter aus
`d11da1c` mit neuen Tests ergeben jeweils JVM/AndroidHost 2/4 Fehler. Isolierter Busy-Produktivfix
auf unverändertem Adapter ergibt beide Male 4/4 grün. Finale Statusveröffentlichung behält Busy;
monotone `actionRevision` schützt gegen catch/finally älterer Aktionen. Dieser Diff wird
eigenständig vom Recovery-Fix committet.

Zusätzlich belegte alte Adapterfixture verletzte den seriellen Hostvertrag durch eigenen
`Dispatchers.Unconfined`-Scope: Widerruf hatte die Kontositzung bereits gelöscht, während
UI-Collector noch ausstand. Zwei alte Fixtures dürfen auf den gemeinsamen `coroutineContext`
mit eigenem SupervisorJob wechseln; alle Widerrufs-/UIassertions bleiben unverändert.
Explizit Unconfined bleibt nur der deterministische Reentry-Beobachter. Finale Prüfung läuft.

Eigene Server-Integration abschließend geprüft: volle SQL-Suite bis auf die beiden lokalen
Cron-Assertions grün; Legacy-Standalone, 48 ursprüngliche Rennen in vier Isolationsmodi,
80 Consent-Lock-Rennen, K1-Request-/Recovery-Rennen und fünf Harness-Schutztests bestanden.
Der erste eigene Lauf hing im temporären Windows-Harness: synchrone Kindprozesse blockierten
den Eventloop zur Leerung der Embedded-Postgres-Logpipes. Nach asynchronem Prozessaufruf
lief dieselbe Prüfung vollständig durch. Keine Produktivänderung dafür; abgebrochener
Teillauf bleibt getrennte Evidenz. Migration 003 und Testkorrekturen sind lokal integriert.

K1 liefert drei getrennte Commits: Busy `5ad1c6d`, Recovery `f8d4cb0`, U3-Merge `bc283f4`.
Worker meldet final Cloud 78, Data 90, Auth JVM 39/AndroidHost 27, Match 135 und UI 217 grün;
vollständiger Merge-Check mit 688 Tasks/Exit 0, SQL nur lokale Cron-Lücken. Parent hat die
Produktivdiffs gelesen und PR #64 auf `bc283f4` gepusht. Neue CI und unabhängiges Review 4
laufen auf genau diesem Head; die Root-CURRENT-/Consent-Generation bleibt separate Integration.

U4-Korrektur `ddba960` ersetzt vollständige Listen durch drei skalare Aggregate und direkte
Statuslesung. Ein Refresh zählt genau einmal, ohne Batchauswahl; Gate und Backend prüfen
Cloud-Lease, aktive Uploadsession und Gerätebindung. Keine DDL/Index-/Ownershipänderung.
Worker: 89 Cloud-/93 Data-Tests und Migration grün, vollständiger Check mit 686 Tasks grün;
Median bei 20.000 quittierten Köpfen 1.300,2 → 3,2 ms, zusätzlich struktureller Drei-Reads-Beweis.
Parent hat den Korrekturdiff gelesen; neues unabhängiges Review 2 auf `ddba960` läuft.

Parent integriert Busy-/Recovery-Fixes konfliktfrei unter Erhalt des CURRENT-/Consent-
Generationsschutzes. Vollständiger Check des gemeinsamen Standes mit 692 Tasks grün.
Die Backend-SQL-Dateien bleiben dabei unverändert. Der neue CI-Head von PR #64 ist
`bc283f4`; dessen Review 4 und die neue CI bleiben vor Merge abzuwarten.

K1 Review 4 bestätigt die bisherigen Korrekturen einschließlich Busyfix, findet aber P2:
Recovery mit verlorener Antwort, danach erfolgreiche Zustimmung und frisches CURRENT-Profil
bleiben lokal gesperrt, weil eine globale Mutationsrevision die noch bestätigbare Recovery
entwertet. Korrektur 4 trennt diese beiden Verantwortungen und prüft angrenzende REQUEST/
RECOVERY-/ACCEPT-/DECLINE-Folgen. Neuere Lösch-/Ablehnungsabsichten bleiben geschlossen.

U4 Review 2 bestätigt Performancekorrektur und misst 3,45 ms bei 20.000 quittierten Köpfen.
Neuer P2: Initiales direktes Refresh zählt einen eigenen Self-Spieler noch nicht mit, dessen
belegbare Herkunft erst die nachfolgende Auswahl auflöst (4→5 Metadaten ohne Fachdatenänderung).
Korrektur 2 vereinheitlicht Herkunftsregeln von Status und Auswahl ohne Rückkehr zu Snapshot-
oder vollständigen Listenabfragen. Beide Implementierer arbeiten isoliert weiter.

### K3-Server – frühes Folgepaket

Der Backendvertrag von K1 (Sitzungsprüfung, Deletion-Ursprungsmarker und Recovery) blieb über
alle Clientkorrekturen unverändert und wurde unabhängig geprüft. Darauf kann der separate
K3-Serverteil aufbauen: neue Migration `20261018000400_account_deletion_job.sql` und eigene
`190_account_deletion*`-Tests. Ergebnis: serverautoritärer Frist-/Rücknahmevertrag, begrenzter
idempotenter Löschlauf über den vorhandenen Purge-/Redaktionspfad und überprüfbare Planung/
Jobzustände. Keine Kotlin-/UI-/Protokoll-/001–003-Änderung, keine Liveaktivierung. Eigene
Testkonten und Recovery-/Job-Rennen; echtes Cron und Apple-Token-Widerruf separat nachweisen.

K3-Server läuft isoliert ab `3ad5f0a`: eigene Status-RPC mit beantragtem Zeitpunkt/frühester
Löschung/Rücknahme, regulärer fester 14-Tage-Lauf mit höchstens 100 Konten, benannter Cronjob
und minimaler aggregierter Laufnachweis. Bestehende Purge-/Redaktions-/Support-Auditpfade
wiederverwenden, keine neue Redaktion oder öffentliche Diagnosedaten. Eigener Harnessport
54587; D3, UI, echte Cronausführung und Apple-Widerruf bleiben getrennte Abnahmen.

U4-Herkunftskorrektur zusätzlich konkret freigegeben: gemeinsame U3-Auflösung in
`CloudSyncMetadata.kt`, UNKNOWN-Matches bei `CloudUploadStore.activate` mit bereits
kontoeigener Genesisquelle, nach atomarem `bindOwnedDevices` sowie nach belegtem
`AccountIdentityRepository.recordSource`. Vorhandene Kotlin-Genesisprüfung bleibt maßgeblich;
Kandidaten in höchstens 500 IDs pro Seite mit strikt fortschreitendem Cursor. Keine Auflösung
im Refresh, keine bereits quittierte Gesamthistorie parsen. Auch inverse Self-Aliasabhängigkeit
zwischen zwei belegten Quellen desselben Kontos testen; nie aktuelles Hostkonto als Beleg nutzen.

K1 Fix4 bleibt in Adapter und vorhandenen Recovery-Integrationstests. Kompakte 36-Zellen-Matrix
für REQUEST/RECOVERY und ACCEPT/DECLINE mit Erfolg, verlorener Antwort und Fehler ohne Commit.
Zusätzlich darf REQUIRED/UNAVAILABLE eine unbestätigte Ablehnung nicht quittieren; nur frisches
DECLINED oder bewusste neuere bestätigte Annahme. Öffentliche API, Gate und SQL unverändert.

K3 meldet einen DST-Befund: `interval '14 days'` kann in Europe/Paris über Winterzeit um eine
Stunde abweichen. Freigegeben ist ausschließlich in 004 eine funktionslokale UTC-Festlegung
auch für bestehendes `cancel_account_deletion()` per ALTER FUNCTION, ohne API-/Rechteänderung.
Status, Rücknahme und Job müssen identisch entscheiden; Sommer-/Winterzeit und UTC-Kontrollen
sowie vor/an/nach Frist testen. Zusätzlich nachgewiesenen Lock-Wait über die Fristgrenze prüfen;
weitere Änderung des Fristvergleichs erst nach belegtem Repro und konkreter Meldung.

K1 Fix4-Fokus: Cloud 78, Auth JVM 51/AndroidHost 27 grün. Zwei Dateien, neun Produktivzeilen;
24 echte Recovery-Integrationstests einschließlich 36-Zellen-Matrix. Originaladapter `bc283f4`
mit finalen Tests 7 rot/17 Kontrollen grün; korrigiert 24/24. Der Decline-Flagdelta wurde
zusätzlich isoliert rot→grün geprüft. Vollständiger Check und unveränderte SQL-Suite laufen;
Commit und neues unabhängiges Review 5 stehen noch aus.

K3 hat den echten Frist-Wait belegt: gleiche Authsitzung beginnt vor Fälligkeit, nachgewiesene
Advisory-Sperre wird erst nach Ablauf freigegeben; `now()` erlaubt trotzdem noch Recovery.
Zusätzlich konkret freigegeben ist in 004 die additive identische Neudefinition von
`cancel_account_deletion`, ausschließlich Fristvergleich mit `clock_timestamp()` statt
Transaktionsbeginn plus funktionslokales UTC. Status erfasst Serverzeit nach dem Lock;
Job verwendet ebenfalls frische Zeit. API, Grants, SecurityDefiner, search_path und sämtliche
Session-/Ursprungs-/Consentregeln erhalten. Rot→Grün muss Status/Rücknahme/Job konsistent
belegen; 001/002/003-Dateien bleiben unverändert.

### Aktuelle Prüfstände: K1 Fix4, U4 Fix2 und K3

K1 Fix4 `4ba0880` ist auf PR #64 gepusht und im gemeinsamen Stand zur Prüfung übernommen.
Nur Authadapter und bestehende Recovery-Integrationstests ändern sich; das gemeinsame
CURRENT-/Consent-Generationsgate, U3-Acks und Server-003 bleiben erhalten. Worker-Check:
688 Tasks grün, 24 Recoveryfälle einschließlich 36 Übergangszellen; Originaladapter mit
denselben Tests 7 rot/17 Kontrollen grün. Auch der gemeinsame vollständige Check ist mit
688 Tasks grün (1m15s, Exit 0); Gate und SQL bleiben durch diesen Fix unverändert.
Unabhängiges Review 5 prüft den exakten Worker-Head; CI läuft auf diesem Head neu.

U4 Fix2 `c1b4322` ist fertig: erste Inventur und spätere Auswahl zählen dieselben belegten
Metadaten. UNKNOWN-Herkunft wird nur an den drei freigegebenen Bindungsstellen mit
Kotlin-validierter Genesis aufgelöst, Kandidatenseiten bleiben auf 500 begrenzt. Worker:
89 Cloud-/100 Data-Tests und Migration grün, vollständiger Check 686 Tasks. Messung bei
20.000 quittierten Köpfen: Status 4,4 ms, Aktivierung 11,8 ms, Bindung 15,7 ms; kein
erneutes Kopf-/Payload-Laden. Review 3 prüft den gesamten U4-Schnitt vor Integration.

K3 Server `683f6db` ist fertig, ausschließlich additive 004 und neue 190-Tests. Eigener
Status bleibt unabhängig von Consent/Gerätebindung; der Servicejob verwendet den vorhandenen
Purgepfad in maximal 100er-Batches. Worker: vollständiger Check 688 Tasks grün;
1223 SQL-Assertions bestanden, zwei bekannte 110-Cronfehler und drei ausdrücklich übersprungene
190-Cronkatalogprüfungen im PG17-Ersatzharness. Echte Lock-/Recovery-/Jobrennen und Frist-Wait
grün; Owner, ACL, Signatur und übriger Rücknahmekörper gegenüber 002 erhalten. Keine
Hosted-/Cron-/UI-/D3-Abnahme daraus ableiten. Unabhängiges Review 1 läuft vor Integration.

| Laufender Auftrag | Task-ID | Ziel und Abnahme |
|---|---|---|
| K1 Review 5 | `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r5` | Read-only auf `4ba0880`, alle bisherigen Befunde plus Intentmatrix, Ship/No-Ship |
| U4 Review 3 | `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-review-r3` | Read-only auf `c1b4322`, Inventar/Herkunft/Leistung und Runner-Invarianten |
| K3 Server Review 1 | `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k3-server-review-r1` | Read-only auf `683f6db`, Rechte, Fristen, atomare Löschung, Rennen und Scheduler |

Alle drei Reviews laufen parallel mit eigenem Task; keine Worker-Pushes oder Live-Deployments.

K1 Review 5 auf `4ba0880`: No-Ship wegen eines belegten P2-Rennens. Ein während laufender
Ablehnung gestarteter Profilabruf mit bisherigem `DECLINED` quittiert das lokale Ablehnungsflag
vorzeitig. Eine ältere Annahme kann danach serverseitig CURRENT setzen, obwohl ihre Antwort
lokal korrekt verworfen wird; die neuere Ablehnung scheitert ohne Commit und ein späterer
Refresh öffnet unerwünscht Cloud/Identität. Ohne Zwischenabruf bleibt der Kontrollfall gesperrt.
Fix5 ist ausschließlich für Authadapter und bestehende Recovery-Integrationstests beauftragt:
abgeschlossene Mutation beim Profilstart erfassen, nicht erst bei Antwort prüfen. Auch verzögerte
Profilantwort nach RPC-Ende muss den fehlenden Startnachweis erhalten. Bisherige Matrix,
Retention-, Busy- und Altantwortbeweise erhalten; Rot→Grün plus vollständiger Check vor Commit.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k1-fix-r5`.
Die unabhängige Wiederholung bestätigt alle bisherigen Korrekturen und 598 Kotlin-Tests;
SQL 878/880 bleibt ausschließlich wegen der zwei bekannten Cron-Stubfehler offen.
PR #64 bleibt Entwurf; Supabase-CI auf `4ba0880` ist grün, Android/Apple laufen noch.

U4 Review 3 auf `c1b4322`: No-Ship wegen eines belegten P2-Seiteneffekts. `refresh()` ruft
die Spielerherkunftsauflösung auf; deren UPDATE aktiviert korrekt den U3-Revisionstrigger.
Unabhängiges Repro: ohne Upload/Fachänderung steigt PARTICIPANTS von Revision 1 auf 2,
PLAYER wechselt UNKNOWN→LOCAL. Drei skalare Reads allein beweisen keine Schreibfreiheit.
Fix3 verlagert die nötige Auflösung an passende bestehende Schreib-/Bindungsstellen oder
berechnet die Inventur rein lesend. Der Trigger bleibt erhalten. Neue Regression vergleicht
vollständige persistierte Herkunfts-, Revisions- und Ackstände vor/nach Statuslesen;
initiale Zählgleichheit, späte Quellenbelege und bisherige Laufzeit-/Uploadbeweise erhalten.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u4-core-fix-r3`.
Review bestätigt separat 89 Cloud-/100 Data-Tests, Migration, vollständigen Check mit
690 Tasks und 20.000er-Inventur in 3,20 ms. Weitere Proben für 501/1001 Events,
Projektion zuletzt, fremde Antwort-IDs sowie alte Retry-Versuche nach Rebind/Sitzungswechsel
und Suspend grün. U4 wird erst nach Korrektur und neuem Review integriert.

K3 Review 1 auf `683f6db`: Ship für den begrenzten Serverteilschnitt, keine belegten
P1/P2/P3. Unabhängige frische Migration und vollständiger SQL-Lauf im eigenen Harness:
1223 Assertions bestanden, ausschließlich bekannte zwei 110-Cronfehler und drei explizite
190-Cron-Skips. Frist-/Authwiderruf-/Job-/Recoveryrennen bestätigt. Zusätzlicher Timeout
während der zweiten Löschung rollt beide Konten vollständig zurück; stabiler Fehlerstatus,
Retry löscht genau zwei. UTC-/Fristkorrektur erhält Owner/ACL/Signatur und übrigen Körper.

Der Orchestrator übernimmt ausschließlich die acht geprüften K3-Dateien unverändert.
Entwurfs-PR des gemeinsamen Integrationszweigs liefert als nächsten Nachweis die echte
Supabase-CI für 003/004 und deren Scheduler-Katalogtests. K1 Fix5 und U4 Fix3 bleiben
getrennte laufende Aufträge; der Integrations-PR ist vor deren Abschluss und gemeinsamer
Abnahme nicht mergebereit. Reale Cron-Ausführung, Hosted, K3-UI, D3 und Apple bleiben offen.

U4 Fix3: Nach Codeprüfung zusätzlich zwei minimale Schreibseams freigegeben:
`PlayerRepository.kt` meldet tatsächliche Neuanlage durch Remote-/Adoptions-/Archiv-/
Platzhalterpfade intern, `LocalData.kt` verdrahtet gezielte UNKNOWN-only-Auflösung genau
dieser Spieler-ID anhand bestehender U3-Quellenbelege. Alles in derselben Transaktion;
kein Herkunftsbeweis aus Spielerart, keine Änderung vorhandener CLOUD-/PEER-/LOCAL-Herkunft,
Writer oder Acks. `ensureSelf`/`addGuest` behalten ihren eigenen lokalen Herkunftsbeleg.
So bleiben auch nach Quellenbindung eintreffende Spieler vor dem ersten Status korrekt,
ohne Statusschreibeffekt. Reihenfolge, fremde/unbelegte Aliase und Rollback gezielt testen.

Gemeinsamer Entwurfs-PR [#65](https://github.com/thealextd1337-spec/racketone/pull/65)
auf `c6703b1` ist verknüpft und wird durch T3 überwacht. Er enthält K3 unverändert;
gemeinsamer Gradle-Check 688 Tasks grün. PR #64 bleibt separater K1-Entwurf.

K1 Fix5 `025efc5` ist fertig und auf PR #64 gepusht. Genau Authadapter und bestehende
Recovery-Integrationstests: `mutationsFinishedAtStart` verhindert eine nachträgliche
Quittierung durch während Pending gestartete Profilabrufe. Dies gilt für DECLINED sowie
REQUEST/RECOVERY; unabhängige Profilabfrage und Export bleiben erreichbar. Sieben zusätzliche
echte Integrationstests: Original `4ba0880` mit 31 Fällen 5 rot/26 grün, Fix 31/31 grün.
Alle bisherigen 24 Fälle und 36 Matrixzellen bleiben erhalten. Fokus JVM/AndroidHost und
Worker-Gesamtcheck mit 688 Tasks grün; 605 Kotlin-Tests ohne Fehler. SQL unverändert und
wie beauftragt nicht erneut ausgeführt. Gemeinsamer Gesamtcheck nach Übernahme ebenfalls
688 Tasks grün (1m16s, Exit 0), gemeinsames Gate und 003/004 dabei unverändert erhalten.
Frisches unabhängiges Review 6 auf exakt `025efc5`:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k1-review-r6`.
Root-CURRENT-/Consent-Generationsgate und Server-003/004 erfordern weiterhin eigene gemeinsame
Abnahme. Beide PRs bleiben bis zu deren erfüllten Bedingungen Entwürfe.

U4 Fix3 `06c264c` ist fertig: Status verwendet ausschließlich drei Counts ohne SQL-Schreiben;
Herkunft wird an freigegebenen Schreib-/Bindungsstellen nachgeführt. Vollständiger Datenbank-
vergleich vor/nach Refresh einschließlich Payloads, Ownership, Herkunft, Revisionen und Acks
grün, initiale `(1,0,5)`-Zählgleichheit erhalten. Worker-Fokus 89 Cloud/104 Data, Migration
und Gesamtcheck 690 Tasks grün. 20.000er-Statusmessung 3,98 ms, Aktivierung/Bindung ohne
erneute Historien-Payloadladung. Frisches unabhängiges Review 4 auf exaktem Head:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-core-review-r4`.

Echte Supabase-CI für gemeinsamen Head `c6703b1`: Run `39133816941`, Job `117694203206`
grün, 32 Dateien/1228 Tests PASS einschließlich aller sechs 190-Dateien und Cron-Katalogtests.
Damit ist die frühere PG17-Stub-Lücke für Migration/Katalogplanung in diesem Stack geschlossen;
periodische tatsächliche Jobausführung und gehosteter Betrieb bleiben offen. Gradle plus
Android-/Wear-APKs ebenfalls grün; Apple-CI zu diesem Prüfzeitpunkt noch laufend.

### Aktuelle Abnahmen und isolierte Fortsetzung

K1 Review 6 auf `025efc5`: Ship ohne belegte P1/P2/P3 oder offene R1–R5-Einwände.
Unabhängig 170 Cloud-/Auth-/UI-Fokustests grün, ursprünglicher R5-Repro rot→grün und
Callbackfehler bestätigt. PR #64 bleibt bis zur grünen CI auf genau diesem Head Entwurf.

Gemeinsames Integrationsreview 1 auf `33c1b8b`: Ship ohne Befund für zusätzliches Root-Gate
und Zusammenspiel 001–004. Eigene 34 bestehende Tests plus 15 reale Integrationsproben grün,
auch verspätete Antworten trotz Cancellation, unabhängiger Export und unveränderte Peer-Acks.
Retentions-/Kontowechsel-/Hostgrenzen erhalten. Task:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-joint-gate-review-r1`.
PR #65 wartet noch auf die finale CI; Supabase ist bereits grün. Der ältere Workflow auf
`c6703b1` wurde durch den neuen Head abgebrochen; dessen einzelner Supabase-Job bleibt ein
gültiger bestandener SQL-Nachweis, kein grüner Gesamtworkflow.

U4 Review 4 auf `06c264c`: Ship ohne Befund. Unabhängiger Fokus, Migration und Gesamtcheck
690 Tasks grün; Status ohne SQL-Schreibaufruf, initiale Zählgleichheit und Quellenreihenfolgen
bestätigt. Integration mit aktuellem K1 wird in einem neuen isolierten Worktree ab `33c1b8b`
ausgeführt, um den parallel geprüften Rootstand unverändert zu halten. Besitz ausschließlich
17 U4-Dateien plus nötigenfalls ein gemeinsamer JVM-Grenztest. Root-Gatefences, Authadapter
und Backend-SQL erhalten; volle gemeinsame Tests und unabhängige Prüfung vor PR/Merge.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-k1-integration-impl-r1`.

Parallel grenzt ein lesender Auftrag den K2-Serverteilschnitt anhand der bestehenden
Export-Whitelist ab: private ZIP-Ausgabe, Ablauf/Aufräumen und idempotente Anforderung;
noch keine Implementierung oder API-Erweiterung. Clientbedienung bleibt eigener Folgeschritt.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-server-scope-r1`.

### K2 – abgegrenzter Serverauftrag

Die Codeerkundung bestätigt: bislang keine Edge Functions, Storage-CI oder ZIP-Auslieferung.
Kleinster vollständiger Serverteilschnitt: additive `20261018000500_private_data_exports.sql`,
`functions/export-my-data`, `functions/cleanup-data-exports`, funktionslokale Deno-Konfiguration
und Lockdatei sowie neue `200_private_data_exports*`-SQL-/Parallel-/HTTP-/Storage-Tests.
Ein synchroner begrenzter Produzent reicht zunächst; keine stillen Teilarchive. Technische
Grenzüberschreitung ist ein sichtbarer Fehler und keine K2-Abnahme für das betroffene Konto.

Bestehende `export_my_data()`-Auswahl samt Consent-/Supporthistorie wiederverwenden;
verschachtelte Format-/Meta-/Summary-/Eventdetails und Ticketfelder additiv in 005 nach
dem vorhandenen Fachschema whitelisten. Freitext bleibt ausdrücklich Freitext; zusätzliche
technische Schlüssel und fremde strukturierte Referenzen dürfen nicht durchgereicht werden.
Eigene Kontorechte verwenden `require_account_session()` auch bei Anforderung/Status/Download,
ohne Consent-/Gerätezwang und ohne `auth.getUser()` als Ersatz für Sitzungswiderruf.

HTTP-Vertrag v1: POST mit `requestId` erzeugt idempotent; GET Status; GET Download prüft
erneut die eigene Sitzung und liefert ZIP ohne Cache. ZIP enthält vollständiges `data.json`
und CSVs aller Exportbereiche mit festen Spalten/Pfaden und Formelschutz. Private Storageobjekte
werden nicht über dauerhaft berechtigende Signed-URLs herausgegeben. Metadaten/CAS-Versuche,
Leasewechsel und Cleanupqueue sichern parallele Anforderung, verlorene Antworten und späte
Produzenten; Purge invalidiert alte Archive auch bei enthaltenen redigierten Mitspielern.
Physisches Löschen erfolgt über Storage-API, nicht durch SQL-DELETE in `storage.objects`.

Besitz des Implementierers ausschließlich neue 005-/200-Dateien und beide Function-Verzeichnisse.
Historische Migrationen, Kotlin/UI und Authadapter bleiben unberührt. Root besitzt
`supabase/config.toml`, CI-/Deploy-Workflows und Dokumentation; notwendige Integrationswerte
meldet der Worker konkret. Keine Live-Deploys oder erfundenen Secrets. Geplanter Cleanup
über Cron/pg_net mit Vault-Bezug bleibt ohne echte Einrichtung ausdrücklich unbewiesen.
Akzeptanz: entpackbares vollständiges ZIP/CSV, Whitelist-/Rechte-Negativfälle, Widerruf,
Expiry, echte parallele Reservierung/Leaseübernahme, Upload-/Finalizefehler, Purge-Rennen und
physischer Cleanup; Deno-/SQL-/HTTP-Tests plus vollständiger Gradle-Check vor Commit.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-server-impl-r1`.

U4/K1-Integration ist als `9ecb8d2` auf Parent `33c1b8b` fertig, eigener sauberer Worktree
`t3-72b6c4c1`, Branch `t3/u4-k1-integration`. Alle 16 U4-Dateien außer Gate sind bytegleich
zum abgenommenen `06c264c`; Gate ergänzt ausschließlich Import/Inventory-Methode.
Authadapter und Backend-SQL sind bytegleich zum geprüften Parent. Ein neuer realer
Runner-/Auth-/Gate-/MatchSession-/SQLDelight-/HTTP-Test prüft Consentverlust, alte Retries/Acks,
unabhängige Kontorechte, Retention und Kontoisolation. 691 Fokusprüfungen mit Migrationsprüfung
und vollständiger Check 692 Tasks grün. Keine Abschwächung bestehender Tests.
Frisches unabhängiges Integrationsreview:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-k1-integration-review-r1`.
[Entwurfs-PR #66](https://github.com/thealextd1337-spec/racketone/pull/66) ist verknüpft;
CI prüft diesen kombinierten Head. Vorläufer #64/#65 sind bis zu deren Merge enthalten.
Root übernimmt U4 erst nach Integrationsreview; Android-Host und echte Stack-/Socketabnahme
bleiben eigene anschließende Umsetzung beziehungsweise Nachweise.

U4/K1-Integrationsreview 1 auf `9ecb8d2`: Ship ohne belegte P1/P2/P3. Unabhängig 691
JVM-/AndroidHost-Tests plus Migration grün, fünf weitere Testmethoden mit neun Abläufen
einschließlich nicht kooperativ verzögerter HTTP-Antworten. Aktuelle Rootfences erhalten,
Inventur lehnt Konto-/Profil-Leases ab, Runner-Close beendet ausschließlich den Runner-Child.
Auth/LocalData und unabhängiger Export bleiben nutzbar; Peer-Acks unverändert.
PR #66 wartet auf CI; keine erneute vollständige SQL-Suite bei unverändertem Backend.

Nachfolgende Android-Hostverdrahtung und D1-Pullkern werden parallel rein lesend abgegrenzt,
damit gemeinsame Dateigrenzen vor Implementierung feststehen. K2 bleibt alleiniger Besitzer
neuer Backend-005-/200-/Edge-Dateien. Keine Host-/Pulländerung vor konkretem Briefing.
- U4 Android-Scope: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-android-scope-r1`.
- D1 Scope: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-scope-r1`.

PR #65 auf exakt `33c1b8b` vollständig CI-grün: Run `39134584961`, Ubuntu-Check/APKs,
Apple-Native-/Simulator-Builds und Supabase erfolgreich; Admin unverändert übersprungen.
Gemeinsames Review auf demselben Head Ship, keine Kommentare/offenen Reviewbefunde.
PR als bereit markiert. Merge wartet auf Vorläufer #64, dessen Apple-Job zum Prüfzeitpunkt
noch läuft; Ubuntu und Supabase dort bereits grün. Keine Pollschleife oder Freigabe aufgrund
eines anderen Heads. Beide PR-Watches bleiben aktiv.

### Nächste konkrete Clientaufträge

| Paket | Exklusiver Besitz | Begrenztes Ergebnis / Abnahme |
|---|---|---|
| U4 Android-Host | `PhoneAccount.kt`, neuer `PhoneUploadHost`, schmaler `PhoneUploadPort` und optionaler AuthViewModel-Anschluss, neuer Account-Uploadbereich/Einbindung, nur `cloud_upload_*` und `auth_ready` DE/EN, lesender Quellenkatalog in `AccountIdentityRepository`/`LocalIdentity.sq`, neuer Uploadport-Decorator, passende Tests | Explizite Bindung/Upload nur nach Nutzeraktion mit aktueller Phone-ID und CURRENT; Retry bindet nicht neu; ehrlicher unbekannter/ausstehender/abgewiesener Status; Main-Kontext, eigenes Upload-Busy, Cancel+Join vor Authclose; gemeinsame DB bleibt prozessweit erhalten. Keine Pull-/K2-/K3-UI-/Gateänderung. |
| D1 Pullkern | Neue Pull-Runner/Port/State/Mapping und CloudPullStore/SQL/Tests, ausschließlich Pull-Seams im Backend, versionierte Cloud-Aktualisierung in CloudSyncMetadata, `6.sqm`→Schema 7 und minimale LocalData-Schemaanbindung | Bestehende v1-RPCs, Cursor und Downloadpersistenz nutzen. Metadatenseite samt dauerhafter Eventarbeit/Cursor atomar; Eventseiten/Empfangsnachweise/Cursor getrennt atomar. Begrenzte Läufe, Wasserstandheilung auch ohne neue Kopfversion, Neustart und Epochenvollabgleich. Lokale/Peer-Bestände schützen, D2/D3 nur als offene Arbeit vormerken. Keine Backend-/Android-/Auth-/Gate-/Engineänderung. |

Beide neuen Worktrees übernehmen `9ecb8d2` ausschließlich als geprüfte Abhängigkeit; danach
eigene Featurecommits. Keine Root-/fremden Worktreeedits. Android besitzt AccountIdentity-
Leseseam; D1 besitzt CloudSyncMetadata und LocalData-Schema, keine gegenseitige Dateiänderung.
K2 besitzt weiterhin allein neue Backend-/Edge-Dateien. Zusätzlich nötige Dateien vorab melden.
Tests gegen reale Gate-/DB-/HTTP-Seams, Migration, Main-/Cancellation-/Lifecyclegrenzen und
DE/EN bei 200 Prozent; jeweils vollständiger Check vor Commit, unabhängiges Review danach.
Lokale Tests sind keine echte Hosted-/Socket-/K4-/Pilotabnahme.

- U4 Android-Implementierung: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-android-impl-r1`.
- D1 Implementierung: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-impl-r1`.

K2 meldet erste lokale Nachweise: 59 SQL-Fachassertions, acht echte Parallelrennen und neun
Deno-Tests grün; Gesamtcheck 692 Tasks grün. Harte 200-Plattformprüfungen für Storage/Cron/
pg_net fehlen im lokalen PG17-Harness ausdrücklich. Root integriert nach finalem Commit
beide Function-Konfigurationen und echte Storage-/Edge-CI; keine Ersatzdienste oder Skips.

K1 PR #64 ist nach Review6 und allen relevanten CI-Jobs auf exakt `025efc5` als
`d9f055c` gemergt. Deployworkflow ist ausschließlich manuell; kein Deployment ausgelöst.
Vor PR65-Merge zeigte der historische Mergebaum einen Gatekonflikt und einen automatischen
020-Fixture-Doppelimport. Auflösung bewahrt den bereits gemeinsam abgenommenen Root-Gatebaum
einschließlich vollständiger K1-Fences und die 003-Testkataloghelfer. Vor Dokuänderung ist der
aufgelöste Gitbaum exakt identisch zum bisherigen geprüften Rootbaum; keine Produktiv-/SQL-
Änderung aus der Auflösung. Vollständiger Check danach 688 Tasks grün (29s, Exit 0).
Main wird als eigener Merge übernommen; PR #65 erhält deshalb einen neuen CI-Lauf statt
einen unbekannten Mergebaum freizugeben. Watch #64 beendet; weitere Arbeit bleibt aktiv.

U4 Android: zusätzlich ausschließlich `testImplementation(libs.ktor.mock)` und
`testImplementation(libs.sqldelight.sqlite.driver)` im vorhandenen Android-Build freigegeben,
keine neue Version/Produktionsdependency. Serielle injizierte Testgrenze ersetzt keine
Android-Geräteabnahme; produktiv Main.immediate und Looper-Guard erhalten.

K2 Server `d02c7a8` ab Parent `7fefc43` fertig, sauberer Worktree `t3-95f9b0da`.
20 ausschließlich neue freigegebene Dateien; kein Kotlin/Root/Docs-Edit. Zwölf Deno-Tests
samt Typprüfung/Lint/Format, 59 SQL-Fachassertions, acht echte Parallelrennen und voller
Gradle-Check 692 Tasks grün. Plattformlücken bleiben harte Fehler: fünf lokale SQL-Fehler
aus fehlendem Storage/Cron/pg_net, drei bestehende K3-Cron-Skips unverändert; echter
HTTP-/Storage-Test lokal ausdrücklich nicht erfolgreich. Keine K2-Abnahme daraus ableiten.
Budgets 4 MiB JSON, 16 MiB ZIP, 100.000 CSV-Zeilen, 750ms Serializer; sichtbarer 413 statt
Teilarchiv. Unabhängiges Review 1 auf exakt diesem Head läuft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-server-review-r1`.
Root ergänzt nach Review Function-Konfiguration und echte Storage-/Edge-CI; keine automatische
Live-Auslieferung oder Secrets im Repo. K2/UI und Konten oberhalb der Budgets bleiben offen.

PR #66 wurde im eigenen Orchestrator-Hilfsworktree `p2-u4-main-sync` abgeglichen und per
normalem Fast-forward auf `270782b` gepusht. Code, Tests und SQL bytegleich zum geprüften
`9ecb8d2`; nur README/Plan neuer. Vollständiger Check 692 Tasks grün, neue CI läuft.
Keine fremden Worktreeedits/History-Rewrites. Der Hilfsworktree enthält den eigenen Merge.

D1: für echten Runner-/DB-Close-Reopen-Test zusätzlich ausschließlich
`jvmTest.dependencies { implementation(libs.sqldelight.sqlite.driver) }` in
`shared/cloud-sync/build.gradle.kts` freigegeben. Bestehender Katalogeintrag, keine neue
Version oder Produktionsdependency. Dependencycommit `4a95352` enthält unverändert U4;
dessen Gesamtcheck 692 Tasks grün. Schema-7-/Pullimplementierung und eigene Abnahme laufen.

K2 Review 1 auf `d02c7a8`: No-Ship, zwei belegte P2 und ein P3. Rekursives Null-Stripping
entfernt fachlich relevante explizite Nullwerte in MatchCreated-Regeln; im entpackten ZIP
bestätigt. Unterschiedliche Request-ID B wird während A fälschlich als Pending angekündigt,
obwohl kein B existiert und GET dauerhaft 404 liefert. Array als Request-ID passiert den
Regex durch implizite Konvertierung und führt zu 503 statt 400.
Fix1 innerhalb K2-Besitz beauftragt: Whitelist und vorhandene erlaubte Nullwerte semantisch
erhalten, Typ vor Regex prüfen; kontoweite Belegung durch andere Request-ID ausdrücklich
als `409 export_busy` mit Retry-After melden. Dieselbe existierende Pending-ID bleibt
202/pollbar; kein zweiter Produzent oder neues Queueframework. Diese technische HTTP-Ergänzung
ist freigegeben, Client noch unverdrahtet. SQL-/Deno-/ZIP-Rot→Grün, echte Parallelfälle und
vollständiger Check vor separatem Commit, danach neue unabhängige Reviewrunde.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k2-server-fix-r1`.

U4-Host interne Schnittstelle präzisiert: `CloudUploadPort` plus suspendierende
`bindDevice(String)`-Funktion kommen aus derselben Backendinstanz. Der reale Backendaufruf
wird im Drain-Test durch einen suspendierten Host-Child-finally ergänzt, damit dessen
vollständiger Abschluss vor Authclose bewiesen wird; ein Ktor-Engine-Job allein genügt nicht.
Keine neue Datei/Produktionsdependency oder Gate-/Backendänderung.

U4-UI-Visualprüfung: tatsächlicher Account-Host nutzt `Box.background` ohne ContentColor-
Bereitstellung. Neuer Uploadbereich darf deshalb gezielt `LocalContentColor` aus bestehendem
`MaterialTheme.onBackground` bereitstellen; Test nutzt denselben Host statt kaschierendem
Test-Surface. Vorbestehender Kontrastfehler übriger Kontotexte bleibt separat für Root-
Integrationsprüfung erfasst, kein breiter Theme-/Accountumbau im Worker. Screenshotbeleg
und DE/EN-200%-Prüfung mit finalem Fullcheck vor Commit erforderlich.

K2 Fix1 `ff13429` ist fertig: sieben freigegebene Dateien. Explizite fachliche Nullwerte
bleiben im SQL→ZIP→JSON/CSV-Rundlauf erhalten; andere aktive Request-ID ergibt 409
`export_busy`/Retry-After 3, dieselbe ID bleibt 202 abrufbar; falsche ID-Typen 400 ohne
Backendaufruf. Baseline rot, Fix mit 19 Deno-/65 SQL-Fachassertions, acht Parallelrennen und
Gesamtcheck 688 Tasks grün. Harte Plattformlücken unverändert. Frisches Review 2:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-server-review-r2`.

U4 Android Feature `2e135ac` auf Dependency `7fd1f69` fertig: 17 Dateien, 506 Fokusprüfungen,
Migration und Gesamtcheck/APK mit 740 Tasks grün. Root hat Host/Decorator/UI/VM-Diff und
Desktop-Screenshots gesichtet: Uploadbereich lesbar; bestehende übrige Kontotexte im echten
Host ohne ContentColor schlecht lesbar. Keine Android-Geräteabnahme aus Desktopbildern.
Gezielte native Impeccable-Prüfung nutzt bestehende Material-Tokens/Komponenten, kein Redesign
oder neues Produkt-/Designdokument. Frisches unabhängiges Host-/Retry-/Lifecycle-/UI-Review:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-android-review-r1`.

D1 darf ausschließlich im atomaren Eventdownload entstandene PARTICIPANTS-Projektionsrevision
als empfangene Cloud-Basis sauberstellen, wenn vorher sauber, Herkunft CLOUD und demselben
materialisierten Empfangsprofil zugeordnet. Exakte Vor-/Nachrevision und Profil prüfen;
vorher dirty/local/peer/anderes Konto sowie Event-/Geräte-Acks/Rejections bleiben unverändert.
Positive anschließende versionierte Kopfaktualisierung und alle Negativkontrollen beweisen;
kein pauschales Upload-Ack. Änderung innerhalb CloudPullStore/Mappingtest, anschließend
erneut Fokus und Gesamtcheck vor Commit.

Der Integrationszweig ist mit Server/K4 aus `origin/main` abgeglichen. Die beiden
Testfixture-Konflikte erhalten die bereits gemeinsam geprüften kanonischen Sitzungen,
JWT-Ablaufclaims und die relative Sessionzählung; keine Sitzung wird nach Widerruf neu angelegt.
Danach vollständiger Gradle-Check mit 686 Tasks grün und SQL-Gesamtlauf erneut bis auf
die zwei bekannten lokalen pg_cron-Nachweise grün.

### K2-Serviceintegration und D1-Review

PR #65 auf `3265c6f` vollständig CI-grün (Run `39137204547` einschließlich Apple und
echtem Supabase); als `cb14420` nach main gemergt. Kein Deployment ausgelöst.

K2 Review 2 auf `ff13429`: `ship`, alle drei Vorbefunde geschlossen. Unabhängig 19 Deno-Tests,
65 SQL-Fachassertions, acht Parallelrennen, SQL→ZIP/CRC→JSON/CSV-Rundlauf und zusätzliche
Lease-/Downloadablauf-Lockproben grün. Plattformnachweise bleiben bis zur echten Service-CI offen.
Root integriert ausschließlich diesen Kandidaten im eigenen Worktree `p2-k2-server-integration`
auf Branch `p2/k2-server-integration`; keine fremden Worktrees bearbeitet.

Root besitzt zusätzlich `supabase/config.toml`, `.github/workflows/ci.yml`, neuen lokalen
CI-Helfer `.github/scripts/export_stack_ci.py` und Dokumentation. Beide Functions prüfen ihre
eigene Berechtigung bei `verify_jwt=false`. CI startet echte Storage-/Edge-Dienste, erzeugt
einen ausschließlich lokalen Zufallsschlüssel, verbindet Vault und prüft pgTAP, den
SQL-ZIP-Rundlauf, echte Auth-/HTTP-/Storage-Wege sowie Vault→pg_net→Cleanup. Keine Ersatzdienste
oder neuen Skips. Lokaler Root-Gesamtcheck: 692 Tasks grün; Deno 19/19, Typen/Format/Lint,
TOML und Actionlint grün. Docker fehlt lokal; Service-CI noch ausstehend, separater PR/Review folgt.
Periodischer Cronbetrieb, Hosted-Limits und Phone-ZIP bleiben eigene offene Abnahmen.

Konkreter K2-Integrationsstand `79b5faa` ist als Draft-PR #67 angelegt, verknüpft und überwacht.
Die 20 Serverdateien bleiben bytegleich zum geprüften `ff13429`. Neue unabhängige Prüfung
der Root-Konfiguration/Service-CI:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-integration-review-r1`.
Der aktuelle Integrationszweig übernimmt main `cb14420`; ausschließlich der add/add-Konflikt
im Ausführungsplan wird durch den vollständigeren eigenen Plan aufgelöst, Produktbaum unverändert.

D1 Feature `68d23de` gegen Dependency `4a95352` fertig: 19 Dateien, Schema 6→7, dauerhafte
Metadaten-/Eventseiten, Vollscan und Empfangslückenreparatur. Worker 235 Fokusprüfungen,
Migration und 688 Gesamtcheck-Tasks grün; echter Stacktest ausdrücklich 1× SKIPPED mangels
Wegwerfstack. Root hat Scope, additive Backend-Leases und Runner/Projektionsseams gesichtet.
Neues unabhängiges Review:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-review-r1`.

### Nächste isolierte Umsetzungspakete

### U4-Kern auf main abgeschlossen

PR #66 wurde auf exakt `09bdd1b` nach grüner Ubuntu-/APK- und Apple-Simulator-CI
(Run `39140546089`) zusammengeführt; main ist `870792a`.
Der anschließende Root-Abgleich löst ausschließlich den Plankonflikt und erhält beide
Nachweise. Produktbaum gegen Root `eb187f5` unverändert; der vollständige grüne
744-Task-Check mit Android-APK gilt fort. PR #68 benötigt wegen seines konkreten
Plankonflikts diesen Mainabgleich und anschließend CI auf dem neuen Head.
K2-A/B und D1-Korrektur 2 arbeiten weiterhin in ihren getrennten Worktrees.

### K2 Phone – konkrete parallele Verträge

#### Aktueller Abschluss und Reviewbesitz

PR #67 ist nach exakt grünem `ba90a1d` zusammengeführt; main `8660716`, Run
`39144135593` mit fünf erfolgreichen Prüfungen einschließlich echter Service-CI und
Apple-Native-/Simulator-Builds. Root gleicht PR #68 danach ab: nur Plankonflikt,
Kotlin/Android/Wear unverändert zur geprüften Basis, Server/CI/Katalog bytegleich main.
Gesamtcheck vor Mergecommit und neue CI danach; keine automatische Livebereitstellung.

K2-A Review 3 bestätigt `d73f372` ohne Befunde. 137 JVM-/85 Android-Host-Tests frisch
grün; unabhängige native Probe hält DNS, Terminalcallback und offenen Body getrennt
zurück: Export und Register bleiben offen, erst nach Freigabe native Calls/Pool null
und eigener Executor beendet. Android-Schnitt damit zur Integration freigegeben;
JVM/iOS-ZIP bleibt ausdrücklich vor Netzwerk gesperrt. A/B-Verträge sind unabhängig
geprüft; gemeinsame Basis und Android-Dateihost können als nächste eigene Lane folgen.

D2/D3-Ergänzung verlangt einen expliziten Herkunftsstamp neben Cloudstamp, ohne fehlenden
Stamp als Freigabe zu deuten. Bestehende Provenienz und U2-Sourcebelege zuerst wiederverwenden;
historische unbewiesene Importe dürfen keinen Cloudentzug umgehen. ID-only-Assignment über
nachgestellte Defaultparameter und eigenen serialization-core-Serializer, Legacyform und
Goldens erhalten. Redaktionsmanifest maximal 200 IDs/500 Events pro Seite plus Bytebudget,
durabler Transfer und atomarer Abschluss statt unbegrenzter Liste. Root legt die endgültigen
kleinen Lane-Verträge vor Umsetzung fest; noch kein Protokollwechsel aktiviert.

K2-A-Nativfix `d73f372` fertig, Parent `c58a96f`, genau neun freigegebene Dateien.
Android-Transport pro Aufruf besitzt Client/Dispatcher/Pool/Listener; Ktor-Join, native
Terminalevents und Dispatcher-idle gemeinsam vor Operationsabschluss. Öffentliche API,
Zeitlimits und RPC-Pfad unverändert. JVM/iOS und uninstrumentierte Injection verweigern
ZIP vor Netzwerk mit `export_unavailable`. 137 JVM-/85 Android-Host-Tests einschließlich
16 nativer Tests, ktlint und Gesamtcheck/APKs grün; alle bisherigen 133 JVM-Tests erhalten.
Root hat tatsächlichen Produktdiff gelesen, frisches unabhängiges Review 3 läuft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-http-review-r3`.

D1-Stack-CI-Abhängigkeit `2307a3a` vorbereitet und sauber: D1 `25dc96b` plus geprüfte
K2-Service-CI `ba90a1d`, danach aktueller Rootplan; ausschließlich Plankonflikte.
Gemeinsamer Gesamtcheck 692 Tasks grün (3m29s), zusätzlicher Planmerge produktidentisch.
Isolierter Harnessauftrag läuft im eigenen Worktree `t3-1bf18c5e`, Branch
`t3/d1-stack-ci-harness`; vorbereitete Basis als Vorfahr bestätigt. Exklusiver Besitz:
neuer Harness/gezielte Guardtests, CI-Anschluss und minimale Stacktest-Fixturekorrektur.
Keine D1-Produkt-/Serveränderung. Genau ein echter Test ohne Skip als hartes XML-Gate.
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-stack-ci-impl-r1`.

D2/D3-Vertragsentwurf liegt vor: vollständig geordnete serverseitige `visibilityEpoch`
auch für Grants, dauerhafter Personen-ID-Redaktionsnachweis und explizite ID-only-
Assignmentversion. Vor Umsetzungsvergabe noch eng präzisieren: P1-Gast-/Offline-/Peer-
Matches ohne Cloudsnapshot dürfen keinen Freigabebypass und keinen Funktionsverlust
erzeugen; Legacy-API/Goldens müssen erhalten bleiben; Manifest-/Eventtransfer begrenzen
und nach Neustart fortsetzen. Ausschließlich lesende Ergänzung, keine Implementierung:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-d2d3-contract-refine-r1`.

D1 ist separat auf `25dc96b` integriert, eigener Root-Worktree `p2-d1-pull-integration`,
Branch `p2/d1-pull-integration`, Basis Android `dbb7ed1`. R4 bestätigt `89a6adb` ohne
offene Befunde. Einziger Mergekonflikt: `CloudPullPort` zusätzlich zu Upload-/Backend-Port;
alle 22 D1-Dateien nach Auflösung bytegleich Worker, Android-/Auth-/Gate-/SQL-/CI-Stand
unverändert. Gemeinsamer Gesamtcheck samt beiden APKs 761 Tasks grün (2m49s).
Frisches unabhängiges Integrationsreview läuft, eigener PR folgt getrennt von #68:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-root-integration-review-r1`.
Echte D1-Stackabnahme noch offen; konkrete Wiederverwendung der K2-Service-CI wird lesend geprüft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-stack-ci-scope-r1`.

Root hat den konkreten K2-A-Nativschnitt freigegeben: Android erhält pro Export-HTTP-
Aufruf eigenen instrumentierten OkHttp-Client/Dispatcher/Pool/Listener. Ktor-Join,
native Terminalevents und eigener Dispatcher-idle gemeinsam vor Operationsabschluss.
Neun festgelegte Dateien im A-Worktree, öffentlicher Port unverändert, keine neue Version
oder globale SDK-/RPCänderung. iOS- und produktives JVM-ZIP sowie uninstrumentierte
native Injection werden für diesen Android-Pilotschnitt vor Netzwerk ausdrücklich mit
`export_unavailable` verweigert. Bestehende Auth-/RPC-/Kontorechte bleiben erhalten;
Apple-/JVM-Nativabschluss als getrennte spätere Lieferung. Keine Engine-Namensheuristik,
kein unbewiesener Ktor-Job-Drain. DNS-/Header-/Body-/Fehlerbarrieren und bisherige Fences
vor Gesamtcheck und frischem Review 3 erforderlich. Umsetzung läuft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k2-phone-http-fix-r2-impl`.

Android-CI-Review 1 bestätigt `15f08cf` ohne Befunde; 13 Hosttests unabhängig grün.
Transportcancel ist nachgewiesen keine Host-Publikationsbarriere, der 15-s-StateFlow-Wait
erhält alle ursprünglichen Assertions und verhindert über `IDLE` Scheinerfüllung.
Root übernimmt exakt diese einzelne Testdatei ohne Konflikt; Produktcode und öffentliche
API unverändert. Gemeinsamer Gesamtcheck mit Phone-/Wear-APK vor Integrationscommit
761 Tasks grün (1m1s, `advantly-android-ci-root-integration-check.log`), danach neuer
CI-Head für PR #68. Kein Merge des fehlgeschlagenen `6da7517`.

K2-A Review 2 auf `c58a96f` bestätigt alle sieben Ktor-Drainfälle, findet aber einen
neuen P1 am nativen OkHttp-Callback: blockierte DNS-Auflösung bleibt aktiv, obwohl der
Request-Job beendet und die Exportoperation aus dem Register entfernt ist. Eigene Probe
mit echtem Ktor 3.5.1/OkHttp 5.3.2 bestätigt dies vor separater DNS-Freigabe. Keine alte
Veröffentlichung, aber weiterhin falsche Close-Barriere. Vor Produktänderung ist im
bestehenden A-Worktree die minimale instrumentierte native Engine-/Call-Completion-Seam
abzugrenzen; Android/JVM/iOS und injizierte Engines ausdrücklich behandeln, keine
unbelegte Parität aus Ktor-Jobabschluss ableiten. Öffentlicher Port und RPC/Authpfad bleiben.
Konkrete Dateien/API gehen vor Edit an Root, keine Nutzerrückfrage und kein neues Framework.
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k2-phone-http-fix-r2-scope`.

D1-Korrektur 3 `89a6adb` fertig, sechs autorisierte Dateien; indizierte Einzelquery und
Fences ohne Schema-/Index-/EventStoreänderung. Originalrepros rot→grün, genau 2/500
Payloadzeilen bei 1.000/10.000 Bestandsereignissen, 10.000er-Reparatur über Wiederöffnungen
und lokale Abbrüche/Teil-Receipts atomar geprüft. 268 Tests plus Migration grün, ein realer
Stacktest bleibt ausdrücklich offen; Gesamtcheck 692 Tasks grün. Root-Produktdiff gelesen,
frisches unabhängiges Review 4 läuft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-review-r4`.

Android-CI-Fix `15f08cf` fertig: ausschließlich `PhoneUploadHostTest.kt`, keine Produkt-
oder APIänderung. Anhalten nur des Hostdispatchs beweist Transportcancel vor Hostpublikation;
alter Wait deterministisch rot, explizite StateFlow-Resetbarriere grün. 13 Host-/21 App-Tests,
Main.immediate-Owner-Vertragsprobe und Gesamtcheck samt APKs 761 Tasks grün. Alle ursprünglichen
zehn Transitions-/History-/Ack-/Altantwortassertionen erhalten. Root hat Diff/Übergabe gelesen;
frisches Review vor Integration in PR #68:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-u4-android-ci-review-r1`.

K2-A Drainkorrektur `c58a96f` gegen `acdd93e` fertig, sauber und genau zwei Dateien:
`CloudHttp.kt` plus bestehender Export-HTTP-Test. Request-Lifetime ab Requestaufbau erfassen,
im `finally` cancellationfest canceln und vollständig joinen; öffentliche API unverändert.
Drei Originalrepros rot→grün, sieben Drainfälle einschließlich Pre-Header-/Body-/Timeout-/
Caller-/Sink-/MIME-Fehler mit separater Transportbarriere grün. Vor Freigabe bleiben Export
und Transport unvollendet; danach beide beendet. 133 Cloud-JVM-Tests, ktlint, Gesamtcheck
und Phone-/Wear-APK grün. Dauerhaft blockierter Transportabbau hält die Operation bewusst
offen; kein falscher Drain. Root hat Produktdiff geprüft; frisches unabhängiges Review 2 läuft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-http-review-r2`.
Android-C bleibt bis zu diesem Review ungestartet; D1-/Android-CI-Korrekturen laufen getrennt.

PR #68 auf `6da7517`: Ubuntu-Job `117723125763` scheitert in einem von 19 Androidtests.
CI-XML wurde gelesen: nicht der `runBlocking`-Einstieg 591, sondern `assertFalse` für
`canRefreshStatus` in `PhoneUploadHostTest.kt:627`. Der Test wartet zuvor auf ein
MockEngine-Cancellationsignal, danach prüft er den Hostzustand. Ursache noch nicht
festgelegt: kausale Reihenfolge von Auth-/Gate-Entwertung, Transportabbruch und serieller
Host-Publikation muss deterministisch belegt werden. Enger Diagnose-/Korrekturauftrag
im bestehenden Android-Worktree, Besitz nur `PhoneUploadHost.kt` und direkter Test;
keine Sleeps, Wiederholungen oder abgeschwächten Sicherheitsassertionen. Rot→Grün,
alle bestehenden Transitions-/Drain-/Refreshfälle und vollständiger Check/APKs erforderlich.
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-u4-android-ci-fix-r1`.

D2/D3-Erkundung abgeschlossen, ausschließlich lesend. Benötigt reversiblen Zugriffszustand
statt `deleteMatch`-Tombstone, dauerhafte Offline-Zustellung unabhängig vom Event-Ack,
Redaktionsnachweis bis zur speziellen Store-Operation und koordiniertes Protokoll 4.
Konkrete vorhandene Seams und Tests liegen im Taskresultat. Vor Umsetzung Root-Verträge
für monotone Zugriffsgeneration bei vollständigem Verschwinden, autorisierte Personen-
Whitelist und neue namenfreie Assignmentdarstellung festlegen. Persistenz-/Engine-/Protokoll-
Besitz kann danach teilweise parallel laufen; `MatchSession.kt` bleibt bei genau einem
Integrator. D1 und seine aktuelle `MatchEvent.sq`-Korrektur zuerst abschließen; keine
überlappende Persistenzumsetzung gestartet. Reale Offline-Uhr-/G8-Abnahme bleibt offen.

D1 Review 3 auf `b44c5ab` hält sämtliche R1-/R2-Befunde geschlossen, findet aber einen
neuen P2 in wiederverwendetem Downloadcode: pro Duplikat lädt `storeDownloaded` das
vollständige Matchlog. Bei 10.000 Bestandsereignissen lädt eine Zweierseite 20.000,
eine 500er-Seite fünf Millionen Payloadzeilen; ein Lauf mit 1-s-Frist dauerte etwa
5,9 s und rollte ohne Cursorfortschritt zurück. Zwei Strukturregressionen belegen den Fehler.
Enger zusätzlicher Besitz freigegeben: `CloudSyncRepository.kt`, ausschließlich additive
Einzelquery in `MatchEvent.sq` (vorhandener ID-Primärschlüssel, keine Migration) und bei
Bedarf bestehender `CloudSyncRepositoryTest.kt`. Tatsächlichen Einzelinhalt prüfen,
Fences zwischen begrenzten Einheiten; Konflikt-/Ack-/Tombstone-/Batchrollbackregeln erhalten.
Keine EventStore-/Peer-/Engine-/Backend-/Auth-/U4-Erweiterung. Korrektur im bestehenden
isolierten D1-Worktree mit Originalrepros, Fokus/Migration und Gesamtcheck beauftragt:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-d1-fix-r3`.

PR #67 ist nach Mainabgleich auf `ba90a1d` konfliktbereinigt und gepusht; gemeinsamer
Gesamtcheck 688 Tasks grün (2m10s). K2-Server/CI unverändert zu `8e49ccc`, U4-Dateien
bytegleich main `870792a`. Echte Service-CI auf `8e49ccc` vollständig grün, einschließlich
Storage-Bereinigung, altem JWT nach Auth-Logout und Vault→pg_net→Cleanup. CI auf dem
neuen Mergehead bleibt nötig. Kein Hosted-/periodischer Cron-/Phone-Beweis daraus abgeleitet.

K2-A Review 1 meldet einen P1: Exportoperation kann unter Cancellation/Timeout bereits
enden, während der tatsächliche Transport noch abbaut. Belegt vor Headern, bei Metadaten-
Timeout und während Body-Lesen; alte Erfolge bleiben gefenct, Close-/Drain-Barriere verletzt.
Enge Korrektur im bestehenden A-Worktree beauftragt, öffentliche API und sechs Besitzdateien
beibehalten. Tests müssen vor separater Transportfreigabe einen noch offenen Exportjob
beweisen; danach cancellationfest tatsächlichen Abschluss abwarten. C wartet auf Review 2.
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-k2-phone-http-fix-r1`.

K2-B Review 1 bestätigt `99f19cf` ohne Befunde: sechs Dateien, Port-/Aktionsvertrag und
DE/EN-Hell/Dunkel-200-Prozent-Bilder geprüft. Ausschließlich isolierte UI-Lane freigegeben;
Host-/Account-/Datei-/Freigabevertrag bleibt C/D. Der nächste freie unabhängige Arbeitsstrang
bereitet D2/D3 ausschließlich lesend vor: konkrete Store-/Peer-/Protokoll-4-Grenzen und
prüfbare Pakete, keine vorgezogene Umsetzung oder Änderung an D1.
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d2d3-scope-r1`.

K2-A ist sauber auf `acdd93e` abgeschlossen (Basis `7ff695e`), genau sechs autorisierte
Cloud-Dateien. 126 Cloud-JVM-Tests einschließlich 21 neuer Exporttests und vollständiger
Check mit Phone-/Wear-APK grün. Eigenständige Exportrevision, Kontogrenzen, begrenzter
Stream und native Zeitlimits; SHA-256 und Dateipromotion bleiben ausdrücklich beim
folgenden Androidhost. Frisches unabhängiges Review 1 läuft:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-http-review-r1`.

K2-B ist sauber auf `99f19cf` abgeschlossen (dieselbe Basis), genau sechs autorisierte
Präsentations-/UI-/Test-/Stringdateien. 38 neue Schlüssel je Sprache, 62 Präsentations-
und 222 Desktoptests grün; vollständiger Check samt Phone-/Wear-APK mit 761 Tasks grün.
12 Phasen und neun Fehler DE/EN, Hell/Dunkel, 360×640 bei 200 Prozent geprüft;
24 Desktopbilder. Fehlertext nutzt lokal `onBackground` plus Fehlersemantik, weil das
bestehende Material-Fehlerrot auf hellem Apphintergrund nur 4,463:1 erreichte.
Frisches unabhängiges UI-Review 1 läuft; noch keine Account-/Androidabnahme:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-ui-review-r1`.

K2-Serverkorrektur `8e49ccc`: echter PostgREST-ACK für `void` liefert 204 ohne JSON-Body.
Der Backendadapter akzeptiert ausschließlich diesen zusätzlichen Erfolgsfall; leerer 200
bleibt fehlerhaft. Zwei neue Regressionen gegen Original rot, mit Fix insgesamt 21
Deno-Tests sowie Typen/Format/Lint und vollständiger 688-Task-Check grün. Der Liveharness
behält sämtliche Cleanup-Negativprüfungen und verlangt zusätzlich den echten ACK204.
Frisches Integrationsreview 4 bestätigt `8e49ccc` ohne neue Befunde, unabhängig 21
Deno-Tests/Typen/Format/Lint und sichere Diagnose geprüft. Echte Service-CI bleibt offen:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-integration-review-r4`.
Kein Merge vor beiden Nachweisen. D1-Korrektur 2 ist auf `b44c5ab` abgeschlossen:
neun autorisierte Dateien, beide R2-Repros rot→grün, R1-Regressionen erhalten, 258 Tests
und Migration grün sowie ein ausdrücklich offener Stackskip; Gesamtcheck 688 Tasks grün.
Persistierte Quote von zwölf Eventversuchen erzwingt eine Metadatenseite; erster vollständiger
Rundenkatalog bleibt Voraussetzung, spätere Seiten wechseln mit Events. Frisches Review 3:
`node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-review-r3`.
C konsumiert A/B erst nach ihren Reviews. Keine gemeinsame Besitzdatei wird parallel editiert.

Root-Androidintegration `ba1f37d` unabhängig `ship`, keine offenen Befunde; 20 Tests frisch
plus zwei aus Cache und zusätzliche gemeinsame DE/EN-Hell/Dunkel-200%-Probe grün.
PR #68 wartet auf Vorläufer #66 und eigene CI. K2-Server auf `84ee8fc` besteht echte
1.299 pgTAP-Prüfungen/35 Dateien, Parallelrennen, ZIP- und Idempotenz-HTTP-Proben; spätere
Storage-Negativ-/Cleanup-Probe scheitert noch. Root-Diagnose `425c094` ändert nur sichere
Testpositionen, keine Assertion/Produktlogik. Service-CI läuft erneut, keine Gesamtabnahme.

D1 Fix `8305254` gegen `68d23de` fertig: zwölf autorisierte Dateien, fünf Originalrepros
rot→grün, 250 Fokusprüfungen plus ein offener Stackskip, Migration/Gesamtcheck 688 Tasks grün.
Persistierte faire Prüfrunde/Phase, Minimalbudget und `scheduled`-Snapshots gehen ins frische
Review 2: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-d1-review-r2`.

K2-Phone-Erkundung bestätigt den kleinsten vollständigen Androidpfad: manuell anfordern,
Status prüfen, in privaten Cache laden/verifizieren, ausdrücklich per Sharesheet teilen.
Kein zusätzliches SAF-Speicherframework oder automatisches Polling. Empfängerkopien und
bereits offene Dateideskriptoren sind nicht nachträglich fernlöschbar.

| Lane | Exklusiver Besitz | Prüfbares Ergebnis |
|---|---|---|
| K2-A Cloudadapter | Neue `CloudDataExportPort.kt`/`SupabaseDataExport.kt`, nur additive Exportseams in `CloudHttp.kt`/`SupabasePhoneAuth.kt`, direkte neue Cloudtests | Frische unabhängige Kontositzung, echter fester Bearer, strikte Methoden/ID-/Status-/Pfadvalidierung, begrenztes Streaming; keine Änderung an Gate, Upload, D1 oder SQL. |
| K2-B Präsentation/UI | Neue `PhoneExportPort.kt`, `CloudExportSection.kt` und direkte Tests; ausschließlich neue `cloud_export_*` in beiden Stringdateien | Eigenständige Zustände/Aktionen ohne SDK/Dateipfad/Androidtypen, DE/EN bei 360×640/200 %, keine AccountScreen-/VM-/Hosteinbindung. |
| K2-C Android, danach Integration | Nach A/B-Review eigener Auftrag für Cache/Provider/Host; Root vergibt AuthViewModel/PhoneAccount/MainActivity/AdvantlyApp/AccountScreen einmalig | Teilarchiv nie freigebbar; Hash/Größe/Frist/Account vor Promotion und Teilen; Upload und Export beide synchron sperren und drainen, danach Auth genau einmal schließen. |

A und B starten isoliert auf demselben geprüften Rootstand nach `ba1f37d`; keine
gegenseitigen Imports. C konsumiert anschließend beide geprüften Verträge.
Tatsächliche gemeinsame Basis `7ff695e`. A arbeitet im eigenen Git-Worktree
`p2-k2-a-export-http`, B im eigenen `p2-k2-phone-ui`; B meldet T3-`already_in_worktree`
und nutzt deshalb für sämtliche Datei-/Buildoperationen explizit seinen eigenen Workdir.
Root-/fremde Worktrees bleiben unverändert. Tasks:
- A: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-http-impl-r1`
- B: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-ui-impl-r1`

Verbindlicher A-Vertrag: `CloudDataExportPort.context` ist
`StateFlow<CloudDataExportContext?>`; Context enthält ausschließlich Profil-ID, verifizierte
Kontogeneration und eigene Exportrevision. `requireCurrent(context)` prüft synchron auch die
SDK-Identität. Suspendierende `request(context, requestId)` und `status(context, requestId)`
liefern `CloudDataExportStatus` (Pending mit bestätigter ID/Retry-After, Ready mit ID/
exportedAtMs/expiresAtMs/byteLength/sha256, Busy mit Retry-After ohne fremde ID).
`download(context, ready, sink)` streamt höchstens 16 MiB und exakt die bestätigte Länge;
`CloudDataExportSink.write(bytes, offset, length)` erhält kleine Blöcke. HTTP prüft MIME,
Route, EOF und native Zeitlimits; C prüft SHA-256 vor Dateipromotion. Kein öffentlicher Token-
Getter und kein behaupteter Hashbeweis allein durch HTTP-Abschluss.

Exportrevision entwertet laufende Arbeit/Dateifreigabe bei Konto-/SDK-Grenze, Logout,
Close und Beginn eines Löschantrags (`requestDeletion`/`stopForDeletion`). Eine danach
ausdrücklich neu gestartete Exportaktion bleibt in der Karenz zulässig. Consent-/reine
Cloud-Sperren entziehen dieses Kontorecht nicht. Alle bestehenden K1-Mutations-/Antwortfences
bleiben erhalten; neue Exportjobs werden getrennt verwaltet.

B-Vertrag: `PhoneExportPort.state: StateFlow<PhoneExportState>`, Methoden `requestExport`,
`retry`, `refreshStatus`, `download`, `cancel`, `discard`, `close(onDrained)`.
State enthält ausschließlich Phase, erlaubte Aktionen, stabile Fehlerklasse und optionale
Zeit-/Größen-/Retryangaben; keine Request-ID, Cloudtypen, Pfade oder Uri. Die Sektion bekommt
einen optionalen `onShare`-Callback statt einer gespeicherten Activity. Primäraktion je
Zustand plus nötiges Anhalten/Verwerfen; kein Prozentfortschritt ohne Beleg, „Teilen geöffnet“
ist kein bestätigtes Speichern. Cancel hält nur lokale Arbeit an, kein erfundener Serverabbruch.
Retry/Status behalten dieselbe Request-ID im späteren Host; 409 kündigt keine Pending-ID an.

Android Review 2 bestätigt `31620a7` und den separaten Root-Kontrastfix `1f830dc` jeweils
ohne offene Befunde. Unabhängig 21 gezielte Tests grün: elf Host-, zwei Präsentations-,
ein Upload-UI-Matrix- und sieben Kontotests. Root übernimmt den Androidkandidaten in den
eigenen Integrationszweig. Einziger Produktkonflikt: die einzeilige Upload-Einhängung muss
innerhalb der bereits korrigierten Kontospalte bleiben. Alle anderen Android-/Cloud-/Data-/
Präsentationsdateien und Ressourcen bleiben bytegleich zu `31620a7`; Root-Kontrasttest bleibt
zusätzlich erhalten. Keine Änderungen am Authadapter, Backend-SQL oder D1.
Gemeinsamer vollständiger Check plus Android-APK: 744 Tasks grün (1m48s),
`advantly-android-root-integration-check.log`. Frisches Review der Integrationsauflösung folgt.

K2 Integrationsreview 3 bestätigt `84ee8fc` ohne offene Befunde, einschließlich zentralem
Deno-Pfadfilter. PR #67 ist kein Draft mehr; vollständige Service-CI auf genau diesem Head
bleibt vor Merge erforderlich. Veraltete CI-Meldungen zu `79b5faa` und `270782b` wurden
geprüft, nicht als Fehler beziehungsweise Freigabe des aktuellen Heads behandelt.

Nächster begrenzter K2-Schnitt wird parallel lesend abgegrenzt: echter privater ZIP-Download
auf Android mit Dateiausgabe/Freigabe, unabhängigen Kontorechten und Kontowechselfences.
Noch kein Implementierungsbesitz vergeben; keine UI-Kollision mit der Androidintegration.
Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Adelegate-task%3Aadvantly-20261018-k2-phone-scope-r1`.

Android Review 1 auf `2e135ac`: funktional `ship`, 506 Fokusprüfungen und 14 zusätzliche
nicht kooperative Bind-/Push-/Close-Rennen grün. Ein P3: Refresh vor erster Bindung aktiv,
aber ohne Wirkung. Fix `31620a7` ergänzt ausschließlich `canRefreshStatus=false` als Default,
Freigabe erst mit aktuellem gebundenem Runner; UI sperrt zusätzlich während Busy.
Worker 507 Fokusprüfungen und 740 Gesamtcheck/APK-Tasks grün; frisches Review 2 folgt.

Root behebt separat den vorbestehenden Account-Kontrastfehler in `AccountScreen.kt` und
`AccountScreenTest.kt`: Hintergrund-ContentColor aus Material-Token nur für die Kontospalte,
keine globale Theme-/Text-/Layoutänderung; Einrückungen mechanisch. Regression im tatsächlichen
Box-background-Host ohne kaschierendes Surface: vorher 1,0737:1, nach Fix mindestens 4,5:1,
DE/EN, Hell/Dunkel, 200 %, Kontoaktionen erreichbar. Baseline-XML und Screenshots lokal erhalten.

D1 Review 1 auf `68d23de`: vier belegte P2, fünf rote eigene Regressionen. Fehlerhafter
erster Eventauftrag verhungert Folgeaufträge; Prüfrunde startet bei jedem Aufruf neu und
wird im größeren ruhenden Bestand nie vollständig; Einseitenbudget verhindert Eventarbeit;
gültiges Server-`scheduled` scheitert am Engine-Status. Korrektur innerhalb bestehender
D1-Dateien beauftragt: faire dauerhafte Auswahl, fortsetzbare begrenzte Prüfrunde/Phase,
geplanter Match als Snapshot ohne erfundenen Engine-Status. Cursor/Receipts erst bei Erfolg,
Fehler sichtbar. Task: `node:delegated-task:command%3Amcp%3A2455d11b-46b1-487c-82f0-bc3d3b9480ee%3Athread-send%3Aadvantly-20261018-d1-fix-r1`.

K2-Integrationsreview 1 findet Deno-Formatfehler vor echtem Stackstart. Root-Fix `990179f`
pinnt 2.9.7 im zentralen Katalog und verlangt zwei Leerzeichen; mit genau dieser Runtime
19 Tests/Typen/Format/Lint und Gesamtcheck 692 Tasks grün. Neue Service-CI und Review 2
laufen. PR66 nach PR65-Squash im eigenen Hilfsworktree auf `09bdd1b` abgeglichen; ausschließlich
Plan-Konflikt, Produktcode/SQL/Tests unverändert. Neue CI erforderlich.
K2-Mainabgleich nach PR #66: `origin/main 870792a` bringt ausschließlich den unabhängig
geprüften U4-Kern in diese Serverintegration. Konflikte nur im Plan; beide Nachweise bleiben
erhalten. Shared-/Android-/Wear-Baum bytegleich zu main, K2-Server/CI/Katalog unverändert
gegen `8e49ccc`. Gemeinsamer Gesamtcheck vor Mergecommit mit 688 Tasks grün (2m10s,
`advantly-k2-main-u4-merge-check.log`); neue CI danach erforderlich.

K2-Integrationsreview 4 bestätigt `8e49ccc` ohne offene Befunde und mit 21 unabhängig
grünen Deno-Tests/Typen/Format/Lint. Die echte Service-CI desselben Heads ist erfolgreich
(Run `39143114803`, Job `117721530287`): 1.299 pgTAP-Prüfungen in 35 Dateien, Parallelrennen,
vollständiger ZIP-/CSV-/JSON-Rundlauf, private Storage-/Fremd-/Anonymzugriffe, Ablauf und
physische Bereinigung einschließlich verspätetem Upload, Teilnehmer-/Owner-Purge sowie
echter Auth-Logout mit Abweisung des alten JWT. Tatsächlicher ACK liefert 204; Vault→pg_net→
Cleanup-Function→Storage ebenfalls grün. Periodischer Cronbetrieb, Hosted und Phone-Ausgabe
bleiben getrennte Nachweise; übrige CI auf dem neuen Mergehead bleibt vor Merge nötig.

K2-Diagnose-CI `425c094` lokalisiert den Fehler exakt in `cleanup()` nach Ablaufprüfung,
auch während finally-Cleanup. Glaubwürdiger Mechanismus durch offizielles PostgREST-
Protokoll und zwei rote Regressionen belegt: `ack_data_export_cleanup_v1` liefert als
void-RPC HTTP 204; Backend parst trotzdem JSON. Root ergänzt ausschließlich No-Content-
Behandlung im Backend (zwei Zeilen), reale Backend→Handler-Regression und strikten
Gegenbeweis für ungültiges leeres HTTP 200. 21 Deno-Tests/Typen/Format/Lint grün; Live-Test
prüft zusätzlich tatsächliches RPC-204. Produktfreigabe erst nach frischem Review und
vollständiger echter Service-CI, bisherige Sicherheits-/Cleanup-Assertions unverändert.

Echte K2-Service-CI auf `84ee8fc`, Run `39141216108`: Stackstart, beide Handler, sämtliche
1.299 pgTAP-Assertions in 35 Dateien und acht Parallelrennen samt SQL-ZIP-Rundlauf grün.
Live-HTTP bestätigt vollständiges ZIP/CRC/JSON/CSV und Idempotenz/409-Vertrag; danach harter
Fehler im Storage-Negativ-/Ablauf-/Cleanup-Abschnitt. Scheduler und Lint deshalb noch nicht
ausgeführt. Keine grüne Gesamtabnahme. Die bewusst generische Fehlerausgabe lässt die
exakte Testzeile offen. Root ergänzt nur Typ und eigene Testpositionen (keine Inhalte,
Exceptiontexte oder Variablen), Assertions und Produktcode unverändert. Lokaler Diagnose-
Fehlerpfad bleibt hart rot ohne Dienste; Syntax und vollständiger Check 692 Tasks grün.

K2-Integrationsreview 1 auf `79b5faa`: ein belegter P2. Der tatsächlich installierte
Deno-2.9.7-Formatter liest `.editorconfig` (vier Leerzeichen); bisherige lokale Deno-2.5.4-
Prüfung tat das nicht. Erste Service-CI stoppt deshalb vor Stackstart. Enge Root-Korrektur:
Deno 2.9.7 im zentralen Versionskatalog pinnen, CI liest diesen Wert; Formatcheck verlangt
ausdrücklich zwei Leerzeichen. Beide Functions unter genau 2.9.7 mit Typen/Format/Lint und
19 Tests grün; vollständiger Root-Check 692 Tasks grün (4m30s). Servercode/SQL unverändert.
Erneute tatsächliche Service-CI und frisches Integrationsreview 2 bleiben vor Merge nötig.
PR #66 nach PR65-Squash erneut auf main `cb14420` abgeglichen. Ausschließlich add/add im
Plan; der neuere vollständige Orchestratorplan bleibt erhalten. Gate und sämtliche übrigen
Produkt-/SQL-/Testdateien unverändert zu `270782b` und zum unabhängig geprüften U4-Kandidaten.
Der grüne vollständige 692-Task-Check dieses identischen Produktbaums gilt fort; CI wird
auf dem neuen Mergehead erneut ausgeführt, kein Force-Push und keine fremden Worktreeedits.

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
