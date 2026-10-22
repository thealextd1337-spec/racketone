-- Kontolöschung skaliert quadratisch mit der eigenen Historie (Review b854b9d, O1, vorbestehend): drei Ursachen,
-- jede mit identischer Semantik behoben. Es bleibt der lineare Anteil der Zeilentrigger (rund 1 ms je eigener
-- Teilnehmerzeile); oberhalb von etwa 10 000 eigenen Zeilen überschreitet eine Löschung weiter das 10-s-Budget.
-- Ursache 1: private.redaction_other_profile_context ist eine SQL-Funktion mit SECURITY DEFINER, wird also nie
-- inline ausgeführt und mit generischem Plan geplant. Der Filter „p_match is null or mp.match_id=p_match“ lässt
-- dort keinen Indexzugriff zu: Jeder matchbezogene Aufruf liest alle Teilnehmerzeilen. Die Löschung ruft ihn je
-- eigenem Match auf (referenced, Kandidaten, redacted_event_identity) – Matches × Teilnehmer.
-- Abhilfe: dieselbe Bedingung je Fall als eigener Zweig, der matchbezogene über den Primärschlüssel.
-- Keine Prüfung entfällt, keine Budgetänderung, Owner/ACL bleiben (CREATE OR REPLACE), SECURITY DEFINER und
-- leerer search_path wie in 00700.

create or replace function private.redaction_other_profile_context(p_match uuid,p_id uuid,p_profile uuid) returns boolean
language sql security definer set search_path = '' as $$
  -- Unverändert gegenüber 00700, nur nach p_match getrennt; CASE wertet nur den passenden Zweig aus.
  select case when p_match is null then exists(select 1 from public.match_participants mp
      join public.profiles f on f.id=mp.profile_id
    where not mp.deleted_profile
      and (p_profile is null or mp.profile_id<>p_profile)
      and not private.redacted_profile_identity(mp.profile_id::text)
      and (mp.profile_id=p_id or mp.player_id=p_id
        or exists(select 1 from private.match_player_profile_contexts c
          where c.match_id=mp.match_id and c.slot_id=mp.slot_id and c.profile_id=mp.profile_id and c.player_id=p_id)
        or exists(select 1 from public.players p where p.id=p_id and private.redaction_uuid(mp.slot_id)=p.id
          and mp.profile_id=case when p.kind='self' then p.owner_id else p.profile_id end)))
  else exists(select 1 from public.match_participants mp join public.profiles f on f.id=mp.profile_id
    where mp.match_id=p_match and not mp.deleted_profile
      and (p_profile is null or mp.profile_id<>p_profile)
      and not private.redacted_profile_identity(mp.profile_id::text)
      and (mp.profile_id=p_id or mp.player_id=p_id
        or exists(select 1 from private.match_player_profile_contexts c
          where c.match_id=mp.match_id and c.slot_id=mp.slot_id and c.profile_id=mp.profile_id and c.player_id=p_id)
        or exists(select 1 from public.players p where p.id=p_id and private.redaction_uuid(mp.slot_id)=p.id
          and mp.profile_id=case when p.kind='self' then p.owner_id else p.profile_id end))) end
$$;

-- Ursache 2: Jede eigene Teilnehmerzeile, die ihren Profil-FK verliert, und jedes übergebene Match erhöht über die
-- Zeilentrigger visibility_epoch des gelöschten Profils, bei 100 000 eigenen Zeilen rund 120 000-mal dieselbe
-- profile_settings-Zeile in einer Transaktion. Jede Version bleibt bis zum Commit sichtbar und muss vom nächsten
-- Update durchlaufen werden, die Kosten wachsen quadratisch. Die Zeile entfällt per Kaskade am Ende desselben
-- DELETE, die Erhöhungen sind nie lesbar. redacted_profile_identities enthält ausschließlich Profile, deren
-- Löschtrigger lief (capture_profile_redactions, Trigger 00); für alle anderen Profile bleibt jede Erhöhung gleich.
create or replace function private.bump_visibility(p_profiles uuid[])
returns void
language sql
security definer
set search_path = ''
as $$
  update public.profile_settings s set visibility_epoch = visibility_epoch + 1
   where s.profile_id = any (p_profiles)
     and not exists(select 1 from private.redacted_profile_identities d where d.profile_id = s.profile_id);
$$;

-- Ursache 3: anonymize_profile löscht eigene Matches ohne weiteren Profil-FK einzeln. Die FK-Aktion
-- „previous_match_id … on delete set null“ sucht dabei ohne Index in allen Matches (je gelöschtem Match ein Seq Scan).
-- Teilindex genügt: Die RI-Abfrage „previous_match_id = $1“ impliziert „is not null“.
create index matches_previous_match_idx on public.matches (previous_match_id) where previous_match_id is not null;

