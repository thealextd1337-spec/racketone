# Google Play Console – Checkliste für den ersten Release (W1-G)

Schritt-für-Schritt-Anleitung für das private Entwicklerkonto (Sitz Österreich), App **Advantly**, Phone- und Wear-OS-App.
Stand: 15.10.2026. Die Play-Regeln wurden an diesem Tag in der Play-Console-Hilfe bzw. auf developer.android.com geprüft (Quellen am Ende, [P1]–[P12]).
Menüpfade beziehen sich auf die deutschsprachige Console. Wenn ein Menüpunkt anders heißt, gilt die Funktion, nicht der Wortlaut.

**Reihenfolge auf einen Blick**
1. App anlegen (§1) → 2. Upload-Key und AABs (§2, §3) → 3. Wear OS als Formfaktor (§4) → 4. Interner Test (§5)
→ 5. App-Inhalte und Erklärungen (§6) → 6. Store-Eintrag (§7) → 7. Geschlossener Test mit ≥ 12 Testern über 14 Tage (§8) → 8. Produktion.

> **Stand 15.10.2026:** Engine, Session/Sync, Phone-Assistent, Live-Score, Verlauf und Datenschutzlinks sind auf `main`.
> Die Integration Phone ↔ Session und Wear ↔ Session sowie die Feldtests stehen noch aus. Danach zunächst intern über Play
> installieren, den **geschlossenen Pilot** erst nach dem Android-P1-Teilgate starten. Gates, Zeitplan und Produktionsentscheidung:
> [Veröffentlichungsplan](veroeffentlichungsplan.md). Geschlossene Tests durchlaufen die Play-Prüfung; die 14 Tage mit der echten App testen.
>
> **Freigabekriterium vor dem ersten Upload, auch für den internen Test:** Die Datenschutzerklärung muss in Handy- **und** Uhr-App
> erreichbar sein (§6.1). Die Links sind umgesetzt; vor Upload in beiden tatsächlichen Release-Builds prüfen.
>
> **Stand 27.10.2026 (erster Upload mit Konto und Cloud):** Der erste Upload enthält P2 (Konto per E-Mail-Code, Cloud-Sicherung,
> Export, Kontolöschung, Support-Meldungen). Die Phase-1-Angaben unten sind dafür ersetzt: §6.1 (Zustimmungsfassungen),
> §6.2 (App-Zugriff), §6.6 (Data safety mit Erhebung), §6.7. Vorher müssen die neuen Rechtsseiten live und die Fassungen freigegeben sein.

---

## 1. App anlegen

Play Console → **Alle Apps** → **App erstellen**:

| Feld | Wert |
|---|---|
| App-Name | `Advantly` (der Store-Titel wird in §7 festgelegt und darf länger sein) |
| Standardsprache | Deutsch (Deutschland) – de-DE |
| App oder Spiel | App |
| Kostenlos oder kostenpflichtig | Kostenlos (lässt sich später **nicht** mehr auf kostenpflichtig umstellen; In-App-Abos bleiben möglich) |
| Erklärungen | Programmrichtlinien für Entwickler und US-Exportgesetze bestätigen |

Danach unter **Einstellungen der App** bzw. **Store-Einstellungen**:
- **Kategorie:** App → **Sport**
- **Tags:** z. B. „Sport“, „Tennis“, „Fitness-Tracking“ (max. 5, aus der Liste der Console)
- **Kontakt:** E-Mail `alxpro@cakamper.at` (wird im Store öffentlich angezeigt), Website `https://advantly.eu`
- Telefonnummer: optional, nicht angeben

## 2. Upload-Key und Play App Signing

Neue Apps nutzen **Play App Signing** automatisch [P1]. Google erzeugt und verwahrt den eigentlichen **App-Signaturschlüssel**.
Wir signieren nur mit dem **Upload-Key**. Der erste hochgeladene AAB registriert dieses Zertifikat als Upload-Zertifikat.
Phone und Wear sind **eine** App (`eu.advantly`) und bekommen deshalb denselben App-Signaturschlüssel. Das braucht die Wear Data Layer API [P9].

| Was | Wert |
|---|---|
| Keystore | `H:\dev\keys\advantly-upload.jks` (PKCS12, RSA 4096, SHA384withRSA) |
| Alias | `advantly-upload` |
| Passwörter | nur in `H:\dev\keys\advantly-upload.properties` (Store- und Key-Passwort identisch, wie bei PKCS12 üblich) |
| Inhaber | `CN=Carlo-Alexander Kamper, O=Advantly, C=AT` |
| Gültig | 15.10.2026 bis 02.03.2054 (10.000 Tage) |
| **SHA-1** | `6E:70:4D:20:10:50:5C:D5:B2:C0:C1:4F:98:8D:B4:8D:F9:1C:D3:55` |
| **SHA-256** | `B6:CC:1A:07:EB:C7:91:39:8B:92:CD:AF:B2:A3:12:61:7E:89:DA:F0:E9:E9:BC:92:EB:AB:8B:FE:32:21:D0:64` |

- [ ] **Sichern:** Keystore und Properties-Datei zusätzlich offline ablegen (z. B. verschlüsselter USB-Stick) und das Passwort im Passwortmanager speichern.
  Geht der Upload-Key verloren, lässt er sich über den Play-Support zurücksetzen, aber das dauert. Ein neues PEM-Zertifikat für so einen Reset erzeugst du mit:
  `keytool -export -rfc -keystore H:\dev\keys\advantly-upload.jks -alias advantly-upload -file advantly-upload-cert.pem` (nie ins Repo).
- [ ] Nach dem ersten Upload: **Testen und veröffentlichen → App-Integrität → App-Signatur** öffnen und die Fingerabdrücke des **App-Signaturschlüssels** notieren.
  Diese (nicht die des Upload-Keys) brauchst du später für Google-APIs, z. B. Sign-In in P2.
- Seitenlade-Builds (Debug oder lokal signiertes Release) und Play-Installationen haben **verschiedene Signaturen**.
  Uhr und Handy finden sich per Data Layer nur, wenn beide Apps aus derselben Quelle stammen.

## 3. AABs bauen und prüfen

