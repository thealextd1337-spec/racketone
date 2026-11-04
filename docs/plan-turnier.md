# Plan: Turniermodus (Turnierleitung, Americano, Tabelle), Bezahlstufen ab einer Größe

> Status: Entwurf 2026-11-04 · Bezug: [PLAN](PLAN.md) §1 (Freemium), P2, „Danach“, F22, F27 ·
> [p2-plan](p2-plan.md) P2-M, §5.6, §5.8, G11 · [03](03-backend-datenmodell.md) A15/A18, §1.7, §3.3.6 ·
> [04](04-ux-design.md) §3.3 (mehrere Matches), §7.3 (Paywall) · [plan-live-mitspieler](plan-live-mitspieler.md) ·
> Abrechnung und Entitlements: [plan-pro](plan-pro.md) (gilt für alles Gemeinsame, Pass-Lebenszyklus plan-pro §4.3.1)
> · Überarbeitet nach Review (Fable, Astra): Pass-Lebenszyklus aus plan-pro, Americano als Entscheidung TE1,
> Löschpfad `tournament_entries`, „Ergebnis eintragen“, Datenschutzfassung für T1, USt AT

## 1. Ziel und Nutzer

**Nutzerwunsch 04.11.2026:** Den Turniermodus planen, potenziell als Bezahlfunktion ab einer gewissen Größe.

| Nutzer | Typische Größe | Was er braucht |
|---|---|---|
| Freundesturnier | 1–2 Plätze, 4–8 Personen, 1–3 h | Paarungen ohne Zettel, Tabelle am Ende, Ergebnis teilen |
| Vereinsabend / Clubabend | 2–4 Plätze, 8–16 Personen | Spielplan je Runde, mehrere Zähler, Überblick über alle Plätze |
| Kleiner Club, Vereinsmeisterschaft light | 4–8 Plätze, 16–40 Personen, 1–2 Tage | Helfer, Live-Übersicht, Ergebnisse für alle Teilnehmer |
| Padel Americano / Mexicano | 2–4 Plätze, 8–16 Personen, Partner wechseln je Runde | Rotation, Punkte je Person, Rangliste |

**Passt das zum Plan?** Ja, mit klarer Abgrenzung:

- **F22/F27 (entschieden 15.10.2026)** verlangen einen Turnierdirektor-Modus schon in P2: Plätze, Spielplan,
  Zähler-Zuweisung, Live-Übersicht, ohne Auslosung und Brackets. Das ist offenes Paket **P2-M**. Der Server hat das
  Grundgerüst, die App nichts (§2).
- **„Danach“ (PLAN §4)** meint **Ligen und organisierte Turniere**: Ausschreibung, Anmeldung, Auslosung, Saison-Ranglisten,
  Startgeld, Verbandsanbindung. Das bleibt draußen.
- **„Americano-Sessions später möglich“ (PLAN §4):** Americano im MVP **widerspricht diesem Stand** und braucht eine
  Nutzerentscheidung (**TE1**). Dafür spricht: Americano ist für Padel der häufigste Anlass für einen Abend mit mehreren
  Plätzen (Marktanalyse [06](06-marktanalyse.md) Z. 124), und der Spielplan ist kleine, reine Logik ohne neues
  Zählformat (§3.2). Ohne Zustimmung enthält T1 nur Round Robin, Americano rückt nach T2.

| Gehört dazu (dieser Plan) | Bleibt draußen |
|---|---|
| Ein Turnier an einem Tag/Wochenende, eine Leitung | Ligen, Saisons, Spieltage über Wochen |
| Teilnehmer: Du, Freunde, Gäste | Offene Anmeldung, Wartelisten, Startgeld |
| Round Robin, Americano, später Mexicano und K.-o. | Gesetzte Auslosung nach Rangliste, Verbandsregeln |
| Tabelle dieses Turniers | Eigenes Rating, vereinsübergreifende Ranglisten |
| Ergebnisse für Teilnehmer, Teilen als Bild | Öffentliche Turnierseite mit Suche, Chat |

## 2. Bestand

### 2.1 Server (Supabase)

| Baustein | Wo | Zustand |
|---|---|---|
| Tabellen `tournaments`, `tournament_courts`, `tournament_staff` (`co_director`/`scorer`) | `…15000200_spieler_matches_events.sql:38-72` | vorhanden; Status `planned/running/finished/cancelled`, `access_epoch` |
| `matches.tournament_id`, `court_id`, `scheduled_at`, Status `scheduled` | ebd. `:84-90` | vorhanden |
| RLS-Helfer `is_tournament_staff`, `is_tournament_director` | `…15000300:46-58`, gehärtet in `…22000200:276-291` (Leitung in Löschung → kein Zugriff) | vorhanden |
| Policies: Leitung schreibt Turnier/Plätze direkt, Staff liest | `…15000300:300-317`, Spaltenrechte `…16000200:36-41` | vorhanden; direkte Tabellen-Writes, abweichend von L11 „nur RPCs“ |
| Turnier-Matches anlegen | `sync_push` mit `tournamentId`/`courtId` (`…15000500:218-241`, aktuell `…27000500:34-57`), nur Leitung, sonst `tournament_forbidden`/`court_invalid` | vorhanden |
| Rechte im Match | Turnierleitung (inkl. Co-Leitung) darf Events hochladen, Projektion schreiben, übernehmen, Zähler einladen (`can_submit_events`, `can_take_over` `…02000300:6-15`, `invite_scorer`) | vorhanden |
| Helfer verwalten | `add_tournament_staff` (nur Freunde, nicht blockiert, Rate-Limit `…16000000:326`), `remove_tournament_staff` | vorhanden |
| Live-Übersicht | `tournament_overview(id)` (`…15000900`): Plätze, Matches mit `visitor_view`, Zähler | vorhanden, aber **kein `cloud_*_v1`-Wrapper**; Bindungsprüfung wurde per Migration eingespritzt (`…18000100:283-289`) |
| Realtime-Kanal `tournament:<id>:<epoch>` | Trigger + `rotate` beim Entfernen eines Helfers | seit U1 **abgeschaltet** (`…18000100`; Test 090 Z. 54, 87, 106) |
| Kontolöschung | Turniere der Leitung kaskadieren, Turnier-Matches verlieren `tournament_id` (`…22000200:403-411`), Staff verliert Zugriff | vorhanden |
| Export | `export_my_data` enthält **keine** Turniere | fehlt |
| Entitlements | `public.entitlements (profile_id, entitlement, source, product_id, expires_at)`, nur lesbar, Schreiben nur Service-Rolle | Tabelle leer, kein Webhook |
| pgTAP | `090_turnier_sync_realtime.test.sql` (29 Prüfungen): Anlegen, Fremde abgewiesen, Helfer nur Freunde, Helfer liest, Zuweisung + Annahme, Übersicht zeigt Stand, Entfernen erhöht Kanalversion | grün |

