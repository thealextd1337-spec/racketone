# U1 – Sitzungsgrenze und Gerätevertrag

Stand: 17.10.2026. Umsetzungsschnitt zum [Cloud-Pilot-Lieferplan](p2-cloud-pilot-lieferplan.md).

## Lieferumfang und Freigabe

U1 implementiert die lokale Sitzungsgrenze bis zum tatsächlichen HTTP-Request und zur
Auswahl/Quittierung im vorhandenen `CloudUploadStore`. Der Android-Auth-Host verwendet dafür
dieselbe `LocalData`-Instanz wie `AndroidMatchSessions`; alle Zugriffe bleiben auf Main.
Die Geräteberechtigung ist als versionierter Client-/Serververtrag festgelegt und clientseitig
ausführbar. **Die dazugehörigen Server-RPCs und deren Rechteprüfung sind noch nicht implementiert.**
Der bestehende Server kann deshalb keinen Upload oder Download über diesen Adapter aktivieren.
Es gibt weder automatische Registrierung noch einen Worker, Retry-Loop oder automatischen Pull.

U4/D1 dürfen erst nach Implementierung des folgenden Serververtrags und K4-Abnahme freigegeben
werden. Ein erfolgreicher MockEngine-Test ist kein Nachweis für reale Supabase-Gerätesicherheit.
U2 (Eigentumsübernahme/Identitätswechsel), U3 (Metadatenrevisionen), U4, D1 und B4 bleiben eigene Pakete.

## Lokale Sicherheitsgrenze

- `CloudSessionGate` vergibt eine neue Generation bei expliziter Anmeldung, Kontowechsel,
  SDK-Sessionwechsel, Verlust, Logout, Löschbeginn und Schließen des Hosts. Dieselbe Profil-ID
  genügt nicht als Identität; `session_id` des aktuellen JWT und Generation gehören dazu.
- Ein Refresh mit unveränderter `session_id` erhält die Generation. Jeder neue Versuch erfasst
  den aktuellen Bearer; ein bereits ausgewählter Versuch behält seine unveränderlichen Credentials.
  Ein möglicherweise abgelaufenes altes Token wird vom Server abgewiesen, niemals gegen ein Token
  eines anderen Kontos ausgetauscht.
- Die lokal gelesenen JWT-Claims dienen nur der Abbruchentscheidung, niemals als Vertrauensbeweis.
  Erst die erfolgreiche serverseitige Profilprüfung veröffentlicht `CloudSession`. Wiederherstellung
  aus SDK-Speicher allein aktiviert weder Profil, Upload noch Geräteberechtigung.
- Vor Versand, nach Antwort und vor lokalem Ack wird der **aktuelle SDK-Zustand synchron** gelesen.
  Die zusätzliche SDK-Flow-Beobachtung bricht laufende Requests ab; ihre Zustellung ist keine
  Voraussetzung für die Prüfung. Ein Logout sperrt lokal vor dem ersten Netzaufruf.
- Aktivierung bindet keine Quellgeräte. Vorhandene Eigentümer bleiben erhalten. Die Auswahl enthält
  ausschließlich Events bereits zugeordneter eigener Quellen. Die Geräteberechtigung des HTTP-Callers
  ist kein Eigentumsnachweis für eine empfangene Uhr, Eventquelle oder historische Geräte-ID.
- `captureUpload` hält Auth-Generation, ausgewählte Events und die konkrete Gerätebindung fest.
  `pushSelected` prüft diese erneut und quittiert ausschließlich die gesendete Auswahl. Ein Rebind
  macht auch innerhalb derselben Auth-Generation alte Auswahlen und Antworten ungültig.
- `stopForDeletion` muss beim Beginn des späteren K3-Löschablaufs aufgerufen werden. Der serverseitige
  Profilzustand `deletionRequested` sperrt ebenfalls. K3-UI/Recovery-RPCs sind nicht Teil von U1.

HTTP verwendet einen eigenen Ktor-Client ohne SDK-Auth-Plugin: der eingefrorene Bearer steht
ausdrücklich im `Authorization`-Header. Redirects sind deaktiviert. Gesamtfrist: 20 Sekunden;
maximal 2 MB Request-JSON und 8 MB gestreamte Antwort, maximal 500 Events pro Push. Eigene Fristen
werden als Verbindungsfehler sichtbar; externe Coroutine-Cancellation wird weitergereicht.
Token und Proof sind keine `data class`-Felder und erscheinen nicht in generierten `toString`-Ausgaben.
Serverfehler werden ausschließlich über bekannte Grundschlüssel weitergegeben.

