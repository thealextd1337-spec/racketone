-- Kontolöschung mit eigener Historie (20261022000100): Zeitprobe unter den harten Budgets 10 s / 5 s.
-- Vorher las redaction_other_profile_context je eigenem Match alle verknüpften Teilnehmerzeilen (generischer Plan
-- ohne Indexzugriff), visibility_epoch des gelöschten Profils stieg je eigener Zeile, und jedes gelöschte eigene
-- Match suchte previous_match_id ohne Index. Hier: 600 eigene Matches (Self-Zeile, Events, in 100 auch ein Gast),
-- davon 480 mit dem Freund (Übergabe) und 120 ohne weiteren Profil-FK (Löschung, je mit Nachfolger),
-- dazu 24 000 fremde Teilnehmerzeilen mit lebendem Profil-FK.
begin;
select plan(22);
select tests.create_user('oh_del');
select tests.create_user('oh_keep');
select tests.create_user('oh_k'||k) from generate_series(1,3) k;

create function pg_temp.m(n integer) returns uuid language sql as $$
  select ('27000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid $$;
create function pg_temp.p(n integer) returns uuid language sql as $$
  select ('a7040000-0000-4000-b000-'||lpad(n::text,12,'0'))::uuid $$;

insert into public.players(id,owner_id,kind,display_name) values
  (pg_temp.p(1),tests.uid('oh_del'),'self','Self'),(pg_temp.p(2),tests.uid('oh_del'),'guest','Gast'),
  (pg_temp.p(3),tests.uid('oh_keep'),'self','Keep');
-- 1..600 eigene Matches, 601..6600 fremde Matches des Freundes; 601..720 folgen auf die gelöschten 481..600.
insert into public.matches(id,created_by,sport,discipline,format,started_at)
  select pg_temp.m(n),case when n<=600 then tests.uid('oh_del') else tests.uid('oh_keep') end,'tennis','doubles','{}',now()
  from generate_series(1,6600) n;
update public.matches set previous_match_id=pg_temp.m(n-120) from generate_series(601,720) n where id=pg_temp.m(n);
insert into public.match_participants(match_id,slot_id,team,position,display_name,profile_id,link_status,player_id)
  select pg_temp.m(n),pg_temp.p(1)::text,'A',1,'Self',tests.uid('oh_del'),'self',pg_temp.p(1) from generate_series(1,600) n
  union all select pg_temp.m(n),'placeholder:'||n,'B',1,'Gast',null,null,pg_temp.p(2) from generate_series(1,100) n
  union all select pg_temp.m(n),pg_temp.p(3)::text,'A',2,'Keep',tests.uid('oh_keep'),'linked',pg_temp.p(3) from generate_series(1,480) n;
insert into public.match_participants(match_id,slot_id,team,position,display_name,profile_id,link_status)
  select pg_temp.m(m),'slot-'||k,case when k<=2 then 'A' else 'B' end,1+(k%2),'P',
    case when k=1 then tests.uid('oh_keep') else tests.uid('oh_k'||(k-1)) end,'linked'
  from generate_series(601,6600) m,generate_series(1,4) k;
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  select gen_random_uuid(),pg_temp.m(n),'27090000-0000-4000-8000-000000000001',k,k,k,'PlayerAssigned',
    jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',pg_temp.p(k)::text,'displayName','Name'))
  from generate_series(1,600) n,generate_series(1,2) k where k=1 or n<=100;

select is((select count(*) from public.match_participants where profile_id=tests.uid('oh_del')),600::bigint,'600 own self rows');
select cmp_ok((select count(*) from public.match_participants where match_id>pg_temp.m(600) and profile_id is not null),
  '>=',24000::bigint,'At least 24 000 foreign participant rows with a live profile FK');
select ok(coalesce((select i.indisvalid and i.indisready from pg_index i
  where i.indexrelid=to_regclass('public.matches_previous_match_idx')),false),'previous_match_id index is valid');

create temp table before_keep as select visibility_epoch from public.profile_settings where profile_id=tests.uid('oh_keep');
analyze public.match_participants;
analyze public.matches;
-- Gelesene Zeilen statt Zeit (unabhängig von der Runnergeschwindigkeit): Transaktionszähler je Tabelle.
create function pg_temp.reads(p_rel regclass) returns bigint language sql as $$
  select coalesce(seq_tup_read,0)+coalesce(idx_tup_fetch,0) from pg_stat_xact_user_tables where relid=p_rel $$;
-- Ursache gezielt: Der matchbezogene Helper liest nur das eine Match. Vorher je Aufruf alle 24 000 verknüpften Zeilen.
select is(private.redaction_other_profile_context(pg_temp.m(1),pg_temp.p(1),null),true,'Helper finds the live self row');
select is(private.redaction_other_profile_context(pg_temp.m(1),pg_temp.p(1),tests.uid('oh_del')),false,
  'Helper ignores the excluded profile');
create temp table helper_reads as select pg_temp.reads('public.match_participants') r;
create temp table helper_hits as select count(*) filter(where private.redaction_other_profile_context(
  pg_temp.m(601+(n%6000)),pg_temp.p(1),null)) c from generate_series(1,2000) n;
select ok((select c from helper_hits)=0 and pg_temp.reads('public.match_participants')-(select r from helper_reads)<20000,
  '2 000 matchwise helper calls read at most 10 participant rows each');

create temp table sizes as select (select count(*) from public.match_participants) participants,(select count(*) from public.matches) matches;
create temp table delete_reads as select pg_temp.reads('public.match_participants') participants,pg_temp.reads('public.matches') matches;
set local statement_timeout='10s';
set local lock_timeout='5s';
create temp table started as select clock_timestamp() t;
delete from auth.users where id=tests.uid('oh_del');
select cmp_ok(clock_timestamp()-(select t from started),'<',interval '5 seconds',
  'Account deletion with 600 own matches and 24 000 linked foreign rows stays far below the 10 s statement budget');
select diag('delete took '||round(extract(epoch from clock_timestamp()-(select t from started))*1000)||' ms (including this probe)');
reset statement_timeout;
reset lock_timeout;
select cmp_ok(pg_temp.reads('public.match_participants')-(select participants from delete_reads),'<',
  10*(select participants from sizes),'Deletion reads participants a few times in total, not once per own match');
select cmp_ok(pg_temp.reads('public.matches')-(select matches from delete_reads),'<',20*(select matches from sizes),
  'Deleting own matches does not scan all matches per deleted match');

select is((select count(*) from public.matches where id in (select pg_temp.m(n) from generate_series(1,480) n)
  and created_by=tests.uid('oh_keep')),480::bigint,'Matches with the friend are handed over to the friend');
select is_empty($$select 1 from public.matches where id in (select pg_temp.m(n) from generate_series(481,600) n)$$,
  'Own matches without another profile are deleted');
select is_empty($$select 1 from public.matches where id in (select pg_temp.m(n) from generate_series(601,720) n)
  and previous_match_id is not null$$,'Successors lose the link to deleted predecessors');
select is((select count(*) from public.match_participants where match_id in (select pg_temp.m(n) from generate_series(1,480) n)
  and slot_id=pg_temp.p(1)::text and deleted_profile and profile_id is null and player_id is null),480::bigint,
  'Own self rows are anonymized');
select is_empty($$select 1 from public.match_participants where player_id=any(array[pg_temp.p(1),pg_temp.p(2)])$$,
  'No participant keeps an own player');
select is((select count(*) from public.match_events where match_id in (select pg_temp.m(n) from generate_series(1,480) n)
  and redacted_at is not null),480::bigint,'Own self events in handed-over matches are redacted');
select ok((select visibility_epoch from public.profile_settings where profile_id=tests.uid('oh_keep'))
  >(select visibility_epoch from before_keep),'The friend still gets visibility bumps');
select is_empty($$select 1 from public.profile_settings where profile_id=tests.uid('oh_del')$$,'Own settings are gone');
-- Ein Profil in redacted_profile_identities (Löschung läuft) erhält keine Erhöhung mehr, alle anderen wie bisher.
create temp table before_k as select profile_id,visibility_epoch from public.profile_settings
  where profile_id in (tests.uid('oh_k1'),tests.uid('oh_k2'));
insert into private.redacted_profile_identities values(tests.uid('oh_k1'));
select private.bump_visibility(array[tests.uid('oh_k1'),tests.uid('oh_k2')]);
select is((select s.visibility_epoch from public.profile_settings s where s.profile_id=tests.uid('oh_k1')),
  (select visibility_epoch from before_k where profile_id=tests.uid('oh_k1')),'Profile under deletion gets no bump');
select is((select s.visibility_epoch from public.profile_settings s where s.profile_id=tests.uid('oh_k2')),
  (select visibility_epoch+1 from before_k where profile_id=tests.uid('oh_k2')),'Other profiles in the same call still get one bump');

-- Ersetzte Funktionen behalten Definer, leeren search_path, Owner, Volatilität und Rechte.
select ok(not exists(select 1 from pg_proc p where p.oid in ('private.redaction_other_profile_context(uuid,uuid,uuid)'::regprocedure,
  'private.bump_visibility(uuid[])'::regprocedure,'private.anonymize_profile()'::regprocedure,
  'private.run_account_deletion_job_v1()'::regprocedure)
  and (not p.prosecdef or not p.proconfig @> array['search_path=""'] or p.provolatile<>'v'
    or p.proowner<>'postgres'::regrole or has_function_privilege('authenticated',p.oid,'execute')
    or has_function_privilege('anon',p.oid,'execute'))),
  'Replaced functions keep definer, empty search_path, owner, volatility and no client ACL');
select ok(has_function_privilege('service_role','private.run_account_deletion_job_v1()','execute'),
  'The job stays executable for service_role');
select ok((select proconfig @> array['lock_timeout=5s','statement_timeout=60s','TimeZone=UTC']
  from pg_proc where oid='private.run_account_deletion_job_v1()'::regprocedure),'Job keeps its lock and statement limits');
select * from finish();
rollback;