### 2.2 Sync und Client

| Baustein | Wo | Zustand |
|---|---|---|
| Wire-DTOs mit `tournamentId`, `courtId`, `scheduledAt` | `shared/cloud-sync/.../Wire.kt:126-129, 213-215` | Felder da, App sendet sie nie |
| Endgültige Ablehnung `tournament_forbidden`, `court_invalid` | `OutboxPlanner.kt:42`, `CloudUpload.sq:14` (0.6.3) | vorhanden |
| `LiveChannelPort.tournament()` | `Ports.kt:217-222` | nur Schnittstelle, keine Umsetzung (Realtime aus) |
| Lokale Datenbank | `shared/data` | **keine** Turnier-Tabellen, Match ohne Platz/Turnier |
| Mehrere Matches parallel am Handy, Director-Modus | `SessionMatchStore.openMatches` (F22, W3-M) | vorhanden, Basis für „Leitung zählt mehrere Plätze“ |
| Zählen weitergeben an anderes Konto | Live-Mitspieler PR 8–11 (`invite_scorer`, `handOverScoring`) | vorhanden, Basis für „Zähler je Platz“ |
| Live-Abfrage statt Realtime | `cloud_my_live_matches_v1` (5-s-Abfrage, E1); Turnierleitung ohne Rolle bewusst ausgenommen | vorhanden |
| Ergebnis nachtragen/korrigieren | Engine-Event `ResultCorrected` (`Events.kt:333`), `ResultCorrection.kt`, Ergebnis `DRAW` möglich | vorhanden, Basis für „Ergebnis eintragen“ |
| Ergebnis als Bild teilen | `result_share_confirm` u. a. in `ui-phone` | vorhanden, Vorlage für „Tabelle teilen“ |
| Uhr zeigt fremde Matches | `LiveViewSubscription`/`LiveViews` (≤ 5 Matches, ≤ 2 KB) | vorhanden, Vorlage für die Uhr der Leitung |
| `shared/entitlements` | `EntitlementsInfo.kt` | nur Platzhalter, keine Logik |
| UI-Strings | `ui-phone`, Android, Wear, watchApp | **kein** String mit „Turnier“/„tournament“ |
| iOS | – | kein Konto, keine Cloud; Turnier zunächst nur Android + Wear OS |

### 2.3 Was fehlt

1. Lokales Turnier-Datenmodell und Sync (Turnier, Plätze, Teilnehmerliste, Runden).
2. Spielplan-Logik (Round Robin, Americano, Mexicano, K.-o.) und Tabelle, rein und deterministisch.
3. Teilnehmerliste im Server (`tournament_entries` o. ä.); heute gibt es nur Match-Teilnehmer.
4. Start eines **geplanten** Matches auf dem Zähler-Gerät (Kopf ohne Events → Genesis mit vorhandener Match-ID).
5. `cloud_*_v1`-RPCs für Turnier-Kopf, Übersicht, Teilnehmeransicht; Übersicht per Abfrage statt Realtime.
6. Alle Screens, Strings DE/EN, Export, Datenschutzerklärung.
7. Größenstufen und Kaufprüfung (auf Basis von plan-pro).

## 3. Umfang in Stufen

### 3.1 Übersicht

| Baustein | T1 = MVP | T2 | T3 | Später |
|---|---|---|---|---|
| Turnier anlegen (Name, Sport, Regeln aus Presets), Plätze | ✅ | | | |
| Teilnehmer: Du, Freunde, Gäste (Schnelleingabe) | ✅ | Beitritt per Turnier-QR für Nicht-Freunde | | |
| Formate | Round Robin (Einzel, feste Doppel), Americano (wechselnde Partner, Doppel) **nur bei TE1 = ja** | Mexicano, Americano „auf Punkte“ (neues Zählformat) | K.-o. (einfach, ohne Setzliste) | Gruppen + K.-o., Trostrunde |
| Rundenbetrieb | Runde für Runde, Freilos bei ungerader Zahl | Zeitlimit je Runde mit Hinweis | | |
| Zähler je Platz | dieses Handy (parallel), eigene Uhr (eine), Freund/Helfer per vorhandener Übergabe, „Ergebnis eintragen“ | Teilnehmer zählt eigenes Match auf eigener Uhr ohne Freundschaft (QR) | | |
| Live-Übersicht für die Leitung | ✅ (lokal + 5-s-Abfrage) | | | Realtime |
| Tabelle/Rangliste | ✅ | | | |
| Ergebnisse für Teilnehmer | über Verlauf (F23) + Bild teilen | Turnieransicht in der App (Tabelle, nächstes Match) | | |
| Zuschauer-/Teilen-Ansicht | Bild teilen | Zuschauer je Match (vorhanden) | öffentlicher Link (Opt-in) | |
| Uhr | Zähler-Uhr zählt wie heute, Kopf „Platz 2 · R3“ | Uhr der Leitung: Platzliste (≤ 5) | Teilnehmer-Uhr: „Dein nächstes Match“ | |
| Bezahlstufen | Grenzen sichtbar, im Pilot alles frei | Bezahlung aktiv (mit plan-pro) | Club-Abo nach Bedarf | |

