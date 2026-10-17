# Cloud-Synchronisierung und Online-Pilot: Lieferplan

Stand: 17.10.2026. Konkretisierung der beauftragten Punkte 2 (Upload), 3 (Download)
und 4 (Pilot, Datenschutz, Kontosupport). **Planung, noch keine Umsetzung dieser Pakete.**
Grundlage: [PLAN](PLAN.md), [P2](p2-plan.md), [Upload-Vertrag](p2-upload.md)
und [Feedback/Admin](plan-feedback-admin.md). Bei Widersprüchen gilt `PLAN.md`.
ChatGPT hat den Cloud-Pfad untersucht, Claude die Pilot-/Betriebsfunktionen; die folgende
Reihenfolge führt beide Berichte zusammen und hält bestehende Produktentscheidungen bei.

## Bestand und Abgrenzung

- B1 und B2a sind integriert: lokale Outbox, getrennte Geräte-/Cloud-Acks, kontogebundene
  Cursor, unveränderliche Geräte-Eigentümer und sitzungsgebundene Upload-Auswahl.
  `CloudUploadStore` ist vorhanden; `CloudBackend`, `CloudOutboxStore` und `CloudSyncPort`
  sind noch keine ausführbare Netzwerkanbindung der App.
- Auth/Onboarding und Support-Inbox sind vorhanden. Staging verwendet Supabase, Resend
  und Cloudflare; Betreiber-Login mit MFA und Passwort-Recovery wurden im Projektthread bestätigt.
  PR #58 ist integriert. Deployment und Grenzen: [Betriebsnachweis](support-admin-betrieb.md).
- PR #57 enthält den Home-Feedbackknopf und die korrigierte Uhren-Erreichbarkeit, zuletzt
  `b2aa40d`. Auf Pixel 9 Pro XL und Pixel Watch 3 installiert; drei Meldekategorien laut
  Nutzer erfolgreich. CI/Integration dieses PR separat abschließen, nichts doppelt implementieren.
- SQL für Export, Löschantrag, Abbruch, Anonymisierung und endgültige Löschung existiert.
  App-Bedienung, ZIP-Auslieferung und geplanter Kontolöschjob fehlen. Vorhandene SQL-Funktionen
  sind kein Nachweis eines automatischen gehosteten Ablaufs.
- Freunde, Cloud-Live, eigenständige Wear-Sitzungen, Health/Tracker und Garmin folgen in ihren
  bestehenden Phasen. Tizen bleibt ausgeschlossen. Öffentliche Veröffentlichung erst nach P3.

## Reihenfolge und Verantwortung

Jede Zeile ist ein prüfbares Lieferpaket, bei Bedarf in kleine PRs teilbar. Ein benannter
Implementierer besitzt die angegebenen Module; gemeinsame Dateien werden nacheinander geändert.
Orchestrator: Abhängigkeiten, Review, CI und Abnahmen. Für die Umsetzung bleibt ChatGPT der
Standard; zusätzliches Claude-Kontingent gezielt für unabhängige Fachprüfung verwenden.

