-- Advantly P2-Spike · Cloud-Sync: Geräte, sync_push (Outbox-Upload), sync_pull (Delta), Event-Download
-- Wire-Format (camelCase wie die Kotlin-DTOs in shared/cloud-sync): siehe docs/p2-plan.md §5.

create or replace function public.register_device(
  p_id uuid,
  p_kind text,
  p_platform text,
  p_paired_with uuid default null,
  p_standalone boolean default false,
  p_model text default null,
  p_app_version text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid   uuid := private.require_active();
  v_owner uuid;
begin
  if p_id is null then
    perform private.fail('invalid_device');
  end if;
  select d.profile_id into v_owner from public.devices d where d.id = p_id for update;
  if v_owner is not null and v_owner <> v_uid then
    perform private.fail('device_owned_by_other', '42501');
  end if;
  if p_paired_with is not null
     and not exists (select 1 from public.devices d where d.id = p_paired_with and d.profile_id = v_uid) then
    perform private.fail('paired_device_unknown');
  end if;
  insert into public.devices (id, profile_id, kind, platform, paired_with, standalone, model, app_version)
  values (p_id, v_uid, p_kind, p_platform, p_paired_with, coalesce(p_standalone, false), p_model, p_app_version)
  on conflict (id) do update
    set kind = excluded.kind, platform = excluded.platform, paired_with = excluded.paired_with,
        standalone = excluded.standalone, model = excluded.model, app_version = excluded.app_version,
        last_seen_at = now(), revoked_at = null;
end;
$$;

-- Spieler (Self-Aliase und Dummys) des Aufrufers anlegen/umbenennen/löschen.
create or replace function private.push_players(p_uid uuid, p_players jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_p        jsonb;
  v_out      jsonb := '[]'::jsonb;
  v_id       uuid;
  v_kind     text;
  v_name     text;
  v_deleted  boolean;
  v_existing public.players%rowtype;
begin
  for v_p in select value from jsonb_array_elements(coalesce(p_players, '[]'::jsonb)) loop
    begin
      v_id := (v_p ->> 'id')::uuid;
      v_kind := v_p ->> 'kind';
      v_name := btrim(v_p ->> 'displayName');
      v_deleted := coalesce((v_p ->> 'deleted')::boolean, false);
      select * into v_existing from public.players where id = v_id for update;
      if found then
        if v_existing.owner_id <> p_uid then
          v_out := v_out || jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'player_owned_by_other');
          continue;
        elsif v_existing.kind <> v_kind then
          v_out := v_out || jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'kind_mismatch');
          continue;
        end if;
        update public.players
           set display_name = v_name,
               deleted_at = case when v_deleted then coalesce(deleted_at, now()) end
         where id = v_id;
      else
        insert into public.players (id, owner_id, kind, display_name, profile_id, deleted_at)
        values (v_id, p_uid, v_kind, v_name, case when v_kind = 'self' then p_uid end,
                case when v_deleted then now() end);
      end if;
      v_out := v_out || jsonb_build_object('id', v_id, 'result', 'ok');
    exception when data_exception or integrity_constraint_violation then
      v_out := v_out || jsonb_build_object('id', v_p ->> 'id', 'result', 'rejected', 'reason', 'invalid');
    end;
  end loop;
  return v_out;
end;
$$;

