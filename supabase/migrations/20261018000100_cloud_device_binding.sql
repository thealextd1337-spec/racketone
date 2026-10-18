-- U1-v1/K4: Auth-Sitzung + kontogebundene Installations-Capability, keine Hardwareattestierung.
-- auth.sessions wird ausschließlich gelesen. Kein FK darauf: Auth-Logout darf Tombstones nicht löschen.
-- K1/003 erweitert ausschließlich require_cloud_profile()/cloud_profile_allowed(uuid) um Zustimmung.
create function private.cloud_profile_allowed(p_profile uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile);
$$;

create function private.require_cloud_profile()
returns uuid language plpgsql security definer set search_path = '' as $$
begin
  return private.require_active();
end;
$$;

-- Bind kennt weder Plattform noch Bauform. Erst explizite Metadaten im Upload setzen diese Felder.
alter table public.devices alter column kind drop not null;
alter table public.devices alter column platform drop not null;

create table private.cloud_installations (
  profile_id uuid not null references public.profiles(id) on delete cascade,
  installation_id uuid not null,
  device_id uuid not null unique references public.devices(id),
  proof_digest bytea not null check (octet_length(proof_digest) = 32),
  created_at timestamptz not null default clock_timestamp(),
  revoked_at timestamptz,
  primary key (profile_id, installation_id),
  unique (profile_id, installation_id, device_id)
);
create table private.cloud_session_bindings (
  session_id uuid primary key,
  profile_id uuid not null,
  installation_id uuid not null,
  device_id uuid not null,
  created_at timestamptz not null default clock_timestamp(),
  revoked_at timestamptz,
  foreign key (profile_id, installation_id, device_id)
    references private.cloud_installations(profile_id, installation_id, device_id) on delete cascade
);
create index cloud_session_installation_idx
  on private.cloud_session_bindings(profile_id, installation_id);
alter table private.cloud_installations enable row level security;
alter table private.cloud_session_bindings enable row level security;
revoke all on private.cloud_installations, private.cloud_session_bindings from public, anon, authenticated, service_role;

-- Nur Claims aus dem vom Gateway verifizierten JWT; niemals Konto-/Sitzungs-Parameter des Clients.
create function private.cloud_auth_session()
returns table (profile_id uuid, session_id uuid, created_at timestamptz)
language plpgsql stable security definer set search_path = '' as $$
declare
  v_claims jsonb := auth.jwt();
  v_uid uuid;
  v_sid uuid;
begin
  begin
    v_uid := (v_claims ->> 'sub')::uuid;
    v_sid := (v_claims ->> 'session_id')::uuid;
  exception when invalid_text_representation then
    perform private.fail('device_not_authorized', '42501');
  end;
  if v_uid is null or v_sid is null or v_claims ->> 'role' is distinct from 'authenticated'
     or v_uid is distinct from auth.uid() then
    perform private.fail('device_not_authorized', '42501');
  end if;
  return query select s.user_id, s.id, s.created_at from auth.sessions s
    where s.id = v_sid and s.user_id = v_uid
      and (s.not_after is null or s.not_after > statement_timestamp());
  if not found then
    perform private.fail('device_not_authorized', '42501');
  end if;
end;
$$;

create function private.cloud_capability()
returns table (installation_id uuid, proof_digest bytea)
language plpgsql stable security definer set search_path = '' as $$
declare
  v_headers jsonb;
  v_id uuid;
  v_proof text;
begin
  begin
    v_headers := nullif(current_setting('request.headers', true), '')::jsonb;
    v_id := (v_headers ->> 'x-advantly-installation-id')::uuid;
  exception when invalid_text_representation then
    perform private.fail('device_not_authorized', '42501');
  end;
  v_proof := v_headers ->> 'x-advantly-installation-proof';
  if v_id is null or v_proof is null or v_proof !~ '^[0-9a-fA-F]{64}$' then
    perform private.fail('device_not_authorized', '42501');
  end if;
  return query select v_id, sha256(decode(v_proof, 'hex'));
end;
$$;

create function private.require_cloud_binding()
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := private.require_cloud_profile();
  v_auth record;
  v_cap record;
  v_binding private.cloud_session_bindings%rowtype;
  v_install private.cloud_installations%rowtype;
