-- K2: Feldschemas aus core-model/MatchFormat.kt, MatchMeta.kt, core-scoring/Events.kt,
-- MatchState.kt und data/MatchRepository.kt. Null bleibt Null, falsche Typen werden Null.
create function private.export_shape(p_value jsonb, p_schema jsonb) returns jsonb
language plpgsql immutable set search_path = '' as $$
declare v_out jsonb; v_key text; v_spec jsonb; v_item jsonb;
begin
 if p_value is null or p_value = 'null'::jsonb then return p_value; end if;
 if jsonb_typeof(p_schema) = 'string' then
   if jsonb_typeof(p_value) = (p_schema #>> '{}') then return p_value; end if;
   return 'null'::jsonb;
 elsif jsonb_typeof(p_schema) = 'array' then
   if jsonb_typeof(p_value) <> 'array' then return 'null'::jsonb; end if;
   v_out := '[]';
   for v_item in select value from jsonb_array_elements(p_value) loop
     v_out := v_out || jsonb_build_array(private.export_shape(v_item,p_schema->0));
   end loop;
   return v_out;
 end if;
 if jsonb_typeof(p_value) <> 'object' then return 'null'::jsonb; end if;
 v_out := '{}';
 for v_key,v_spec in select key,value from jsonb_each(p_schema) loop
   if p_value ? v_key then v_out := v_out || jsonb_build_object(v_key,private.export_shape(p_value->v_key,v_spec)); end if;
 end loop;
 return v_out;
end;
$$;
create or replace function private.export_format(p_value jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_value,'{"formatVersion":"number","presetId":"string","sport":"string","discipline":"string","match":{"mode":"string","bestOf":"number"},"game":{"deuceMode":"string","maxAdvantages":"number"},"set":{"kind":"string","gamesToWin":"number","tiebreakAt":"number","tiebreak":{"pointsToWin":"number","winBy":"number","endChange":{"first":"number","every":"number"}}},"decidingSet":{"kind":"string","gamesToWin":"number","tiebreakAt":"number","tiebreak":{"pointsToWin":"number","winBy":"number","endChange":{"first":"number","every":"number"}},"pointsToWin":"number","winBy":"number","endChange":{"first":"number","every":"number"}},"matchTiebreak":{"kind":"string","gamesToWin":"number","tiebreakAt":"number","tiebreak":{"pointsToWin":"number","winBy":"number","endChange":{"first":"number","every":"number"}},"pointsToWin":"number","winBy":"number","endChange":{"first":"number","every":"number"}},"pickleball":{"pointsToWin":"number","winBy":"number","pointCap":"number","midGameEndChangeAt":"number","firstServiceTurnSingleServer":"boolean","gameWinningPointOnServeOnly":"boolean","scoring":"string","midGameEndChange":"string"},"rally":{"pointsToWin":"number","winBy":"number","cap":"number","serveEvery":"number","serveEveryAtDeuce":"number","midGameEndChangeAt":"number","intervalAt":"number","serveRotation":"string","nextGameFirstServer":"string","midGameEndChange":"string","receiverSwapAtMidGameEndChange":"boolean"}}'::jsonb);
$$;
create or replace function private.export_meta(p_value jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_value,'{"surface":"string","environment":"string","venue":"string","mixed":"boolean","watchWorkout":"boolean","serveStats":"boolean","courtPace":"number"}'::jsonb);
$$;
create or replace function private.export_summary(p_value jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_value,'{"winner":"string","reason":"string","setsWonA":"number","setsWonB":"number","sets":[{"a":"number","b":"number","tiebreakA":"number","tiebreakB":"number","matchTiebreak":"boolean"}],"current":{"a":"number","b":"number","tiebreakA":"number","tiebreakB":"number","matchTiebreak":"boolean"},"completedSets":[{"kind":"string","games":{"A":"number","B":"number"},"tiebreak":{"A":"number","B":"number"},"points":{"A":"number","B":"number"},"winner":"string"}],"currentState":{"setIndex":"number","setKind":"string","gameKind":"string","games":{"A":"number","B":"number"},"points":{"A":"number","B":"number"},"display":{"A":"string","B":"string"},"callout":"string","deuceCount":"number","decidingPoint":"boolean"},"lastEventAtMs":"number","eventCount":"number"}'::jsonb);
$$;
create or replace function private.export_detail(p_detail jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_detail,'{"kind":"string","secondServe":"boolean","serve":"string"}'::jsonb);
$$;
create or replace function private.export_payload(p_type text, p_payload jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  -- Nur optionale Felder der bisherigen Eventhülle auslassen. Fachobjekte behalten
  -- ausdrückliches Null (every:null ist semantisch verschieden vom Default 6).
  select coalesce(jsonb_object_agg(e.key,e.value),'{}'::jsonb)
  from jsonb_each(case p_type
    when 'MatchCreated' then jsonb_build_object('type', p_type, 'config', private.export_format(p_payload -> 'config'), 'meta', private.export_meta(p_payload -> 'meta'))
    when 'PointWon' then jsonb_build_object('type', p_type, 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb),
                                            'detail', private.export_detail(p_payload -> 'detail'))
    when 'Undo' then jsonb_build_object('type', p_type, 'target', private.export_shape(p_payload -> 'target', '"string"'::jsonb))
    when 'ContinueDecision' then jsonb_build_object('type', p_type, 'anotherSet', private.export_shape(p_payload -> 'anotherSet', '"boolean"'::jsonb),
                                                    'nextSetKind', private.export_shape(p_payload -> 'nextSetKind', '"string"'::jsonb))
    when 'Correction' then jsonb_build_object('type', p_type, 'kind', private.export_shape(p_payload -> 'kind', '"string"'::jsonb), 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb),
                                              'serverNumber', private.export_shape(p_payload -> 'serverNumber', '"number"'::jsonb))
    when 'MatchEnded' then jsonb_build_object('type', p_type, 'reason', private.export_shape(p_payload -> 'reason', '"string"'::jsonb), 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb))
    when 'PointDetailSet' then jsonb_build_object('type', p_type, 'target', private.export_shape(p_payload -> 'target', '"string"'::jsonb),
                                                  'detail', private.export_detail(p_payload -> 'detail'))
    when 'ServerChosen' then jsonb_build_object('type', p_type, 'set', private.export_shape(p_payload -> 'set', '"number"'::jsonb), 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb))
    else jsonb_build_object('type', p_type)   -- Paused, Resumed, ScorerChanged, PlayerAssigned: nur der Typ
  end) e
  where e.value <> 'null'::jsonb;
