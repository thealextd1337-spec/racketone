# Nächste Phasen – Ausführung ab 16.10.2026

Dieser Arbeitsplan konkretisiert [P2](p2-plan.md); fachlich gilt weiterhin [PLAN.md](PLAN.md).

## Übernommener Stand

- Ausgangspunkt: `main` bei `5e3db67`, PRs #43–#49 integriert. Kein offener PR bei Übernahme.
- P2-A Backend-Härtung, P2-C Android-Login und P2-J Telemetrie-Sichtbarkeit sind im Code vorhanden.
  P2-C ist ohne Auth-Konfiguration, Zwei-Geräte-Cloud-Test und P2-K noch nicht vollständig abgenommen.
- Laut Feldtest-Protokoll des bisherigen Projektthreads läuft dieser Build auf Pixel 9 Pro XL,
  Pixel Watch 3 und Pixel 6. Die Galaxy Watch8 wartet dort auf einen erreichbaren WLAN-Debug-Port.
  Das sind übernommene Nachweise, kein neuer Gerätetest in diesem Arbeitspaket.
- staging-Migrationen sind laut Übergabe bis `20261016000600` ausgerollt. Hook-Negativtest und
  Google-Anmeldung stehen aus; diese Übergabe ersetzt keine Prüfung des gehosteten Systems.
- Der Backend-Lasttest verwendet noch den seit P2-C gesperrten Onboarding-RPC.

## P2-1: Fundament fertigstellen

1. **Backend-Lasttest reparieren.** Fixture über Alters-Ticket, Bindung, Auth-Hook und `finish_onboarding`
   anlegen. Rechte/Trigger bleiben aktiv. Abnahme: 500 Matches lesbar, parallele Uploads ohne fehlende
   Matches, Latenzbudgets bestanden, eigenes Fixture-Konto nachher gelöscht. Nur wegwerfbare lokale DB.
2. **P2-B1 – lokale Persistenz.** Schema 3 mit unabhängiger Event-Outbox, Ablehnungsgründen und
   kontogebundenen Cursorn. Downloads werden atomar dedupliziert, Inhalts-/Sequenzkonflikte zurückgerollt.
   Abnahme: bestehende P1-Daten und Geräte-Acks unverändert, Neustart/Wiederholung/Teilantwort getestet,
   Gradle-Checks und unabhängiges Review. Noch keine App-/Netzwerk-Anbindung.
3. **P2-B2 – Upload.** Eigentümerbindung für lokale P1-Daten und Self-Aliase festlegen, Kontowechsel
   absichern; dirty/revision-Marken für Köpfe/Spieler und Projektionen ergänzen. Outbox-Adapter und
   `CloudBackend` anbinden. Bestätigungen dürfen Änderungen während eines Uploads nicht verlieren.
   Nur eigene Geräte/Events hochladen. Abnahme: Abbruch nach Server-Commit, Retry ohne Doppelung,
   über 500 Events in mehreren Batches, Director-Backup, kein Upload unter falschem Konto.
4. **P2-B3 – Download und Entzug.** Paging, Event-Wasserstände, Sichtbarkeits-Epochen, Vollabgleich,
   Tombstones, Übergang Mitglied → Feed und Redaktionen bis zur Uhr. Cursor und angewandte Daten
   atomar speichern. Abnahme: zwei Konten/Geräte, unterbrochene Seiten, fehlende Events,
   Rechteentzug und Löschung ohne Wiederauftauchen.
5. **P2-B4 – Betrieb.** WorkManager/BGTask, App-/Netz-Auslöser, begrenzte Wiederholungen und
   nachvollziehbarer Sicherungsstatus. Auth-Session-Wechsel und Logout stoppen laufende Arbeit sicher.
6. **P2-K – technische Datenschutzfunktionen parallel zu B2/B3.** Versionierte Zustimmung,
   funktionsfähiger Export und Kontolöschung mit 14 Tagen Karenz; Widerruf eigener Geräte-Sitzungen.
   Erklärung DE/EN, Anbietervereinbarungen, Data safety und Prüferzugang anhand der tatsächlichen
   Datenflüsse abschließen. Vor dem ersten Pilot mit Auth/Cloud gilt das vorhandene Pilot-Gate.

## Danach in Abhängigkeitsreihenfolge

- **P2-2 Sozialer Kern:** D Freunde + I Push, danach E geteilte Matches und F Zuweisung/Claim;
  L Entitlements vorbereiten. Abnahme jeweils mit zwei echten Konten inklusive Blockieren/Widerspruch.
- **P2-3 Live:** G bestätigte Zählerübernahme, H Live/Visitor mit sofortigem Entzug,
  N eigenständige Wear-Sitzung. Erst nach belastbarem Upload/Pull und sozialen Rollen.
- **P2-4 Abschluss:** M Turnierübersicht, Q Chaos-/Vertrags-/Gerätetests und dokumentiertes Android-Teilgate.
- **P3 Statistik/Health:** erst nach P2-Gate; getrennte Einwilligungen, Speicherung und Health-Anbindung.
  Öffentliche Veröffentlichung weiterhin erst nach P3 und den bestehenden Release-Gates.

## Externe Voraussetzungen

Auth-Einrichtung: Signup/Anonymous/Google aus → `public.before_user_created` als Hook aktivieren →
kontrollierter Negativtest → Provider einrichten. Ablauf und Nachweise: [P2-C](p2c-auth.md).
E-Mail-Anbieter ist noch offen; Apple-Account und echte Apple-Tests bleiben separat ausstehend.
Diese Punkte blockieren lokale Implementierung und automatische Tests von B1–B3 nicht, wohl aber
die entsprechenden Live-Abnahmen. Keine Produktionsfreigabe aus einem lokalen Testergebnis ableiten.

## Integration

Eigener Worktree/Branch je Lieferung; vor Commit `gradlew check`, vor Merge unabhängiges Review
und grüne CI. Apple-Linking/Simulator-Nachweise kommen aus macOS-CI. Datenbanktests mit lokalem
Postgres-Stubsatz ersetzen nicht den echten Supabase-Stack mit Gateway, Auth und `pg_cron`.

## Nachweise dieser Lieferung

- B1 ist implementiert; 44 JVM-Tests in `shared/data` einschließlich elf neuer Outbox-/Migrationstests
  bestanden. SQLDelight-Migrationsprüfung und `gradlew.bat check` bestanden (686 Tasks, 1 min 36 s).
  Unabhängiges Review und CI sind vor Integration zusätzlich erforderlich.
- Lasttest gegen einen frisch aufgebauten lokalen PostgreSQL 17.10 mit allen aktuellen Migrationen:
  500 Ausgangsmatches, vier Schreiber mit je 50 Uploads, 20 Pull-Messungen. Ohne parallelen Build:
  0 fehlende Matches, 0 verbleibende Fixture-Konten, maximale Sperrzeit 117,488 ms (Budget 200 ms),
  maximaler 500-Match-Pull 40,717 ms (Budget 2.000 ms).
- Erster Lauf gleichzeitig mit Gradle: maximale Sperrzeit 313,901 ms, Budget verfehlt; ebenfalls
  keine fehlenden Matches. Wiederholung ohne konkurrierenden Build bestanden. Das ist lokale
  Entwicklungsrechner-Evidenz und kein gehosteter Kapazitätsnachweis.
- B2–B4, P2-K sowie reale Konto-/Cloud-Abnahmen bleiben offen. Die neuen Primitive starten keinen
  Upload und ersetzen noch nicht den `CloudOutboxStore`-Adapter aus dem ursprünglichen P2-Plan.
