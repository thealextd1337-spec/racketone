# Plan Einladungen: Freund per QR-Code oder Link, Gast übernimmt sich selbst

> Status: Entwurf 2026-11-04 · Bezug: PLAN P2 („Freundschaften per Suche, QR und Einladungslink“, „Gäste-Claim per Link“),
> p2-plan P2-D/P2-F, 03 §6.1/§6.3, 0.6.3 §3.5 (Gast mit Freund verknüpfen), 0.6.4 (Kontolöschung des verknüpften Freundes)
> · Vorschlag Version 0.7.0, Build 18 (versionCodes 181/182), nach 0.6.4

## 1. Ziel und Nutzerfluss

Nutzerwünsche vom 04.11.2026:

- **(3) Freund per QR-Code oder Einladungslink hinzufügen.** Heute geht das nur über den Freundescode oder den genauen
  Benutzernamen, mit Anfrage und Annahme.
- **(4) Ein Gast beansprucht sich selbst per Link.** Alex schickt Anja einen Link. Sie übernimmt „Test1“ mit allen
  Matches und ist danach mit Alex befreundet.

Beide Wege nutzen **einen** Link-Typ: `https://advantly.eu/i/#<token>` (siehe §3.1). Der QR-Code enthält denselben Link.

### 1.1 Freund per Einladungslink (Fall 3, aus der Ferne)

1. Alex: Freunde-Tab → „Freunde einladen“ → „Link teilen“. Die App holt einen Link (`create_invite('friend')`, 14 Tage,
   einmal nutzbar) und öffnet das System-Teilen-Menü mit Text und Link.
2. Anja tippt den Link in WhatsApp o. Ä. an.
   - **App installiert, Konto aktiv:** Android öffnet Advantly direkt (verifizierter App Link). Die App wechselt in den
     Freunde-Tab und zeigt die Vorschau „Freundschaft mit Alex?“ (Alias aus `preview_invite`). „Annehmen“ →
     `redeem_invite` → „Du bist jetzt mit Alex befreundet.“ Alex bekommt den Push `friend_accepted`.
   - **App installiert, nicht angemeldet bzw. Onboarding unvollständig:** Die App merkt sich den Link (nur im Speicher und
     im `SavedStateHandle`, nie in der Datenbank oder im Backup). Hinweis oben: „Eine Einladung wartet. Melde dich an, um
     sie anzunehmen.“ Danach normales Onboarding: Land → Geburtsdatum → Anmeldung → Alias/Benutzername → Zustimmung zu
     Nutzungsbedingungen und Datenschutz. Sobald der Freunde-Zugang `READY` ist, erscheint die Vorschau wie oben.
     Geht der Prozess unterwegs verloren (z. B. Wechsel in die Mail-App für den Code), stellt der `SavedStateHandle` den
     Link wieder her. Ist auch der weg, tippt Anja den Link einfach erneut an.
   - **Altersgrenze nicht erreicht oder Land gesperrt:** Es entsteht kein Konto. Die App verwirft den Link mit
     „Einladungen kannst du nur mit Konto annehmen.“
   - **App nicht installiert:** Der Browser öffnet die Fallback-Seite `advantly.eu/i/` (statisch, ohne Tracking). Sie zeigt
     „Du wurdest zu Advantly eingeladen“, den Knopf „Bei Google Play laden“ und die Schritte: installieren, Konto anlegen,
     **den Link danach noch einmal antippen**. Zusätzlich „Link kopieren“. In der App kann der Link auch in das bestehende
     Feld „Freund hinzufügen“ eingefügt werden (es erkennt Einladungslinks neben Code und Benutzername).
     Kein Install-Referrer in der ersten Stufe (Entscheidung E6).
   - **iPhone:** Die Fallback-Seite sagt „Advantly für iPhone folgt“. Universal Links kommen mit der iOS-App (§3.6).
3. Sonderfälle:
   - **Schon befreundet:** Vorschau „Ihr seid schon befreundet.“ Nur „OK“, der Link wird nicht verbraucht, kein Push an Alex.
   - **Offene Anfrage in einer der beiden Richtungen oder früher abgelehnt:** Einlösen macht daraus eine Freundschaft
     (Einladen und Annehmen sind beide ausdrücklich). Das ist der heutige Serverstand.
   - **Blockiert (in einer der beiden Richtungen) oder Alex' Konto gelöscht bzw. in Löschung:** „Diese Einladung ist nicht
     mehr gültig.“ Bewusst derselbe Text wie bei unbekannten Links, damit kein Orakel für Blockierungen entsteht.
   - **Abgelaufen, schon benutzt, zurückgezogen:** jeweils eigener Text. Der Token ist nicht erratbar, die Unterscheidung
     verrät nur dem Linkinhaber etwas.
   - **Eigener Link:** „Das ist deine eigene Einladung.“
   - **Offline, Zustimmung fehlt, Gerät gesperrt:** Link bleibt gemerkt, Hinweis wie bei den übrigen Freunde-Aktionen.

### 1.2 Freund per QR-Code (Fall 3, persönlich)

1. Alex: Freunde-Tab → „QR-Code zeigen“. Die App holt einen kurzlebigen Link (`create_invite('friend', ttl 10 min)`) und
   zeigt ihn als QR mit Restzeit.
2. Anja scannt:
   - in Advantly mit „QR-Code scannen“ (Google Code Scanner, keine Kamera-Berechtigung, §3.3) oder
   - mit der normalen Kamera-App. Die erkennt den Link und öffnet Advantly über den App Link.
   Danach weiter wie in §1.1, inklusive aller Fälle ohne App oder ohne Konto. Bei frischer Installation ist der QR meist
   abgelaufen. Alex tippt dann „Neuer Code“.
3. Nach dem Einlösen kommt bei Alex der Push `friend_accepted` an. Ist der QR-Bildschirm offen, lädt er die Freundesliste
   neu und zeigt automatisch einen neuen Code (für die nächste Person). Ein QR ist einmal nutzbar (Entscheidung E5).

### 1.3 Gast übernimmt sich selbst (Fall 4)

1. Alex: Profil → Spieler → „Test1“ → Menü „Einladungslink senden“. Sichtbar wie „Mit Freund verknüpfen“ (0.6.3) nur
   für Gäste, die der Server kennt (mindestens ein hochgeladenes Match), die nicht verknüpft und nicht redigiert sind.
