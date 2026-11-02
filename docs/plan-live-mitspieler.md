# Plan: Mitspieler live, Push-Benachrichtigungen, Zählen weitergeben

> Status: Entwurf 2026-11-02 · Bezug: [00 F16/F17/F25](00-entscheidungen.md), [03 §5.7](03-backend-datenmodell.md),
> [p2-plan §5.3/§11](p2-plan.md), Datenschutz-Entscheidung „Pulsdaten nur lokal“ ([plan-puls-highlights §10](plan-puls-highlights.md))

## 1. Ziel (Nutzerwunsch 02.11.2026)

1. **Mitspieler verfolgen ein laufendes Match live**, auf dem eigenen Handy und auf der eigenen Uhr.
2. **Push-Benachrichtigungen**, z. B. „Alex hat dich in ein Match eingetragen“ oder „Alex bittet dich zu zählen“.
   Bisher verschickt Advantly keine Benachrichtigungen; Freunde sehen Matches nur beim Öffnen der App.
3. **Zählen weitergeben**, auch an Geräte eines **anderen Kontos**: Alex zählt auf seiner Uhr und übergibt an
   Jonas' Uhr oder Handy.

Gesundheitsdaten bleiben ausgeschlossen. Zuschauer und Mitspieler sehen nie Puls oder Schritte.

## 2. Was es schon gibt

| Baustein | Wo | Zustand |
|---|---|---|
| Benachrichtigungs-Outbox | `ops.notification_outbox`, `private.notify` (`…15000100:158-180`) | wird befüllt (Freundschaft, `match_shared`, `scorer_invited/responded`, `visitor_invited`, Zuweisungen), aber nie versendet oder geleert; Spalte für den Auslöser (`actor_id`) fehlt |
| Push-Token | `devices.push_token/push_provider` | Spalte vorhanden, kein RPC zum Setzen; Widerruf leert das Token schon |
| Zähler-Rollen (F17) | `match_roles`, `invite_scorer`, `respond_scorer_invitation`, `revoke_scorer` (`…15000600`) | serverseitig fertig, im Client ungenutzt |
| Schreibrecht mehrerer Konten | `private.can_submit_events`, `push_event` (`…15000500:304-421`) | Ersteller, verknüpfte Mitspieler und angenommene Rollen dürfen schon hochladen; `ScorerChanged` nur auf eigene oder per Rolle angenommene Geräte |
| Zuschauer (F16/F25) | `add_match_visitor`, `cloud_get_visitor_match_v1` (auch für Mitglieder), `private.visitor_view` + `sanitize_projection` (Whitelist ohne Telemetrie) | fertig |
| Live-Upload | `LiveVisitorUpload` (`presentation-auth/.../friends/Visitors.kt`, `androidApp/.../PhoneAccount.kt`) | nur bei Matches mit Zuschauern, höchstens alle 30 s; Zuschauer fragen alle 15 s ab, also bis etwa 45 s Verzögerung |
| Realtime | Kanäle und Trigger (`…15000600`) | abgeschaltet (`20261018000100`), weil die Geräte-Bindung über WebSocket nicht prüfbar ist |
| Engine | `ScorerChanged` je `deviceId`, deterministische Ordnung, Fremdeingaben werden verworfen (`EventLog.kt`) | für mehrere Konten **keine Engine-Änderung nötig** |
| Replikate | gezogene Mitglieds-Matches sind lesbar und beschreibbar (`CloudPullStore`, `replica_readable_match`) | Lücke: gezogene Events gehen nicht sofort an die Uhr, ein gezogenes `ScorerChanged` wird nicht mit der Uhr abgeglichen (`MatchSession.kt` `eventsCommitted`) |
| Uhr-Seitenkanal | `LiveTelemetrySubscription` / `LiveTelemetry` (sync-protocol) | passendes Vorbild für „Uhr schaut zu“, ohne Protokollwechsel |
| Edge Functions | `export-my-data`, `cleanup-data-exports` (pg_cron → pg_net → Function mit Secret → claim/ack-RPCs) | Vorlage für den Push-Versand |
| Handy-Manifest | kein `POST_NOTIFICATIONS`, kein Firebase | – |
| iOS | kein Konto, kein Cloud-Abgleich, kein Apple-Developer-Account | alles in diesem Plan zunächst nur Android + Wear OS |

