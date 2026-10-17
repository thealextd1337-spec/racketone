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

Mit einem vorab provisionierten Betreiberkonto und Passwort anmelden. Beim ersten Login einen TOTP-Authentifikator einrichten; danach den aktuellen Code bestätigen. Die Inbox öffnet erst nach AAL2; jeder Support-RPC prüft zusätzlich die aktive Betreiberrolle. Sitzungen werden nicht dauerhaft gespeichert.

Interne Notizen sind voreingestellt. Eine Nutzerantwort erfordert eine ausdrückliche Auswahl und Bestätigung. Bei Versionskonflikten wird der aktuelle Vorgang geladen; der Antwortentwurf bleibt erhalten.

## Prüfungen

```powershell
npm test
npm run build
```
