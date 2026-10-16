# P2-C – Auth und Onboarding (Android-Handy)

Stand: 16.10.2026. Keine Konfiguration gebucht, keine Secrets hinterlegt und kein staging-Deploy ausgeführt.
Freigabe für Pilot-Builds mit Konten bleibt am Datenschutz-/Store-Gate P2-K hängen.

## Umsetzung und Grenzen

- Optionaler Kontobereich im Profil: Wohnsitzland und Geburtstag → Altersgate → Google Credential Manager
  oder E-Mail-Code → öffentlicher Alias, optional eindeutiger Benutzername. Lokales Zählen braucht kein Konto.
- AT ab 14, DE/CH/LI ab 16; andere Länder noch nicht verfügbar. CH/LI rechtlich vor Pilot bestätigen (F24).
- `shared/cloud-sync`: `CloudAuthPort`, `SupabasePhoneAuth`; `shared/presentation-auth`: `AuthViewModel`;
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
3. **Hook:** `public.before_user_created(jsonb)` ist nur für `supabase_auth_admin` ausführbar. Mit einem
   atomaren `UPDATE … WHERE consumed_at IS NULL` markiert er genau ein ungenutztes, nicht abgelaufenes Ticket
   als verbraucht. Ohne Treffer gibt er HTTP 403 / `age_ticket_required` zurück; kein `auth.users`-Eintrag.
   Gleichzeitige Versuche können denselben Nachweis nicht zweimal verbrauchen.
4. Der vorhandene AFTER-INSERT-Trigger übernimmt Land/Zeit/Grenze aus dem serverseitigen Ticket in
   `profile_settings` und entfernt dessen kurzlebige Bindung. Die Zuordnung erfolgt über Provider und Identität,
   bei OTP zusätzlich über den Ticket-Hash. **Die Hook-UUID ist dafür ungeeignet:** GoTrue erzeugt für externe
   Provider zunächst ein vorläufiges User-Objekt und beim späteren Insert eine neue UUID. Ein Unique-Index erlaubt
   nur ein gebundenes Ticket pro Identität, einschließlich bereits vom Hook verbrauchter, noch nicht übernommener
   Nachweise. Neue Tickets ersetzen ältere ungenutzte Bindungen derselben Identität; die Bindung verkürzt die
   Ticketgültigkeit auf höchstens zwei Minuten. Ein verbrauchter Nachweis bleibt für Hook→Insert höchstens eine
   Minute geschützt; danach kann eine neue Altersprüfung einen fehlgeschlagenen Insert ersetzen.
   `finish_onboarding(alias, username)` braucht diesen
   Nachweis und keinen Geburtstag. `my_auth_profile()` unterscheidet Onboarding, aktives Konto und Löschantrag.
   Der alte `complete_onboarding(country,birth_date,…)` bleibt als Funktion für administrative
   Spike-Kompatibilität bestehen, ist aber für `anon` und `authenticated` ausdrücklich gesperrt.
   Damit kann auch ein ungeprüftes Profil nicht über einen zweiten Client-RPC aktiviert werden.

Für Google werden weder eine Geräte-ID aus Metadaten noch eine unverifizierte E-Mail als Beweis vertraut.
`p_device_secret` ist vollständig clientgewählt: Ein modifizierter Client kann bei jedem Aufruf einen neuen
Wert senden und die gerätebezogene 24-h-Sperre umgehen, auch ohne App-Reset. Diese bewusst schwache Hürde
bremst nur den regulären Client; sie ist weder Geräteattestierung noch ein belastbarer Identitätsnachweis (F24).
Die getrennten IP-/Globalbudgets bleiben erforderlich. Kennt jemand ein fremdes Google-`sub`, kann er mit einer eigenen bestandenen Selbstauskunft
höchstens eine kurzlebige Bindung dafür vorbereiten, ohne sich als diese Person anmelden zu können. Die Person
muss weiterhin selbst einen gültigen Google-Token vorlegen. Absichtliche Falschangaben zum Alter verhindert diese
Selbstauskunft nicht (F24). Metadaten allein können keine Tickets erzeugen oder auf andere Provider übertragen.

