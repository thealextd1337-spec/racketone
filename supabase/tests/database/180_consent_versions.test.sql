-- Nur transaktionale Testfassungen; keine fachliche Freigabe der verlinkten Texte.
begin;
select plan(38);
select tests.create_user('consent_alice');
select tests.create_user('consent_bob');
select tests.create_user('consent_new', false);
update public.profile_settings set age_confirmed_at = now(), country = 'AT', age_rule_min_age = 14
  where profile_id = tests.uid('consent_new');

select tests.authenticate_as('consent_alice');
select is(public.my_consent_v1('de')->>'status', 'UNAVAILABLE', 'Ohne Fachfreigabe sicher gesperrt');
select is(public.my_auth_profile()->>'consentStatus', 'UNAVAILABLE', 'Profil publiziert expliziten Zustand');
select throws_ok($$select public.record_consent_v1(public.my_consent_v1('de')->'documents',true)$$,
  'P0001','consent_unavailable','Lokale Versionsbehauptung kann keine Freigabe erzeugen');
select throws_ok($$select public.my_consent_v1('fr')$$,'P0001','consent_language_invalid','Keine unfreigegebene Sprache');
select lives_ok($$select public.export_my_data()$$,'Export ohne Zustimmung erreichbar');
select lives_ok($$select public.my_support_tickets()$$,'Support ohne Zustimmung erreichbar');
select throws_ok($$select public.record_consent_v1('[]',true)$$,'P0001','consent_documents_invalid','Beide Dokumente erforderlich');
select throws_ok($$select * from private.consent_history$$,'42501',null,'Kein direkter Zugriff auf Historie');

reset role;
update private.consent_versions set is_current = false;
insert into private.consent_versions(document_type,version,is_current) values ('TERMS','test-v1',true),('PRIVACY','test-v1',true);
insert into private.consent_translations(document_type,version,language,url)
  select document_type,version,l.language,'https://advantly.eu/' || case when l.language='de' then 'app-datenschutz.html' else 'en/app-privacy.html' end
  from private.consent_versions cross join (values('de'),('en')) l(language) where version='test-v1';
update private.consent_versions set approved_at=now() where version='test-v1';
select tests.authenticate_as('consent_new');
select throws_ok($$select public.finish_onboarding('New',null)$$,'42501','consent_required','Onboarding verlangt aktuelle Zustimmung');
select tests.authenticate_as('consent_alice');
select is(public.my_consent_v1('de')->>'status','REQUIRED','Freigegebene Version verlangt bewusste Bestätigung');
select is(public.record_consent_v1(public.my_consent_v1('de')->'documents',true)->>'status','CURRENT','Beide Fassungen bestätigt');
select set_config('tests.old_consent',(public.my_consent_v1('de')->'documents')::text,true);
select is(public.my_consent_v1('en')->>'status','CURRENT','Sprachwechsel erfindet keine neue fachliche Version');
select is(jsonb_array_length(public.export_my_data()->'consentHistory'),2,'Bestätigte Fassungen exportierbar');
select is(public.export_my_data()->'consentHistory'->0->>'language','de','Tatsächlich bestätigte Sprache erhalten');
select ok((public.export_my_data()->'consentHistory'->0->>'decidedAt')::timestamptz between now()-interval '1 minute' and clock_timestamp(),'Zeit kommt vom Server');
select lives_ok($$select public.record_consent_v1(public.my_consent_v1('de')->'documents',true)$$,'Doppelte Zustimmung idempotent');
select is(jsonb_array_length(public.export_my_data()->'consentHistory'),2,'Keine doppelte Historienzeile');
select tests.authenticate_as('consent_bob');
select is(public.my_consent_v1('de')->>'status','REQUIRED','Anderes Konto erbt keine Zustimmung');
select is(jsonb_array_length(public.export_my_data()->'consentHistory'),0,'Export enthält keine fremde Historie');

reset role;
select ok(private.has_current_consents(tests.uid('consent_alice')),'Serverhelfer bestätigt ausschließlich aktuelle Zustimmung');
select throws_ok($$update private.consent_translations set url='https://advantly.eu/changed.html' where version='test-v1'$$,
  'P0001','consent_version_immutable','Bestätigte Dokumentfassungen bleiben unverändert');
update private.consent_versions set is_current=false where document_type='TERMS';
insert into private.consent_versions(document_type,version,is_current) values('TERMS','test-v2',true);
insert into private.consent_translations(document_type,version,language,url)
  select 'TERMS','test-v2',language,'https://advantly.eu/' || case when language='de' then 'app-datenschutz.html' else 'en/app-privacy.html' end
  from (values('de'),('en')) l(language);
update private.consent_versions set approved_at=now() where version='test-v2';
select ok(not private.has_current_consents(tests.uid('consent_alice')),'Neue fachliche Version sperrt Cloudhelfer');
select tests.authenticate_as('consent_alice');
select throws_ok($$select public.record_consent_v1(current_setting('tests.old_consent')::jsonb,true)$$,
  'P0001','consent_version_changed','Alte geöffnete Fassung kann neue nicht bestätigen');
select is(public.record_consent_v1(public.my_consent_v1('en')->'documents',false)->>'status','DECLINED','Bewusste Ablehnung respektiert');
select is(jsonb_array_length(public.export_my_data()->'consentHistory'),4,'Alte Zustimmung und neue Ablehnung bleiben erhalten');
select lives_ok($$select public.my_support_tickets()$$,'Support nach Ablehnung erreichbar');
select lives_ok($$select public.request_account_deletion()$$,'Löschantrag nach Ablehnung erreichbar');
select lives_ok($$select public.export_my_data()$$,'Export während Löschkarenz erreichbar');
select lives_ok($$select public.cancel_account_deletion()$$,'Recovery nach Ablehnung erreichbar');
select is(public.my_consent_v1('en')->>'status','DECLINED','Recovery stimmt nicht unbemerkt zu');
select is(public.record_consent_v1(public.my_consent_v1('en')->'documents',true)->>'status','CURRENT','Erneute bewusste Annahme möglich');
select is(jsonb_array_length(public.export_my_data()->'consentHistory'),6,'Entscheidungswechsel bleiben nachvollziehbar');
select tests.authenticate_as('consent_new');
select lives_ok($$select public.export_my_data()$$,'Export auch während noch offenem Onboarding erreichbar');
select lives_ok($$select public.request_account_deletion()$$,'Löschantrag auch vor Alias und Zustimmung erreichbar');
select lives_ok($$select public.cancel_account_deletion()$$,'Recovery vor Alias erreichbar');
select is(public.my_auth_profile()->>'onboarded','false','Recovery überspringt offenes Onboarding nicht');
select throws_ok($$select public.finish_onboarding('New',null)$$,'42501','consent_required','Recovery ersetzt keine Zustimmung');
select public.record_consent_v1(public.my_consent_v1('de')->'documents',true);
select lives_ok($$select public.finish_onboarding('New',null)$$,'Onboarding nach aktueller Zustimmung erlaubt');
select * from finish();
rollback;
