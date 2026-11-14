-- Plan W12 Paket 3: Live-Signale über privaten Realtime-Kanal live:<profile_uuid>, leerer Payload, Ereignis changed.
-- Beitritt nur eigenes Konto mit gebundener JWT-Sitzung; Signale an Mitspieler, Zähler-Personen und Zuschauer, nicht an
-- Ersteller, Blockierte, Ausgeblendete, Widerrufene oder Fremde; wer die Sicht verliert, bekommt ein letztes Signal.
begin;
select plan(35);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

-- Wie 170: effektive Supabase-Grants, damit die Negativfälle an der Policy scheitern, nicht am Grant.
grant select, insert on realtime.messages to anon, authenticated;
-- Ohne DELETE auf realtime.messages: bisherige Zeilen merken, danach nur neue zählen; Entdoppelung je Transaktion leeren.
create temporary table seen_messages(r text);
create function pg_temp.reset_signals() returns void language plpgsql as $$
begin
  delete from seen_messages;
  insert into seen_messages select m::text from realtime.messages m;
  perform set_config('advantly.live_signaled', '', true);
end;
$$;
create function pg_temp.new_messages() returns setof realtime.messages language sql as $$
  select m.* from realtime.messages m where m::text not in (select r from seen_messages);
$$;
-- Empfänger der Signale seit dem letzten Reset, als sortierte Kürzel.
create function pg_temp.signals() returns text language sql as $$
  select coalesce(string_agg(u.identifier, ',' order by u.identifier), '')
  from (select distinct topic from pg_temp.new_messages() where event = 'changed') m
  join tests.users u on m.topic = 'live:' || u.id::text;
$$;
select pg_temp.reset_signals();
create function pg_temp.live_update(p_match uuid) returns void language sql as $$
  update public.matches set live_state = coalesce(live_state, '{}'::jsonb) || jsonb_build_object('tick', clock_timestamp())
  where id = p_match;
$$;

select ok(exists(select 1 from pg_policies where schemaname = 'realtime' and tablename = 'messages'
                 and policyname = 'cloud_realtime_closed' and permissive = 'RESTRICTIVE' and cmd = 'ALL'
                 and roles = '{authenticated}' and with_check = 'false'),
          'Sperre für authenticated öffnet nur Lesen, Schreiben bleibt zu');
select ok(exists(select 1 from pg_policies where schemaname = 'realtime' and tablename = 'messages'
                 and policyname = 'cloud_realtime_closed_anon' and roles = '{anon}' and qual = 'false'
                 and with_check = 'false'), 'anon bleibt vollständig gesperrt');
select ok(not has_function_privilege('authenticated', 'private.send_live_signal(uuid[])', 'execute')
          and not has_function_privilege('anon', 'private.send_live_signal(uuid[])', 'execute'),
          'Clients können keine Signale auslösen');

