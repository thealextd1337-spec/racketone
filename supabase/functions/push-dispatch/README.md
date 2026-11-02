# `push-dispatch` – Push-Versand über FCM HTTP v1

Plan: `docs/plan-live-mitspieler.md` §3.3 und §8. Die Function versendet
Einträge aus `ops.notification_outbox` an Android-Geräte. Sie wird nur vom
Server aufgerufen (AFTER-INSERT-Trigger und Minuten-Cron per `pg_net`), nie von
der App.

```
POST /functions/v1/push-dispatch   Header x-push-dispatch-secret: <PUSH_DISPATCH_SECRET>
  → claim_push_batch_v1(p_limit := 10)   je Eintrag {id, message, targets: [{deviceId, provider, token}]}
  → FCM messages:send je Ziel (data-only, android.priority=high, ttl=3600s)
  → ack_push_batch_v1(p_results := [{id, token, result, error?}])  result = sent | error | invalid_token
  → wiederholt, solange volle Batches kommen und das Zeitbudget (10 s) reicht
Antwort 200 {contractVersion: 1, sent, error, invalid_token}; 401 falscher Schlüssel; 503 nicht konfiguriert/Backendfehler
```

- Vertrag: `supabase/migrations/20261102000200_push_outbox.sql` (ein Ergebnis je
  Ziel; ein gesendetes Ziel genügt für den Eintrag; Eintrag ohne Ziel →
  `error`/`no_target`).
- `message` wird auf die Schlüssel `v`, `kind`, `matchId`, `profileId`, `role`
  beschränkt und als Strings gesendet. Ohne `kind`, mit anderem `provider` als
  `fcm` oder ohne Token → `error` (der Server verwirft nach 5 Versuchen).
- `invalid_token` nur bei FCM `UNREGISTERED` oder `INVALID_ARGUMENT` zum Feld
  `message.token`; andere `INVALID_ARGUMENT` (eigener Fehler) sowie
  401/429/5xx/Netzfehler → `error`.
- Das OAuth-Token (Scope `firebase.messaging`) wird per RS256-JWT aus dem
  Service-Account geholt und je Instanz bis 5 min vor Ablauf wiederverwendet.
- Laufzeit: neue Batches nur in den ersten 10 s, neue Sende-Runden (10 parallel)
  nur in den ersten 14 s; OAuth und FCM haben je 4 s Timeout. Ein Lauf endet
  damit nach spätestens rund 22 s plus Ack, sicher innerhalb des pg_net-Timeouts
  von 30 s. Nicht mehr gesendete Ziele gehen als `error`/`deadline` zurück und
  kommen nach dem Rückoff erneut.
- **Idempotenz:** Doppelte Zustellung ist möglich (Ack geht verloren, Lease
  läuft nach 2 min ab). Die App zeigt jede Nachricht unter einer festen
  Notification-ID aus Art, Match und Auslöser (`showPush` in
  `androidApp/.../push/AndroidPush.kt`); eine zweite Zustellung ersetzt also die
  vorhandene Benachrichtigung, statt eine weitere anzuzeigen.

## Konfiguration (Function-Secrets, nicht im Repo)

| Name                                                             | Inhalt                                                                                                                                      |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `PUSH_DISPATCH_SECRET`                                           | zufälliger Schlüssel, mindestens 32 Zeichen; derselbe Wert liegt im Vault für den pg_net-Aufruf                                             |
| `FCM_SERVICE_ACCOUNT_JSON`                                       | vollständiges Service-Account-JSON des Firebase-Projekts (Rolle „Firebase Cloud Messaging API Admin“ bzw. `cloudmessaging.messages.create`) |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` | stellt die Edge-Runtime bereit                                                                                                              |

```bash
npx supabase secrets set PUSH_DISPATCH_SECRET=... FCM_SERVICE_ACCOUNT_JSON="$(cat service-account.json)"
```

Fehlt einer der beiden Werte, antwortet die Function mit 503 und versendet
nichts.

## Prüfen

```bash
deno task check && deno fmt --check --indent-width 2 && deno lint && deno task test
```
