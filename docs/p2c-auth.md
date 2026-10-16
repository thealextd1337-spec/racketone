# P2-C – Auth und Onboarding (Android-Handy)

Stand: 16.10.2026. Keine Konfiguration gebucht, keine Secrets hinterlegt und kein staging-Deploy ausgeführt.
Freigabe für Pilot-Builds mit Konten bleibt am Datenschutz-/Store-Gate P2-K hängen.

## Umsetzung und Grenzen

- Optionaler Kontobereich im Profil: Wohnsitzland und Geburtstag → Altersgate → Google Credential Manager
  oder E-Mail-Code → öffentlicher Alias, optional eindeutiger Benutzername. Lokales Zählen braucht kein Konto.
- AT ab 14, DE/CH/LI ab 16; andere Länder noch nicht verfügbar. CH/LI rechtlich vor Pilot bestätigen (F24).
- `shared/cloud-sync`: `CloudAuthPort`, `SupabasePhoneAuth`; `presentation/auth`: `AuthViewModel`;
  `ui-phone/auth`: Compose-Kontobereich; Android-Host injiziert Konfiguration und sicheren Speicher.
- Kein Apple-/iOS-Login in dieser Lieferung. Der Port und der plattformunabhängige Flow erlauben später native
  iOS-Credentials; auf iOS wird noch kein Auth-Client erzeugt. Uhr und Wear-Login bleiben unverändert (P2-N).
- P1-Daten werden nicht umgeschrieben, gelöscht oder beim Login neu angelegt. `register_device` und `sync_push`
  können vorhandene IDs und Event-Logs bereits übernehmen; neue Server-Import-RPCs wären redundant.
  Upload, Self-Aliase, Kontowechsel-Zuordnung und Wiederherstellung auf Gerät zwei liefert P2-B. P2-C allein erfüllt
  daher noch nicht das paketübergreifende Abnahmeszenario „lokale Matches auf dem zweiten Gerät“.

## Ticket-Zuordnung und Sicherheitsgrenze

`issue_age_ticket(country, birth_date, device_secret)` prüft `country_age_rules` serverseitig. Das Kalenderdatum
kommt aus der Wohnsitzzeitzone (Wien/Berlin/Zürich/Vaduz), unabhängig von DB- und Gerätezeitzone.
Ein am 29.02. Geborener erreicht die Grenze in Nicht-Schaltjahren am 01.03. Das Datum bleibt ein Funktionsparameter;
keine Tabelle, Auth-Metadaten, Session oder SavedState erhalten das Geburtsdatum. Request-Bodies und SQL-Parameter für diese
RPC dürfen auch in eigenen Gateway-/Debug-/Analytics-Logs nicht aufgezeichnet werden.

Erfolg liefert ein Ticket aus zwei UUIDv4 (244 Zufallsbits), gültig zehn Minuten. Die DB hält ausschließlich dessen
SHA-256, einen Hash des zufälligen Installationsgeheimnisses, Land, Prüfzeit, angewandte Grenze, Ablauf und vorübergehend
gehashte Identitätsbindung und Verbrauchszeit. Es ist eine risikobasierte Selbstauskunft, kein Identitäts-/Ausweisnachweis.

1. **OTP:** `bind_age_ticket(ticket, 'email', email)` bindet per Ticketbesitz an die normalisierte E-Mail.
   `signInWith(OTP)` übermittelt das Ticket als `data.age_ticket`. Der Hook vergleicht Ticket-Hash **und** Bindung
   mit `user.email` und dem durch GoTrue gesetzten `app_metadata.provider = email`.
   Bei OTP entsteht das unbestätigte Konto beim Codeversand, aber erst nach bestandenem Altersgate;
   eine authentifizierte Sitzung gibt es erst nach Codeprüfung. E-Mail-Bestätigung ist eingeschaltet.
