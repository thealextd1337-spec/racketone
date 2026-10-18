-- K3: neuer Job nutzt die bestehende Redaktion, Event-Deduplizierung und Support-Kaskade.
begin;
set local timezone = 'UTC';
select plan(19);
select tests.world();
select tests.publish_consent_documents('k3-lifecycle');
select tests.accept_current_consents(id) from tests.users;
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(jsonb_build_object('id','b1900000-0000-7000-8000-000000000001','kind','self','displayName','Ich')),
  'matches', jsonb_build_array(
    tests.match('a1900000-0000-7000-8000-000000000001',jsonb_build_array(
      tests.part('alice','A',1,'Alice',tests.uid('alice')),tests.part('bob','B',1,'Bob',tests.uid('bob')))),
    tests.match('a1900000-0000-7000-8000-000000000002',jsonb_build_array(
      tests.part('alice','A',1,'Alice',tests.uid('alice')),tests.part('guest','B',1,'Gast')))),
  'events', jsonb_build_array(
    tests.event('e1900000-0000-7000-8000-000000000001','a1900000-0000-7000-8000-000000000001',tests.device('a1'),1,'MatchCreated'),
    tests.event('e1900000-0000-7000-8000-000000000002','a1900000-0000-7000-8000-000000000001',tests.device('a1'),2,'PointWon','{"side":"A"}'),
    tests.event('e1900000-0000-7000-8000-000000000003','a1900000-0000-7000-8000-000000000001',tests.device('a1'),3,'PlayerAssigned',
      '{"slot":"alice","player":{"id":"b1900000-0000-7000-8000-000000000001","displayName":"Alice Klarname"},"assignedBy":"x"}')
  ))),'events',2),'accepted','Geteiltes und Gast-Match angelegt');
select public.submit_support('f1900000-0000-4000-8000-000000000001',repeat('a',64),'bug','Test','Text');
select tests.authenticate_as('bob');
select set_config('tests.k3_reupload',tests.event('e1900000-0000-7000-8000-000000000004','a1900000-0000-7000-8000-000000000003',
  tests.device('b1'),1,'PlayerAssigned',jsonb_build_object('slot','bob','player',jsonb_build_object('id',tests.uid('alice'),
    'displayName','Alice Klarname'),'assignedBy','x'))::text,true);
select public.sync_push(jsonb_build_object('matches',jsonb_build_array(
  tests.match('a1900000-0000-7000-8000-000000000003',jsonb_build_array(
    tests.part('bob','A',1,'Bob',tests.uid('bob')),tests.part('guest','B',1,'Gast')))),
  'events',jsonb_build_array(current_setting('tests.k3_reupload')::jsonb)));
select public.submit_support('f1900000-0000-4000-8000-000000000002',repeat('b',64),'feedback','Anderes Ticket','Text');
reset role;
insert into ops.support_messages(id,ticket_id,author_id,body,internal)
  values('f1900000-0000-4000-8000-000000000003','f1900000-0000-4000-8000-000000000001',tests.uid('bob'),'Antwort',false);
insert into ops.support_audit(actor_id,ticket_id,action,reason)
  values(tests.uid('alice'),'f1900000-0000-4000-8000-000000000001','user_reply','user_reply');
create temp table score_before as select id,payload from public.match_events where type='PointWon';
select tests.authenticate_as('alice');
select public.request_account_deletion();
select throws_ok('select public.sync_push(''{}'')','42501','profile_not_active','Antrag erweitert Schreibrechte nicht');
reset role;
update public.profile_settings set deletion_requested_at=now()-interval '14 days' where profile_id=tests.uid('alice');
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int,1,'Neuer Job löscht fälliges Konto');
select is_empty($$select 1 from auth.users where id=tests.uid('alice')$$,'Authkonto weg');
select is_empty($$select 1 from public.devices where profile_id=tests.uid('alice')$$,'Geräte weg');
select is_empty($$select 1 from auth.sessions where user_id=tests.uid('alice')$$,'Sitzungen über Auth-Kaskade weg');
select is_empty($$select 1 from public.matches where id='a1900000-0000-7000-8000-000000000002'$$,'Gast-only-Match weg');
select is_empty($$select 1 from ops.support_tickets where id='f1900000-0000-4000-8000-000000000001'$$,'Eigene Meldung weg');
select is_empty($$select 1 from ops.support_messages where ticket_id='f1900000-0000-4000-8000-000000000001'$$,'Ticketnachrichten weg');
select ok(exists(select 1 from ops.support_audit where action='user_reply' and actor_id is null and ticket_id is null),
  'Audit bleibt ohne gelöschtes Ziel und Akteur');
select ok(exists(select 1 from ops.support_tickets where id='f1900000-0000-4000-8000-000000000002' and owner_id=tests.uid('bob')),
  'Fremdes Ticket unberührt');
select is((select status from public.profiles where id=tests.uid('bob')),'active','Fremdes Konto unberührt');
select results_eq($$select e.id,e.payload from public.match_events e where e.type='PointWon' order by e.id$$,
  $$select id,payload from score_before order by id$$,'Score-Payload unverändert');
select tests.authenticate_as('bob');
select is((select created_by from public.matches where id='a1900000-0000-7000-8000-000000000001'),tests.uid('bob'),'Geteiltes Match übertragen');
select results_eq($$select display_name,deleted_profile,profile_id is null from public.match_participants
    where match_id='a1900000-0000-7000-8000-000000000001' and slot_id='alice'$$,
  $$values('Gelöschter Spieler'::text,true,true)$$,'Teilnehmer redigiert');
select is((select count(*)::int from public.match_events where type='PlayerAssigned' and redacted_at is not null
  and payload->'player'->>'displayName'='Gelöschter Spieler'),2,'Profil- und Self-ID-Payload redigiert');
select is((select count(*)::int from public.matches where id in ('a1900000-0000-7000-8000-000000000001','a1900000-0000-7000-8000-000000000003')
  and redaction_epoch>0),2,'Beide Redaktionsepochen erhöht');
select is(tests.result(public.sync_push(jsonb_build_object('events',jsonb_build_array(current_setting('tests.k3_reupload')::jsonb))),
  'events',0),'duplicate','Alte Payload bleibt Duplikat');
select ok(public.export_my_data()::text not like '%Alice Klarname%','Reupload belebt Klarnamen nicht wieder');
reset role;
select * from finish();
rollback;
