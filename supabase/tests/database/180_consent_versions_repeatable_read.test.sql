-- K1: Nicht unterstuetzte Isolation darf keine alten Auth-/Consent-Snapshots autorisieren.
begin isolation level repeatable read;
select plan(8);
select tests.create_user('isolation_alice');
select tests.authenticate_as('isolation_alice');
select throws_ok('select '||call,'42501','cloud_session_changed','Isolation geschlossen: '||call)
from (values
  ('public.my_consent_v1(''en'')'), ('public.record_consent_v1(''[]'',false)'),
  ('public.my_auth_profile()'), ('public.export_my_data()'),
  ('public.request_account_deletion()'), ('public.cancel_account_deletion()')
) r(call);
reset role;
select throws_ok($$select private.require_account_session()$$,'42501','cloud_session_changed','Sessionhelfer lehnt Isolation ab');
select throws_ok($$select private.require_current_consents()$$,'42501','cloud_session_changed','Consenthelfer lehnt Isolation ab');
select * from finish();
rollback;