2. Bestätigung: „Wer den Link öffnet, übernimmt alle Matches von Test1 und wird dein Freund. Schick ihn nur an die Person,
   die Test1 ist.“ → `create_invite('claim_player', test1)`. Ein neuer Link zieht ältere offene Links für diesen Gast
   zurück (§3.4). Danach Teilen-Menü.
3. In der Gästeliste steht „Einladung offen bis 18.11.“ und im Menü „Einladung zurückziehen“.
4. Anja öffnet den Link. App, Konto und Onboarding verhalten sich wie in §1.1. Vorschau:
   „Bist du Test1? Alex hat dich in 7 Matches als ‚Test1‘ eingetragen …“ → „Ja, das bin ich“ → `redeem_invite`.
   Ergebnis: „7 Matches übernommen. Du bist jetzt mit Alex befreundet.“ Die Matches kommen über den normalen Pull.
5. Alex bekommt den Push „Anja hat Test1 übernommen“ (neu, Entscheidung E8). In der Gästeliste steht „verknüpft mit Anja“
   mit „Verknüpfung lösen“ (vorhanden aus 0.6.3, `unassign_player` löst auch Claims).
6. Sonderfälle:
   - **Anja ist schon Alex' Freundin:** Claim geht normal, die Freundschaft bleibt.
   - **Test1 wurde inzwischen jemandem zugeordnet oder beansprucht:** „Test1 wurde schon übernommen.“
   - **Alex hat Test1 gelöscht oder Test1 ist redigiert** (Kontolöschung): „Diese Einladung ist nicht mehr gültig.“
     Heute prüft `redeem_invite` `deleted_at` nicht (Lücke, §2.1).
   - **Anja stand in einzelnen Matches schon mit Konto:** Diese Matches werden übersprungen. Die Anzahl nennt nur die
     übernommenen.
   - **Blockiert, abgelaufen, eigener Link:** wie §1.1.
   - **Falsche Person hat den Link** (weitergeleitet): Sie sieht vorher Alex' Alias, „Test1“ und die Anzahl. Nimmt sie
     trotzdem an, erfährt Alex es per Push und löst die Verknüpfung. Die Freundschaft entfernt er wie gewohnt.
     Anja selbst kann einzelne Matches mit „War ich nicht“ lösen (`reject_match_participation` greift auch für `confirmed`).

## 2. Bestand

### 2.1 Server (kann fast alles)

Migration `20261015000700_rpc_zuweisung_claim.sql`, aktuelle Fassung mit Rate-Limit in `20261016000000_rate_limits.sql`,
Cloud-Bindung seit `20261018000100_cloud_device_binding.sql`, Redaktionssperre seit `20261018000700_redaction_write_guards.sql`:

- **Tabelle `invites`:** `token_hash` (sha256, unique), `kind` (`friend` | `claim_player`), `created_by`, `player_id`,
  `expires_at`, `used_by`, `used_at`, `revoked_at`. Anders als in 03 §3.3.6 **ohne** `max_uses`/`used_count`: einmal
  nutzbar. RLS: nur eigene Zeilen lesbar, Spaltenrechte ohne `token_hash`.
- **`create_invite(kind, player_id, ttl_minutes)`:** Token aus 2 × UUIDv4 (≈ 244 Bit, 64 Hex-Zeichen), nur der Hash wird
  gespeichert. TTL 1 min bis 14 Tage (Standard 14 Tage). Für Claims: eigener, unverknüpfter, nicht gelöschter, nicht
  redigierter Gast.
- **`redeem_invite(token)`:** prüft gültig, nicht benutzt, nicht zurückgezogen, nicht abgelaufen, nicht eigener Link,
  keine Blockierung, Ersteller aktiv. Claim: `players.profile_id` setzen (`assignment_source = 'claim'`), alle
  Teilnehmerzeilen verknüpfen (`link_status = 'confirmed'`, Matches mit Anja schon drin oder per „War ich nicht“
  abgelehnt werden übersprungen), Outbox `player_claimed`. Danach immer Freundschaft `accepted` (Quelle `link` bzw.
  `claim`) und Outbox `friend_accepted`. Antwort `{kind, friendId, playerId, matchCount}`.
- **Rate-Limit:** `create_invite`, `redeem_invite`, `send_friend_request` usw. teilen das Budget `invitation`
  (20 je Minute und Konto). `find_profile` hat `search` (30 je Minute).
- **Sicherheit:** Beide RPCs laufen unter `require_cloud_binding` (Versionssperre, Zustimmung, Gerätebindung, globale
  `lock_sync`), sind also wie die übrigen Freunde-RPCs direkt aufrufbar. Eigene `cloud_*_v1`-Hüllen sind nicht nötig
  (wie in 0.6.3 für `assign_player` festgestellt).
- **Kontolöschung:** offene Einladungen werden zurückgezogen bzw. gelöscht, `used_by` geleert. 0.6.4 behandelt
  beanspruchte Gäste wie zugeordnete.
- **pgTAP 070** deckt den Glücksfall ab: Claim ohne Freundschaft, Matchanzahl, Freundschaft entsteht, Link nur einmal.
  **pgTAP 100** prüft das gemeinsame Budget.

**Fehlt bzw. ist zu korrigieren:**

| # | Was | Warum |
|---|---|---|
| S1 | `preview_invite(token)` (neu, nur lesend) | Empfänger muss vor dem Einlösen sehen, von wem und wofür (§4). Heute gibt es nur Einlösen. |
| S2 | `revoke_invite(id)` (neu) | Widerruf ist geplant (03 §6.3), aber es gibt keinen RPC. |
| S3 | `redeem_invite`: Gast mit `deleted_at` ablehnen | Ein vom Anleger gelöschter Gast lässt sich heute noch beanspruchen. |
| S4 | `redeem_invite` bei Freundes-Link und bestehender Freundschaft | Heute: Link verbraucht, falscher Push „hat deine Anfrage angenommen“. Neu: Ergebnis `alreadyFriends`, kein Verbrauch, kein Push. |
| S5 | `create_invite`: ältere offene Claim-Links desselben Gasts zurückziehen | Sonst mehrere gültige Links für „Test1“. Der erste gewinnt, die anderen scheitern verwirrend. |
| S6 | Obergrenze offener Einladungen je Konto (z. B. 50) | Das Minuten-Budget allein lässt die Tabelle wachsen. Fehler `too_many_open_invites`. |
| S7 | Aufräumjob (pg_cron, wie `purge_push_outbox`) | Einladungen werden heute nie gelöscht. Vorschlag: 30 Tage nach Ablauf bzw. Nutzung löschen. |
| S8 | Push für `player_claimed` (in `private.push_kinds()` aufnehmen) | Heute nur Outbox ohne Versand. Alex erfährt den Claim nur indirekt über `friend_accepted`. |