## Versionierter HTTP-Vertrag

Die Basis ist `/rest/v1/rpc/`. Alle Requests sind POST mit JSON und `apikey`. Authentifizierte
Requests tragen den eingefrorenen Bearer. Gerätegebundene Requests tragen zusätzlich:

```text
X-Advantly-Installation-Id: <Installations-UUID>
X-Advantly-Installation-Proof: <256-Bit-Capability als 64 Hex-Zeichen>
```

`PhoneAuthStorage.cloudInstallation` erzeugt und speichert UUID/Proof gemeinsam in verschlüsselten,
separaten Schlüsseln `cloud_installation_id_v1`/`cloud_installation_proof_v1`. Das bestehende
Altersgate-Geheimnis `device_secret` wird nicht wiederverwendet. Ein unvollständiger Datensatz
führt zum Fehler, niemals zum stillen Austausch der Identität. Nach einem Widerruf darf der
Client weder Proof noch Installations-ID erneuern, um die Sperre zu umgehen.

Der spätere U4-Host fordert ausdrücklich `cloudBackend(installation).bindDevice(deviceId)` an.
Die Registrierung wird nicht beim Erstellen einer Activity ausgeführt. `deviceId` bezeichnet die
autorisierte Caller-Geräteidentität, nicht automatisch eine lokale Eventquelle.

| RPC | JSON-Parameter | Erfolgsantwort |
| --- | --- | --- |
| `bind_cloud_session_v1` | `p_installation_id`, `p_device_id` | `contractVersion: 1`, `profileId`, `sessionId`, `installationId`, `deviceId` |
| `cloud_sync_push_v1` | `p_batch` | `PushResponse` |
| `cloud_sync_pull_v1` | `p_cursor`, `p_limit` (1–200) | `PullResponse` |
| `cloud_pull_match_events_v1` | `p_match_id`, `p_after_server_seq`, `p_limit` (1–500) | Liste `PulledEvent` |
| `cloud_match_event_watermarks_v1` | `p_match_id` | Liste `EventWatermark` |
| `cloud_get_visitor_match_v1` | `p_match_id` | `VisitorView` |

Die Bestätigung muss alle vier IDs und Version exakt zum unveränderlichen Anmeldeversuch zurückgeben.
Sie wird nur innerhalb derselben aktuellen Generation angenommen. Ein zweiter Bindungsversuch
invalidiert die erste ausstehende Bestätigung. Fehlende RPCs (404/PGRST202), Widerruf, fehlende
Felder oder Abweichungen lassen den Adapter gesperrt. **Kein Fallback auf `register_device` oder
die bisherigen unversionierten Sync-RPCs.** Profil/Onboarding verwenden weiterhin ihre separaten
RPCs ohne Geräteberechtigung, aber mit festem Sitzungs-Bearer.

## Verbindliche Serverarbeit vor U4/D1/K4-Freigabe

1. Die vom Gateway verifizierten Claims `sub`/`session_id` müssen zum Account und zu einer
   weiterhin vorhandenen `auth.sessions`-Zeile passen. Nur lesend prüfen, keine direkten
   Änderungen an `auth.sessions`. Ein globaler Auth-Logout kann eine Zeile entfernen, obwohl
   ein bereits ausgegebenes JWT noch nicht abgelaufen ist.
2. Eine serverseitige Installations-Capability mit ausschließlich gespeichertem Proof-Digest
   und eine kontogebundene Installation-/Gerätezuordnung anlegen. Historische Quellgeräte
   behalten ihren bisherigen Eigentümer. Derselbe physische Client kann für B eine neue
   Caller-/Self-Identität erhalten; dies darf A weder umhängen noch entsperren (U2).
