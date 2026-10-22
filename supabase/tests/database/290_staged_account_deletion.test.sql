-- Gestaffelte Kontolöschung (20261022000200): A sperrt und tilgt alles Nicht-Matchlokale, B redigiert je Aufruf
-- vollständige Matches samt Epoche, C löscht. Der Job läuft hier mit Budget 0, also genau eine Einheit je Aufruf.
-- Prüfungen auf neue Objekte laufen über pg_temp.q: gegen den Stand vor der Migration sind sie rot statt abzubrechen.
begin;
set local timezone = 'UTC';
select plan(64);
select tests.world();
select tests.publish_consent_documents('staged-290');
select tests.accept_current_consents(id) from tests.users;

create function pg_temp.q(p_sql text) returns text language plpgsql as $$
declare v text;
begin
  execute p_sql into v;
  return v;
exception when others then return 'ERR ' || sqlstate;
end $$;
create function pg_temp.as_user(p_name text, p_sql text) returns text language plpgsql as $$
declare v text;
begin
  perform tests.authenticate_as(p_name);
  execute p_sql into v;
  reset role;
  return v;
exception when others then return 'ERR ' || sqlstate || ' ' || sqlerrm;
end $$;
create function pg_temp.job() returns jsonb language plpgsql as $$
begin
  perform set_config('advantly.account_deletion_budget', '0', true);
  return private.run_account_deletion_job_v1();
end $$;
create function pg_temp.m(p_n integer) returns uuid language sql as $$
  select ('29000000-0000-4000-8000-' || lpad(p_n::text, 12, '0'))::uuid $$;
create function pg_temp.due(p_name text, p_age interval) returns void language sql as $$
  update public.profiles set status = 'deletion_requested' where id = tests.uid(p_name);
  update public.profile_settings set deletion_requested_at = now() - p_age where profile_id = tests.uid(p_name);
$$;
-- 60 Matches von bob mit alice (Self-Player, Klarname im PlayerAssigned) und ein Gäste-Match von alice.
create function pg_temp.history(p_owner text, p_member text, p_from integer, p_count integer, p_self uuid)
returns void language sql as $$
  insert into public.players(id, owner_id, kind, display_name) values (p_self, tests.uid(p_member), 'self', 'Klar');
  insert into public.matches(id, created_by, sport, discipline, format, started_at)
    select pg_temp.m(n), tests.uid(p_owner), 'tennis', 'singles', '{}', now() from generate_series(p_from, p_from + p_count - 1) n;
  insert into public.match_participants(match_id, slot_id, team, position, display_name, profile_id, link_status, player_id)
    select pg_temp.m(n), 'member', 'A', 1, 'Klar ' || p_member, tests.uid(p_member), 'self', p_self
      from generate_series(p_from, p_from + p_count - 1) n
    union all select pg_temp.m(n), 'owner', 'B', 1, 'Owner', tests.uid(p_owner), 'linked', null
      from generate_series(p_from, p_from + p_count - 1) n;
  insert into public.match_events(id, match_id, device_id, device_seq, lamport, wall_clock_ms, type, payload, author_id)
    select gen_random_uuid(), pg_temp.m(n), tests.device('b1'), 1, 1, 1, 'PlayerAssigned',
      jsonb_build_object('type', 'PlayerAssigned', 'slot', 'member',
        'player', jsonb_build_object('id', p_self, 'displayName', 'Klarname ' || p_member)), tests.uid(p_member)
      from generate_series(p_from, p_from + p_count - 1) n;
$$;

select pg_temp.history('bob', 'alice', 1, 60, '29100000-0000-4000-8000-000000000001');
insert into public.matches(id, created_by, sport, discipline, format, started_at)
  values (pg_temp.m(99), tests.uid('alice'), 'tennis', 'singles', '{}', now());
insert into public.match_participants(match_id, slot_id, team, position, display_name, profile_id, link_status) values
  (pg_temp.m(99), 'alice', 'A', 1, 'Alice', tests.uid('alice'), 'self'),
  (pg_temp.m(99), 'guest', 'B', 1, 'Gast', null, null);