| Paket | Verantwortung / Änderungsbereich | Voraussetzung | Ergebnis |
|---|---|---|---|
| U1 | Auth-Host Android, `shared/cloud-sync`, HTTP-Adapter, Backend-Vertrag | B2a | Anfragen an echte Auth-Sitzung und belegbaren Gerätekontext binden |
| U2 | `shared/data`, `shared/match-session`, Android-Host | U1 | Eigene Altbestände und neue Identitäten eindeutig zuordnen |
| U3 | `shared/data`, Cloud-DTOs | U2 | Revisionsstände für Köpfe, Spieler und Projektionen |
| U4 | `shared/cloud-sync`, `shared/data`, Android-Host | U1–U3 | Begrenzter ausführbarer Upload mit korrektem Retry |
| D1 | `shared/cloud-sync`, `shared/data` | U4 | Dauerhafter Pull mit Paging, Wasserständen und Zugriffsstufen |
| D2 | Datenhaltung, Geräteprotokoll, Match-Session | D1 | Rechteentzug und Wiederfreigabe bis zu lokalen Replikaten |
| D3 | Datenhaltung, Geräteprotokoll, Match-Session, nötige Facades | D1, D2, K3-Serververtrag | Redaktionen auch auf zuvor vollständig synchronisierte Uhren |
| B4 | Android-/iOS-Hosts, Cloud-Koordination, Präsentation | U4, D1; Freigabe erst mit D2/D3 | Hintergrundbetrieb und ehrlicher Sicherungsstatus |
| K1–K3 | Backend und Phone-Präsentation/UI | Vorhandene Auth-/Konto-RPCs | Zustimmung, Export, Löschung parallel zum Cloud-Pfad vorbereiten |
| K4–K6 | Backend, Admin-Web, Betriebsdaten | siehe unten | Begrenzter Kontosupport und Betriebsansicht |
| K7 | Orchestrator und reale Geräteprüfung | Cloud-Pfad, K1–K6, externe Nachweise | Dokumentierte Pilotentscheidung |

**Erster Umsetzungsschnitt: U1 (Clientgrenze umgesetzt, Serverfreigabe offen).** Danach U2/U3;
parallel kann K1 vorbereitet werden. K3-Serververtrag früh
festlegen, damit D3 nicht auf eine spätere UI warten muss. B4 darf die Begrenzung und den
Abbruch eines einzelnen Upload-/Pull-Laufs nicht aufschieben: diese gehören bereits zu U1/U4/D1.
Apple-Host und echte Apple-Abnahme sind getrennte Lieferungen; ein BGTask allein stellt keine iOS-Parität her.

## Punkt 2: Upload (B2b)

### U1 – Auth-Sitzung und tatsächlichen HTTP-Request gemeinsam binden

Umsetzungsstand 17.10.2026: Clientgrenze mit expliziter Generation, festem HTTP-Bearer,
sofortigem lokalem Abbruch und an Auswahl/Bindung gekoppeltem Ack umgesetzt.
Der [U1-Vertrag](p2-u1-sitzungs-und-geraetevertrag.md) beschreibt die ausführbare, ohne bestätigten
v1-Server gesperrte Gerätebindung und die noch offene Server-/K4-Abnahme. Automatische
Uploads/Downloads und die Serverdurchsetzung bleiben U4/D1/K4; U1 erteilt keine Pilotfreigabe.

Explizite Sitzungsgeneration vom Auth-Lebenszyklus bis HTTP-Adapter, Upload-Auswahl und Ack führen.
Gleiches Konto nach erneuter Anmeldung ist eine neue Sitzung; Token-Erneuerung innerhalb derselben
Sitzung ist davon zu unterscheiden. Ein `StateFlow<CloudSession>` mit gleichen Kontodaten genügt nicht.
Vor Umsetzung des Transports den Vertrag für serverseitig belegbare Gerätebindung festlegen:
eine vom Client behauptete Geräte-ID reicht nicht. Auth-Sitzung, Konto und Geräteberechtigung müssen
zusammen geprüft werden. Aktuell erhalten `sync_pull`, `pull_match_events` und `match_event_watermarks`
keinen solchen Kontext. Den Vertrag auf Registrierung, Upload und sämtliche Cloud-Lesewege anwenden;
widerrufene Bindungen dürfen durch `register_device` nicht wieder aktiviert werden. U1 legt diese
Schnittstelle fest, U4/D1 implementieren sie, K4 liefert den Widerruf und den durchgängigen Negativtest.
**Kein Release von U4/D1 ohne K4-Abnahme.** Die unabhängige Rechteprüfung für Export, Löschung und Support
bleibt erhalten; Gerätewiderruf und globaler Auth-Sitzungswiderruf sind unterschiedliche Operationen.
Beim Beginn von Logout/Kontowechsel, Sitzungsverlust oder Löschantrag laufende Arbeit deaktivieren;
nicht erst auf den Netzwerk-Logout warten. Wiederherstellung nach Neustart erst nach Auth-Prüfung.

