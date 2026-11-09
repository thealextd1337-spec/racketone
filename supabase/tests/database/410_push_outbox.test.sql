-- Plan Live PR 5: Push-Outbox – actor_id, Zusammenfassen, Token-RPC, claim/ack nur für service_role, Prüfung beim
-- Versand (Token, Blockierung, Sichtbarkeit, Freundschaft, Auslöser, Match-Arten nur für Matches der letzten 24 h),
-- Limit je Stunde, Aufbewahrung, Scheduler.
begin;
select plan(52);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

-- Rechte
select ok(has_function_privilege('service_role', 'public.claim_push_batch_v1(integer)', 'execute')
          and has_function_privilege('service_role', 'public.ack_push_batch_v1(jsonb)', 'execute')
          and not has_function_privilege('authenticated', 'public.claim_push_batch_v1(integer)', 'execute')
          and not has_function_privilege('authenticated', 'public.ack_push_batch_v1(jsonb)', 'execute')
          and not has_function_privilege('anon', 'public.claim_push_batch_v1(integer)', 'execute')
          and not has_function_privilege('anon', 'public.ack_push_batch_v1(jsonb)', 'execute'),
          'claim/ack nur für service_role');
select ok(has_function_privilege('authenticated', 'public.cloud_set_push_token_v1(text,text)', 'execute')
          and not has_function_privilege('anon', 'public.cloud_set_push_token_v1(text,text)', 'execute')
          and not has_function_privilege('service_role', 'public.cloud_set_push_token_v1(text,text)', 'execute'),
          'Token-RPC nur für angemeldete Geräte');
select ok(not exists (select 1 from unnest(array['authenticated', 'anon', 'service_role']) r
                      where has_function_privilege(r, 'private.schedule_push_dispatch_v1()', 'execute')
                         or has_function_privilege(r, 'private.purge_push_outbox()', 'execute')
                         or has_function_privilege(r, 'private.notify(uuid,text,jsonb,uuid)', 'execute') and r <> 'service_role'),
          'Scheduler, Bereinigung und notify nicht über die API');
select has_trigger('ops', 'notification_outbox', 'notification_outbox_dispatch', 'AFTER-INSERT-Trigger stößt den Versand an');

-- Token nur am gebundenen Gerät des Aufrufers
select tests.authenticate_as('alice');
select lives_ok($$ select public.cloud_set_push_token_v1('fcm', 'tok-shared-000000000001') $$, 'alice setzt ihr Token');
reset role;
select is((select push_token || '/' || push_provider from public.devices
           where id = (select device_id from tests.cloud_context where profile_id = tests.uid('alice'))),
          'tok-shared-000000000001/fcm', 'Token am gebundenen Gerät');
select is((select count(*)::int from public.devices where push_token is not null), 1, 'kein anderes Gerät betroffen');
select tests.authenticate_as('alice');
select throws_ok($$ select public.cloud_set_push_token_v1('gcm', 'tok-shared-000000000001') $$,
                 'P0001', 'invalid_push_token', 'unbekannter Anbieter');
select throws_ok($$ select public.cloud_set_push_token_v1('fcm', 'kurz') $$, 'P0001', 'invalid_push_token',
                 'ungültiges Token');
select tests.authenticate_as('bob');
select lives_ok($$ select public.cloud_set_push_token_v1('fcm', 'tok-shared-000000000001') $$,
                'bob meldet dasselbe Token (Kontowechsel auf demselben Handy)');
reset role;
select is((select string_agg(profile_id::text, ',') from public.devices where push_token = 'tok-shared-000000000001'),
          tests.uid('bob')::text, 'Token wandert, das alte Gerät verliert es');
select tests.authenticate_as('bob');
select lives_ok($$ select public.cloud_set_push_token_v1(null, null) $$, 'Abmelden leert das Token');
reset role;
select is((select count(*)::int from public.devices where push_token is not null), 0, 'kein Token mehr gesetzt');
select tests.authenticate_as_id(tests.uid('alice'));
select throws_ok($$ select public.cloud_set_push_token_v1('fcm', 'tok-alice-000000000002') $$, '42501',
                 'device_not_authorized', 'ohne Cloud-Bindung kein Token');

-- Testwelt: Token bei bob, carol, dave; vera ohne Token
reset role;
update public.devices set push_provider = 'fcm', push_token = 'tok-' || id::text
 where id in (tests.device('b1'), tests.device('c1'), tests.device('d1'));