begin
  -- require_cloud_profile nimmt die Versionssperre vor jeder Cloud-/Widerrufs-Mutation.
  select * into strict v_auth from private.cloud_auth_session();
  select * into strict v_cap from private.cloud_capability();
  select * into v_binding from private.cloud_session_bindings b where b.session_id = v_auth.session_id;
  if not found or v_binding.profile_id <> v_uid or v_binding.installation_id <> v_cap.installation_id then
    perform private.fail('device_not_authorized', '42501');
  end if;
  select * into strict v_install from private.cloud_installations i
    where i.profile_id = v_uid and i.installation_id = v_binding.installation_id;
  if v_install.proof_digest <> v_cap.proof_digest then
    perform private.fail('device_not_authorized', '42501');
  end if;
  if v_binding.revoked_at is not null or v_install.revoked_at is not null
     or exists (select 1 from public.devices d where d.id = v_binding.device_id and d.revoked_at is not null) then
    perform private.fail('device_revoked', '42501');
  end if;
  if not exists (select 1 from public.devices d where d.id = v_binding.device_id and d.profile_id = v_uid) then
    perform private.fail('device_not_authorized', '42501');
  end if;
  return v_binding.device_id;
end;
$$;

-- RLS liest nur, ohne Sperr-Upgrade eines SELECT. RPCs/Widerruf sind unter lock_sync serialisiert.
create function private.has_cloud_binding()
returns boolean language plpgsql stable security definer set search_path = '' as $$
declare
  v_auth record;
  v_cap record;
begin
  select * into strict v_auth from private.cloud_auth_session();
  select * into strict v_cap from private.cloud_capability();
  return private.cloud_profile_allowed(v_auth.profile_id) and exists (
    select 1 from private.cloud_session_bindings b
    join private.cloud_installations i using (profile_id, installation_id, device_id)
    join public.devices d on d.id = b.device_id and d.profile_id = b.profile_id
    where b.session_id = v_auth.session_id and b.profile_id = v_auth.profile_id
      and b.installation_id = v_cap.installation_id and i.proof_digest = v_cap.proof_digest
      and b.revoked_at is null and i.revoked_at is null and d.revoked_at is null);
exception when sqlstate '42501' then
  return false;
end;
$$;

