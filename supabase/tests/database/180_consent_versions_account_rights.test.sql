-- K1-Kontorechte: ausschließlich eigene Profile, keine implizite Zustimmung durch Recovery.
begin;
select plan(24);
select tests.create_user('rights_new',false);
select tests.create_user('rights_active');
select tests.create_user('rights_other');
select set_config('tests.rights_new',tests.uid('rights_new')::text,true);
select throws_ok($$update public.profiles set status='active' where id=tests.uid('rights_new')$$,
  '23514',null,'Aktive Profile bleiben aliaspflichtig');
select tests.authenticate_as('rights_new');
select lives_ok($$select public.request_account_deletion()$$,'Onboarding ohne Alias kann Löschung beantragen');
select throws_ok($$select * from private.consent_deletion_origins$$,'42501',null,'Ursprungsmarker nicht direkt lesbar');
select throws_ok($$update private.consent_deletion_origins set previous_status='active'$$,'42501',null,'Ursprungsmarker nicht manipulierbar');
reset role;
select is((select previous_status from private.consent_deletion_origins where profile_id=tests.uid('rights_new')),
  'onboarding','Tatsächlicher vorheriger Status gespeichert');
update public.profile_settings set deletion_requested_at=now()-interval '1 day' where profile_id=tests.uid('rights_new');
select tests.authenticate_as('rights_new');
select lives_ok($$select public.request_account_deletion()$$,'Doppelter Antrag idempotent');
reset role;
select is((select deletion_requested_at from public.profile_settings where profile_id=tests.uid('rights_new')),
  now()-interval '1 day','Wiederholung verlängert die Karenz nicht');
select tests.authenticate_as('rights_other');
select throws_ok($$select public.cancel_account_deletion()$$,'P0001','no_deletion_pending','Fremder Marker hilft anderem Konto nicht');
reset role;
select is((select status from public.profiles where id=tests.uid('rights_new')),'deletion_requested','Fremder Versuch ändert nichts');
delete from private.consent_deletion_origins where profile_id=tests.uid('rights_new');
select tests.authenticate_as('rights_new');
select throws_ok($$select public.cancel_account_deletion()$$,'P0001','no_deletion_pending','Fehlender Marker gibt Konto nicht frei');
reset role;
insert into private.consent_deletion_origins values(tests.uid('rights_new'),'active');
select tests.authenticate_as('rights_new');
select throws_ok($$select public.cancel_account_deletion()$$,'23514',null,'Fehlerhafte Recovery rollt vollständig zurück');
reset role;
select ok(exists(select 1 from private.consent_deletion_origins where profile_id=tests.uid('rights_new'))
  and (select status from public.profiles where id=tests.uid('rights_new'))='deletion_requested',
  'Fehler erhält Antrag und Marker atomar');
update private.consent_deletion_origins set previous_status='onboarding' where profile_id=tests.uid('rights_new');
select tests.authenticate_as('rights_new');
select lives_ok($$select public.cancel_account_deletion()$$,'Eigener gültiger Marker erlaubt Recovery');
select is(public.my_auth_profile()->>'onboarded','false','Recovery stellt onboarding wieder her');
select is(public.my_auth_profile()->>'consentStatus','UNAVAILABLE','Recovery erteilt keine Zustimmung');
reset role;
select is((select count(*)::int from private.consent_deletion_origins where profile_id=tests.uid('rights_new')),0,'Marker nach Recovery entfernt');
select tests.authenticate_as('rights_active');
select public.request_account_deletion();
select public.cancel_account_deletion();
select is(public.my_auth_profile()->>'onboarded','true','Vorher aktives Konto kehrt zu active zurück');
select is(public.my_auth_profile()->>'consentStatus','UNAVAILABLE','Auch active-Recovery bestätigt nichts');
select public.request_account_deletion();
reset role;
update public.profile_settings set deletion_requested_at=now()-interval '14 days' where profile_id=tests.uid('rights_active');
select tests.authenticate_as('rights_active');
select throws_ok($$select public.cancel_account_deletion()$$,'P0001','no_deletion_pending','Karenzgrenze erlaubt keine verspätete Rücknahme');
select public.request_account_deletion();
reset role;
select is((select deletion_requested_at from public.profile_settings where profile_id=tests.uid('rights_active')),
  now()-interval '14 days','Wiederholung nach Ablauf öffnet kein neues Fenster');
reset role;
select private.purge_account(tests.uid('rights_active'));
select is((select count(*)::int from private.consent_deletion_origins where profile_id=tests.uid('rights_active')),0,'Profil-Lifecycle entfernt privaten Marker');
select tests.authenticate_as('rights_new');
select public.request_account_deletion();
reset role;
update public.profile_settings set deletion_requested_at=null where profile_id=tests.uid('rights_new');
select tests.authenticate_as('rights_new');
select throws_ok($$select public.cancel_account_deletion()$$,'P0001','no_deletion_pending','Ungültiger Löschzustand wird nicht entsperrt');
reset role;
select ok(not private.has_current_consents(tests.uid('rights_new')),'Kontorechte verändern Consent nicht');
select ok(not has_table_privilege('anon','private.consent_deletion_origins','SELECT'),'Auch anon liest keinen Marker');
select * from finish();
rollback;
