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
7. **Feedback & Admin vor nächstem Online-Pilot.** Android/iOS: Feedback, Featurewünsche und Fehler
   erfassen und bewusst absenden. Geschütztes Web-Admintool: Meldungen bearbeiten, Kontosupport,
   Geräte-/Sync-Betrieb und protokollierte Verwaltungsaktionen. Nach B2a den Meldeweg samt privater
   Inbox als eigenes Paket neben B2b angehen; [Umfang und Abnahme](plan-feedback-admin.md).

## Danach in Abhängigkeitsreihenfolge

- **P2-2 Sozialer Kern:** D Freunde + I Push, danach E geteilte Matches und F Zuweisung/Claim;
  L Entitlements vorbereiten. Abnahme jeweils mit zwei echten Konten inklusive Blockieren/Widerspruch.
- **P2-3 Live:** G bestätigte Zählerübernahme, H Live/Visitor mit sofortigem Entzug,
  N eigenständige Wear-Sitzung. Erst nach belastbarem Upload/Pull und sozialen Rollen.
- **P2-4 Abschluss:** M Turnierübersicht, Q Chaos-/Vertrags-/Gerätetests und dokumentiertes Android-Teilgate.
- **P3 Statistik/Health:** erst nach P2-Gate; getrennte Einwilligungen, Speicherung und Health-Anbindung.
  Einschließlich [Tracker ohne Display](p3-tracker-import.md): Daten via Handy nachträglich dem eigenen Match
  zuordnen, Nachlieferungen und mehrere Quellen ohne Doppelzählung verarbeiten.
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

- B1 ist implementiert; 50 JVM-Tests in `shared/data` einschließlich 17 neuer Outbox-/Migrationstests
  bestanden. SQLDelight-Migrationsprüfung und `gradlew.bat check` bestanden.
  Unabhängiges Review und CI sind vor Integration zusätzlich erforderlich.
- Review-Runde 1: JSON-Payloads strukturell vergleichen (Feldreihenfolge/Leerzeichen), alte leere Typfelder
  ableiten und vorhandene Payloads unverändert lassen. Cloud-Empfang darf den Geräte-Rückstand nicht
  bestätigen; Duplikate behalten ihren Geräte-Ack, neue Downloads bleiben für Geräte ausstehend.
  Beide Befunde sind korrigiert und mit zusätzlichen Regressionstests abgesichert.
- Lasttest gegen einen frisch aufgebauten lokalen PostgreSQL 17.10 mit allen aktuellen Migrationen:
  500 Ausgangsmatches, vier Schreiber mit je 50 Uploads, 20 Pull-Messungen. Ohne parallelen Build:
  0 fehlende Matches, 0 verbleibende Fixture-Konten, maximale Sperrzeit 117,488 ms (Budget 200 ms),
  maximaler 500-Match-Pull 40,717 ms (Budget 2.000 ms).
- Erster Lauf gleichzeitig mit Gradle: maximale Sperrzeit 313,901 ms, Budget verfehlt; ebenfalls
  keine fehlenden Matches. Wiederholung ohne konkurrierenden Build bestanden. Das ist lokale
  Entwicklungsrechner-Evidenz und kein gehosteter Kapazitätsnachweis.
- B2–B4, P2-K sowie reale Konto-/Cloud-Abnahmen bleiben offen. Die neuen Primitive starten keinen
  Upload und ersetzen noch nicht den `CloudOutboxStore`-Adapter aus dem ursprünglichen P2-Plan.

## Fortschritt nach PR #50

P2-B1 wurde am 16.10.2026 nach dem Gegencheck „ship“ und vier grünen CI-Checks als `cc0466b` integriert,
einschließlich Kotlin/Native-/Apple-Simulator- und echtem Supabase-pgTAP-Lauf.
P2-B2 beginnt mit **B2a: Geräte-Eigentümer, Upload-Sitzungen und an die Auswahl gebundene Antworten**.
Vertrag, Integrationsgrenzen und das verbleibende B2b-Paket: [p2-upload.md](p2-upload.md).

## Ergänzung aus dem Feldtest: F31 / W9