Nicht nötig: neue Tabellen, Änderungen an `sync_pull` (Claims laufen über dieselben Touch-Trigger wie
`assign_player`), Freundescode-Link `/f/<code>` aus 03 §6.1 (Entscheidung E9).

### 2.2 App (kennt Einladungen noch gar nicht)

- **Vorhanden:**
  - Freunde-Tab (`ui-phone/.../friends/FriendsScreen.kt`, `presentation-auth/.../FriendsViewModel.kt`): eigener
    Freundescode mit „Teilen“ (Text), Feld „Freund hinzufügen“ für Code oder genauen Benutzernamen (`find_profile` →
    `send_friend_request`), Anfragen, Blockieren, Fehlertexte über `errorLabel`.
  - Gäste (`ui-phone/.../app/GuestList.kt`, `cloud-sync/.../GuestLinks.kt`): Verknüpfen, Lösen, „verknüpft mit …“.
  - Muster für eingehende Ziele: `MainActivity` reicht `PushTarget` aus dem Intent an `FriendsScreen(link, onLinkHandled)`
    weiter. Einladungslinks laufen genauso.
  - Teilen von Text (`onShareText`).
- **Fehlt:**
  - `cloud-sync`: Port und HTTP-Aufrufe für `create_invite`, `preview_invite`, `redeem_invite`, `revoke_invite` und das
    Lesen eigener offener Einladungen (`invites`-Select wie `ownRow`).
  - `presentation-auth`: Linkparser, ausstehender Link über Anmeldung, Onboarding und Zustimmung hinweg, Vorschau,
    Einlösen, Ergebnis und Fehler. Dazu QR-Zustand (Token, Ablauf, Erneuern) und offene Claim-Links in der Gästeliste.
  - `ui-phone`: Block „Freunde einladen“, QR-Bildschirm, Einlöse-Dialoge, Gast-Menüpunkte, QR-Erzeugung.
  - `androidApp`: Intent-Filter mit `autoVerify`, Weitergabe der URL, QR-Scan über Google Code Scanner.
  - **Keine Website-Verknüpfung:** Es gibt kein `site/.well-known/`, kein `assetlinks.json`, keine Seite unter `/i/` und
    keinen VIEW-Intent-Filter. `site/` wird per GitHub Pages ausgeliefert (`CNAME` = `advantly.eu`, `.nojekyll`
    vorhanden, `pages.yml` lädt Dotfiles mit `include-hidden-files: true`). **`pages.yml` muss nicht geändert werden.**
- **Sync:** keine Änderung. Nach dem Claim kommen die Matches über den normalen Pull, weil die Touch-Trigger auf
  `match_participants` greifen wie bei `assign_player`. Die Gästeliste beim Anleger zeigt den Claim über den gezogenen
  Spieler (`players.profile_id`) so, wie sie heute eine Zuordnung zeigt.

## 3. Technik

### 3.1 Linkformat

**Empfehlung: `https://advantly.eu/i/#<token>`** (Token = die 64 Hex-Zeichen aus `create_invite`).

- **Fragment statt Pfad oder Query:**
  - Der Teil nach `#` wird nicht an den Server geschickt. GitHub Pages sieht in seinen Logs nur `/i/`, nie den Token.
  - `/i/` ist eine echte statische Seite (`site/i/index.html`). Ein Pfad `/i/<token>` bräuchte dagegen den 404-Trick von
    GitHub Pages.
  - Android-App-Links vergleichen nur Schema, Host und Pfad. `android:path="/i/"` passt, die App liest
    `Uri.fragment`.
- **Die App nimmt auch an:** `https://advantly.eu/i?t=<token>` (für den „In App öffnen“-Weg der Fallback-Seite, falls
  später nötig) sowie den nackten Token im Feld „Freund hinzufügen“. Alles andere lehnt der Parser ab: fremder Host,
  `http`, falsche Länge, keine Hex-Zeichen.
- **Ein Link-Typ für Freund und Claim:** Die Art kennt nur der Server. Wer den Link sieht, erfährt ohne Konto nichts.
- **Verworfen:**
  - Eigenes Schema `advantly://`: Messenger machen es nicht anklickbar, und es gibt keinen Fallback ohne App.
  - Edge Function auf `*.supabase.co`: Dort lassen sich weder `assetlinks.json` für unsere Domain ablegen noch HTML
    ausliefern.
- **Android-Verifikation:**
  - `site/.well-known/assetlinks.json` mit `delegate_permission/common.handle_all_urls` für `eu.advantly`.
    Fingerabdrücke: der **App-Signaturschlüssel aus der Play Console** (Play App Signing) und zusätzlich der
    Upload-Schlüssel (`docs/release/play-console.md` §2).
  - Intent-Filter in `androidApp` (nicht Wear): `VIEW`, `DEFAULT`, `BROWSABLE`, `https`, Host `advantly.eu`, Pfad `/i/`,
    `android:autoVerify="true"`.
  - Debug-Builds sind nicht verifiziert. Lokal hilft
    `adb shell pm set-app-links-user-selection --user cur --package eu.advantly true advantly.eu`.

> **Achtung: Änderung in `site/` nötig, die einen ausdrücklichen Auftrag braucht** (CLAUDE.md „Nicht tun“).
> Betroffen sind `site/.well-known/assetlinks.json` (neu), `site/i/index.html` (neu, zweisprachig wie `404.html`) und
> die App-Datenschutzerklärung `site/app-datenschutz.html` + `site/en/app-privacy.html` (§4). Ohne diese Dateien öffnen
> Links immer den Browser, und es gibt keinen Weg für Empfänger ohne App. `.github/workflows/pages.yml` bleibt unverändert.

**Fallback-Seite `site/i/index.html`:**
- Statisch, keine externen Ressourcen, keine Cookies, kein Tracking, `noindex`.
- Inhalt: Kurztext DE/EN, Knopf zu `https://play.google.com/store/apps/details?id=eu.advantly`, die drei Schritte,
  „Link kopieren“ (eine Zeile Inline-JS mit `navigator.clipboard`) und der Hinweis zum iPhone.
