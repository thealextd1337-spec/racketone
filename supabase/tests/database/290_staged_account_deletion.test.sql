-- Gestaffelte Kontolöschung (20261022000200): A sperrt und tilgt alles Nicht-Matchlokale, B redigiert je Aufruf
-- vollständige Matches samt Epoche, C löscht. Der Job läuft hier mit Budget 0, also genau eine Einheit je Aufruf.
-- Prüfungen auf neue Objekte laufen über pg_temp.q: gegen den Stand vor der Migration sind sie rot statt abzubrechen.
-- Review r1: jede Zugriffsänderung mit neuer Generation (P2-2), Fehler eines Kontos halten andere nicht auf (P2-1),
-- zwei Löschungen mit gemeinsamen Matches enden wie zwei nacheinander laufende purge_account (P3), Blockgröße.
-- Eigene, nicht zugeordnete Gäste des Kontos werden in gemeinsamen Matches redigiert und stillgelegt (K7, 23.10.2026).
-- 260/270 füllen die drei Tabellen unten in zurückgerollten Transaktionen. Räumt Autovacuum die toten Zeilen ab, während
-- ein Test sie sperrt, entfällt das Kürzen: reltuples 0 bei vielen Seiten, der Planner schätzt jede Abfrage auf 1 Zeile,
-- die Übergabe in anonymize_profile läuft kubisch und 290 hing in CI bis zum Jobabbruch. VACUUM vorab kürzt sie.
vacuum public.matches, public.match_participants, public.match_events;
begin;
set local timezone = 'UTC';
-- Ein Hänger beendet die Sitzung nach 5 min (normal ~15 s); statement_timeout fangen die Job-Einheiten selbst ab.
set local transaction_timeout = '5min';
select plan(106);
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
-- dave ist Co-Leitung in alice' Turnier, aber nicht mit ihr befreundet (Review P2-2).
insert into public.tournament_staff(tournament_id, profile_id, role)
  values ('29200000-0000-4000-8000-000000000001', tests.uid('dave'), 'co_director');
select pg_temp.due('alice', interval '15 days');
-- Pull-Zugriff (Match:Stufe) und Generation G je Fremdkonto nach jedem Schritt. Der Client verlangt: gleiches G,
-- gleiche Stufe. pg_temp.unbumped nennt jedes Konto, dessen Zugriff sich zwischen zwei Schritten ohne neues G ändert.
create temp table access_log(step integer, name text, g bigint, access text);
create function pg_temp.snap(p_step integer) returns void language plpgsql as $$
declare v_name text;
begin
  foreach v_name in array array['bob', 'carol', 'dave', 'vera'] loop
    insert into access_log select p_step, v_name,
      (select visibility_epoch from public.profile_settings where profile_id = tests.uid(v_name)),
      pg_temp.as_user(v_name, $q$select coalesce(string_agg((m->>'id') || ':' || (m->>'access'), ',' order by m->>'id'), '')
        from jsonb_array_elements(public.sync_pull(0, 500)->'matches') m$q$);
  end loop;
end $$;
create function pg_temp.unbumped(p_from integer, p_to integer) returns text language sql as $$
  select coalesce(string_agg(b.name, ',' order by b.name), '') from access_log a join access_log b using (name)
  where a.step = p_from and b.step = p_to and a.access is distinct from b.access and b.g <= a.g $$;
select pg_temp.snap(0);
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
select pg_temp.snap(1);
select isnt((select access from access_log where step = 1 and name = 'dave'), (select access from access_log where step = 0 and name = 'dave'),
  'Co-director without friendship loses tournament access in A');
select is(pg_temp.unbumped(0, 1), '', 'Every pull access change in A comes with a new generation');

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
select pg_temp.snap(2);
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
select pg_temp.snap(3);
drop trigger zz_test_fail_batch on public.match_participants;
select is((pg_temp.job()->>'status'), 'succeeded', 'Batch is retried');
select pg_temp.snap(4);
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
select pg_temp.snap(5);
select is(pg_temp.unbumped(1, 2) || pg_temp.unbumped(2, 3) || pg_temp.unbumped(3, 4) || pg_temp.unbumped(4, 5), '',
  'Every pull access change in B and C comes with a new generation');
select is_empty($$select 1 from auth.users where id = tests.uid('alice') union all
  select 1 from public.match_participants where display_name like '%alice%' union all
  select 1 from public.match_events where payload::text like '%Klarname alice%' union all
  select 1 from public.tournaments where director_id = tests.uid('alice')$$, 'Nothing of the account remains');
select is(pg_temp.q($$select count(*) from private.account_deletion_progress$$), '0', 'Progress row is gone with the profile');
select is((select created_by from public.matches where id = pg_temp.m(1)), tests.uid('bob'), 'Shared matches stay with the owner');

-- Abbruch in A: weder Status noch Grabstein bleiben. Das A von ann scheitert ab hier dauerhaft (Review P2-1).
select tests.create_user('ann');
select pg_temp.due('ann', interval '20 days');
create function pg_temp.fail_begin() returns trigger language plpgsql as $$
begin
  if new.status = 'deleting' and new.id = tests.uid('ann') then raise exception 'sensitive-begin-detail'; end if;
  return new;