create function public.bind_cloud_session_v1(p_installation_id uuid, p_device_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := private.require_cloud_profile();
  v_auth record;
  v_cap record;
  v_binding private.cloud_session_bindings%rowtype;
  v_install private.cloud_installations%rowtype;
begin
  perform private.lock_sync();
  select * into strict v_auth from private.cloud_auth_session();
  select * into strict v_cap from private.cloud_capability();
  if p_installation_id is distinct from v_cap.installation_id or p_device_id is null then
    perform private.fail('device_not_authorized', '42501');
  end if;
  select * into v_binding from private.cloud_session_bindings b where b.session_id = v_auth.session_id;
  if found then
    -- Bestehende Bindung/Tombstone hat Vorrang vor Zeitfenster und jeder neu behaupteten Identität.
    if v_binding.profile_id <> v_uid or v_binding.installation_id <> p_installation_id
       or v_binding.device_id <> p_device_id then
      perform private.fail('device_not_authorized', '42501');
    end if;
    perform private.require_cloud_binding();
  else
    if v_auth.created_at is null or v_auth.created_at > clock_timestamp()
       or v_auth.created_at < clock_timestamp() - interval '5 minutes' then
      perform private.fail('device_not_authorized', '42501');
    end if;
    select * into v_install from private.cloud_installations i
      where i.profile_id = v_uid and i.installation_id = p_installation_id;
    if found then
      if v_install.revoked_at is not null then
        perform private.fail('device_revoked', '42501');
      end if;
      if v_install.device_id <> p_device_id or v_install.proof_digest <> v_cap.proof_digest then
        perform private.fail('device_not_authorized', '42501');
      end if;
    else
      if exists (select 1 from public.devices d where d.id = p_device_id
                   and (d.profile_id <> v_uid or d.revoked_at is not null))
         or exists (select 1 from private.cloud_installations i where i.device_id = p_device_id) then
        perform private.fail('device_not_authorized', '42501');
      end if;
      insert into public.devices(id, profile_id) values (p_device_id, v_uid) on conflict (id) do nothing;
      insert into private.cloud_installations(profile_id, installation_id, device_id, proof_digest)
        values (v_uid, p_installation_id, p_device_id, v_cap.proof_digest);
    end if;
    insert into private.cloud_session_bindings(session_id, profile_id, installation_id, device_id)
      values (v_auth.session_id, v_uid, p_installation_id, p_device_id);
    perform private.require_cloud_binding();
  end if;
  return jsonb_build_object('contractVersion', 1, 'profileId', v_uid, 'sessionId', v_auth.session_id,
                           'installationId', p_installation_id, 'deviceId', p_device_id);
end;
$$;

-- Registrierung bleibt für eigene Quellgeräte möglich, ist aber kein Ersatz für Caller-Bindung.
-- Kein Umhängen historischer Eigentümer; kein Wiederbeleben widerrufener Geräte.
create or replace function public.register_device(p_id uuid, p_kind text, p_platform text,
  p_paired_with uuid default null, p_standalone boolean default false,
  p_model text default null, p_app_version text default null)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := private.require_active();
  v_device public.devices%rowtype;
begin
  perform private.require_cloud_binding();
  if p_id is null or p_kind is null or p_platform is null then
    perform private.fail('invalid_device');
  end if;
  select * into v_device from public.devices d where d.id = p_id for update;
  if found then
    if v_device.profile_id <> v_uid then
      perform private.fail('device_owned_by_other', '42501');
    end if;
    if v_device.revoked_at is not null then
      perform private.fail('device_revoked', '42501');
    end if;
  end if;
  if p_paired_with is not null and not exists (
    select 1 from public.devices d where d.id = p_paired_with and d.profile_id = v_uid and d.revoked_at is null) then
    perform private.fail('paired_device_unknown');
  end if;
  insert into public.devices(id, profile_id, kind, platform, paired_with, standalone, model, app_version)
    values (p_id, v_uid, p_kind, p_platform, p_paired_with, coalesce(p_standalone, false), p_model, p_app_version)
  on conflict (id) do update set kind = excluded.kind, platform = excluded.platform,
    paired_with = excluded.paired_with, standalone = excluded.standalone, model = excluded.model,
    app_version = excluded.app_version, last_seen_at = now();
end;
$$;

-- Historische Implementierungen behalten Fach-/Wire-Verhalten, erhalten aber vorab dieselbe Prüfung.
-- Der Migrationsblock scheitert bei unerwarteter Signatur/Sprache; keine still ausgelassene Alt-RPC.
do $$
declare
  v_signature text;
  v_oid oid;
  v_definition text;
begin
  foreach v_signature in array array[
    'public.sync_push(jsonb)', 'public.sync_pull(bigint,integer)',
    'public.find_profile(text)', 'public.send_friend_request(uuid,text)', 'public.respond_friend_request(uuid,boolean)',
    'public.remove_friend(uuid)', 'public.block_user(uuid)', 'public.unblock_user(uuid)',
    'public.get_visitor_match(uuid)', 'public.my_visitor_matches()', 'public.tournament_overview(uuid)',
    'public.invite_scorer(uuid,uuid,text)', 'public.respond_scorer_invitation(uuid,boolean,uuid)',
    'public.revoke_scorer(uuid,uuid)', 'public.add_match_visitor(uuid,uuid)', 'public.revoke_match_visitor(uuid,uuid)',
    'public.assign_player(uuid,uuid)', 'public.reject_player_assignment(uuid)', 'public.unassign_player(uuid)',
    'public.create_invite(text,uuid,integer)', 'public.redeem_invite(text)',
    'public.reject_match_participation(uuid)', 'public.set_my_participation(uuid,boolean,boolean)',
    'public.add_tournament_staff(uuid,uuid,text)', 'public.remove_tournament_staff(uuid,uuid)'
  ] loop
    v_oid := v_signature::regprocedure;
    select pg_get_functiondef(v_oid) into v_definition;
    if (select l.lanname from pg_proc p join pg_language l on l.oid = p.prolang where p.oid = v_oid) <> 'plpgsql'
       or v_definition !~ E'\\mbegin\\M' then
      raise exception 'Unerwartete Cloud-RPC: %', v_signature;
    end if;
    v_definition := regexp_replace(v_definition, E'\\mbegin\\M',
      E'begin\n  perform private.require_cloud_binding();', 'i');
    execute v_definition;
  end loop;
end;
$$;

create function public.cloud_sync_push_v1(p_batch jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  if octet_length(p_batch::text) > 2097152 then perform private.fail('batch_too_large'); end if;
  return public.sync_push(p_batch);
end;
$$;
create function public.cloud_sync_pull_v1(p_cursor bigint default 0, p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  if p_limit is null or p_limit not between 1 and 200 then perform private.fail('invalid_limit'); end if;
  return public.sync_pull(p_cursor, p_limit);
end;
$$;

-- SECURITY INVOKER erhält RLS-/Mitgliedschaftsprüfung der bisherigen Event-RPCs.
create function public.cloud_pull_match_events_v1(p_match_id uuid, p_after_server_seq bigint default 0,
                                                p_limit integer default 500)
returns table ("id" uuid, "matchId" uuid, "deviceId" uuid, "deviceSeq" bigint, "lamport" bigint,
  "wallClockMs" bigint, "parentId" uuid, "type" text, "payload" jsonb, "serverSeq" bigint, "redacted" boolean)
language plpgsql security invoker set search_path = '' as $$
begin
  perform private.require_cloud_binding();
  if p_limit is null or p_limit not between 1 and 500 then raise exception 'invalid_limit'; end if;
  return query select * from public.pull_match_events(p_match_id, p_after_server_seq, p_limit);
end;
$$;
create function public.cloud_match_event_watermarks_v1(p_match_id uuid)
returns table ("deviceId" uuid, "maxSeq" bigint, "eventCount" bigint)
language plpgsql security invoker set search_path = '' as $$
begin
  perform private.require_cloud_binding();
  return query select * from public.match_event_watermarks(p_match_id);
end;
$$;
create function public.cloud_get_visitor_match_v1(p_match_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  return public.get_visitor_match(p_match_id);
end;
$$;

-- Restriktive Zusatzpolicy: vorhandene fachliche Policies bleiben notwendig, aber reichen allein nie.
-- Auch Profile/Freunde/Einstellungen werden nicht über einen WebSocket ohne Capability ausgeliefert.
do $$
declare v_table text;
begin
  foreach v_table in array array['profiles','profile_settings','user_blocks','friendships','devices',
    'players','player_assignment_rejections','tournaments','tournament_courts','tournament_staff',
    'matches','match_participants','match_link_rejections','match_roles','match_visitors','match_events','invites','entitlements']
  loop
    execute format('create policy cloud_binding_required on public.%I as restrictive for all to authenticated
      using ((select private.has_cloud_binding())) with check ((select private.has_cloud_binding()))', v_table);
  end loop;
end;
$$;
revoke delete on public.devices from public, anon, authenticated;
drop policy devices_delete_own on public.devices;

-- K4: eigene Übersicht/Widerruf benötigt reale Auth-Sitzung und Profil, aber keine Geräte-Capability.
-- So bleibt Recovery nach Selbstwiderruf möglich; globale Auth-Abmeldung ist eine andere Operation.
create function public.cloud_list_installations_v1()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  perform private.lock_sync();
  perform private.cloud_auth_session();
  if not private.has_profile(v_uid) then perform private.fail('profile_missing', '42501'); end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
    'installationId', i.installation_id, 'deviceId', d.id, 'createdAt', i.created_at,
    'revokedAt', coalesce(i.revoked_at, d.revoked_at), 'kind', d.kind, 'platform', d.platform,
    'model', d.model, 'appVersion', d.app_version, 'lastSeenAt', d.last_seen_at,
    'sessions', (select coalesce(jsonb_agg(jsonb_build_object('sessionId', b.session_id,
      'createdAt', b.created_at, 'revokedAt', b.revoked_at,
      'authSessionExists', exists (select 1 from auth.sessions s where s.id = b.session_id and s.user_id = v_uid))
      order by b.created_at, b.session_id), '[]'::jsonb)
      from private.cloud_session_bindings b where b.profile_id = i.profile_id and b.installation_id = i.installation_id))
    order by i.created_at, i.installation_id), '[]'::jsonb)
    from private.cloud_installations i join public.devices d on d.id = i.device_id where i.profile_id = v_uid);
end;
$$;
create function public.cloud_revoke_installation_v1(p_installation_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_device uuid;
begin
  perform private.lock_sync();
  perform private.cloud_auth_session();
  if not private.has_profile(v_uid) then perform private.fail('profile_missing', '42501'); end if;
  update private.cloud_installations set revoked_at = coalesce(revoked_at, clock_timestamp())
    where profile_id = v_uid and installation_id = p_installation_id returning device_id into v_device;
  if not found then perform private.fail('device_not_authorized', '42501'); end if;
  update private.cloud_session_bindings set revoked_at = coalesce(revoked_at, clock_timestamp())
    where profile_id = v_uid and installation_id = p_installation_id;
  update public.devices set revoked_at = coalesce(revoked_at, clock_timestamp()), push_token = null, push_provider = null
    where id = v_device and profile_id = v_uid;
  return jsonb_build_object('installationId', p_installation_id, 'revoked', true);
end;
$$;
create function public.cloud_revoke_session_v1(p_session_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  perform private.lock_sync();
  perform private.cloud_auth_session();
  if not private.has_profile(v_uid) then perform private.fail('profile_missing', '42501'); end if;
  update private.cloud_session_bindings set revoked_at = coalesce(revoked_at, clock_timestamp())
    where profile_id = v_uid and session_id = p_session_id;
  if not found then perform private.fail('device_not_authorized', '42501'); end if;
  return jsonb_build_object('sessionId', p_session_id, 'revoked', true);
end;
$$;

-- Einschließlich historischer Quellgeräte ohne Caller-Installation; keine Push-Token in der Übersicht.
create function public.cloud_list_devices_v1()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  perform private.lock_sync();
  perform private.cloud_auth_session();
  if not private.has_profile(v_uid) then perform private.fail('profile_missing', '42501'); end if;
  return (select coalesce(jsonb_agg(jsonb_build_object('deviceId', d.id, 'installationId', i.installation_id,
    'kind', d.kind, 'platform', d.platform, 'model', d.model, 'appVersion', d.app_version,
    'lastSeenAt', d.last_seen_at, 'revokedAt', coalesce(d.revoked_at, i.revoked_at)) order by d.created_at, d.id), '[]'::jsonb)
    from public.devices d left join private.cloud_installations i on i.device_id = d.id where d.profile_id = v_uid);
end;
$$;
create function public.cloud_revoke_device_v1(p_device_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_installation uuid;
begin
  perform private.lock_sync();
  perform private.cloud_auth_session();
  if not private.has_profile(v_uid) then perform private.fail('profile_missing', '42501'); end if;
  update public.devices set revoked_at = coalesce(revoked_at, clock_timestamp()), push_token = null, push_provider = null
    where id = p_device_id and profile_id = v_uid;
  if not found then perform private.fail('device_not_authorized', '42501'); end if;
  select installation_id into v_installation from private.cloud_installations where device_id = p_device_id;
  if found then perform public.cloud_revoke_installation_v1(v_installation); end if;
  return jsonb_build_object('deviceId', p_device_id, 'revoked', true);
end;
$$;

-- Realtime bleibt geschlossen: REST-Header sind keine WebSocket-Capability.
-- Keine neuen Nutzdaten selbst für bereits autorisierte/zwischengespeicherte Kanal-Abos.
create or replace function private.can_receive_realtime(p_topic text, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$ select false; $$;
create or replace function private.realtime_send(p_payload jsonb, p_event text, p_topic text)
returns void language plpgsql security definer set search_path = '' as $$ begin return; end; $$;
do $$
declare v_table record;
begin
  if to_regclass('realtime.messages') is not null then
    execute 'create policy cloud_realtime_closed on realtime.messages as restrictive for all to anon, authenticated
      using (false) with check (false)';
    execute 'revoke all on realtime.messages from public, anon, authenticated';
  end if;
  -- Auch DELETE-Replikation ohne RLS-Zeilenprüfung darf keine Cloud-Daten verraten.
  for v_table in select schemaname, tablename from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname in ('public', 'private', 'ops')
  loop
    execute format('alter publication supabase_realtime drop table %I.%I', v_table.schemaname, v_table.tablename);
  end loop;
end;
$$;
-- Grenze: öffentliche Broadcast-/Presence-Kanäle umgehen SQL-RLS. Vor jedem Realtime-Release muss der
-- Betreiber zusätzlich private_only=true setzen und bestehende Sockets trennen (Control Plane, kein SQL-RPC).
-- Diese Migration sendet keinerlei App-Broadcasts und gibt keine Postgres-Changes-Publikation frei.

revoke all on function private.cloud_profile_allowed(uuid), private.require_cloud_profile(),
  private.cloud_auth_session(), private.cloud_capability(), private.require_cloud_binding(), private.has_cloud_binding()
  from public, anon, authenticated, service_role;
grant execute on function private.has_cloud_binding(), private.require_cloud_binding() to authenticated;
revoke all on function public.bind_cloud_session_v1(uuid,uuid), public.cloud_sync_push_v1(jsonb),
  public.cloud_sync_pull_v1(bigint,integer), public.cloud_pull_match_events_v1(uuid,bigint,integer),
  public.cloud_match_event_watermarks_v1(uuid), public.cloud_get_visitor_match_v1(uuid),
  public.cloud_list_installations_v1(), public.cloud_revoke_installation_v1(uuid), public.cloud_revoke_session_v1(uuid),
  public.cloud_list_devices_v1(), public.cloud_revoke_device_v1(uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.bind_cloud_session_v1(uuid,uuid), public.cloud_sync_push_v1(jsonb),
  public.cloud_sync_pull_v1(bigint,integer), public.cloud_pull_match_events_v1(uuid,bigint,integer),
  public.cloud_match_event_watermarks_v1(uuid), public.cloud_get_visitor_match_v1(uuid),
  public.cloud_list_installations_v1(), public.cloud_revoke_installation_v1(uuid), public.cloud_revoke_session_v1(uuid),
  public.cloud_list_devices_v1(), public.cloud_revoke_device_v1(uuid)
  to authenticated;
