-- Plan 0.6.5 §4 (0.7.0, 20261109000200): vera löscht ihr Konto. Fremde, mit ihr verknüpfte Gäste (Zuordnung und Claim)
-- werden wieder unverknüpfte Gäste des Anlegers mit ihrem Namen; ihr direkter Platz, ihr eigener unzugeordneter Gast und
-- ihr Self bleiben „Gelöschter Spieler“, ihr einem Dritten zugeordneter Gast geht an ihn über. Job und purge_account
-- enden gleich. admin_redact_guest_name: nur Support-Admin mit AAL2 und Begründung, Audit ohne Gastnamen, Ergebnis wie
-- die Redaktion eines pensionierten Gasts.
begin;
set local timezone = 'UTC';
set local transaction_timeout = '5min';
select plan(49);
select tests.world();
select tests.create_user('sup520');
select tests.create_user('adm520');
select tests.publish_consent_documents('gn-520');
select tests.accept_current_consents(id) from tests.users;
insert into ops.support_members(id, role) values (tests.uid('sup520'), 'support'), (tests.uid('adm520'), 'admin');
-- vera und bob befreundet: vera ordnet bob ihren Gast zu.
insert into public.friendships (requester_id, addressee_id, status, responded_at)
  values (tests.uid('vera'), tests.uid('bob'), 'accepted', now());

create function pg_temp.as_user(p_name text, p_sql text) returns text language plpgsql as $$
declare v text;
begin
  perform tests.authenticate_as(p_name);
  execute p_sql into v;
  reset role;
  return v;
exception when others then
  reset role;
  return 'ERR ' || sqlstate || ' ' || sqlerrm;
end $$;
create function pg_temp.admin(p_name text, p_sql text, p_aal text default 'aal2') returns text language plpgsql as $$
declare v text;
begin
  perform tests.authenticate_as(p_name);
  perform set_config('request.jwt.claims', (current_setting('request.jwt.claims')::jsonb
    || jsonb_build_object('aal', p_aal))::text, true);
  execute p_sql into v;
  reset role;
  return v;
exception when others then
  reset role;
  return 'ERR ' || sqlstate || ' ' || sqlerrm;
end $$;
create function pg_temp.slot(p_match text, p_slot text) returns text language sql as $$
  select concat_ws('|', display_name, deleted_profile, coalesce(profile_id::text, 'null'), coalesce(link_status, 'null'))
  from public.match_participants
  where match_id = ('52000000-0000-4000-8000-00000000000' || p_match)::uuid and slot_id = p_slot $$;
create function pg_temp.g(p text) returns text language sql immutable as $$
  select '52100000-0000-4000-8000-0000000000' || p $$;

-- alice: Self, Test1 (vera zugeordnet), Test2 (von vera beansprucht), Test3 (bob zugeordnet).
-- M1: Test1, M2: Test2 und Test3, M3: vera direkt. PlayerAssigned-Events tragen die Namen.
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
    jsonb_build_object('id', pg_temp.g('a0'), 'kind', 'self', 'displayName', 'Ich'),
    jsonb_build_object('id', pg_temp.g('01'), 'kind', 'guest', 'displayName', 'Test1'),
    jsonb_build_object('id', pg_temp.g('02'), 'kind', 'guest', 'displayName', 'Test2'),
    jsonb_build_object('id', pg_temp.g('03'), 'kind', 'guest', 'displayName', 'Test3')),
  'matches', jsonb_build_array(
    tests.match('52000000-0000-4000-8000-000000000001', jsonb_build_array(
      tests.part(pg_temp.g('a0'), 'A', 1, 'Alice', null, pg_temp.g('a0')::uuid),
      tests.part(pg_temp.g('01'), 'B', 1, 'Test1', null, pg_temp.g('01')::uuid))),
    tests.match('52000000-0000-4000-8000-000000000002', jsonb_build_array(
      tests.part(pg_temp.g('a0'), 'A', 1, 'Alice', null, pg_temp.g('a0')::uuid),
      tests.part(pg_temp.g('03'), 'A', 2, 'Test3', null, pg_temp.g('03')::uuid),
      tests.part(pg_temp.g('02'), 'B', 1, 'Test2', null, pg_temp.g('02')::uuid)), '{"discipline":"doubles"}'),
    tests.match('52000000-0000-4000-8000-000000000003', jsonb_build_array(
      tests.part(pg_temp.g('a0'), 'A', 1, 'Alice', null, pg_temp.g('a0')::uuid),
      tests.part('v-direkt', 'B', 1, 'Vera Klar', tests.uid('vera'))))),
  'events', jsonb_build_array(
    tests.event('52200000-0000-4000-8000-000000000001', '52000000-0000-4000-8000-000000000001', tests.device('a1'), 1, 'PlayerAssigned',
      jsonb_build_object('slot', pg_temp.g('01'), 'player', jsonb_build_object('id', pg_temp.g('01'), 'displayName', 'Test1'))),
    tests.event('52200000-0000-4000-8000-000000000002', '52000000-0000-4000-8000-000000000002', tests.device('a1'), 2, 'PlayerAssigned',
      jsonb_build_object('slot', pg_temp.g('02'), 'player', jsonb_build_object('id', pg_temp.g('02'), 'displayName', 'Test2'))),
    tests.event('52200000-0000-4000-8000-000000000003', '52000000-0000-4000-8000-000000000002', tests.device('a1'), 3, 'PlayerAssigned',
      jsonb_build_object('slot', pg_temp.g('03'), 'player', jsonb_build_object('id', pg_temp.g('03'), 'displayName', 'Test3')))))),
  'matches', 0), 'created', 'alice: drei Matches mit Gästen und vera');
