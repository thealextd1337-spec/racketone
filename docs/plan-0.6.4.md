# Plan 0.6.5: Gastname bleibt bei Kontolöschung des verknüpften Freundes

> Status: **umgesetzt, gebündelt in 0.7.0 (Build 18), 2026-11-09** (D1 = Ja, D2 = mit Einladungen bündeln; Migration
> `20261109000200_gastnamen_erhalten.sql`, pgTAP 470/520, `admin_redact_guest_name` nur Rolle `admin` mit AAL2, vom Nutzer bestätigt am 2026-11-11) ·
> Entwurf 2026-11-04, überarbeitet nach Review (Fable, Astra) · Bezug: 0.6.3 (PR #97, Gast mit Freund
> verknüpfen, pgTAP 470) · Version **0.6.5**, Build ≥ 18 (nächster freier nach 0.6.4) · **Umsetzung erst nach den
> Nutzerentscheidungen in §6**
>
> Die Datei heißt aus historischen Gründen `plan-0.6.4.md`. **0.6.4 (Build 17)** ist mit reinen Fehlerkorrekturen belegt:
> Statistik-Perspektive und Einladungs-Sicherheitsfixes am Server (gelöschter Gast, schon befreundet).

## 1. Ziel

Ein Gast wie „Test1“ ist mit einer Freundin wie Anja verknüpft (zugeordnet oder beansprucht). Löscht Anja ihr Konto,
wird „Test1“ wieder ein ganz normaler Gast des Anlegers, mit seinem Namen und so, als hätte es die Verknüpfung nie
gegeben. Heute steht dort „Gelöschter Spieler“ (bestätigt durch pgTAP 470).

## 2. Begründung (DSGVO)

- **Der Gastname kann Anja identifizieren.** Auch wenn der Anleger ihn eingegeben hat, ist er ein personenbezogenes
  Datum Anjas, sobald er sie bezeichnet (z. B. Klarname). „Gehört dem Anleger, also keine Daten Anjas“ trägt nicht.
- **Rechtsgrundlage Art. 6 Abs. 1 lit. f:** Berechtigtes Interesse des Anlegers an seiner eigenen, vollständigen
  Spielhistorie. Abwägung: Der Name war schon vor der Verknüpfung so gespeichert, sichtbar bleibt er nur für den Anleger
  und die Mitglieder dieser Matches. Die Verbindung zu Anjas Konto, ihr Alias, ihr Verlauf und ihre Freundschaften
  verschwinden.
- **Art. 17 und Art. 21:** Anja (oder jede andere bezeichnete Person, auch ohne Konto) kann widersprechen bzw. die
  Löschung verlangen. Dann wird der Gastname redigiert wie heute („Gelöschter Spieler“). Weg: Support-Anfrage, umgesetzt
  über eine neue Admin-Funktion mit Audit (§3.1). Die Kontolöschung selbst bleibt ohne Rückfrage.
- **Datenschutzerklärung:** Die heutige Zusage („Ihr Name und die Namen der von Ihnen angelegten Gäste werden …
  ersetzt“) deckt den Fall nicht ausdrücklich ab. Zu nennen wären: Gastname bleibt beim Anleger, Rechtsgrundlage lit. f,
  Widerspruch über den Support. Das ist eine inhaltliche Änderung und damit voraussichtlich eine **neue Fassung mit
  erneuter Bestätigung** (Nutzerentscheidung D1, §6).
- Ergebnisse und Statistik bleiben wie bisher erhalten.

## 3. Umfang

### 3.1 Server (Kontolöschung, gestaffelt)

**Fremde Gäste, die mit Anja verknüpft sind** (`kind = 'guest'`, `owner_id ≠ Anja`, `profile_id = Anja`, Zuordnung
oder Claim, auch per Einladungslink):
- `players.profile_id`, `assigned_by`, `assigned_at`, `assignment_source` werden geleert (wie heute).
- **Neu: keine Redaktion.** Der Gast fällt aus `v_retired` bzw. den Seams heraus: kein `player_redacted`, kein
  Manifest-Eintrag, keine Zeile in `redacted_player_identities`, `PlayerAssigned`-Payloads bleiben.
- **Neu:** Das pauschale `update public.players set display_name='Gelöschter Spieler' where profile_id=p_profile` in
  Phase A überschreibt heute auch den Namen dieser Gäste. Es wird auf Anjas eigene Spieler beschränkt.
