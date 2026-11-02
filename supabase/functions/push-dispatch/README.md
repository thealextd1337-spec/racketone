# `push-dispatch` – Push-Versand über FCM HTTP v1

Plan: `docs/plan-live-mitspieler.md` §3.3 und §8. Die Function versendet
Einträge aus `ops.notification_outbox` an Android-Geräte. Sie wird nur vom
Server aufgerufen (AFTER-INSERT-Trigger und Minuten-Cron per `pg_net`), nie von
der App.

```
POST /functions/v1/push-dispatch   Header x-push-dispatch-secret: <PUSH_DISPATCH_SECRET>
  → claim_push_batch_v1(p_limit := 20)            je Eintrag {id, provider, token, payload}
  → FCM messages:send je Eintrag (data-only, android.priority=high, ttl=3600s)
  → ack_push_batch_v1(p_results := [{id, status}])  status = sent | failed | invalid_token
  → wiederholt, solange volle Batches kommen und das Zeitbudget (15 s) reicht
Antwort 200 {contractVersion: 1, sent, failed, invalid_token}; 401 falscher Schlüssel; 503 nicht konfiguriert/Backendfehler
```

- `payload` wird auf die Schlüssel `v`, `kind`, `matchId`, `profileId`, `role`
  beschränkt und als Strings gesendet. Ohne `kind`, mit anderem `provider` als
  `fcm` oder ohne Token → `failed` (der Server verwirft nach 5 Versuchen).
- `invalid_token` nur bei FCM `UNREGISTERED` oder `INVALID_ARGUMENT` zum Feld
  `message.token`; andere `INVALID_ARGUMENT` (eigener Fehler) sowie
  401/429/5xx/Netzfehler → `failed`.
- Das OAuth-Token (Scope `firebase.messaging`) wird per RS256-JWT aus dem
  Service-Account geholt und je Instanz bis 5 min vor Ablauf wiederverwendet.

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