- Liest den Token nicht aus und zeigt ihn nicht an.
- **Pilot:** Solange nur interne bzw. geschlossene Tracks laufen, muss die eingeladene Person Testerin sein, sonst zeigt
  Play „nicht verfügbar“. Die Seite sagt das in einem Satz.

### 3.2 QR-Erzeugung

- In `libs.versions.toml` gibt es **keine** QR- oder Barcode-Bibliothek.
- **Empfehlung: kleiner QR-Encoder in `commonMain` von `ui-phone`**, gekürzt nach Nayuki „QR Code generator“ (MIT):
  - nur Byte-Modus, Fehlerkorrektur M, automatische Version 1–10, Masken-Auswahl
  - etwa 300 Zeilen, ohne neue Laufzeit-Abhängigkeit, läuft auf Android, iOS und Desktop
  - Lizenzhinweis in der Datei und in den Drittanbieter-Hinweisen der App
  - Zeichnen per Compose-`Canvas` mit Ruhezone, schwarz auf weiß, auch im Dark-Theme
- Der Link hat 87 Zeichen (23 + 64), das ergibt QR-Version 6 (41 × 41 Module). Auf dem Handy-Bildschirm problemlos lesbar.
- **Verworfen:**
  - `zxing-core` als Laufzeit-Abhängigkeit: nur JVM, also nicht für iOS.
  - KMP-QR-Bibliotheken mit eigener Compose-Abhängigkeit: Sie koppeln sich an die CMP-Version, und die Regel lautet
    „Kotlin, SKIE und CMP nur gemeinsam anheben“.
  - `zxing-core` ist aber als **reine Test-Abhängigkeit** (`desktopTest`) sinnvoll, um erzeugte Codes zurückzulesen (§6).

### 3.3 QR-Scan

- **Empfehlung: Google Code Scanner** (`com.google.android.gms:play-services-code-scanner`, Version beim Bau aus Google
  Maven prüfen und in `libs.versions.toml` eintragen), nur in `androidApp`.
  - **Keine `CAMERA`-Berechtigung:** Die Kamera-Oberfläche läuft in Google Play-Diensten, die App bekommt nur den
    erkannten Text. Beim Bau mit dem zusammengeführten Manifest prüfen, dass keine Berechtigung hinzukommt.
  - Anbindung wie `onShareText`: eine Callback-Funktion `onScanQr` aus `MainActivity` in `PhoneApp` → `FriendsScreen`.
    Ohne Play-Dienste (oder auf iOS/Desktop) ist der Knopf ausgeblendet.
  - Das Scanner-Modul lädt Play-Dienste beim ersten Gebrauch nach. Ist es noch nicht da, erscheint der Hinweis „Scanner
    wird vorbereitet. Versuche es gleich noch einmal.“ Kein Vorab-Download per Manifest-Metadaten.
  - Das Ergebnis geht durch denselben Linkparser. Fremde QR-Inhalte ergeben „Kein Advantly-Code“.
- **Systemkamera geht immer:** Pixel-, Samsung- und Lens-Kamera öffnen den Link, der App Link landet in Advantly. Deshalb
  bleibt der In-App-Scanner eine Bequemlichkeit (Entscheidung E4).
- **Datenschutz und Datensicherheit:** Der Code Scanner gehört zu ML Kit. Laut Google-Doku wird das Bild auf dem Gerät
  verarbeitet. ML Kit sendet aber Nutzungs- und Diagnosedaten an Google. **Vor dem Bau mit der Google-Seite „ML Kit –
  Google Play data disclosure“ abgleichen (nicht geprüft).** Je nach Ergebnis:
  - Play-Datensicherheit: Geräte- oder andere IDs sind schon „erhoben: ja“ (FCM, `play-console.md` §6.6). Eventuell kommt
    „App-Informationen und -Leistung → Diagnose“ hinzu.
  - Datenschutzerklärung §6, letzter Satz („nutzen Sie Dienste von Google nach deren eigenen Bestimmungen“) um „den
    QR-Scanner“ ergänzen. §9 („keine … Analyse-Werkzeuge“) bleibt richtig, weil wir keine Daten bekommen. Den Satz aber
    gegen die Google-Angabe prüfen.
  - Fällt die Prüfung schlecht aus: kein In-App-Scanner, nur Systemkamera. Dann ändert sich an der Datensicherheit nichts.

### 3.4 Gültigkeit, Nutzung, Widerruf, Missbrauch

| Punkt | Freundes-Link | QR (persönlich) | Claim-Link |
|---|---|---|---|
| Gültigkeit | 14 Tage (heute Standard) | 10 Minuten (`p_ttl_minutes = 10`) | 14 Tage |
| Nutzung | einmal | einmal | einmal |
| Mehrere offen | ja | ja (jeder neue Code ist ein neuer Link) | **nur einer je Gast** (S5) |
| Widerruf | `revoke_invite` (S2); in der App nur indirekt durch Ablauf | Ablauf | „Einladung zurückziehen“ in der Gästeliste; neuer Link zieht den alten zurück |
| Rücknahme nach Einlösen | Freund entfernen | Freund entfernen | Anleger: „Verknüpfung lösen“ (`unassign_player`); Anja: „War ich nicht“ je Match |

- **Token:** 244 Bit Zufall, Hash in der Datenbank. Erraten ist ausgeschlossen. Einlösen geht nur mit aktivem Konto und
  Cloud-Bindung, anonym also nicht.
- **Rate-Limits:** Erzeugen und Einlösen bleiben im gemeinsamen Budget `invitation` (20 je Minute). `preview_invite`
  nutzt `search` (30 je Minute). Neu ist die Obergrenze von 50 offenen Einladungen je Konto (S6). Der QR-Bildschirm
  erneuert nur auf Knopfdruck oder nach einem Einlösen, nie im Takt.
- **Missbrauch:**
  - *Spam-Freundschaften:* Freundschaft entsteht nur, wenn der Empfänger aktiv bestätigt. Ein Link wirkt nur einmal.
    Blockieren wirkt wie bisher.
  - *Abfotografierter QR:* nach 10 Minuten wertlos und nur einmal nutzbar.
  - *Weitergeleiteter Claim-Link:* Die Vorschau nennt Gastname, Alias und Anzahl. Alex bekommt einen Push (S8) und kann
    lösen. Der Link ist einmal nutzbar, ein neuer zieht alte zurück.
  - *Blockier-Orakel:* Blockiert, gelöscht und unbekannt ergeben dieselbe Antwort `invalid_invite` (heutiger Stand,
    bleibt so, auch in `preview_invite`).
