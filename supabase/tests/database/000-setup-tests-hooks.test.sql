-- Test-Hilfen für alle pgTAP-Dateien (läuft als erste Datei, alphabetische Reihenfolge von pg_prove).
-- Bewusst AUSSERHALB einer Transaktion: Schema `tests` bleibt für die folgenden Dateien bestehen.
-- Nur lokal/CI – nie in Migrationen (Produktion bekommt keine Test-Helfer).

create extension if not exists pgtap with schema extensions;

create schema if not exists tests;
grant usage on schema tests to anon, authenticated, service_role;

create table if not exists tests.users (
  identifier text primary key,
  id         uuid not null
);
grant select on tests.users to anon, authenticated, service_role;

-- Nur Testsetup: explizit erzeugte Sitzung bleibt auch nach einem Widerruf dieselbe Fixture-ID.
create table if not exists tests.account_sessions(
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  session_id uuid not null
);
grant select on tests.account_sessions to anon, authenticated, service_role;
create or replace function tests.create_auth_session(p_profile uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_session uuid := gen_random_uuid();
begin
  insert into auth.sessions(id,user_id,created_at,updated_at,not_after)
    values(v_session,p_profile,now(),now(),now()+interval '1 day');
  insert into tests.account_sessions values(p_profile,v_session)
    on conflict(profile_id) do update set session_id=excluded.session_id;
  return v_session;
end;
$$;
revoke all on function tests.create_auth_session(uuid) from public, anon, authenticated;

create or replace function tests.authenticate_session(p_profile uuid, p_session uuid) returns void
language plpgsql set search_path = '' as $$
begin
  perform set_config('role','authenticated',true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',p_profile,'role','authenticated',
    'session_id',p_session,'exp',extract(epoch from now()+interval '1 hour')::bigint)::text,true);
end;
$$;

-- Auth-Nutzer anlegen (Trigger erzeugt Profil + Einstellungen); optional Onboarding als erledigt markieren.
create or replace function tests.create_user(p_identifier text, p_onboard boolean default true,
                                             p_country text default 'AT')
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid := gen_random_uuid();
begin
  insert into auth.users (id, email, raw_user_meta_data, raw_app_meta_data, aud, role, created_at, updated_at)
  values (v_id, p_identifier || '@test.advantly.eu', jsonb_build_object('test_identifier', p_identifier),
          '{}'::jsonb, 'authenticated', 'authenticated', now(), now());
  perform tests.create_auth_session(v_id);
  insert into tests.users (identifier, id) values (p_identifier, v_id)
  on conflict (identifier) do update set id = excluded.id;
  if p_onboard then
    update public.profiles set status = 'active', alias = initcap(p_identifier), username = p_identifier
     where id = v_id;
    update public.profile_settings set country = p_country, age_confirmed_at = now(), age_rule_min_age = 14
     where profile_id = v_id;
  end if;
  return v_id;
end;
$$;

create or replace function tests.uid(p_identifier text)
returns uuid
language sql
stable
set search_path = ''
as $$
  select u.id from tests.users u where u.identifier = p_identifier;
$$;

-- Rolle authenticated mit JWT-Claims des Testnutzers (wie supabase_test_helpers/basejump).
create or replace function tests.authenticate_as(p_identifier text)
returns void
language plpgsql
set search_path = ''
as $$
declare
  v_id uuid := tests.uid(p_identifier);
begin
  if v_id is null then
    raise exception 'Testnutzer % fehlt', p_identifier;
  end if;
  perform tests.authenticate_session(v_id,(select session_id from tests.account_sessions where profile_id=v_id));
end;
$$;

-- „Veraltetes JWT“ eines gelöschten Accounts: Claims mit einer ID ohne Nutzer.
create or replace function tests.authenticate_as_id(p_id uuid)
returns void
language plpgsql
set search_path = ''
as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', json_build_object('sub', p_id, 'role', 'authenticated')::text, true);
end;
$$;