end $$;
create trigger zz_test_fail_begin before update on public.profiles for each row execute function pg_temp.fail_begin();
select is((pg_temp.job()->>'errorCode'), 'account_deletion_failed', 'Failed A is reported');
select ok((select status from public.profiles where id = tests.uid('ann')) = 'deletion_requested'
  and not exists(select 1 from private.redacted_profile_identities where profile_id = tests.uid('ann')),
  'Failed A leaves neither status nor tombstone');

-- Reihum: ein großes Konto bremst ein kleines, später fälliges nicht aus. Das dauerhaft scheiternde A von ann (älteste
-- Frist) kommt hinter alle anderen Einheiten und hält beide nicht auf.
select tests.create_user('big');
select tests.create_user('small');
select pg_temp.history('carol', 'big', 101, 120, '29100000-0000-4000-8000-000000000003');
select pg_temp.history('carol', 'small', 301, 1, '29100000-0000-4000-8000-000000000004');
select pg_temp.due('big', interval '19 days');
select pg_temp.due('small', interval '18 days');
select pg_temp.job() from generate_series(1, 6);
select ok(not exists(select 1 from auth.users where id = tests.uid('small'))
  and pg_temp.q($$select cardinality(pending_matches) - next_match + 1 from private.account_deletion_progress where profile_id = tests.uid('big')$$) = '20',
  'Small accounts finish while the big one has done two batches, despite a permanently failing A');
select is((select status || ' ' || (select attempts from private.account_deletion_failures where profile_id = tests.uid('ann'))
  from public.profiles where id = tests.uid('ann')), 'deletion_requested 1', 'The failing account waits behind all other work');

-- Löschweg außerhalb des Jobs mitten in B (etwa Dashboard): die Restliste wird im selben DELETE abgearbeitet.
delete from auth.users where id = tests.uid('big');
select is_empty($$select 1 from public.match_participants where display_name = 'Klar big' union all
  select 1 from public.match_events where payload::text like '%Klarname big%'$$, 'Out-of-band delete finishes all matches');
select is((select min(redaction_epoch) || ' ' || max(redaction_epoch) from public.matches
  where id between pg_temp.m(101) and pg_temp.m(220)), '2 2', 'Every match of the out-of-band deletion is raised exactly twice');

-- Im selben Aufruf: Nach dem Fehler eines Kontos laufen die Einheiten anderer weiter. stuck hat einen dauerhaft
-- scheiternden Matchblock; er hält weder A und C von late noch das A von ann auf.
select tests.create_user('stuck');
select tests.create_user('late');
select pg_temp.history('carol', 'stuck', 401, 3, '29100000-0000-4000-8000-000000000005');
select pg_temp.history('carol', 'late', 501, 2, '29100000-0000-4000-8000-000000000006');
select pg_temp.due('stuck', interval '17 days');
select pg_temp.job();
create function pg_temp.fail_stuck() returns trigger language plpgsql as $$
begin
  if new.match_id = pg_temp.m(401) then raise exception 'sensitive-stuck-detail'; end if;
  return new;
end $$;
create trigger zz_test_fail_stuck before update on public.match_participants for each row execute function pg_temp.fail_stuck();
select pg_temp.due('late', interval '16 days');
create temp table one_call as select private.run_account_deletion_job_v1() r
  from (select set_config('advantly.account_deletion_budget', '30s', true)) z;
select is((select (r->>'deletedCount') || ' ' || (r->>'status') || ' ' || (r->>'errorCode') from one_call),
  '1 failed account_deletion_failed', 'One call reports the failure and still deletes the other account');
select ok(not exists(select 1 from auth.users where id = tests.uid('late')), 'A and C of another account run in the same call');
select is((select string_agg(t.identifier || '=' || f.attempts, ',' order by t.identifier) from private.account_deletion_failures f
  join tests.users t on t.id = f.profile_id), 'ann=2,stuck=1', 'Each failing account is tried once per call');

-- Gemeinsames Match mit einer hängenden Löschung: A wartet, bis diese drei Fehlversuche hat, dann nicht länger.
select tests.create_user('frank');
insert into public.match_participants(match_id, slot_id, team, position, display_name, profile_id, link_status)
  values (pg_temp.m(401), 'frank', 'A', 2, 'Frank', tests.uid('frank'), 'linked');
select pg_temp.due('frank', interval '15 days');
select pg_temp.job();
select is((select status from public.profiles where id = tests.uid('frank')), 'deletion_requested',
  'A waits while a running deletion still has shared matches open');
-- ann und stuck scheitern abwechselnd: so lange aufrufen, bis stuck drei Fehlversuche hat, dann noch einmal.
do $$ begin
  for i in 1..10 loop
    exit when (select attempts from private.account_deletion_failures where profile_id = tests.uid('stuck')) >= 3;
    perform pg_temp.job();
  end loop;