- **Ausstehender Link in der App:** Er liegt nur im Speicher und im `SavedStateHandle`, nie in SQLDelight, Prefs oder
  Backup. Er wird nach Erfolg, Ablehnung, Abmeldung, Kontowechsel oder spätestens beim Prozessende verworfen.

### 3.5 Server-Änderungen im Detail (eine Migration)

- **`public.preview_invite(p_token text) returns jsonb`**
  - `security definer`, `search_path = ''`, Cloud-Bindung wie die übrigen RPCs, `consume_rate_limit('search')`, nur lesend.
  - Antwort: `{status, kind, inviterId, inviterAlias, guestName, matchCount, expiresAt}`.
  - `status`:
    - `ok`
    - `already_friends` (nur `friend`)
    - `already_claimed` (Gast schon verknüpft)
    - `own`
    - `expired`
    - `used`
    - `revoked`
    - `invalid` (unbekannt, blockiert, Ersteller inaktiv, Gast gelöscht oder redigiert)
  - Bei `invalid` gibt es keine weiteren Felder.
  - `guestName` und `matchCount` nur bei `claim_player` und `ok`.
- **`public.revoke_invite(p_invite_id uuid) returns void`:** nur der Ersteller, nur offene Links. Sonst `invite_not_found`.
- **`create_invite`** per Seam-Ersatz wie in den Vormigrationen (Signatur, Owner, ACL bleiben):
  - S5: offene Claim-Links desselben Gasts auf `revoked_at = now()` setzen
  - S6: Zählprüfung offener Einladungen
- **`redeem_invite`** per Seam-Ersatz:
  - S3: Gast `deleted_at is null`, sonst `invalid_invite`
  - S4: Bei `friend` und bestehender Freundschaft `{kind, friendId, alreadyFriends: true}` zurückgeben, ohne `notify` und
    ohne `used_at`
- **S7** `private.purge_invites()` + `cron.schedule('advantly-invites-cleanup-v1', …)`.
- **S8** `private.push_kinds()` um `player_claimed` erweitern. Ältere Apps ignorieren unbekannte Arten
  (`PushKind.entries.firstOrNull … ?: return null`).
- Rechte: `revoke all … from public, anon`, `grant execute … to authenticated`. Beide neuen RPCs in die Prüfliste der
  Cloud-Bindung (Muster aus pgTAP 170) aufnehmen.
- Abwärtskompatibel: Alte Apps rufen keine Einladungs-RPCs auf.

### 3.6 iOS und Universal Links (später)

- Das Linkformat passt unverändert. `site/.well-known/apple-app-site-association` mit
  `applinks.details[].components = [{"/": "/i/", "#": "*"}]` und App-ID `<TeamID>.eu.advantly`. Dazu Associated Domains
  `applinks:advantly.eu` im XcodeGen-Projekt und Weitergabe der URL im Swift-Host an eine `AdvantlyKit`-Facade.
- Voraussetzung: Apple-Developer-Konto (noch offen). Prüfen, ob GitHub Pages die Datei ohne Endung mit passendem
  Content-Type ausliefert.
- QR zeigen funktioniert dank `commonMain`-Encoder sofort. Scannen über die iOS-Kamera-App, ein In-App-Scanner
  (VisionKit) bräuchte `NSCameraUsageDescription`, deshalb erst einmal nicht.
- Die Fallback-Seite ändert dann nur den iPhone-Satz.

## 4. Datenschutz

- **Was der Empfänger vor dem Einlösen sieht:**
  - Ohne Konto: nichts Personenbezogenes. Die Website und die App zeigen nur „Du wurdest eingeladen“.
  - Mit Konto über `preview_invite`:
    - den **Alias** des Einladenden (ohnehin öffentlich, F18)
    - bei Claims den **Gastnamen**, den Alex vergeben hat, und die **Anzahl der Matches**
    - keine Mitspielernamen, keine Spielstände, kein Benutzername
  - Erst nach dem Claim sieht Anja die Matches mit allen Teilnehmernamen. Das entspricht der Zuordnung (§4.3 „eingetragene
    Mitspieler sehen die Anzeigenamen aller Teilnehmenden“).
- **Was gespeichert wird:** Hash des Tokens, Art, Ersteller, Gast, Ablauf, Einlöser mit Zeitpunkt bzw. Widerruf. Neu ist die
  Löschfrist von 30 Tagen nach Ablauf bzw. Nutzung (S7). Der Klartext-Token liegt nur im geteilten Link (beim Messenger
  des Nutzers) und kurz im App-Speicher.
- **Website:** Der Token steht im Fragment und erreicht GitHub nicht. Die Website-Erklärung (`site/datenschutz.html` §3
  „aufgerufene Seite“) passt weiter.
- **Datenschutzerklärung der App: neue Fassung nötig, Empfehlung PRIVACY 10.** Neue Fassung heißt, dass alle Konten neu
  bestätigen. Nach dem Eintrag `is_current` prüfen.
  - Grund: §4.3 sagt heute „Andere können Sie nur über Ihren genauen Benutzernamen oder Ihren Freundescode finden und
    Ihnen eine Freundschaftsanfrage senden“. Einladungslinks und QR erzeugen dagegen eine Freundschaft **ohne Anfrage**.
    Das Übernehmen eines Gasts per Link ist eine neue Verknüpfung durch Dritte und fehlt ganz.
  - Vorschlag für die neuen Sätze in §4.3 (DE/EN synchron):
    - **Einladungslinks und QR-Codes:** Sie können einen Link oder QR-Code erzeugen. Wer ihn öffnet und bestätigt, wird
      sofort Ihr Freund. Vorher sieht er Ihren Anzeigenamen. Links gelten 14 Tage, QR-Codes 10 Minuten, jeweils einmal.
      Wir speichern nur einen Hashwert des Links, Art, Ablauf und wer ihn wann eingelöst hat, und löschen das 30 Tage
      nach Ablauf bzw. Nutzung.
    - **Gast übernehmen:** Für einen von Ihnen angelegten Gast können Sie einen Link senden. Wer ihn einlöst, sieht vorher
      Ihren Anzeigenamen, den Gastnamen und die Zahl der Matches. Danach werden alle Matches dieses Gasts mit seinem Konto
      verknüpft, Sie werden Freunde, und Sie werden benachrichtigt. Sie können die Verknüpfung jederzeit lösen.
  - §6 bzw. §8: Google Code Scanner (§3.3), keine Kamera-Berechtigung.
  - Ob „Gast übernehmen“ zusätzlich unter Rechtsgrundlage lit. f fällt (Anleger dokumentiert Spiele) wie die Zuordnung, im
    Text so lassen wie bei „Freunde als Mitspieler“.
