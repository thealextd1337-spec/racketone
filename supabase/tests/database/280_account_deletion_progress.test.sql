-- Löschjob kommt auch bei langen Konten voran (20261022000100, Review 0780489 P2-1; seit 20261022000200 gestaffelt)
-- und getilgte Profil-IDs werden nicht wieder angelegt (P3-1). Verzögerung über einen Test-Trigger NACH allen
-- Löschtriggern auf profiles, also in C: Bricht C ab, rollt nur C zurück; A und die Matchblöcke bleiben.
-- Maßstab 1:12: Joblimit 5 s statt 60 s. A 2,5 s und B 3 s liegen je allein darunter, zusammen darüber;
-- X braucht 8 s und liegt allein über dem Limit; C ohne Verzögerung.
begin;
select plan(20);
delete from private.account_deletion_job_state;
select tests.create_user(n) from unnest(array['dp_a','dp_b','dp_x','dp_c','dp_keep']) n;
update public.profiles set status='deletion_requested'
  where id in (tests.uid('dp_a'),tests.uid('dp_b'),tests.uid('dp_x'),tests.uid('dp_c'));
update public.profile_settings s set deletion_requested_at=now()-v.age
  from (values ('dp_a',interval '30 days'),('dp_b',interval '29 days'),('dp_x',interval '28 days'),('dp_c',interval '27 days')) v(n,age)
  where s.profile_id=tests.uid(v.n);