Abnahme: fremde/fehlende Geräteberechtigung und widerrufene Bindung auch bei gültigem Konto-Token
auf jedem Cloud-Endpunkt verweigert; kein Entsperren durch Registrierung oder neue behauptete ID.
Zusätzlich Konto A → B vor und während HTTP, A → A neu anmelden, Token-Erneuerung, Offline-Logout,
Prozessneustart und verspätete Antwort. Den tatsächlich gesendeten Authorization-Kontext testen;
ein erfolgreicher lokaler Generationstest allein schützt nicht vor dem falschen Token im Request.

### U2 – Eigentum und Identitäten

Nur nachweislich eigene Handy-/Uhren-Geräte und Self-Aliase binden. Empfangene Event-Quellen sind
kein Eigentumsnachweis. Planungsstandard: alte eigene lokale Bestände bewusst zur Übernahme auswählen;
ungeklärte Bestände bleiben lokal ungebunden. Bestehende `cloud_device_owner`-Bindungen nie umhängen.
Beim Kontowechsel neue Geräte-/Self-Identitäten für neue Daten verwenden; historische IDs und
Event-Payloads unverändert lassen. Ein eigenes Gerät verleiht keine Schreibrechte auf fremde Matchköpfe.

Abnahme: fremde/ungebundene Quellen ausgeschlossen, Konflikt atomar zurückgerollt, alte Logs unverändert,
neue Daten unter neuen Identitäten, verifizierte Uhren-Aliase und Rückwechsel zum alten Konto.

### U3 – Revisions- und Herkunftsmetadaten

Schema nach bestehender Version 4 erweitern: Änderungsversionen für Matchköpfe, Teilnehmer, Spieler
und Projektionen sowie deren Herkunft/Schreibberechtigung. Auswahl enthält einen festen Stand;
ein Ack bestätigt nur diesen. Neuere Änderungen bleiben ausstehend. `cloud_dirty` aus dem älteren
Plan ist derzeit keine verfügbare Implementierung.

Abnahme: Änderung während Upload geht nicht verloren, fremder heruntergeladener Kopf wird nicht
zurückgeschrieben, Migration erhält P1-Daten und unabhängige Geräte-/Cloud-Acks.

### U4 – Outbox-Adapter und begrenzter Upload-Lauf

Vorhandene Ports implementieren und `CloudUploadStore` anbinden. Batches begrenzen, Fehler klassifizieren,
Wiederholung idempotent halten. Ob ein Batch der letzte eines Matches ist, aus dem tatsächlichen
Restbestand bestimmen; eine auf 500 Einträge begrenzte Auswahl beweist kein vollständiges Match.
Neue parallele Änderungen bleiben ausstehend. Dauerhafte Ablehnung sichtbar erhalten.

Abnahme gegen lokalen echten Supabase-Stack: Server-Commit mit verlorener Antwort, Retry ohne Duplikate,
Teilbestätigungen, mehr als 500 Events, parallele neue Events, finale Projektion nur bei vollständiger
Auswahl, Director-Backup, dauerhafter Fehler und Kontowechsel ohne falschen Upload/Ack.

## Punkt 3: Download, Entzug und Redaktionen (B3)

### D1 – Pull und dauerhafter Zugriffsstand

Paging, Event-Wasserstände und Lückenheilung implementieren; `access`, `redactionEpoch`, Herkunft
und Vollabgleichsfortschritt dauerhaft speichern. Cursor und angewandte Seite in derselben Transaktion.
Sichtbarkeitsepochen lösen einen vollständigen Abgleich aus; vor dessen Abschluss nichts aufgrund
einer unvollständigen Ergebnismenge entfernen. Eigene noch nicht hochgeladene Daten bewahren.
Neue Downloads bleiben für Geräte ausstehend; Duplikate bewahren vorhandene Geräte-Acks.

Abnahme: mehr als 200 Matches, Seitenabbruch/Neustart, fehlende Events, atomarer Rollback,
Epochenwechsel während Paging, doppelte Seiten und getrennte Kontocursor.

### D2 – Rechteentzug als eigener Zustand