- **Play-Datensicherheit:**
  - Keine neue Datenart durch Einladungen. Nutzer-IDs und Freundesliste sind schon erklärt (`play-console.md` §6.6).
  - Keine Kamera-Berechtigung, also auch kein Eintrag „Fotos/Videos“.
  - Offen ist allein die ML-Kit-Telemetrie des Code Scanners (§3.3). Ohne In-App-Scanner ändert sich nichts.

## 5. UX

### 5.1 Screens

- **Freunde-Tab → Freunde, unter „Dein Freundescode“: neuer Block „Freunde einladen“** mit „Link teilen“, „QR-Code zeigen“
  und „QR-Code scannen“ (nur Android mit Play-Diensten).
- **QR-Bildschirm (Bottom-Sheet):**
  - großer QR, darunter Restzeit („Noch 9:41“) und „Neuer Code“
  - bei Ablauf ausgegraut mit „Abgelaufen“
  - Bildschirmhelligkeit bleibt unverändert, keine Extra-Logik
- **Feld „Freund hinzufügen“:** erkennt eingefügte Einladungslinks und öffnet die Vorschau statt der Suche.
- **Hinweisleiste „Einladung wartet“** über dem Freunde-Tab bzw. im Konto-Screen, solange der Zugang nicht `READY` ist.
- **Einlöse-Dialog Freund / Claim** mit Vorschau, danach Snackbar mit dem Ergebnis.
- **Gästeliste:** Menü „Einladungslink senden“ und „Einladung zurückziehen“, Zeile „Einladung offen bis …“.
- Barrierefreiheit: Der QR hat eine `contentDescription` („QR-Code für deine Einladung“). Alle Aktionen gibt es auch
  als Text-Link, „Link teilen“ reicht ohne QR.

### 5.2 Texte (`ui-phone`, `values/` EN + `values-de/`)

| Schlüssel | DE | EN |
|---|---|---|
| `invite_title` | Freunde einladen | Invite friends |
| `invite_share_link` | Link teilen | Share link |
| `invite_show_qr` | QR-Code zeigen | Show QR code |
| `invite_scan_qr` | QR-Code scannen | Scan QR code |
| `invite_share_text` | Lass uns auf Advantly befreundet sein: %1$s | Let's be friends on Advantly: %1$s |
| `invite_qr_hint` | Lass diesen Code mit der Kamera oder in Advantly scannen. Einmal nutzbar. | Scan this code with the camera or in Advantly. Works once. |
| `invite_qr_remaining` | Noch %1$s | %1$s left |
| `invite_qr_expired` | Abgelaufen | Expired |
| `invite_qr_new` | Neuer Code | New code |
| `invite_qr_description` | QR-Code für deine Einladung | QR code for your invitation |
| `invite_pending` | Eine Einladung wartet. Melde dich an, um sie anzunehmen. | An invitation is waiting. Sign in to accept it. |
| `invite_needs_account` | Einladungen kannst du nur mit Konto annehmen. | You need an account to accept invitations. |
| `invite_friend_title` | Freundschaft mit %1$s? | Become friends with %1$s? |
| `invite_friend_text` | %1$s hat dich eingeladen. Als Freunde seht ihr die Matches, die ihr teilt, und könnt euch in Matches eintragen. | %1$s invited you. As friends you see the matches you share and can add each other to matches. |
| `invite_accept` | Annehmen | Accept |
| `invite_decline` | Nicht jetzt | Not now |
| `invite_claim_title` | Bist du %1$s? | Are you %1$s? |
| `invite_claim_text` | %1$s hat dich in %2$d Matches als „%3$s“ eingetragen. Wenn du annimmst, erscheinen sie in deinem Verlauf und zählen für deine Statistik. Ihr seid dann befreundet, und %1$s sieht, dass du sie übernommen hast. | %1$s added you to %2$d matches as “%3$s”. If you accept, they appear in your history and count towards your stats. You'll become friends, and %1$s will see that you took them over. |
| `invite_claim_accept` | Ja, das bin ich | Yes, that's me |
| `invite_claim_decline` | Nein | No |
| `invite_done_friend` | Du bist jetzt mit %1$s befreundet. | You're now friends with %1$s. |
| `invite_done_claim` | %1$d Matches übernommen. Du bist jetzt mit %2$s befreundet. | %1$d matches taken over. You're now friends with %2$s. |
| `invite_already_friends` | Ihr seid schon befreundet. | You're already friends. |
| `invite_error_invalid` | Diese Einladung ist nicht mehr gültig. | This invitation is no longer valid. |
| `invite_error_expired` | Diese Einladung ist abgelaufen. Bitte um eine neue. | This invitation has expired. Ask for a new one. |
| `invite_error_used` | Diese Einladung wurde schon benutzt. | This invitation has already been used. |
| `invite_error_revoked` | Diese Einladung wurde zurückgezogen. | This invitation was withdrawn. |
| `invite_error_own` | Das ist deine eigene Einladung. | This is your own invitation. |
| `invite_error_claimed` | %1$s wurde schon übernommen. | %1$s has already been taken over. |
| `invite_error_too_many` | Du hast zu viele offene Einladungen. Ziehe alte zurück oder warte, bis sie ablaufen. | You have too many open invitations. Withdraw old ones or wait until they expire. |
| `invite_scan_not_advantly` | Kein Advantly-Code | Not an Advantly code |
| `invite_scan_preparing` | Scanner wird vorbereitet. Versuche es gleich noch einmal. | Scanner is getting ready. Try again in a moment. |
| `guest_invite_send` | Einladungslink senden | Send invitation link |
| `guest_invite_confirm_text` | Wer den Link öffnet, übernimmt alle Matches von %1$s und wird dein Freund. Schick ihn nur an die Person, die %1$s ist. | Whoever opens the link takes over all matches of %1$s and becomes your friend. Only send it to the person who is %1$s. |
| `guest_invite_share_text` | Ich habe dich auf Advantly als „%1$s“ eingetragen. Öffne den Link, um deine Matches zu übernehmen (gültig bis %2$s): %3$s | I added you on Advantly as “%1$s”. Open the link to take over your matches (valid until %2$s): %3$s |
| `guest_invite_open` | Einladung offen bis %1$s | Invitation open until %1$s |
| `guest_invite_revoke` | Einladung zurückziehen | Withdraw invitation |