2. **Google:** Benutzerdefinierte `IDToken.Config.data` werden vom ID-Token-Endpunkt ignoriert (SDK-Quellcode 3.8.0).
   Deshalb kein Ticket in vermeintlich vertrauenswürdigen Client-Metadaten und keine IP als Identitätsbindung.
   Credential Manager liefert nach dem Altersgate einen ID-Token mit Nonce. Der Client liest dessen `sub` nur als
   Routingwert und bindet das Ticket an `('google', sub)`, bevor er das gesamte Token an Supabase übergibt.
   Supabase prüft Signatur, Aussteller, Audience, Ablauf und Nonce. Im Hook kommt die `sub` aus diesen verifizierten
   Google-Claims. Bei abweichendem Provider oder anderer `sub` gibt es keine passende Bindung.
   Eine selbst erstellte JWT-Payload reicht nicht: Der Auth-Endpunkt muss das Token zuerst akzeptieren.
3. **Hook:** `public.before_user_created(jsonb)` ist nur für `supabase_auth_admin` ausführbar. Unter derselben
   Transaktionssperre wie die übrigen Schreib-RPCs markiert er genau ein ungenutztes, nicht abgelaufenes Ticket
   als verbraucht. Ohne Treffer gibt er HTTP 403 / `age_ticket_required` zurück; kein `auth.users`-Eintrag.
   Gleichzeitige Versuche können denselben Nachweis nicht zweimal verbrauchen.
4. Der vorhandene AFTER-INSERT-Trigger übernimmt Land/Zeit/Grenze aus dem serverseitigen Ticket in
   `profile_settings` und entfernt dessen kurzlebige Bindung. Die Zuordnung erfolgt über Provider und Identität,
   bei OTP zusätzlich über den Ticket-Hash. **Die Hook-UUID ist dafür ungeeignet:** GoTrue erzeugt für externe
   Provider zunächst ein vorläufiges User-Objekt und beim späteren Insert eine neue UUID. Ein Unique-Index erlaubt
   nur ein gebundenes Ticket pro Identität, einschließlich bereits vom Hook verbrauchter, noch nicht übernommener
   Nachweise. Eine neue Bindung überschreibt diesen Nachweis nicht; ohne Insert läuft er nach zehn Minuten ab.
   `finish_onboarding(alias, username)` braucht diesen
   Nachweis und keinen Geburtstag. `my_auth_profile()` unterscheidet Onboarding, aktives Konto und Löschantrag.
   Der alte `complete_onboarding(country,birth_date,…)` bleibt als Funktion für administrative
   Spike-Kompatibilität bestehen, ist aber für `anon` und `authenticated` ausdrücklich gesperrt.
   Damit kann auch ein ungeprüftes Profil nicht über einen zweiten Client-RPC aktiviert werden.

Für Google werden weder eine Geräte-ID aus Metadaten noch eine unverifizierte E-Mail als Beweis vertraut.
Das Installationsgeheimnis ist ausschließlich Missbrauchsbremse; Zurücksetzen der App ist keine starke
Geräteattestierung. Kennt jemand ein fremdes Google-`sub`, kann er mit einer eigenen bestandenen Selbstauskunft
höchstens eine kurzlebige Bindung dafür vorbereiten, ohne sich als diese Person anmelden zu können. Die Person
muss weiterhin selbst einen gültigen Google-Token vorlegen. Absichtliche Falschangaben zum Alter verhindert diese
Selbstauskunft nicht (F24). Metadaten allein können keine Tickets erzeugen oder auf andere Provider übertragen.