insert into public.match_roles(match_id, profile_id, role, status, invited_by)
  values (pg_temp.m(1), tests.uid('alice'), 'scorer', 'accepted', tests.uid('bob'));
insert into public.players(id, owner_id, kind, display_name, profile_id, assigned_by, assignment_source)
  values ('29100000-0000-4000-8000-000000000002', tests.uid('bob'), 'guest', 'Alice bei Bob', tests.uid('alice'),
    tests.uid('bob'), 'assignment');
insert into public.invites(token_hash, kind, created_by, expires_at)
  values (sha256('alice-290'), 'friend', tests.uid('alice'), now() + interval '1 day');
insert into public.tournaments(id, director_id, name, sport)
  values ('29200000-0000-4000-8000-000000000001', tests.uid('alice'), 'Alice Cup', 'tennis');
insert into public.tournament_staff(tournament_id, profile_id, role)
  values ('29200000-0000-4000-8000-000000000001', tests.uid('bob'), 'scorer');
update public.matches set tournament_id = '29200000-0000-4000-8000-000000000001' where id = pg_temp.m(2);
insert into ops.support_tickets(id, owner_id, receipt_hash, payload_hash, category, title, body)
  values ('29300000-0000-4000-8000-000000000001', tests.uid('alice'), sha256('a'), sha256('b'), 'bug', 'Hilfe', 'Text');
