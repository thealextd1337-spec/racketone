-- K3: feste Frist, deterministischer 100er-Batch, atomarer Fehler und wahrer Betriebsnachweis.
begin;
set local timezone = 'UTC';
select plan(29);
delete from private.account_deletion_job_state;
select is(public.account_deletion_job_status_v1()->'lastCommittedRun', '{"status":"unknown"}'::jsonb,
  'Ohne ausgeführten Lauf ausdrücklich unbekannt');
select tests.create_user('k3_due_before');
select tests.create_user('k3_due_exact');
select tests.create_user('k3_due_after');
select tests.create_user('k3_not_due');
select tests.create_user('k3_no_time');
select tests.create_user('k3_still_active');
select tests.create_user('k3_still_onboarding',false);
update public.profiles set status='deletion_requested' where id in
  (select id from tests.users where identifier in ('k3_due_before','k3_due_exact','k3_due_after','k3_not_due','k3_no_time'));
update public.profile_settings set deletion_requested_at=now()-interval '15 days'
  where profile_id in (tests.uid('k3_due_after'),tests.uid('k3_still_active'),tests.uid('k3_still_onboarding'));
update public.profile_settings set deletion_requested_at=now()-interval '14 days' where profile_id=tests.uid('k3_due_exact');
update public.profile_settings set deletion_requested_at=now()-interval '14 days'+interval '1 minute' where profile_id=tests.uid('k3_due_before');
update public.profile_settings set deletion_requested_at=now() where profile_id=tests.uid('k3_not_due');
set local role service_role;
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int, 2, 'An und nach 14 Tagen fällig');
select is(public.account_deletion_job_status_v1()->'lastCommittedRun'->>'status','succeeded','Tatsächlicher Lauf abgeschlossen');
select is((public.account_deletion_job_status_v1()->'lastCommittedRun'->>'deletedCount')::int,2,'Tatsächlich gelöschte Anzahl');
select ok((public.account_deletion_job_status_v1()->'lastCommittedRun'->>'startedAt')::timestamptz
  <= (public.account_deletion_job_status_v1()->'lastCommittedRun'->>'completedAt')::timestamptz,'Echte Laufzeiten');
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int, 0, 'Wiederholung löscht nichts doppelt');
reset role;
select is((select count(*)::int from auth.users where id in (tests.uid('k3_due_exact'),tests.uid('k3_due_after'))),0,'Auth-Kaskade ausgeführt');
select is((select count(*)::int from public.profiles where id in
  (tests.uid('k3_due_before'),tests.uid('k3_not_due'),tests.uid('k3_no_time'),tests.uid('k3_still_active'),tests.uid('k3_still_onboarding'))),5,
  'Nichtfällige, active, onboarding und fehlender Zeitpunkt bleiben');

do $$ begin for i in 1..103 loop perform tests.create_user('k3_batch_' || i); end loop; end $$;
update public.profiles set status='deletion_requested' where id in (select id from tests.users where identifier like 'k3_batch_%');
update public.profile_settings set deletion_requested_at=now()-interval '16 days'
  where profile_id in (select id from tests.users where identifier like 'k3_batch_%');
-- Zwei frühere Fristen beweisen Fristsortierung zusätzlich zur ID bei gleichen Fristen.
update public.profile_settings set deletion_requested_at=now()-interval '17 days'
  where profile_id in (select id from tests.users where identifier in ('k3_batch_1','k3_batch_2'));
create temp table expected_remaining as select p.id from public.profiles p join public.profile_settings s on s.profile_id=p.id
  where p.status='deletion_requested' and s.deletion_requested_at<=now()-interval '14 days'
  order by s.deletion_requested_at,p.id offset 100;
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int,100,'Mehr als 100 begrenzt');
select results_eq($$select p.id from public.profiles p join public.profile_settings s on s.profile_id=p.id
    where p.status='deletion_requested' and s.deletion_requested_at<=now()-interval '14 days' order by p.id$$,
  $$select id from expected_remaining order by id$$,'Frist und ID bestimmen die Auswahl');
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int,3,'Restlicher Batch');
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int,0,'Leerer Batch idempotent');