end $$;
select pg_temp.job();
select is((select status || ' ' || (select attempts from private.account_deletion_failures where profile_id = tests.uid('stuck'))
  from public.profiles where id = tests.uid('frank')), 'deleting 3', 'A no longer waits for a stalled deletion');
drop trigger zz_test_fail_stuck on public.match_participants;
drop trigger zz_test_fail_begin on public.profiles;
create function pg_temp.drain() returns integer language plpgsql as $$
declare v integer := 0;
begin
  while v < 200 and (exists(select 1 from public.profiles where status = 'deleting')
      or exists(select 1 from public.profiles p join public.profile_settings s on s.profile_id = p.id
        where p.status = 'deletion_requested' and s.deletion_requested_at <= now() - interval '14 days')) loop
    perform pg_temp.job();
    v := v + 1;
  end loop;
  return v;
end $$;
select ok(pg_temp.drain() < 200, 'All deletions finish once the faults are gone');

-- Zwei Konten mit gemeinsamen Matches (Review P3): gegenseitige Gast-Dummys, Klarnamen in Events, Rollen. Jeder Lauf
-- startet vom selben Stand (Unterblock, danach zurückgerollt) und vergleicht alle Match-, Player- und Redaktionszeilen
-- ohne Sequenzwerte mit purge_account(erin) und danach purge_account(finn).
select tests.create_user('erin');
select tests.create_user('finn');
insert into public.players(id, owner_id, kind, display_name) values
  ('29600000-0000-4000-8000-0000000000e1', tests.uid('erin'), 'self', 'Erin Self'),
  ('29600000-0000-4000-8000-0000000000f1', tests.uid('finn'), 'self', 'Finn Self'),
  ('29600000-0000-4000-8000-0000000000e2', tests.uid('erin'), 'guest', 'Gast E2');
insert into public.players(id, owner_id, kind, display_name, profile_id, assigned_by, assignment_source) values
  ('29600000-0000-4000-8000-0000000000ef', tests.uid('erin'), 'guest', 'Gast EF', tests.uid('finn'), tests.uid('erin'), 'assignment'),
  ('29600000-0000-4000-8000-0000000000fe', tests.uid('finn'), 'guest', 'Gast FE', tests.uid('erin'), tests.uid('finn'), 'assignment');
insert into public.matches(id, created_by, sport, discipline, format, started_at)
  select pg_temp.m(600 + n), case n % 3 when 0 then tests.uid('carol') when 1 then tests.uid('erin') else tests.uid('finn') end,
    'tennis', 'doubles', '{}', now() from generate_series(1, 12) n;
insert into public.match_participants(match_id, slot_id, team, position, display_name, profile_id, link_status, player_id)
  select pg_temp.m(600 + n), 'e', 'A', 1, 'Erin Klar', tests.uid('erin'), 'linked', '29600000-0000-4000-8000-0000000000e1'::uuid
    from generate_series(1, 12) n where n % 4 <> 3
  union all select pg_temp.m(600 + n), 'f', 'B', 1, 'Finn Klar', tests.uid('finn'), 'linked', '29600000-0000-4000-8000-0000000000f1'::uuid
    from generate_series(1, 12) n where n % 4 <> 2
  union all select pg_temp.m(600 + n), 'g1', 'A', 2, 'Gast EF', case when n % 4 = 2 then tests.uid('finn') end,
    case when n % 4 = 2 then 'linked' end, '29600000-0000-4000-8000-0000000000ef'::uuid from generate_series(1, 12) n where n % 4 in (1, 2)
  union all select pg_temp.m(600 + n), 'g2', 'B', 2, 'Gast FE', case when n % 4 = 3 then tests.uid('erin') end,
    case when n % 4 = 3 then 'linked' end, '29600000-0000-4000-8000-0000000000fe'::uuid from generate_series(1, 12) n where n % 4 in (0, 3)
  union all select pg_temp.m(600 + n), '29600000-0000-4000-8000-0000000000e2', 'B', 2, 'Gast E2', null, null, null
    from generate_series(1, 12) n where n % 5 = 0;
insert into public.match_events(id, match_id, device_id, device_seq, lamport, wall_clock_ms, type, payload, author_id)
  select gen_random_uuid(), pg_temp.m(600 + n), tests.device('b1'), v.s, v.s, v.s, 'PlayerAssigned',
    jsonb_build_object('type', 'PlayerAssigned', 'slot', v.sl, 'player', jsonb_build_object('id', v.pid, 'displayName', v.nm)),
    case when v.s % 2 = 0 then tests.uid('erin') else tests.uid('finn') end
  from generate_series(1, 12) n, (values (1, 'e', '29600000-0000-4000-8000-0000000000e1', 'Erin Klarname'),
    (2, 'f', '29600000-0000-4000-8000-0000000000f1', 'Finn Klarname'), (3, 'g1', '29600000-0000-4000-8000-0000000000ef', 'Gast EF Klar'),
    (4, 'g2', '29600000-0000-4000-8000-0000000000fe', 'Gast FE Klar'), (5, 'x', '29600000-0000-4000-8000-0000000000e2', 'Gast E2 Klar')) v(s, sl, pid, nm);