Der Planungs-PR [#51](https://github.com/thealextd1337-spec/racketone/pull/51) ergänzt einen sichtbaren
Versionsstand und ein kleines Smartwatch-Symbol auf der Handy-Startseite. Laut Nutzer-Rückmeldung vom
16.10.2026 ist das Uhren-Ampelsystem fertig implementiert und wird parallel geprüft. W9 wird daher
hier als **implementiert, Review läuft** geführt; es ist kein weiteres Implementierungspaket dieses Threads.
Versionsanzeige (Version, Commit, Build-Zeit) und Ampelsystem bleiben in der bestehenden parallelen Lieferung.

- Grün: verbunden und gleicher Commit. Gelb: verbunden und kompatibel, aber anderer oder unbekannter Stand.
  Rot: nicht erreichbar oder inkompatibel. Grau: keine gekoppelte Uhr.
- Antippen zeigt Uhrname, beide Versionsstände und den letzten Kontakt. Auf der Uhr erscheint der
  entsprechende Handy-Status am Ende der Startliste.
- Je gekoppelter Uhr ein Symbol; Farbe durch Form/Text und Screenreader-Beschriftung ergänzen.
- Versionsmetadaten werden optional im `Hello` ergänzt; Protokoll 3 und die Behandlung älterer Geräte
  bleiben kompatibel. Ein unbekannter App-Stand darf nicht als gleicher Stand erscheinen.

Reihenfolge: laufendes B2a-Paket integrieren und B2b fortsetzen. Für W9 nur das Ergebnis des parallelen
Reviews, CI, Merge und die dokumentierte Geräteabnahme übernehmen; keine erneute Umsetzung oder
doppeltes Review starten. „Implementiert“ bedeutet noch nicht „integriert und auf Geräten abgenommen“.
Maßgeblich sind
die F31-/W9-Details aus `docs/00-entscheidungen.md`, `docs/04-ux-design.md` und `docs/PLAN.md` nach Merge von #51.
Laut aktualisierter Feldtest-Rückmeldung ist auch die Galaxy Watch8 inzwischen auf dem aktuellen Stand;
die ältere Port-Blockade im übernommenen Ausgangsstand ist damit überholt. Noch kein neuer Testnachweis für W9.

## Displaygrößen als laufendes Abnahmekriterium

Der Feldtest vom 16.10.2026 erforderte laut Nutzer eine Layout-Anpassung für die kleinere Galaxy-Watch-Variante.
Die bestehenden UX-Vorgaben (responsive Ränder, System-Schriftgrößen und deutsche Screenshots kleiner Uhren,
[04-ux-design.md](04-ux-design.md), §§4.3.5, 8 und 9) werden deshalb für kommende UI-Pakete ausdrücklich geprüft:

- Kleine und große Galaxy- und Pixel-Watch-Varianten, kleine rechteckige Apple-Watch-Displays und Ultra berücksichtigen.
  Die tatsächlich verwendete Galaxy Watch als festen Regressionstest führen; Modell, nutzbare Fläche in dp/pt,
  Dichte und Schriftgröße im Testnachweis erfassen. Gehäusegröße oder Marke allein bestimmen das Layout nicht.
- iOS: kleine und große iPhone-Displays, Safe Areas und Dynamic Type abdecken. iPad-Flächen nur dann als
  unterstützte Geräteklasse abnehmen, wenn iPad-Unterstützung ausdrücklich zum Produktumfang gehört.
- Garmin: in der Garmin-Phase kleine und große runde sowie rechteckige Displays, MIP/AMOLED und Bedienung
  per Tasten beziehungsweise Touch prüfen. Das ist eine Vorgabe für P4, keine Behauptung bestehender Unterstützung.
- Live-Score, Doppel-Spielernamen, Aufschlagwahl, Dialoge, Startliste und F31-Status/Versionsanzeige prüfen:
  keine verdeckten Aktionen, überlappenden Texte oder abgeschnittenen Punkte am runden Rand.
- Deutsch und Englisch, lange Namen sowie vergrößerte Systemschrift prüfen. Bedienflächen erhalten;
  bei Platzmangel Inhalte sinnvoll anordnen oder Listen scrollbar machen, nicht pauschal alles verkleinern.
- Vor Abnahme Layout-/Screenshot-Prüfungen auf kleinen und großen Flächen dokumentieren; echte Geräteprüfungen
  für Galaxy Watch, Pixel Watch, iPhone und Apple Watch sowie in P4 Garmin getrennt nachweisen. Die kleine
  Galaxy Watch ist verpflichtender Regressionstest. Noch nicht verfügbare Geräte bleiben als offene Nachweise
  sichtbar; ein Test auf einer Marke oder Größe ersetzt nicht die übrigen Varianten.

Die heutige Anpassung ist übernommene Feldtest-Evidenz, kein Nachweis, dass alle Ansichten und Größen bereits
abgedeckt sind. W9 bleibt in seiner parallelen Lieferung; dessen Größenabnahme wird dort übernommen.