create or replace function tests.authenticate_anon()
returns void
language plpgsql
set search_path = ''
as $$
begin
  perform set_config('role', 'anon', true);
  perform set_config('request.jwt.claims', json_build_object('role', 'anon')::text, true);
end;
$$;

-- Feste Geräte-IDs (UUIDv7-Form) je Testnutzer: <kurz>1 = Handy, <kurz>2 = Uhr.
create or replace function tests.device(p_short text)
returns uuid
language sql
immutable
set search_path = ''
as $$
  select ('d0000000-0000-7000-8000-0000000000' || p_short)::uuid;
$$;

-- Standardwelt: alice (AT) und bob (DE) befreundet, alice–vera befreundet, bob–carol befreundet,
-- dave ist Fremder. Jede Person hat ein registriertes Handy, alice zusätzlich eine Uhr.
create or replace function tests.world()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_alice uuid := tests.create_user('alice');
  v_bob   uuid := tests.create_user('bob', true, 'DE');
  v_carol uuid := tests.create_user('carol');
  v_dave  uuid := tests.create_user('dave');
  v_vera  uuid := tests.create_user('vera');
begin
  insert into public.friendships (requester_id, addressee_id, status, responded_at) values
    (v_alice, v_bob, 'accepted', now()),
    (v_alice, v_vera, 'accepted', now()),
    (v_bob, v_carol, 'accepted', now());
  insert into public.devices (id, profile_id, kind, platform) values
    (tests.device('a1'), v_alice, 'phone', 'android'),
    (tests.device('a2'), v_alice, 'watch', 'wearos'),
    (tests.device('b1'), v_bob, 'phone', 'ios'),
    (tests.device('c1'), v_carol, 'phone', 'android'),
    (tests.device('d1'), v_dave, 'phone', 'android'),
    (tests.device('e1'), v_vera, 'phone', 'ios');
end;
$$;

-- JSON-Bausteine für sync_push (Wire-Format camelCase wie shared/cloud-sync).
create or replace function tests.part(p_slot text, p_team text, p_pos integer, p_name text,
                                      p_profile uuid default null, p_player uuid default null)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select jsonb_strip_nulls(jsonb_build_object('slotId', p_slot, 'team', p_team, 'position', p_pos,
                                              'displayName', p_name, 'profileId', p_profile, 'playerId', p_player));
$$;

create or replace function tests.match(p_id uuid, p_participants jsonb, p_extra jsonb default '{}'::jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select jsonb_build_object('id', p_id, 'sport', 'tennis', 'discipline', 'singles',
                            'format', jsonb_build_object('sport', 'TENNIS', 'setsToWin', 2),
                            'meta', '{}'::jsonb, 'startedAt', '2026-10-16T10:00:00Z',
                            'participants', p_participants) || p_extra;
$$;

create or replace function tests.event(p_id uuid, p_match uuid, p_device uuid, p_seq bigint, p_type text,
                                       p_payload jsonb default '{}'::jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select jsonb_build_object('id', p_id, 'matchId', p_match, 'deviceId', p_device, 'deviceSeq', p_seq,
                            'lamport', p_seq, 'wallClockMs', 1760608800000 + p_seq * 1000, 'parentId', null,
                            'type', p_type, 'payload', jsonb_build_object('type', p_type) || p_payload);
$$;

-- Ergebnis eines Events/Matches aus der sync_push-Antwort.
create or replace function tests.result(p_res jsonb, p_section text, p_idx integer)
returns text
language sql
immutable
set search_path = ''
as $$
  select coalesce(p_res -> p_section -> p_idx ->> 'result', '?')
         || coalesce(':' || (p_res -> p_section -> p_idx ->> 'reason'), '');
$$;

grant execute on all functions in schema tests to anon, authenticated, service_role;

begin;
select plan(2);
select has_function('tests', 'authenticate_as', array['text'], 'Test-Helfer installiert');
select has_function('public', 'sync_push', array['jsonb'], 'RPC sync_push vorhanden');
select * from finish();
rollback;
