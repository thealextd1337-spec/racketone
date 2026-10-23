# P2-Pilotnachweise (K7)

Stand: 23.10.2026, Basis `aa0df4d1` (PR #75, Branch `p2/d2d3-integration-b`). Paket K7 aus
[Welle 4](p2-fortsetzung-2026-10-20.md#welle-4--automatischer-sync-und-pilotvorbereitung) und
[Lieferplan Punkt 4](p2-cloud-pilot-lieferplan.md#punkt-4-datenschutz-kontosupport-und-online-pilot).

Regeln für dieses Dokument:
- **belegt** heißt: Ein beobachteter Lauf liegt vor, mit Run-ID und Test- oder Schrittname.
  Lokale Ergebnisse und JVM-Simulationen gelten nur für das, was sie tatsächlich ausführen.
- **teilweise belegt:** Der automatisierte Teil ist belegt (meist JVM-Simulation in CI), Gerät oder Hosting fehlt.
- **vorbereitet:** Skript oder Bogen liegt vor, aber kein Lauf.
- **offen:** Nachweis fehlt oder braucht Zugang, Gerät oder Entscheidung.
- Keine Pilotfreigabe aus einem lokalen Testergebnis. Ein leerer Protokollbogen ist keine bestandene Abnahme.
- Gehostete Jobs, Export-Bereitstellung, echte Konten/Geräte und Datenschutz-/Store-Unterlagen brauchen eigene
  Nachweise. Nichts hier ist eine Produktionsfreigabe.

## Ausgangslage (beobachtet am 23.10.2026)

- **CI-Lauf `39893514857`** auf `aa0df4d1` (pull_request, PR #75): alle vier Jobs grün. Im Einzelnen:
  - Gradle `check` + APKs;
  - macOS (K/N-Tests iOS/watchOS, Simulator-Builds);
  - Supabase-Job mit pgTAP, Lockrennen 181/210/220/250/290, Export-Rennen, Export-Live, Vault-Scheduler, D1-Stack und `db lint`.
  Die Testberichte (Artefakt `reports-ubuntu`) wurden ausgewertet. Die unten genannten JVM-Klassen liefen mit 0 Fehlern
  und 0 übersprungenen Tests. Ausnahme ist `CloudPullStackTest`: Im Gradle-Job ist er übersprungen, er läuft
  echt im Supabase-Job über `d1_stack_ci.py`, das genau einen ausgeführten Test ohne Skip verlangt.
- **Staging:** Der letzte Lauf von „Supabase staging deploy“ ist `39014532139` (17.10.2026) auf `3fbd5dce`.
  Dieser Commit enthält Migrationen bis `20261016000700_support`. Die 13 späteren Migrationen
  (`20261018000100` … `20261022000200`) sind damit nicht auf staging. Dazu gehören Gerätebindung, Zustimmung,
  Löschjob, Export und das gesamte Release-Gate. Der Deploy-Workflow rollt keine Edge Functions aus.
- **Geräte:** Auf Pixel 9 Pro XL und Pixel Watch 3 läuft ein alter Build (DB-Schema 4) mit 30 echten
  Matches. Vor jedem Gerätebogen ist die [Datensicherung](#datensicherung-vor-jedem-gerätebogen) Pflicht.
- **K7-Worktree:** Docker und die Supabase-CLI fehlen. Keines der neuen Skripte ist gegen einen echten Stack
  gelaufen (siehe [Skripte](#ausführbare-prüfungen-toolspilot-checks)).

## Nachweismatrix

„CI 39893514857“ bezeichnet den oben beschriebenen Lauf auf `aa0df4d1`.

### Export, Auth, Provider, Support

| Nr | Anforderung | Quelle | Nachweisart | Status | Wer |
|---|---|---|---|---|---|
| E1 | Export-Edge-Function: vollständiges ZIP (JSON/CSV, CRC), Whitelist, keine fremden IDs, 202/409, Ablauf, privater Storage, Cleanup inkl. verspätetem Upload, Vault→pg_net→Cleanup | Lieferplan K2; p2-plan §11 G9 | CI-Schritte „Export-Rennen und SQL-ZIP-CSV-Rundlauf“ (`200_private_data_exports_concurrency.py --archive-roundtrip`), „Export über echte Auth-, HTTP- und Storage-Dienste“ (`200_private_data_exports_live.py`), „Vault-Scheduler über pg_net und Edge prüfen“; Deno-Tests beider Functions; JVM `CloudDataExportHttpTest` (20) | **belegt**: CI 39893514857, nur lokaler Stack | CI |
| E2 | Export-Rundlauf mit echtem Upload eines gemeinsamen Matches; beide Seiten ohne fremde Profil-/Geräte-IDs; fremder/anonymer Abruf; altes JWT | G9; K7 | Skript `check_export.py` | vorbereitet | Integrator (CI-Anschluss) |
| E3 | Export gehostet: Functions deployt (`verify_jwt=false`), Secret und Vault, periodischer Cleanup-Cron, Hosted-Limits | supabase/README „K2“ | `check_export.py --confirm-staging`, `check_release_gate.py` (Vault, Cron) | offen: Functions nicht deployt, Migration `…18000500` nicht auf staging | Integrator mit Staging-Zugang |
| E4 | Export am Handy anfordern und ZIP öffnen | Lieferplan K2; README | [Bogen E](#bogen-e--export-am-handy-g9) | offen | Mensch mit Gerät |
| A1 | Auth-/RLS-Negativfälle auf DB-Ebene: Ticket, Hook, XFF, Rechte, Spalten, Consent-Gate | P2-C; p2-plan §11.1 | CI-Schritt „pgTAP-Tests“ (010, 120, 140, 150, 160, 170, 181 …) | **belegt**: CI 39893514857 | CI |
| A2 | Auth-Negativtests über echte HTTP-Endpunkte (Liste unter der Tabelle) | Welle 4 K7; p2c-auth | `check_auth_negative.py` (lokal) | vorbereitet | Integrator (CI-Anschluss) |
| A3 | Gehosteter Hook-Negativtest (HTTP 403 `age_ticket_required`, kein Auth-Nutzer) und Spoofing-Test mit allen drei Headern | p2c-auth „Staging“ 3 und 8; README P2-C | `check_auth_negative.py --confirm-staging` im kontrollierten Signup-Testfenster | offen | Integrator mit Dashboard |
| A4 | Nach globaler Abmeldung wird das alte JWT abgewiesen | K4 | Edge: `200_private_data_exports_live.py`; RPC: `check_revoke.py`, `check_export.py` | teilweise belegt (Edge: CI 39893514857); RPC vorbereitet | CI, Integrator |
| P1 | Provider Google: Dashboard-Konfiguration, Nonce, Google→OTP mit gleicher Profil-ID, Linking-Konflikt | p2c-auth „Abnahme“ 3–4, „Staging“ 4–5 | [Bogen G](#bogen-g--provider-google-und-e-mail-code); `check_auth_negative.py` meldet nur den Provider-Schalter | offen | Mensch mit Pixel und Dashboard |
| P2 | E-Mail-Code mit eigenem EU-SMTP (Code statt Link, Ablauf, Bestätigung an) | p2c-auth „Staging“ 6 | Bogen G, Teil a; die staging-Kontoanlage der Skripte nutzt denselben Weg | offen | Mensch |
| S1 | Support auf DB- und Adapterebene: Meldung, Wiederholung, Rechte der Admin-RPCs | support-admin-betrieb | pgTAP 014; JVM `SupabaseSupportTest` (4) | **belegt**: CI 39893514857 | CI |
| S2 | Supportantwort in „Meine Meldungen“, interne Notiz unsichtbar, fremdes Konto sieht nichts, Admin-RPCs ohne Mitgliedschaft verweigert | Lieferplan „Gemeinsame Lieferung“; support-admin-betrieb | `check_support.py`: lokal Antwort per Owner-DB, staging Antwort durch Betreiber im Admin | vorbereitet | Integrator; staging mit Betreiber (MFA) |
| S3 | Support-Live-Abnahme Admin: MFA, zwei Betreiber, Widerruf, Gast-IP am Gateway, Android und iOS | support-admin-betrieb „Abnahme vor dem Online-Pilot“ | manuell nach der dortigen Liste | offen | Betreiber |

A2 prüft im Einzelnen:
- Signup ohne bzw. mit erfundenem Ticket;
- RPCs ohne Sitzung;
- gefälschte JWTs (HS256 und `alg: none`);
- private Schemas, Service-RPCs und Auth-Admin mit anon-Schlüssel;
- Edge Functions ohne Sitzung bzw. mit falschem Schlüssel;
- gefälschte `X-Forwarded-For`/`X-Real-IP` gegen das Ticketbudget.

### Widerruf, Löschjob, Release-Gate

| Nr | Anforderung | Quelle | Nachweisart | Status | Wer |
|---|---|---|---|---|---|
| W1 | Gerätebindung und Widerruf auf DB-Ebene | K4/U1 | pgTAP 170 | **belegt**: CI 39893514857 | CI |
| W2 | Widerruf über echte Auth/PostgREST im Pull-Runner (Installation widerrufen → `BLOCKED`, ohne JWT/Consent/Capability verweigert) | D1 | CI-Schritt „D1 über echte Auth und PostgREST …“ (`CloudPullStackTest.realAuthPostgrestRunnerPagingReopenGapRepairAndDeniedRequests`) | **belegt**: CI 39893514857 | CI |
| W3 | Geräte- und Sitzungswiderruf, Neuregistrierung mit widerrufener Geräte-ID, fremdes Konto, globale Abmeldung; Kontowege bleiben offen | K4 | `check_revoke.py` | vorbereitet | Integrator |
| W4 | Realtime nach Widerruf (bereits offene Sockets, Postgres-Changes) | `170_cloud_device_binding_live.py`, Kopfkommentar | Skript nicht in CI; endet absichtlich ungleich 0 | offen | Server-Paket |
| W5 | Widerruf in der Phone-App (Geräteliste); widerrufenes Gerät synchronisiert nicht mehr | K4 | [Bogen W](#bogen-w--geräte-widerruf-in-der-app) | offen | Mensch mit Gerät |
| L1 | Gestaffelte Löschung A/B/C, Rennen, Cron-Registrierung, Fehlerisolation | Fortsetzung „Übernahme gestaffelte Kontolöschung“ | pgTAP 190, 280 und 290; Schritt „Consent-Gate und D2/D3-Lockrennen“ (`290_staged_account_deletion_concurrency.py`) | **belegt**: CI 39893514857 | CI |
| L2 | Löschjob von der Fälligkeit bis zum Abschluss durch den echten pg_cron-Job (ohne manuellen Aufruf); Antrag, Abbruch, erneuter Antrag; Redaktion beim Mitspieler | G8; K3 | `check_deletion_job.py` (lokal) | vorbereitet | Integrator (CI-Anschluss) |
| L3 | Löschjob auf dem gehosteten Projekt (`advantly-account-deletion-v1` alle 5 s, `cron.job_run_details`) | K3; Auflage vor Pilot-Freigabe | `check_deletion_job.py --confirm-staging`, `check_release_gate.py` | offen: `20261022000200` nicht auf staging (Release-Gate) | Integrator |
| R1 | Release-Gate: `20261018000700`, `20261021000800`, `20261021000900`, `20261021001000` und `20261022000200` nur gemeinsam und nur mit einer App, die U4 und den D1-Pull betreibt | Fortsetzung „Release-Gate“ (P2-1, D1/U4, 20261022000200) | `check_release_gate.py` (Drift, Gate-Satz, Cronjobs, Vault-Namen) plus Release-Entscheid | vorbereitet. Ist: staging auf `3fbd5dce`, Gate nicht ausgerollt (Deploy `39014532139`) | Integrator, Release |
| R2 | Datenschutz-/Store-Gate P2 §8: Texte DE/EN mit Versionierung, AVV/DPA, VVT, Data safety, Altersgate aktiv | p2-plan §8; Lieferplan K7 | Unterlagen und Konsolen | offen | Mensch |

### V4, Gerätetransport, Pull, Upload

| Nr | Anforderung | Quelle | Nachweisart | Status | Wer |
|---|---|---|---|---|---|
| V1 | Offline-Uhr während der Löschung, Name verschwindet nach Reconnect | p2-plan G8; Welle-3-Abnahme | JVM `ReplicaDeliveryTest.offlineWatchDuringDeletionLosesTheNameOnReconnect`; [Bogen V1](#bogen-v1--offline-uhr-während-löschung-g8) | teilweise belegt (JVM: CI 39893514857); Gerät offen | CI; Mensch mit Gerät |
| V2 | Neustart/Reconnect mitten in der Übertragung | Welle 3, E3 | JVM `phoneRestartMidTransferResendsFromTheFirstPage`, `watchRestartMidTransferContinuesWithTheMissingPage`, `multiPageRedactionSurvivesALostPageAckAndLostCompletionAck`; Bogen V2 | teilweise belegt; Gerät offen | CI; Mensch |
| V3 | Keine Namenwiederkehr, kein Wiederhochladen alter Payloads | Welle-3-Abnahme | JVM `aDeletedNameNeverReturnsAcrossRestartsAndIsNeverUploadedAgain`, `anOldWatchStateNeverReuploadsAndCatchesUpAfterTheRedaction`; Wear `WearRestoreGuardTest.glanceCacheStoresNoNamesAndDropsLegacyValuesWithNames`; Bogen V3 | teilweise belegt; Gerät offen | CI; Mensch |
| V4 | Entzug und Wiederfreigabe | Welle-3-Abnahme; G13 | JVM `revocationAndRegrantFollowTheGeneration`; Bogen V4 | teilweise belegt; Gerät offen | CI; Mensch |
| V5 | Konto A/B getrennt, A → B → A, verspätete Antworten | Identitätssicherheit | JVM `lateEnvelopesAfterAnAccountSwitchChangeNothing`, `anotherAccountGetsItsOwnPagesForTheSameMatchGenerationAndEpoch`, `ReplicaIdentityRecoveryTest` (3), `AccountOwnershipSessionTest` (9 je Plattform); Bogen V5 | teilweise belegt; Gerät offen | CI; Mensch |
| V6 | Unabhängige Zustell-Acks (Access/Redaktion getrennt, je Gegenstelle) | Welle 3 | JVM `ReplicaDeliveryTest` (14), `MatchStampsEchoTest`; Bogen V6 | teilweise belegt; Gerät offen | CI; Mensch mit zwei Uhren |
| V7 | 3↔4: Updatezustand statt Teilsync; ohne Konto bleibt v3 kompatibel | Welle 3 „Protokoll 4 aktiviert“ | JVM `ProtocolFourTest` (10), u. a. `threeAgainstFourIsAnUpdateStateWithoutPartialSync`, `withAnAccountANewPhoneAndAnOldWatchAreAnUpdateState`, `withAnAccountAnOldPhoneAndANewWatchAreAnUpdateState`, `withoutAnAccountNewAndOldDevicesStayCompatibleOverThree`; [Bogen V7](#bogen-v7--34-mit-echter-alter-store-app) mit echter alter Store-App | teilweise belegt; Store-App offen | CI; Mensch mit Gerät |
| V8 | Gestaffelter Rollout von Phone- und Wear-App | Welle 3 „Nur auf Geräten …“ | [Bogen V8](#bogen-v8--gestaffelter-rollout-phonewear) | offen | Release, Mensch |
| T1 | Wear Data Layer mit Verlusten (Funkabbruch, Prozessende, Flugmodus) | Welle 3 „Nur auf Geräten …“ | JVM `MultiDeviceSyncTest` (8 je Plattform), `RandomizedConvergenceTest`, `ProtocolFourTest.aDoubleHelloLossUnderFourHealsWithTheTickWithoutPingPong` (InMemoryLinkNetwork); [Bogen T](#bogen-t--wear-data-layer-und-watchconnectivity-mit-verlusten) | teilweise belegt; Gerät offen | CI; Mensch |
| T2 | WatchConnectivity mit Verlusten (watchOS) | dto. | CI-Schritte „Kotlin/Native-Tests watchOS-Simulator“ und „watchOS-App-Smoke“ (ohne Verlustszenarien); Bogen T, Teil b | offen: iOS hat noch keinen Konto-Host und bleibt bei P1/v3 | Mensch mit iPhone/Apple Watch |
| U1 | Pull mit Unterbrechung: Katalogseiten, Neustart, Lückenreparatur, mehr als 200 Matches / 500 Events | D1 | CI-Schritt „D1 über echte Auth …“ (`CloudPullStackTest`); JVM `CloudPullRunnerTest` (32), `CloudPullStoreTest` (14) | **belegt**: CI 39893514857 | CI |
| U2 | Pull mit Unterbrechung zwischen Katalog und Redaktion gegen Supabase | Welle-3-Notiz | Server: `check_deletion_job.py` (Redaktion in Einzelseiten, Abbruch, Fortsetzung mit erneuerter Sitzung, 40001 bei altem G/E, Readiness bis H); Client-Runner gegen den Stack: fehlt | Server vorbereitet; Client offen (Kotlin-Stacktest nötig, nicht K7-Besitz) | Integrator; D1-Besitzer |
| U3 | Server-Annahme von `PlayerAssigned.reference` (assignmentVersion 2) und Teilnehmer-Upload von einem zweiten eigenen Gerät | Welle-3-Notiz; S Runde 2 (P3-C) | `check_cloud_upload.py`; DB-Ebene: pgTAP 210 (Zeile mit `assignmentVersion` 2) | vorbereitet | Integrator |

### Restore, Feldtest, G-Szenarien

| Nr | Anforderung | Quelle | Nachweisart | Status | Wer |
|---|---|---|---|---|---|
| RC1 | Restore/Recovery Android/Wear: vom Betriebssystem übertragene DB gesperrt bis zur bestätigten UID, keine übernommene Geräte-ID oder Pairing-Autorität | „Verbindliche Identitätssicherheit“; Recovery-Paket | JVM `WearRestoreGuardTest` (4), `InstallationHostFailureTest` (21), `ReplicaIdentityRecoveryTest` (3), alle simuliert; [Bogen RC](#bogen-rc--restore-und-recovery-android) | teilweise belegt; Gerät offen, erst nach Integration des Recovery-Pakets | CI; Mensch mit Zweitgerät |
| RC2 | Apple-Restore | Identitätssicherheit | eigener Nachweis im Apple-Paket | offen | Apple-Paket |
| F1 | P1-Feldtestmatrix inklusive kleiner Galaxy Watch (Pflicht-Regression), Pixel Watch separat | Nachweise je Lieferung; [p1-gate-protokoll](release/p1-gate-protokoll.md) §3/§8; p2-next-steps „Displaygrößen“ | Ergebnisbogen dort §8 | offen: Ergebnisbogen leer, keine Ablage unter `docs/release/p1-gate-berichte/` | Mensch mit Geräten |
| G | G1–G7 und G10–G13 manuell | p2-plan §11 | je Szenario mit dem allgemeinen Bogenkopf unten | offen | Mensch |

**Zählung:** 7 belegt (E1, A1, S1, W1, W2, L1, U1), 10 teilweise belegt (A4, V1–V7, T1, RC1),
7 vorbereitet (E2, A2, S2, W3, L2, R1, U3), 1 gemischt (U2: Server vorbereitet, Client offen),
14 offen (E3, E4, A3, P1, P2, S3, W4, W5, L3, R2, V8, T2, RC2, F1) und die Sammelzeile G.

## Ausführbare Prüfungen (`tools/pilot-checks/`)

Die Skripte liegen unter `tools/` und nicht unter `supabase/tests/`, aus zwei Gründen:
- Sie dürfen nach ausdrücklicher Bestätigung gegen staging laufen. Die Suite unter `supabase/tests/` ist
  dagegen nur für wegwerfbare Datenbanken gedacht (README „Warum kein `supabase test db --linked`“).
- `supabase test db` soll sie nicht aufsammeln.

Sie nutzen ausschließlich echte Dienste: GoTrue, PostgREST, Edge Functions und pg_cron. Die Owner-DB dient nur
der Vorbereitung, dem Verkürzen der Frist und dem Aufräumen.

| Skript | Prüft (Matrix) | Konten |
|---|---|---|
| `check_auth_negative.py` | A2/A3: Liste unter der Matrix; dazu Auth-Settings (Bestätigung an, anonymous/phone/apple aus) | keine (ein Konto nur bei Fehlverhalten, dann gelöscht) |
| `check_support.py` | S2 | 2 |
| `check_revoke.py` | W3, A4 | 2, davon 3 Sitzungen für A |
| `check_export.py` | E2/E3, A4 | 2, befreundet |
| `check_cloud_upload.py` | U3 | 2, davon 2 Sitzungen für A |
| `check_deletion_job.py` | L2/L3, U2 (Server) | 2, befreundet; B wird durch den Job gelöscht |
| `check_release_gate.py` | R1, E3/L3 (Cron, Vault) | keine, nur lesend |
| `test_stack.py` | Zielschranke und Ausgabe ohne Geheimnisse (Unit-Tests ohne Netz) | – |

Ausgabe: je Schritt `PASS …`, am Ende `PASS|FAIL|INCONCLUSIVE <skript> (<ziel>)`.
- Exitcode 0 bedeutet bestanden, 1 Fehler, 2 nicht nachweisbar (z. B. Signup aus oder kein freigegebener
  Zustimmungskatalog). Exitcode 2 ist kein PASS.
- Fehlermeldungen nennen nur den Prüfschritt und die eigenen Zeilen, nie Antworten, Tokens oder DSNs.
- Alle angelegten Konten werden am Ende über `private.purge_account` entfernt (README, Migrationsregel 5).
  Ein Konto, das das nicht schafft, wird mit seiner UID ausgegeben.

### Umgebungsvariablen

| Variable | Pflicht | Inhalt |
|---|---|---|
| `SUPABASE_URL` | ja | lokal `http://127.0.0.1:54321`, staging `https://<ref>.supabase.co` |
| `SUPABASE_ANON_KEY` | ja | öffentlicher Schlüssel des Ziels |
| `ADVANTLY_PILOT_DB_URL` | ja | Owner-DSN. Lokal `postgresql://postgres:postgres@127.0.0.1:54322/postgres`. Staging direkt (`db.<ref>.supabase.co`, Benutzer `postgres`) oder über den Session-Pooler (`*.pooler.supabase.com`, Benutzer `postgres.<ref>`) |
| `SUPABASE_SERVICE_ROLE_KEY` | nur lokal | Kontoanlage der lokalen Fixture; auf staging wird er nie verwendet |
| `ADVANTLY_PILOT_EMAIL_TEMPLATE` | nur staging | Adresse mit `{tag}`, z. B. `pruefer+{tag}@<eigene-domain>` (Plus-Adressen eines eigenen Postfachs) |
| `ADVANTLY_PILOT_DELETION_TIMEOUT` | nein | Sekunden Wartezeit auf pg_cron in `check_deletion_job.py`, Standard 600 |

`PG*`-Variablen sind verboten. Ohne `--confirm-staging <ref>` läuft nichts gegen ein Ziel außerhalb von
localhost. Die Referenz muss zur API- und zur DB-Adresse passen. Gemischte Ziele (lokal/staging) werden abgelehnt.

### Lokal (Supabase-CLI-Stack wie in der CI)

Voraussetzungen: Stack wie im CI-Job gestartet (Storage und Edge Runtime nötig), Functions per
`supabase functions serve --env-file supabase/functions/.env` mit eigenem Cleanup-Schlüssel, und Vault-Einträge
wie in `.github/scripts/export_stack_ci.py configure`.

Lokal entsteht ein Konto per Auth-Admin-Fixture mit gesetztem Altersnachweis, wie in `d1_stack_ci.py`.
Dazu kommt eine temporär freigegebene Test-Zustimmungsfassung (`test_consent_catalog` aus
`170_cloud_device_binding_live.py`), die am Ende zurückgesetzt wird. Zustimmung, Onboarding, Bindung und alle
Prüfungen laufen über die echten RPCs.

```bash
export SUPABASE_URL=http://127.0.0.1:54321 SUPABASE_ANON_KEY=<lokal> SUPABASE_SERVICE_ROLE_KEY=<lokal>
export ADVANTLY_PILOT_DB_URL=postgresql://postgres:postgres@127.0.0.1:54322/postgres
log="${TEMP:-/tmp}/advantly-k7-$(date +%Y%m%d)"; mkdir -p "$log"
uv run tools/pilot-checks/test_stack.py
for c in release_gate support revoke export cloud_upload deletion_job auth_negative; do
  uv run "tools/pilot-checks/check_$c.py" | tee "$log/$c.log"
done
```

Vorschlag für den CI-Anschluss (Integrator; `ci.yml` wurde in K7 nicht geändert): Den folgenden Schritt im Job
„Supabase – Migrationen + pgTAP“ nach dem D1-Schritt einfügen und `tools/pilot-checks/**` in den Filter
`supabase` aufnehmen. `check_auth_negative.py` läuft zuletzt, weil es das Ticketbudget bucht.

```yaml
      - name: K7-Pilotprüfungen über echte Dienste
        timeout-minutes: 15
        run: |
          uv run tools/pilot-checks/test_stack.py
          for c in release_gate support revoke export cloud_upload deletion_job auth_negative; do
            ADVANTLY_PILOT_DB_URL="$ADVANTLY_EXPORT_TEST_DB_URL" uv run "tools/pilot-checks/check_$c.py"
          done
```

### Staging

Erst nach dem Deploy der jeweiligen Migrationen und Functions sinnvoll; siehe R1, das Release-Gate gilt.

1. `check_release_gate.py --confirm-staging <ref>` zuerst. Es ist nur lesend und zeigt Stand, Gate, Cron und Vault.
2. Der Zustimmungskatalog muss eine freigegebene aktuelle Fassung haben, sonst `INCONCLUSIVE`.
   Die Skripte veröffentlichen auf staging nie Testfassungen.
3. Kontoanlage über den echten Weg mit Hook:
   - `issue_age_ticket` (AT, Testgeburtsdatum), `bind_age_ticket`;
   - `/auth/v1/otp` mit `data.age_ticket`;
   - den E-Mail-Code fragt das Skript im Terminal ab.

   Jede neue Sitzung braucht einen neuen Code: bis zu 4 Codes je Skript, GoTrue erlaubt einen Code je Minute
   und Adresse. Keine Service-Role-Kontoanlage (p2c-auth, Admin-Verbot).
4. `check_auth_negative.py` nur im kontrollierten Signup-Testfenster (p2c-auth „Staging“ 3). Bei Signup AUS
   meldet es `SKIP signup …`; das ist kein Hook-Nachweis. Der Header-Test bucht das Ticketbudget der eigenen IP,
   danach eine Minute keine Kontoanlage.
5. `check_support.py` wartet bis zu 15 Minuten auf Antwort und interne Notiz im Admin (Betreiber mit MFA).
6. `check_deletion_job.py` setzt als einzigen Owner-Eingriff `deletion_requested_at` auf „vor 15 Tagen“
   (G8 „staging verkürzt“) und wartet dann nur auf den Cronjob.
7. Spuren auf staging, die nicht vom Purge erfasst werden:
   - Auth-Audit-Log;
   - `cron.job_run_details`;
   - Budgetzeilen (`private.age_attempts`, `ops.support_budgets`), die die stündlichen Jobs aufräumen;
   - vorgerückte Versionszähler.

   Deshalb nie gegen Produktion laufen lassen.
8. Aufruf je Skript: `uv run tools/pilot-checks/check_<name>.py --confirm-staging <ref> | tee <log>`.
   Die Logs enthalten Konto-Tags (`k7-…`) und den Hostnamen, aber keine Schlüssel.

Bei einem Abbruch vor dem Aufräumen sind Restkonten am Tag erkennbar. Sie werden als Owner so entfernt:
`select private.purge_account(id) from auth.users where email like '%k7-%'`
(vorher die Treffer ansehen).

### Lokaler Probelauf in K7

Logs liegen unter `%TEMP%/advantly-k7-20261023/`.
- `test_stack.py`: 4/4 Tests grün. Drei Mutationen der Zielschranke wurden jeweils erkannt: Flag-Prüfung aus,
  Hostprüfung aus, Pooler-Benutzerprüfung aus.
- Alle Skripte kompilieren (`py_compile`). Ohne Ziel und bei staging ohne Flag brechen sie vor jeder Verbindung ab.
  Gegen einen nicht laufenden lokalen Stack enden sie mit `FAIL … ConnectionTimeout`, ohne DSN oder Schlüssel
  in der Ausgabe (`dry-run-no-stack.log`).
- **Nicht lokal gelaufen:** alle `check_*.py` gegen einen echten Stack, weil Docker und die Supabase-CLI hier
  fehlen. Die RPC-Verträge sind aus den Migrationen und Client-DTOs abgeleitet, aber nicht ausgeführt. Der erste
  CI- oder Stacklauf ist der eigentliche Nachweis. Abweichungen dort sind Skript- oder Produktbefunde und
  getrennt zu bewerten.

## Geräte-Protokollbögen

### Datensicherung vor jedem Gerätebogen

Pixel 9 Pro XL und Pixel Watch 3 tragen einen alten Build (DB-Schema 4) mit 30 echten Matches. Diese Daten
dürfen nicht verloren gehen.

1. Prüfen, ob der Build debugfähig ist: `adb -s <handy> shell run-as eu.advantly true` (Exitcode 0), ebenso an
   der Uhr. Ist er es nicht, ist keine Sicherung per `run-as` möglich. Dann die echten Geräte für **keinen**
   Bogen verwenden, der Updates, Löschung, Kontowechsel oder Restore enthält.
2. Sicherung mit dem vorhandenen Werkzeug. Nach Matchende ausführen, es beendet die App per `force-stop`:
   `.\tools\p1-gate\pull-and-compare.ps1 -Phone <handy> -Watch pixel=<ip:port> -SkipCompare -OutDir H:\advantly-sicherung\<JJJJMMTT>`.
   Es kopiert `advantly.db`, `-wal` und `-shm` je Gerät mit Größenprüfung. Eine zweite Kopie auf einem anderen
   Datenträger ablegen. Nicht ins Repo, die Dateien enthalten Spielernamen.
3. Kontrolle: Verlauf am Handy und an der Uhr zeigt 30 Matches. Screenshot der Liste und drei Endstände notieren.
4. Rückweg (ungeprüft, vor Gebrauch an einem Zweitgerät üben):
   - alte APK gleicher Signatur aufbewahren;
   - `adb install -r -d <alte.apk>` (Downgrade nur bei debugfähigem Build);
   - `adb push advantly.db* /data/local/tmp/`, danach `adb shell run-as eu.advantly cp /data/local/tmp/advantly.db* databases/`;
   - vorher `am force-stop eu.advantly`.
5. Testkonten verwenden, keine privaten Konten. Destruktive Bögen (V1, V4, V5, RC, W) bevorzugt auf Zweitgeräten
   ausführen: Pixel 6, Galaxy Watch8 oder kleine Galaxy-Variante. Auf den echten Geräten nur Bogen V7 nach der
   Sicherung; Kontrolle 3 danach wiederholen.

### Kopf für jeden Bogen (kopieren)

```
Bogen: ____   Datum/Uhrzeit: ____________   Ausführende Person: ____________
Handy: Modell/Android ____________  App versionName/buildNumber/Commit ____________  Protokoll 3/4
Uhr 1: Modell/Wear OS ____________  App ____________  Protokoll 3/4   Uhr 2: ____________
Backend: lokal / staging (Ref) ____  Migrationsstand (check_release_gate.py) ____
Testkonten (nur Tag, keine Adresse): A ____  B ____   Sicherung (Pfad) ____
Schritt | Ist-Beobachtung | ok? (j/n)
______  | ______________  | ___
Ergebnis: bestanden / nicht bestanden / abgebrochen   Defekte/Abweichungen: ____________
```

Ein Bogen ist nur dann bestanden, wenn jeder Schritt mit einer Ist-Beobachtung ausgefüllt ist.

### Bogen V1 – Offline-Uhr während Löschung (G8)

Voraussetzung: Handy und Uhr von A mit Protokoll 4 und Konto. B ist mit A befreundet. Integrator mit Owner-Zugang.

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | B legt ein Match an, A ist verknüpfter Mitspieler, B hat zusätzlich einen eigenen Gast; Match beenden | A-Handy zeigt das Match nach dem Pull, die Uhr von A zeigt es vollständig, Status „synchron“ |
| 2 | Uhr von A in den Flugmodus oder ausschalten | Handy zeigt die Uhr als nicht verbunden |
| 3 | B: Konto löschen beantragen, abbrechen, erneut beantragen | Frist sichtbar, nach Abbruch wieder aktiv, danach erneut „Löschung beantragt“ |
| 4 | Integrator: Frist verkürzen wie `check_deletion_job.py`, auf `advantly-account-deletion-v1` warten | B in `auth.users` und `profiles` verschwunden; Cronläufe `succeeded` |
| 5 | A-Handy: App öffnen (Pull) | Match bleibt; B und sein Gast heißen „Gelöschter Spieler“ in Verlauf, Zuweisung und Vorschlägen |
| 6 | Uhr wieder verbinden | Nach Reconnect auch auf der Uhr „Gelöschter Spieler“; kein alter Name in Liste, Glance oder Tile |
| 7 | Uhr neu starten, Handy-App neu starten | Name kehrt nicht zurück; Status wird wieder „synchron“ |
| 8 | Variante „Uhr noch Protokoll 3“ (Bogen V7) | Updatehinweis; nach dem Update redigiert |

### Bogen V2 – Neustart und Reconnect mitten in der Übertragung

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Wie V1 bis Schritt 5, mit vielen Matches mit B (mehrere Redaktionsseiten) | Handy hat ausstehende Zustellung |
| 2 | Uhr verbinden; während der Zustellung Handy-App per `adb shell am force-stop eu.advantly` beenden und neu starten | Zustellung beginnt erneut (ab Seite 0) und endet; keine Fehlermeldung |
| 3 | Gleiches mit Neustart der Uhr | Uhr setzt fort; Ergebnis wie oben |
| 4 | Bluetooth am Handy kurz aus und wieder an | Status kehrt zu „synchron“ zurück, kein Name sichtbar |

### Bogen V3 – Keine Namenwiederkehr, kein Wiederhochladen

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Nach V1: an der Uhr ein neues Match starten und die Spielerauswahl öffnen | B und sein Gast werden nicht vorgeschlagen, auch nicht als „zuletzt“ |
| 2 | Revanche/Rematch aus dem alten Match | Kein Klarname von B |
| 3 | Handy und Uhr synchronisieren lassen; Integrator sucht als Owner in `match_events`/`match_participants` nach dem Alias von B nach dem Löschzeitpunkt | Kein Treffer: nichts mit altem Namen wieder hochgeladen |

### Bogen V4 – Entzug und Wiederfreigabe

Die Umsetzung hängt davon ab, welche Wege die App anbietet: Teilnehmer entfernen, „War ich nicht“ oder Entfreunden.

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | A verliert die Mitgliedschaft an einem Match von B (G13) | Events, Rollen und Live-Stand verschwinden auf Handy und Uhr; höchstens die Feed-Karte bleibt |
| 2 | Uhr während des Entzugs offline, danach verbinden | Auch auf der Uhr entfernt |
| 3 | Mitgliedschaft wieder erteilen | Match erscheint nach dem nächsten Pull wieder; auf der Uhr erst nach der neuen Zustellung |
| 4 | Nochmals entziehen | Kein Rückfall auf frühere Rechte |

### Bogen V5 – Konto A/B getrennt

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Handy mit A und Uhr synchron | A-Matches auf beiden |
| 2 | Abmelden, mit B anmelden (Flugmodus während des Wechsels einmal ein und aus) | Keine A-Daten bei B, auch nicht auf der Uhr; keine Mischanzeige |
| 3 | Zurück zu A | Eigene A-Daten erst nach bestätigter Anmeldung wieder sichtbar |
| 4 | Ein Konto C mit gleichem Alias wie A anlegen | C sieht keine Daten von A |

### Bogen V6 – Unabhängige Zustell-Acks

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Zwei Uhren am Handy von A (z. B. Pixel Watch und Galaxy Watch), beide synchron | – |
| 2 | Uhr 2 offline; Löschung wie V1 | Uhr 1 wird redigiert, das Handy hält für Uhr 2 einen Rückstand |
| 3 | Uhr 2 verbinden | Uhr 2 wird redigiert, ohne dass Uhr 1 erneut Arbeit bekommt; danach beide „synchron“ |

### Bogen V7 – 3↔4 mit echter alter Store-App

Voraussetzung: alte v3-App aus dem Play-Testtrack bzw. der Store-Build gleicher Signatur. Auf echten Geräten
nur nach der Sicherung.

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Handy neu (v4, mit Konto), Uhr alter Store-Build (v3) | Beide zeigen einen verständlichen Updatehinweis (DE und EN prüfen); es fließen keine Matches, keine Teilsynchronisierung |
| 2 | Handy alter Store-Build, Uhr neu, mit Konto | Ebenso Updatezustand |
| 3 | Ohne Konto: neue und alte App gemischt | P1-Synchronisierung über v3 funktioniert wie bisher |
| 4 | Uhr aktualisieren | Synchronisierung setzt ein, Redaktionen werden angewendet, Status „synchron“ |
| 5 | Echte Geräte: Handy-Update von Schema 4 | Alle 30 Matches vorhanden, drei notierte Endstände unverändert |

### Bogen V8 – Gestaffelter Rollout Phone/Wear

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Play: Phone-App im gestaffelten Rollout, Wear-App noch alt (oder umgekehrt) | Updatehinweis wie V7; keine Datenverluste; lokales Zählen bleibt möglich |
| 2 | Mehrere Tage gemischter Betrieb, Matches auf beiden Geräten zählen | Nach dem Update der zweiten App fließt alles nach, keine Duplikate (`gate-check compare`) |
| 3 | Rollout pausieren oder zurücknehmen | Kein Gerät fällt auf frühere Rechte zurück |

### Bogen T – Wear Data Layer und WatchConnectivity mit Verlusten

Teil a (Wear OS): Integrität mit `tools/p1-gate/pull-and-compare.ps1` prüfen (0 verlorene oder doppelte Punkte).

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Während des Zählens an der Uhr Bluetooth am Handy alle ~30 s aus und an (5×) | Kein Punkt verloren oder doppelt; Status kommt zu „synchron“ zurück |
| 2 | Handy-App während laufender Übertragung per `force-stop` beenden | Wie oben |
| 3 | Uhr außer Reichweite (> 30 m) während eines Satzes, dann zurück | Wie oben |
| 4 | Uhr-Neustart während ausstehender Events | Wie oben |
| 5 | Kleine Galaxy Watch: Schritte 1–4 wiederholen | Wie oben, Anzeige ohne Abschneiden |

Teil b (watchOS): erst mit dem Apple-Paket und iOS-Konto-Host. Bis dahin nur P1/v3 mit denselben Schritten.

### Bogen W – Geräte-Widerruf in der App

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Konto A auf zwei Handys (Zweitgeräte), beide synchronisieren | Beide in der Geräteliste |
| 2 | Auf Handy 1 Handy 2 widerrufen | Handy 2 kann weder hochladen noch laden, zeigt einen verständlichen Zustand; lokale Daten bleiben |
| 3 | Handy 2: abmelden und neu anmelden | Neue Installation bekommt neue Bindung; alte Geräte-ID bleibt gesperrt (Integrator: `cloud_list_installations_v1`) |
| 4 | Fremdes Gerät eines anderen Kontos | Nicht widerrufbar, nicht sichtbar |

### Bogen RC – Restore und Recovery (Android)

Erst nach Integration des Recovery-Pakets. Nur Zweitgeräte und Testkonten.

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Handy mit Konto A und Matches; `adb shell bmgr backupnow eu.advantly` (oder Systembackup) | Sicherung erstellt |
| 2 | App deinstallieren und neu installieren (Auto-Restore) oder Gerätewechsel mit Übertragung | App startet gesperrt: keine Session, kein Link, keine privaten Anzeigen vor Bestätigung |
| 3 | Mit A anmelden | Eigener Bestand wieder sichtbar; neue Installation und Geräte-ID; alte Geräte-ID nicht übernommen |
| 4 | Stattdessen mit B anmelden | Nichts von A sichtbar; nichts gelöscht |
| 5 | Uhr | Ausdrückliche Wiederkopplung nötig; alte Pairing-Autorität ungültig |
| 6 | Normaler Offline-Neustart ohne Restore | Wird nicht als Restore behandelt |

### Bogen E – Export am Handy (G9)

| Nr | Aktion | Erwartete Beobachtung |
|---|---|---|
| 1 | Konto mit gemeinsamem Match: Export anfordern | Fortschritt sichtbar, danach Download |
| 2 | ZIP am Handy öffnen (Dateien-App) | `data.json` und CSVs lesbar; eigene Daten vollständig; keine fremden Profil- oder Geräte-IDs |
| 3 | Export nach Abmeldung erneut öffnen bzw. nach 24 h | Nicht mehr abrufbar |

### Bogen G – Provider Google und E-Mail-Code

Nach p2c-auth „Vor manueller Freigabe auf staging/Pixel“ 3–5:
- a) E-Mail-Code bis Alias; Ticket-Replay und abgelaufenes Ticket werden abgelehnt.
- b) Google-ID-Token bis Alias.
- c) Google → E-Mail-Code mit derselben verifizierten Adresse ergibt dieselbe Profil-ID.
- d) E-Mail-Code → Google manuell hinzufügen (auch andere Adresse): Profil-ID unverändert; Konflikt mit einer
  anderswo verknüpften Identität wird abgelehnt.
- e) Neustart/Rotation: Sitzung bleibt, keine Tokens in Logcat.

Jeden Punkt mit Ist-Beobachtung im Bogenkopf festhalten.

### P1-Feldtestmatrix

Maßgeblich bleibt [p1-gate-protokoll.md](release/p1-gate-protokoll.md):
- Matrix §3, Ergebnisbogen §8, Ablage unter `docs/release/p1-gate-berichte/<Datum>/`;
- die kleine Galaxy Watch ist Pflicht-Regression ([Displaygrößen](p2-next-steps.md#displaygrößen-als-laufendes-abnahmekriterium)).

Stand 23.10.2026: Ergebnisbogen leer, also offen.
