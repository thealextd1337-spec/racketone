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

## Anmeldung und Posteingang

Mit einem vorab provisionierten Betreiberkonto und Passwort anmelden. Beim ersten Login einen TOTP-Authentifikator einrichten; danach den aktuellen Code bestätigen. Die Inbox öffnet erst nach AAL2; jeder Support-RPC prüft zusätzlich die aktive Betreiberrolle. Sitzungen werden nicht dauerhaft gespeichert.

Interne Notizen sind voreingestellt. Eine Nutzerantwort erfordert eine ausdrückliche Auswahl und Bestätigung. Bei Versionskonflikten wird der aktuelle Vorgang geladen; der Antwortentwurf bleibt erhalten.

## Prüfungen

```powershell
npm test
npm run build
```
