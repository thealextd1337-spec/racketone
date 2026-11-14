-- Plan W12 Paket 3 (Entscheidung 5): Live per Supabase Realtime als reines Signal.
-- Je Konto ein privater Kanal `live:<profile_uuid>`; Ereignis `changed` mit leerem Payload ({}). Der Client lädt danach
-- wie bisher per RPC (cloud_my_live_matches_v1, cloud_get_visitor_match_v1); Daten und Rechteprüfung bleiben dort.
-- Beitritt nur mit gebundener JWT-Sitzung (cloud_session_bindings), ohne Installationsnachweis: WebSockets tragen keine
-- REST-Header. Die alten Themen aus 20261015000600 (match:, match-live:, tournament:) bleiben geschlossen,
-- private.realtime_send bleibt Leerlauf (20261018000100). Abwärtskompatibel: 0.7.1 tritt keinem Kanal bei.
-- Betrieb (kein SQL): Realtime „Allow public access“ aus (private_only), sonst umgehen öffentliche Kanäle die Policy.

-- Nie werfen: Realtime ruft das im Policy-Check auf, jeder Fehler heißt „kein Beitritt“.
create or replace function private.can_receive_realtime(p_topic text, p_profile uuid default auth.uid())
returns boolean language plpgsql stable security definer set search_path = '' as $$
declare
  v_claims jsonb;
  v_uid uuid;
  v_sid uuid;
begin
  if coalesce(p_topic, '') !~ '^live:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return false;
  end if;
  v_claims := auth.jwt();
  v_uid := (v_claims ->> 'sub')::uuid;
  v_sid := (v_claims ->> 'session_id')::uuid;
  if v_uid is null or v_sid is null or v_claims ->> 'role' is distinct from 'authenticated'
     or v_uid is distinct from auth.uid() or v_uid is distinct from p_profile
     or substr(p_topic, 6)::uuid <> v_uid then
    return false;
  end if;
  return exists (select 1 from auth.sessions s
                 where s.id = v_sid and s.user_id = v_uid
                   and (s.not_after is null or s.not_after > statement_timestamp()))
     and exists (select 1 from private.cloud_session_bindings b
                 join private.cloud_installations i using (profile_id, installation_id, device_id)
                 join public.devices d on d.id = b.device_id and d.profile_id = b.profile_id
                 where b.session_id = v_sid and b.profile_id = v_uid
                   and b.revoked_at is null and i.revoked_at is null and d.revoked_at is null)
     and private.cloud_profile_allowed(v_uid);
exception when others then
  return false;
end;
$$;

-- Restriktive Sperre aus 20261018000100 nur für Lesen von Broadcasts auf erlaubten Themen öffnen; Presence und jedes
-- Senden durch Clients bleiben zu. Die permissive Policy advantly_realtime_receive (20261015000600) prüft dasselbe.
-- anon bleibt eigens ganz zu (ohne EXECUTE auf den Helfer würde die Policy sonst mit einem Rechtefehler abbrechen).
do $$
begin
  if to_regclass('realtime.messages') is not null then
    execute 'drop policy if exists cloud_realtime_closed on realtime.messages';
    execute $policy$
      create policy cloud_realtime_closed on realtime.messages as restrictive for all to authenticated
        using (realtime.messages.extension = 'broadcast'
               and (select private.can_receive_realtime((select realtime.topic()))))
        with check (false)
    $policy$;
    execute 'create policy cloud_realtime_closed_anon on realtime.messages as restrictive for all to anon
      using (false) with check (false)';
  else
    raise notice 'realtime.messages fehlt – Live-Signale ohne Wirkung (nur ohne Supabase-Realtime)';
  end if;
end;
$$;

-- Leerer Payload, je Konto höchstens ein Signal pro Transaktion (ein Upload mit vielen Punkten = ein Signal).
-- Gesperrte, nicht zugestimmte oder gelöschte Konten bekommen nichts.
create function private.send_live_signal(p_profiles uuid[])
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_profile uuid;
  v_sent text;
