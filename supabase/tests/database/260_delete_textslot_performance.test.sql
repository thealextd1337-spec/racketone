-- Kontolöschung mit vielen Textslot-Teilnehmern (01100): Zeitprobe unter den harten Budgets 10 s / 5 s.
-- Vorher prüfte 00700 private.redaction_uuid(slot_id) für jede Teilnehmerzeile (im Referenz-Join je Zeile und ID);
-- Textslots laufen dort in den Ausnahmepfad. Hier: 205 Self-IDs und 505 Events wie 210, dazu 50 000 fremde
-- Textslots (jede vierte mit lebendem Profil-FK) und einige UUID-Slots in allen PostgreSQL-Schreibweisen.
begin;
select plan(22);
select tests.create_user('tsp_del');
select tests.create_user('tsp_keep');

create function pg_temp.self(n integer) returns uuid language sql as $$
  select ('a6030000-0000-4000-b000-'||lpad(n::text,12,'0'))::uuid $$;
create function pg_temp.m(n integer) returns uuid language sql as $$
  select ('26000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid $$;

insert into public.players(id,owner_id,kind,display_name)
  select pg_temp.self(n),tests.uid('tsp_del'),'self','Self' from generate_series(1,205) n;
insert into public.matches(id,created_by,sport,discipline,format,started_at)
  select pg_temp.m(n),tests.uid('tsp_keep'),'tennis','doubles','{}',now() from generate_series(0,12500) n;
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  select gen_random_uuid(),pg_temp.m(0),'26090000-0000-4000-8000-000000000001',n,n,n,'PlayerAssigned',
    jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',pg_temp.self(1+(n%205))::text,'displayName','Identical'))
  from generate_series(1,505) n;
-- 50 000 Textslots in 12 500 fremden Doppeln; je Match ein lebender Profil-FK des Überlebenden.
insert into public.match_participants(match_id,slot_id,team,position,display_name,profile_id,link_status)
  select pg_temp.m(m),case when n%2=0 then 'slot-'||n else 'placeholder:'||m||'-'||n end,
    case when n<=2 then 'A' else 'B' end,1+(n%2),'P',
    case when n=1 then tests.uid('tsp_keep') end,case when n=1 then 'linked' end
  from generate_series(1,12500) m,generate_series(1,4) n;
-- UUID-Slots derselben Self-ID in allen Schreibweisen, ohne Profil-FK: alle redigiert, Textslot daneben nicht.
insert into public.match_participants(match_id,slot_id,team,position,display_name) values
 (pg_temp.m(0),pg_temp.self(7)::text,'A',1,'Lower'),
 (pg_temp.m(0),upper(pg_temp.self(7)::text),'A',2,'Upper'),
 (pg_temp.m(0),'{'||pg_temp.self(7)::text||'}','B',1,'Braces'),
 (pg_temp.m(0),replace(pg_temp.self(7)::text,'-',''),'B',2,'No hyphens');

select cmp_ok((select count(*) from public.match_participants where match_id<>pg_temp.m(0)
  and private.redaction_uuid(slot_id) is null),'>=',50000::bigint,'At least 50 000 text-slot participants');
select ok((select i.indisvalid and i.indisready and pg_get_expr(i.indpred,i.indrelid) like '%length(slot_id)%32%41%'
  from pg_index i where i.indexrelid='public.match_participants_redaction_slot_idx'::regclass),
  'Partial UUID-slot index is valid and covers only 32..41 characters');
-- Grenzen des Indexprädikats: 32 und 41 Zeichen sind gültige Eingaben, 31 und 42 nie. uuid_in trimmt keinen
-- Whitespace; täte es das, lägen gültige Slots mit Leerzeichen außerhalb des Prädikats.
select is(private.redaction_uuid(s) is not null,valid,'UUID input length bound: '||length(s)||' '||to_json(s))
  from (values (repeat('a',32),true),('{aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa}',true),(repeat('a',31),false),
    ('{aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa}a',false),(repeat('a',33),false),
    (' '||repeat('a',32),false),(repeat('a',32)||' ',false),(E'	'||repeat('a',32),false),
    ('{aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa} ',false)) v(s,valid);

create temp table before_matches as select id,redaction_epoch from public.matches;
analyze public.match_participants;
set local statement_timeout='10s';
set local lock_timeout='5s';
create temp table started as select clock_timestamp() t;
delete from auth.users where id=tests.uid('tsp_del');
select cmp_ok(clock_timestamp()-(select t from started),'<',interval '5 seconds',
  'Account deletion with 205 self IDs and 50 000 text slots stays far below the 10 s statement budget');
select diag('delete took '||round(extract(epoch from clock_timestamp()-(select t from started))*1000)||' ms (including this probe)');
reset statement_timeout;
reset lock_timeout;

select is((select count(*) from private.match_redacted_players where match_id=pg_temp.m(0)),205::bigint,'All 205 self IDs in the manifest');
select is((select array_agg(slot_id order by slot_id) from private.match_redacted_slots where match_id=pg_temp.m(0)),
  (select array_agg(s order by s) from unnest(array[pg_temp.self(7)::text,upper(pg_temp.self(7)::text),
    '{'||pg_temp.self(7)::text||'}',replace(pg_temp.self(7)::text,'-','')]) s),'Every UUID spelling of the slot is captured');
select is((select count(*) from public.match_participants where match_id=pg_temp.m(0) and deleted_profile),4::bigint,
  'Every UUID spelling of the slot is renamed');
select is_empty($$select 1 from private.match_redacted_slots where match_id<>pg_temp.m(0)$$,'Foreign text slots get no slot proof');
select is_empty($$select 1 from public.match_participants where match_id<>pg_temp.m(0) and deleted_profile$$,'Foreign text slots keep their names');
select is_empty($$select 1 from public.matches m join before_matches b using(id)
  where m.id<>pg_temp.m(0) and m.redaction_epoch<>b.redaction_epoch$$,'Foreign matches keep E');
select ok((select m.redaction_epoch>b.redaction_epoch from public.matches m join before_matches b using(id) where m.id=pg_temp.m(0)),
  'Affected match raises E');
select is((select count(*) from public.match_events where match_id=pg_temp.m(0) and redacted_at is not null),505::bigint,'All 505 events redacted');

-- Ersetzte Funktionen behalten Definer, leeren search_path, Owner, Volatilität und Rechte aus 00600/00700.
select ok(not exists(select 1 from pg_proc p where p.oid in ('private.capture_profile_redactions()'::regprocedure,
  'private.redact_linked_names()'::regprocedure)
  and (not p.prosecdef or p.proconfig is distinct from array['search_path=""'] or p.provolatile<>'v'
    or p.proowner<>'postgres'::regrole or has_function_privilege('authenticated',p.oid,'execute')
    or has_function_privilege('anon',p.oid,'execute') or has_function_privilege('service_role',p.oid,'execute'))),
  'Replaced trigger functions keep definer, empty search_path, owner, volatility and no client ACL');
select is((select array_agg(tgname::text order by tgname) from pg_trigger where tgrelid='public.profiles'::regclass
  and tgname in ('profiles_00_capture_redactions','profiles_01_redact_linked_names')),
  array['profiles_00_capture_redactions','profiles_01_redact_linked_names'],'Delete triggers unchanged');
select * from finish();
rollback;