reset role;
select is(pg_temp.as_user('alice', format('select public.assign_player(%L, %L)::text', pg_temp.g('01'), tests.uid('vera'))),
  '1', 'Test1 → vera zugeordnet');
select is(pg_temp.as_user('alice', format('select public.assign_player(%L, %L)::text', pg_temp.g('03'), tests.uid('bob'))),
  '1', 'Test3 → bob zugeordnet');
select set_config('tests.claim', pg_temp.as_user('alice', format('select public.create_invite(%L, %L)', 'claim_player', pg_temp.g('02'))), true);
select is(pg_temp.as_user('vera', format('select public.redeem_invite(%L) ->> %L', current_setting('tests.claim'), 'matchCount')),
  '1', 'Test2 → von vera beansprucht');

-- vera: Self, eigener freier Gast, eigener Gast bob zugeordnet; M4 mit bob über die Zuordnung.
select tests.authenticate_as('vera');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
    jsonb_build_object('id', pg_temp.g('e0'), 'kind', 'self', 'displayName', 'Vera'),
    jsonb_build_object('id', pg_temp.g('e1'), 'kind', 'guest', 'displayName', 'Vgast frei'),
    jsonb_build_object('id', pg_temp.g('e2'), 'kind', 'guest', 'displayName', 'Vgast Bob')),
  'matches', jsonb_build_array(
    tests.match('52000000-0000-4000-8000-000000000004', jsonb_build_array(
      tests.part(pg_temp.g('e0'), 'A', 1, 'Vera', null, pg_temp.g('e0')::uuid),
      tests.part(pg_temp.g('e2'), 'A', 2, 'Vgast Bob', null, pg_temp.g('e2')::uuid),
      tests.part(pg_temp.g('e1'), 'B', 1, 'Vgast frei', null, pg_temp.g('e1')::uuid)), '{"discipline":"doubles"}')))),
  'matches', 0), 'created', 'vera: Match mit eigenen Gästen');
reset role;
select is(pg_temp.as_user('vera', format('select public.assign_player(%L, %L)::text', pg_temp.g('e2'), tests.uid('bob'))),
  '1', 'Vgast Bob → bob zugeordnet');
select is(pg_temp.as_user('vera', 'select public.request_account_deletion()::text'), '', 'vera beantragt die Löschung');

-- Beide Löschwege vom selben Stand (Unterblock, danach zurückgerollt): Job bzw. purge_account.
create function pg_temp.digest() returns text language sql as $$
  select md5(string_agg(r, E'\n' order by r)) from (
    select 'm ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text r from public.matches x
    union all select 'p ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text from public.match_participants x
    union all select 'e ' || (to_jsonb(x) - 'server_seq')::text from public.match_events x
    union all select 'pl ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text from public.players x
    union all select 'i ' || (to_jsonb(x) - 'created_at')::text from public.invites x
    union all select 'n ' || to_jsonb(x)::text from private.match_redacted_names x
    union all select 's ' || to_jsonb(x)::text from private.match_redacted_slots x
    union all select 'rp ' || to_jsonb(x)::text from private.match_redacted_players x
    union all select 'ri ' || to_jsonb(x)::text from private.redacted_player_identities x
    union all select 'pw ' || to_jsonb(x)::text from private.player_write_redactions x
    union all select 'u ' || x.id::text from auth.users x) d $$;
