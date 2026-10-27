-- P2-A: effektive Spaltenrechte (inkl. geerbter Tabellen-Grants) und echte Client-Schreibproben.
begin;
select plan(22);
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

-- Vollständige Schreibmatrix, damit zusätzliche Spalten nicht unbemerkt schreibbar werden.
create temporary table expected_writes (tbl text, col text, priv text);
insert into expected_writes values
 ('profiles', 'alias', 'UPDATE'), ('profiles', 'username', 'UPDATE'), ('profiles', 'discoverable', 'UPDATE'),
 ('profile_settings', 'share_matches_default', 'UPDATE'),
 ('profile_settings', 'locale', 'UPDATE');
insert into expected_writes
 select 'tournaments', c, 'INSERT' from unnest(array['id','director_id','name','sport','starts_on','ends_on','status']) c;
insert into expected_writes
 select 'tournaments', c, 'UPDATE' from unnest(array['name','sport','starts_on','ends_on','status']) c;
insert into expected_writes
 select 'tournament_courts', c, 'INSERT' from unnest(array['id','tournament_id','name','sort_order']) c;
insert into expected_writes values ('tournament_courts','name','UPDATE'), ('tournament_courts','sort_order','UPDATE');
select results_eq($q$
 select c.table_name::text collate "C", c.column_name::text collate "C", p.priv collate "C"
 from information_schema.columns c cross join (values ('INSERT'), ('UPDATE'), ('REFERENCES')) p(priv)
 where c.table_schema = 'public' and has_column_privilege('authenticated',
   format('public.%I',c.table_name), c.column_name, p.priv)
 order by 1,2,3
$q$, $$ select tbl collate "C", col collate "C", priv collate "C" from expected_writes order by 1,2,3 $$,
  'authenticated schreibt exakt die freigegebenen Spalten');
select is_empty($q$
 select c.table_name, c.column_name from information_schema.columns c
 where c.table_schema = 'public' and (has_column_privilege('anon', format('public.%I',c.table_name),c.column_name,'INSERT')
   or has_column_privilege('anon', format('public.%I',c.table_name),c.column_name,'UPDATE')
   or has_column_privilege('anon', format('public.%I',c.table_name),c.column_name,'REFERENCES'))
$q$, 'anon hat keinerlei Spaltenschreibrechte');
select is_empty($q$
 select c.table_name, c.column_name from information_schema.columns c
 where c.table_schema = 'public' and c.table_name <> 'country_age_rules'
   and has_column_privilege('anon', format('public.%I',c.table_name),c.column_name,'SELECT')
$q$, 'anon liest nur die Länderregeln');
select is_empty($q$
 select c.table_name, c.column_name from information_schema.columns c
 where c.table_schema = 'public' and has_column_privilege('authenticated',format('public.%I',c.table_name),c.column_name,'SELECT')
 and (c.table_name not in ('country_age_rules','profiles','profile_settings','user_blocks','friendships','devices',
   'players','player_assignment_rejections','tournaments','tournament_courts','tournament_staff','matches',
   'match_participants','match_link_rejections','match_roles','match_visitors','match_events','invites','entitlements')
   or (c.table_name,c.column_name) in (('profiles','friend_code'),('invites','token_hash'),
      ('devices','push_token'),('devices','push_provider'),('match_roles','device_id')))
$q$, 'Keine internen Token oder unbekannten Tabellen lesbar');
select column_privs_are('public','profiles','friend_code','authenticated', array[]::text[], 'Freundescodes ohne Client-Grant');
select column_privs_are('public','invites','token_hash','authenticated', array[]::text[], 'Token-Hashes ohne Client-Grant');
select column_privs_are('public','devices','push_token','authenticated', array[]::text[], 'Push-Token ohne Client-Grant');
select column_privs_are('public','tournaments','access_epoch','authenticated', array['SELECT'], 'Kanalversion nur lesbar');

select tests.authenticate_as('alice');
select is(length(public.my_friend_code()), 8, 'Eigener Freundescode über RPC verfügbar');
select lives_ok($$ select alias, username from public.profiles where id = tests.uid('bob') $$, 'Öffentliche Profilfelder eines Freundes weiterhin lesbar');
select throws_ok($$ select friend_code from public.profiles where id = tests.uid('bob') $$, '42501', null, 'Fremden Freundescode nicht auslesen');
select throws_ok($$ select token_hash from public.invites $$, '42501', null, 'Keine Token-Hashes direkt auslesen');
select throws_ok($$ select push_token from public.devices $$, '42501', null, 'Keine Push-Token direkt auslesen');
select throws_ok($$ update public.profile_settings set visibility_epoch = 0 where profile_id = tests.uid('alice') $$,
                 '42501', null, 'Sichtbarkeitsepoche nicht clientseitig zurücksetzen');
select throws_ok($$ update public.profiles set friend_code = '00000000' where id = tests.uid('alice') $$,
                 '42501', null, 'Freundescode nicht manipulierbar');
select throws_ok($$ insert into public.tournaments (director_id,name,sport,access_epoch)
 values(tests.uid('alice'),'Fake','tennis',99) $$, '42501', null, 'Keine Kanalversion beim INSERT einschleusen');
select lives_ok($$ insert into public.tournaments (id,director_id,name,sport)
 values('fa000000-0000-7000-8000-000000000001',tests.uid('alice'),'Cup','tennis') $$, 'Fachlicher Turnier-INSERT funktioniert');
select throws_ok($$ update public.tournaments set access_epoch = 0 where id = 'fa000000-0000-7000-8000-000000000001' $$,
                 '42501', null, 'Keine Kanalversion zurücksetzen');
select throws_ok($$ update public.tournaments set director_id = tests.uid('bob') where id = 'fa000000-0000-7000-8000-000000000001' $$,
                 '42501', null, 'Turnierleitung ist unveränderlich');
select lives_ok($$ update public.tournaments set name = 'Updated Cup' where id = 'fa000000-0000-7000-8000-000000000001' $$,
                'Fachliches Turnier-UPDATE funktioniert');
select lives_ok($$ update public.profiles set alias = 'New Alice' where id = tests.uid('alice') $$, 'Alias-UPDATE bleibt erlaubt');
reset role;
select tests.authenticate_as('bob');
select is(public.my_friend_code(), (public.export_my_data() -> 'profile' ->> 'friendCode'), 'RPC liefert ausschließlich eigenen Code');
reset role;
select * from finish();
rollback;