insert into public.match_roles(match_id, profile_id, role, status, invited_by)
  select pg_temp.m(600 + n), tests.uid('carol'), 'scorer', 'accepted',
    case when n % 2 = 0 then tests.uid('erin') else tests.uid('finn') end from generate_series(1, 12) n;
select pg_temp.due('erin', interval '16 days');
select pg_temp.due('finn', interval '15 days');
create function pg_temp.digest() returns text language sql as $$
  select md5(string_agg(r, E'\n' order by r)) from (
    select 'm ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text r from public.matches x
    union all select 'p ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text from public.match_participants x
    union all select 'e ' || (to_jsonb(x) - 'server_seq')::text from public.match_events x
    union all select 'r ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text from public.match_roles x
    union all select 'pl ' || (to_jsonb(x) - 'server_version' - 'updated_at')::text from public.players x
    union all select 'n ' || to_jsonb(x)::text from private.match_redacted_names x
    union all select 's ' || to_jsonb(x)::text from private.match_redacted_slots x
    union all select 'rp ' || to_jsonb(x)::text from private.match_redacted_players x
    union all select 'ri ' || to_jsonb(x)::text from private.redacted_player_identities x
    union all select 'pw ' || to_jsonb(x)::text from private.player_write_redactions x
    union all select 'pr ' || to_jsonb(x)::text from private.redacted_profile_identities x
    union all select 'u ' || x.id::text from auth.users x) d $$;
-- Modi: old = purge_account(erin), dann finn; job = nur Job-Aufrufe; dash_other = erin gestaffelt, finn per DELETE
-- mitten in erins B; dash_self = erin per DELETE mitten in B, finn danach im Job. Blöcke à 1 Match, wo möglich.
create temp table deferred_seen(seen boolean);
create function pg_temp.run(p_mode text) returns text language plpgsql as $f$
declare v text; v_calls integer := 0; v_seen boolean;
begin
  begin
    if p_mode = 'old' then
      delete from auth.users where id = tests.uid('erin');
      delete from auth.users where id = tests.uid('finn');
    else
      perform pg_temp.job();
      perform pg_temp.q($$update private.account_deletion_progress set block_size = 1 returning 'x'$$);
      perform pg_temp.job();
      perform pg_temp.job();
      if p_mode = 'job' then
        select status = 'deletion_requested' into v_seen from public.profiles where id = tests.uid('finn');
      elsif p_mode = 'dash_other' then
        delete from auth.users where id = tests.uid('finn');
      elsif p_mode = 'dash_self' then
        delete from auth.users where id = tests.uid('erin');
      end if;
      while v_calls < 100 and exists(select 1 from auth.users where id in (tests.uid('erin'), tests.uid('finn'))) loop
        perform pg_temp.job();
        perform pg_temp.q($$update private.account_deletion_progress set block_size = 1 returning 'x'$$);
        v_calls := v_calls + 1;
      end loop;
    end if;
    v := pg_temp.digest();
    raise exception using errcode = 'AB001';
  exception when sqlstate 'AB001' then null;
    when others then v := 'ERR ' || sqlstate || ' ' || sqlerrm;
  end;
  if p_mode = 'job' then insert into deferred_seen values (v_seen); end if;
  return v;
end $f$;
create temp table runs as select m, pg_temp.run(m) d from unnest(array['old', 'job', 'dash_other', 'dash_self']) m;
select is((select d from runs where m = 'job'), (select d from runs where m = 'old'),
  'Two staged deletions with shared matches end like two purge_account in a row');
select ok((select bool_and(seen) from deferred_seen), 'The second A waits while the first deletion has shared matches open');
select is((select d from runs where m = 'dash_other'), (select d from runs where m = 'old'),
  'Out-of-band delete of the second account during B ends like two purge_account in a row');
select is((select d from runs where m = 'dash_self'), (select d from runs where m = 'old'),
  'Out-of-band delete of the staged account during B ends like two purge_account in a row');

-- Review r2 P3-1, Ausnahmepfad: erins erster Block scheitert dauerhaft, ab drei Fehlversuchen beginnt finn trotzdem.
-- Der Endzustand ist dann nicht garantiert gleich. Garantiert bleiben: keine Klarnamen, die nicht auch zwei
-- purge_account hinterlassen, jede Namensänderung eines Matches mit höherer Epoche (E monoton), jede Zugriffsänderung
-- mit neuem G. Ein Schritt je Job-Aufruf (Budget 0, eine Einheit).
create function pg_temp.fail_erin() returns trigger language plpgsql as $$
begin
  if new.match_id = pg_temp.m(601) and old.profile_id = tests.uid('erin') then raise exception 'sensitive-erin-detail'; end if;
  return new;
end $$;
create function pg_temp.names() returns text language sql as $$
  select coalesce(string_agg(n, ',' order by n), '') from (
    select display_name n from public.match_participants where match_id between pg_temp.m(601) and pg_temp.m(612)
    union all select payload#>>'{player,displayName}' from public.match_events where match_id between pg_temp.m(601) and pg_temp.m(612)
    union all select display_name from public.players where id::text like '29600000-%') x
  where n <> 'Gelöschter Spieler' $$;
