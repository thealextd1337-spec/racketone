-- P2-E: Ausblenden beendet das Teilen (Feed der Freunde) und share_matches_default ändert keine Generation.
begin;
select no_plan();
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

create function pg_temp.feed_ids(p_user text) returns text[] language plpgsql as $$
declare v_ids text[];
begin
  perform tests.authenticate_as(p_user);
  select coalesce(array_agg(m ->> 'id'), '{}') into v_ids
    from jsonb_array_elements(public.cloud_sync_pull_v1(0, 200) -> 'matches') m where m ->> 'access' = 'feed';
  reset role;
  return v_ids;
end;
$$;

-- M1: Alice (Erstellerin, teilt) mit Bob (verknüpft). Vera ist nur mit Alice befreundet → Feed.
insert into public.matches(id, created_by, sport, discipline, format, started_at, status)
  values ('35000000-0000-4000-8000-000000000001', tests.uid('alice'), 'tennis', 'singles', '{}', now(), 'finished');
insert into public.match_participants(match_id, slot_id, team, position, profile_id, link_status, display_name, share_with_friends)
  values ('35000000-0000-4000-8000-000000000001', 'alice', 'A', 1, tests.uid('alice'), 'self', 'Alice', true),
         ('35000000-0000-4000-8000-000000000001', 'bob', 'B', 1, tests.uid('bob'), 'linked', 'Bob', false);
select ok('35000000-0000-4000-8000-000000000001' = any (pg_temp.feed_ids('vera')), 'Vor dem Löschen: im Feed von Vera');

-- Löschweg des Erstellers mit verknüpftem Freund: nur ausblenden – und nicht mehr teilen.
select is(private.push_match(tests.uid('alice'), '{"id":"35000000-0000-4000-8000-000000000001","deleted":true}') ->> 'result',
  'hidden_only', 'Ersteller löscht geteiltes Match: hidden_only');
select results_eq($$ select hidden, share_with_friends from public.match_participants
                     where match_id = '35000000-0000-4000-8000-000000000001' and slot_id = 'alice' $$,
  $$ values (true, false) $$, 'hidden_only setzt hidden und beendet das Teilen');
select ok(not ('35000000-0000-4000-8000-000000000001' = any (pg_temp.feed_ids('vera'))), 'Nach hidden_only: nicht mehr im Feed');
select is((select deleted_at from public.matches where id = '35000000-0000-4000-8000-000000000001'), null::timestamptz,
  'Match bleibt für Bob erhalten');

-- M2: Bob teilt, Carol (Bobs Freundin) sieht es; Bob blendet aus wie der Client (teilen aus + ausblenden).
insert into public.matches(id, created_by, sport, discipline, format, started_at, status)
  values ('35000000-0000-4000-8000-000000000002', tests.uid('alice'), 'tennis', 'singles', '{}', now(), 'finished');
insert into public.match_participants(match_id, slot_id, team, position, profile_id, link_status, display_name, share_with_friends)
  values ('35000000-0000-4000-8000-000000000002', 'alice', 'A', 1, tests.uid('alice'), 'self', 'Alice', false),
         ('35000000-0000-4000-8000-000000000002', 'bob', 'B', 1, tests.uid('bob'), 'linked', 'Bob', true);
select ok('35000000-0000-4000-8000-000000000002' = any (pg_temp.feed_ids('carol')), 'Bobs Freigabe: im Feed von Carol');
select tests.authenticate_as('bob');
select lives_ok($$ select public.set_my_participation('35000000-0000-4000-8000-000000000002', false, true) $$,
  'Bob blendet aus und beendet das Teilen');
reset role;
select ok(not ('35000000-0000-4000-8000-000000000002' = any (pg_temp.feed_ids('carol'))), 'Ausgeblendet: nicht mehr im Feed');

-- share_matches_default ist keine Sichtbarkeit: Umschalten lässt G unverändert, der Trigger bleibt für andere Spalten.
select set_config('tests.g', (select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob'))::text, true);
update public.profile_settings set share_matches_default = true where profile_id = tests.uid('bob');
update public.profile_settings set share_matches_default = false where profile_id = tests.uid('bob');
select is((select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob')),
  current_setting('tests.g')::bigint, 'share_matches_default ändert die Sichtbarkeits-Generation nicht');
update public.profile_settings set age_confirmed_at = now() - interval '1 day' where profile_id = tests.uid('bob');
select ok((select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob'))
          > current_setting('tests.g')::bigint, 'Altersgate-Änderung erhöht G weiterhin');
select ok((select count(*) = 1 from pg_trigger where tgrelid = 'public.profile_settings'::regclass
    and tgname = 'cloud_access_generation' and tgenabled = 'O'), 'Generationstrigger auf profile_settings aktiv');
select * from finish();
rollback;