-- Teilnehmer eines Matches setzen (nur Ersteller). Verknüpfung mit Accounts:
--   eigener Self-Spieler / eigene profileId → self · Dummy mit Zuweisung → assigned · Freund → linked (sofort
--   sichtbar und gewertet, F23; Widerspruch „War ich nicht“)
--   alles andere bleibt unverknüpft (nur Snapshot-Name).
create or replace function private.push_participants(p_uid uuid, p_match uuid, p_parts jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_p          jsonb;
  v_notes      jsonb := '[]'::jsonb;
  v_slot       text;
  v_player     uuid;
  v_req        uuid;
  v_profile    uuid;
  v_status     text;
  v_pl         public.players%rowtype;
  v_existing   public.match_participants%rowtype;
  v_share      boolean;
  v_had_row    boolean;
begin
  delete from public.match_participants mp
   where mp.match_id = p_match
     and mp.slot_id <> all (array(select x ->> 'slotId' from jsonb_array_elements(p_parts) x));

  for v_p in select value from jsonb_array_elements(p_parts) loop
    v_slot := v_p ->> 'slotId';
    v_player := (v_p ->> 'playerId')::uuid;
    v_req := (v_p ->> 'profileId')::uuid;
    v_profile := null;
    v_status := null;

    if v_player is not null then
      select * into v_pl from public.players where id = v_player;
      if not found or v_pl.owner_id <> p_uid then
        v_notes := v_notes || jsonb_build_object('slotId', v_slot, 'note', 'player_not_owned');
        v_player := null;
      elsif v_pl.kind = 'self' then
        v_profile := p_uid;
        v_status := 'self';
      elsif v_pl.profile_id is not null then
        v_profile := v_pl.profile_id;
        v_status := 'assigned';
      end if;
    end if;

    if v_profile is null and v_req is not null then
      if v_req = p_uid then
        v_profile := p_uid;
        v_status := 'self';
      elsif private.are_friends(p_uid, v_req) and not private.is_blocked(p_uid, v_req) then
        v_profile := v_req;
        v_status := 'linked';
      else
        v_notes := v_notes || jsonb_build_object('slotId', v_slot, 'note', 'not_friend');
      end if;
    end if;

    if v_profile is not null and exists (
         select 1 from public.match_link_rejections r where r.match_id = p_match and r.profile_id = v_profile) then
      v_notes := v_notes || jsonb_build_object('slotId', v_slot, 'note', 'link_rejected');
      v_profile := null;
      v_status := null;
    end if;
    if v_profile is not null and exists (
         select 1 from public.match_participants mp
         where mp.match_id = p_match and mp.profile_id = v_profile and mp.slot_id <> v_slot) then
      v_notes := v_notes || jsonb_build_object('slotId', v_slot, 'note', 'profile_already_in_match');
      v_profile := null;
      v_status := null;
    end if;

    select * into v_existing from public.match_participants where match_id = p_match and slot_id = v_slot;
    v_had_row := found;
    if v_had_row and v_existing.profile_id is not distinct from v_profile then
      v_status := v_existing.link_status;   -- bestätigte Verknüpfung bleibt bestätigt
    end if;
    v_share := case when v_status = 'self'
                    then coalesce((select s.share_matches_default from public.profile_settings s where s.profile_id = p_uid), false)
                    else false end;

    insert into public.match_participants as mp
      (match_id, slot_id, team, position, player_id, profile_id, link_status, display_name, share_with_friends, linked_at)
    values (p_match, v_slot, v_p ->> 'team', (v_p ->> 'position')::smallint, v_player, v_profile, v_status,
            btrim(v_p ->> 'displayName'), v_share, case when v_profile is not null then now() end)
    on conflict (match_id, slot_id) do update
      set team = excluded.team,
          position = excluded.position,
          player_id = excluded.player_id,
          display_name = excluded.display_name,
          profile_id = excluded.profile_id,
          link_status = excluded.link_status,
          linked_at = case when mp.profile_id is not distinct from excluded.profile_id then mp.linked_at
                           else excluded.linked_at end,
          share_with_friends = case when mp.profile_id is not distinct from excluded.profile_id
                                    then mp.share_with_friends else excluded.share_with_friends end,
          hidden = case when mp.profile_id is not distinct from excluded.profile_id then mp.hidden else false end;

    if v_status = 'linked' and (not v_had_row or v_existing.profile_id is distinct from v_profile) then
      perform private.notify(v_profile, 'match_shared', jsonb_build_object('matchId', p_match));
    end if;
  end loop;
  return v_notes;
end;
$$;

-- Match-Kopf anlegen/ändern (nur Ersteller) inkl. Teilnehmer. Löschen = Tombstone; sind andere Accounts
-- verknüpft, blendet der Ersteller das Match nur für sich aus (geteilte Matches gehören allen, 03 §8.3).
create or replace function private.push_match(p_uid uuid, p_m jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id      uuid;
  v_match   public.matches%rowtype;
  v_tour    uuid;
  v_court   uuid;
  v_prev    uuid;
  v_result  text;
  v_notes   jsonb := '[]'::jsonb;
begin
  begin
    v_id := (p_m ->> 'id')::uuid;
    v_tour := (p_m ->> 'tournamentId')::uuid;
    v_court := (p_m ->> 'courtId')::uuid;
    v_prev := (p_m ->> 'previousMatchId')::uuid;
  exception when data_exception then
    return jsonb_build_object('id', p_m ->> 'id', 'result', 'rejected', 'reason', 'invalid');
  end;
  if v_id is null then
    return jsonb_build_object('id', null, 'result', 'rejected', 'reason', 'invalid');
  end if;

  select * into v_match from public.matches where id = v_id for update;
  begin
    if not found then
      if v_tour is not null and not private.is_tournament_director(v_tour, p_uid) then
        return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'tournament_forbidden');
      end if;
      if v_court is not null and not exists (
           select 1 from public.tournament_courts c where c.id = v_court and c.tournament_id = v_tour) then
        return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'court_invalid');
      end if;
      if v_prev is not null and not private.can_view_match(v_prev, p_uid) then
        v_prev := null;
      end if;
      insert into public.matches (id, created_by, sport, discipline, format, meta, previous_match_id, tournament_id,
                                  court_id, scheduled_at, started_at, status)
      values (v_id, p_uid, p_m ->> 'sport', p_m ->> 'discipline', p_m -> 'format', coalesce(p_m -> 'meta', '{}'::jsonb),
              v_prev, v_tour, v_court, (p_m ->> 'scheduledAt')::timestamptz, (p_m ->> 'startedAt')::timestamptz,
              case when p_m ->> 'startedAt' is null then 'scheduled' else 'in_progress' end);
      v_result := 'created';
    elsif v_match.created_by <> p_uid then
      return jsonb_build_object('id', v_id, 'result', 'not_owner');
    elsif coalesce((p_m ->> 'deleted')::boolean, false) then
      if exists (select 1 from public.match_participants mp
                 where mp.match_id = v_id and mp.profile_id is not null and mp.profile_id <> p_uid) then
        update public.match_participants set hidden = true where match_id = v_id and profile_id = p_uid;
        return jsonb_build_object('id', v_id, 'result', 'hidden_only');
      end if;
      update public.matches set deleted_at = coalesce(deleted_at, now()) where id = v_id;
      return jsonb_build_object('id', v_id, 'result', 'deleted');
    else
      if v_prev is not null and not private.can_view_match(v_prev, p_uid) then
        v_prev := v_match.previous_match_id;
      end if;
      update public.matches
         set meta = coalesce(p_m -> 'meta', meta),
             previous_match_id = coalesce(v_prev, previous_match_id),
             scheduled_at = coalesce((p_m ->> 'scheduledAt')::timestamptz, scheduled_at),
             started_at = coalesce(started_at, (p_m ->> 'startedAt')::timestamptz),
             status = case when status = 'scheduled' and p_m ->> 'startedAt' is not null then 'in_progress' else status end
       where id = v_id;
      v_result := 'updated';
    end if;

    if jsonb_typeof(p_m -> 'participants') = 'array' then
      v_notes := private.push_participants(p_uid, v_id, p_m -> 'participants');
    end if;
  exception when data_exception or integrity_constraint_violation then
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'invalid', 'detail', sqlerrm);
  end;
  return jsonb_build_object('id', v_id, 'result', v_result, 'notes', v_notes);
