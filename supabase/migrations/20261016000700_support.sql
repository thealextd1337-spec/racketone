-- Privater Meldeweg. Nur schmale RPCs sind öffentlich; Betreiberrechte nie aus Profil-Metadaten.
create table ops.support_members (
  id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('support','admin')),
  revoked_at timestamptz
);
create table ops.support_tickets (
  id uuid primary key,
  owner_id uuid references auth.users(id) on delete cascade,
  receipt_hash bytea not null,
  payload_hash bytea not null,
  category text not null check (category in ('feedback','feature','bug')),
  title text not null check (length(title) between 1 and 120),
  body text not null check (length(body) between 1 and 8000),
  contact_email text check (length(contact_email) <= 254),
  diagnostics jsonb not null default '{}',
  status text not null default 'new' check (status in ('new','reviewing','planned','resolved','closed')),
  priority text not null default 'normal' check (priority in ('low','normal','high')),
  assignee uuid references ops.support_members(id) on delete set null,
  related_id uuid references ops.support_tickets(id) on delete set null,
  resolution_version text check (length(resolution_version) <= 80),
  version bigint not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index support_tickets_owner on ops.support_tickets(owner_id, created_at desc);
create index support_tickets_inbox on ops.support_tickets(created_at desc);
create index support_tickets_cleanup on ops.support_tickets(updated_at) where status in ('resolved','closed');
create table ops.support_messages (
  id uuid primary key,
  ticket_id uuid not null references ops.support_tickets(id) on delete cascade,
  author_id uuid references auth.users(id) on delete set null,
  body text not null check (length(body) between 1 and 4000),
  internal boolean not null,
  created_at timestamptz not null default now()
);
create index support_messages_ticket on ops.support_messages(ticket_id, created_at);
create table ops.support_audit (
  id bigint generated always as identity primary key,
  actor_id uuid references auth.users(id) on delete set null,
  ticket_id uuid references ops.support_tickets(id) on delete set null,
  action text not null,
  created_at timestamptz not null default now()
);
create table ops.support_budgets (
  key_hash bytea primary key,
  started_at timestamptz not null,
  used integer not null
);
create index support_audit_cleanup on ops.support_audit(created_at);
alter table ops.support_members enable row level security;
alter table ops.support_tickets enable row level security;
alter table ops.support_messages enable row level security;
alter table ops.support_audit enable row level security;
alter table ops.support_budgets enable row level security;
revoke all on ops.support_members, ops.support_tickets, ops.support_messages,
  ops.support_audit, ops.support_budgets from public, anon, authenticated;
grant all on ops.support_members, ops.support_tickets, ops.support_messages,
  ops.support_audit, ops.support_budgets to service_role;
grant usage on sequence ops.support_audit_id_seq to service_role;

create function private.require_support() returns uuid
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null or not exists(select 1 from ops.support_members where id = auth.uid() and revoked_at is null) then
    perform private.fail('support_forbidden');
  end if;
  if coalesce(auth.jwt()->>'aal', '') <> 'aal2' then perform private.fail('support_mfa_required'); end if;
  return auth.uid();
end;
$$;

-- Erfolgreiche und abgewiesene Budgetbuchungen bleiben erhalten: RPC liefert Fehler als JSON statt Rollback.
create function private.support_budget(p_key text, p_limit int) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_used int;
begin
  insert into ops.support_budgets as b values (sha256(convert_to(p_key,'UTF8')), clock_timestamp(), 1)
  on conflict(key_hash) do update set
    used = case when b.started_at < clock_timestamp() - interval '1 hour' then 1 else least(b.used + 1, 100000) end,
    started_at = case when b.started_at < clock_timestamp() - interval '1 hour' then clock_timestamp() else b.started_at end
  returning used into v_used;
  return v_used <= p_limit;
end;
$$;

create function public.submit_support(p_id uuid, p_receipt_secret text, p_category text,
  p_title text, p_body text, p_contact_email text default null, p_diagnostics jsonb default '{}')
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_existing ops.support_tickets%rowtype;
  v_payload bytea;
  v_ip text;
begin
  if p_id is null or p_receipt_secret is null or p_receipt_secret !~ '^[0-9a-f]{64}$'
    or p_category is null or p_category not in ('feedback','feature','bug')
    or p_title is null or length(btrim(p_title)) not between 1 and 120
    or p_body is null or length(btrim(p_body)) not between 1 and 8000
    or length(coalesce(p_contact_email,'')) > 254
    or p_diagnostics is null or jsonb_typeof(p_diagnostics) <> 'object'
    or octet_length(p_diagnostics::text) > 2000 then
    return jsonb_build_object('error','support_invalid');
  end if;
  -- Keine beliebigen Logs/Match-/Health-Objekte im Diagnosefeld zulassen.
  if exists(select 1 from jsonb_each(p_diagnostics) e where
    e.key not in ('app_version','commit','os','device','display','font_scale','error_code')
    or jsonb_typeof(e.value) <> 'string' or length(e.value #>> '{}') > 200) then
    return jsonb_build_object('error','support_invalid');
  end if;
  v_payload := sha256(convert_to(jsonb_build_array(p_category,btrim(p_title),btrim(p_body),
    nullif(btrim(p_contact_email),''),p_diagnostics)::text,'UTF8'));
  -- Gleichzeitige Wiederholungen derselben UUID werden vor Lesen/Anlegen serialisiert.
  perform private.lock_sync();
  perform pg_advisory_xact_lock(hashtextextended('support:' || p_id::text,0));
  select * into v_existing from ops.support_tickets where id = p_id;
  if found then
    if v_existing.receipt_hash <> sha256(convert_to(p_receipt_secret,'UTF8'))
      or v_existing.owner_id is distinct from auth.uid() then
      return jsonb_build_object('error','support_identity_changed');
    end if;
    if v_existing.payload_hash <> v_payload then return jsonb_build_object('error','support_conflict'); end if;
    return jsonb_build_object('id',p_id,'receipt','ADV-' || p_id::text);
  end if;
  if auth.uid() is null then
    v_ip := private.age_client_ip();
    -- Hosted-Gateway muss die IP authentisch liefern; niemals clientgewählten XFF blind vertrauen.
    if v_ip is null then return jsonb_build_object('error','support_unavailable'); end if;
  end if;
  if not private.support_budget(case when auth.uid() is null then 'ip:' || v_ip else 'user:' || auth.uid()::text end,10) then
    return jsonb_build_object('error','support_rate_limited');
  end if;
  if not private.support_budget('global',500) then return jsonb_build_object('error','support_rate_limited'); end if;
  insert into ops.support_tickets(id,owner_id,receipt_hash,payload_hash,category,title,body,contact_email,diagnostics)
  values(p_id,auth.uid(),sha256(convert_to(p_receipt_secret,'UTF8')),v_payload,p_category,btrim(p_title),btrim(p_body),
    nullif(btrim(p_contact_email),''),p_diagnostics);
  return jsonb_build_object('id',p_id,'receipt','ADV-' || p_id::text);
end;
$$;

-- Private Darstellungsfunktion: Geheimnis-/Payloadhash gelangen niemals in API-Antworten.
create function private.support_ticket_json(p_id uuid, p_internal boolean) returns jsonb
language sql stable security definer set search_path = '' as $$
  select (to_jsonb(t) - 'receipt_hash' - 'payload_hash') || jsonb_build_object('messages',coalesce(
    (select jsonb_agg(jsonb_build_object('id',m.id,'body',m.body,'internal',m.internal,
      'author_id',case when p_internal then m.author_id else null end,'created_at',m.created_at)
      order by m.created_at,m.id) from ops.support_messages m
      where m.ticket_id=t.id and (p_internal or not m.internal)), '[]'::jsonb))
    from ops.support_tickets t where t.id=p_id;
$$;

create function public.my_support_tickets() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then perform private.fail('support_forbidden'); end if;
  return coalesce((select jsonb_agg(x.item order by x.created_at desc) from (
    select (private.support_ticket_json(t.id,false) - 'assignee' - 'related_id' - 'owner_id') as item,t.created_at
    from ops.support_tickets t where t.owner_id=auth.uid() order by t.created_at desc limit 100) x),'[]'::jsonb);
end;
$$;

create function public.admin_support_members() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  perform private.require_support();
  return coalesce((select jsonb_agg(jsonb_build_object('id',id,'role',role) order by id)
    from ops.support_members where revoked_at is null),'[]'::jsonb);
end;
$$;
create function public.admin_support_list(p_status text default null,p_search text default null) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  perform private.require_support();
  if length(coalesce(p_search,'')) > 120 then perform private.fail('support_invalid'); end if;
  return coalesce((select jsonb_agg(to_jsonb(x) order by x.created_at desc) from (
    select id,category,title,status,priority,version,created_at,updated_at from ops.support_tickets
    where (p_status is null or status=p_status) and (nullif(p_search,'') is null
      or position(lower(p_search) in lower(title)) > 0 or position(lower(p_search) in id::text) > 0)
    order by created_at desc,id limit 100) x),'[]'::jsonb);
end;
$$;
create function public.admin_support_ticket(p_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_result jsonb;
begin
  perform private.require_support();
  v_result := private.support_ticket_json(p_id,true);
  if v_result is null then perform private.fail('support_not_found'); end if;
  return v_result;
end;
$$;

create function public.admin_support_update(p_id uuid,p_status text,p_priority text,p_assignee uuid,
  p_related_id uuid,p_resolution_version text,p_expected_version bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_actor uuid := private.require_support(); v_ticket ops.support_tickets%rowtype;
begin
  perform private.lock_sync();
  select * into v_ticket from ops.support_tickets where id=p_id for update;
  if not found then perform private.fail('support_not_found'); end if;
  if p_expected_version is distinct from v_ticket.version then perform private.fail('support_conflict'); end if;
  if p_status is null or p_status not in ('new','reviewing','planned','resolved','closed')
    or p_priority is null or p_priority not in ('low','normal','high')
    or length(coalesce(p_resolution_version,'')) > 80
    or (p_status='resolved' and v_ticket.category='bug' and nullif(btrim(p_resolution_version),'') is null)
    or (p_assignee is not null and not exists(select 1 from ops.support_members where id=p_assignee and revoked_at is null))
    or (p_related_id is not null and (p_related_id=p_id or not exists(select 1 from ops.support_tickets where id=p_related_id))) then
    perform private.fail('support_invalid');
  end if;
  update ops.support_tickets set status=p_status,priority=p_priority,assignee=p_assignee,related_id=p_related_id,
    resolution_version=nullif(btrim(p_resolution_version),''),version=version+1,updated_at=clock_timestamp() where id=p_id;
  insert into ops.support_audit(actor_id,ticket_id,action) values(v_actor,p_id,'update:' || v_ticket.status || '->' || p_status);
  return private.support_ticket_json(p_id,true);
end;
$$;

create function public.admin_support_message(p_id uuid,p_message_id uuid,p_body text,p_internal boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_actor uuid := private.require_support(); v_existing ops.support_messages%rowtype;
  v_owner_id uuid;
begin
  perform private.lock_sync();
  if p_message_id is null or p_body is null or length(btrim(p_body)) not between 1 and 4000 or p_internal is null then
    perform private.fail('support_invalid');
  end if;
  select owner_id into v_owner_id from ops.support_tickets where id=p_id for update;
  if not found then perform private.fail('support_not_found'); end if;
  if not p_internal and v_owner_id is null then perform private.fail('support_guest_reply_unavailable'); end if;
  select * into v_existing from ops.support_messages where id=p_message_id;
  if found then
    if v_existing.ticket_id<>p_id or v_existing.author_id is distinct from v_actor
      or v_existing.body<>btrim(p_body) or v_existing.internal<>p_internal then perform private.fail('support_conflict'); end if;
  else
    insert into ops.support_messages(id,ticket_id,author_id,body,internal) values(p_message_id,p_id,v_actor,btrim(p_body),p_internal);
    update ops.support_tickets set version=version+1,updated_at=clock_timestamp() where id=p_id;
    insert into ops.support_audit(actor_id,ticket_id,action) values(v_actor,p_id,case when p_internal then 'internal_note' else 'user_reply' end);
  end if;
  return private.support_ticket_json(p_id,true);
end;
$$;

revoke all on function private.require_support(), private.support_budget(text,int),
  private.support_ticket_json(uuid,boolean) from public,anon,authenticated;
revoke all on function public.submit_support(uuid,text,text,text,text,text,jsonb), public.my_support_tickets(),
  public.admin_support_members(), public.admin_support_list(text,text),public.admin_support_ticket(uuid),
  public.admin_support_update(uuid,text,text,uuid,uuid,text,bigint),public.admin_support_message(uuid,uuid,text,boolean)
  from public,anon,authenticated;
grant execute on function public.submit_support(uuid,text,text,text,text,text,jsonb) to anon,authenticated;
grant execute on function public.my_support_tickets(),public.admin_support_members(),public.admin_support_list(text,text),
  public.admin_support_ticket(uuid),public.admin_support_update(uuid,text,text,uuid,uuid,text,bigint),
  public.admin_support_message(uuid,uuid,text,boolean) to authenticated;

-- Geschlossene Meldungen 180 Tage, Audit ein Jahr; offene Vorgänge bleiben bis zum Abschluss erhalten.
create function private.purge_support() returns void
language plpgsql security definer set search_path = '' set lock_timeout = '5s' set statement_timeout = '60s' as $$
begin
  perform private.lock_sync();
  delete from ops.support_tickets where id in (select id from ops.support_tickets
    where status in ('resolved','closed') and updated_at < now() - interval '180 days' order by updated_at limit 500);
  delete from ops.support_budgets where started_at < now() - interval '2 hours';
  delete from ops.support_audit where created_at < now() - interval '1 year';
end;
$$;
revoke all on function private.purge_support() from public,anon,authenticated;
grant execute on function private.purge_support() to service_role;
do $$ begin
  if exists(select 1 from pg_available_extensions where name='pg_cron') then
    perform cron.schedule('advantly-support-cleanup','35 * * * *','select private.purge_support();');
  end if;
end $$;

-- Begrenzte RPC-Laufzeiten wie beim bestehenden Backend; keine Sperrwarteschlange durch Gastverkehr.
alter function public.submit_support(uuid,text,text,text,text,text,jsonb) set lock_timeout = '500ms';
alter function public.submit_support(uuid,text,text,text,text,text,jsonb) set statement_timeout = '3s';
alter function public.admin_support_update(uuid,text,text,uuid,uuid,text,bigint) set lock_timeout = '2s';
alter function public.admin_support_update(uuid,text,text,uuid,uuid,text,bigint) set statement_timeout = '5s';
alter function public.admin_support_message(uuid,uuid,text,boolean) set lock_timeout = '2s';
alter function public.admin_support_message(uuid,uuid,text,boolean) set statement_timeout = '5s';

-- Bestehenden vollständigen Kontoexport um eigene Meldungen und sichtbare Antworten ergänzen.
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
    'support', coalesce((select jsonb_agg(private.support_ticket_json(t.id,false) - 'assignee' - 'related_id' - 'owner_id' order by t.created_at, t.id) from ops.support_tickets t where t.owner_id=v_uid),'[]'::jsonb),
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
                   'format', m.format, 'meta', m.meta, 'status', m.status, 'resultReason', m.result_reason,
                   'winner', m.winner, 'startedAt', m.started_at, 'endedAt', m.ended_at, 'summary', m.summary,
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
