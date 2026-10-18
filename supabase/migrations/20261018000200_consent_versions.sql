-- K1: Fachliche Versionen kommen vom Betreiber nach Textprüfung, niemals vom Client.
-- Die Platzhalter sind ausdrücklich NICHT freigegeben. Es gibt bisher keinen Nutzungsbedingungen-Link.
create table private.consent_versions (
  document_type text not null check (document_type in ('TERMS', 'PRIVACY')),
  version text not null check (length(version) between 1 and 100),
  approved_at timestamptz,
  is_current boolean not null default false,
  primary key (document_type, version)
);
create unique index consent_versions_current on private.consent_versions(document_type) where is_current;
create table private.consent_translations (
  document_type text not null,
  version text not null,
  language text not null check (language in ('de', 'en')),
  url text check (url ~ '^https://advantly[.]eu/[^[:space:]]+$'),
  primary key (document_type, version, language),
  foreign key (document_type, version) references private.consent_versions
);
create table private.consent_history (
  id bigint generated always as identity primary key,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  document_type text not null,
  version text not null,
  language text not null,
  accepted boolean not null,
  decided_at timestamptz not null default clock_timestamp(),
  foreign key (document_type, version, language) references private.consent_translations
);
create index consent_history_profile on private.consent_history(profile_id, document_type, version, id desc);
alter table private.consent_versions enable row level security;
alter table private.consent_translations enable row level security;
alter table private.consent_history enable row level security;
revoke all on private.consent_versions, private.consent_translations, private.consent_history from public, anon, authenticated;
revoke all on sequence private.consent_history_id_seq from public, anon, authenticated;

-- Auch ein Konto vor der Zustimmung kann Löschung beantragen, ohne durch Recovery das Onboarding zu überspringen.
create table private.consent_deletion_origins (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  previous_status text not null check (previous_status in ('onboarding', 'active'))
);
alter table private.consent_deletion_origins enable row level security;
revoke all on private.consent_deletion_origins from public, anon, authenticated;
-- Bisher konnten ausschließlich bereits fertig eingerichtete Profile einen Antrag stellen.
insert into private.consent_deletion_origins(profile_id, previous_status)
  select id, 'active' from public.profiles where status = 'deletion_requested';
create trigger zz_lock_sync before insert or update or delete on private.consent_deletion_origins
  for each statement execute function private.lock_sync_statement();
alter table public.profiles drop constraint active_needs_alias;
alter table public.profiles add constraint active_needs_alias check (status <> 'active' or alias is not null);

insert into private.consent_versions(document_type, version, is_current) values
  ('TERMS', 'unreleased', true), ('PRIVACY', 'unreleased', true);
insert into private.consent_translations(document_type, version, language, url) values
  ('TERMS', 'unreleased', 'de', null), ('TERMS', 'unreleased', 'en', null),
  ('PRIVACY', 'unreleased', 'de', 'https://advantly.eu/app-datenschutz.html'),
  ('PRIVACY', 'unreleased', 'en', 'https://advantly.eu/en/app-privacy.html');

-- Veröffentlichung und Entscheidung benutzen denselben Lock; bestätigte Fassungen bleiben unveränderlich.
create function private.guard_consent_catalog() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform private.lock_sync();
  if tg_table_name = 'consent_versions' and tg_op <> 'DELETE' then
    if new.approved_at is not null then
      if (select count(*) from private.consent_translations t where t.document_type = new.document_type
          and t.version = new.version and t.url is not null) <> 2 then
        perform private.fail('consent_unavailable');
      end if;
    end if;
  end if;
  if tg_op <> 'INSERT' then
    if tg_table_name = 'consent_versions' then
      if old.approved_at is not null and (tg_op = 'DELETE' or
         (to_jsonb(new) - 'is_current') is distinct from (to_jsonb(old) - 'is_current')) then
        perform private.fail('consent_version_immutable');
      end if;
    elsif exists (select 1 from private.consent_versions v where v.document_type = old.document_type
                  and v.version = old.version and v.approved_at is not null) then
      perform private.fail('consent_version_immutable');
    end if;
  elsif tg_table_name = 'consent_translations' and exists (
    select 1 from private.consent_versions v where v.document_type = new.document_type
      and v.version = new.version and v.approved_at is not null) then
    perform private.fail('consent_version_immutable');
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;
create trigger consent_versions_guard before insert or update or delete on private.consent_versions
  for each row execute function private.guard_consent_catalog();
create trigger consent_translations_guard before insert or update or delete on private.consent_translations
  for each row execute function private.guard_consent_catalog();
create trigger zz_lock_sync before insert or update or delete on private.consent_versions
  for each statement execute function private.lock_sync_statement();
create trigger zz_lock_sync before insert or update or delete on private.consent_translations
  for each statement execute function private.lock_sync_statement();
create trigger zz_lock_sync before insert or update or delete on private.consent_history
  for each statement execute function private.lock_sync_statement();