Einmalig in `local.properties` des Checkouts eintragen (nicht eingecheckt):
```
advantly.signing.file=H:/dev/keys/advantly-upload.properties
```
Alternativ (CI) die Umgebungsvariablen `ADVANTLY_UPLOAD_KEYSTORE`, `ADVANTLY_UPLOAD_STORE_PASSWORD`, `ADVANTLY_UPLOAD_KEY_ALIAS`, `ADVANTLY_UPLOAD_KEY_PASSWORD` setzen.
Ohne beides baut `release` **unsigniert**. Ist eine Konfiguration nur teilweise gesetzt, bricht der Build mit einer Meldung ab.

1. [ ] `advantly.buildNumber` in `gradle.properties` für neue Binary-Uploads erhöhen, auch im internen Test. Der erste Upload darf mit `1` laufen; Promotion desselben vorhandenen Artefakts erhöht nichts ([Versionsführung](veroeffentlichungsplan.md#versionen-und-tags)).
2. [ ] `gradlew.bat bundleAllRelease`
3. [ ] Ergebnis:
   - Phone: `androidApp\build\outputs\bundle\release\androidApp-release.aab` (versionCode `buildNumber*10+1`, minSdk 28, targetSdk 36)
   - Wear: `wearApp\build\outputs\bundle\release\wearApp-release.aab` (versionCode `buildNumber*10+2`, minSdk 34, targetSdk 36)
4. [ ] Optional mit bundletool prüfen. Die Version liegt außerhalb des Repos in `H:\dev\tools\bundletool\bundletool-all-1.18.3.jar` (SHA-256 gegen den GitHub-Release geprüft):
   ```
   java -jar H:\dev\tools\bundletool\bundletool-all-1.18.3.jar validate --bundle=wearApp\build\outputs\bundle\release\wearApp-release.aab
   java -jar H:\dev\tools\bundletool\bundletool-all-1.18.3.jar dump manifest --bundle=wearApp\build\outputs\bundle\release\wearApp-release.aab
   keytool -printcert -jarfile wearApp\build\outputs\bundle\release\wearApp-release.aab
   ```
   Erwartet: `package="eu.advantly"`, `uses-feature android.hardware.type.watch`, `com.google.android.wearable.standalone=true` (nur Wear),
   keine `LinkDebugActivity`, Zertifikat-SHA-256 wie in §2.

Das R8-Mapping liegt im AAB (`BUNDLE-METADATA`), deshalb zeigt Play Abstürze automatisch deobfuskiert an.

Bei W1-G geprüft (buildNumber 1): Phone versionCode 11, Wear 12, beide `eu.advantly`, beide mit dem Upload-Key signiert.
`android_wear_capabilities` ist im Release-Ressourcenbaum enthalten, das S5-Diagnose-Log und der Link-Debug-Screen sind nicht im Release-Code.
Die Phone-App wurde auf einem Pixel 9 Pro XL gestartet, ohne Absturz. Auf der Uhr steht der Laufzeittest noch aus.

## 4. Wear OS als Formfaktor hinzufügen

Laut Play-Hilfe und Wear-Doku [P2][P3]. Das sind zwei getrennte Schritte zu verschiedenen Zeitpunkten.

**4a. Formfaktor einrichten (jetzt, vor dem ersten Wear-Upload):**
1. [ ] **Testen und veröffentlichen → Erweiterte Einstellungen → Formfaktoren → Formfaktor hinzufügen → Wear OS**.
2. [ ] **Eigenen Release-Track für Wear OS verwenden** auswählen. Wear-AABs laufen dann über eigene Tracks („Wear OS only“), getrennt vom Smartphone-Track.
3. [ ] Im Store-Eintrag mindestens **einen Wear-OS-Screenshot** hochladen [P3]: quadratisch, mindestens 384 × 384 Pixel, nur die App-UI, ohne Uhrengehäuse, Transparenz oder Maske ([Assetregeln](https://support.google.com/googleplay/android-developer/answer/9866151)).
   Aufnehmen kannst du ihn z. B. mit `adb -s <uhr> exec-out screencap -p > wear.png`.
4. [ ] Den Wear-AAB in einen Test-Track des Wear-Formfaktors hochladen (interner Test, §5).

**4b. Wear-OS-Opt-in (später, erst im geschlossenen Test, §8):**
5. [ ] Das Opt-in setzt einen Release im **geschlossenen** Test-Track voraus. Wörtlich: „Once you have a release in your closed testing track, you can opt in to Wear OS and agree to the review policy“ [P3].
   Dann **Erweiterte Einstellungen → Wear OS → Opt-in für Wear OS und Zustimmung zur Überprüfungsrichtlinie**. Danach prüft Google die Uhr-App gegen die Wear-OS-Qualitätsrichtlinien [P3].
   „Wear OS“ im Store-Text als verständliche Plattformangabe beibehalten (§7); die ausdrückliche Erwähnungspflicht wurde laut [Qualitätsrichtlinie/History](https://developer.android.com/docs/quality-guidelines/wear-app-quality) am 12.03.2026 entfernt. Der alte Hinweis in [P3] ist insoweit überholt.

Technische Voraussetzungen (erfüllt): gleiches Package `eu.advantly`, eindeutige versionCodes je Formfaktor (…1/…2), `uses-feature android.hardware.type.watch` ohne `required=false`, Standalone-Metadaten [P3].
Ziel-API: Seit 31.08.2026 brauchen neue Phone-Apps targetSdk ≥ 36, Wear-Apps ≥ 35 [P4]. Wir liegen bei 36/36.
64-Bit-Pflicht für Wear OS seit 15.09.2026 [P5]: Die einzige native Bibliothek (`libandroidx.graphics.path.so`) ist auch für `arm64-v8a` enthalten.

## 5. Interner Test (zuerst)

Der interne Test ist für maximal 100 Tester gedacht und eignet sich zum Ausprobieren der Play-Installation von Handy und Uhr.
Updates im internen Test werden normalerweise **nicht** vorab geprüft und sind sofort verfügbar. Es gibt aber Ausnahmen [P13]:
- Ist der **erste Release-Rollout** einer vollständig eingerichteten App ein interner Test, wird er **vor der Veröffentlichung geprüft**.
  Das gilt nicht für interne Test-Apps, die noch nicht vollständig eingerichtet sind.
- Nach einer **abgelehnten** Einreichung wird auch die nächste Einreichung geprüft.
- Interne Updates können **nachträglich** geprüft werden.
- Prüfungen dauern einige Stunden bis zu sieben Tage, in Ausnahmefällen länger [P13]. Für den ersten Upload deshalb Puffer einplanen.

1. [ ] **Testen und veröffentlichen → Testen → Interner Test → Tester:** E-Mail-Liste anlegen (z. B. „Advantly intern“) mit den **Google-Konto-Adressen** der Tester, inklusive deiner eigenen.
2. [ ] **Smartphone-Track:** Neuer Release → `androidApp-release.aab` hochladen. Versionshinweis z. B. „0.1.0 (1) – erster interner Test“ → Release prüfen → Einführen.
3. [ ] **Wear-OS-Track:** Formfaktor oben auf der Testseite auf **Wear OS** umstellen → Interner Test → dieselbe Testerliste → `wearApp-release.aab` hochladen → einführen.
4. [ ] Unter **Tester** den **Link zur Teilnahme** (Opt-in-URL) kopieren und an die Tester schicken. Jeder Tester öffnet ihn mit dem gelisteten Google-Konto und nimmt teil.
5. [ ] Installation: Auf dem Handy über den Play Store. Auf der Uhr über den Play Store der Uhr (Suche „Advantly“) oder über den Handy-Store („Auf weiteren Geräten installieren“).
   Uhr und Handy müssen mit demselben Google-Konto angemeldet sein.
6. [ ] Rauchtest: Uhr-App starten → Match auf der Uhr → Puls-Berechtigung → Display aus und wieder an → Spielstand stimmt, Ongoing Activity führt zurück.

Falls die Console beim Einführen fehlende Angaben meldet (z. B. Datenschutzerklärung oder Content-Rating), zuerst §6 ausfüllen.

## 6. App-Inhalte und Erklärungen

Pfad: **Richtlinien → App-Inhalte**. Alle Punkte müssen vor dem geschlossenen Test erledigt sein.

### 6.1 Datenschutzerklärung
- [ ] URL: **`https://advantly.eu/app-datenschutz.html`** (englische Fassung: `https://advantly.eu/en/app-privacy.html`)
- [ ] Vorher prüfen, ob die Seite live ist. Sie wird erst nach dem Merge nach `main` über `pages.yml` veröffentlicht.
- Die Seite ist öffentlich, ohne Geo-Sperre und als HTML erreichbar. Das verlangt Play, und für Health Connect muss es später **dieselbe** URL sein [P6].
- [ ] **Verbindliches Freigabekriterium vor dem ersten Upload: Die Datenschutzerklärung ist auch in den Apps erreichbar.**
  Google verlangt beides: „a privacy policy link in the designated field within Play Console“ **und** „a privacy policy link or text within the app itself“ [P14].
  - **Handy:** Profil bzw. Einstellungen → „Datenschutz“ (DE) / „Privacy policy“ (EN) öffnet `https://advantly.eu/app-datenschutz.html` bzw. die englische URL im Browser.
  - **Uhr (Wear OS):** Hinweis „Datenschutz“ am Ende der Startliste: Matchdaten gehen ans gekoppelte Handy, Puls und Schritte nur wie unter
    „Puls & Schritte“ eingestellt; Details in der Handy-App unter Profil › Datenschutz.
  - **Status 27.10.2026:** In beiden Apps umgesetzt (Handy seit W1-G, Uhr in `HomeScreen`). Vor Upload in beiden Release-Builds prüfen.
- [ ] **Rechtsseiten live** (über `pages.yml` nach Merge nach `main`): `app-datenschutz.html`, `en/app-privacy.html` (Version 2),
  `nutzungsbedingungen.html`, `en/terms.html` (Version 1), `konto-loeschen.html`, `en/delete-account.html`.
  Die Texte sind Entwürfe; die mit `PRÜFEN` markierten Stellen (Anbieterverträge, Logs, Backups, CH/LI) vor der Freigabe klären (Pilot-Gate R2).
- [ ] **Zustimmungsfassungen freigeben** – erst wenn die Seiten live und geprüft sind, sonst bleibt die Cloud in der App gesperrt
  (`consent_unavailable`). Freigegebene Fassungen sind unveränderlich; jede spätere Textänderung braucht eine neue Fassung.
  Im SQL-Editor des jeweiligen Projekts (zuerst staging):
  ```sql
  begin;
  update private.consent_versions set is_current = false where is_current;
  insert into private.consent_versions(document_type, version, is_current) values
    ('TERMS', '1', true), ('PRIVACY', '2', true);
  insert into private.consent_translations(document_type, version, language, url) values
    ('TERMS', '1', 'de', 'https://advantly.eu/nutzungsbedingungen.html'),
    ('TERMS', '1', 'en', 'https://advantly.eu/en/terms.html'),
    ('PRIVACY', '2', 'de', 'https://advantly.eu/app-datenschutz.html'),
    ('PRIVACY', '2', 'en', 'https://advantly.eu/en/app-privacy.html');
  update private.consent_versions set approved_at = now()
    where (document_type, version) in (('TERMS', '1'), ('PRIVACY', '2'));
  commit;
  ```
  **0.2.0 (Datenschutzerklärung Version 3, Freunde und geteilte Matches):** nur PRIVACY wechselt, TERMS 1 bleibt aktuell.
  Erst nach Merge und live geschalteter Seite ausführen; danach bestätigen bestehende Nutzer beim nächsten Öffnen neu.
  ```sql
  begin;
  update private.consent_versions set is_current = false where document_type = 'PRIVACY' and is_current;
  insert into private.consent_versions(document_type, version, is_current) values ('PRIVACY', '3', true);
  insert into private.consent_translations(document_type, version, language, url) values
    ('PRIVACY', '3', 'de', 'https://advantly.eu/app-datenschutz.html'),
    ('PRIVACY', '3', 'en', 'https://advantly.eu/en/app-privacy.html');
  update private.consent_versions set approved_at = now() where (document_type, version) = ('PRIVACY', '3');
  commit;
  ```
  Voraussetzung: Migration `20261018000200_consent_versions.sql` ist auf dem Projekt (Release-Gate).

### 6.2 App-Zugriff
- [ ] **„Alle oder einige Funktionen sind eingeschränkt“** wählen: Zählen funktioniert ohne Konto; Konto und Cloud-Sicherung brauchen eine Anmeldung.
- [ ] Anleitung für die Prüfer (DE/EN): „Zählen, Verlauf und Uhr-App funktionieren ohne Konto. Für Konto und Cloud-Sicherung: Profil → Konto →
  eigene E-Mail-Adresse eingeben, Land Österreich oder Deutschland wählen, ein Geburtsdatum ab 16 Jahren eingeben, Code aus der E-Mail eintragen,
  Nutzungsbedingungen und Datenschutzerklärung annehmen. Pulsanzeige nur auf einer Wear-OS-Uhr mit Herzfrequenzsensor.“
  Es gibt keine festen Prüferzugangsdaten; der Mail-Code geht an die Adresse des Prüfers.

### 6.3 Werbung
- [ ] **Nein**, die App enthält keine Werbung.
- [ ] **Werbe-ID:** **Nein**, die App nutzt keine Werbe-ID. Geprüft: Das zusammengeführte Manifest beider AABs enthält kein `com.google.android.gms.permission.AD_ID`.

### 6.4 Einstufung des Inhalts (IARC-Fragebogen)
Ablauf laut [P7]: Starten → E-Mail für IARC angeben (`alxpro@cakamper.at`) → Kategorie wählen → Fragebogen ausfüllen → Zusammenfassung prüfen → Einreichen.
- [ ] **Kategorie:** „Alle anderen App-Typen“ (Utility/Produktivität). Nicht „Spiel“ und nicht „Soziale Netzwerke“.
- [ ] **Empfohlene Antworten (Phase 1):** Gewalt, sexuelle Inhalte, Vulgärsprache, Drogen, Alkohol, Tabak, Glücksspiel und anstößiger Humor jeweils **Nein**.
  Interaktion bzw. Austausch zwischen Nutzern: 0.1.0 **Nein**; ab 0.2.0 **Ja** (Freunde, geteilte Matches mit Anzeigenamen, kein Chat). Standortweitergabe: **Nein**.
  Digitale Käufe: **Nein**. Uneingeschränkter Internetzugang bzw. Webbrowser: **Nein**.
- Erwartetes Ergebnis: niedrigste Stufe (z. B. PEGI 3, USK 0, IARC 3+). Das Ergebnis berechnet die Console.
- **Phase 2 neu ausfüllen:** Freunde/Teilen = Nutzerinteraktion, In-App-Abo = digitale Käufe.

### 6.5 Zielgruppe und Inhalte
**Mindestalter: 14 Jahre** (Nutzerentscheidung vom 15.10.2026, ersetzt die 16 aus 03 §8.2).
- Rechtsgrundlage: Nach Art. 8 Abs. 1 DSGVO kann ein Kind bei Diensten der Informationsgesellschaft ab 16 selbst einwilligen. Die Mitgliedstaaten dürfen die Grenze auf bis zu 13 senken.
  Österreich hat sie in **§ 4 Abs. 4 DSG** auf **14** gesenkt. Deutschland hat keine abweichende Regel, dort gilt **16**.
- Phase 1 stützt sich nicht auf eine Einwilligung nach Art. 6 Abs. 1 lit. a, Art. 8 greift deshalb nur am Rand. Die Grenze wird relevant, sobald in P2/P3 Einwilligungen eingeholt werden (Konto, Health).
  Dann muss die Altersabfrage bei der Registrierung **je Land** prüfen (z. B. AT 14, DE 16) oder einheitlich 16 verlangen. Vor P2 juristisch klären.
- Die Datenschutzerklärung (DE/EN, §8) nennt 14 Jahre.

Die Play Console bietet nur feste Altersgruppen an: 5 und jünger, 6–8, 9–12, **13–15**, **16–17**, **18 und älter** [P15]. „14+“ lässt sich nicht exakt abbilden.
- Die Gruppe 13–15 „may be considered to include children in some locales“ [P15]. Wenn mindestens eine ausgewählte Gruppe Kinder umfasst, gelten die Anforderungen der **Familienrichtlinie** [P15].
- **Empfehlung: nur 16–17 und 18 und älter auswählen**, also 13–15 **nicht**. Gründe:
  1. Die Zielgruppe in der Console beschreibt, für wen die App gestaltet ist, und ist keine Altersgrenze. 14- und 15-Jährige dürfen Advantly trotzdem nutzen, das Mindestalter (14) steht in der Datenschutzerklärung.
  2. 13–15 würde ausdrücklich auch 13-Jährige unter dem Mindestalter ansprechen.
  3. Mit 13–15 kann je nach Land die Familienrichtlinie greifen, mit Zusatzprüfung und Zusatzpflichten [P15]. Für eine Sport-Utility ohne kindgerichtete Gestaltung bringt das keinen Vorteil.
- Alternative, falls Jugendliche ausdrücklich Zielgruppe sein sollen: 13–15 zusätzlich auswählen und die Familienrichtlinie erfüllen. Die App hat keine Werbung und keine SDKs, damit wäre das machbar, aber mit längerer Prüfung zu rechnen.
- [ ] **Altersgruppen:** **16–17** und **18 und älter**.
- [ ] „Könnte die App für Kinder interessant sein?“: **Nein** (Sport-Utility ohne kindgerichtete Gestaltung).

### 6.6 Datensicherheit (Data safety)
Googles Definitionen [P8]:
- **„Erheben“ (collect)** heißt, Daten von der App vom Gerät des Nutzers zu übertragen.
- Drei Ebenen auseinanderhalten: (1) **tatsächliche Übertragung** vom Gerät, (2) was im **Formular** anzugeben ist, (3) was **öffentlich** im Store angezeigt wird.
  - **Nur auf dem Gerät verarbeitet** („only processed locally on the user's device and not sent off device“): nicht übertragen, also **gar nicht** im Formular anzugeben.
    Auch lokal **gespeicherte** Daten sind keine Erhebung, solange sie das Gerät nicht verlassen.
  - **Ende-zu-Ende-verschlüsselt übertragen** („unreadable by you or anyone other than the sender and recipient“, keine Zwischenstelle und auch nicht der Entwickler kann lesen):
    zwar übertragen, aber **nicht** anzugeben.
  - **Ephemer verarbeitet** (nur im Arbeitsspeicher, nur so lange wie für die Anfrage nötig): Das betrifft Daten, die das Gerät **verlassen**.
    Sie **sind im Formular anzugeben** (Frage „Is this data processed ephemerally?“), werden aber **nicht öffentlich** im Data-safety-Abschnitt angezeigt.
    „Ephemer“ ist also keine Ausnahme vom Ausfüllen, sondern nur von der Anzeige. Profilbildung oder Werbung gilt nie als ephemer.
- **„Teilen“ (share)** heißt Weitergabe an Dritte.
- Auch Apps ohne Datenerhebung müssen das Formular ausfüllen und eine Datenschutz-URL angeben [P8].

Einordnung ab dem ersten Upload (P2, Stand 27.10.2026). Nur die Handy-App hat `INTERNET`; die Uhr-App nicht.
Empfänger ist ausschließlich unser Backend (Supabase mit Cloudflare davor, Mailversand über Resend); das sind Auftragsverarbeiter, also **kein Teilen**.

| Daten | Was passiert | Play-Datentyp | Optional? | Zweck |
|---|---|---|---|---|
| E-Mail-Adresse | Anmeldung per Mail-Code (bzw. Google), optionale Kontaktadresse in Support-Meldungen | Persönliche Infos → E-Mail-Adresse | ja (Konto freiwillig) | App-Funktionalität, Kontoverwaltung, Kommunikation des Entwicklers |
| Alias, Benutzername, Spielernamen | Profil sowie Namen in Matches und Gästen | Persönliche Infos → Name | ja | App-Funktionalität, Kontoverwaltung |
| Konto-ID, Freundescode | Profil | Persönliche Infos → Nutzer-IDs | ja | App-Funktionalität, Kontoverwaltung |
| Land, Altersgrenze, Zeitpunkt der Altersbestätigung (Geburtsdatum nur geprüft, nicht gespeichert) | Altersgate | Persönliche Infos → Sonstige Infos | ja | Kontoverwaltung, Betrugsprävention/Sicherheit/Compliance |
| Installations- und Geräte-IDs (zufällig erzeugt), Plattform | Gerätebindung, Widerruf | Geräte- oder sonstige IDs | ja | App-Funktionalität, Betrugsprävention/Sicherheit |
| Matches (Spielstand, Punkte, Zeitpunkte, optionaler Ort) | Cloud-Sicherung | Fitness → Fitness-Infos (vorsichtige Einordnung; Alternative „App-Aktivität → Andere nutzergenerierte Inhalte“) | ja | App-Funktionalität |
| Support-Meldungen (Titel, Text) | Feedback & Hilfe, auch als Gast | App-Aktivität → Andere nutzergenerierte Inhalte | ja | App-Funktionalität, Kommunikation des Entwicklers |
| Technische Angaben (App-Version, OS, Gerätemodell, Bildschirm, Schriftgröße) | nur per Häkchen in Support-Meldungen | App-Info und -Leistung → Sonstige Leistungsdaten | ja | App-Funktionalität |
| Abgleich Handy ↔ Uhr, Puls und Schritte | Data Layer, Ende-zu-Ende-verschlüsselt [P9]; nicht in die Cloud | – | – | **nicht erhoben** |

- [ ] „Erhebt oder teilt deine App einen der erforderlichen Nutzerdatentypen?“ → **Ja**; Typen und Zwecke wie in der Tabelle, jeweils **erhoben, nicht geteilt**, **nicht ephemer**.
- [ ] „Werden alle Nutzerdaten bei der Übertragung verschlüsselt?“ → **Ja** (TLS).
- [ ] „Können Nutzer das Löschen ihrer Daten anfordern?“ → **Ja**: in der App (Profil → Konto → Kontolöschung beantragen) und per Web-Link
  **`https://advantly.eu/konto-loeschen.html`** (Feld „Link zum Löschen des Kontos“; englisch `https://advantly.eu/en/delete-account.html`).
- IP-Adressen landen nur als ungesalzener Hash (Missbrauchsschutz bei Altersgate und Gastmeldungen) sowie in den Verbindungslogs der Anbieter; daraus wird kein Standort abgeleitet.
- Android-Datensicherung (Phone `allowBackup=true`) ist eine Systemfunktion im Google-Konto des Nutzers und keine Übertragung an uns. In der Datenschutzerklärung (§7) ist sie erwähnt.
- Absturzdaten (Android vitals) erhebt Google über das System bzw. Play mit Zustimmung des Nutzers, nicht unser App-Code. Es ist kein SDK eingebunden.
- **0.2.0 (Freunde, geteilte Matches, Google-Login):** Datentypen bleiben gleich (Alias, Benutzername, Freundescode und
  Freundesliste fallen unter Name/Nutzer-IDs; geteilte Matches unter Fitness-Infos). Die Sichtbarkeit für andere Nutzer der App
  ist **kein „Teilen“** im Sinne von Play (keine Weitergabe an Drittunternehmen). Kontoerstellung zusätzlich **„OAuth“** ankreuzen
  (Google-Login), weiterhin „Nutzername und andere Authentifizierung“ (E-Mail-Code). Die Einstellung „Puls & Schritte“ ist eine
  Präferenz, keine Gesundheitsinfo; Puls- und Schrittwerte bleiben **nicht erhoben**.
- **Neu ausfüllen, sobald** Push, Live-Zuschauer oder Health-Daten in der Cloud dazukommen.
  In P3 kommen Puls und kcal dazu, sobald sie in die Cloud **synchronisiert** werden („Gesundheitsinfo/Fitness-Info“, Zweck App-Funktionalität, nicht geteilt).
  **Rein lokales Speichern** von Puls oder kcal auf Uhr bzw. Handy ist **keine** Erhebung.
  Ob das Schreiben nach Health Connect auf demselben Gerät eine Angabe auslöst, vor P3 prüfen. Unabhängig davon ist Health Connect eigens zu deklarieren (§6.7).

### 6.7 Erklärung zu Gesundheits-Apps (Health apps declaration)
Alle veröffentlichten Apps auf Play müssen sie ausfüllen, ausdrücklich auch für geschlossene/offene Tests [P10]. Vor dem Pilot abschließen und bereits intern vorbereiten; die Quelle nennt interne Tests nicht ausdrücklich.
Die Kategorie „Aktivität und Fitness“ umfasst ausdrücklich „tracking body vitals related to fitness (like heart rate)“ [P10].
- [ ] Auswählen: **Gesundheit und Fitness → Aktivität und Fitness**. **Nicht** „Medizinisch“ und nicht „Medizinprodukt“.
- [ ] Beschreibung (DE/EN, falls ein Feld kommt):
  „Advantly zählt Racketsport-Matches auf Wear-OS-Uhr und Handy. Während eines Matches läuft auf der Uhr eine Trainings-Session (Health Services, Übungstyp Tennis bzw. Badminton/Tischtennis), damit die Zählung bei ausgeschaltetem Display weiterläuft. Die aktuelle Herzfrequenz wird dabei nur angezeigt, auf Wunsch auch auf dem gekoppelten Handy; sie wird nicht gespeichert, nicht in die Cloud übertragen und nicht geteilt. Keine medizinischen Funktionen.“
- **Health Connect** wird in Phase 1 **nicht** genutzt (keine `android.permission.health.*` außer `READ_HEART_RATE` für den Sensor der Uhr).
  Falls die Console wegen `health.READ_HEART_RATE` eine Begründung pro Berechtigung verlangt, diese verwenden:
  - `health.READ_HEART_RATE` / `BODY_SENSORS` (≤ Wear OS 5): „Anzeige der aktuellen Herzfrequenz auf der Uhr während eines vom Nutzer gestarteten Matches. Nur im Vordergrund bzw. während der laufenden Trainings-Session, keine Hintergrundmessung, keine Speicherung.“
  - `ACTIVITY_RECOGNITION`: „Schrittzählung der Trainings-Session über Health Services während eines Matches.“
- Vor P3 (Health Connect: Exercise, Heart Rate, Calories, Steps) neu deklarieren. Dann jeder Datentyp mit nutzersichtbarem Zweck, nur das Minimum (05 §6.2).

### 6.8 Vordergrunddienst-Berechtigungen (FGS-Erklärung)
Apps mit targetSdk ≥ 34 müssen jeden FGS-Typ deklarieren, und zwar mit Beschreibung, Auswirkung bei Verzögerung oder Unterbrechung, **Video-Link** und Anwendungsfall [P11].
Typ `health` ist für Fitness-Apps gedacht, z. B. „an exercise app that tracks a user's exercise“ [P11].
- [ ] **Typ:** Gesundheit (`FOREGROUND_SERVICE_HEALTH`), nur Wear-App (`WorkoutService`).
- [ ] **Anwendungsfall:** den Eintrag zum Erfassen eines Trainings bzw. von Fitnessdaten wählen.
- [ ] **Beschreibung:** „Während eines vom Nutzer gestarteten Matches hält ein Vordergrunddienst die Health-Services-Trainings-Session (ExerciseClient) aktiv. So läuft die Zählung bei ausgeschaltetem Display weiter, die Herzfrequenz wird angezeigt und eine Ongoing Activity führt zurück ins Match. Der Dienst endet mit dem Match.“
- [ ] **Auswirkung bei Unterbrechung:** „Wird der Dienst verzögert oder beendet, bricht die Trainings-Session ab: Puls- und Dauermessung fehlen für den Rest des Matches, und die Uhr kann die App bei ausgeschaltetem Display beenden. Ein laufendes Match wäre nur nach Wiederherstellung fortsetzbar.“
- [ ] **Video** (Pflicht, z. B. nicht gelistetes YouTube-Video oder Google-Drive-Link mit Freigabe „Jeder mit dem Link“):
  App auf der Uhr öffnen → Sportart wählen → Match starten → Berechtigungsdialog → Punkte zählen → Display aus/an → Ongoing-Activity-Symbol → Match beenden (Dienst endet).
  Aufnahme auf der Uhr: `adb -s <uhr> shell screenrecord /sdcard/fgs.mp4` (Strg+C beendet) → `adb -s <uhr> pull /sdcard/fgs.mp4`.
  Alternativ die Uhr am Handgelenk filmen.

### 6.9 Weitere Erklärungen
- [ ] **Behörden-App:** Nein. **Finanzfunktionen:** keine. **Nachrichten-App:** Nein. **Gesundheits-/Medizinprodukt:** Nein.
- [ ] **Exportbeschränkungen bzw. Verschlüsselung:** Die App nutzt nur die Verschlüsselung des Systems (keine eigene Kryptografie).

## 7. Store-Eintrag (Entwurf)

**Wachstum → Store-Präsenz → Haupt-Store-Eintrag.** Die Texte entsprechen der Website (Slogan „Jeder Punkt zählt. Am Handgelenk.“)
und der Positionierung aus 06 §6 („gleichwertig auf Handy und Uhr“, „offline-fest“). Sie enthalten **keine Gesundheits- oder Leistungsversprechen**.
Erwähnt ist nur, was es in Phase 1 gibt. Sportarten und Funktionen vor dem geschlossenen Test mit dem tatsächlichen Android-P1-Integrationsstand aus PLAN abgleichen.

| | Deutsch (de-DE, Standard) | Englisch (en-US) |
|---|---|---|
| Titel (≤ 30) | `Advantly – Racketsport zählen` (29) | `Advantly: Racket Score Keeper` (29) |
| Kurzbeschreibung (≤ 80) | `Tennis, Padel, Pickleball & mehr zählen – auf Wear-OS-Uhr und Handy, offline.` (77) | `Score tennis, padel & pickleball on your Wear OS watch and phone – offline.` (75) |

**Beschreibung (DE)**
```
Jeder Punkt zählt. Am Handgelenk.

Advantly zählt dein Tennis-, Padel-, Pickleball-, Badminton- oder Tischtennis-Match – auf deiner Wear-OS-Uhr oder deinem Android-Handy, auch ohne Netz. Ein Gerät zählt, die anderen zeigen den Stand.

GEBAUT FÜR DEN PLATZ
• Große Tap-Flächen und Vibration statt Hinsehen: eigenes Muster für „Wir“ und „Gegner“
• Rückgängig, auch über Satzgrenzen hinweg
• Dunkle Live-Ansicht mit maximalem Kontrast für Sonne und Halle
• Läuft bei ausgeschaltetem Display weiter – kein Punkt geht verloren

ALLE REGELN, KORREKT
Tiebreak, Match-Tiebreak, No-Ad, Golden und Star Point, Side-Out- und Rally-Scoring, Verlängerung im Badminton, Aufschlagwechsel im Tischtennis – Einzel und Doppel, inklusive Aufschlagreihenfolge.

UHR UND HANDY
Zähle auf der Uhr oder am Handy im Director-Modus. Genau ein Gerät zählt und führt Undo aus; weitere Geräte zeigen den Stand. Mit bestätigter Übergabe wechselst du das Zähler-Gerät. Die Uhr-App funktioniert auch ohne Handy (Wear OS, Pixel Watch und Galaxy Watch ab Wear OS 5).

PULS IM BLICK
Auf der Uhr siehst du während des Matches deine aktuelle Herzfrequenz. Advantly ist kein Medizinprodukt; die Anzeige dient nur deiner Information.

PRIVAT VON ANFANG AN
Keine Werbung, kein Tracking, kein Konto. Deine Matches bleiben auf deinen Geräten.

Advantly ist in aktiver Entwicklung. Als Nächstes kommen Freunde, Statistik mit Satzverlauf und Head-to-Head sowie die Auswertung deiner Herzfrequenz pro Satz.
```

**Description (EN)**
```
Every point counts. On your wrist.

Advantly scores your tennis, padel, pickleball, badminton or table tennis match – on your Wear OS watch and your Android phone, equally and even without a connection.

BUILT FOR THE COURT
• Big tap targets and haptics instead of looking: distinct patterns for "us" and "them"
• Undo, even across set boundaries
• Dark live view with maximum contrast for sunshine and indoor courts
• Keeps running with the display off – no point gets lost

EVERY RULE, DONE RIGHT
Tiebreak, match tiebreak, no-ad, golden and star point, side-out and rally scoring, extended games in badminton, service changes in table tennis – singles and doubles, including serving order.

WATCH AND PHONE
Score on your watch or on your phone in Director mode. One device keeps score and handles undo; other devices display the score. A confirmed handover changes the scoring device. The watch app also works without your phone (Wear OS, Pixel Watch and Galaxy Watch on Wear OS 5 or later).

HEART RATE AT A GLANCE
During the match your watch shows your current heart rate. Advantly is not a medical device; the display is for your information only.

PRIVATE FROM THE START
No ads, no tracking, no account. Your matches stay on your devices.

Advantly is in active development. Coming next: friends, stats with set history and head-to-head, and your heart rate per set.
```

**Grafiken** (Vorlage: `site/assets/icon.svg`, Farben aus 04 §5: Hintergrund `#0b0c0f`, Marke `#ff6a1a`, Gegner `#5b9bff`):
- [ ] App-Symbol 512 × 512 PNG (32 Bit, ohne abgerundete Ecken; Play rundet selbst ab)
- [ ] Vorstellungsgrafik 1024 × 500 PNG/JPG
- [ ] Smartphone-Screenshots: mindestens 2 (z. B. Live-Score und Match-Setup, DE und EN getrennt), aufgenommen mit `adb exec-out screencap -p > phone.png`
- [ ] Wear-OS-Screenshots: mindestens 1, quadratisch, nur die App-UI (§4)
- [ ] Englische Übersetzung als zweite Sprache hinzufügen (**Übersetzungen verwalten → eigene Übersetzungen**)

## 8. Geschlossener Test und Produktionszugang

Für private Entwicklerkonten, die **nach dem 13.11.2023** erstellt wurden, gilt [P12]:
- Vor dem Antrag auf Produktionszugriff ist ein **geschlossener Test mit mindestens 12 Testern** nötig, die **in den letzten 14 Tagen ununterbrochen** teilgenommen haben.
- Wer sich ab- und wieder anmeldet, zählt nur, wenn die 14 Tage am Stück liegen.
- Danach stellst du den **Antrag auf Produktionszugriff** im Dashboard und beantwortest Fragen zum Test, zur App und zur Produktionsbereitschaft.

Schritte:
1. [ ] **Testen → Geschlossener Test → Track erstellen** (z. B. „Beta“) für Smartphone und für den Formfaktor Wear OS.
2. [ ] Tester: eine E-Mail-Liste mit **mindestens 15** Adressen (Puffer für Abmeldungen) oder eine Google Group. Jeder Tester muss über den Link teilnehmen und die App installieren.
3. [ ] Release einführen. Geschlossene Tests werden von Google **geprüft**, deshalb müssen §6 und §7 vollständig sein.
4. [ ] 14 Tage laufen lassen und Feedback sammeln (Test-Feedback-Kanal: E-Mail `alxpro@cakamper.at`).
5. [ ] **Produktionszugriff beantragen.** Erst danach ist ein öffentlicher Release möglich.

Die Hilfe nennt keine zusätzliche Mindestzahl von zwölf Uhren [P12]. Phone- **und** Wear-Track mit derselben Testerliste laufen lassen und Geräteabdeckung gesondert nachweisen; maßgeblich ist der Produktionszugang im Dashboard.

**Produktion:** Nutzerentscheidung vom 15.10.2026: ausschließlich nach P3-Gates, zuerst DACH. Der allererste Produktionsrelease ist **nicht prozentual staffelbar**; Prozentrollouts gelten erst für Updates ([Google-Regel](https://support.google.com/googleplay/android-developer/answer/6346149)). Gates und Staffelung: [Veröffentlichungsplan §2](veroeffentlichungsplan.md#2-stufen-und-gates).

## 9. CI: signierte AABs per GitHub Actions (vorbereitet)

> **Backend-Ziel:** Beide Workflows bauen im Environment `android-release` mit dessen Variablen `ADVANTLY_SUPABASE_URL`,
> `ADVANTLY_SUPABASE_KEY` und `ADVANTLY_GOOGLE_WEB_CLIENT_ID` (öffentliche Client-Werte, keine Secrets). Produktion entsteht
> nur per Promote aus alpha, also läuft **Produktion gegen dasselbe Backend wie der getestete Build**. Stand 27.10.2026 zeigen
> die Variablen auf **staging** (`lgeoafnbluvxoezpftzo`, Google-Projekt `advantly-staging`). Vor dem ersten Produktions-Build
> ein eigenes Produktionsprojekt anlegen und die Variablen umstellen; staging-Builds nie nach Produktion hochstufen.

Workflow `.github/workflows/android-release.yml` (nur `workflow_dispatch`): Er baut `bundleAllRelease` mit dem Upload-Key,
prüft die Signatur, schreibt die Zertifikats-SHA-256 ins Job-Summary und lädt AABs und R8-Mappings als Artefakt hoch (7 Tage).
Die Secrets sind **noch nicht angelegt**. Ohne sie bricht der Workflow sofort mit einer Meldung ab. Einrichtung:

1. [ ] GitHub → Repo → **Settings → Environments → New environment** `android-release`.
   Dort verpflichtend **Deployment branches and tags: Selected branches and tags → Branch `main`**, keine Tags; optional **Required reviewers: du selbst**.
2. [ ] Keystore base64-kodieren (PowerShell), ohne Zeilenumbrüche:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("H:\dev\keys\advantly-upload.jks")) | Set-Clipboard
   ```
3. [ ] Im Environment `android-release` unter **Environment secrets** anlegen:
   | Secret | Wert |
   |---|---|
   | `ADVANTLY_UPLOAD_KEYSTORE_BASE64` | Zwischenablage aus Schritt 2 |
   | `ADVANTLY_UPLOAD_STORE_PASSWORD` | `storePassword` aus `H:\dev\keys\advantly-upload.properties` |
   | `ADVANTLY_UPLOAD_KEY_ALIAS` | `advantly-upload` |
   | `ADVANTLY_UPLOAD_KEY_PASSWORD` | `keyPassword` aus derselben Datei |
4. [ ] Zwischenablage leeren. **Actions → Android-Release (signierte AABs) → Run workflow** auf `main`.

Das Repo ist öffentlich. Secrets sehen nur Workflows dieses Repos, nicht Forks oder Pull Requests aus Forks.
Den Workflow starten können nur Personen mit Schreibrechten. Der Keystore wird nach dem Lauf vom Runner gelöscht.

**Play-Publishing:** Der zusätzliche Workflow `play-publish.yml` nutzt für Testtracks `android-release` und für Produktion das separate Environment `play-production`. Dort sind dein Required Review, die Branch-Regel nur `main` und eigene Secrets Pflicht. Produktion nur per Promotion aus `alpha` nach P3. Schrittweise Einrichtung, gesperrte Ruby-Abhängigkeiten und Pflicht-Erstlauf auf `internal`: [Veröffentlichungsplan §6](veroeffentlichungsplan.md#6-upload-automatisierung-und-versionsführung). Publish-Artefakte bleiben nur einen Tag verfügbar; Zieltrack-Notes und „Notes gegen Build geprüft“ sind Pflicht ([§7](veroeffentlichungsplan.md#7-release-notes-vorlage-und-erster-interner-test)).

## 10. Vor jedem weiteren Upload
- [ ] Für neue Binary-Uploads `advantly.buildNumber` erhöhen (Phone …1 und Wear …2 steigen automatisch mit); bei Promotion desselben Artefakts unverändert lassen
- [ ] `gradlew.bat check bundleAllRelease`
- [ ] Notes gegen Build geprüft (beide Formfaktoren und Sprachen, Zieltrack-Dateien; [Plan §7](veroeffentlichungsplan.md#7-release-notes-vorlage-und-erster-interner-test))
- [ ] Ändern sich Berechtigungen, Datenflüsse oder Health-Typen: §6.6–6.8 und `site/app-datenschutz.html` / `site/en/app-privacy.html` anpassen (Kommentare „ÜBERARBEITEN vor Phase 2/3“).

---

## Quellen (abgerufen 15.10.2026)
- [P1] Play App Signing – https://support.google.com/googleplay/android-developer/answer/9842756
- [P2] Formfaktor-Releases auf eigenen Tracks verwalten – https://support.google.com/googleplay/android-developer/answer/13295490
- [P3] Wear-OS-Apps verpacken und verteilen – https://developer.android.com/training/wearables/packaging
- [P4] Target-API-Anforderungen (ab 31.08.2026: Phone API 36, Wear OS API 35) – https://developer.android.com/google/play/requirements/target-sdk
- [P5] Android Developers Blog, 64-Bit-Pflicht für Wear OS ab 15.09.2026 – https://android-developers.googleblog.com/2026/04/get-your-wear-os-apps-ready-for-64-bit-requirement.html
- [P6] Health-App auf Google Play veröffentlichen (Datenschutz-URL identisch mit Health Connect) – https://developer.android.com/health-and-fitness/health-connect/publish
- [P7] Content-Rating-Fragebogen – https://support.google.com/googleplay/android-developer/answer/188189 und https://support.google.com/googleplay/android-developer/answer/9898843
- [P8] Datensicherheit: Definitionen „collect“/„share“, Ausnahmen, ephemere Verarbeitung im Formular – https://support.google.com/googleplay/android-developer/answer/10787469
- [P9] Wear Data Layer: Übertragung (Bluetooth bzw. Google Cloud Ende-zu-Ende-verschlüsselt), gleiche Paketnamen und Signatur – https://developer.android.com/training/wearables/data/overview
- [P10] Erklärung zu Gesundheits-Apps – https://support.google.com/googleplay/android-developer/answer/14738291
- [P11] Vordergrunddienst-Anforderungen (Deklaration, Video, Typ `health`) – https://support.google.com/googleplay/android-developer/answer/13392821
- [P12] Testanforderungen für neue private Entwicklerkonten (12 Tester, 14 Tage) – https://support.google.com/googleplay/android-developer/answer/14151465
- [P13] Veröffentlichungsstatus und Prüfung (interner Test: Prüfung beim ersten Rollout bzw. nach Ablehnung) – https://support.google.com/googleplay/android-developer/answer/9859654
- [P14] Richtlinie Nutzerdaten (Datenschutzerklärung in der Play Console **und** in der App) – https://support.google.com/googleplay/android-developer/answer/10144311
- [P15] Zielgruppe und Inhalte (Altersgruppen, 13–15 „may be considered to include children in some locales“, Familienrichtlinie) – https://support.google.com/googleplay/android-developer/answer/9867159
- Health-Services-Berechtigungen ab Wear OS 6 (`READ_HEART_RATE`) – https://developer.android.com/health-and-fitness/health-services/permissions