end;
$$;

-- Vergleich eines gespeicherten Events mit einem erneut hochgeladenen (Idempotenz). Alle unveränderlichen Felder
-- müssen gleich sein. Redigierte Events (Kontolöschung) gelten als gleich, wenn sich nur der redigierte Klarname
-- unterscheidet – der Client hält noch die alte Payload.
create or replace function private.same_event(p_existing public.match_events, p_match uuid, p_device uuid,
                                              p_seq bigint, p_lamport bigint, p_wall bigint, p_parent uuid,
                                              p_type text, p_payload jsonb)
returns boolean
language sql
stable
set search_path = ''
as $$
  select p_existing.match_id = p_match and p_existing.device_id = p_device and p_existing.device_seq = p_seq
     and p_existing.lamport = p_lamport and p_existing.wall_clock_ms = p_wall
     and p_existing.parent_id is not distinct from p_parent and p_existing.type = p_type
     and (p_existing.payload = p_payload
          or (p_existing.redacted_at is not null and jsonb_typeof(p_payload -> 'player') = 'object'
              and p_existing.payload = jsonb_set(p_payload, '{player,displayName}',
                                                 p_existing.payload -> 'player' -> 'displayName')));
$$;

-- Ein Event idempotent übernehmen. Prüft Identität (Autor = auth.uid(), Quellgerät gehört dem Autor),
-- Upload-Recht am Match und die Single-Scorer-Regeln über Accounts (wer darf übernehmen/zuweisen).
-- Ob ein Event fachlich wirkt (aktiver Zähler zum Zeitpunkt), entscheidet weiterhin die Engine beim Replay.
create or replace function private.push_event(p_uid uuid, p_e jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id       uuid;
  v_match    uuid;
  v_device   uuid;
  v_seq      bigint;
  v_lamport  bigint;
  v_wall     bigint;
  v_parent   uuid;
  v_type     text;
  v_payload  jsonb;
  v_existing public.match_events%rowtype;
  v_m        public.matches%rowtype;
  v_target   text;
  v_owner    uuid;
  v_new      uuid;
begin
  begin
    v_id := (p_e ->> 'id')::uuid;
    v_match := (p_e ->> 'matchId')::uuid;
    v_device := (p_e ->> 'deviceId')::uuid;
    v_seq := (p_e ->> 'deviceSeq')::bigint;
    v_lamport := (p_e ->> 'lamport')::bigint;
    v_wall := coalesce((p_e ->> 'wallClockMs')::bigint, 0);
    v_parent := (p_e ->> 'parentId')::uuid;
    v_type := p_e ->> 'type';
    v_payload := p_e -> 'payload';
  exception when data_exception then
    return jsonb_build_object('id', p_e ->> 'id', 'result', 'rejected', 'reason', 'invalid');
  end;
  if v_id is null or v_match is null or v_device is null or v_seq is null or v_lamport is null or v_type is null
     or v_payload is null or jsonb_typeof(v_payload) <> 'object' or (v_payload ->> 'type') is distinct from v_type then
    return jsonb_build_object('id', p_e ->> 'id', 'result', 'rejected', 'reason', 'invalid');
  end if;

  -- Idempotenz: gleiche ID + gleicher Inhalt = Duplikat (No-op), abweichender Inhalt = Kollision.
  select * into v_existing from public.match_events e where e.id = v_id;
  if found then
    if private.same_event(v_existing, v_match, v_device, v_seq, v_lamport, v_wall, v_parent, v_type, v_payload) then
      return jsonb_build_object('id', v_id, 'result', 'duplicate');
    end if;
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'id_conflict');
  end if;

  select * into v_m from public.matches m where m.id = v_match;
  if not found then
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'unknown_match');
  elsif v_m.deleted_at is not null then
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'match_deleted');
  elsif not private.can_submit_events(v_match, p_uid) then
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'forbidden');
  elsif not exists (select 1 from public.devices d
                    where d.id = v_device and d.profile_id = p_uid and d.revoked_at is null) then
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'device_not_owned');
  end if;

  if v_type = 'MatchCreated' then
    if not (v_m.created_by = p_uid
            or exists (select 1 from public.match_roles r
                       where r.match_id = v_match and r.profile_id = p_uid and r.status = 'accepted')) then
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'genesis_forbidden');
    end if;
  elsif v_type = 'ScorerChanged' then
    if not private.can_take_over(v_match, p_uid) then
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'takeover_forbidden');
    end if;
    v_target := v_payload ->> 'scorerDeviceId';
    if v_target is null or v_target !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'scorer_target_invalid');
    end if;
    -- Review P2 #5: Ziel ist ein eigenes Gerät des Hochladenden (Übernahme/Rückgabe im eigenen Verbund) oder das
    -- Gerät, mit dem eine andere Person ihre Zähler-/Director-Rolle serverseitig angenommen hat. Nie ein beliebiges
    -- Gerät eines anderen Kontos.
    select d.profile_id into v_owner from public.devices d where d.id = v_target::uuid and d.revoked_at is null;
    if v_owner is null
       or not (v_owner = p_uid
               or exists (select 1 from public.match_roles r
                          where r.match_id = v_match and r.profile_id = v_owner and r.status = 'accepted'
                            and r.device_id = v_target::uuid)) then
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'scorer_target_forbidden');
    end if;
  elsif v_type = 'PlayerAssigned' then
    -- F20/02 §7.6: nur der Ersteller weist Plätze zu; P2 prüft den Akteur gegen den Account.
    if v_m.created_by <> p_uid then
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'assignment_forbidden');
    end if;
  end if;

  insert into public.match_events (id, match_id, device_id, device_seq, lamport, wall_clock_ms, parent_id, type,
                                   payload, author_id)
  values (v_id, v_match, v_device, v_seq, v_lamport, v_wall, v_parent, v_type, v_payload, p_uid)
  on conflict do nothing
  returning id into v_new;

  if v_new is null then
    -- Review P2 #8: paralleler Upload derselben ID – nur bei identischem Inhalt ein Duplikat.
    select * into v_existing from public.match_events e where e.id = v_id;
    if found then
      if private.same_event(v_existing, v_match, v_device, v_seq, v_lamport, v_wall, v_parent, v_type, v_payload) then
        return jsonb_build_object('id', v_id, 'result', 'duplicate');
      end if;
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'id_conflict');
    elsif v_type = 'MatchCreated'
          and exists (select 1 from public.match_events e where e.match_id = v_match and e.type = 'MatchCreated') then
      return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'genesis_exists');
    end if;
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'sequence_conflict');
  end if;
  return jsonb_build_object('id', v_id, 'result', 'accepted');