### 3.2 Empfehlung MVP (T1)

**„Turnierabend am Handy der Leitung“.** Alles läuft lokal auf dem Handy der Leitung und funktioniert ohne Netz.
Die Cloud repliziert und verteilt, sie ist für den Ablauf nicht nötig.

1. **Anlegen:** Sport, Format (Round Robin oder Americano), Regeln aus den vorhandenen Presets (z. B. Padel 1 Satz bis 6,
   Short Set, Match-Tiebreak), Plätze (Anzahl + Namen), Teilnehmer.
2. **Spielplan:** Das Handy erzeugt Runde 1 sofort und jede weitere Runde auf Knopfdruck. Bei Americano wechselt der
   Partner, jeder spielt möglichst mit jedem einmal. Bei ungerader Zahl gibt es Freilose, gleichmäßig verteilt.
3. **Zählen je Platz** mit den vorhandenen Wegen:
   - dieses Handy als Director (mehrere Plätze parallel, F22),
   - die eigene Uhr (höchstens ein Platz),
   - ein Freund oder Helfer mit Konto über `invite_scorer` und die vorhandene Übergabe,
   - **„Ergebnis eintragen“** ohne Zählen, wenn niemand zählt. `ResultCorrected` wirkt nur bei `FINISHED`
     (`Core.kt`, `onResultCorrected`). Ablauf deshalb: Genesis → `MatchEnded(STOPPED)` (beendet, Ergebnis gilt) →
     `ResultCorrected(sets)`. Die Engine setzt dann `COMPLETED`, `DRAW` bzw. `STOPPED` „wie es steht“. **Nie**
     `MatchEnded(ABANDONED)`: `ResultCorrection` behielte den Abbruch bei, und das Match zählte nach F21 nicht.
4. **Americano „auf Zeit“ oder „auf Games“** mit vorhandenen Formaten: Der Zähler beendet mit „Beenden – Ergebnis gilt“
   (F21, `STOPPED`, Gleichstand = `DRAW`). Rangliste nach gewonnenen Games bzw. Punkten je Person. Ein neues
   Zählformat „bis 24/32 Punkte gesamt“ kommt erst in T2 (Engine-Änderung mit Testvektoren).
5. **Live-Übersicht:** Karte je Platz mit Paarung, Stand, Zähler, „Stand vor X s“. Eigene Matches lokal, fremd gezählte
   per Abfrage alle 5 s.
6. **Tabelle** live aus den Ergebnissen; am Ende „Tabelle teilen“ als Bild.
7. **Freunde** sehen ihre Turnier-Matches wie jedes Match sofort im Verlauf (F23, „War ich nicht“).

**Nicht im MVP:** K.-o., Mexicano, Teilnehmer-Turnieransicht, Turnier-QR, öffentlicher Link, Uhr-Übersicht, iOS.
Grund: Jedes davon braucht neue Sichtregeln oder Engine-Arbeit. Der MVP braucht nur neue Logik auf dem Handy der Leitung
und kleine Server-Erweiterungen.

**Abweichung von F27:** F27 schließt Auslosung und Brackets aus. Americano und Round Robin sind keine Brackets, nur ein
Rotationsplan. K.-o. in T3 braucht eine neue Entscheidung (TE1).

## 4. Monetarisierung

### 4.1 Grundsätze

- **Zählen bleibt immer frei**, auch im Turnier (PLAN §1). Gesperrt wird nur die **Größe** eines Turniers.
- **Es zahlt nur die Turnierleitung.** Teilnehmer, Helfer, Zähler und Zuschauer zahlen nie und sehen keine Paywall.
- **Nie mitten im Turnier sperren** (§4.6). Die Paywall erscheint nur beim Anlegen oder Vergrößern, nie im Match und nie
  auf der Uhr (04 §7.3).
- **Gemeinsame Architektur aus [plan-pro](plan-pro.md):** Kauf über Google Play Billing (später StoreKit), Prüfung des
  Kaufs im Server, Eintrag in `entitlements` bzw. das Kaufjournal von plan-pro, `EntitlementProvider` in
  `shared/entitlements` mit Cache für offline. Dieser Plan definiert nur die Turnier-Produkte, Grenzen und Prüfungen.

### 4.2 Kostenlose Grenzen

| Größe | Frei | Begründung |
|---|---|---|
| Plätze je Turnier | **2** | Freundesturnier und kleiner Americano (8 Personen auf 2 Plätzen) bleiben frei. Ab 3 Plätzen beginnt der Vereinsabend, dort hilft die Live-Übersicht wirklich |
| Teilnehmer je Turnier | **8** | 8 ist die klassische Americano-Größe und die Obergrenze einer Freundesrunde. Wettbewerber setzen ähnlich an (§4.7) |
| Laufende Turniere gleichzeitig | **1** | Verhindert ein „Dauerturnier“ als Club-Ersatz; geplante und beendete Turniere zählen nicht |
| Matches, Runden, Dauer | **unbegrenzt** | Eine Grenze hier würde ein laufendes Turnier sperren (§4.6) |
| Helfer/Zähler | **unbegrenzt** | Zähler sind Kern der Zuverlässigkeit und immer frei |

**Bezahlte Größe „L“:** bis **8 Plätze** und **48 Teilnehmer**. Das deckt Clubturniere ab und hält die 5-s-Abfrage der
Übersicht klein. Technische Obergrenze für alle Stufen: **16 Plätze, 96 Teilnehmer** (nur mit Club-Abo, falls es kommt).

### 4.3 Optionen über der Grenze