- Teilnehmer-Zeilen mit diesem `player_id` und `profile_id = Anja` werden entkoppelt (`profile_id`, `link_status`
  leer), in derselben Transaktion wie eine Erhöhung der Match-Epoche. `display_name` muss **nicht zurückgeschrieben**
  werden: `link_player_participants` ändert ihn nie, nur die Redaktion. Rückführung heißt also nur, die Redaktion
  auszulassen.

**Unverändert bleiben:**
- Plätze, auf denen Anja direkt als Freundin stand → „Gelöschter Spieler“.
- Anjas eigene Gäste, **unzugeordnet** → „Gelöschter Spieler“ (Nutzerentscheid 23.10.2026).
- Anjas eigene Gäste, **anderen zugeordnet bzw. von anderen beansprucht** → gehen an diese Person über
  (`owner_id = profile_id`, heutiger Stand in `20261022000200`), Name bleibt.

**Umsetzung:**
- Migration mit Seam-Ersatz in Phase A/B, `purge_account`, `capture_profile_redactions`, `redact_linked_names`. Beide
  Löschwege ergeben denselben Endzustand (Kommentar in `20261022000200`).
- Neu `public.admin_redact_guest_name(p_player_id, p_reason)` (nur Rolle `admin` mit AAL2, Audit-Zeile): redigiert einen einzelnen
  Gast über den vorhandenen Redaktionspfad (Epoche, Manifest, Payloads).
- Späteres erneutes Verknüpfen mit einem anderen Freund ist erlaubt, weil der Gast nicht redigiert ist.

### 3.2 App

- Gästeliste beim Anleger: „Test1“ unverknüpft, mit „Mit Freund verknüpfen“.
- Eigene Matches zeigen „Test1“ (Verknüpfungsinfo je Match und Platz aus 0.6.3), auch nicht kurzzeitig „Gelöschter
  Spieler“ nach dem Pull. Die Redaktionsliste enthält den Gast nicht.

## 4. Tests

- **pgTAP 470 umstellen** (Gast zugeordnet bzw. beansprucht, Anja löscht):
  - Beim Anleger bleiben Match, Ergebnis und Projektion unverändert.
  - Teilnehmername „Test1“, `profile_id`/`link_status` leer, `deleted_profile` false.
  - `players.profile_id` leer, `players.display_name` „Test1“.
  - Nicht in der Redaktionsliste; `assign_player` an einen anderen Freund klappt.
- **Getrennte Fälle:**
  - Anja direkt als Freundin auf einem Platz → „Gelöschter Spieler“.
  - Anjas eigener, unzugeordneter Gast → „Gelöschter Spieler“.
  - Anjas eigener Gast, einem Dritten zugeordnet bzw. von ihm beansprucht → gehört danach dem Dritten, Name bleibt.
  - Gleicher Endzustand für Job-Pfad und `purge_account` (wie Test 290).
  - `admin_redact_guest_name`: nur Rolle `admin` mit AAL2, Audit-Zeile, Ergebnis wie Redaktion.
- **Client:** Pull nach der Löschung: Gast unverknüpft, Name „Test1“, Aktion verfügbar; eigene Matches zeigen „Test1“.

## 5. Release

- Version 0.6.5, Build ≥ 18. Handy und Uhr aus demselben sauberen Commit. Migration vor der App auf Staging.
- Bei neuer Datenschutzfassung: Website-Text DE/EN (`site/`, nur mit Auftrag), Katalogeintrag mit dem App-Rollout,
  danach `is_current` prüfen.
- „Was ist neu?“ (`whats_new_<build>_*`, DE/EN) und Release-Notes DE/EN.

## 6. Offene Entscheidungen

| # | Frage | Empfehlung |
|---|---|---|
| D1 | Verhalten so umsetzen (lit. f + Widerspruch über Support) und dafür eine neue PRIVACY-Fassung? | **Ja.** Alternative ohne neue Fassung: heutiges Verhalten („Gelöschter Spieler“) beibehalten. |
| D2 | Eigenes Release 0.6.5 oder mit dem nächsten Release, das ohnehin eine neue Fassung braucht (z. B. Einladungen)? | **Mit Einladungen bündeln**, wenn die zeitnah kommen; sonst eigenes 0.6.5 (siehe `plan-reste-0.6.x.md` §2.1). |