-- Übergabe und Löschung eigener Matches als je eine Anweisung statt je Match (Zeilenschleife mit zwei Anweisungen
-- je Match). Neuer Ersteller wie bisher: erster weiterer Profil-FK nach (team, position). Die Zeilentrigger feuern
-- je Match wie zuvor; Übergaben und Löschungen betreffen getrennte Matches und lesen nur deren eigene Teilnehmer.
-- Alles Weitere unverändert gegenüber der Fassung nach 00700 (dort entfiel die frühere Payload-Suche).
create or replace function private.anonymize_profile()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_p   record;
begin
  update public.matches m set created_by = n.profile_id
    from (select distinct on (mp.match_id) mp.match_id, mp.profile_id
            from public.matches o join public.match_participants mp on mp.match_id = o.id
           where o.created_by = old.id and mp.profile_id is not null and mp.profile_id <> old.id
           order by mp.match_id, mp.team, mp.position) n
   where m.id = n.match_id;
  delete from public.matches where created_by = old.id;

  for v_p in select p.id from public.players p where p.profile_id = old.id and p.owner_id <> old.id loop
    perform private.unlink_player(v_p.id);
  end loop;

  update public.match_participants
     set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false,
         display_name = 'Gelöschter Spieler', deleted_profile = true, player_id = null
   where profile_id = old.id;

  update public.players
     set owner_id = profile_id, assigned_by = null
   where owner_id = old.id and kind = 'guest' and profile_id is not null and profile_id <> old.id;
  update public.match_participants mp set player_id = null
   where mp.player_id in (select p.id from public.players p where p.owner_id = old.id);
  delete from public.players where owner_id = old.id;
  update public.players set assigned_by = null where assigned_by = old.id;
  return old;
end;
$$;

-- Löschjob (00400): Mehrere fällige Konten mit eigener Historie überschreiten im selben Lauf das 60-s-Limit
-- (8 Konten × 10 000 eigene Teilnehmerzeilen: Abbruch nach 60 s, deletedCount 0, alle 8 bleiben fällig).
-- Unverändert gegenüber 00400 bis auf die Zeitgrenze; CREATE OR REPLACE behält Owner und ACL.
create or replace function private.run_account_deletion_job_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' set statement_timeout = '60s' as $$
declare
  v_started timestamptz := clock_timestamp();
  v_completed timestamptz;
  v_selection_time timestamptz;
  v_id uuid;
  v_count integer := 0;
  v_error text;
begin
  -- Kein veralteter Snapshot nach Warten, auch nicht bei direktem Service-Aufruf.
  perform private.require_account_read_committed();
  begin
    perform private.lock_sync();
    v_selection_time := clock_timestamp();
    -- Auswahl erst NACH der globalen Sperre; alle normalen Recovery-/Schreibwege nehmen dieselbe Sperre.
    for v_id in
      select p.id from public.profiles p join public.profile_settings s on s.profile_id = p.id
      where p.status = 'deletion_requested' and s.deletion_requested_at <= v_selection_time - interval '14 days'
      order by s.deletion_requested_at, p.id limit 100
      for update of p, s
    loop
      -- 0022: Zeitbudget je Lauf. Nach 30 s beginnt keine weitere Löschung; der Rest folgt im nächsten Lauf.
      -- Sonst bricht das 60-s-Limit den ganzen Lauf ab, alle Löschungen rollen zurück, und jeder Folgelauf
      -- wählt dieselben ältesten Konten. Mindestens ein Konto je Lauf.
      exit when v_count > 0 and clock_timestamp() - v_started >= interval '30 seconds';
      -- Frische Prüfung direkt vor dem bestehenden Löschpfad, keine Auth-Sitzungsmutation hier.
      if exists (select 1 from public.profiles p join public.profile_settings s on s.profile_id = p.id
          where p.id = v_id and p.status = 'deletion_requested'
            and s.deletion_requested_at <= clock_timestamp() - interval '14 days') then
        perform private.purge_account(v_id);
        v_count := v_count + 1;
      end if;
    end loop;
  exception
    -- Der Unterblock rollt ALLE Kontolöschungen zurück; ausschließlich feste Fehlerklassen speichern.
    when lock_not_available then v_error := 'lock_timeout';
    when query_canceled then v_error := 'statement_canceled';
    when others then v_error := 'account_deletion_failed';
  end;
  if v_error is not null then v_count := 0; end if;
  v_completed := clock_timestamp();
  insert into private.account_deletion_job_state as j
    (singleton, started_at, completed_at, status, deleted_count, error_code)
    values (true, v_started, v_completed, case when v_error is null then 'succeeded' else 'failed' end, v_count, v_error)
    on conflict (singleton) do update set started_at = excluded.started_at, completed_at = excluded.completed_at,
      status = excluded.status, deleted_count = excluded.deleted_count, error_code = excluded.error_code
      where j.completed_at <= excluded.completed_at;
  return jsonb_build_object('contractVersion', 1, 'startedAt', v_started, 'completedAt', v_completed,
    'status', case when v_error is null then 'succeeded' else 'failed' end,
    'deletedCount', v_count, 'errorCode', v_error);
end;
$$;