-- X spielt in einem Match des Freundes: nach jedem Abbruch muss es unverändert sein.
insert into public.matches(id,created_by,sport,discipline,format,started_at)
  values ('28000000-0000-4000-8000-000000000001',tests.uid('dp_keep'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,display_name,profile_id,link_status) values
  ('28000000-0000-4000-8000-000000000001','x','A',1,'Xaver',tests.uid('dp_x'),'linked'),
  ('28000000-0000-4000-8000-000000000001','k','B',1,'Keep',tests.uid('dp_keep'),'linked');

create temp table delays(id uuid primary key, seconds float8 not null);
insert into delays values (tests.uid('dp_a'),2.5),(tests.uid('dp_b'),3),(tests.uid('dp_x'),8);
create function pg_temp.delay_delete() returns trigger language plpgsql as $$
begin
  perform pg_sleep(d.seconds) from pg_temp.delays d where d.id=old.id;
  return old;
end $$;
create trigger profiles_zz_test_delay before delete on public.profiles for each row execute function pg_temp.delay_delete();
-- Liefert null, solange es keinen Fehlversuchszähler gibt (Gegenprobe gegen den alten Stand).
create function pg_temp.attempts(p_id uuid) returns integer language plpgsql as $$
declare v integer;
begin
  if to_regclass('private.account_deletion_failures') is null then return null; end if;
  execute 'select attempts from private.account_deletion_failures where profile_id=$1' into v using p_id;
  return coalesce(v,0);
end $$;
create function pg_temp.alive(p_names text[]) returns text[] language sql as $$
  select coalesce(array_agg(t.identifier order by t.identifier),'{}') from tests.users t
  where t.identifier=any(p_names) and exists(select 1 from auth.users u where u.id=t.id) $$;

set local statement_timeout='5s';
-- Gestaffelt (20261022000200): Ein Aufruf endet nach ~1 s Budget, die Verzögerung liegt in C (Profil-DELETE).
-- Lauf 1: A für alle vier, dann C von A (2,5 s) - danach ist das Budget verbraucht.
create temp table run1 as select private.run_account_deletion_job_v1() r;
select is((select (r->>'deletedCount')::int||' '||(r->>'status') from run1),'1 succeeded',
  'Run 1 stages all due accounts and finishes A');
select is(pg_temp.alive(array['dp_a','dp_b','dp_x','dp_c']),array['dp_b','dp_c','dp_x'],'Run 1 deleted A only');
select is((select array_agg(status order by status) from public.profiles
  where id in (tests.uid('dp_b'),tests.uid('dp_x'),tests.uid('dp_c'))),array['deleting','deleting','deleting'],
  'All due accounts are locked out after the first run');
-- Lauf 2: B (ältester Schritt) braucht 3 s.
create temp table run2 as select private.run_account_deletion_job_v1() r;
select is((select (r->>'deletedCount')::int from run2),1,'Run 2 deletes B');
select is(pg_temp.alive(array['dp_a','dp_b','dp_x','dp_c']),array['dp_c','dp_x'],'Run 2 deleted B');
-- Lauf 3: Matchblock von X, C von C, dann bricht C von X am Limit ab.
create temp table run3 as select private.run_account_deletion_job_v1() r;
select is((select (r->>'deletedCount')::int||' '||(r->>'status')||' '||(r->>'errorCode') from run3),'1 failed statement_canceled',
  'Run 3 keeps the finished deletion and reports the canceled one');
select is(pg_temp.alive(array['dp_a','dp_b','dp_x','dp_c']),array['dp_x'],'Only the overlong account remains');
select is(pg_temp.attempts(tests.uid('dp_x')),1,'X has one failed attempt');
select is((select array[p.status,mp.deleted_profile::text,mp.display_name,coalesce(mp.profile_id::text,'null')]
  from public.profiles p, public.match_participants mp
  where p.id=tests.uid('dp_x') and mp.match_id='28000000-0000-4000-8000-000000000001' and mp.slot_id='x'),
  array['deleting','true','Gelöschter Spieler','null'],'Canceled C keeps A and the finished batch');
select ok(exists(select 1 from public.profiles where id=tests.uid('dp_x'))
  and exists(select 1 from private.redacted_profile_identities where profile_id=tests.uid('dp_x')),
  'Canceled C leaves no half-deleted account');
reset statement_timeout;
select is((public.account_deletion_job_status_v1()->>'stalledAccountCount')::int,0,'No alarm below three attempts');
-- Läufe 4 und 5: zweiter und dritter Fehlversuch -> Alarm, nur als Anzahl.
set local statement_timeout='5s';
create temp table run4 as select private.run_account_deletion_job_v1() r;
reset statement_timeout;
select is(pg_temp.attempts(tests.uid('dp_x')),2,'X has two failed attempts');
set local statement_timeout='5s';
create temp table run5 as select private.run_account_deletion_job_v1() r;
reset statement_timeout;
select is(pg_temp.attempts(tests.uid('dp_x')),3,'X has three failed attempts');
select is((public.account_deletion_job_status_v1()->>'stalledAccountCount')::int,1,'Three failed attempts raise the alarm');
select ok(public.account_deletion_job_status_v1()::text not like '%'||tests.uid('dp_x')::text||'%',
  'Alarm carries no account reference');
-- Ohne Verzögerung gelingt X; Zähler und Alarm verschwinden mit dem Konto.
drop trigger profiles_zz_test_delay on public.profiles;
select is((private.run_account_deletion_job_v1()->>'deletedCount')::int,1,'X is deleted once it fits');
select is((public.account_deletion_job_status_v1()->>'stalledAccountCount')::int,0,'Alarm clears with the account');
select ok(case when to_regclass('private.account_deletion_failures') is null then false
  else not has_table_privilege('service_role','private.account_deletion_failures','SELECT')
    and not has_table_privilege('authenticated','private.account_deletion_failures','SELECT') end,
  'Failure counters are not client-readable');

-- P3-1: Eine getilgte Profil-ID wird nicht wieder angelegt, eine neue weiterhin.
select throws_ok(format($$insert into auth.users(id,email,raw_user_meta_data,raw_app_meta_data,aud,role,created_at,updated_at)
  values (%L,'dp_again@test.advantly.eu','{}','{}','authenticated','authenticated',now(),now())$$,tests.uid('dp_a')),
  'P0001','profile_id_retired','A retired profile ID cannot be created again');
select lives_ok($$select tests.create_user('dp_new')$$,'A fresh profile ID is still created');
select * from finish();
rollback;
