# Plan 0.6.4: Gastname bleibt bei Kontolöschung des verknüpften Freundes

> Status: Entwurf 2026-11-04 · Bezug: 0.6.3 (PR #97, Gast mit Freund verknüpfen, pgTAP 470) · Version 0.6.4, Build 17
> (versionCodes 171/172) · Umsetzung nach dem Feldtest mit 0.6.3

## 1. Ziel

Ein Gast wie „Test1“ ist mit einem Freund wie Anja verknüpft. Löscht Anja ihr Konto, wird „Test1“ wieder ein ganz normaler
Gast des Anlegers, mit seinem ursprünglichen Namen und so, als hätte es die Verknüpfung nie gegeben. Heute steht dort
„Gelöschter Spieler“ (bestätigt durch pgTAP 470).

## 2. Begründung

- Den Gastnamen hat der Anleger selbst eingegeben, es sind nicht Anjas Daten. Die Datenschutzerklärung (§ Kontolöschung)
  sagt zu: „Ihr Name und die Namen der von Ihnen angelegten Gäste werden … durch ‚Gelöschter Spieler‘ ersetzt.“
  Ein fremder Gast fällt nicht darunter. Das heutige Verhalten geht also über die Zusage hinaus. Eine neue Fassung der
  Erklärung ist nicht nötig. Vor dem Release prüfen, ob ein klarstellender Satz gewünscht ist. Wenn ja, wird es eine neue
  Fassung mit erneuter Bestätigung.
- Ergebnisse und Statistik bleiben wie bisher erhalten. Gelöscht werden Anjas Konto, ihr Alias, ihr Verlauf und die
  Verknüpfung.

## 3. Umfang

### 3.1 Server (Kontolöschung, gestaffelt)

- Betroffen sind Spieler mit `kind = 'guest'`, `owner_id ≠ gelöschtes Konto` und `profile_id = gelöschtes Konto`, die
  zugeordnet oder beansprucht wurden. Für sie gilt:
  - `players.profile_id`, `assigned_by`, `assigned_at` und `assignment_source` werden geleert. Der Spieler wird **nicht**
    redigiert: kein `player_redacted`, kein Manifest-Eintrag, `display_name` bleibt.
  - Teilnehmer-Zeilen mit diesem `player_id` und `profile_id = gelöschtes Konto` werden entkoppelt (`profile_id` und
    `link_status` leer), und `display_name` wird wieder der Name des Gasts. Das geschieht in derselben Transaktion wie eine
    Erhöhung der Match-Epoche, damit die Clients neu laden.
  - Event-Payloads (`PlayerAssigned`) dieses Gasts enthalten den vom Anleger vergebenen Namen und bleiben unverändert.
- Unverändert bleiben:
  - Plätze, auf denen Anja direkt als Freundin stand, werden zu „Gelöschter Spieler“.
  - Gäste, die Anja selbst angelegt hat, werden zu „Gelöschter Spieler“ (Nutzerentscheid 23.10.2026).
  - Von Anja beanspruchte Gäste anderer: siehe oben. Dasselbe gilt für Claims per Einladungslink.
- Umsetzung als Migration mit Seam-Ersatz in den Löschpfaden: Phase A/B der gestaffelten Löschung, `purge_account`,
  `capture_profile_redactions` und `redact_linked_names`. Beide Löschwege müssen denselben Endzustand ergeben (Kommentar in
  `20261022000200`).
- Ein späteres erneutes Verknüpfen mit einem anderen Freund ist erlaubt. Die Redaktions-Schreibsperre greift nicht mehr,
  weil der Gast nicht redigiert ist.

### 3.2 App

- Die Gästeliste zeigt „Test1“ danach wieder unverknüpft, mit der Aktion „Mit Freund verknüpfen“.
- Eigene Matches zeigen wieder „Test1“, gesteuert über die Verknüpfungsinfo je Match und Platz aus 0.6.3.
- Kein „Gelöschter Spieler“ für diesen Gast, auch nicht kurzzeitig nach dem Pull. Die Redaktionsliste enthält ihn nicht.

## 4. Tests

- pgTAP 470 umstellen: Gast zuordnen, Anja löscht ihr Konto. Erwartet:
  - Beim Anleger bleiben Match, Ergebnis und Projektion unverändert.
  - Der Teilnehmername ist „Test1“, `profile_id` und `link_status` sind leer, `deleted_profile` ist false.
  - `players.profile_id` ist leer, `display_name` ist „Test1“.
  - Der Spieler ist nicht in der Redaktionsliste, und ein erneutes `assign_player` an einen anderen Freund klappt.
- Zusätzlich:
  - Anja stand **direkt** als Freundin auf einem Platz → weiter „Gelöschter Spieler“.
  - Anjas eigene Gäste → weiter „Gelöschter Spieler“.
  - Gleicher Endzustand für den Job-Pfad und für `purge_account` (wie Test 290).
- Client:
  - Pull nach der Löschung: Gast unverknüpft, Name „Test1“, Aktion verfügbar.
  - Eigene Matches zeigen „Test1“.

## 5. Release

- Version 0.6.4, Build 17. Handy und Uhr aus demselben sauberen Commit bauen. Die Migration geht vor der App auf Staging.
- „Was ist neu?“ (`whats_new_17_*`, 2–4 Punkte, DE/EN) und Release-Notes DE/EN.
- Weitere Punkte für 0.6.4 ergeben sich aus dem Feldtest mit 0.6.3.