3. Eine dauerhafte Zuordnung der verifizierten Auth-Sitzung zu Installation, Account und
   Caller-Gerät führen. Gewählte v1-Protokollregel für die Erstregistrierung: Die weiterhin
   vorhandene, zum verifizierten JWT gehörende `auth.sessions`-Zeile wurde nach Serverzeit
   vor höchstens **fünf Minuten** angelegt. Ein durch Refresh neues JWT-`iat` genügt nicht.
   Eine vorhandene Zuordnung oder ein Tombstone derselben `session_id` hat stets Vorrang und
   verbietet den Wechsel zu einer anderen behaupteten Installation, Geräte-ID oder anderem Proof.
   Eine aktive vorhandene Bindung kann mit ihrem Proof auch nach fünf Minuten idempotent
   wiederhergestellt werden. Dauert Onboarding länger, braucht Erstregistrierung ausdrücklich
   eine erneute Anmeldung; kein automatischer Fallback oder Geheimniswechsel.
   Widerrufene `(profile_id, installation_id)`-Bindungen werden auch durch frische Anmeldung
   nicht wieder aktiviert. Frische Reauthentifizierung kann über den späteren K4-Recovery-Ablauf
   eine tatsächlich neue Installation zulassen. Dies sperrt Credentials und Bindungen, beweist
   aber keine dauerhafte physische Hardwareidentität. Ein frei erzeugter Proof oder eine
   beliebige Geräte-ID allein beweist keine bestehende Berechtigung.
4. Vor jeder Mutation und jedem Cloud-Leseweg Session, Account, Proof, Gerätezuordnung und
   Widerrufsstatus prüfen. Bindung atomar/idempotent vornehmen; dieselbe Auth-Sitzung darf nicht
   durch andere behauptete IDs neu gebunden werden. Widerrufene Session-/Installationsbindungen
   als Tombstones erhalten. `register_device` darf `revoked_at` nicht zurücksetzen.
5. Die Prüfung auf bestehende `sync_push`, `sync_pull`, `pull_match_events`,
   `match_event_watermarks`, `get_visitor_match`, `my_visitor_matches`, `tournament_overview`
   sowie direkte authenticated Tabellen-SELECTs und Realtime-/Broadcast-/Presence-Zugang anwenden
   oder diese Zugänge zuverlässig entziehen. Ein abgesicherter v1-RPC neben ungesicherten alten
   Endpunkten genügt nicht. Den aktuellen DELETE-Zugang auf `public.devices` entfernen, damit
   Widerrufstombstones nicht durch Löschen/Neuanlegen verschwinden.
6. `private.is_active`/`require_active` nicht pauschal an Gerätebindung koppeln:
   Export, Support, Löschung und Wiederherstellung brauchen ihre unabhängigen Berechtigungen.
   Gerätewiderruf und globaler Auth-Sitzungswiderruf bleiben unterschiedliche Operationen.
7. Realtime benötigt eine serverseitig prüfbare Capability/Session-Bindung; bloße REST-Header
   dürfen nicht als vorhandener WebSocket-Schutz angenommen werden. Kein Realtime-Release,
   solange die Policy/Channel-Lösung die gleiche Sperre nicht durchgängig belegt.

Referenz für die serverseitige Sitzungsprüfung:
[Supabase – User sessions](https://supabase.com/docs/guides/auth/sessions).

## Abnahmeevidenz und nächste Tests

Clienttests verwenden reale SDK-Sessionwechsel und Ktor MockEngine sowie die echte lokale
SQLDelight-Datenbank: gesendete Bearer-/Proof-Header, A→B, A→A, Refresh, Logout während Request,
verzögerte Profilantwort, Wiederherstellung vor Profilbestätigung, Löschsperre, ausstehende
Outbox bei verspätetem Upload, fehlende/falsche Gerätebestätigung, Timeout und Antwortgrößenlimit.
Sie prüfen keinen echten Supabase-Stack und ersetzen keine serverseitige Rechteabnahme.
Lokal bestanden: `gradlew.bat check` (einschließlich 45 Cloud-JVM-Tests, Android-Tests,
Lint und Apple-Kotlin-Kompilierung). Native Apple-Ausführung und echte Provider-/Gerätetests
bleiben der macOS-/Pilot-Abnahme vorbehalten.

Für das Serverpaket sind pgTAP-Tests mit echten Auth-Session-Fixtures und zusätzlich HTTP-/Realtime-
Negativtests erforderlich: fehlende/fremde Session, falscher Proof, anderer Account, widerrufenes
Gerät bei gültigem JWT, Neuregistrierung mit alter oder neuer behaupteter ID, Tombstone-DELETE,
direkte Tabellenzugänge sowie weiterhin nutzbarer Support/Export/Recovery. Diese Tests und die
K4-Abnahme stehen aus; der Live-Pilot wird durch U1 nicht freigegeben.
