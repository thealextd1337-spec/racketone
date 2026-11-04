-- Plan 0.6.3 §3.6: Steht das Zielkonto mitten in der Löschung (Status `deleting`, nach Schritt A des gestaffelten Jobs),
-- scheitert assign_player sauber: stabiler Fehlercode (Client-Text „nicht (mehr) befreundet“), nichts verknüpft, keine
-- Benachrichtigung. Hält nur das heutige Verhalten fest, keine neue Fachlogik.
begin;
select plan(5);
select tests.world();
select tests.publish_consent_documents('kl-471');
select tests.accept_current_consents(id) from tests.users;

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

select is(pg_temp.as_user('alice', $$select tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
    jsonb_build_object('id', '47110000-0000-4000-8000-000000000001', 'kind', 'self', 'displayName', 'Ich'),
    jsonb_build_object('id', '47110000-0000-4000-8000-000000000002', 'kind', 'guest', 'displayName', 'Test1')),
  'matches', jsonb_build_array(tests.match('47010000-0000-4000-8000-000000000001', jsonb_build_array(
    tests.part('47110000-0000-4000-8000-000000000001', 'A', 1, 'Alice', null, '47110000-0000-4000-8000-000000000001'),
    tests.part('47110000-0000-4000-8000-000000000002', 'B', 1, 'Test1', null, '47110000-0000-4000-8000-000000000002')))))),
  'matches', 0)$$), 'created', 'Match von alice mit Gast Test1');

-- vera (Freundin von alice) steht nach Schritt A der Kontolöschung; die Freundschaftszeile besteht noch.
update public.profiles set status = 'deleting' where id = tests.uid('vera');
create temp table outbox_before as select count(*) n from ops.notification_outbox where profile_id = tests.uid('vera');

select is(pg_temp.as_user('alice', $$select public.assign_player('47110000-0000-4000-8000-000000000002', tests.uid('vera'))::text$$),
  'ERR P0001 assignee_must_be_friend', 'Zuordnung an ein Konto in Löschung scheitert mit stabilem Code');
select is((select profile_id from public.players where id = '47110000-0000-4000-8000-000000000002'), null::uuid,
  'Gast bleibt unzugeordnet');
select is_empty($$select 1 from public.match_participants where match_id = '47010000-0000-4000-8000-000000000001'
  and profile_id = tests.uid('vera')$$, 'Kein Platz mit veras Konto verknüpft');
select is((select count(*) from ops.notification_outbox where profile_id = tests.uid('vera')), (select n from outbox_before),
  'Keine Benachrichtigung an das Konto in Löschung');
select * from finish();
rollback;