create temp table names_log(step integer, id uuid, e bigint, names text);
create function pg_temp.log_step(p_step integer) returns void language plpgsql as $$
begin
  perform pg_temp.snap(p_step);
  insert into names_log select p_step, m.id, m.redaction_epoch, md5(
      coalesce((select string_agg(display_name, ',' order by slot_id) from public.match_participants where match_id = m.id), '')
      || '|' || coalesce((select string_agg(payload#>>'{player,displayName}', ',' order by id) from public.match_events
        where match_id = m.id), ''))
    from public.matches m where m.id between pg_temp.m(601) and pg_temp.m(612);
end $$;
create function pg_temp.stalled() returns text[] language plpgsql as $f$
declare v text[]; v_step integer := 100; v_path boolean;
begin
  begin
    create trigger zz_test_fail_erin before update on public.match_participants for each row execute function pg_temp.fail_erin();
    perform pg_temp.log_step(v_step);
    while v_step < 160 and exists(select 1 from auth.users where id = tests.uid('finn')) loop
      perform pg_temp.job();
      v_step := v_step + 1;
      perform pg_temp.log_step(v_step);
    end loop;
    v_path := not exists(select 1 from auth.users where id = tests.uid('finn'))
      and (select attempts from private.account_deletion_failures where profile_id = tests.uid('erin')) >= 3
      and pg_temp.q($$select next_match from private.account_deletion_progress where profile_id = tests.uid('erin')$$) = '1';
    drop trigger zz_test_fail_erin on public.match_participants;
    while v_step < 220 and exists(select 1 from auth.users where id = tests.uid('erin')) loop
      perform pg_temp.job();
      v_step := v_step + 1;
      perform pg_temp.log_step(v_step);
    end loop;
    v := array[v_path::text, pg_temp.names(),
      (select count(*)::text from names_log a join names_log b on b.id = a.id and b.step = a.step + 1
        where b.e < a.e or (b.names <> a.names and b.e = a.e)),
      (select string_agg(pg_temp.unbumped(s, s + 1), '' order by s) from generate_series(100, v_step - 1) s),
      (exists(select 1 from auth.users where id in (tests.uid('erin'), tests.uid('finn'))))::text];
    raise exception using errcode = 'AB001';
  exception when sqlstate 'AB001' then null;
    when others then v := array['ERR ' || sqlstate || ' ' || sqlerrm];
  end;
  return v;
end $f$;
create function pg_temp.old_names() returns text language plpgsql as $f$
declare v text;
begin
  begin
    delete from auth.users where id = tests.uid('erin');
    delete from auth.users where id = tests.uid('finn');
    v := pg_temp.names();
    raise exception using errcode = 'AB001';
  exception when sqlstate 'AB001' then null;
  end;
  return v;
end $f$;
create temp table stalled_run as select pg_temp.stalled() r, pg_temp.old_names() old;
select is((select r[1] || ' ' || r[5] from stalled_run), 'true false',
  'Stalled path: the second account finishes while the first still has its shared matches open, then both are gone');
select ok((select r[2] = old and r[2] !~ '(Erin|Finn) ' from stalled_run),
  'Stalled path leaves no clear names beyond those of two purge_account in a row');
select ok((select r[2] !~ 'Gast E2' from stalled_run), 'Stalled path also redacts the own guest of the stalled account');
select is((select r[3] from stalled_run), '0', 'Stalled path: every name change raises the epoch, which never drops');
select is((select r[4] from stalled_run), '', 'Stalled path: every pull access change comes with a new generation');

-- Eigene Gäste (Nutzerentscheid 23.10.2026), tools/pilot-checks/check_deletion_job.py als SQL: gina lädt wie der Client
-- ein Match mit Freund hank hoch (Self-Player, eigener Gast in Teilnehmern und Lineup, namentragende Zuweisung) und
-- weist hank einen zweiten eigenen Gast zu. Gestaffelt gelöscht sieht hank weder ginas Alias noch den Gastnamen, auch
-- im inkrementellen Pull; jede Namensänderung kam mit höherem E; die Gast-ID ist stillgelegt; der Gast von hank bleibt.
select pg_temp.drain();
select tests.create_user('gina');
select tests.create_user('hank');
select tests.accept_current_consents(tests.uid(n)) from unnest(array['gina', 'hank']) n;
insert into public.friendships(requester_id, addressee_id, status, responded_at)
  values (tests.uid('gina'), tests.uid('hank'), 'accepted', now());
create function pg_temp.push(p_name text, p_batch jsonb) returns text language plpgsql as $$
declare v_life jsonb := jsonb_build_object('generation',
  (select visibility_epoch from public.profile_settings where profile_id = tests.uid(p_name)),
  'redactionEpochs', (select coalesce(jsonb_object_agg(id::text, redaction_epoch), '{}') from public.matches));
begin
  return pg_temp.as_user(p_name, format('select public.cloud_sync_push_v1(%L)::text', p_batch || jsonb_build_object('lifecycle', v_life)));
end $$;
create temp table k7 as select '29700000-0000-4000-8000-000000000001'::uuid mid, '29700000-0000-4000-8000-0000000000a1'::uuid self,
  '29700000-0000-4000-8000-0000000000a2'::uuid guest, '29700000-0000-4000-8000-0000000000a3'::uuid kept,
  (select device_id from tests.prepare_cloud_context(tests.uid('gina'))) dev;
create temp table k7_push as select pg_temp.push('gina', (select jsonb_build_object(
    'players', jsonb_build_array(jsonb_build_object('id', self, 'kind', 'self', 'displayName', 'Gina'),
      jsonb_build_object('id', guest, 'kind', 'guest', 'displayName', 'K7 Gast 290'),
      jsonb_build_object('id', kept, 'kind', 'guest', 'displayName', 'Gast Hank')),
    'matches', jsonb_build_array(tests.match(mid, jsonb_build_array(tests.part(self::text, 'A', 1, 'Gina', tests.uid('gina'), self),
      tests.part('s-hank', 'B', 1, 'Hank', tests.uid('hank')), tests.part(guest::text, 'B', 2, 'K7 Gast 290', null, guest),
      tests.part(kept::text, 'A', 2, 'Gast Hank', null, kept)), '{"discipline":"doubles"}')),
    'events', jsonb_build_array(
      tests.event(gen_random_uuid(), mid, dev, 1, 'MatchCreated', jsonb_build_object('lineup', jsonb_build_object(
        'A', jsonb_build_array(self, kept), 'B', jsonb_build_array('s-hank', guest), 'firstServer', self), 'createdBy', self)),
      tests.event(gen_random_uuid(), mid, dev, 2, 'PointWon', '{"side":"A"}'),
      tests.event(gen_random_uuid(), mid, dev, 3, 'PlayerAssigned', jsonb_build_object('slot', guest, 'assignedBy', self,
        'player', jsonb_build_object('id', guest, 'displayName', 'K7 Gast 290'))),
      tests.event(gen_random_uuid(), mid, dev, 4, 'PlayerAssigned', jsonb_build_object('slot', kept, 'assignedBy', self,
        'player', jsonb_build_object('id', kept, 'displayName', 'Gast Hank Klar'))))) from k7)) r;
select is((select case when r like 'ERR%' then r else (r::jsonb#>>'{matches,0,result}') || ' ' || (select string_agg(e->>'result', ',')
    from jsonb_array_elements(r::jsonb->'events') e) end from k7_push), 'created accepted,accepted,accepted,accepted',
  'K7: gina uploads the match with her guest and a named assignment');
select is(pg_temp.as_user('gina', format('select public.assign_player(%L, %L)', (select kept from k7), tests.uid('hank'))), '0',
  'K7: gina assigns her second guest to hank');
create function pg_temp.k7_pull(p_cursor bigint) returns jsonb language sql as $$
  select pg_temp.as_user('hank', format('select public.cloud_sync_pull_v1(%s, 200)::text', p_cursor))::jsonb $$;
create function pg_temp.k7_visible() returns text language sql as $$
  select (select j::text from jsonb_array_elements(pg_temp.k7_pull(0)->'matches') j where j->>'id' = (select mid::text from k7))
    || pg_temp.as_user('hank', format('select jsonb_agg(e)::text from public.cloud_pull_match_events_v1(%L) e', (select mid from k7))) $$;
create temp table k7_before as select (select redaction_epoch from public.matches where id = (select mid from k7)) e,
  (pg_temp.k7_pull(0)->>'cursor')::bigint c, pg_temp.k7_visible() v;
select ok((select v like '%K7 Gast 290%' and v like '%Gina%' from k7_before), 'K7: hank sees the match with both names');
create temp table k7_log(step integer, e integer, names text);
create function pg_temp.k7_step(p_step integer) returns void language sql as $$
  insert into k7_log select p_step, m.redaction_epoch, md5(
      coalesce((select string_agg(display_name, ',' order by slot_id) from public.match_participants where match_id = m.id), '')
      || '|' || coalesce((select string_agg(payload::text, ',' order by id) from public.match_events where match_id = m.id), ''))
    from public.matches m where m.id = (select mid from k7) $$;
select pg_temp.k7_step(0);
select pg_temp.due('gina', interval '15 days');
do $$ begin
  for i in 1..10 loop
    exit when not exists(select 1 from auth.users where id = tests.uid('gina'));
    perform pg_temp.job();
    perform pg_temp.k7_step(i);
  end loop;
end $$;
select ok(not exists(select 1 from auth.users where id = tests.uid('gina'))
  and (select count(*) from k7_log) between 4 and 5, 'K7: the job deletes gina in A, B and C');
select is((select count(*)::int from k7_log a join k7_log b on b.step = a.step + 1 where b.e < a.e or (b.names <> a.names and b.e <= a.e)), 0,
  'K7: every name change in the shared match comes with a higher epoch');
create temp table k7_after as select pg_temp.k7_visible() v, pg_temp.k7_pull((select c from k7_before)) inc;
select ok((select v not like '%K7 Gast 290%' and v not like '%Gina%' from k7_after),
  'K7: neither the guest name nor the alias of gina is visible to hank after the deletion');
select is((select string_agg(p->>'displayName', ',' order by p->>'slotId') from k7_after,
    jsonb_array_elements(inc->'matches') j, jsonb_array_elements(j->'participants') p
    where j->>'id' = (select mid::text from k7) and p->>'slotId' in ((select self::text from k7), (select guest::text from k7))),
  'Gelöschter Spieler,Gelöschter Spieler', 'K7: incremental pull from the old cursor delivers both slots redacted');
select is((select array[display_name, deleted_profile::text, coalesce(player_id::text, 'null')] from public.match_participants
  where match_id = (select mid from k7) and slot_id = (select guest::text from k7)),
  array['Gelöschter Spieler', 'true', 'null'], 'K7: own guest slot is redacted like the deleted account');
select is((select payload#>>'{player,displayName}' || ' ' || (redacted_at is not null)::text from public.match_events
  where match_id = (select mid from k7) and payload#>>'{player,id}' = (select guest::text from k7)),
  'Gelöschter Spieler true', 'K7: PlayerAssigned of the own guest is redacted and marked');
select is(pg_temp.as_user('hank', format($$select string_agg(x, ',' order by x) from jsonb_array_elements_text(
    public.cloud_pull_match_redaction_v1(%L, %s, %s)->'redactedPlayerIds') x$$, (select mid from k7),
    (select visibility_epoch from public.profile_settings where profile_id = tests.uid('hank')),
    (select redaction_epoch from public.matches where id = (select mid from k7)))),
  (select string_agg(x::text, ',' order by x::text) from k7, unnest(array[self, guest]) x),
  'K7: manifest of the shared match lists the self player and the own guest, not the guest of hank');
select ok(exists(select 1 from private.match_redacted_names where match_id = (select mid from k7) and identity_id = (select guest from k7))
  and (select redaction_epoch from public.matches where id = (select mid from k7)) > (select e from k7_before),
  'K7: lineup reference of the own guest is recorded for the device redaction, E increased');
select is((select array[p.owner_id::text, p.display_name, mp.display_name, e.payload#>>'{player,displayName}']
  from k7 join public.players p on p.id = k7.kept
  join public.match_participants mp on mp.match_id = k7.mid and mp.slot_id = k7.kept::text
  join public.match_events e on e.match_id = k7.mid and e.payload#>>'{player,id}' = k7.kept::text),
  array[tests.uid('hank')::text, 'Gast Hank', 'Gast Hank', 'Gast Hank Klar'], 'K7: the guest assigned to hank goes to hank and keeps its name');
-- Stillgelegt: Neuanlage lehnt schon deleted_player_identities ab; eine dennoch vorhandene Zeile mit derselben ID
-- (Altbestand, Restore) darf weder zugewiesen noch beansprucht werden.
select ok(private.player_write_redacted((select guest::text from k7)) and not private.player_write_redacted((select kept::text from k7)),
  'K7: the own guest ID is retired, the assigned guest is not');
insert into public.players(id, owner_id, kind, display_name) select guest, tests.uid('hank'), 'guest', 'Wieder da' from k7;
select is(pg_temp.as_user('hank', format('select public.assign_player(%L, %L)', (select guest from k7), tests.uid('vera'))),
  'ERR P0001 player_redacted', 'K7: a later assignment of the retired guest ID fails');
select is(pg_temp.as_user('hank', format($$select public.create_invite('claim_player', %L)$$, (select guest from k7))),
  'ERR P0001 player_redacted', 'K7: a later claim of the retired guest ID fails');
delete from public.players where id = (select guest from k7);

-- Rückfall: Ein Matchblock über dem Statementlimit läuft beim nächsten Mal halb so groß (50 -> 25). Je Match 80 ms
-- (eine Teilnehmerzeile, danach hat der Guard profile_id schon geleert): 50 Matches 4 s, sicher über der Grenze von
-- 3,5 s; 25 Matches 2 s, also 1,5 s Reserve für langsame Runner.
select tests.create_user('slow');
select pg_temp.history('carol', 'slow', 701, 60, '29100000-0000-4000-8000-000000000007');
select pg_temp.drain();
select pg_temp.due('slow', interval '15 days');
create function pg_temp.slow_rows() returns trigger language plpgsql as $$
begin
  if old.profile_id = tests.uid('slow') then perform pg_sleep(0.08); end if;
  return new;
end $$;
create trigger zz_test_slow before update on public.match_participants for each row execute function pg_temp.slow_rows();
select pg_temp.job();
set local statement_timeout = '3.5s';
create temp table slow1 as select pg_temp.job() r;
reset statement_timeout;
select is((select r->>'errorCode' from slow1) || ' ' || pg_temp.q($$select block_size || ' ' || (cardinality(pending_matches) - next_match + 1)
  from private.account_deletion_progress where profile_id = tests.uid('slow')$$), 'statement_canceled 25 60',
  'A block over the statement limit halves the next block');
set local statement_timeout = '3.5s';
create temp table slow2 as select pg_temp.job() r;
reset statement_timeout;
select is((select r->>'status' from slow2) || ' ' || pg_temp.q($$select cardinality(pending_matches) - next_match + 1
  from private.account_deletion_progress where profile_id = tests.uid('slow')$$), 'succeeded 35', 'The halved block fits');
drop trigger zz_test_slow on public.match_participants;
-- Ohne Last wächst der Block wieder (25 -> 50). C bleibt hier hängen, damit die Fortschrittszeile sichtbar bleibt.
create function pg_temp.keep_slow() returns trigger language plpgsql as $$
begin
  if old.id = tests.uid('slow') then raise exception 'sensitive-keep-detail'; end if;
  return old;
end $$;
create trigger zz_test_keep before delete on auth.users for each row execute function pg_temp.keep_slow();
create temp table slow3 as select private.run_account_deletion_job_v1() r
  from (select set_config('advantly.account_deletion_budget', '10s', true)) z;
select is(pg_temp.q($$select block_size || ' ' || (cardinality(pending_matches) - next_match + 1)
  from private.account_deletion_progress where profile_id = tests.uid('slow')$$), '50 0', 'A fast block grows back to 50');
drop trigger zz_test_keep on auth.users;

-- Review r2 P1-1: zwei große Löschungen ohne gemeinsame Matches. settle (jeder Löschweg außerhalb des Jobs) und A neben
-- laufendem B vergleichen die Listen per Hash-Join; vorher Nested Loop über das Kreuzprodukt, bei 10 000 × 10 000
-- rund 4 s unter lock_sync. Feste Grenze 2 s je Anweisung, gebraucht werden wenige 100 ms.
select pg_temp.drain();
select tests.create_user('huge1');
select tests.create_user('huge2');
-- Eigene Matches genügen für die Arbeitsliste. cloud_access_generation erhöhte G des Erstellers je Zeile, 10 000-mal
-- dieselbe Zeile in einer Transaktion (quadratisch, Minuten); für die Zeitprüfung unerheblich, daher beim Aufbau aus.
alter table public.matches disable trigger cloud_access_generation;
insert into public.matches(id, created_by, sport, discipline, format, started_at)
  select pg_temp.m(100000 + n), tests.uid(case when n <= 10000 then 'huge1' else 'huge2' end), 'tennis', 'singles', '{}', now()
    from generate_series(1, 20000) n;
alter table public.matches enable trigger cloud_access_generation;
select pg_temp.due('huge1', interval '16 days');
select pg_temp.job();
select pg_temp.job();
create function pg_temp.timed(p_sql text) returns text language plpgsql as $$
begin
  execute p_sql;
  return 'done';
exception when query_canceled then return 'canceled';
end $$;
set local statement_timeout = '2s';
create temp table huge_settle as select pg_temp.timed(format('select private.account_deletion_settle(%L)', tests.uid('huge2'))) r;
reset statement_timeout;
select is((select r from huge_settle), 'done', 'Out-of-band delete next to a large running deletion checks for shared matches in linear time');
select pg_temp.due('huge2', interval '15 days');
set local statement_timeout = '2s';
create temp table huge_a as select pg_temp.job() r;
reset statement_timeout;
select is((select r->>'status' from huge_a) || ' ' || (select status from public.profiles where id = tests.uid('huge2')),
  'succeeded deleting', 'A of a large account next to a large running deletion stays far below the statement limit');

-- Review r2 P3-3: Ein zurückgestelltes A zählt auf das Budget (Budget 0: der Aufruf endet danach) und wird erst wieder
-- gewählt, wenn huge1 das gemeinsame Match (Position 60 der Liste) abgeschlossen hat. huge1 steht bei 51.
select tests.create_user('waiter');
insert into public.match_participants(match_id, slot_id, team, position, display_name, profile_id, link_status)
  values (pg_temp.m(100060), 'w', 'B', 1, 'Waiter', tests.uid('waiter'), 'linked');
select pg_temp.due('waiter', interval '15 days');
select pg_temp.job();
select is((select status from public.profiles where id = tests.uid('waiter')) || ' ' || (select string_agg(next_match::text, ','
  order by started_at) from private.account_deletion_progress), 'deletion_requested 51,1', 'A deferred A counts against the budget');
-- Selbst ohne gemeinsames Match wartet das Konto bis zur Wartestelle: Der Job prüft es bis dahin nicht erneut.
delete from public.match_participants where match_id = pg_temp.m(100060) and slot_id = 'w';
select pg_temp.job();
select is((select status from public.profiles where id = tests.uid('waiter')) || ' ' || (select string_agg(next_match::text, ','
  order by started_at) from private.account_deletion_progress), 'deletion_requested 101,1',
  'A deferred A is not examined again before the running deletion has passed the shared match');
select pg_temp.job();
select is((select status from public.profiles where id = tests.uid('waiter')), 'deleting', 'Afterwards the deferred A begins');
select * from finish();
rollback;