exception when data_exception or integrity_constraint_violation then
  return jsonb_build_object('id', p_e ->> 'id', 'result', 'rejected', 'reason', 'invalid');
end;
$$;

-- Projektion (Status, Ergebnis, bereinigter Live-Stand) – berechnet die Client-Engine (03 E9) über die Events, die
-- bereits in der Cloud liegen. Review P2 #10: an den tatsächlichen Server-Stand gebunden (eventCount ≤ Anzahl
-- gespeicherter Events, sonst ahead_of_events → später erneut), monoton (ältere Stände → stale) und nur von
-- Personen mit Übernahme-Recht (widerrufene Zähler nicht).
create or replace function private.apply_projection(p_uid uuid, p_match uuid, p_proj jsonb)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_m     public.matches%rowtype;
  v_count integer;
begin
  select * into v_m from public.matches where id = p_match for update;
  if not found then
    return 'unknown_match';
  elsif v_m.deleted_at is not null then
    return 'match_deleted';
  elsif not private.can_take_over(p_match, p_uid) then
    return 'forbidden';
  end if;
  v_count := coalesce((p_proj ->> 'eventCount')::integer, 0);
  if v_count < v_m.projection_event_count then
    return 'stale';
  elsif v_count > (select count(*) from public.match_events e where e.match_id = p_match) then
    return 'ahead_of_events';
  end if;
  update public.matches
     set status = coalesce(p_proj ->> 'status', status),
         result_reason = p_proj ->> 'resultReason',
         winner = p_proj ->> 'winner',
         ended_at = (p_proj ->> 'endedAt')::timestamptz,
         started_at = coalesce(started_at, (p_proj ->> 'startedAt')::timestamptz, now()),
         summary = p_proj -> 'summary',
         live_state = p_proj -> 'liveState',
         projection_event_count = v_count
   where id = p_match;
  return 'applied';