create function pg_temp.state() returns jsonb language sql as $$
  select jsonb_build_object(
    'gone', not exists (select 1 from public.profiles where id = tests.uid('vera')),
    'm1', pg_temp.slot('1', pg_temp.g('01')),
    'm2', pg_temp.slot('2', pg_temp.g('02')),
    'm2b', pg_temp.slot('2', pg_temp.g('03')),
    'm3', pg_temp.slot('3', 'v-direkt'),
    'm4v1', pg_temp.slot('4', pg_temp.g('e1')),
    'm4v2', pg_temp.slot('4', pg_temp.g('e2')),
    'm4owner', (select created_by = tests.uid('bob') from public.matches where id = '52000000-0000-4000-8000-000000000004'),
    'players', (select jsonb_object_agg(id, concat_ws('|', display_name, owner_id = tests.uid('bob'),
        coalesce(profile_id::text, 'null'), coalesce(assignment_source, 'null')))
      from public.players where id::text in (pg_temp.g('01'), pg_temp.g('02'), pg_temp.g('e1'), pg_temp.g('e2'))),
    'events', (select jsonb_object_agg(id, (payload #>> '{player,displayName}') || '|' || (redacted_at is not null))
      from public.match_events where type = 'PlayerAssigned' and match_id::text like '52000000-%'),
    'redacted', (select count(*) from private.player_write_redactions where player_id::text in (pg_temp.g('01'), pg_temp.g('02')))
      + (select count(*) from private.redacted_player_identities where player_id::text in (pg_temp.g('01'), pg_temp.g('02')))
      + (select count(*) from private.match_redacted_players where player_id::text in (pg_temp.g('01'), pg_temp.g('02')))
      + (select count(*) from private.match_redacted_names where identity_id::text in (pg_temp.g('01'), pg_temp.g('02')))
      + (select count(*) from private.match_redacted_slots where slot_id in (pg_temp.g('01'), pg_temp.g('02'))),
    'v1retired', exists (select 1 from private.player_write_redactions where player_id::text = pg_temp.g('e1')),
    'digest', pg_temp.digest()) $$;
create function pg_temp.run(p_mode text) returns jsonb language plpgsql as $f$
declare v jsonb;
begin
  begin
    if p_mode = 'purge' then
      perform private.purge_account(tests.uid('vera'));
    else
      update public.profile_settings set deletion_requested_at = now() - interval '15 days' where profile_id = tests.uid('vera');
      perform set_config('advantly.account_deletion_budget', '30s', true);
      for i in 1..20 loop
        exit when not exists (select 1 from public.profiles where id = tests.uid('vera'));
        perform private.run_account_deletion_job_v1();
      end loop;
    end if;
    v := pg_temp.state();
    raise exception using errcode = 'AB001';
  exception when sqlstate 'AB001' then null;
    when others then v := jsonb_build_object('error', sqlstate || ' ' || sqlerrm);
  end;
  return v;
end $f$;
create temp table before_deletion as select pg_temp.state() s,
  (select jsonb_object_agg(id, redaction_epoch) from public.matches where id::text like '52000000-%') epochs;
create temp table runs as select m, pg_temp.run(m) s from unnest(array['job', 'purge']) m;

select ok((select (s ->> 'gone')::boolean from runs where m = 'job'), 'Job löscht vera vollständig');
select is((select s ->> 'm1' from runs where m = 'job'), 'Test1|f|null|null', 'zugeordneter Gast: Name bleibt, entkoppelt');
select is((select s ->> 'm2' from runs where m = 'job'), 'Test2|f|null|null', 'beanspruchter Gast: Name bleibt, entkoppelt');
select is((select s ->> 'm2b' from runs where m = 'job'), 'Test3|f|' || tests.uid('bob') || '|assigned',
  'bobs Zuordnung im selben Match unberührt');
select is((select s ->> 'm3' from runs where m = 'job'), 'Gelöschter Spieler|t|null|null', 'veras direkter Platz redigiert');
select is((select s ->> 'm4v1' from runs where m = 'job'), 'Gelöschter Spieler|t|null|null', 'veras freier Gast redigiert');
select is((select s ->> 'm4v2' from runs where m = 'job'), 'Vgast Bob|f|' || tests.uid('bob') || '|assigned',
  'veras bob zugeordneter Gast behält Namen und Verknüpfung');
select ok((select (s ->> 'm4owner')::boolean from runs where m = 'job'), 'veras Match gehört jetzt bob');
select is((select s -> 'players' from runs where m = 'job'), jsonb_build_object(
    pg_temp.g('01'), 'Test1|f|null|null', pg_temp.g('02'), 'Test2|f|null|null',
    pg_temp.g('e2'), 'Vgast Bob|t|' || tests.uid('bob') || '|assignment'),
  'players: Gäste von alice frei mit Namen, Vgast Bob gehört bob, Vgast frei gelöscht');
select is((select s -> 'events' from runs where m = 'job'), jsonb_build_object(
    '52200000-0000-4000-8000-000000000001', 'Test1|false', '52200000-0000-4000-8000-000000000002', 'Test2|false',
    '52200000-0000-4000-8000-000000000003', 'Test3|false'), 'PlayerAssigned-Payloads unverändert');
select is((select (s ->> 'redacted')::int from runs where m = 'job'), 0, 'keine Schreibsperre, kein Manifest, keine Redaktion');
select ok((select (s ->> 'v1retired')::boolean from runs where m = 'job'), 'veras freier Gast ist pensioniert');
select is((select s - 'digest' from runs where m = 'purge'), (select s - 'digest' from runs where m = 'job'),
  'purge_account: dieselben Einzelwerte');
select is((select s ->> 'digest' from runs where m = 'purge'), (select s ->> 'digest' from runs where m = 'job'),
  'purge_account und Job: gleicher Endzustand (alle Match-, Player-, Einladungs- und Redaktionszeilen)');
select is((select s from before_deletion), pg_temp.state(), 'Unterblöcke zurückgerollt');

-- Jetzt endgültig (Job); danach Epoche und Sicht von alice.
update public.profile_settings set deletion_requested_at = now() - interval '15 days' where profile_id = tests.uid('vera');
do $$
begin
  perform set_config('advantly.account_deletion_budget', '30s', true);
  for i in 1..20 loop
    exit when not exists (select 1 from public.profiles where id = tests.uid('vera'));
    perform private.run_account_deletion_job_v1();
  end loop;
end $$;
select ok((select bool_and(m.redaction_epoch > (b.epochs ->> m.id::text)::int) from public.matches m, before_deletion b
  where m.id in ('52000000-0000-4000-8000-000000000001', '52000000-0000-4000-8000-000000000002')),
  'Epoche der Matches mit entkoppelten Gästen gestiegen');
select is(pg_temp.as_user('alice', $$select string_agg(p ->> 'displayName' || '/' || coalesce(p ->> 'profileId', 'null'), ',' order by p ->> 'displayName')
  from jsonb_array_elements(public.sync_pull(0) -> 'players') p where p ->> 'kind' = 'guest'$$),
  'Test1/null,Test2/null,Test3/' || tests.uid('bob'), 'Pull von alice: Gäste mit Namen, Test1/Test2 unverknüpft');

-- admin_redact_guest_name: Rechte und Eingaben.
select is(pg_temp.admin('alice', format('select public.admin_redact_guest_name(%L, %L)', pg_temp.g('01'), 'Widerspruch')),
  'ERR P0001 support_forbidden', 'kein Support-Mitglied: abgelehnt');
select is(pg_temp.admin('sup520', format('select public.admin_redact_guest_name(%L, %L)', pg_temp.g('01'), 'Widerspruch')),
  'ERR P0001 support_admin_required', 'Rolle support reicht nicht');
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L)', pg_temp.g('01'), 'Widerspruch'), 'aal1'),
  'ERR P0001 support_mfa_required', 'ohne AAL2 abgelehnt');
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L)', pg_temp.g('01'), '   ')),
  'ERR P0001 support_invalid', 'Begründung Pflicht');
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L)', pg_temp.g('a0'), 'Widerspruch')),
  'ERR P0001 player_not_found', 'Self-Spieler ist kein Gast');
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L)', '00000000-0000-4000-8000-000000000000', 'x')),
  'ERR P0001 player_not_found', 'unbekannter Spieler');