| Option | Preis (Vorschlag) | Für wen | Pro | Contra |
|---|---|---|---|---|
| **A. In Pro enthalten** | Pro wie PLAN §1 (3,99 €/Monat, 29,99 €/Jahr) | Spieler, die öfter organisieren | kein neues Produkt, eine Paywall | Wer nur einmal organisiert, schließt kein Abo ab |
| **B. Turnier-Pass** (Verbrauchsartikel je Turnier) | **2,99 €** je Turnier | Einmal-Organisator, Vereinsabend | fairer Preis je Anlass, kein Abo | eigener Kaufablauf, Bindung an eine Turnier-ID |
| **C. Club-Abo** | z. B. 9,99 €/Monat oder 79,99 €/Jahr | Club mit wöchentlichem Betrieb | höhere Erlöse, Stufe XL | Store-Abos gelten je Konto, nicht je Verein; Nachfrage unbekannt |

**Empfehlung: A + B, C erst nach Daten.**
- Pro enthält Größe L für alle eigenen Turniere. Der Pass gibt Größe L für **ein** Turnier.
- Der Pass kostet weniger als ein Monat Pro. Wer zweimal im Monat organisiert, fährt mit Pro besser.
- Ein Club-Abo lohnt sich erst, wenn Leitungen regelmäßig an die Grenze L stoßen. Ein „Vereinskonto“ mit mehreren
  Zahlern gibt es in beiden Stores nicht als Standard. 3.1.3(c) (Enterprise) gilt nur für Verkäufe außerhalb des Stores
  direkt an Organisationen für deren Mitglieder und passt nicht zu Advantly.
- Netto nach Store-Anteil (§4.4): Pass 2,99 € brutto → in AT ca. 2,49 € ohne 20 % USt (DE 19 %: 2,51 €) → nach 15 %
  Google-Anteil (erste 1 Mio. USD Jahreserlös) ca. 2,12 €. Ohne diese Stufe (20/25 % + 5 %) ca. 1,87–1,99 €.

### 4.4 Store-Regeln (Stand November 2026)

| Thema | Google Play | Apple App Store |
|---|---|---|
| Pflicht | Bezahlung für Funktionen in der App muss über Play Billing laufen [G1] | „unlock features or functionality … you must use in-app purchase“ (3.1.1) [A1] |
| Ausnahmen | physische Waren/Dienste (z. B. Platzmiete, Startgeld, Tickets) dürfen **nicht** über Play Billing laufen [G1] | physische Waren/Dienste außerhalb der App ohne IAP (3.1.3(e)); Enterprise-Verkauf (3.1.3(c)) [A1] |
| Folge für Advantly | Pass und Pro sind digitale Funktionen → Play Billing. Ein Startgeld der Teilnehmer wäre ein realer Dienst und gehört nicht in die App (ausgeschlossen, §1) | dto. StoreKit. Kauf im Web und Nutzung in der App nur, wenn auch per IAP angeboten (3.1.3(b)) |
| Alternativen EU/DMA | alternative Abrechnung oder Link nach außen in berechtigten Ländern (u. a. EWR, UK, USA) nur mit Programm-Anmeldung [G1][G2] | seit 01.10.2026 EU: IAP 26 % (15 % Small Business), eigene Zahlung 20 % (10 %), Link nach außen 15 % (10 %); Wahl gilt 12 Monate; Elternschranke unter 18, kein Link unter 13 [A2] |
| Gebühren | ab 30.06.2026 in EWR/UK/USA: Abos 10 % + 5 % Abrechnungsgebühr; Einmalkäufe 20 % (neue Installation) bzw. 25 % (bestehende) + 5 %; **erste 1 Mio. USD Jahreserlös für alle Arten 10 % + 5 %** [G2]; übrige Länder [G3] | Small Business Program 15 % |
| Umsatzsteuer | Google ermittelt, berechnet und führt die USt für EU-Kunden ab [G4] | Apple rechnet als Kommissionär ebenfalls selbst ab (vor Apple-Start im Vertrag prüfen, nicht verifiziert) |
| Technik | Play Billing Library ≥ 8 für Updates ab 31.08.2026 [G5]; Verbrauchsartikel nach Gutschrift „consume“, sonst Rückerstattung nach 3 Tagen | Verbrauchsartikel brauchen kein „Wiederherstellen“, der Anspruch liegt im Server |

**Empfehlung:** nur Play Billing (später StoreKit), keine alternative Abrechnung. Die Ersparnis von 5 Prozentpunkten
rechtfertigt keinen eigenen Zahlungsdienst, keine eigene USt-Abwicklung (OSS) und keine Programm-Pflichten. Details und
Kontoeinrichtung gehören zu plan-pro.

### 4.5 Turnierspezifische Produkte und Prüfungen

| Produkt-ID (Vorschlag) | Art | Wirkung |
|---|---|---|
| `tournament_pass_l` | Play: Einmalprodukt, verbrauchbar; Apple: Consumable | Turnier X bekommt Stufe L (Zeile in `tournament_licenses`, `source = 'pass'`), bis es endet bzw. höchstens 7 Tage nach Start |
| Entitlement `pro` (aus plan-pro) | Abo | alle Turniere der Leitung, die sie während aktivem Pro **startet**, bekommen Stufe L (`source = 'pro'`, beim Start geschrieben) |
| `club` (nur Option C) | Abo | Stufe XL; später |

**Ablauf Pass:** ausschließlich nach dem Zustandsautomaten in plan-pro §4.3.1 (Server bindet und verbraucht,
Idempotenz über `purchase_token_hash`, nicht gebundener Kauf nach plan-pro E18). Turnierseitig gilt nur: Die App startet
den Kauf beim Anlegen oder Vergrößern und sendet Token + Turnier-ID; die Stufe steht danach in `tournament_licenses`.

**Prüfungen im Server:**
- Nur die Leitung (`director_id`) kann ein Turnier hochstufen. Co-Leitung nicht (TE6).
- Ein Pass gilt für genau ein Turnier (plan-pro §4.3.1).
- Rückerstattung: Ein **laufendes oder beendetes** Turnier behält seine Stufe. Ein noch geplantes fällt auf „frei“
  zurück; Plätze und Teilnehmer über der Grenze bleiben gespeichert, das Starten verlangt dann die Verkleinerung oder
  einen neuen Kauf.