select tests.authenticate_as('alice');
select lives_ok($$ select public.send_friend_request(tests.uid('dave')) $$, 'alice fragt dave an');
reset role;
select is((select actor_id from ops.notification_outbox where profile_id = tests.uid('dave') and kind = 'friend_request'),
          tests.uid('alice'), 'actor_id = Aufrufer');
select private.notify(tests.uid('dave'), 'friend_request', jsonb_build_object('profileId', tests.uid('alice')),
                      tests.uid('alice'));
select private.notify(tests.uid('dave'), 'friend_request', jsonb_build_object('profileId', tests.uid('bob')),
                      tests.uid('bob'));
select private.notify(tests.uid('alice'), 'friend_accepted', '{}'::jsonb, tests.uid('alice'));
select is((select array_agg(actor_id order by id) from ops.notification_outbox where kind like 'friend_%'),
          array[tests.uid('alice'), tests.uid('bob')],
          'gleiche Art/Empfänger/Auslöser binnen 10 min zusammengefasst, keine Nachricht an sich selbst');
select lives_ok($$ select private.notify(tests.uid('bob'), 'scorer_changed',
                    jsonb_build_object('matchId', gen_random_uuid()), tests.uid('alice')) $$, 'neue Art scorer_changed');
delete from ops.notification_outbox where kind = 'scorer_changed';
-- Gesendet: der nächste gleiche Eintrag ist eine neue Nachricht (Inhalt kann sich geändert haben)
update ops.notification_outbox set sent_at = now()
 where profile_id = tests.uid('dave') and kind = 'friend_request' and actor_id = tests.uid('alice');
select private.notify(tests.uid('dave'), 'friend_request', jsonb_build_object('profileId', tests.uid('alice')),
                      tests.uid('alice'));
select is((select array_agg(sent_at is null order by id) from ops.notification_outbox
           where profile_id = tests.uid('dave') and kind = 'friend_request' and actor_id = tests.uid('alice')),
          array[false, true], 'nach dem Senden nicht mehr zusammengefasst');
delete from ops.notification_outbox where sent_at is not null;

-- carol fragt dave an, dave blockiert carol (Anfrage bleibt für den Test bestehen)
select tests.authenticate_as('carol');
select lives_ok($$ select public.send_friend_request(tests.uid('dave')) $$, 'carol fragt dave an');
reset role;
insert into public.user_blocks (blocker_id, blocked_id) values (tests.uid('dave'), tests.uid('carol'));

-- alice zählt M1 mit bob; vera (ohne Token) und carol (später widerrufen) als Zuschauerinnen
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
  tests.match('a4100000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a4100000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4100000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('startedAt', now()))))), 'matches', 0),
  'created', 'Match mit bob angelegt (match_shared)');