$$;
create function private.export_support_ticket(p_id uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
 select jsonb_build_object('id',t.id,'category',t.category,'title',t.title,'body',t.body,
 'contact_email',t.contact_email,'diagnostics',private.export_shape(t.diagnostics,
 '{"app_version":"string","commit":"string","os":"string","device":"string","display":"string","font_scale":"string","error_code":"string"}'),
 'status',t.status,'priority',t.priority,'resolution_version',t.resolution_version,
 'version',t.version,'created_at',t.created_at,'updated_at',t.updated_at,
 'messages',coalesce((select jsonb_agg(jsonb_build_object('id',m.id,'body',m.body,
 'internal',false,'author_id',null,'created_at',m.created_at) order by m.created_at,m.id)
 from ops.support_messages m where m.ticket_id=t.id and not m.internal),'[]'::jsonb))
 from ops.support_tickets t where t.id=p_id;
$$;
create or replace function public.export_my_data()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not exists (select 1 from public.profiles where id = v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  perform private.lock_sync();
  perform private.require_account_session();
  return jsonb_build_object(
    'support', coalesce((select jsonb_agg(private.export_support_ticket(t.id) order by t.created_at, t.id) from ops.support_tickets t where t.owner_id=v_uid),'[]'::jsonb),
    'consentHistory', (select coalesce(jsonb_agg(jsonb_build_object(
      'type', h.document_type, 'version', h.version, 'language', h.language,
      'accepted', h.accepted, 'decidedAt', h.decided_at) order by h.id), '[]'::jsonb)
      from private.consent_history h where h.profile_id = v_uid),
    'exportedAt', now(),
    'profile', (select jsonb_build_object('id', p.id, 'alias', p.alias, 'username', p.username,
                                          'friendCode', p.friend_code, 'discoverable', p.discoverable,
                                          'status', p.status, 'createdAt', p.created_at)
                from public.profiles p where p.id = v_uid),
    'settings', (select jsonb_build_object('country', s.country, 'ageConfirmedAt', s.age_confirmed_at,
                                           'telemetryVisibility', s.telemetry_visibility,
                                           'shareMatchesDefault', s.share_matches_default, 'locale', s.locale,
                                           'deletionRequestedAt', s.deletion_requested_at)
                 from public.profile_settings s where s.profile_id = v_uid),
    'devices', (select coalesce(jsonb_agg(jsonb_build_object('id', d.id, 'kind', d.kind, 'platform', d.platform,
                                                             'model', d.model, 'appVersion', d.app_version,
                                                             'lastSeenAt', d.last_seen_at)), '[]'::jsonb)
                from public.devices d where d.profile_id = v_uid),
    'friends', (select coalesce(jsonb_agg(jsonb_build_object(
                   'alias', o.alias, 'status', f.status, 'outgoing', f.requester_id = v_uid,
                   'createdAt', f.created_at)), '[]'::jsonb)
                from public.friendships f
                join public.profiles o on o.id = case when f.requester_id = v_uid then f.addressee_id else f.requester_id end
                where v_uid in (f.requester_id, f.addressee_id)),
    'blockedCount', (select count(*) from public.user_blocks b where b.blocker_id = v_uid),
    'players', (select coalesce(jsonb_agg(case when p.owner_id = v_uid
                   then jsonb_build_object('id', p.id, 'kind', p.kind, 'displayName', p.display_name,
                                           'assignedToFriend', p.profile_id is not null and p.profile_id <> v_uid,
                                           'deleted', p.deleted_at is not null)
                   else jsonb_build_object('id', p.id, 'kind', p.kind, 'displayName', p.display_name,
                                           'assignedToMe', true, 'source', p.assignment_source) end), '[]'::jsonb)
                from public.players p where p.owner_id = v_uid or p.profile_id = v_uid),
    'matches', (select coalesce(jsonb_agg(jsonb_build_object(
                   'id', m.id, 'createdByMe', m.created_by = v_uid, 'sport', m.sport, 'discipline', m.discipline,
                   'format', private.export_format(m.format), 'meta', private.export_meta(m.meta), 'status', m.status, 'resultReason', m.result_reason,
                   'winner', m.winner, 'startedAt', m.started_at, 'endedAt', m.ended_at, 'summary', private.export_summary(m.summary),
                   'participants', (select jsonb_agg(jsonb_build_object(
                                       'team', mp.team, 'position', mp.position, 'displayName', mp.display_name,
                                       'isMe', mp.profile_id = v_uid) order by mp.team, mp.position)
                                    from public.match_participants mp where mp.match_id = m.id),
                   'events', (select coalesce(jsonb_agg(case when e.author_id = v_uid
                                 then jsonb_build_object('id', e.id, 'deviceId', e.device_id, 'deviceSeq', e.device_seq,
                                                         'lamport', e.lamport, 'wallClockMs', e.wall_clock_ms,
                                                         'parentId', e.parent_id, 'type', e.type,
                                                         'payload', private.export_payload(e.type, e.payload),
                                                         'mine', true)
                                 else jsonb_build_object('id', e.id, 'lamport', e.lamport, 'wallClockMs', e.wall_clock_ms,
                                                         'type', e.type, 'mine', false) end
                                 order by e.lamport, e.device_id, e.id), '[]'::jsonb)
                              from public.match_events e where e.match_id = m.id))), '[]'::jsonb)
                from public.matches m
                where m.created_by = v_uid
                   or exists (select 1 from public.match_participants mp
                              where mp.match_id = m.id and mp.profile_id = v_uid)),
    'roles', (select coalesce(jsonb_agg(jsonb_build_object('matchId', r.match_id, 'role', r.role, 'status', r.status,
                                                           'invitedAt', r.invited_at, 'respondedAt', r.responded_at)),
                              '[]'::jsonb)
              from public.match_roles r where r.profile_id = v_uid),
    'visitorOf', (select coalesce(jsonb_agg(jsonb_build_object('matchId', v.match_id, 'createdAt', v.created_at,
                                                               'revokedAt', v.revoked_at)), '[]'::jsonb)
                  from public.match_visitors v where v.profile_id = v_uid),
    'entitlements', (select coalesce(jsonb_agg(jsonb_build_object('entitlement', e.entitlement, 'source', e.source,
                                                                  'productId', e.product_id, 'expiresAt', e.expires_at)),
                                     '[]'::jsonb)
                     from public.entitlements e where e.profile_id = v_uid));
end;
$$;
-- Private Metadaten. Objektpfade enthalten keinerlei Konto-/Requestkennung.
create table private.data_export_epoch(singleton boolean primary key default true check(singleton), epoch bigint not null);
insert into private.data_export_epoch values(true,1);
create table private.data_exports (
  owner_id uuid not null references public.profiles(id) on delete cascade,
  request_id uuid not null,
  attempt_id uuid not null unique,
  object_path text not null unique,
  epoch bigint not null,
  status text not null check(status in ('pending','ready','failed','too_large','expired')),
  lease_until timestamptz not null,
  expires_at timestamptz not null,
  exported_at timestamptz,
  byte_length integer,
  sha256 text,
  primary key(owner_id,request_id)
);
-- Dauerhafte Tombstones ohne Besitzer-FK: auch NACH erfolgreichem Delete wiederholen.
-- Damit werden beliebig verspätete Uploads veralteter Produzenten erneut physisch entfernt.
create table private.data_export_objects (
  object_path text primary key,
  next_cleanup_at timestamptz not null,
  last_deleted_at timestamptz
);
create index data_export_objects_due on private.data_export_objects(next_cleanup_at);
alter table private.data_export_epoch enable row level security;
alter table private.data_exports enable row level security;
alter table private.data_export_objects enable row level security;
revoke all on private.data_export_epoch,private.data_exports,private.data_export_objects from public,anon,authenticated,service_role;

create function private.data_export_purge() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform private.lock_sync();
  update private.data_export_epoch set epoch=epoch+1;
  update private.data_export_objects set next_cleanup_at=clock_timestamp()
    where object_path in (select object_path from private.data_exports);
  update private.data_exports set status='expired';
  return old;
end;
$$;
create trigger profiles_data_export_purge before delete on public.profiles
  for each row execute function private.data_export_purge();

create function private.data_export_state(p_row private.data_exports) returns jsonb
language sql volatile set search_path = '' as $$
 select jsonb_build_object('contractVersion',1,'requestId',p_row.request_id,
 'status',case when p_row.expires_at<=clock_timestamp() or p_row.epoch<>(select epoch from private.data_export_epoch)
   then 'expired' else p_row.status end,
 'exportedAt',p_row.exported_at,'expiresAt',p_row.expires_at,
 'byteLength',p_row.byte_length,'sha256',p_row.sha256);
$$;

create function public.data_export_status_v1(p_request_id uuid, p_download boolean default false) returns jsonb
language plpgsql security definer set search_path = '' set lock_timeout='2s' set statement_timeout='5s' as $$
declare v_uid uuid; v_row private.data_exports; v_state jsonb;
begin
  perform private.lock_sync();
  v_uid:=private.require_account_session();
  select * into v_row from private.data_exports where owner_id=v_uid and request_id=p_request_id;
  if not found then return jsonb_build_object('error','not_found'); end if;
  v_state:=private.data_export_state(v_row);
  if p_download and v_state->>'status'='ready' then
    v_state:=v_state||jsonb_build_object('objectPath',v_row.object_path,'attemptId',v_row.attempt_id);
  end if;
  return v_state;
end;
$$;

create function public.request_data_export_v1(p_request_id uuid) returns jsonb
language plpgsql security definer set search_path = '' set lock_timeout='2s' set statement_timeout='15s' as $$
declare v_uid uuid; v_row private.data_exports; v_attempt uuid; v_path text; v_epoch bigint;
begin
  perform private.lock_sync();
  v_uid:=private.require_account_session();
  if not exists(select 1 from public.profiles where id=v_uid) then perform private.fail('not_authenticated','42501'); end if;
  if p_request_id is null then return jsonb_build_object('error','invalid_request'); end if;
  select * into v_row from private.data_exports where owner_id=v_uid and request_id=p_request_id;
  if found then
    if v_row.status <> 'pending' or v_row.expires_at<=clock_timestamp()
      or v_row.lease_until>clock_timestamp() then return private.data_export_state(v_row); end if;
  end if;
  -- Pro Konto genau ein aktiver Produzent, auch mit verschiedenen Request-IDs.
  if exists(select 1 from private.data_exports where owner_id=v_uid and status='pending'
    and lease_until>clock_timestamp() and expires_at>clock_timestamp()) then
    return jsonb_build_object('error','export_busy');
  end if;
  v_attempt:=gen_random_uuid(); v_path:=gen_random_uuid()::text||'/'||gen_random_uuid()::text||'.zip';
  select epoch into v_epoch from private.data_export_epoch;
  -- Registrierung VOR jedem Upload, nie Pfadwiederverwendung.
  insert into private.data_export_objects values(v_path,clock_timestamp()+interval '90 seconds',null);
  insert into private.data_exports as e(owner_id,request_id,attempt_id,object_path,epoch,status,lease_until,expires_at)
    values(v_uid,p_request_id,v_attempt,v_path,v_epoch,'pending',clock_timestamp()+interval '90 seconds',clock_timestamp()+interval '24 hours')
    on conflict(owner_id,request_id) do update set attempt_id=excluded.attempt_id,object_path=excluded.object_path,
      epoch=excluded.epoch,status='pending',lease_until=excluded.lease_until,expires_at=excluded.expires_at,
      exported_at=null,byte_length=null,sha256=null
    returning * into v_row;
  return private.data_export_state(v_row)||jsonb_build_object('producer',true,'attemptId',v_attempt,'objectPath',v_path);
end;
$$;

create function public.data_export_snapshot_v1(p_request_id uuid,p_attempt_id uuid) returns jsonb
language plpgsql security definer set search_path = '' set lock_timeout='2s' set statement_timeout='15s' as $$
declare v_uid uuid; v_data jsonb;
begin
  perform private.lock_sync();
  v_uid:=private.require_account_session();
  if not exists(select 1 from private.data_exports where owner_id=v_uid and request_id=p_request_id
    and attempt_id=p_attempt_id and status='pending' and lease_until>clock_timestamp()
    and epoch=(select epoch from private.data_export_epoch)) then return jsonb_build_object('error','export_unavailable'); end if;
  v_data:=public.export_my_data();
  if octet_length(v_data::text)>4194304 then return jsonb_build_object('error','export_too_large'); end if;
  return jsonb_build_object('data',v_data);
end;
$$;

create function public.finalize_data_export_v1(p_attempt_id uuid,p_result text,p_exported_at timestamptz default null,
 p_byte_length integer default null,p_sha256 text default null) returns boolean
language plpgsql security definer set search_path = '' set lock_timeout='2s' set statement_timeout='5s' as $$
declare v_path text; v_expiry timestamptz;
begin
  perform private.require_account_read_committed();
  perform private.lock_sync();
  if p_result is null or p_result not in ('ready','failed','too_large') then return false; end if;
  if p_result='ready' and (p_exported_at is null or p_byte_length is null or p_byte_length not between 1 and 16777216
    or p_sha256 is null or p_sha256 !~ '^[0-9a-f]{64}$') then return false; end if;
  update private.data_exports set status=p_result,exported_at=p_exported_at,byte_length=p_byte_length,sha256=p_sha256
    where attempt_id=p_attempt_id and status='pending' and lease_until>clock_timestamp()
      and expires_at>clock_timestamp() and epoch=(select epoch from private.data_export_epoch)
    returning object_path,expires_at into v_path,v_expiry;
  if not found then return false; end if;
  update private.data_export_objects set next_cleanup_at=case when p_result='ready' then v_expiry else clock_timestamp() end
    where object_path=v_path;
  return true;
end;
$$;

create function public.claim_data_export_cleanup_v1() returns jsonb
language plpgsql security definer set search_path = '' set lock_timeout='2s' set statement_timeout='5s' as $$
declare v_paths jsonb;
begin
  perform private.require_account_read_committed();
  perform private.lock_sync();
  update private.data_exports set status='expired' where status in ('ready','pending') and
    (expires_at<=clock_timestamp() or epoch<>(select epoch from private.data_export_epoch));
  select coalesce(jsonb_agg(object_path),'[]'::jsonb) into v_paths from (
    select o.object_path from private.data_export_objects o where o.next_cleanup_at<=clock_timestamp()
      and not exists(select 1 from private.data_exports e where e.object_path=o.object_path and
        ((e.status='ready' and e.expires_at>clock_timestamp()) or (e.status='pending' and e.lease_until>clock_timestamp()))
        and e.epoch=(select epoch from private.data_export_epoch))
    order by o.next_cleanup_at,o.object_path limit 100) q;
  return v_paths;
end;
$$;

create function public.ack_data_export_cleanup_v1(p_paths text[]) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform private.require_account_read_committed();
  perform private.lock_sync();
  if cardinality(p_paths)>100 then perform private.fail('invalid_request'); end if;
  update private.data_export_objects set last_deleted_at=clock_timestamp(),next_cleanup_at=clock_timestamp()+interval '1 hour'
    where object_path=any(p_paths);
end;
$$;

revoke all on function private.export_shape(jsonb,jsonb),private.export_format(jsonb),private.export_meta(jsonb),
 private.export_summary(jsonb),private.export_detail(jsonb),private.export_payload(text,jsonb),private.export_support_ticket(uuid),
 private.data_export_purge(),private.data_export_state(private.data_exports) from public,anon,authenticated,service_role;
revoke all on function public.data_export_status_v1(uuid,boolean),public.request_data_export_v1(uuid),
 public.data_export_snapshot_v1(uuid,uuid),public.finalize_data_export_v1(uuid,text,timestamptz,integer,text),
 public.claim_data_export_cleanup_v1(),public.ack_data_export_cleanup_v1(text[]) from public,anon,authenticated,service_role;
grant execute on function public.data_export_status_v1(uuid,boolean),public.request_data_export_v1(uuid),
 public.data_export_snapshot_v1(uuid,uuid) to authenticated;
grant execute on function public.finalize_data_export_v1(uuid,text,timestamptz,integer,text),
 public.claim_data_export_cleanup_v1(),public.ack_data_export_cleanup_v1(text[]) to service_role;

-- Kein Clientzugriff auf den privaten Bucket; fehlende Storage-Dienste bleiben sichtbar.
do $$ begin
 if to_regclass('storage.buckets') is not null then
   insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
     values('private-data-exports','private-data-exports',false,16777216,array['application/zip']);
 else raise warning 'storage unavailable: private data export bucket unverified'; end if;
end $$;

-- Vault-Namen sind Integrationsvertrag; die Migration erfindet keine Geheimnisse.
create function private.schedule_data_export_cleanup_v1() returns bigint
language plpgsql security definer set search_path = '' as $$
declare v_url text; v_secret text; v_request bigint;
begin
 if to_regclass('vault.decrypted_secrets') is null or not exists(select 1 from pg_extension where extname='pg_net') then
   raise exception using message='export_scheduler_unavailable';
 end if;
 execute 'select decrypted_secret from vault.decrypted_secrets where name=''data_exports_project_url''' into v_url;
 execute 'select decrypted_secret from vault.decrypted_secrets where name=''data_exports_cleanup_secret''' into v_secret;
 if v_url is null or v_secret is null or length(v_secret)<32 then raise exception using message='export_scheduler_unavailable'; end if;
 execute 'select net.http_post(url:=$1,headers:=$2,body:=$3,timeout_milliseconds:=20000)'
   into v_request using v_url||'/functions/v1/cleanup-data-exports',
     jsonb_build_object('Content-Type','application/json','x-export-cleanup-secret',v_secret),'{}'::jsonb;
 return v_request;
end;
$$;
revoke all on function private.schedule_data_export_cleanup_v1() from public,anon,authenticated,service_role;
do $$ begin
 if exists(select 1 from pg_available_extensions where name='pg_net') then create extension if not exists pg_net; end if;
 if exists(select 1 from pg_available_extensions where name='pg_cron') then
   create extension if not exists pg_cron;
   perform cron.schedule('advantly-data-export-cleanup-v1','*/5 * * * *','select private.schedule_data_export_cleanup_v1();');
 else raise warning 'pg_cron unavailable: export cleanup scheduler unverified'; end if;
end $$;