Budgets je festem Ein-Minuten-Fenster ab Erstaufruf: Ausstellung zehn je IP und Installation, Bindung und
Abbruch je 30 je IP, insgesamt 1.000 anonyme Schreibaufrufe global. Auch fachliche Ablehnungen zählen
(JSON-Fehler, kein Rollback). Fehlende/ungültige IP fällt geschlossen auf **drei je Aktion** in einem gemeinsamen
Budget zurück; keine unlimitierte oder pro Client frei wählbare Ersatz-IP. `cancel_age_ticket` bleibt `void` und
verändert nach Budgetende kein Ticket. Die höhere globale Notbremse begrenzt Tabellenwachstum, bleibt aber bei
großem verteiltem Missbrauch ein möglicher Engpass; Budget-Ablehnungen überwachen und Lastkapazität prüfen.
Untergrenze sperrt das clientgewählte Installationsgeheimnis 24 Stunden.

**Proxy-Annahme (vor Freigabe nachweisen):** PostgREST liefert Header in `request.headers`. Vorrang hat
`cf-connecting-ip`, das auf Supabase-Hosted von Cloudflare überschrieben wird. Der erste Eintrag von
`x-forwarded-for` ist clientgewählt und wird standardmäßig **ignoriert**. Nur wenn die DB-Einstellung
`app.settings.trust_xff` exakt `on` ist, dient er als Fallback. **Auf Hosted/staging diesen Schalter nicht setzen.**
Ohne Cloudflare-IP und ohne Freigabe greift das gemeinsame Dreierbudget. Die neue Migration
`20261016000600_auth_xff_vertrauen.sql` setzt keinen solchen DB-Default; lokale Tests aktivieren ihn ausdrücklich
in ihrer Testsitzung. Für lokale/Self-Host-Instanzen ist eine Freigabe erst nach nachgewiesenem Überschreiben
der Clientheader zulässig (z. B. `alter database postgres set app.settings.trust_xff = 'on'` in einer ausschließlich
lokalen Seed-/Test-Konfiguration, nie in einer staging-Migration).
IP-Literale werden kanonisiert, Netze/ungültige Werte nutzen das kleine Ersatzbudget; `x-real-ip` wird ignoriert.
Die SQL-Funktion kann die Herkunft eines Headers nicht verifizieren. Daher müssen Supabase/Cloudflare/Kong
unvertrauenswürdige Clientwerte **entfernen/überschreiben oder die Anfrage ablehnen**, und PostgREST darf nicht
am Gateway vorbei erreichbar sein. Die Dokumentation des ersten XFF-Eintrags allein beweist diese Eigenschaft
nicht. Für Self-Hosting die vertrauenswürdigen Proxy-Hops explizit konfigurieren; bei Änderungen neu abnehmen.

**Staging-Nachweis gegen Spoofing:** ohne personenbezogene Daten und mit frischem Budgetfenster zehn
`issue_age_ticket`-Aufrufe vom selben Anschluss, mit je neuem `p_device_secret` und `US` (erwartet
`country_not_supported`). Der elfte muss `rate_limit_exceeded` liefern. Danach jeden der drei Header einzeln mit
wechselnden erfundenen IPs probieren: kein neuer Erfolg, sondern `rate_limit_exceeded` oder Ablehnung am Proxy.
Beispiel (Bash, `SUPABASE_URL` und öffentlicher `SUPABASE_ANON_KEY` bereits gesetzt):

```bash
for i in $(seq 1 10); do
  curl -sS "$SUPABASE_URL/rest/v1/rpc/issue_age_ticket" \
    -H "apikey: $SUPABASE_ANON_KEY" -H 'Content-Type: application/json' \
    -d "{\"p_country\":\"US\",\"p_birth_date\":\"2000-01-01\",\"p_device_secret\":\"$(openssl rand -hex 32)\"}"
done
for header in x-real-ip x-forwarded-for cf-connecting-ip; do
  for i in 21 22; do
    curl -sS -w '\nHTTP %{http_code}\n' "$SUPABASE_URL/rest/v1/rpc/issue_age_ticket" \
      -H "apikey: $SUPABASE_ANON_KEY" -H 'Content-Type: application/json' -H "$header: 203.0.113.$i" \
      -d "{\"p_country\":\"US\",\"p_birth_date\":\"2000-01-01\",\"p_device_secret\":\"$(openssl rand -hex 32)\"}"
  done
done
```

