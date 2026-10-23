# Advantly Support Admin

Statisches Vite-Frontend für den privaten Support-Posteingang. Es verwendet ausschließlich den öffentlichen Anon-/Publishable-Key des konfigurierten Supabase-Projekts. Die Support-RPCs prüfen Mitgliedschaft und MFA serverseitig. Einrichtung und Live-Abnahme: [Betriebsanleitung](../docs/support-admin-betrieb.md).

## Lokal starten

Node.js 22 oder neuer verwenden. Projekt-URL und öffentlichen Schlüssel vor dem Start setzen:

```powershell
$env:VITE_SUPABASE_URL = "https://your-project.supabase.co"
$env:VITE_SUPABASE_ANON_KEY = "your-public-anon-or-publishable-key"
npm ci
npm run dev
```

Service-/Secret-Keys werden abgewiesen. HTTPS ist außerhalb lokaler Loopback-Adressen verpflichtend. Keine privilegierten Schlüssel in die Buildkonfiguration schreiben. Der eigenständige Web-Client verwaltet seine JavaScript-Abhängigkeiten in `package.json` und `package-lock.json`, getrennt vom Gradle-Katalog.

Mit denselben Buildvariablen das Produktionspaket erstellen und lokal prüfen:

```powershell
npm run build
npm run preview
```

`dist/` anschließend auf der vorgesehenen HTTPS-Adminadresse hosten. Diese Befehle stellen nichts online.

## Staging auf Cloudflare