Mitglied → Feed entfernt Eventlog, Rollen, Live-Details und Teilnehmerdaten, erhält nur die erlaubte
Feedkarte. Vollständiger Entzug entfernt auch diese. Nicht den allgemeinen `deleteMatch`-Tombstone
zweckentfremden: Wiederfreigabe muss möglich bleiben. Entzug dauerhaft an offline Geräte zustellen;
alte Peer-Pakete dürfen entfernte Inhalte nicht wiederbeleben.

Abnahme mit zwei Konten: Mitglied → Feed → kein Zugriff → erneute Freigabe, Neustart in jeder Stufe,
offline Uhr, verspätete Peer-/Cloud-Pakete und eigene ungesicherte Daten ohne pauschales Löschen.

### D3 – Redaktionen bis zur Uhr / Protokoll 4

Autorisierte Feldredaktion von normalen Payload-Konflikten unterscheiden, Redaktionsepochen speichern
und Projektionen neu bilden. Auch bereits bestätigte Events einer offline Uhr müssen korrigiert werden.
Neue Logs sollen Klarnamen nicht weiter vervielfältigen; Engine-/Wire-Vertrag koordiniert ändern.
Der bestehende Vertrag nutzt Protokoll 3; Protokoll 4 mit `CURRENT`/`MIN_SUPPORTED`, Hello und
Update-Hinweis gemeinsam liefern, einschließlich Verhalten bei 3↔4 statt stiller Teilkompatibilität.

Abnahme G8: vollständig synchronisierte Uhr während Kontolöschung offline, danach Neustart/Reconnect;
kein alter Name in Verlauf, Vorschlägen oder Rematch und kein Wiederhochladen alter Payloads.
Normale abweichende Event-Payloads bleiben Konflikte. K3-UI und D3 werden gemeinsam Ende-zu-Ende abgenommen.

## Punkt 4: Datenschutz, Kontosupport und Online-Pilot

| Paket | Konkreter Umfang | Abnahme / Abhängigkeit |
|---|---|---|
| K1 Dokumentversionen | Terms-/Privacy-Versionen und bestätigte Version, Sprache, Zeitpunkt speichern; Onboarding und erneute Zustimmung bei fachlicher Änderung | Neue/alte Version, Ablehnung und Export der Historie; Export, Löschantrag, Recovery und Support bleiben erreichbar. Kein pauschales Sperren aller RPCs über `is_active`. Texte fachlich prüfen lassen. |
| K2 Export | Vorhandenes `export_my_data()` als Whitelist nutzen; private ZIP-Ausgabe (JSON/CSV) und App-Download, gemäß bestehendem P2-Gate | K1-Historie enthalten; keine Tokens, fremden Daten oder internen Notizen; private Ablage, Ablauf, Aufräumen und parallele idempotente Anforderung testen. ZIP nicht stillschweigend auf JSON reduzieren. |
| K3 Kontolöschung | In-App-Antrag, sichtbare 14-Tage-Frist, Rücknahme während Karenz über bestehenden RPC; echten Löschjob einrichten und überwachen | Antrag, Rücknahme und endgültige Löschung mit Testkonten; geteilte Matches redigiert, eigene Meldungen entfernt, Audit entkoppelt; echter Joblauf plus D3/G8. Android-SQL-Job und späterer Apple-Token-Widerruf koordiniert planen. |
| K4 Geräte-/Sitzungswiderruf | Eigenes Gerät widerrufen, Push-Token entfernen; globale Auth-Abmeldung und serverseitigen Auth-Admin-Widerruf getrennt behandeln | U2/U4-Gerätebindung; widerrufenes Gerät kann weder pushen noch pullen, auch nicht durch erneute Registrierung selbst entsperren. Fremde Geräte verweigert; keine direkten SQL-Eingriffe in `auth.sessions`. |
| K5 Kontosupport | Exakte Suche nach Alias/Freundescode/UUID, minimaler Kontostatus; Sperren/Entsperren und Widerruf mit Begründung und eigenem Konto-Audit | K3/K4 verwenden; serverseitige Rolle + AAL2 bei jeder Aktion; keine Matches, Health-Daten, Geburtsdaten, Impersonation oder freier SQL-Editor. Audit nach bestehendem Support-Vorbild begrenzen und gelöschte Ziele entkoppeln. |
| K6 Betriebsansicht | Version/Plattform/letzter Kontakt, aggregierter Sync-Rückstand, Fehler und Jobzustand | U4/B4 liefern belastbare Betriebsdaten; fehlende Telemetrie = unbekannt. Zeit/Herkunft sichtbar; keine Spiel-/Health-Inhalte. |
| K7 Pilotnachweise | Ein Nachweisbogen mit Commit, Migration, Gerät/OS, Soll/Ist, Defekten und Freigabestatus | Alle fünf Datenschutz-/Store-Punkte aus P2 §8, relevante G1–G8 aus §11 und Support-Live-Abnahme separat belegen. |