- **Android-Push** (`androidApp/src/main/res/values*/strings.xml`):
  - `push_player_claimed`: „%1$s hat %2$s übernommen“ / “%1$s took over %2$s”
  - `push_player_claimed_neutral`: „Ein Gast wurde übernommen“ / “A guest was taken over”
  - Den Gastnamen setzt die App lokal ein, der Push enthält nur IDs.
- **Fallback-Seite** (zweisprachig auf einer Seite):
  - „Du wurdest zu Advantly eingeladen / You've been invited to Advantly“
  - „1. App installieren 2. Konto anlegen 3. Diesen Link noch einmal antippen“
  - „Bei Google Play laden / Get it on Google Play“
  - „Link kopieren / Copy link“
  - „Für iPhone folgt Advantly später. / Advantly for iPhone is coming later.“
  - Pilot-Satz: „Während des Pilots nur für eingeladene Tester. / During the pilot, for invited testers only.“

## 6. Tests

- **pgTAP `490_einladungen.test.sql` (neu):**
  - `preview_invite`: jeder Status, d. h. `ok`, `already_friends`, `already_claimed`, `own`, `expired`, `used`,
    `revoked`. Dazu `invalid` für unbekannt, blockiert in beide Richtungen, Ersteller in Löschung, gelöschter Gast und
    redigierter Gast. `invalid` liefert keine weiteren Felder. Felder je Art.
  - `redeem_invite`:
    - gelöschter Gast → `invalid_invite` (S3)
    - Freundes-Link bei bestehender Freundschaft → `alreadyFriends`, `used_at` leer, keine Outbox-Zeile (S4)
    - Claim mit Freundschaft schon vorhanden
    - Claim überspringt Matches, in denen der Einlöser schon steht
  - `create_invite`:
    - zweiter Claim-Link zieht den ersten zurück, der erste liefert dann `revoked` (S5)
    - 51. offene Einladung → `too_many_open_invites` (S6)
    - TTL 10 min ergibt `expires_at` ≈ jetzt + 10 min
  - `revoke_invite`: nur der Ersteller, nur offene Links, danach `revoked`.
  - Aufräumjob löscht nur alte Zeilen (S7). `player_claimed` erscheint als Push-Art (S8, wie 410).
  - Rechte: `anon` darf nichts ausführen. `token_hash` bleibt für `authenticated` unlesbar (wie 120). Beide neuen RPCs
    verlangen die Cloud-Bindung (wie 170).
  - **070, 100, 410, 461, 470 bleiben grün.**
- **HTTP-Tests `cloud-sync`** (`InvitesHttpTest`, Vorlage `GuestLinksHttpTest`):
  - Rumpf und Name je RPC
  - Antworten dekodieren (alle `status`-Werte), Fehlercodes durchreichen, ungültige Antworten → `cloud_response_invalid`
  - Kontowechsel: kein Netzaufruf
- **Parser-Tests (`presentation-auth`, commonTest):**
  - gültig: Fragment, `?t=`, nackter Token, Groß- und Kleinschreibung, Leerzeichen
  - abgelehnt: fremder Host, `http`, Länge 63/65, Nicht-Hex, leerer Fragment
- **ViewModel-Tests:**
  - Link kommt abgemeldet an → bleibt über Anmeldung, Onboarding und Zustimmung → Vorschau → Annehmen → Ergebnis →
    Link verworfen
  - Ablehnen verwirft den Link, ohne Netzaufruf zum Einlösen
  - Altersgate ohne Konto → `invite_needs_account`
  - Kontowechsel verwirft den Link
  - alle Fehlerstatus, Rate-Limit, offline (Link bleibt)
  - QR: Erneuern nach Ablauf und nach `friend_accepted`, kein Erneuern im Takt
  - Gästeliste: offener Link sichtbar, Zurückziehen, neuer Link ersetzt alten
- **QR-Encoder (`ui-phone`, desktopTest):**
  - Rundlauf mit `zxing-core` als reiner Testabhängigkeit, für Längen 1, 23, 87 und 150 sowie Grenzen je Version
  - eine Golden-Matrix für einen festen Link (gegen versehentliche Änderungen)
- **UI-Tests (`desktopTest`, DE und EN):**
  - Block „Freunde einladen“, QR-Sheet mit Restzeit und „Neuer Code“
  - beide Einlöse-Dialoge mit Platzhaltern, Fehlertexte
  - Gastmenü-Einträge nur unter den richtigen Bedingungen (bekannt, unverknüpft, nicht redigiert)
  - `StringResourcesParityTest` grün
- **Android (`androidApp/src/test`, JUnit 4/Robolectric):**
  - VIEW-Intent mit `https://advantly.eu/i/#…` landet als Einladungsziel in der App, auch bei `onNewIntent`
  - fremde URLs werden ignoriert
  - Push `player_claimed` erzeugt den richtigen Text (wie `PushNotificationsTest`)
  - Lint `MissingTranslation` grün
- **Deep-Link-Prüfung von Hand** (Play-Installation aus dem internen Track, nicht seitenladen, wegen pairip):
  - `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://advantly.eu&relation=delegate_permission/common.handle_all_urls`
    listet `eu.advantly` mit beiden Fingerabdrücken
  - `adb shell pm get-app-links eu.advantly` zeigt `advantly.eu: verified`
  - `adb shell am start -W -a android.intent.action.VIEW -d "https://advantly.eu/i/#<token>"` öffnet die App
  - Link aus WhatsApp und QR mit der Pixel-Kamera öffnen die App
  - Fallback-Seite im Browser ohne App
- **Feldtest mit zwei Konten** (Pixel 6 + zweites Gerät bzw. Emulator mit Play):
  - QR in unter 10 s (Abnahmekriterium P2-D)
  - Claim von „Test1“ mit Anja inklusive frischer Installation und Onboarding
  - Rücknahme durch Alex

## 7. Release-Zuschnitt, Aufwand, Risiken

### 7.1 Zuschnitt (ein Feature-Release, drei Lieferschritte)

1. **Migration (Server)** S1–S8 + pgTAP 490. Abwärtskompatibel, geht **vor** allem anderen auf Staging.
2. **`site/`-PR** (nur mit ausdrücklichem Auftrag):
   - `.well-known/assetlinks.json`
   - `i/index.html`
   - App-Datenschutzerklärung DE/EN als PRIVACY 10 + Katalogeintrag
   Muss live sein, **bevor** die App ausgerollt wird, weil Android beim Installieren bzw. Aktualisieren verifiziert.
   Die Datenschutz-Fassung wird mit dem App-Rollout aktiv (Ablauf wie in den bisherigen Releases).