exception when data_exception or integrity_constraint_violation then
  return 'invalid';
end;
$$;

-- Outbox-Upload: ein Aufruf, eine Transaktion, idempotent. Reihenfolge: Geräte → Spieler → Matches (+Teilnehmer)
-- → Events → Projektionen. Antwort je Element; der Client löscht Outbox-Einträge erst nach der Antwort
-- (accepted/duplicate = erledigt, rejected = nicht wiederholen, melden).
create or replace function public.sync_push(p jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid      uuid := private.require_active();
  v_item     jsonb;
  v_devices  jsonb := '[]'::jsonb;
  v_players  jsonb;
  v_matches  jsonb := '[]'::jsonb;
  v_events   jsonb := '[]'::jsonb;
  v_proj     text;
begin
  if p is null or jsonb_typeof(p) <> 'object' then
    perform private.fail('invalid_batch');
  end if;
  if jsonb_array_length(coalesce(p -> 'events', '[]'::jsonb)) > 500 then
    perform private.fail('batch_too_large');
  end if;
  -- (Versionssperre hält bereits private.require_active() – vor allen Zeilensperren)

  for v_item in select value from jsonb_array_elements(coalesce(p -> 'devices', '[]'::jsonb)) loop
    begin
      perform public.register_device((v_item ->> 'id')::uuid, v_item ->> 'kind', v_item ->> 'platform',
                                     (v_item ->> 'pairedWith')::uuid, coalesce((v_item ->> 'standalone')::boolean, false),
                                     v_item ->> 'model', v_item ->> 'appVersion');
      v_devices := v_devices || jsonb_build_object('id', v_item ->> 'id', 'result', 'ok');
    exception
      when sqlstate 'P0001' or sqlstate '42501' then
        v_devices := v_devices || jsonb_build_object('id', v_item ->> 'id', 'result', 'rejected', 'reason', sqlerrm);
      when data_exception or integrity_constraint_violation then
        v_devices := v_devices || jsonb_build_object('id', v_item ->> 'id', 'result', 'rejected', 'reason', 'invalid');
    end;
  end loop;

  v_players := private.push_players(v_uid, p -> 'players');

  for v_item in select value from jsonb_array_elements(coalesce(p -> 'matches', '[]'::jsonb)) loop
    v_matches := v_matches || private.push_match(v_uid, v_item);
  end loop;

  for v_item in select value from jsonb_array_elements(coalesce(p -> 'events', '[]'::jsonb)) loop
    v_events := v_events || private.push_event(v_uid, v_item);
  end loop;

  -- Projektionen nach den Events, damit sie sich auf den neuen Stand beziehen
  for v_item in select value from jsonb_array_elements(coalesce(p -> 'matches', '[]'::jsonb)) loop
    if jsonb_typeof(v_item -> 'projection') = 'object' then
      begin
        v_proj := private.apply_projection(v_uid, (v_item ->> 'id')::uuid, v_item -> 'projection');
      exception when data_exception then
        v_proj := 'invalid';
      end;
      v_matches := (
        select coalesce(jsonb_agg(case when r ->> 'id' = v_item ->> 'id' then r || jsonb_build_object('projection', v_proj)
                                       else r end order by ord), '[]'::jsonb)
        from jsonb_array_elements(v_matches) with ordinality as t (r, ord));
    end if;
  end loop;

  return jsonb_build_object('devices', v_devices, 'players', v_players, 'matches', v_matches, 'events', v_events);
end;
$$;

-- Delta-Pull: alle für mich sichtbaren Matches (inkl. Tombstones) mit server_version > Cursor, eigene/zugewiesene
-- Spieler und Freundschaften.
--  * Konsistenz (Review P2 #6): Lesen unter der geteilten Versionssperre → kein Schreiber committet währenddessen
--    neue Versionen; Cursor = höchste vergebene Version (nicht das Maximum der gelieferten Zeilen).
--  * Sichtbarkeitsverlust (#7): `visibilityEpoch` steigt, wenn sich meine Sichtbarkeit ohne neue Match-Version
--    ändert → Client macht einen Vollabgleich ab Cursor 0 und entfernt nicht mehr gelieferte Cloud-Matches.
--  * Feed (#2): Freunde eines teilenden Teilnehmers erhalten eine bereinigte Sicht (`access = feed`): kein Ersteller,
--    keine Rollen, kein Live-Stand, Profil-ID/Alias nur von eigenen Freunden, Ergebnis per Whitelist.
-- Events kommen NICHT im Feed, sondern je Match über pull_match_events (nur Mitglieder, 03 §5.5).
create or replace function public.sync_pull(p_cursor bigint default 0, p_limit integer default 200)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid     uuid := auth.uid();
  v_cursor  bigint := coalesce(p_cursor, 0);
  v_limit   integer := least(greatest(coalesce(p_limit, 200), 1), 500);
  v_hwm     bigint;
  v_friends uuid[];
  v_ids     uuid[];
  v_matches jsonb;
  v_more    boolean;
  v_players jsonb;
  v_friendships jsonb;
  v_next    bigint;
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  perform pg_advisory_xact_lock_shared(private.sync_lock_key());
  v_hwm := coalesce(pg_sequence_last_value('private.sync_version_seq'), 0);
  v_friends := private.friend_ids(v_uid);

  with candidates as (
    select m.id from public.matches m where m.created_by = v_uid
    union select mp.match_id from public.match_participants mp where mp.profile_id = v_uid
    union select r.match_id from public.match_roles r where r.profile_id = v_uid and r.status in ('invited', 'accepted')
    union select mp.match_id from public.match_participants mp
          where mp.share_with_friends and mp.profile_id = any (v_friends)
    union select m.id from public.matches m
          where m.tournament_id is not null and private.is_tournament_staff(m.tournament_id, v_uid)
  )
  select coalesce(array_agg(x.id order by x.server_version), '{}') into v_ids
  from (
    select m.id, m.server_version
    from public.matches m
    join candidates c on c.id = m.id
    where m.server_version > v_cursor and m.server_version <= v_hwm and private.can_view_match(m.id, v_uid)
    order by m.server_version
    limit v_limit + 1
  ) x;
  v_more := cardinality(v_ids) > v_limit;
  if v_more then
    v_ids := v_ids[1:v_limit];
  end if;

  select coalesce(jsonb_agg(x.j order by x.v), '[]'::jsonb) into v_matches
  from (
    select m.server_version as v,
           jsonb_build_object(
             'id', m.id,
             'access', case when mem.is_member then 'member' else 'feed' end,
             'createdBy', case when mem.is_member or m.created_by = v_uid or m.created_by = any (v_friends)
                               then m.created_by end,
             'sport', m.sport, 'discipline', m.discipline, 'format', m.format, 'meta', m.meta,
             'previousMatchId', case when mem.is_member then m.previous_match_id end,
             'tournamentId', case when mem.is_member then m.tournament_id end,
             'courtId', case when mem.is_member then m.court_id end,
             'scheduledAt', m.scheduled_at, 'startedAt', m.started_at, 'endedAt', m.ended_at,
             'status', m.status, 'resultReason', m.result_reason, 'winner', m.winner,
             'summary', case when mem.is_member then m.summary else private.sanitize_projection(m.summary) end,
             'liveState', case when mem.is_member then m.live_state end,
             'projectionEventCount', m.projection_event_count,
             'accessEpoch', case when mem.is_member then m.access_epoch end,
             'redactionEpoch', m.redaction_epoch,
             'deleted', m.deleted_at is not null,
             'serverVersion', m.server_version,
             'participants', (
               select coalesce(jsonb_agg(jsonb_build_object(
                        'slotId', mp.slot_id, 'team', mp.team, 'position', mp.position,
                        'playerId', case when pl.owner_id = v_uid then mp.player_id end,
                        -- Mitglieder sehen Konten und Aliase der Mitspieler; Feed-Leser nur die ihrer Freunde
                        'profileId', case when mem.is_member or mp.profile_id = v_uid or mp.profile_id = any (v_friends)
                                          then mp.profile_id end,
                        'linkStatus', case when mem.is_member then mp.link_status end,
                        'displayName', case when pr.status in ('active', 'deletion_requested')
                                                 and (mem.is_member or mp.profile_id = v_uid
                                                      or mp.profile_id = any (v_friends))
                                            then pr.alias else mp.display_name end,
                        'deletedProfile', mp.deleted_profile,
                        'shareWithFriends', case when mp.profile_id = v_uid then mp.share_with_friends end,
                        'hidden', case when mp.profile_id = v_uid then mp.hidden end)
                      order by mp.team, mp.position), '[]'::jsonb)
               from public.match_participants mp
               left join public.players pl on pl.id = mp.player_id
               left join public.profiles pr on pr.id = mp.profile_id
               where mp.match_id = m.id),
             'roles', (
               select coalesce(jsonb_agg(jsonb_build_object(
                        'profileId', r.profile_id, 'alias', pr.alias, 'role', r.role, 'status', r.status,
                        'deviceId', case when r.profile_id = v_uid then r.device_id end)
                      order by r.invited_at), '[]'::jsonb)
               from public.match_roles r
               join public.profiles pr on pr.id = r.profile_id
               where r.match_id = m.id and mem.is_member)) as j
    from public.matches m
    cross join lateral (select private.is_match_member(m.id, v_uid) as is_member) mem
    where m.id = any (v_ids)
  ) x;

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', p.id, 'kind', p.kind, 'displayName', p.display_name, 'owned', p.owner_id = v_uid,
           'profileId', p.profile_id, 'assignmentSource', p.assignment_source,
           'deleted', p.deleted_at is not null, 'serverVersion', p.server_version) order by p.server_version), '[]'::jsonb)
    into v_players
  from public.players p
  where (p.owner_id = v_uid or p.profile_id = v_uid) and p.server_version > v_cursor and p.server_version <= v_hwm;

  select coalesce(jsonb_agg(jsonb_build_object(
           'profileId', o.id, 'alias', case when o.status = 'active' then o.alias end,
           'status', f.status, 'outgoing', f.requester_id = v_uid, 'serverVersion', f.server_version)
           order by f.server_version), '[]'::jsonb)
    into v_friendships
  from public.friendships f
  join public.profiles o on o.id = case when f.requester_id = v_uid then f.addressee_id else f.requester_id end
  where v_uid in (f.requester_id, f.addressee_id) and f.server_version > v_cursor and f.server_version <= v_hwm;

  if v_more then
    v_next := (select max((x ->> 'serverVersion')::bigint) from jsonb_array_elements(v_matches) x);
  else
    v_next := greatest(v_cursor, v_hwm);
  end if;

  return jsonb_build_object(
    'matches', v_matches, 'players', v_players, 'friendships', v_friendships,
    'cursor', v_next, 'hasMore', v_more,
    'visibilityEpoch', (select s.visibility_epoch from public.profile_settings s where s.profile_id = v_uid));
end;
$$;

-- Event-Download je Match über den Server-Cursor (RLS greift: SECURITY INVOKER; nur Mitglieder, nie Feed/Visitor).
-- Spaltennamen in camelCase = Wire-Format (PostgREST liefert sie unverändert als JSON-Schlüssel, Review P2 #9).
create or replace function public.pull_match_events(p_match_id uuid, p_after_seq bigint default 0,
                                                    p_limit integer default 500)
returns table (
  "id" uuid, "matchId" uuid, "deviceId" uuid, "deviceSeq" bigint, "lamport" bigint, "wallClockMs" bigint,
  "parentId" uuid, "type" text, "payload" jsonb, "serverSeq" bigint, "redacted" boolean)
language sql
stable
security invoker
set search_path = ''
as $$
  select e.id, e.match_id, e.device_id, e.device_seq, e.lamport, e.wall_clock_ms, e.parent_id, e.type, e.payload,
         e.server_seq, e.redacted_at is not null
  from public.match_events e
  where e.match_id = p_match_id and e.server_seq > coalesce(p_after_seq, 0)
  order by e.server_seq
  limit least(greatest(coalesce(p_limit, 500), 1), 1000);
$$;

-- Anti-Entropie gegen die Cloud wie zwischen Geräten (01 §4.3): Wasserstand je Gerät. Weicht er vom lokalen
-- Stand ab, lädt der Client das Match vollständig nach (schließt Lücken durch spät committete Uploads).
create or replace function public.match_event_watermarks(p_match_id uuid)
returns table ("deviceId" uuid, "maxSeq" bigint, "eventCount" bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select e.device_id, max(e.device_seq), count(*)
  from public.match_events e
  where e.match_id = p_match_id
  group by e.device_id
  order by e.device_id;
$$;