Planungsstandard für K5: `support` bearbeitet Tickets und liest den minimalen Kontostatus;
zustandsändernde Kontoaktionen bleiben `admin` vorbehalten. Das vergibt heute keine neuen Rechte.
Mitglieder werden weiterhin gezielt durch den Betreiber provisioniert. Löschrücknahme im Kontosupport
nur auf dokumentierten Nutzerwunsch. Die genaue Login-/Rücknahme-UX an §8.3 des Datenmodells ausrichten,
vor Umsetzung ausdrücklich darstellen; Token-Refresh darf keine unbeabsichtigte Rücknahme auslösen.

Gastmeldungen werden keinem späteren Konto automatisch zugeordnet. Eine Kontolöschung kann sie daher
nicht anhand einer bloßen Kontaktadresse automatisch finden/löschen; kontrollierten Besitznachweis
und bestehenden manuellen Gastprozess beibehalten.

Externe Pilot-Nachweise: Texte DE/EN, Anbietervereinbarungen und tatsächliche Datenflüsse von Supabase,
Resend, Cloudflare und aktivierten Login-Anbietern prüfen; VVT, Data safety und Prüferzugang abschließen.
Die vorhandene EU-Anbieter-/Datenschutzvorgabe ist durch die technische Resend-Einrichtung allein
nicht nachgewiesen. Gehosteten Altersgate-Negativtest und ausstehende Provider-Abnahmen dokumentieren.
Backup-/Wiederherstellungskonzept vor Fremdnutzer-Pilot bewerten. Keine Rechtsfreigabe aus diesem Plan ableiten.

## Gemeinsame Lieferung und Nachweise

- UI immer DE/EN; kleine/große Phone- und Uhrenflächen sowie große Systemschrift nach
  [Display-Abnahmekriterien](p2-next-steps.md#displaygrößen-als-laufendes-abnahmekriterium).
  Kleine Galaxy Watch als Regression, Pixel Watch separat; iPhone/Apple Watch nicht aus Android ableiten.
- Vor Commit `gradlew check`; je Änderung passende SQL-/HTTP-Vertragstests. Vor Merge unabhängiges
  Review und grüne CI. Keine Stubs als Ersatz für Auth/Gateway/Cron-Nachweise deklarieren.
- Support: drei Kategorien sind Nutzerbestätigung; Antwort in „Meine Meldungen“, interne Notizen,
  Fremdzugriff, fehlende MFA und widerrufene Mitgliedschaft bleiben als vollständige Live-Matrix offen.
- Android-Teilgate getrennt von Apple: iOS-Konto-Host, Apple-Account, Token-Widerruf und reale Geräte
  zusätzlich nachweisen. P2-Pilot ist keine öffentliche Produktionsfreigabe.
- Nächster ausführbarer Auftrag nach U1-Review: U2/U3 für Eigentum, neue Identitäten und
  Metadatenrevisionen. Serverdurchsetzung des U1-Vertrags mit U4/D1 und K4 nachweisen.
  Dieser Plan löst noch keine Cloud-Uploads, neuen Admin-Rechte oder Pilot-Verteilung aus.