- Grenzen prüft der Server bei **Online-Aktionen** (Platz/Teilnehmer hinzufügen, Turnier starten). Er lehnt **nie**
  hochgeladene Events, Matches oder Ergebnisse wegen der Größe ab. Ein offline angelegtes Turnier über der Grenze wird
  angenommen; die Überschreitung berechnet der Server aus `private.tournament_capacity` (keine eigene Spalte), das
  nächste Turnier der Leitung verlangt dann die Klärung.

### 4.6 Grenze überschritten, Ablauf, laufende Turniere

| Situation | Verhalten |
|---|---|
| Anlegen: 3. Platz oder 9. Teilnehmer | Hinweis „Kostenlos bis 2 Plätze und 8 Teilnehmer“ mit Pass, Pro, „Kleiner bleiben“ |
| Turnier läuft, Leitung fügt Nachzügler über der Grenze hinzu | Hinweis + Kaufangebot; Turnier läuft unverändert weiter |
| Pro läuft während eines laufenden Turniers ab | **keine Wirkung.** Die Stufe wird beim Start festgeschrieben (`tournament_licenses.locked_at`) und sinkt nie |
| Turnier beendet | dauerhaft lesbar, Tabelle teilbar, Export frei |
| Pro abgelaufen, neues Turnier | wieder Grenzen „frei“; alte Turniere bleiben vollständig |
| Offline, Cache abgelaufen | App nimmt den letzten bekannten Anspruch (Kulanzfrist aus plan-pro); im Zweifel **zulassen** |
| Pilot vor plan-pro-Start | Grenzen sichtbar als Hinweis, alles frei („Im Pilot kostenlos“), Server-Schalter `private.tournament_limits_enforced() = false` |

Nie gesperrt: Zählen, Undo, Übergabe, Ergebnis eintragen, nächste Runde, Übersicht, Tabelle, Turnier beenden.

### 4.7 Markt (Stichprobe, abgerufen 04.11.2026)

| App | Frei | Bezahlt |
|---|---|---|
| Americano Padel [M1] | ein Platz | 4,99 $/Monat, 24,99 $/Jahr, 79,99 $ einmalig |
| MaxPadel [M2] | bis 8 Spieler, 2 Plätze | 4,99 $/Monat, 35,99 $/Jahr, 199,99 $ einmalig |
| ScoreSay (06) | Werbung | Turnier-Abo, Betrag unbekannt |

Advantly liegt mit 2 Plätzen/8 Teilnehmern gleichauf, bietet aber Zählen, Uhr und Live-Übersicht dazu. Der Pass ist
eine Lücke: Kein geprüfter Wettbewerber verkauft ein einzelnes Turnier.

## 5. Datenmodell, RLS, Sync

### 5.1 Grundsatz: lokal zuerst

Wie Matches entsteht ein Turnier **auf dem Handy der Leitung** (UUIDv7) und wird über die Outbox hochgeladen. Spielplan
und Tabelle rechnet der Client (eine Logik, wie Projektionen, L6). Der Server speichert, prüft Rechte und verteilt.

**Neues reines Modul `shared/tournament`** (→ `core-scoring`): Paarungen (Round Robin nach Kreismethode, Americano-
Rotation, später Mexicano/K.-o.), Freilose, Tabelle mit festen Tiebreaks. Regeln wie Pure-Module: keine Uhr, kein Zufall,
Startwert für die Auslosung kommt als Parameter. Abhängigkeiten: `match-session`/`presentation` → `tournament`; nicht in
`umbrella-watch`. Eintrag in `settings.gradle.kts` und in die Modultabelle von `CLAUDE.md` (Teil des Implementierungs-PRs).

### 5.2 Erweiterungen des Grundgerüsts

| Änderung | Inhalt |
|---|---|
| `tournaments` + Spalten | `format` (`round_robin`/`americano`/`mexicano`/`knockout`), `discipline`, `match_format jsonb` (Preset), `seed bigint`, `deleted_at`. **Keine `tier*`-Spalten:** die Stufe steht nur in `tournament_licenses` (plan-pro §4.2) |
| neu `tournament_entries` | `id`, `tournament_id`, `display_name` (1–40), `player_id` → `players` (Gast der Leitung) **oder** `profile_id` (Freund), `team_key` (feste Doppel), `withdrawn_at`, `sort_order`; Unique (Turnier, Profil) |
| neu `tournament_rounds` | `tournament_id`, `round_no`, `status` (`planned`/`running`/`done`), `created_at`; Matches tragen `meta.round` bzw. neue Spalte `round_no` |
| `matches` | Teilnehmer eines Turnier-Matches verweisen auf `tournament_entries` (`entry_id` in `match_participants`), damit die Tabelle Personen über Runden und Partnerwechsel zuordnet |
| Schreiben nur per RPC | `cloud_tournament_push_v1(batch)` für Kopf, Plätze, Teilnehmer, Runden (idempotent, LWW je Zeile wie Match-Kopf). Direkte Tabellen-Grants für `tournaments`/`tournament_courts` entfallen (L11) |
| Lesen | `cloud_tournament_overview_v1(id)` (Leitung/Staff, Abfrage alle 5 s), `cloud_tournament_view_v1(id)` (Teilnehmer, T2), `sync_pull` liefert eigene Turniere als neue Sektion |
| Grenzen | `private.tournament_capacity(id)` aus `tournament_licenses` (ohne Zeile: frei); Prüfung in `cloud_tournament_push_v1` nur bei Hinzufügen und `planned → running` (§4.5) |
| Kauf | `tournament_licenses` schreibt nur die Service-Rolle aus dem Prüf-Endpunkt (plan-pro §4.3.1) |
| Export | `export_my_data`: eigene Turniere (als Leitung) mit Plätzen, Teilnehmernamen, Runden; eigene Teilnahmen als Verweis |
| Löschung | Turnier löschen = Tombstone; Matches behalten ihre Teilnehmer-Verknüpfungen (Löschen = nur eigene Sicht). Kontolöschung: Turniere der Leitung wie heute. **`tournament_entries.display_name` muss in den Lösch- und Redaktionspfad** (Phase A/B, `purge_account`, `capture_profile_redactions`, `redact_linked_names`): Einträge mit `profile_id` des gelöschten Kontos → „Gelöschter Spieler“; Gast-Einträge folgen [0.6.5](plan-0.6.4.md). pgTAP-Fall analog 470 |