Ergebnis und Zeitpunkt im Abnahmeprotokoll festhalten. Lässt sich das Budget umgehen, **keine Freigabe**;
Gateway korrigieren. Hinter einem gemeinsamen eigenen Server/LB muss dessen Weiterleitung zuerst geprüft werden.
[Supabase-Auth-Rate-Limits](https://supabase.com/docs/guides/auth/rate-limits) im Dashboard ausdrücklich für
OTP-Versand, Verifikation und Signup konfigurieren. [CAPTCHA](https://supabase.com/docs/guides/auth/auth-captcha)
mit hCaptcha oder Turnstile ist für den Pilot empfohlen; Aktivierung erfordert im nativen Client einen
passenden Challenge-/`captchaToken`-Flow (hier noch nicht implementiert). Auth-CAPTCHA schützt den anonymen
PostgREST-Ticket-RPC nicht; bei Missbrauch ist dort zusätzlich ein Gateway-Limit/Challenge nötig.

Die anonymen RPCs, der Hook und die reine Ticketbereinigung schreiben nur `private.age_*`, vergeben keine
Sync-Version und nehmen **keine** globale Versionssperre. Budget-UPSERTs, Ticket-Zeilensperren,
ein Lock je Identität beim Binden und Unique-Constraints sichern konkurrierende Versuche ab. Der Hook setzt
funktionslokal `lock_timeout=500ms`, `statement_timeout=2s`. Ein Funktions-SET startet keinen neuen äußeren
Statement-Timer; das äußere Laufzeitlimit muss der Auth-Aufrufer setzen (Supabase begrenzt Postgres-Hooks auf
[zwei Sekunden](https://supabase.com/docs/guides/auth/auth-hooks)). Der Auth-INSERT-Trigger schreibt dagegen
versionierte Profile und behält die vorgeschriebene Versionssperre. Lange Sync-Transaktionen können diesen
Insert weiterhin verzögern; sie blockieren weder Ticket-Ausstellung noch den Hook.

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
  Das OTP-Ticket wird als Auth-Metadatum übergeben. Der AFTER-INSERT-Trigger entfernt `age_ticket` sofort aus
  `auth.users.raw_user_meta_data`, ohne andere Metadaten zu ändern. Vorübergehende Auth-Antworten können es
  trotzdem enthalten; nach Einmalverbrauch ist es wertlos. Das Geburtsdatum wird dort nie übertragen.
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

**GoTrue ist ohne aktivierten Before-User-Created-Hook fail-open:** Ein deaktivierter/fehlender Hook wird
übersprungen, er lehnt Signup nicht ab. Gehostete Projekte haben Signup standardmäßig an; `db push` überträgt
keine Dashboard-Hook-Konfiguration. Die zweite DB-Linie verhindert die Nutzung eines so entstandenen Kontos,
ersetzt aber nicht das Ziel „kein ungeprüfter Auth-Nutzer“.

0. **VOR jedem ersten `db push`: Authentication → neue Registrierungen/Signup AUS**, Google/Apple und Anonymous
   Sign-ins AUS. Dies im Deploy-Protokoll bestätigen, bevor der Workflow startet; `config.toml` gilt nur lokal.
1. Orchestrator deployt Migrationen später über den vorhandenen staging-Workflow. **Nicht hier deployen.**
2. Authentication → Hooks → Before User Created → Postgres-Funktion `public.before_user_created` aktivieren.
   Nur `supabase_auth_admin` darf sie ausführen. Ohne Hook keine Freigabe.
3. **Negativtest ohne Ticket vor der dauerhaften Signup-/Google-Freigabe:** im isolierten staging-Projekt ein
   kurzes kontrolliertes E-Mail-Signup-Testfenster öffnen (Google bleibt AUS), folgenden Aufruf ausführen und
   Signup danach sofort wieder AUS. Mit Signup AUS liefert GoTrue nur `signup_disabled`; das beweist den Hook
   ausdrücklich nicht. Für den eigentlichen Hook-Nachweis ist dieses Testfenster deshalb erforderlich.
   Auf Hosted **kein** `app.settings.trust_xff = 'on'` setzen; die Header-Abnahme aus Schritt 8 bleibt Pflicht
   und muss vor dauerhafter Signup-/Google-Freigabe im Abnahmeprotokoll stehen.

   ```bash
   curl -sS -w '\nHTTP %{http_code}\n' "$SUPABASE_URL/auth/v1/signup" \
     -H "apikey: $SUPABASE_ANON_KEY" -H 'Content-Type: application/json' \
     -d "{\"email\":\"age-gate-probe-$(openssl rand -hex 8)@example.com\",\"password\":\"Probe-only-$(openssl rand -hex 16)\"}"
   ```

   Erwartet: **HTTP 403** und Meldung **`age_ticket_required`**, kein neuer `auth.users`-Eintrag. Ein anderer Fehler
   (z. B. `signup_disabled`, CAPTCHA oder Mail-Limit) ist kein bestandener Hook-Test. Bei unerwartetem Erfolg
   Signup AUS lassen, Testkonto administrativ über den vorgesehenen Löschweg entfernen, Konfiguration korrigieren.
   Erst nach dokumentierter Ablehnung und bestandenem Header-Nachweis Signup dauerhaft AN. E-Mail-Confirmations,
   Secure Email Change und Refresh Token Rotation AN; Anonymous Sign-ins AUS. Kein Phone-/Password-UI.
   Auth-Logs ohne Request-Bodies/JWTs auf Ticket-/Budgetfehler prüfen.

**Admin-/Service-Role-Verbot für Kontoanlage:** `adminUserCreate`, `inviteUserByEmail` und entsprechende
Admin-/Invite-Endpunkte umgehen diesen Hook. `service_role` daher **nie** für Kontoanlage in Edge Functions,
Skripten oder Supportwerkzeugen einsetzen. Der Insert-Trigger übernimmt ausschließlich einen zuvor verbrauchten
Nachweis; ohne ihn bleiben `age_confirmed_at` leer und das Profil im Onboarding. `finish_onboarding` lehnt es ab;
`is_active`/`require_active` und aktive RLS-Rechte verlangen zusätzlich zum Status den Altersnachweis. Diese zweite
Linie greift auch bei Admin-/Invite-Inserts und bei einem irrtümlich auf `active` gesetzten ungeprüften Profil
(pgTAP 150). Bestehende ungeprüfte Konten sind keine zulässige Onboarding-Alternative und werden bereinigt.

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
8. RPC über das tatsächliche staging-Gateway prüfen: `cf-connecting-ip` wird vom Gateway überschrieben,
   `x-forwarded-for` ohne ausdrückliche DB-Freigabe und `x-real-ip` werden ignoriert. Auf Hosted keinen
   XFF-Schalter setzen. Den oben beschriebenen Spoofing-Test mit allen drei gefälschten Headern durchführen
   und Ergebnis/Zeitpunkt im Abnahmeprotokoll festhalten; ohne bestandenen Nachweis keine Freigabe.
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
  (681 Tasks). Unter Git Bash entspricht dies `./gradlew check --console=plain --max-workers=2` mit dem
  vorgegebenen `JAVA_HOME`. macOS-exklusive Ausführung/Link-Schritte bleiben wie üblich übersprungen.
- Neue Tests: vier Adaptertests jeweils JVM + Android-Host, zehn Auth-ViewModel-Tests jeweils JVM + Android-Host,
  drei Compose-Desktop-UI-Tests sowie zwei Android-Unit-Tests für die Credential-Typprüfung.
  Die zehn ViewModel-Tests liegen jetzt in `shared/presentation-auth`; sämtliche Auth-Netzaufrufe sind simuliert.
- Postgres-17-Harness, alle Migrationen frisch angewendet: 458/460 pgTAP-Prüfungen grün, davon 67/67 in Test 140 und 37/37 in Test 150.
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
Spike-RPC. Test 150 ergänzt Proxy-Header/Kanonisierung, kleine Fallbackbudgets, bind/cancel-Budgets,
Bindungsersatz und Wiederholung nach gescheitertem Insert, Hook-Timeouteinstellungen, ungeprüfte Email-/Google-/
Admin-Konten, RPC-/RLS-Schutz auch bei Status `active` sowie die bewusst umgehbare clientgewählte Gerätesperre.
Fünf echte Parallelprüfungen in `supabase/tests/concurrency/auth_age_gate.py` bestehen: einmaliger Verbrauch,
parallele Ersatzbindungen, parallele Ausstellungen, Hook-Sperrabbruch nach 0,523 s und unabhängige Ticketwege bei
gehaltenem Sync-Lock. Der Cron-Harness benötigt echte `pg_cron`-Unterstützung; die zwei Pflichtprüfungen in Test 110 bleiben
ohne diese Erweiterung bewusst rot. Keine Testausnahme ersetzt die Supabase-Stack-Abnahme.

Abhängigkeiten nach Modultrennung, mit `gradlew.bat … --console=plain` geprüft:

```text
:androidApp:dependencies --configuration debugRuntimeClasspath
  41 Ktor-Nennungen, alle aufgelöst auf 3.5.1 (einschließlich ktor-client-okhttp/core)
:wearApp:dependencies (alle Konfigurationen; zusätzlich debugRuntimeClasspath separat)
  keine io.github.jan-tennert.supabase, io.ktor oder androidx.security:security-crypto
:shared:umbrella-watch:dependencies --configuration watchosArm64CompileKlibraries
  keine Supabase-, Ktor- oder security-crypto-Abhängigkeit
```

`ui-phone → presentation-auth → cloud-sync` ist der einzige neue Auth-Pfad. `presentation` hängt nicht mehr an
`cloud-sync`; Wear bleibt bei `presentation`, `umbrella-watch` bei data/device-link/match-session.
Rohberichte und die maschinelle Versions-/Ausschlussprüfung liegen lokal unter `H:/dev/tmp/p2c-security/`
(`phone-dependencies.log`, `watch-dependencies.log`, `check-client-commit.log`, `dependency-proof.txt`).

R8-Nachweis: `:androidApp:assembleRelease` grün (291 Tasks), mit `minifyEnabled`/Resource-Shrinking und
öffentlichen Platzhaltern für `advantly.supabase.url=https://example.invalid`,
`advantly.supabase.key=public-r8-test-key`, `advantly.google.webClientId=r8-test.apps.googleusercontent.com`.
Dadurch bleibt der Auth-Pfad erreichbar, während ein Build ohne Konfiguration ihn wegoptimieren könnte.
`ADVANTLY_UPLOAD_*` wurde ausschließlich mit dem vorhandenen **Debug-Keystore** und dessen Standardwerten
belegt; dieser ENV-Vorrang verhindert das Lesen einer lokalen Upload-Key-Konfiguration. Keine Datei unter
`H:/dev/keys` gelesen. `apksigner verify` bestätigt die Signatur `CN=Android Debug`; R8-Mapping enthält den
Auth-Adapter, supabase Auth und den OkHttp-Engine-Einstieg. Die gemeinsamen Proguard-Regeln behalten gezielt
den OkHttp-ServiceLoader-Konstruktor; kotlinx-serialization- und Ktor-Consumer-Regeln sind im SDK enthalten
und dokumentiert. Kein pauschales Keep des gesamten SDKs. Logs: `release-auth.log`, `release-signature.log`;
Test-APK: `H:/dev/tmp/p2c-security/advantly-auth-r8-debug-signed.apk` (nur Platzhalterkonfiguration).
Ein echter minifizierter OTP-/Google-Gerätetest bleibt Teil der staging-/Pixel-Abnahme.

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