-- alice zählt M1 (gegen bob, vera eingeladene Zählerin, carol Zuschauerin über bob); M2 ist beendet.
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
  tests.match('a5400000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a5400000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a5400000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('projection', jsonb_build_object('status', 'in_progress', 'eventCount', 0,
      'liveState', '{"sets":[[1,0]],"points":["15","0"],"serving":"A"}'::jsonb))),
  tests.match('a5400000-0000-7000-8000-000000000002', jsonb_build_array(
      tests.part('a5400000-0000-7000-8000-0000000000f3', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a5400000-0000-7000-8000-0000000000f4', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('projection', jsonb_build_object('status', 'finished', 'eventCount', 0, 'winner', 'A'))))))
  , 'matches', 0), 'created', 'laufendes Match angelegt');
select lives_ok($$ select public.invite_scorer('a5400000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'vera als Zählerin eingeladen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a5400000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bob benennt carol als Zuschauerin');
reset role;
select is(pg_temp.signals(), 'bob,carol,vera',
          'Anlegen, Einladen und Zuschauer-Freigabe melden genau die Betroffenen');

-- Beitritt: nur eigenes Thema, nur gebundene Sitzung, alte Themen bleiben zu.
select tests.authenticate_as('bob');
select ok(private.can_receive_realtime('live:' || tests.uid('bob')), 'bob tritt seinem Kanal bei');
select ok(not private.can_receive_realtime('live:' || tests.uid('alice')), 'fremdes Konto: kein Beitritt');
select ok(not private.can_receive_realtime('live:' || upper(tests.uid('bob')::text))
          and not private.can_receive_realtime('live:' || tests.uid('bob') || ':1')
          and not private.can_receive_realtime(null), 'nur exakte Themenform');
select ok(not private.can_receive_realtime('match-live:a5400000-0000-7000-8000-000000000001:' ||
            (select access_epoch from public.matches where id = 'a5400000-0000-7000-8000-000000000001'))
          and not private.can_receive_realtime('match:a5400000-0000-7000-8000-000000000001:' ||
            (select access_epoch from public.matches where id = 'a5400000-0000-7000-8000-000000000001')),
          'alte Match-Themen bleiben auch für Mitglieder geschlossen');
select set_config('realtime.topic', 'live:' || tests.uid('bob'), true);
select ok(exists(select 1 from realtime.messages where topic = 'live:' || tests.uid('bob')),
          'RLS: bob liest die Broadcasts seines Kanals');
select throws_ok($$ insert into realtime.messages(topic, extension, payload, event, private)
                    values ('live:' || tests.uid('bob'), 'broadcast', '{}', 'changed', true) $$,
                 '42501', null, 'Clients senden nie, auch nicht auf den eigenen Kanal');
select set_config('realtime.topic', 'live:' || tests.uid('alice'), true);
select is_empty($$ select 1 from realtime.messages $$, 'fremdes Thema: RLS liefert nichts');
select tests.authenticate_as_id(tests.uid('bob'));
select ok(not private.can_receive_realtime('live:' || tests.uid('bob')), 'JWT ohne session_id: kein Beitritt');
reset role;
insert into auth.sessions(id, user_id, created_at, updated_at)
  values ('a5400000-0000-4000-8000-0000000000aa', tests.uid('bob'), now(), now());
select tests.authenticate_session(tests.uid('bob'), 'a5400000-0000-4000-8000-0000000000aa');
select ok(not private.can_receive_realtime('live:' || tests.uid('bob')), 'echte, aber ungebundene Sitzung: kein Beitritt');
reset role;
update public.devices set revoked_at = now()
  where id = (select device_id from tests.cloud_context where profile_id = tests.uid('bob'));
select tests.authenticate_as('bob');
select ok(not private.can_receive_realtime('live:' || tests.uid('bob')), 'widerrufenes Gerät: kein Beitritt');
reset role;
update public.devices set revoked_at = null
  where id = (select device_id from tests.cloud_context where profile_id = tests.uid('bob'));
select tests.authenticate_as('dave');
select ok(private.can_receive_realtime('live:' || tests.uid('dave')), 'dave tritt vor dem Abmelden bei');
reset role;
delete from auth.sessions where user_id = tests.uid('dave');
select tests.authenticate_as('dave');
select ok(not private.can_receive_realtime('live:' || tests.uid('dave')), 'abgemeldete Sitzung: kein Beitritt');
select tests.authenticate_anon();
select set_config('realtime.topic', 'live:' || tests.uid('bob'), true);
select is_empty($$ select 1 from realtime.messages $$, 'anon liest nichts');
reset role;

-- Live-Stand: Mitglieder und Zuschauer, nie Ersteller oder Fremde, nur leere Nutzdaten, ein Signal je Transaktion.
select pg_temp.reset_signals();
select pg_temp.live_update('a5400000-0000-7000-8000-000000000001');
select pg_temp.live_update('a5400000-0000-7000-8000-000000000001');
select is(pg_temp.signals(), 'bob,carol,vera', 'Live-Stand meldet Mitspieler, Zählerin und Zuschauerin');
select is((select count(*)::int from pg_temp.new_messages()), 3, 'zwei Änderungen, ein Signal je Konto und Transaktion');
select ok((select bool_and(payload - 'id' = '{}'::jsonb and event = 'changed' and private and extension = 'broadcast')
           from pg_temp.new_messages()), 'nur leere Nutzdaten auf privaten Kanälen');
select is_empty($$ select 1 from pg_temp.new_messages() where topic !~ '^live:' $$, 'alte Themen bekommen weiter nichts');
select pg_temp.reset_signals();
select pg_temp.live_update('a5400000-0000-7000-8000-000000000002');
select is(pg_temp.signals(), '', 'beendetes Match meldet nichts');

-- Wer die Sicht verliert, bekommt ein letztes Signal, danach nichts mehr.
select pg_temp.reset_signals();
select tests.authenticate_as('bob');
select lives_ok($$ select public.revoke_match_visitor('a5400000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bob widerruft carol');
reset role;
-- revoke_match_visitor erhöht die Kanalversion (access_epoch), daher melden auch die übrigen Empfänger.
select is(pg_temp.signals(), 'bob,carol,vera', 'Widerruf meldet auch die Zuschauerin selbst');
select pg_temp.reset_signals();
select tests.authenticate_as('bob');
select lives_ok($$ select public.set_my_participation('a5400000-0000-7000-8000-000000000001', null, true) $$,
                'bob blendet M1 aus');
reset role;
select is(pg_temp.signals(), 'bob', 'Ausblenden meldet nur bob');
select pg_temp.reset_signals();
select pg_temp.live_update('a5400000-0000-7000-8000-000000000001');
select is(pg_temp.signals(), 'vera', 'danach weder Widerrufene noch Ausgeblendete');
select tests.authenticate_as('bob');
select lives_ok($$ select public.set_my_participation('a5400000-0000-7000-8000-000000000001', null, false) $$,
                'bob blendet M1 wieder ein');
reset role;
select pg_temp.reset_signals();
select tests.authenticate_as('bob');
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'bob blockiert die Erstellerin');
reset role;
select is(pg_temp.signals(), 'alice,bob', 'Blockieren meldet beide Seiten');
select pg_temp.reset_signals();
select pg_temp.live_update('a5400000-0000-7000-8000-000000000001');
select is(pg_temp.signals(), 'vera', 'blockierter Mitspieler bekommt keine Live-Signale');

-- Matchende meldet die verbliebenen Empfänger ein letztes Mal.
select pg_temp.reset_signals();
update public.matches set status = 'finished' where id = 'a5400000-0000-7000-8000-000000000001';
select is(pg_temp.signals(), 'vera', 'Matchende meldet die Zählerin');

select * from finish();
rollback;