### 5.3 Wer darf was

| Aktion | Leitung | Co-Leitung | Helfer (`scorer`) | Zähler eines Matches (Rolle `accepted`) | Teilnehmer mit Konto | Zuschauer | Fremde |
|---|---|---|---|---|---|---|---|
| Turnier anlegen, löschen, hochstufen | ✅ | – | – | – | – | – | – |
| Plätze, Teilnehmer, Runden ändern | ✅ | ✅ | – | – | – | – | – |
| Helfer verwalten | ✅ | – | sich selbst entfernen | – | – | – | – |
| Zähler einladen (`invite_scorer`) | ✅ | ✅ | – | – | – | – | – |
| Match zählen, Events hochladen | ✅ | ✅ | nach Annahme | ✅ | nur mit Rolle | – | – |
| Ergebnis eintragen/korrigieren | ✅ | ✅ | – | ✅ (eigenes Match) | – | – | – |
| Live-Übersicht aller Plätze | ✅ | ✅ | ✅ | – | – | – | – |
| Turnieransicht (Tabelle, Paarungen, Namen) | ✅ | ✅ | ✅ | ✅ | ✅ (T2) | – | – |
| Live-Stand eines Matches | ✅ | ✅ | ✅ | ✅ | eigene Matches | benannte Matches (F25, max. 10) | – |
| Puls/Telemetrie anderer | – | – | – | – | – | – | – |

Bestehende Regeln bleiben: Helfer sind Freunde der Leitung (F27), Visitor nie Mitglied, Entfernen eines Helfers erhöht
`access_epoch` und `visibility_epoch`. T2 erweitert um **Turnier-QR**: Wer den QR scannt, wird Teilnehmer (beansprucht einen
Eintrag) oder Helfer, beschränkt auf dieses Turnier und bis 7 Tage nach Ende; das ist eine bewusste Lockerung von F27 (TE2).

### 5.4 Offline

| Fall | Verhalten |
|---|---|
| Kein Netz in der Halle | Handy der Leitung führt Turnier, Spielplan, Tabelle und alle selbst gezählten Plätze vollständig offline; Upload später |
| Platz zählt auf Helfer-Gerät | Match muss einmal online beim Helfer angekommen sein (Annahme der Rolle). Danach zählt er offline; Events kommen später. Die Übersicht zeigt „Stand vor X min“ |
| Helfer-Gerät nie erreicht | Leitung zählt selbst („Hier zählen“, Übernahme durch Turnierleitung ist erlaubt) oder trägt das Ergebnis ein |
| Ergebnis offline von zwei Seiten | Engine-Ordnung entscheidet (letztes gültiges `ResultCorrected`/Single-Scorer); Tabelle rechnet neu |
| Neue Runde braucht Ergebnisse (Mexicano, T2) | Runde erst erzeugen, wenn alle Ergebnisse der Runde lokal sind; sonst „Ergebnis eintragen“ anbieten |
| Kauf offline | nicht möglich; Turnier bleibt in der freien Größe planbar, Start mit Cache-Anspruch (§4.6) |

## 6. Datenschutz

| Neuer Datenfluss | Bewertung | Maßnahme |
|---|---|---|
| Gastnamen, die die Leitung eingibt | wie heutige Gäste (F18); Leitung ist verantwortlich | Hinweis im Assistenten: „Vorname oder Spitzname reicht“ |
| Namen und Ergebnisse für alle Teilnehmer/Helfer, auch Nicht-Freunde | neu: heute sehen Nicht-Freunde nur Namen im selben Match | Teilnehmer mit Konto sehen Alias bzw. Turniername; Hinweis beim Beitritt; „Aus Turnier austreten“ ersetzt Namen durch den Gastnamen der Leitung bzw. „Spieler N“ |
| Helfer sehen alle Turnier-Matches | besteht heute schon (`is_match_member`) | keine Telemetrie, nur `visitor_view`-Felder in der Übersicht |
| Kaufdaten (Bestell-ID, Produkt, Zeit, Turnier-ID) | neu, über plan-pro | Zweck „Vertragserfüllung“; Aufbewahrung laut plan-pro |
| Öffentlicher Link (T3) | neu, für jeden mit Link | nur Opt-in je Turnier, nur Tabelle + Paarungen, keine Konten-IDs, Widerruf jederzeit, `noindex` |

- **Gäste ohne Konto** können ihre Rechte nicht in der App ausüben. Weg: Support-Meldung; Leitung kann Namen ändern
  oder löschen.
- **Export:** Turniere der Leitung und eigene Teilnahmen (§5.2). **Löschung:** §5.2.
- **Datenschutzerklärung:** neuer Abschnitt „Turniere“ (`site/app-datenschutz.html` + `site/en/app-privacy.html`):
  Teilnehmerliste im Server (`tournament_entries`), Sichtbarkeit von Namen/Ergebnissen im Turnier, Helfer, Tabelle,
  Löschung; Kaufdaten erst mit plan-pro. **T1 braucht sicher eine neue Fassung**, alle Konten bestätigen neu. Sie wird
  mit dem T1-Release eingetragen, nicht mit plan-pro (Turnier kommt vorher; Bündelung siehe `plan-reste-0.6.x.md` §2.1).
- **Play-Datensicherheit:** „Finanzdaten → Kaufverlauf“ neu (mit plan-pro). Namen (vom Nutzer eingegeben) und
  App-Interaktionen sind schon deklariert; prüfen, dass „mit anderen Nutzern geteilt“ auch Turnier-Teilnehmer abdeckt.