select pg_temp.due('alice', interval '15 days');
create temp table epoch_before as select id, redaction_epoch from public.matches;
create temp table bob_before as
  select (select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')) g;

-- Phase A
select is((pg_temp.job()->>'deletedCount')::int, 0, 'A deletes nothing yet');
select is((select status from public.profiles where id = tests.uid('alice')), 'deleting', 'A marks the profile as deleting');
select is(pg_temp.q($$select cardinality(pending_matches) - next_match + 1 from private.account_deletion_progress where profile_id = tests.uid('alice')$$), '61',
  'A lists all affected matches');
select ok(exists(select 1 from private.redacted_profile_identities where profile_id = tests.uid('alice')),
  'A writes the profile tombstone');
select ok(exists(select 1 from private.player_write_redactions where player_id = '29100000-0000-4000-8000-000000000001')
  and exists(select 1 from private.redacted_player_identities where player_id = '29100000-0000-4000-8000-000000000001'),
  'A writes the player fence and the manifest decision');
select is_empty($$select 1 from auth.sessions where user_id = tests.uid('alice')
  union all select 1 from private.cloud_installations where profile_id = tests.uid('alice')
  union all select 1 from private.cloud_session_bindings where profile_id = tests.uid('alice')$$,
  'A removes sessions, installations and bindings');
select ok(exists(select 1 from auth.users where id = tests.uid('alice')), 'Auth account stays until C');

-- Eigenes Konto wirkt wie gelöscht, auch mit neuer Anmeldung (neue Auth-Sitzung, neue Bindung).
select pg_temp.q(format('select tests.create_auth_session(%L)', tests.uid('alice')));
select is(pg_temp.as_user('alice', 'select public.my_auth_profile()'), 'ERR 42501 cloud_session_changed', 'my_auth_profile rejects');
select is(pg_temp.as_user('alice', 'select public.account_deletion_status_v1()'), 'ERR 42501 cloud_session_changed', 'Status rejects');
select is(pg_temp.as_user('alice', 'select public.cancel_account_deletion()'), 'ERR 42501 cloud_session_changed', 'Recovery rejects');
select is(pg_temp.as_user('alice', 'select public.request_account_deletion()'), 'ERR 42501 cloud_session_changed', 'Request rejects');
select is(pg_temp.as_user('alice', 'select public.export_my_data()'), 'ERR 42501 cloud_session_changed', 'Export rejects');
select is(pg_temp.as_user('alice', $$select public.my_consent_v1('en')$$), 'ERR 42501 cloud_session_changed', 'Consent rejects');
select is(pg_temp.as_user('alice', 'select public.sync_pull(0, 10)'), 'ERR 42501 profile_not_active', 'Pull rejects');
select is(pg_temp.as_user('alice', 'select public.cloud_list_installations_v1()'), 'ERR 42501 profile_missing',
  'Installation list rejects');
select is(pg_temp.as_user('alice', $$select public.cloud_revoke_session_v1(gen_random_uuid())$$), 'ERR 42501 profile_missing',
  'Session revocation rejects');
select is(pg_temp.as_user('alice', $$select public.reject_player_assignment('29100000-0000-4000-8000-000000000002')$$),
  'ERR 42501 profile_not_active', 'Assignment rejection rejects');
select is(pg_temp.as_user('alice', $$select public.submit_support('29300000-0000-4000-8000-000000000002', repeat('c', 64),
  'bug', 'Neu', 'Text')->>'error'$$), 'support_unavailable', 'Support intake rejects the account');
select is(pg_temp.as_user('alice', 'select public.my_support_tickets()'), '[]', 'Own support tickets are gone');
select ok(not private.has_profile(tests.uid('alice')), 'has_profile closes the account');
select ok(not private.is_match_member(pg_temp.m(1), tests.uid('alice')), 'Membership is closed');

-- Fremde sehen nichts Nicht-Matchlokales mehr.
select is(pg_temp.as_user('bob', $$select count(*) from jsonb_array_elements(public.sync_pull(0, 500)->'friendships') f
  where f->>'profileId' = tests.uid('alice')::text$$), '0', 'Friendship is gone for the friend');
select is(pg_temp.as_user('bob', $$select coalesce(r->>'alias', 'null') from jsonb_array_elements(public.sync_pull(0, 500)->'matches') m,
  jsonb_array_elements(m->'roles') r where m->>'id' = pg_temp.m(1)::text and r->>'profileId' = tests.uid('alice')::text$$),
  'null', 'Role alias is hidden');
select is(pg_temp.as_user('bob', $$select p->>'displayName' || ' ' || coalesce(p->>'profileId', 'null')
  from jsonb_array_elements(public.sync_pull(0, 500)->'players') p where p->>'id' = '29100000-0000-4000-8000-000000000002'$$),
  'Gelöschter Spieler null', 'Assigned dummy is unlinked and renamed');
select is_empty($$select 1 from public.invites where created_by = tests.uid('alice')$$, 'Invites are gone');
select is(pg_temp.as_user('bob', $$select public.tournament_overview('29200000-0000-4000-8000-000000000001')$$),
  'ERR 42501 forbidden', 'Staff no longer sees the tournament');
select is_empty($$select 1 from ops.support_tickets where owner_id = tests.uid('alice')$$, 'Support tickets are gone');
select ok((select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')) > (select g from bob_before),
  'Friend generation changes once the friendship is gone');
select is((select display_name from public.match_participants where match_id = pg_temp.m(60) and slot_id = 'member'),
  'Klar alice', 'Unprocessed matches may still show the name');
select is((public.account_deletion_job_status_v1()->>'stagedDeletionCount')::int, 1, 'Status counts the staged deletion');
select ok((public.account_deletion_job_status_v1()->>'oldestStagedDeletionAgeSeconds')::int >= 0
  and public.account_deletion_job_status_v1()::text not like '%' || tests.uid('alice')::text || '%',
  'Status reports the age without account reference');

-- Phase B: erster Block (50 Matches), danach je Match entweder vollständig oder unberührt.
select is((pg_temp.job()->>'deletedCount')::int, 0, 'First batch');
select is(pg_temp.q($$select cardinality(pending_matches) - next_match + 1 from private.account_deletion_progress where profile_id = tests.uid('alice')$$), '11',
  'First batch covers 50 matches');
select is((select array[display_name, deleted_profile::text, coalesce(profile_id::text, 'null'), coalesce(player_id::text, 'null')]
  from public.match_participants where match_id = pg_temp.m(1) and slot_id = 'member'),
  array['Gelöschter Spieler', 'true', 'null', 'null'], 'Processed match: participant redacted and decoupled');
select is((select payload#>>'{player,displayName}' || ' ' || (redacted_at is not null)::text || ' ' || coalesce(author_id::text, 'null')
  from public.match_events where match_id = pg_temp.m(1)), 'Gelöschter Spieler true null', 'Processed match: event redacted');
select is((select m.redaction_epoch - b.redaction_epoch from public.matches m join epoch_before b using (id) where m.id = pg_temp.m(1)),
  2, 'Processed match: epoch raised with capture and event redaction');
select is_empty($$select 1 from public.match_roles where match_id = '29000000-0000-4000-8000-000000000001'$$,
  'Processed match: role removed');
select is((select coalesce(tournament_id::text, 'null') from public.matches where id = pg_temp.m(2)), 'null',
  'Processed match: detached from the tournament of the account');
select is((select array[display_name, (profile_id = tests.uid('alice'))::text] from public.match_participants
  where match_id = pg_temp.m(60) and slot_id = 'member'), array['Klar alice', 'true'], 'Unprocessed match: participant unchanged');
select is((select m.redaction_epoch - b.redaction_epoch from public.matches m join epoch_before b using (id) where m.id = pg_temp.m(60)),
  0, 'Unprocessed match: epoch unchanged');
select is((select payload#>>'{player,displayName}' from public.match_events where match_id = pg_temp.m(60)),
  'Klarname alice', 'Unprocessed match: event unchanged');
select is(pg_temp.as_user('bob', format($$select public.cloud_match_readiness_v1(%L, %s, %s)->>'redactionEpoch'$$, pg_temp.m(1),
  (select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')),
  (select redaction_epoch from public.matches where id = pg_temp.m(1)))),
  (select redaction_epoch::text from public.matches where id = pg_temp.m(1)), 'Readiness accepts the new epoch of a processed match');
select is(pg_temp.as_user('bob', format($$select public.cloud_match_readiness_v1(%L, %s, %s)$$, pg_temp.m(1),
  (select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')),
  (select redaction_epoch from epoch_before where id = pg_temp.m(1)))),
  'ERR 40001 restart_required', 'Readiness rejects the old epoch of a processed match');
select is(pg_temp.as_user('bob', format($$select jsonb_array_length(public.cloud_pull_match_redaction_v1(%L, %s, %s)->'events')$$,
  pg_temp.m(1), (select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')),
  (select redaction_epoch from public.matches where id = pg_temp.m(1)))), '1', 'Redaction transfer delivers the redacted event');
select is(pg_temp.as_user('bob', format($$select public.cloud_match_readiness_v1(%L, %s, %s)->>'redactionEpoch'$$, pg_temp.m(60),
  (select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')),
  (select redaction_epoch from epoch_before where id = pg_temp.m(60)))),
  (select redaction_epoch::text from epoch_before where id = pg_temp.m(60)), 'Readiness keeps the old epoch of an unprocessed match');

-- Abbruch im zweiten Block: nichts davon bleibt, kein Fehlerdetail, Fortsetzung danach ohne doppelte Epoche.
create function pg_temp.fail_batch() returns trigger language plpgsql as $$
begin
  if new.match_id = pg_temp.m(55) then raise exception 'sensitive-batch-detail'; end if;
  return new;
end $$;
create trigger zz_test_fail_batch before update on public.match_participants for each row execute function pg_temp.fail_batch();
select is((select r->>'status' || ' ' || (r->>'errorCode') from (select pg_temp.job() r) x), 'failed account_deletion_failed',
  'Failed batch is reported');
select is(pg_temp.q($$select cardinality(pending_matches) - next_match + 1 from private.account_deletion_progress where profile_id = tests.uid('alice')$$), '11',
  'Failed batch keeps the work list');
select is((select m.redaction_epoch - b.redaction_epoch from public.matches m join epoch_before b using (id) where m.id = pg_temp.m(51)),
  0, 'Failed batch leaves no half-redacted match');
select is((select attempts from private.account_deletion_failures where profile_id = tests.uid('alice')), 1, 'Failed batch counts one attempt');
drop trigger zz_test_fail_batch on public.match_participants;
select is((pg_temp.job()->>'status'), 'succeeded', 'Batch is retried');
select is((select m.redaction_epoch - b.redaction_epoch from public.matches m join epoch_before b using (id) where m.id = pg_temp.m(51)),
  2, 'Retried batch raises the epoch exactly once per step');
select is_empty($$select 1 from private.account_deletion_failures where profile_id = tests.uid('alice')$$,
  'A successful step clears the failure counter');
select is_empty($$select 1 from public.matches where id = '29000000-0000-4000-8000-000000000099'$$, 'Guest-only match is deleted in B');

-- Phase C, mit Abbruch und Fortsetzung.
create function pg_temp.fail_purge() returns trigger language plpgsql as $$
begin raise exception 'sensitive-purge-detail'; end $$;
create trigger zz_test_fail_purge before delete on auth.users for each row execute function pg_temp.fail_purge();
select is((pg_temp.job()->>'errorCode'), 'account_deletion_failed', 'Failed C is reported');
select is((select status from public.profiles where id = tests.uid('alice')), 'deleting', 'Failed C keeps the staged account');
drop trigger zz_test_fail_purge on auth.users;
select is((pg_temp.job()->>'deletedCount')::int, 1, 'C deletes the account');
select is_empty($$select 1 from auth.users where id = tests.uid('alice') union all
  select 1 from public.match_participants where display_name like '%alice%' union all
  select 1 from public.match_events where payload::text like '%Klarname alice%' union all
  select 1 from public.tournaments where director_id = tests.uid('alice')$$, 'Nothing of the account remains');
select is(pg_temp.q($$select count(*) from private.account_deletion_progress$$), '0', 'Progress row is gone with the profile');
select is((select created_by from public.matches where id = pg_temp.m(1)), tests.uid('bob'), 'Shared matches stay with the owner');

-- Abbruch in A: weder Status noch Grabstein bleiben.
select tests.create_user('ann');
select pg_temp.due('ann', interval '20 days');
create function pg_temp.fail_begin() returns trigger language plpgsql as $$
begin
  if new.status = 'deleting' then raise exception 'sensitive-begin-detail'; end if;
  return new;
end $$;
create trigger zz_test_fail_begin before update on public.profiles for each row execute function pg_temp.fail_begin();
select is((pg_temp.job()->>'errorCode'), 'account_deletion_failed', 'Failed A is reported');
select ok((select status from public.profiles where id = tests.uid('ann')) = 'deletion_requested'
  and not exists(select 1 from private.redacted_profile_identities where profile_id = tests.uid('ann')),
  'Failed A leaves neither status nor tombstone');
drop trigger zz_test_fail_begin on public.profiles;

-- Reihum: ein großes Konto bremst ein kleines, später fälliges nicht aus.
select tests.create_user('big');
select tests.create_user('small');
select pg_temp.history('carol', 'big', 101, 120, '29100000-0000-4000-8000-000000000003');
select pg_temp.history('carol', 'small', 301, 1, '29100000-0000-4000-8000-000000000004');
select pg_temp.due('big', interval '19 days');
select pg_temp.due('small', interval '18 days');
select pg_temp.job() from generate_series(1, 8);
select ok(not exists(select 1 from auth.users where id in (tests.uid('ann'), tests.uid('small')))
  and pg_temp.q($$select cardinality(pending_matches) - next_match + 1 from private.account_deletion_progress where profile_id = tests.uid('big')$$) = '20',
  'Small accounts finish while the big one has done two batches');

-- Löschweg außerhalb des Jobs mitten in B (etwa Dashboard): die Restliste wird im selben DELETE abgearbeitet.
delete from auth.users where id = tests.uid('big');
select is_empty($$select 1 from public.match_participants where display_name = 'Klar big' union all
  select 1 from public.match_events where payload::text like '%Klarname big%'$$, 'Out-of-band delete finishes all matches');
select is((select min(redaction_epoch) || ' ' || max(redaction_epoch) from public.matches
  where id between pg_temp.m(101) and pg_temp.m(220)), '2 2', 'Every match of the out-of-band deletion is raised exactly twice');
select * from finish();
rollback;