select ok(not has_function_privilege('anon', 'public.admin_redact_guest_name(uuid,text)', 'execute')
  and not has_function_privilege('service_role', 'public.admin_redact_guest_name(uuid,text)', 'execute'),
  'nur authenticated (Prüfung im RPC)');
select is((select count(*)::int from ops.account_audit where action = 'guest_name_redact'), 0, 'kein Audit ohne Wirkung');

-- Test3 ist bob zugeordnet und hat einen offenen Claim-Link nicht; Test1 einen offenen Claim-Link.
select set_config('tests.claim1', pg_temp.as_user('alice', format('select public.create_invite(%L, %L)', 'claim_player', pg_temp.g('01'))), true);
create temp table before_admin as select jsonb_object_agg(id, redaction_epoch) epochs from public.matches where id::text like '52000000-%';
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L)::text', pg_temp.g('03'), 'Art. 21 Ticket 4711')),
  jsonb_build_object('playerId', pg_temp.g('03'), 'changed', true, 'matchCount', 1)::text, 'verknüpfter Gast Test3 redigiert');
select is(pg_temp.slot('2', pg_temp.g('03')), 'Gelöschter Spieler|t|null|null', 'Platz redigiert und entkoppelt');
select is(pg_temp.slot('2', pg_temp.g('02')), 'Test2|f|null|null', 'anderer Gast im selben Match unberührt');
select results_eq(format($$select display_name, profile_id is null, assignment_source is null from public.players where id = %L$$, pg_temp.g('03')),
  $$values ('Gelöschter Spieler'::text, true, true)$$, 'players: Name redigiert, Zuordnung gelöst');