- **Apple Privacy Label:** mit iOS-Konto nachziehen.

## 7. UX-Skizze

**Handy, Leitung**
```
Start ── Karte „Turnier“ (neben „Match starten“)
  └─ Turnierliste: Läuft · Geplant · Beendet
       └─ Assistent: 1 Sport + Format → 2 Teilnehmer (Du, Freunde, „+ Gast“, Zähler „8/8 kostenlos“)
                     → 3 Plätze (Anzahl, Namen) → 4 Regeln (Preset; Americano: „auf Games“/„auf Zeit 15 min“)
                     → 5 Zusammenfassung „Turnier starten“
            └─ Turnier-Cockpit (Tabs)
                 Plätze:  Karte je Platz – Paarung, Stand, Zähler · „Stand vor 12 s“
                          Aktionen: Hier zählen · Zähler zuweisen · Ergebnis eintragen
                 Runden:  Runde 1 ✓ · Runde 2 läuft (3/4 fertig) · „Nächste Runde“ (aktiv, wenn alle fertig;
                          vorher mit Rückfrage) · Freilos-Hinweis
                 Tabelle: Rang, Name, Spiele, Siege, Games/Punkte (+/-) · „Teilen“
                 Mehr:    Helfer, Turnier beenden, Größe/Pass
```
- „Hier zählen“ öffnet den vorhandenen Live-Screen; die Chip-Leiste (F22) zeigt die Plätze als „P1 · P2 · P3“.
- Paywall nur im Assistenten oder unter „Mehr → Größe“, als Sheet nach 04 §7.3, mit „Kleiner bleiben“.

**Handy, Teilnehmer (T2)**
- Bereich „Live“ zeigt eine Turnier-Karte: „Dein nächstes Match: Platz 2, Runde 3, mit Anna gegen …“.
- Turnieransicht: Tabelle, eigene Ergebnisse, Paarungen der aktuellen Runde. Keine Bearbeitung.

**Uhr**
- MVP: Die Zähler-Uhr zählt ein Turnier-Match wie jedes Match. Der Kopf zeigt „Platz 2 · R3“. Nach dem Ende kehrt sie
  zur Startliste zurück.
- T2: Uhr der Leitung zeigt bis zu 5 Plätze über `LiveViews` (vorhandener Seitenkanal, ohne Telemetrie).
- T3: Teilnehmer-Uhr bekommt „Dein nächstes Match“ über die gespiegelte Push-Nachricht.
- Keine Paywall und keine Turnierverwaltung auf der Uhr.

Alle Texte DE + EN (`ui-phone` composeResources, Wear `res/values*`), `StringResourcesParityTest` und Lint decken das ab.

## 8. Tests, Phasen, Aufwand, Risiken

### 8.1 Tests

| Ebene | Was |
|---|---|
| `shared/tournament` (jvmTest, Kotest Property, fester Seed) | Round Robin: jeder gegen jeden genau einmal; Americano: kein Partner doppelt, solange möglich; Freilose höchstens 1 Unterschied; gleicher Seed → gleicher Plan; Tabelle mit Gleichständen (Regel-Tabelle) |
| `shared/data` / `match-session` | Turnier offline anlegen, zählen, App-Kill, Upload ohne Doppelungen; geplantes Match auf Zähler-Gerät starten (Genesis mit vorhandener ID) |
| `presentation` | ViewModel-Tests Assistent, Cockpit, Grenzen-Hinweis, „nie mitten im Turnier sperren“ |
| pgTAP (neue Datei, z. B. `480_turnier_teilnehmer.test.sql`) | Rechte-Matrix §5.3, Fremde/Visitor ohne Zugriff, Grenzen nur bei Hinzufügen/Start, Upload über Grenze nie abgelehnt, `tournament_licenses` nur Service-Rolle, Export enthält Turniere, Kontolöschung (Leitung/Teilnehmer) mit `tournament_entries` redigiert (analog 470), gleicher Endzustand Job-Pfad und `purge_account`, keine Telemetrieschlüssel |
| Engine/`match-session` | „Ergebnis eintragen“: `MatchEnded(STOPPED)` + `ResultCorrected` ergibt `COMPLETED`/`DRAW` und zählt in der Tabelle; kein `ABANDONED` |
| Kauf (mit plan-pro) | Übergänge aus plan-pro §4.3.1, Token doppelt, fremdes Konto, Rückerstattung laufend/geplant, Offline-Cache |
| Feldtest | p2-plan G11 erweitert: Americano 8 Personen, 2 Plätze, ein Platz Leitungs-Handy, einer Helfer-Uhr, Flugmodus mitten in Runde 2 |

### 8.2 Phasen

Größen wie p2-plan: S ≤ 2 Tage, M ≤ 1 Woche, L > 1 Woche Agentenarbeit inkl. Review, ohne Feldtests.

| Phase | Inhalt | Abnahme | Größe |
|---|---|---|---|
| **T0** Server | `tournament_entries`, `tournament_rounds`, Spalten, `cloud_tournament_push_v1`, `cloud_tournament_overview_v1`, Export, Grenzen-Schalter aus, pgTAP | Migration auf Staging vor der App | M |
| **T1a** Logik | `shared/tournament`: Round Robin, Americano, Freilose, Tabelle + Property-Tests | grün | M |
| **T1b** Daten/Sync | lokale Tabellen (SQLDelight-Migration), Outbox/Pull, geplantes Match starten | Abbruch-Tests ohne Doppelungen | L |
| **T1c** App | Assistent, Cockpit, Übersicht (5-s-Abfrage), Ergebnis eintragen, Tabelle teilen, Strings DE/EN, Uhr-Kopf | Feldtest §8.1 | L |
| **T2** | Teilnehmeransicht, Turnier-QR, Mexicano, Americano „auf Punkte“ (Engine + 02 + Testvektoren), Uhr-Übersicht, Bezahlung aktiv (mit plan-pro) | Pilot mit echtem Vereinsabend | L |
| **T3** | K.-o., öffentlicher Link, Club-Abo (falls Daten dafür sprechen), iOS mit Konto | eigene Gates | L |

