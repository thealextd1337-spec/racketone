-- Advantly P2-Spike · Account-Löschung (Art. 17, 14 Tage Karenz) und Datenexport (Art. 15/20)

-- Löschung beantragen: sofort unsichtbar für andere (Suche, Freundeslisten, Visitor), Push-Tokens weg,
-- offene Zähler-Einladungen abgelehnt. Health-Daten gibt es erst ab P3 (dann hier sofort hart löschen).
create or replace function public.request_account_deletion()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  update public.profiles set status = 'deletion_requested' where id = v_uid;
  update public.profile_settings set deletion_requested_at = now(), updated_at = now() where profile_id = v_uid;
  update public.devices set push_token = null, push_provider = null where profile_id = v_uid;
  update public.match_visitors set revoked_at = now()
   where revoked_at is null and (profile_id = v_uid or invited_by = v_uid);
  update public.match_roles set status = 'declined', responded_at = now()
   where profile_id = v_uid and status = 'invited';
  update public.invites set revoked_at = now() where created_by = v_uid and used_at is null and revoked_at is null;
end;
$$;

-- Login innerhalb der Karenz: Löschung abbrechen.
create or replace function public.cancel_account_deletion()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  update public.profiles set status = 'active' where id = v_uid and status = 'deletion_requested';
  if not found then
    perform private.fail('no_deletion_pending');
  end if;
  update public.profile_settings set deletion_requested_at = null, updated_at = now() where profile_id = v_uid;
end;
$$;

-- Endgültige Löschung (nach Karenz, 03 §8.3). Läuft als BEFORE-DELETE-Trigger auf profiles, damit jeder
-- Löschweg (Löschjob, auth.admin.deleteUser der Edge Function, Dashboard) dieselben Regeln anwendet:
--  1. Matches des Nutzers mit anderen verknüpften Accounts → Ersteller wechselt (erster Teilnehmer nach Team/Platz);
--     Matches nur mit Gästen → gelöscht (Events kaskadierend)
--  2. Zuweisungen fremder Dummys an den Nutzer → gelöst (Platz zeigt wieder den Dummy-Namen)
--  3. eigene Teilnehmer-Zeilen in verbleibenden Matches → „Gelöschter Spieler“ (Snapshot, Flag für Lokalisierung)
--  4. beanspruchte/zugewiesene eigene Dummys gehen an die verknüpfte Person über, übrige Spieler werden gelöscht
--  5. danach Kaskade (Einstellungen, Freundschaften, Blocks, Geräte, Rollen, Visitor, Outbox …)
-- Sign-in-with-Apple-Token-Widerruf übernimmt die Edge Function delete-account (O5).
create or replace function private.anonymize_profile()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_m   record;
  v_p   record;
  v_new uuid;
begin
  for v_m in select m.id from public.matches m where m.created_by = old.id loop
    select mp.profile_id into v_new
    from public.match_participants mp
    where mp.match_id = v_m.id and mp.profile_id is not null and mp.profile_id <> old.id
    order by mp.team, mp.position
    limit 1;
    if v_new is null then
      delete from public.matches where id = v_m.id;
    else
      update public.matches set created_by = v_new where id = v_m.id;
    end if;
  end loop;

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

create trigger profiles_anonymize_before_delete
  before delete on public.profiles
  for each row execute function private.anonymize_profile();

create or replace function private.purge_account(p_profile uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  delete from auth.users where id = p_profile;
$$;

-- Für den Löschjob (nur service_role): alle Profile, deren Karenz abgelaufen ist.
create or replace function public.admin_purge_due_accounts(p_grace interval default interval '14 days')
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id    uuid;
  v_count integer := 0;
begin
  for v_id in
    select s.profile_id from public.profile_settings s
    join public.profiles p on p.id = s.profile_id
    where p.status = 'deletion_requested' and s.deletion_requested_at <= now() - p_grace
  loop
    perform private.purge_account(v_id);
    v_count := v_count + 1;
  end loop;
  return v_count;
end;
$$;

-- Export eigener Daten als JSON (Art. 15/20). Später erzeugt die Edge Function daraus ZIP (JSON + CSV).
-- Nur eigene Daten und Matches, an denen der Nutzer beteiligt ist; keine privaten Einstellungen Dritter.
create or replace function public.export_my_data()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  return jsonb_build_object(
    'exportedAt', now(),
    'profile', (select to_jsonb(p) - 'server_version' from public.profiles p where p.id = v_uid),
    'settings', (select to_jsonb(s) from public.profile_settings s where s.profile_id = v_uid),
    'devices', (select coalesce(jsonb_agg(to_jsonb(d) - 'push_token'), '[]'::jsonb)
                from public.devices d where d.profile_id = v_uid),
    'friendships', (select coalesce(jsonb_agg(jsonb_build_object(
                       'otherProfileId', case when f.requester_id = v_uid then f.addressee_id else f.requester_id end,
                       'status', f.status, 'outgoing', f.requester_id = v_uid, 'createdAt', f.created_at)), '[]'::jsonb)
                    from public.friendships f where v_uid in (f.requester_id, f.addressee_id)),
    'blocks', (select coalesce(jsonb_agg(b.blocked_id), '[]'::jsonb) from public.user_blocks b where b.blocker_id = v_uid),
    'players', (select coalesce(jsonb_agg(to_jsonb(p) - 'server_version'), '[]'::jsonb)
                from public.players p where p.owner_id = v_uid or p.profile_id = v_uid),
    'matches', (select coalesce(jsonb_agg(jsonb_build_object(
                   'match', to_jsonb(m) - 'server_version',
                   'participants', (select jsonb_agg(jsonb_build_object(
                                       'slotId', mp.slot_id, 'team', mp.team, 'position', mp.position,
                                       'displayName', mp.display_name, 'isMe', mp.profile_id = v_uid))
                                    from public.match_participants mp where mp.match_id = m.id),
                   'events', (select coalesce(jsonb_agg(jsonb_build_object(
                                 'id', e.id, 'deviceId', e.device_id, 'deviceSeq', e.device_seq, 'lamport', e.lamport,
                                 'wallClockMs', e.wall_clock_ms, 'parentId', e.parent_id, 'type', e.type,
                                 'payload', e.payload) order by e.lamport, e.device_id, e.id), '[]'::jsonb)
                              from public.match_events e where e.match_id = m.id))), '[]'::jsonb)
                from public.matches m
                where m.created_by = v_uid
                   or exists (select 1 from public.match_participants mp
                              where mp.match_id = m.id and mp.profile_id = v_uid)),
    'roles', (select coalesce(jsonb_agg(to_jsonb(r) - 'server_version'), '[]'::jsonb)
              from public.match_roles r where r.profile_id = v_uid),
    'visitorOf', (select coalesce(jsonb_agg(jsonb_build_object('matchId', v.match_id, 'createdAt', v.created_at,
                                                               'revokedAt', v.revoked_at)), '[]'::jsonb)
                  from public.match_visitors v where v.profile_id = v_uid),
    'entitlements', (select coalesce(jsonb_agg(to_jsonb(e)), '[]'::jsonb)
                     from public.entitlements e where e.profile_id = v_uid));
end;
$$;