select is((select (payload #>> '{player,displayName}') || '|' || (redacted_at is not null) from public.match_events
  where id = '52200000-0000-4000-8000-000000000003'), 'Gelöschter Spieler|true', 'PlayerAssigned-Payload redigiert');
select is((select (payload #>> '{player,displayName}') from public.match_events where id = '52200000-0000-4000-8000-000000000002'),
  'Test2', 'fremdes Event im selben Match unberührt');
select ok(exists (select 1 from private.player_write_redactions where player_id::text = pg_temp.g('03'))
  and exists (select 1 from private.redacted_player_identities where player_id::text = pg_temp.g('03'))
  and exists (select 1 from private.match_redacted_players where player_id::text = pg_temp.g('03')
    and match_id = '52000000-0000-4000-8000-000000000002'), 'Schreibsperre und Manifest');
select ok((select m.redaction_epoch > (b.epochs ->> m.id::text)::int from public.matches m, before_admin b
  where m.id = '52000000-0000-4000-8000-000000000002'), 'Epoche gestiegen');
select is(pg_temp.as_user('alice', $$select (public.cloud_pull_match_redaction_v1('52000000-0000-4000-8000-000000000002',
    (select visibility_epoch from public.profile_settings where profile_id = auth.uid()),
    (select redaction_epoch from public.matches where id = '52000000-0000-4000-8000-000000000002')) -> 'redactedPlayerIds')::text$$),
  jsonb_build_array(pg_temp.g('03'))::text, 'Redaktionsliste für alice enthält den Gast');
select is(pg_temp.as_user('bob', $$select count(*)::text from public.matches where id = '52000000-0000-4000-8000-000000000002'$$),
  '0', 'bob verliert den Zugang über die gelöste Zuordnung');
select is(pg_temp.as_user('alice', format('select public.assign_player(%L, %L)::text', pg_temp.g('03'), tests.uid('bob'))),
  'ERR P0001 player_redacted', 'erneute Zuordnung abgelehnt');
select is((select concat_ws('|', actor_id = tests.uid('adm520'), target_id = tests.uid('alice'), reason, changes::text)
  from ops.account_audit where action = 'guest_name_redact'),
  't|t|Art. 21 Ticket 4711|' || jsonb_build_object('matches', 1, 'player_id', pg_temp.g('03'))::text,
  'Audit: Akteur, Anleger, Begründung, nur IDs und Zahl');
select ok(not exists (select 1 from ops.account_audit a where to_jsonb(a)::text ~ 'Test3'), 'Audit ohne Gastnamen');
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L)::text', pg_temp.g('03'), 'nochmal')),
  jsonb_build_object('playerId', pg_temp.g('03'), 'changed', false, 'matchCount', 0)::text, 'zweiter Aufruf ändert nichts');
-- Entkoppelter Gast nach Kontolöschung (Widerspruch der früher verknüpften Person): offener Claim-Link wird zurückgezogen.
select is(pg_temp.admin('adm520', format('select public.admin_redact_guest_name(%L, %L) ->> %L', pg_temp.g('01'), 'Art. 17', 'changed')),
  'true', 'Test1 redigiert');
select ok((select revoked_at is not null from public.invites where token_hash = sha256(convert_to(current_setting('tests.claim1'), 'UTF8'))),
  'offener Claim-Link für Test1 widerrufen');
select is((select count(*)::int from ops.account_audit where action = 'guest_name_redact'), 2, 'genau zwei Audit-Zeilen');
select * from finish();
rollback;