create function private.has_current_consents(p_profile uuid default auth.uid()) returns boolean
language sql stable security definer set search_path = '' as $$
  select count(*) = 2 and bool_and(v.approved_at is not null and coalesce(h.accepted, false))
  from private.consent_versions v
  left join lateral (select accepted from private.consent_history h where h.profile_id = p_profile
    and h.document_type = v.document_type and h.version = v.version order by h.id desc limit 1) h on true
  where v.is_current;
$$;

create function private.require_current_consents() returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  perform private.lock_sync();
  if v_uid is null or not private.has_current_consents(v_uid) then
    perform private.fail('consent_required', '42501');
  end if;
  return v_uid;
end;
$$;

create function private.consent_state(p_profile uuid, p_language text) returns jsonb
language sql stable security definer set search_path = '' as $$
  with documents as (
    select v.document_type, v.version, t.url,
      v.approved_at is not null and t.url is not null as approved, h.accepted, h.decided_at
    from private.consent_versions v
    left join private.consent_translations t on t.document_type = v.document_type and t.version = v.version
      and t.language = p_language
    left join lateral (select accepted, decided_at from private.consent_history h where h.profile_id = p_profile
      and h.document_type = v.document_type and h.version = v.version order by h.id desc limit 1) h on true
    where v.is_current
  ) select jsonb_build_object('profileId', p_profile,
    'status', case when bool_or(accepted = false) then 'DECLINED'
      when count(*) <> 2 or not coalesce(bool_and(approved), false) then 'UNAVAILABLE'
      when bool_and(coalesce(accepted, false)) then 'CURRENT'
      else 'REQUIRED' end,
    'documents', coalesce(jsonb_agg(jsonb_build_object('type', document_type, 'version', version,
      'language', p_language, 'url', url, 'approved', approved, 'accepted', coalesce(accepted, false),
      'decidedAt', decided_at) order by document_type), '[]'::jsonb)) from documents;
$$;

create function public.my_consent_v1(p_language text) returns jsonb
language plpgsql security definer set search_path = '' set lock_timeout = '2s' set statement_timeout = '5s' as $$
declare v_uid uuid := auth.uid();
begin
  -- Wie my_auth_profile: Katalogwechsel und Kontostand gemeinsam serialisieren.
  perform private.lock_sync();
  if v_uid is null or not exists (select 1 from public.profiles where id = v_uid) then perform private.fail('not_authenticated', '42501'); end if;
  if p_language is null or p_language not in ('de', 'en') then perform private.fail('consent_language_invalid'); end if;
  return private.consent_state(v_uid, p_language);
end;
$$;

create function public.record_consent_v1(p_documents jsonb, p_accept boolean) returns jsonb
language plpgsql security definer set search_path = '' set lock_timeout = '2s' set statement_timeout = '5s' as $$
declare v_uid uuid := auth.uid(); v_doc jsonb; v_language text; v_previous boolean;
begin
  perform private.lock_sync();
  if v_uid is null or not exists (select 1 from public.profiles where id = v_uid) then perform private.fail('not_authenticated', '42501'); end if;
  if p_accept is null or jsonb_typeof(p_documents) is distinct from 'array' then perform private.fail('consent_documents_invalid'); end if;
  if jsonb_array_length(p_documents) <> 2 then perform private.fail('consent_documents_invalid'); end if;
  if (select count(distinct d->>'type') from jsonb_array_elements(p_documents) d) <> 2 then
    perform private.fail('consent_documents_invalid');
  end if;
  v_language := p_documents->0->>'language';
  if v_language is null or v_language not in ('de', 'en') then perform private.fail('consent_language_invalid'); end if;
  for v_doc in select value from jsonb_array_elements(p_documents) loop
    if (v_doc->>'language') is distinct from v_language or not exists (
      select 1 from private.consent_versions v join private.consent_translations t using(document_type, version)
      where v.is_current and v.document_type = v_doc->>'type' and v.version = v_doc->>'version'
        and t.language = v_language) then perform private.fail('consent_version_changed'); end if;
  end loop;
  if p_accept and (select count(*) <> 2 or not bool_and(v.approved_at is not null and t.url is not null)
      from private.consent_versions v left join private.consent_translations t
        on t.document_type = v.document_type and t.version = v.version and t.language = v_language
      where v.is_current) then
    perform private.fail('consent_unavailable');
  end if;
  for v_doc in select value from jsonb_array_elements(p_documents) loop
    select accepted into v_previous from private.consent_history h where h.profile_id = v_uid
      and h.document_type = v_doc->>'type' and h.version = v_doc->>'version' order by h.id desc limit 1;
    if v_previous is distinct from p_accept then
      insert into private.consent_history(profile_id, document_type, version, language, accepted)
      values(v_uid, v_doc->>'type', v_doc->>'version', v_language, p_accept);
    end if;
  end loop;
  return private.consent_state(v_uid, v_language);
end;
$$;