**T1 gesamt etwa 4–6 Wochen.** Reihenfolge: T0 → T1a parallel zu T0 → T1b → T1c. Server vor App ausrollen
(Staging-Regel). T1 schließt P2-M ab und ist mit „alles frei“ pilotfähig, unabhängig von plan-pro.

### 8.3 Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Kein Netz in der Halle | lokal zuerst; Leitung kann jeden Platz selbst zählen oder Ergebnisse eintragen |
| Umfang wächst Richtung Liga/Vereinssoftware | Abgrenzung §1; jede Erweiterung nach T2 braucht eine Entscheidung |
| Helfer müssen Freunde sein (Reibung im Club) | T1 akzeptieren, T2 Turnier-QR (TE2) |
| Paywall schreckt Pilot ab | im Pilot alles frei; Grenzen erst mit plan-pro aktiv |
| Kauf erfolgreich, Bindung scheitert | Zustandsautomat plan-pro §4.3.1: Server verbraucht erst nach Bindung, Wiederholung beim App-Start, sonst E18 |
| Sperre mitten im Turnier durch Ablauf/Rückerstattung | Stufe beim Start festschreiben; Server lehnt Daten nie wegen Größe ab |
| Zweite Bestätigung der Datenschutzerklärung nervt | Bündelung nach `plan-reste-0.6.x.md` §2.1 (Nutzerentscheidung) |
| Americano „auf Punkte“ ändert die Engine | erst T2, mit Testvektoren und Regel-Prüfer |
| Store-Gebühren und Regeln ändern sich 2026/27 schnell | vor Bezahl-Start erneut prüfen (§4.4 Quellen) |
| Apple fehlt | Android + Wear OS zuerst; Produkte in App Store Connect erst mit Developer-Account |

## 9. Offene Entscheidungen

| # | Frage | Empfehlung |
|---|---|---|
| TE1 | Umfang MVP, insbesondere **Americano schon in T1** (Abweichung von PLAN §4 „später“) | **Ja:** Round Robin + Americano (auf Games/Zeit), Leitung zählt oder weist zu, Übersicht, Tabelle, Bild teilen. K.-o. erst T3 und nur mit neuer Entscheidung zu F27 |
| TE2 | Helfer und Teilnehmer ohne Freundschaft | T1 nur Freunde (F27); T2 Turnier-QR, auf das Turnier beschränkt |
| TE3 | Kostenlose Grenzen | 2 Plätze, 8 Teilnehmer, 1 laufendes Turnier; Matches/Runden/Helfer unbegrenzt |
| TE4 | Bezahlmodell | Pass 2,99 € je Turnier (Lebenszyklus plan-pro §4.3.1) + Größe L in Pro enthalten; Club-Abo erst bei Nachfrage |
| TE5 | Ab wann kostet es | mit dem Bezahl-Release aus plan-pro; vorher Grenzen nur als Hinweis |
| TE6 | Wer kann hochstufen | nur die Leitung (Ersteller); Co-Leitung nicht |
| TE7 | Was passiert bei Ablauf/Rückerstattung | Stufe beim Start festschreiben (`locked_at`), laufende Turniere behalten sie, beendete dauerhaft lesbar |
| TE8 | Namen für Teilnehmer, die keine Freunde sind | ja, Alias bzw. Turniername, mit Hinweis beim Beitritt und „Austreten“ (ab T2) |
| TE9 | Öffentlicher Link | T3, nur Opt-in je Turnier, ohne Konten-IDs |
| TE10 | Datenschutzerklärung | Neue Fassung **mit T1** (sicher nötig); Bündelung mit anderen Plänen nach `plan-reste-0.6.x.md` §2.1 |
| TE11 | Americano „bis 24/32 Punkte“ als eigenes Zählformat | T2, als neues Preset mit Testvektoren |
| TE12 | Zeitpunkt | T0/T1 jetzt als Abschluss von P2-M; T2 parallel zu P3 |
| TE13 | Alternative Abrechnung (EU-Programme) | nein, nur Play Billing/StoreKit |

## 10. Quellen (abgerufen 04.11.2026)

- [G1] Google Play Console-Hilfe, Richtlinie „Payments“: https://support.google.com/googleplay/android-developer/answer/9858738
- [G2] Google Play Console-Hilfe, neue Servicegebühren ab 30.06.2026 (EWR/UK/USA): https://support.google.com/googleplay/android-developer/answer/16954621
- [G3] Google Play Console-Hilfe, Servicegebühren übrige Märkte: https://support.google.com/googleplay/android-developer/answer/112622
- [G4] Google Payments Center-Hilfe, Umsatzsteuer EU: https://support.google.com/paymentscenter/answer/7158952
- [G5] Android Developers, Play Billing Library Deprecation FAQ: https://developer.android.com/google/play/billing/deprecation-faq
- [A1] Apple App Review Guidelines 3.1.1, 3.1.2, 3.1.3(b)/(c)/(e): https://developer.apple.com/app-store/review/guidelines/
- [A2] Apple Newsroom, 18.08.2026, Änderungen für Apps in der EU (gültig ab 01.10.2026): https://www.apple.com/newsroom/2026/08/apple-announces-changes-for-apps-in-the-european-union/
- [M1] App Store, Americano Padel: https://apps.apple.com/us/app/americano-padel/id1536633475
- [M2] App Store, MaxPadel: https://apps.apple.com/cd/app/maxpadel/id6759582285
- Ergänzend (Sekundärquellen, nur zur Einordnung): Engadget/TechSpot zum Google-Gebührenmodell 2026
  (https://www.engadget.com/apps/google-ends-is-30-percent-app-store-fee-and-welcomes-third-party-app-stores-185248673.html),
  MacRumors zu Apple EU 2026 (https://macrumors.com/2026/08/18/eu-app-store-fee-change).