Budgets: zehn Aufrufe je IP und Installation pro festem Ein-Minuten-Fenster ab Erstaufruf, zusätzlich 100 global
pro Minute. Auch fachliche Ablehnungen verbrauchen Budget (JSON-Fehler, kein Transaktionsrollback).
Untergrenze sperrt die Installation 24 Stunden; erfolgreiche neue Ausstellung widerruft vorherige offene Tickets.
`X-Real-IP` muss vom vertrauenswürdigen Gateway gesetzt/überschrieben werden. Fehlt sie, schlägt die Ausstellung
mit `gateway_ip_missing` geschlossen fehl. **Vor staging-Freigabe prüfen**, dass ein selbst gesetzter Header nicht
übernommen wird; andernfalls Gateway entsprechend konfigurieren. Das globale Budget schützt auch bei gespooften
oder wechselnden IPs vor unbeschränktem Tabellenwachstum, kann aber als globaler Engpass missbraucht werden.
Zusätzlich Auth-/Gateway-Rate-Limits bzw. CAPTCHA nach Lastbedarf aktivieren; nicht als Ersatz für den DB-Hook.
Der stündliche Job `advantly-purge-age-gate` entfernt abgelaufene Bindungen (nach einer Stunde) und Budgetdaten
(nach 25 Stunden und Ablauf einer eventuellen Sperre). Ohne pg_cron extern stündlich als Owner ausführen.