3. **App 0.7.0 (Build 18):** `cloud-sync`, `presentation-auth`, `ui-phone`, `androidApp`. Handy und Uhr aus demselben
   Commit bauen (Uhr ohne Änderung). „Was ist neu?“ (`whats_new_18_*`, DE/EN): „Freunde per QR-Code oder Link
   einladen“, „Gäste übernehmen ihre Matches per Link“.

Freund und Claim zusammen ausliefern. Sie teilen Linkformat, Parser, Website, Dialog und Datenschutz-Fassung. Getrennt
bräuchte es zwei Datenschutz-Fassungen.

Später, eigene Releases: Install-Referrer (falls der Feldtest Hänger zeigt, E6), iOS mit Universal Links (§3.6).

### 7.2 Aufwand (Skala wie p2-plan)

| Teil | Aufwand |
|---|---|
| Migration + pgTAP 490 | M |
| `site/` (assetlinks, Fallback-Seite) | S |
| Datenschutztext DE/EN + Katalog | S (plus Abstimmung mit dir) |
| `cloud-sync` Port + HTTP-Tests | S |
| `presentation-auth` (Parser, ausstehender Link, Vorschau, QR-Zustand, Gäste) | M |
| `ui-phone` (QR-Encoder + Tests, Sheet, Dialoge, Gastmenü, Strings) | M |
| `androidApp` (Intent-Filter, Code Scanner, Push-Art) | S |
| Gerätetest App Links + Feldtest | S |
| **Gesamt** | **M–L** |

### 7.3 Risiken

- **App-Link-Verifikation schlägt fehl** (falscher Fingerabdruck, Content-Type, Weiterleitung, `www`):
  - Folge: Links öffnen den Browser. Die Fallback-Seite und das Einfügen in „Freund hinzufügen“ fangen das ab.
  - Gegenmittel: vorher mit der Digital-Asset-Links-API prüfen, mit Play-Build testen.
- **Pilot-Track:** Eingeladene ohne Testerstatus können nicht installieren. Gegenmittel: Seitentext, Tester vorher
  eintragen.
- **Erneute Zustimmung aller** (PRIVACY 10): kurze Reibung beim nächsten Start. Wenn möglich mit einer ohnehin anstehenden
  Änderung bündeln.
- **Fehler im QR-Encoder:** ungültige Codes. Gegenmittel: Rundlauf mit `zxing` im Test, Gerätetest mit zwei Kameras.
- **Code Scanner:** fehlt ohne Play-Dienste, lädt beim ersten Mal nach, ML-Kit-Telemetrie. Gegenmittel: Knopf
  ausblenden, Hinweis anzeigen, Systemkamera als Weg, Prüfung nach §3.3.
- **Weitergeleitete Claim-Links:** falsche Person übernimmt. Gegenmittel: Vorschau, Push an den Anleger, Lösen, einmalige
  Nutzung, ein Link je Gast.
- **Zusammenspiel mit 0.6.4** (Kontolöschung der übernehmenden Person): 0.6.4 behandelt beanspruchte Gäste schon wie
  zugeordnete. pgTAP 470 deckt das ab, 490 ergänzt einen Claim-Fall.
- **Verlorener Link bei Prozessende während des Onboardings:** kein Datenverlust, die Person tippt den Link erneut an.

## 8. Offene Entscheidungen

| # | Frage | Empfehlung |
|---|---|---|
| E1 | Linkformat | `https://advantly.eu/i/#<token>`: Token im Fragment, ein Typ für Freund und Claim (§3.1). |
| E2 | Auftrag für `site/` (assetlinks, `/i/`, Datenschutz DE/EN) | **Ja, als eigener PR vor dem App-Release.** Ohne ihn gibt es keine App Links und keinen Weg ohne App. `pages.yml` bleibt unverändert. |
| E3 | Neue Datenschutz-Fassung (PRIVACY 10, alle bestätigen neu) | **Ja**, weil Freundschaft ohne Anfrage und „Gast übernehmen“ in §4.3 fehlen. Wenn möglich mit einer anderen anstehenden Textänderung bündeln. |
| E4 | In-App-Scanner (Google Code Scanner) oder nur Systemkamera | **Code Scanner**, aber nur wenn die ML-Kit-Offenlegung keine neue Datenart in der Play-Datensicherheit erzwingt. Sonst nur Systemkamera. |
| E5 | QR einmal oder mehrfach (z. B. 10 Personen in 10 min) nutzbar | **Einmal**: Das ist Serverstand und ohne Schemaänderung. Nach jedem Einlösen zeigt die App automatisch einen neuen Code. |
| E6 | Install-Referrer (Link übersteht die Installation) | **Erst nicht.** Fallback-Seite „nach der Installation erneut antippen“ und Einfügen in der App. Nachrüsten, wenn der Feldtest Hänger zeigt. Dann prüfen, dass der Token so über Google Play läuft. |
| E7 | Gültigkeit der Links | Freund und Claim **14 Tage** (Serverstand), QR **10 Minuten** (03 nannte 5, 10 ist für Neuinstallationen gnädiger). |
| E8 | Push an den Anleger beim Claim (`player_claimed`) | **Ja**, er ist der wichtigste Schutz gegen weitergeleitete Links. |
| E9 | Dauerhafter Freundescode-Link/QR `advantly.eu/f/<code>` (03 §6.1) | **Nicht bauen.** Der Code als Text reicht. Einladungslinks decken den Rest ab. |
| E10 | Vorschau vor Kontoanlage (anonymer Endpunkt mit Alias) | **Nein.** Ohne Konto bleibt der Text generisch, Namen erst nach der Anmeldung. Kein anonymer RPC, kein zusätzliches Rate-Limit nötig. |
| E11 | Inhalt der Claim-Vorschau | Alias des Anlegers, Gastname und Anzahl der Matches. Keine Mitspielernamen und keine Ergebnisse vor dem Annehmen. |
| E12 | Aufbewahrung von Einladungen | **30 Tage** nach Ablauf bzw. Nutzung löschen (S7), so in die Datenschutzerklärung. |
| E13 | Versionsnummer | 0.7.0 (Build 18) nach 0.6.4, weil es ein neues Feature mit neuer Datenschutz-Fassung ist. |