-- Erstbackup: ein vor Tagen begonnenes Match meldet nichts
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
  tests.match('a4100000-0000-7000-8000-000000000002', jsonb_build_array(
      tests.part('a4100000-0000-7000-8000-0000000000f3', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4100000-0000-7000-8000-0000000000f4', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('startedAt', now() - interval '3 days'))))), 'matches', 0),
  'created', 'altes Match mit bob hochgeladen (Erstbackup)');
select lives_ok($$ select public.add_match_visitor('a4100000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'vera als Zuschauerin');
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a4100000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'carol als Zuschauerin');
select lives_ok($$ select public.revoke_match_visitor('a4100000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bob widerruft carol');
reset role;
-- Reine Outbox-Art ohne Push (seit 0.7.0 ist player_claimed eine Push-Art, siehe 510).
select private.notify(tests.uid('dave'), 'player_assignment_rejected', '{}'::jsonb, tests.uid('alice'));
insert into ops.notification_outbox (profile_id, kind, payload, actor_id)
  values (tests.uid('bob'), 'friend_accepted', '{}'::jsonb, tests.uid('bob'));

-- Claim: nur dave (Anfrage von alice) und bob (match_shared) sind zustellbar
set local role service_role;
select set_config('tests.claim', public.claim_push_batch_v1(100)::text, true);
select is((select jsonb_agg(e -> 'message' order by (e ->> 'id')::bigint)
           from jsonb_array_elements(current_setting('tests.claim')::jsonb) e),
          jsonb_build_array(
            jsonb_build_object('v', 1, 'kind', 'friend_request', 'profileId', tests.uid('alice')),
            jsonb_build_object('v', 1, 'kind', 'match_shared', 'matchId', 'a4100000-0000-7000-8000-000000000001',
                               'profileId', tests.uid('alice'))),
          'Claim liefert nur Zustellbares, Nachricht nur mit Art und IDs');
select is((select e -> 'targets' from jsonb_array_elements(current_setting('tests.claim')::jsonb) e
           where e -> 'message' ->> 'kind' = 'friend_request'),
          jsonb_build_array(jsonb_build_object('deviceId', tests.device('d1'), 'provider', 'fcm',
                                               'token', 'tok-' || tests.device('d1')::text)),
          'Ziele mit Gerät, Anbieter und Token');
reset role;
select is((select array_agg(kind || ':' || attempts order by id) from ops.notification_outbox),
          array['friend_request:1', 'match_shared:1', 'player_assignment_rejected:0'],
          'Unzustellbares gelöscht (fremde Anfrage, blockiert, ohne Token, widerrufen, an sich selbst), Rest geleast');
select ok(not exists (select 1 from ops.notification_outbox where payload ->> 'matchId' = 'a4100000-0000-7000-8000-000000000002'),
          'Erstbackup: match_shared für ein altes Match verworfen');
set local role service_role;
select is(public.claim_push_batch_v1(100), '[]'::jsonb, 'geleaste Einträge werden nicht erneut geliefert');
select throws_ok($$ select public.claim_push_batch_v1(0) $$, 'P0001', 'invalid_limit', 'Limit 1..500');

-- Ack: gesendet, Token ungültig
select lives_ok(format($$ select public.ack_push_batch_v1(%L) $$, jsonb_build_array(
  jsonb_build_object('id', (select (e ->> 'id')::bigint from jsonb_array_elements(current_setting('tests.claim')::jsonb) e
                            where e -> 'message' ->> 'kind' = 'friend_request'),
                     'token', 'tok-' || tests.device('d1')::text, 'result', 'sent'),
  jsonb_build_object('id', (select (e ->> 'id')::bigint from jsonb_array_elements(current_setting('tests.claim')::jsonb) e
                            where e -> 'message' ->> 'kind' = 'match_shared'),
                     'token', 'tok-' || tests.device('b1')::text, 'result', 'invalid_token'))), 'Ack angenommen');
select throws_ok($$ select public.ack_push_batch_v1('[{"id":1,"result":"maybe"}]') $$, 'P0001', 'invalid_request',
                 'unbekanntes Ergebnis abgelehnt');
reset role;
select ok((select sent_at is not null from ops.notification_outbox where kind = 'friend_request'), 'gesendet markiert');
select ok((select push_token is null and push_provider is null from public.devices where id = tests.device('b1')),
          'ungültiges Token geleert');
select ok((select sent_at is null and last_error = 'invalid_token' and claimed_until > now()
           from ops.notification_outbox where kind = 'match_shared'), 'nicht gesendet, erneuter Versuch nach Wartezeit');
update ops.notification_outbox set claimed_until = now() - interval '1 second' where kind = 'match_shared';
set local role service_role;
select is(public.claim_push_batch_v1(100), '[]'::jsonb, 'erneuter Versuch ohne Token: nichts zu senden');
reset role;
select ok(not exists (select 1 from ops.notification_outbox where kind = 'match_shared'), 'Eintrag ohne Token gelöscht');

-- Fehler mit Rückoff, Rolle in der Nachricht, nach 5 Versuchen Schluss
update public.devices set push_provider = 'fcm', push_token = 'tok-' || id::text where id = tests.device('b1');
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a4100000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                'alice bittet bob zu zählen');
set local role service_role;
select set_config('tests.claim', public.claim_push_batch_v1(100)::text, true);
select is(current_setting('tests.claim')::jsonb -> 0 -> 'message',
          jsonb_build_object('v', 1, 'kind', 'scorer_invited', 'matchId', 'a4100000-0000-7000-8000-000000000001',
                             'profileId', tests.uid('alice'), 'role', 'scorer'), 'Zähl-Bitte mit Rolle');
select lives_ok(format($$ select public.ack_push_batch_v1(%L) $$, jsonb_build_array(jsonb_build_object(
  'id', (current_setting('tests.claim')::jsonb -> 0 ->> 'id')::bigint, 'token', 'tok-' || tests.device('b1')::text,
  'result', 'error', 'error', 'fcm 503'))), 'Fehler quittiert');
reset role;
select ok((select last_error = 'fcm 503' and attempts = 1 and claimed_until > now()
           from ops.notification_outbox where kind = 'scorer_invited'), 'Fehler vermerkt, Rückoff');
update ops.notification_outbox set attempts = 5, claimed_until = null where kind = 'scorer_invited';
set local role service_role;
select is(public.claim_push_batch_v1(100), '[]'::jsonb, 'nach 5 Versuchen keine Lieferung mehr');

-- Höchstens 30 Push je Empfänger und Stunde
reset role;
insert into ops.notification_outbox (profile_id, kind, payload, actor_id, created_at, sent_at)
  select tests.uid('bob'), 'friend_accepted', '{}'::jsonb, tests.uid('alice'), now() - interval '30 minutes',
         now() - interval '30 minutes'
  from generate_series(1, 30);
insert into ops.notification_outbox (profile_id, kind, payload, actor_id)
  values (tests.uid('bob'), 'match_shared', jsonb_build_object('matchId', 'a4100000-0000-7000-8000-000000000001'),
          tests.uid('alice'));
set local role service_role;
select is(public.claim_push_batch_v1(100), '[]'::jsonb, '31. Push binnen einer Stunde fällt weg');
reset role;
delete from ops.notification_outbox where id = (select min(id) from ops.notification_outbox where sent_at is not null
                                                and profile_id = tests.uid('bob'));
insert into ops.notification_outbox (profile_id, kind, payload, actor_id)
  values (tests.uid('bob'), 'match_shared', jsonb_build_object('matchId', 'a4100000-0000-7000-8000-000000000001'),
          tests.uid('alice'));
set local role service_role;
select is(jsonb_array_length(public.claim_push_batch_v1(100)), 1, '30. Push binnen einer Stunde geht raus');

-- Aufbewahrung und inaktive Token
reset role;
update ops.notification_outbox set created_at = now() - interval '8 days' where kind = 'friend_request';
update public.devices set last_seen_at = now() - interval '181 days' where id = tests.device('d1');
update public.devices set last_seen_at = now() - interval '179 days' where id = tests.device('c1');
select lives_ok($$ select private.purge_push_outbox() $$, 'Bereinigung läuft');
select is((select array_agg(distinct kind order by kind) from ops.notification_outbox),
          array['friend_accepted', 'match_shared', 'player_assignment_rejected'],
          'nach 7 Tagen und nach 5 Versuchen gelöscht, Rest bleibt');
select is((select array_agg(id order by id) from public.devices where push_token is not null),
          array[tests.device('b1'), tests.device('c1')], 'Token ohne Aktivität seit 180 Tagen geleert');

-- Scheduler: nichts offen → kein Aufruf; offen ohne Vault-Secret/pg_net → sichtbarer Fehler
delete from ops.notification_outbox where sent_at is null;
select is(private.schedule_push_dispatch_v1(), null, 'nichts offen: kein Anstoß');
select private.notify(tests.uid('bob'), 'friend_request', jsonb_build_object('profileId', tests.uid('vera')),
                      tests.uid('vera'));
select is((select count(*)::int from ops.notification_outbox where sent_at is null), 1,
          'Trigger ohne Setup bricht die Transaktion nicht ab');
select throws_ok($$ select private.schedule_push_dispatch_v1() $$, 'P0001', 'push_scheduler_unavailable',
                 'ohne Vault-Secret/pg_net: push_scheduler_unavailable');

create function pg_temp.push_cron() returns boolean language plpgsql as $$
declare v_ok boolean;
begin
  execute $q$select count(*) = 2 and bool_and(active) from cron.job where
    (jobname = 'advantly-push-dispatch-v1' and schedule = '* * * * *'
     and command = 'select private.schedule_push_dispatch_v1();')
    or (jobname = 'advantly-push-cleanup-v1' and schedule = '53 * * * *' and command = 'select private.purge_push_outbox();')$q$
    into v_ok;
  return v_ok;
end;
$$;
select case when exists (select 1 from pg_extension where extname = 'pg_cron')
  then ok(pg_temp.push_cron(), 'Cronjobs für Wiederholung (jede Minute) und Bereinigung (stündlich), ohne Secret')
  else skip('pg_cron fehlt: Planung im Supabase-Stack nachweisen') end;

select * from finish();
rollback;