-- Nur Onboarding wird erweitert. Export/Support/Löschung/Recovery bleiben von is_active unabhängig.
create or replace function public.my_auth_profile() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_profile public.profiles%rowtype;
begin
  perform private.lock_sync();
  if v_uid is null then perform private.fail('not_authenticated', '42501'); end if;
  select * into v_profile from public.profiles where id = v_uid;
  if not found then perform private.fail('profile_missing'); end if;
  return jsonb_build_object('profileId', v_uid, 'onboarded', v_profile.status = 'active',
    'deletionRequested', v_profile.status = 'deletion_requested',
    'consentStatus', private.consent_state(v_uid, 'en')->>'status');
end;
$$;

create or replace function public.finish_onboarding(p_alias text, p_username text default null)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid(); v_status text;
  v_alias text := btrim(coalesce(p_alias, '')); v_username text := lower(nullif(btrim(p_username), ''));
begin
  perform private.lock_sync();
  if v_uid is null then perform private.fail('not_authenticated', '42501'); end if;
  select status into v_status from public.profiles where id = v_uid for update;
  if not found then perform private.fail('profile_missing'); end if;
  if v_status <> 'onboarding' then perform private.fail('already_onboarded'); end if;
  if not exists (select 1 from public.profile_settings where profile_id = v_uid and age_confirmed_at is not null) then
    perform private.fail('age_ticket_required');
  end if;
  perform private.require_current_consents();
  if length(v_alias) not between 1 and 40 or v_alias ~ '[[:cntrl:]]' then perform private.fail('invalid_alias'); end if;
  if v_username is not null and v_username !~ '^[a-z0-9_.]{3,20}$' then perform private.fail('invalid_username'); end if;
  if v_username in ('admin', 'advantly', 'support', 'moderator', 'system', 'deleted', 'null', 'official') then
    perform private.fail('username_reserved');
  end if;
  begin
    update public.profiles set status = 'active', alias = v_alias, username = v_username where id = v_uid;
  exception when unique_violation then perform private.fail('username_taken'); end;
end;
$$;

revoke all on function private.guard_consent_catalog(), private.has_current_consents(uuid),
  private.require_current_consents(), private.consent_state(uuid,text) from public, anon, authenticated;
revoke all on function public.my_consent_v1(text), public.record_consent_v1(jsonb,boolean) from public, anon, authenticated;
grant execute on function public.my_consent_v1(text), public.record_consent_v1(jsonb,boolean) to authenticated;

-- Bestehende Export-Whitelist einschließlich Support beibehalten.
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
  return jsonb_build_object(
    'support', coalesce((select jsonb_agg(private.support_ticket_json(t.id,false) - 'assignee' - 'related_id' - 'owner_id' order by t.created_at, t.id) from ops.support_tickets t where t.owner_id=v_uid),'[]'::jsonb),
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

-- Auch während des um Zustimmung erweiterten Onboardings erreichbar.
create or replace function public.request_account_deletion()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_status text;
begin
  perform private.lock_sync();
  if v_uid is null or not exists (select 1 from public.profiles where id = v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  select status into v_status from public.profiles where id = v_uid for update;
  if v_status = 'deletion_requested' then return; end if;
  if v_status not in ('onboarding', 'active') then perform private.fail('profile_not_active', '42501'); end if;
  insert into private.consent_deletion_origins(profile_id, previous_status) values(v_uid, v_status);
  update public.profiles set status = 'deletion_requested' where id = v_uid;
  update public.profile_settings set deletion_requested_at = now(), updated_at = now() where profile_id = v_uid;
  update public.devices set push_token = null, push_provider = null where profile_id = v_uid;
  update public.match_visitors set revoked_at = now()
   where revoked_at is null and (profile_id = v_uid or invited_by = v_uid);
  update public.match_roles set status = 'declined', responded_at = now()
   where profile_id = v_uid and status = 'invited';
  update public.invites set revoked_at = now() where created_by = v_uid and used_at is null and revoked_at is null;
  -- Feeds der Freunde ändern sich ohne neue Match-Version → Vollabgleich auslösen
  perform private.bump_visibility(private.friend_ids(v_uid));
end;
$$;

create or replace function public.cancel_account_deletion()
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_previous text;
begin
  perform private.lock_sync();
  select o.previous_status into v_previous from private.consent_deletion_origins o
    join public.profiles p on p.id = o.profile_id join public.profile_settings s on s.profile_id = p.id
    where p.id = v_uid and p.status = 'deletion_requested' and s.deletion_requested_at > now() - interval '14 days'
    for update of p, o, s;
  if not found then perform private.fail('no_deletion_pending'); end if;
  update public.profiles set status = v_previous where id = v_uid;
  delete from private.consent_deletion_origins where profile_id = v_uid;
  update public.profile_settings set deletion_requested_at = null, updated_at = now() where profile_id = v_uid;
  perform private.bump_visibility(private.friend_ids(v_uid));
end;
$$;