Quellen: [Before User Created](https://supabase.com/docs/guides/auth/auth-hooks/before-user-created-hook),
[Google bei Supabase](https://supabase.com/docs/guides/auth/social-login/auth-google),
[Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg).

## Sichere Session und Fehlerfälle

`PhoneAuthStorage` ersetzt den SDK-Standard-SessionManager durch EncryptedSharedPreferences mit AES256-GCM,
Schlüssel aus Android Keystore. Das zufällige Installationsgeheimnis liegt im selben verschlüsselten Speicher und
bleibt beim Logout erhalten. Auth-Datei `shared_prefs/advantly_auth.xml` ist für Auto Backup **und** Device Transfer
in `backup_rules.xml` / `data_extraction_rules.xml` ausgeschlossen. Keine Tokens in Logs oder Fehlermeldungen;
kein Fallback auf Klartext, kein Kopieren von Refresh-Tokens zur Uhr. Bei Keystore-Initialisierungsfehler bleibt der
Kontobereich deaktiviert und lokales Zählen verfügbar. EncryptedSharedPreferences ist von Android inzwischen
als deprecated markiert; der explizite SessionManager-Port ermöglicht später einen Keystore-/DataStore-Ersatz.

- Ticket abgelaufen/verbraucht/anderweitig gebunden: neue Altersabfrage. Wiederholte Bindung desselben Tickets
  an dieselbe Identität ist idempotent, damit ein Netzfehler vor dem Codeversand wiederholbar bleibt.
  „Weiter ohne Konto“/Neustart widerruft ein noch offenes Ticket per `cancel_age_ticket`; offline wird es lokal
  verworfen und läuft serverseitig spätestens nach zehn Minuten ab. Eine bestehende Sitzung bleibt erhalten.
- Ungültiger Code: wiederholen oder neu anmelden. Onboarding-Nutzer werden nach 24 h serverseitig aufgeräumt.
- `profile_missing`: „Neu anmelden“ entfernt die lokale Sitzung; danach Altersgate und regulärer Login.
- Offline: lokale App bleibt bedienbar; Cloud-Aufrufe zeigen einen wiederholbaren Fehler. Logout löscht die
  lokale Sitzung auch bei Netzfehler; ein fehlgeschlagener globaler Serverwiderruf kann offline nicht nachgeholt
  werden. Bereits ausgegebene Access-Tokens bleiben grundsätzlich bis Ablauf gültig (P2-N-Sitzungswiderruf separat).
- Geburtsdatum, OTP und Ticket werden nicht per `rememberSaveable` oder `SavedStateHandle` gesichert.
  Das OTP-Ticket wird als Auth-Metadatum übergeben und kann dadurch in Auth-Nutzerdaten und der verschlüsselten
  Session enthalten sein. Nach Einmalverbrauch ist es wertlos; das Geburtsdatum wird dort nie übertragen.
  Rotation kann die Alters-/Codeeingabe leeren; der ViewModel-Flow und die sichere Sitzung bleiben erhalten.
- Alias: 1–40 druckbare Zeichen, nicht eindeutig. Benutzername optional, normalisiert, 3–20 Zeichen `[a-z0-9_.]`,
  eindeutig durch DB-Unique-Constraint, reserviert: admin, advantly, support, moderator, system, deleted, null, official.

## Lokale Konfiguration ohne Secrets im Repo

In die ignorierte `local.properties` oder benutzerspezifische `$GRADLE_USER_HOME/gradle.properties`:

```properties
advantly.supabase.url=https://<PROJECT-REF>.supabase.co
advantly.supabase.key=<PUBLISHABLE-ODER-ANON-KEY>
advantly.google.webClientId=<WEB-CLIENT-ID>.apps.googleusercontent.com
```

URL, Publishable/Anon-Key und Web-Client-ID sind öffentliche Clientkonfiguration, keine Serverzugangsdaten.
Service-Role-Key, Google-Client-Secret und SMTP-Passwort gehören niemals in Gradle-/BuildConfig-Felder oder APKs.
Ohne URL/Key zeigt der normale Build keinen Kontobereich; Google-Button fehlt ohne Web-Client-ID.

Lokal: `npx supabase start`, Hook wird aus `[auth.hook.before_user_created]` registriert; Signup bleibt eingeschaltet.
Zuerst in der App ein Ticket holen, dann OTP über Mailpit prüfen. Für Android-Emulator lokale API über einen
TLS-fähigen lokalen Proxy oder HTTPS-Tunnel erreichbar machen; HTTP-Cleartext wird nicht global freigeschaltet.
Keine generelle Hook-Ausnahme für lokale Registrierung. SQL-Test-Fixtures als DB-Owner sind davon getrennt.
Ein Google-ID-Token-Test verlangt einen korrekt konfigurierten Provider; lokal ist dieser standardmäßig deaktiviert.

## Staging im Supabase-Dashboard (manuell)

1. Orchestrator deployt Migrationen später über den vorhandenen staging-Workflow. **Nicht hier deployen.**
2. Authentication → Hooks → Before User Created → Postgres-Funktion `public.before_user_created` aktivieren.
   Prüfen, dass nur `supabase_auth_admin` Ausführungsrecht hat. Dashboard-Konfiguration ist nicht automatisch
   durch eine Migration auf das gehostete Projekt übertragen.
3. Anonymous Sign-ins aus, Signup an, E-Mail-Confirmations an, Secure Email Change an,
   Refresh Token Rotation an. Kein Phone-/Password-UI. Auth-Logs auf Ticketfehler und Budget-Ablehnungen prüfen,
   ohne Request-Bodies oder JWTs zu protokollieren.
4. Authentication → Providers → Google aktivieren. Web-Client-ID und Web-Client-Secret aus dem nächsten Abschnitt
   eintragen; Web-Audience sowie benötigte Android-Client-IDs in der zulässigen Client-ID-Liste berücksichtigen.
   „Skip nonce checks“ **nicht** einschalten. Apple bleibt aus.
5. Manual Identity Linking aktivieren (`enable_manual_linking = true` lokal). Supabase verknüpft gleiche
   **verifizierte** E-Mail-Adressen automatisch; keine eigenen SQL-Merges von `auth.users` oder `auth.identities`.
   In Kontoeinstellungen kann ein angemeldeter Nutzer Google nativ über `linkIdentityWithIdToken` hinzufügen,
   auch bei anderer E-Mail. Bereits anderswo verknüpfte Identitäten dürfen nicht übernommen werden; Auth meldet
   den Konflikt. Von Google zu E-Mail mit derselben Adresse: regulärer OTP-Login nutzt dasselbe Konto.
   Beliebige zweite E-Mail-Adressen/Unlink-UI sind nicht Teil dieser Lieferung.
6. Authentication → Email → Custom SMTP nach Anbieterentscheidung konfigurieren (Host, Port, Benutzer, Passwort,
   Absender `noreply@<BESTÄTIGTE-DOMAIN>`). SPF, DKIM und DMARC veröffentlichen, Versanddomain verifizieren.
   Confirm-Signup- und Magic-Link-Templates enthalten den **Code** `{{ .Token }}`, keinen für diesen Flow nötigen
   Login-Link. OTP-Länge sechs oder acht Stellen, Ablauf passend begrenzen (z. B. zehn Minuten).
   Versandlimit nach Pilotgröße wählen; Supabase-Standardmail ist kein Produktionsversand.
7. URL Configuration: Site URL `https://advantly.eu`; nur konkrete Redirects freischalten.
   Für den aktuellen nativen Google-ID-Token-/Code-Flow ist **kein App-Redirect/Intent-Filter erforderlich**.
   Google-Web-OAuth verwendet `https://<PROJECT-REF>.supabase.co/auth/v1/callback` als Callback.
   Künftiges Browser-OAuth/Apple-Linking erst mit PKCE und verifiziertem HTTPS-App-Link freischalten:
   z. B. `https://advantly.eu/auth/callback`, zugehörige `assetlinks.json`, Android `autoVerify` sowie
   iOS Associated Domains/Universal Links. Keine ungeprüften Wildcards oder bloßes Custom-Scheme als Freigabe.
8. RPC über das tatsächliche staging-Gateway prüfen: echte IP vorhanden, gefälschtes `X-Real-IP` überschrieben;
   Ticket-Body nicht im Logging. In `cron.job` muss `advantly-purge-age-gate` aktiv sein, Zeitplan `29 * * * *`,
   Befehl `set statement_timeout = '60s'; select private.purge_age_gate();` (als Owner prüfen).
   Dann Pilot-Abnahme unten durchführen.

Quelle: [Identity Linking](https://supabase.com/docs/guides/auth/auth-identity-linking),
[Custom SMTP](https://supabase.com/docs/guides/auth/auth-smtp),
[E-Mail-OTP](https://supabase.com/docs/guides/auth/passwordless-login/auth-magic-link).

## Google Cloud OAuth Schritt für Schritt

1. In Google Cloud ein separates staging-Projekt wählen/anlegen. Google Auth Platform → Branding:
   App „Advantly“, Supportkontakt, Website und bereits vorhandene Datenschutz-/Nutzungsbedingungen-URLs.
   Zielgruppe konfigurieren; im Testmodus die Pilotkonten als Test Users hinzufügen.
2. Data Access: nur `openid`, `email`, `profile`. Keine unnötigen Google-API-Berechtigungen.
3. Clients → Create Client → **Android**, Paketname `eu.advantly`, SHA-1 des Debug-Zertifikats eintragen.
   Für jede Signatur ein eigener Android-Client: Debug, Upload-/lokale Release-Signatur und insbesondere das
   **Play-App-Signing-Zertifikat**, mit dem Play die ausgelieferten APKs signiert.
4. Debug-Fingerprint (bekanntes Standardpasswort ist kein privates Upload-Key-Passwort):

   ```powershell
   & "$env:JAVA_HOME/bin/keytool.exe" -list -v -keystore "$env:USERPROFILE/.android/debug.keystore" -alias androiddebugkey -storepass android
   ```

5. Upload-Key: Pfad und Alias selbst aus deiner lokalen Konfiguration ermitteln. Passwort liegt ausschließlich
   in `H:/dev/keys/advantly-upload.properties`; diese Datei weder ausgeben noch ins Repo kopieren.
   Interaktive Passwortabfrage verwenden, keine Passwörter als Kommandozeilenargumente:

   ```powershell
   & "$env:JAVA_HOME/bin/keytool.exe" -list -v -keystore "<UPLOAD-KEYSTORE-PFAD>" -alias "<UPLOAD-ALIAS>"
   ```

   SHA-1 in OAuth übernehmen; Play-App-Signing-SHA-1 aus Play Console → App Integrity/Signierung ergänzen.
   SHA-256 wird zusätzlich für verifizierte App Links benötigt (späterer Browser-Flow).
6. Clients → Create Client → **Web application**. Redirect URI exakt
   `https://<PROJECT-REF>.supabase.co/auth/v1/callback`; für staging und Produktion getrennte Clients verwenden.
   Der native Credential Manager erhält diese **Web-Client-ID** als Server-Client-ID/Audience, nicht die Android-ID.
   Web-Secret ausschließlich im Supabase-Dashboard hinterlegen. Android-Client hat kein im APK benötigtes Secret.
7. Supabase-Provider wie oben einrichten. Client-ID in lokale Gradle-Properties eintragen, App neu bauen.
   Auf Pixel mit Testkonto anmelden; Nonce wird zufällig erzeugt, SHA-256 an Google und Rohwert an Supabase gegeben.

## Entscheidungsvorlage: EU-SMTP mit AVV

**Empfehlung zur Auswahl: Scaleway TEM**, sofern Domainfreigabe, SMTP-Zugang und Zustelltest für den Pilot passen.
Begründung: auf Transaktionsversand fokussiert; Vertragspaket enthält DPA und spezifische TEM-Bedingungen.
Keine Buchung und keine Preiszusage. EU-Anbieter bedeutet nicht automatisch ausschließlich EU-Unterauftragnehmer
oder EU-Empfänger; aktuelle Unterauftragnehmer, Aufbewahrung von Versandlogs, Supportzugriff und Löschfristen
vor Vertragsabschluss prüfen. Zustellung an z. B. Gmail verlässt zwangsläufig den eigenen EU-Hostingbereich.

- **Scaleway TEM:** [Produkt](https://www.scaleway.com/en/transactional-email-tem/),
  [Verträge inkl. DPA/TEM](https://www.scaleway.com/en/contracts/),
  [Unterauftragnehmer](https://www.scaleway.com/en/subprocessorlist/).
  Prüfen: Versanddomain-Freigabe, SMTP-Verfügbarkeit/Region, Pilotlimits, Log-Aufbewahrung und Produktionsfreigabe.
- **Brevo:** pragmatische Alternative bei einfacherem Onboarding;
  [EU-Speicherorte](https://help.brevo.com/hc/en-us/articles/360001005510-Data-storage-location),
  [DSGVO/AVV](https://help.brevo.com/hc/en-us/articles/360001258744-How-does-Brevo-comply-with-the-GDPR).
  Prüfen: Transaktions-SMTP im gewählten Tarif, Unterauftragnehmer/Google Cloud, Log-Retention und Tracking aus.
- **Mailjet (Sinch):** weitere Alternative mit Transaktions-SMTP;
  [Datenschutz/AVV](https://www.mailjet.com/legal/security-privacy/),
  [DSGVO](https://documentation.mailjet.com/hc/en-us/articles/360043225253-Is-Mailjet-GDPR-compliant).
  Prüfen: konkrete Vertragsgesellschaft, aktuelle EU-Verarbeitung/Transfers, Limits und Log-Aufbewahrung.

Entscheidung vor Pilot: Anbieter + Tarif, AVV-Version/Datum, gewählte Region und Unterauftragnehmer,
Absenderdomain, Zustelltests, Retention, Löschprozess und Betriebsverantwortlicher im VVT festhalten.
Öffnungs-/Klicktracking für Auth-Mails deaktivieren; keine Geburtstage oder Tickets in E-Mails.

## Abnahme und Prüfungen

Lokale Verifikation am 16.10.2026 im Worktree `p2c-auth`:

- JDK 21.0.12, `GRADLE_USER_HOME=H:/dev/gradle`, `gradlew.bat check --console=plain --max-workers=2`: grün
  (640 Tasks). Unter Git Bash entspricht dies `./gradlew check --console=plain --max-workers=2` mit dem
  vorgegebenen `JAVA_HOME`. macOS-exklusive Ausführung/Link-Schritte bleiben wie üblich übersprungen.
- Neue Tests: vier Adaptertests jeweils JVM + Android-Host, zehn Auth-ViewModel-Tests jeweils JVM + Android-Host,
  drei Compose-Desktop-UI-Tests. Sämtliche Auth-Netzaufrufe sind simuliert.
- Postgres-17-Harness, alle Migrationen frisch angewendet: 420/422 pgTAP-Prüfungen grün, davon 66/66 in Test 140.
  Ausschließlich Test 110, Prüfungen 15/16 (installiertes pg_cron + aktiver Onboarding-Cron), scheitert an der im
  Windows-Harness fehlenden Erweiterung. Der vollständige Supabase-Stack/CI einschließlich Cron bleibt offen;
  die Pflichtprüfungen wurden weder übersprungen noch abgeschwächt.
- `config.toml` geparst: Hook aktiv, Google-Nonce-Prüfung an. Geänderte Dateien mit LF, `git diff --check` sauber.

Automatisch ohne Netz in Kotlin-Tests: Altersgate-Reihenfolge, Kalenderdaten, nicht unterstützte Länder,
OTP → Alias → Konto, Google/Linking, abgelaufenes Ticket, Profile-Missing, Username-Kollision, Offline/Logout,
Abbruch; Compose-Desktop prüft Altersgate und lokale Ausstiegsmöglichkeit. Der echte supabase-kt-Adapter läuft
gegen Ktor MockEngine: POST-Reihenfolge, Ticketbindung vor OTP/Google, keine Geburtstage am Auth-Endpunkt,
Nonce-Weitergabe, bereinigte Fehler und lokale Sitzungslöschung bei fehlendem Server. DB-Test `140_auth_age_gate.test.sql`:
Länder-/Geburtstagsgrenzen, Wohnsitzzeitzone, Schaltjahr, Ticketbindung, Hook-Rechte, Annahme/Ablehnung,
Einmalverbrauch, Ablauf, Datensparsamkeit, Reservierungen/Unique-Namen und Budgets. Dazu Regressionen für
abweichende Google-Hook-/Insert-UUID, idempotente Bindung, Umbindungsverbot, Abbruchwiderruf und den gesperrten
Spike-RPC. Der Cron-Harness benötigt echte `pg_cron`-Unterstützung; die zwei Pflichtprüfungen in Test 110 bleiben
ohne diese Erweiterung bewusst rot. Keine Testausnahme ersetzt die Supabase-Stack-Abnahme.

GoTrue-Verhalten geprüft anhand des [externen Hook-Aufrufs](https://github.com/supabase/auth/blob/master/internal/api/hooks.go)
und des [ID-Token-Endpunkts](https://github.com/supabase/auth/blob/master/internal/api/token_oidc.go).

Vor manueller Freigabe auf staging/Pixel:

1. Ohne Konfiguration und offline P1-Match starten, beenden und Verlauf öffnen.
2. Untergrenze/anderes Land: Auth-Userzahl unverändert, 24-h-Gerätesperre bleibt nach Neustart bestehen.
3. Direktes Signup ohne Ticket sowie manipulierte Metadaten ablehnen. Gültige OTP-Anmeldung mit eigener SMTP
   und Google-ID-Token-Anmeldung jeweils bis Alias testen; Ticket-Replay und abgelaufenes Ticket ablehnen.
4. Google → OTP mit derselben verifizierten E-Mail: identische Profil-ID, keine zweiten Konten.
   OTP → manuell Google hinzufügen (auch andere E-Mail): Sitzung/Profil-ID unverändert, Konflikt sicher ablehnen.
5. App-Neustart/Rotation: Session wieder da, keine Tokens in Logcat/Backups; Logout online und offline prüfen.
6. Onboarding-Abbruch >24 h: `profile_missing`/erneuter Login, ohne lokale Daten zu verlieren.
7. P2-K vor Pilot abschließen. P2-B für Upload/zweites Gerät, P2-N für Uhr-Sessions separat abnehmen.

Der echte Supabase-Stack (GoTrue, Gateway, pg_cron, Hook-Payload und Providerkonfiguration) wird durch den
Postgres-Harness nicht ersetzt. Diese Integration und echte Pixel-Anmeldung sind ohne eingerichtetes staging
weiterhin manuell zu prüfen.