**Verdacht (vor allem anderen prüfen, PR 0):** `SessionMatchStore.resumeRecentMatches` öffnet vermutlich auch
gezogene laufende Matches anderer Konten. Deren Zähler ist nie verbunden, deshalb bietet die Live-Ansicht nach 30 s
„Zählen übernehmen“ an, ebenso „Beenden/Abbrechen“. Beides passiert ohne vollständigen Abgleich, und der Server
nimmt es von Mitspielern an. Das könnte schon heute fremde Matches stören.

## 3. Architektur

### 3.1 Live verfolgen (Handy)
- **Server:** neuer RPC `cloud_my_live_matches_v1()`. Er liefert `visitor_view` für laufende Matches, in denen ich
  Zuschauer oder Mitglied bin, aber nicht Ersteller. Matches, die seit mehr als 12 h nichts Neues haben, fallen weg.
  Es gelten nur die bestehenden Sichtregeln (`is_match_member`/`is_match_visitor`). Blockieren, Entfreunden und
  „War ich nicht“ wirken also automatisch.
- **Zählendes Handy:** lädt jedes laufende Match eines angemeldeten Kontos hoch, den Kopf sofort beim Start und danach
  höchstens alle 10 s, wenn sich etwas geändert hat. Das löst auch `match_shared` gleich zu Beginn aus statt erst am
  Ende.
- **Mitspieler-Handy:** neuer Bereich „Live“ (Ich spiele mit / Ich schaue zu) mit Detailansicht. Solange er sichtbar
  ist, wird alle 5 s abgefragt. Fremd-Matches erscheinen nur als Anzeige, ohne Übernehmen oder Beenden.
- **Zielwert:** Stand beim Mitspieler höchstens 15 s alt (p95, beide online).
- Realtime (unter 2 s) wird zurückgestellt (E1).

### 3.2 Live verfolgen (Uhr, Wear OS zuerst)
- Ohne Event-Replikat und ohne Zählrechte auf der Uhr, nach dem Muster von `LiveTelemetrySubscription`:
  - **Uhr → Handy:** `LiveViewSubscription`, alle 10 s, solange die Ansicht sichtbar und nicht im Ambient-Modus ist.
  - **Handy → Uhr:** `LiveViews` mit höchstens 5 Matches: Namen, Sätze, Games, Punkte, Aufschlag, Status, Alter.
    Höchstens 2 KB, keine Telemetriefelder.
  - Jede Abo-Nachricht löst genau einen Serverabruf aus. Ein dauerhafter Hintergrunddienst ist dafür nicht nötig.
- Auf der Uhr gibt es einen Eintrag „Live“ in der Startliste und eine Zuschau-Ansicht.
- Apple Watch kommt, sobald iOS ein Konto hat.

### 3.3 Push
```
private.notify → ops.notification_outbox (+ actor_id)
  ├─ AFTER-INSERT-Trigger: pg_net → Edge Function push-dispatch (nach dem Commit)
  └─ pg_cron je Minute: Wiederholung
       push-dispatch: Secret prüfen → claim_push_batch_v1 → FCM HTTP v1 → ack_push_batch_v1
```
- **Prüfung beim Versand:** Der Empfänger ist aktiv, sieht das Match noch bzw. ist noch befreundet, der Auslöser
  ist nicht blockiert und nicht der Empfänger selbst.
- **Inhalt:** Die Nachricht enthält nur IDs und die Art (`kind`, `matchId`, `profileId`, `role`). Den Text baut die
  App aus ihrem lokalen Freundes-Cache. Google sieht dadurch weder Namen noch Spielstände noch Gesundheitsdaten.
- **Arten:**
  - Freundschaftsanfrage und -annahme
  - Mitspieler eingetragen („… läuft gerade – live ansehen“)
  - Zuschauer-Einladung
  - Zähl-Bitte und Antwort darauf
  - neu: Zählerwechsel (`scorer_changed`)
  - **Kein** Push je Punkt.
- **Token:** `cloud_set_push_token_v1(provider, token)` setzt das Token nur am gebundenen Gerät des Aufrufers.
  Abmelden leert es. Inaktive Tokens werden nach 180 Tagen gelöscht.