Das bestehende Supabase-Staging ist `https://lgeoafnbluvxoezpftzo.supabase.co`.
Die Support-Migration wurde am 17.10.2026 über [Deployment 39014532139](https://github.com/thealextd1337-spec/racketone/actions/runs/39014532139)
eingespielt. Hosting und Betreiberkonto sind damit noch nicht eingerichtet.

Für die statische Oberfläche ist [Cloudflare Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/get-started/)
vorbereitet. Im Verzeichnis `admin/` die obigen Buildvariablen auf dieses Projekt und dessen öffentlichen
Schlüssel setzen, dann `npm run build:staging` ausführen. Der Build bricht bei fehlender, privilegierter
oder abweichender Projektkonfiguration ab. `_headers` im Ergebnis erlaubt Netzwerkzugriffe nur auf diesen
Supabase-Endpunkt, verbietet fremde Skripte und Einbettung und verhindert Browser-Caching.
TOTP-QR-Bilder bleiben als Data-URL erlaubt. Cloudflare wendet diese [Headerregeln](https://developers.cloudflare.com/workers/static-assets/headers/)
auf die statischen Antworten an; Vites lokaler Preview-Server tut das nicht.

Mit installiertem Wrangler CLI (geprüft: 4.151.1) und angemeldetem Cloudflare-Konto:

```powershell
wrangler deploy --config wrangler.staging.jsonc --dry-run
wrangler deploy --config wrangler.staging.jsonc
```

Beide Befehle bauen erneut mit der geprüften Staging-Konfiguration. Der zweite veröffentlicht den separaten
Worker `advantly-admin-staging`; die tatsächliche `workers.dev`-Adresse wird von Cloudflare ausgegeben.
Eine eigene Domain ist dafür zunächst nicht nötig. Keine Produktionsdomain oder DNS-Route ist konfiguriert.
Cloudflare hostet nur die Oberfläche; Authentifizierung, Rollen und Daten bleiben in Supabase.
`preview_urls` ist deaktiviert. Keine Worker-Function oder Analytics hinzufügen.

Nach Veröffentlichung die Header am echten HTTPS-Endpunkt sowie Passwort-/TOTP-Anmeldung und abgewiesene
Admin-Aufrufe ohne Mitgliedschaft prüfen. Die vollständigen Pilot-Gates stehen in der Betriebsanleitung.
Ein erfolgreicher Build oder Deploy ersetzt diese Nachweise nicht. Benötigt werden noch ein Cloudflare-Konto,
der öffentliche Supabase-Schlüssel und ein verifiziertes persönliches Betreiberkonto.

## Anmeldung und Posteingang

Mit einem vorab provisionierten Betreiberkonto und Passwort anmelden. Beim ersten Login einen TOTP-Authentifikator einrichten; danach den aktuellen Code bestätigen. Die Inbox öffnet erst nach AAL2; jeder Support-RPC prüft zusätzlich die aktive Betreiberrolle.

Die optionale Auswahl „Dieses Gerät 30 Tage merken“ speichert erst nach erfolgreicher MFA die Supabase-Sitzung im lokalen Browser-Speicher. Ohne Auswahl bleibt sie flüchtig. Die feste Frist beginnt mit der MFA-Bestätigung und wird durch Token-Erneuerung nicht verlängert. Beim Wiederöffnen wird die Sitzung erneuert, der Benutzer serverseitig geprüft und AAL2 erneut kontrolliert. Abmelden löscht die gespeicherte Sitzung sofort, auch bei einem Netzwerkfehler; der Recovery-Ablauf wird nie gemerkt. Die Option gehört nur auf persönliche Geräte.

Das ist eine lokale Aufbewahrungsfrist, keine Änderung der projektweiten Supabase-Sitzungsdauer. Zugangstokens bleiben kurzlebig; ihre serverseitige Gültigkeit richtet sich weiterhin nach Supabase. Grundlage: [Supabase-Sitzungen](https://supabase.com/docs/guides/auth/sessions).

„Passwort vergessen“ fordert einen E-Mail-Code an. Der Code wird im geöffneten Formular bestätigt,
danach ein vorhandener TOTP-Faktor geprüft und ein neues Passwort mit mindestens 12 Zeichen zweimal
eingegeben. Abschließend wird die Recovery-Sitzung abgemeldet; die normale Anmeldung ist erneut nötig.
Der Ablauf öffnet keine Inbox und entfernt keine Faktoren. Bei verlorenem Authentifikator gilt der
kontrollierte Betreiber-Recovery-Prozess der Betriebsanleitung.

Voraussetzung im Supabase-Dashboard unter **Authentication → Email → Reset password**: Die Vorlage
muss `{{ .Token }}` als sichtbaren Code enthalten, nicht nur `{{ .ConfirmationURL }}`. Beispiel:

```html
<h2>Advantly</h2>
<p>Code zum Zurücksetzen / Password reset code:</p>
<p>{{ .Token }}</p>
<p>Im geöffneten Formular eingeben. / Enter in the open recovery form.</p>
<p>Nicht angefordert? Ignoriere diese E-Mail. / Not requested? Ignore this email.</p>
```

SMTP muss eingerichtet sein. Für Staging sind achtstellige Codes und zehn Minuten Gültigkeit
vorgesehen; der Client akzeptiert sechs bis acht Ziffern. Ablauf und Gültigkeit hängen von der
Supabase-Konfiguration ab. Es werden keine Recovery-Links oder Tokens aus der URL übernommen.
Grundlage: [Supabase Password Recovery](https://supabase.com/docs/reference/javascript/auth-resetpasswordforemail)
und [E-Mail-Vorlagen](https://supabase.com/docs/guides/auth/auth-email-templates).

Interne Notizen sind voreingestellt. Eine Nutzerantwort erfordert eine ausdrückliche Auswahl und Bestätigung. Bei Versionskonflikten wird der aktuelle Vorgang geladen; der Antwortentwurf bleibt erhalten.

## Konten (K5)

Der Umschalter „Konten“ neben „Meldungen“ öffnet die exakte Kontosuche: Konto-UUID, Freundescode oder genauer
Alias. Kein Teiltreffer, keine Liste; mehrdeutige Aliase liefern kein Ergebnis. Angezeigt wird nur der minimale
Status (Status, Sperre, Löschfrist, Anzahl aktiver Geräte, widerrufene Geräte mit Art, Plattform, ID und Zeitpunkt,
letzter Aktivitätstag, Zustimmungsversion). Keine E-Mail, Matches, Gesundheits- oder Geburtsdaten, keine Anmeldung
als Nutzer.

`support` sieht nur den Status. `admin` kann sperren/entsperren, alle Geräte und Cloud-Sitzungen widerrufen und
eine Löschung auf Nutzerwunsch zurücknehmen. Dafür ist die UUID einer Meldung Pflicht, die nach dem Löschantrag
entstand: vom Kontoinhaber oder als Gastmeldung, deren Zuordnung die Begründung belegt (mindestens 20 Zeichen).
„Gerätefreigabe wiederherstellen“ (K4-U) gibt ein widerrufenes Gerät wieder frei, mit derselben Regel: Meldung nach
dem Widerruf. Gesperrte Konten und Konten in Löschung zeigen den Knopf nicht. Eine gekoppelte Uhr vor ihrem Handy
freigeben, sonst lehnt der Server Uhr-Events ab, die das Handy vorher hochlädt.
Jede Aktion verlangt eine Begründung (1–500 Zeichen), hat einen eigenen Knopf ohne Enter-Auslösung und läuft erst
nach einer Rückfrage, die Aktion und Zielkonto nennt, bei der Freigabe auch das Gerät. Sie landet im Konto-Audit. Der Server prüft Rolle und AAL2 bei
jedem Aufruf erneut; die Oberfläche blendet Aktionen für `support` nur aus. Details:
[`supabase/README.md`](../supabase/README.md#k4k5-gerätewiderruf-und-kontosupport).

## Prüfungen

```powershell
npm test
npm run build
```