begin
  if to_regprocedure('realtime.send(jsonb,text,text,boolean)') is null then
    return;
  end if;
  for v_profile in select distinct p from unnest(p_profiles) p where p is not null loop
    v_sent := coalesce(current_setting('advantly.live_signaled', true), '');
    continue when strpos(v_sent, v_profile::text) > 0;
    -- Auch abgelehnte Konten merken: Massenänderungen (Kontolöschung) prüfen jedes Konto nur einmal.
    perform set_config('advantly.live_signaled', v_sent || v_profile::text || ',', true);
    continue when not private.cloud_profile_allowed(v_profile);
    perform realtime.send('{}'::jsonb, 'changed', 'live:' || v_profile::text, true);
  end loop;
end;
$$;

-- Wer das Match in cloud_my_live_matches_v1 sehen kann (ohne 12-h-Fenster): Mitspieler mit Konto, Zähler-Personen
-- (invited/accepted), nicht widerrufene Zuschauer; ohne Ersteller, Blockierung mit dem Ersteller, ausgeblendete Teilnahme.
create function private.live_signal_recipients(p_match uuid)
returns uuid[] language sql stable security definer set search_path = '' as $$
  select coalesce(array_agg(c.p), '{}') from (
      select mp.profile_id as p from public.match_participants mp where mp.match_id = p_match and mp.profile_id is not null
      union select r.profile_id from public.match_roles r where r.match_id = p_match and r.status in ('invited', 'accepted')
      union select v.profile_id from public.match_visitors v where v.match_id = p_match and v.revoked_at is null) c
    where c.p is distinct from (select m.created_by from public.matches m where m.id = p_match)
      and not private.is_blocked(c.p, (select m.created_by from public.matches m where m.id = p_match))
      and not exists (select 1 from public.match_participants mp
                      where mp.match_id = p_match and mp.profile_id = c.p and mp.hidden);
$$;

-- Live-Stand, Status, Löschung oder Kanalversion eines laufenden (oder gerade beendeten) Matches.
create function private.live_signal_match()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if old.status in ('in_progress', 'paused', 'awaiting_decision')
     or new.status in ('in_progress', 'paused', 'awaiting_decision') then
    perform private.send_live_signal(private.live_signal_recipients(new.id));
  end if;
  return null;
end;
$$;
create trigger matches_live_signal
  after update on public.matches
  for each row
  when (old.live_state is distinct from new.live_state or old.status is distinct from new.status
        or old.deleted_at is distinct from new.deleted_at or old.access_epoch is distinct from new.access_epoch)
  execute function private.live_signal_match();

-- Sichtbarkeit einzelner Konten: alte und neue Person bekommen das Signal (wer die Sicht verliert, lädt auch neu).
-- Nur für laufende Matches, nie der Ersteller.
create function private.live_signal_membership()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_match uuid := coalesce(new.match_id, old.match_id);
  v_creator uuid;
begin
  select m.created_by into v_creator from public.matches m
    where m.id = v_match and m.deleted_at is null and m.status in ('in_progress', 'paused', 'awaiting_decision');
  if found then
    perform private.send_live_signal(array(select p from unnest(array[old.profile_id, new.profile_id]) p
                                           where p is distinct from v_creator));
  end if;
  return null;
end;
$$;
create trigger match_participants_live_signal
  after insert or delete or update of profile_id, hidden on public.match_participants
  for each row execute function private.live_signal_membership();
create trigger match_roles_live_signal
  after insert or delete or update of profile_id, status on public.match_roles
  for each row execute function private.live_signal_membership();
create trigger match_visitors_live_signal
  after insert or delete or update of profile_id, revoked_at on public.match_visitors
  for each row execute function private.live_signal_membership();

-- Blockieren/Entsperren ändert die Live-Sicht beider Seiten (blocked_from_live, auch für verknüpfte Mitspieler).
create function private.live_signal_block()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  perform private.send_live_signal(array[old.blocker_id, old.blocked_id, new.blocker_id, new.blocked_id]);
  return null;
end;
$$;
create trigger user_blocks_live_signal
  after insert or delete on public.user_blocks
  for each row execute function private.live_signal_block();

revoke all on function private.send_live_signal(uuid[]), private.live_signal_recipients(uuid),
  private.live_signal_match(), private.live_signal_membership(), private.live_signal_block()
  from public, anon, authenticated, service_role;