- **Einwilligung:** Android 13+ fragt `POST_NOTIFICATIONS` erst bei Bedarf ab (erste Freundschaft,
  Einladung, Profil → Benachrichtigungen). FCM-Auto-Init ist aus, ohne Einwilligung gibt es kein Token.
  Je Kategorie gibt es einen Android-Kanal (Freunde, Matches, Zählen).
- **Begrenzung:**
  - Gleiche Art, gleicher Empfänger und gleiches Match innerhalb von 10 min werden zusammengefasst.
  - Höchstens 30 Push je Empfänger und Stunde.
  - Gesendete Einträge werden nach 7 Tagen gelöscht, fehlgeschlagene nach 5 Versuchen.
- **Uhren:** Die Wear-Uhr spiegelt die Handy-Benachrichtigungen. Uhren bekommen kein eigenes Token.

### 3.4 Zählen weitergeben (anderes Konto)
- **Standard ist die Abgabe durch den aktuellen Zähler:**
  1. Alex wählt in der Live-Ansicht oder beim Start „Zählen weitergeben an Jonas“. Das ruft `invite_scorer`
     auf und schickt Jonas eine Push-Nachricht. Alex zählt weiter.
  2. Jonas nimmt auf Handy oder Uhr an. Für die Uhr registriert sein Handy sie vorher mit `register_device`.
     Danach folgt `respond_scorer_invitation`, und Alex bekommt eine Push-Nachricht.
  3. **Alex' Handy erzeugt automatisch `ScorerChanged(Gerät von Jonas)`**, lädt es sofort hoch und gibt es an
     Alex' Uhr weiter. Die Uhr wechselt sofort in die Anzeige. Weil der Abgebende selbst übergibt, entsteht
     kein Konflikt.
  4. Jonas' Handy holt das Match und prüft, dass der Stand vollständig ist (`cloud_match_readiness_v1`). Bei der
     Uhr als Ziel gibt es das Match per `assignToWatch` weiter. Danach zählt Jonas.