select tests.create_user('k3_atomic_first');
select tests.create_user('k3_atomic_second');
update public.profiles set status='deletion_requested' where id in (tests.uid('k3_atomic_first'),tests.uid('k3_atomic_second'));
update public.profile_settings set deletion_requested_at=now()-interval '20 days' where profile_id=tests.uid('k3_atomic_first');
update public.profile_settings set deletion_requested_at=now()-interval '19 days' where profile_id=tests.uid('k3_atomic_second');
create function pg_temp.reject_k3_delete() returns trigger language plpgsql as $$
begin
  if old.id=tests.uid('k3_atomic_second') then raise exception 'sensitive-test-detail'; end if;
  return old;
end $$;
create trigger k3_test_reject before delete on auth.users for each row execute function pg_temp.reject_k3_delete();
select is(private.run_account_deletion_job_v1()->>'status','failed','Fehlschlag wird nicht als Erfolg ausgegeben');
select is(public.account_deletion_job_status_v1()->'lastCommittedRun'->>'errorCode','account_deletion_failed','Fester Fehlercode gespeichert');
select is((public.account_deletion_job_status_v1()->'lastCommittedRun'->>'deletedCount')::int,1,'Nur die abgeschlossene Löschung zählt');
select is((select array_agg(id) from auth.users where id in (tests.uid('k3_atomic_first'),tests.uid('k3_atomic_second'))),
  array[tests.uid('k3_atomic_second')],'Abgeschlossene Löschung bleibt, nur die fehlgeschlagene rollt zurück');
select ok(public.account_deletion_job_status_v1()::text not like '%sensitive-test-detail%','Kein Rawexceptiontext');
drop trigger k3_test_reject on auth.users;
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int,1,'Retry nach Fehler ausführbar');
select is(public.account_deletion_job_status_v1()->'lastCommittedRun'->>'status','succeeded','Neuer tatsächlicher Erfolg ersetzt Fehler');
select results_eq($$select column_name::text collate "C" from information_schema.columns
    where table_schema='private' and table_name='account_deletion_job_state' order by column_name$$,
  $$values ('completed_at'::text collate "C"),('deleted_count'),('error_code'),('singleton'),('started_at'),('status')$$,
  'Zustand enthält nur Aggregat und Laufzeiten');
select ok(not has_table_privilege('service_role','private.account_deletion_job_state','UPDATE'),'Betriebsnachweis nur über feste Funktionen');
select ok((select proconfig @> array['lock_timeout=5s','statement_timeout=60s','search_path=""']
  from pg_proc where oid='private.run_account_deletion_job_v1()'::regprocedure),'Job begrenzt Sperr- und Anweisungszeit');
select is((public.account_deletion_job_status_v1()->>'contractVersion')::int,1,'Betriebsstatus versioniert');
select ok((select proconfig @> array['TimeZone=UTC'] from pg_proc
  where oid='public.cancel_account_deletion()'::regprocedure),'Rücknahme verwendet dieselbe UTC-Frist');
set local timezone='Europe/Paris';
select tests.authenticate_as('k3_due_before');
select is((public.account_deletion_status_v1()->>'earliestPurgeAt')::timestamptz,
  (public.account_deletion_status_v1()->>'requestedAt')::timestamptz+interval '336 hours',
  '14 Tage bleiben auch bei abweichender Aufruferzeitzone 336 Stunden');
reset role;
select ok((select prosecdef and proconfig @> array['search_path=""','TimeZone=UTC']
  from pg_proc where oid='public.cancel_account_deletion()'::regprocedure),'Recovery bleibt SECURITY DEFINER mit leerem search_path und UTC');
select is((select proowner from pg_proc where oid='public.cancel_account_deletion()'::regprocedure),
  (select proowner from pg_proc where oid='public.request_account_deletion()'::regprocedure),'Recovery-Owner unverändert');
select is((select proacl::text from pg_proc where oid='public.cancel_account_deletion()'::regprocedure),
  (select proacl::text from pg_proc where oid='public.request_account_deletion()'::regprocedure),'Bestehende Konto-RPC-ACL unverändert');
select ok((select strpos(prosrc, 's.deletion_requested_at > clock_timestamp() - interval ''14 days''')>0
  from pg_proc where oid='public.cancel_account_deletion()'::regprocedure),'Einziger fachlicher Vergleich verwendet frische Serveruhr');
select * from finish();
rollback;