- **Notfall-Übernahme** (Alex' Geräte nicht erreichbar): Jonas bestätigt eine Warnung. Die Taste ist erst aktiv,
  wenn der Stand vollständig geladen ist. Punkte, die Alex danach noch zählt, verwirft die Engine deterministisch;
  sie erscheinen als „N Eingaben nicht übernommen“.
- **Nötige Änderungen:**
  - **Backend:**
    - `sync_pull` liefert Rollen-Geräte auch an Mitglieder.
    - Bei einer angenommenen Rolle darf das Gerät gewechselt werden, widerrufene Geräte werden abgelehnt.
    - Blockieren und Entfreunden widerrufen auch Zähler-Rollen.
    - Ein Trigger meldet `scorer_changed`.
  - **Session:**
    - neu `handOverScoring`
    - Gezogene Events gehen sofort an die Uhr, ein gezogenes `ScorerChanged` wird mit der Uhr abgeglichen.
    - Übernehmen ist erst nach Prüfung des vollständigen Stands möglich.
- **Offline:** Die Uhr hat kein Internet, jede Übergabe läuft über beide Handys und die Cloud. Ist Alex' Handy
  offline, bleibt nur die Notfall-Übernahme.

## 4. Phasen (PR-Schnitte, Backend vor App)

| PR | Inhalt | Abnahme | braucht |
|---|---|---|---|
| **0** | Verdacht aus §2 auf echten Geräten mit zwei Konten prüfen, ggf. sofort beheben | Ergebnis dokumentiert; Fremd-Matches nicht übernehmbar ohne Stand | – |
| **1** | Backend `cloud_my_live_matches_v1` + pgTAP | Mitglied/Zuschauer sieht, Widerruf/„War ich nicht“/Blockierung/Feed-Leser nicht; keine Telemetrieschlüssel | – |
| **2** | Handy: Live-Upload für jedes laufende Match, Bereich „Live“ mit 5-s-Abfrage, Fremd-Matches nur Anzeige | Stand beim Mitspieler ≤ 15 s (p95) | 1 |
| **3** | Protokoll `LiveViewSubscription`/`LiveViews` + Tests | Größe, Version, keine Telemetrie | – |
| **4** | Wear: Zuschauen auf der Uhr | Galaxy Watch zeigt fremdes Match ≤ 20 s; Abo endet beim Verlassen/Ambient | 1–3 |
| **5** | Backend Push-Outbox: `actor_id`, Token-RPC, claim/ack, Prüfung beim Versand, Zusammenfassen, Limits, Aufbewahrung, pgTAP | kein Versand bei Blockierung/Widerruf; Token nur am eigenen Gerät | – |
| **6** | Edge Function `push-dispatch` (FCM v1) + Deno-Tests | echter Versand an ein Testgerät | 5 |
| **7** | Android Push: `firebase-messaging` ohne Analytics, Einwilligung, Kanäle, Deep Links; Data Safety und Datenschutzerklärung | Anfrage/Eintrag/Einladung kommen in < 10 s; ohne Einwilligung kein Token | 5, 6 |
| **8** | Backend Übergabe (Rollen an Mitglieder, Gerätewechsel, Blockieren widerruft Rollen, `scorer_changed`) + pgTAP | grün | 5 |
| **9** | cloud-sync/presentation: Rollen-Port, Einladungs-Warteschlange, Zähler-Wahl „Mitspieler“, „Zählen weitergeben“, Annahme auf Handy/Uhr | ViewModel-Tests | 8 |
| **10** | match-session: `handOverScoring`, sofortige Weitergabe gezogener Events an die Uhr, Übernahme erst nach vollständigem Stand | Tests mit zwei Konten (p2-plan G4/G5), kein verlorener Punkt | 8 |
| **11** | Android-Host Übergabe: Annahme-Ablauf, automatische Abgabe, Abfrage bis zum Wechsel | Feldtest zwei Konten: Uhr→Uhr, Uhr→Handy, Notfall bei Flugmodus | 7, 9, 10 |
| *12* | optional: Realtime; iOS-Konto + APNs + Apple Watch | eigenes Gate | Apple-Account |

Reihenfolge: 0 → 1 → (2 + 3/4 parallel zu 5/6/7) → 8 → 9/10 → 11. Jede Server-Migration geht vor der passenden App
auf Staging (siehe Release-Weg).

## 5. Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Konflikt bei Notfall-Übernahme | Abgabe als Standard; Übernahme nur mit Warnung und vollständigem Stand; Anzeige verworfener Eingaben |
| Bestehende Übernahme fremder Matches (§2) | PR 0 vor allem anderen |
| Kein Netz am Platz | Live und Übergabe fallen weg, lokales Zählen bleibt; „Stand vor X s“ anzeigen |
| Android Doze / Hintergrund | Push mit hoher Priorität immer mit sichtbarer Benachrichtigung; Upload als Expedited Job; Feldtest mit Display aus |
| Akku | Uhr-Abo nur bei sichtbarer Ansicht; Upload nur bei Änderung |
| Erstes Fremd-SDK (Firebase) | Data Safety (Geräte-IDs), Datenschutzerklärung, Auftragsverarbeitung mit Google, US-Übermittlung |
| Apple | ohne Developer-Account weder APNs noch iOS-Konto: bis dahin nur Android + Wear OS |

## 6. Offene Entscheidungen

| # | Frage | Empfehlung |
|---|---|---|
| E1 | Live per Abfrage (5–15 s) oder Realtime (< 2 s, schwächere Prüfung) | Abfrage in v1 |
| E2 | Live-Upload für jedes laufende Match oder nur bei Mitspielern/Zuschauern | jedes laufende Match |
| E3 | Wer sieht live? | nur bestehende Regeln: verknüpfte Mitspieler, benannte Zuschauer |
| E4 | Push-Text lokal aus IDs oder Name in der Nachricht | lokal aus IDs |
| E5 | Push-Arten | wie §3.3, kein Push je Punkt |
| E6 | Übergabe-Modell | Abgabe durch aktuellen Zähler, Übernahme nur als Notfall |
| E7 | Firebase einbinden | ja (sonst kein Push auf Android) |
| E8 | Uhr als Zuschauer | Wear OS jetzt, Apple Watch mit iOS-Konto |
| E9 | Apple/APNs | zurückstellen bis Developer-Account |
