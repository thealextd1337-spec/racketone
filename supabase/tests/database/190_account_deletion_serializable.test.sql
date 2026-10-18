-- Auch serialisierbare Aufrufer bleiben außerhalb des Kontovertrags.
begin isolation level serializable;
select plan(2);
select tests.create_user('k3_serial');
select tests.authenticate_as('k3_serial');
select throws_ok('select public.account_deletion_status_v1()','42501','cloud_session_changed','Serializable-Status geschlossen');
reset role;
set local role service_role;
select throws_ok('select private.run_account_deletion_job_v1()','42501','cloud_session_changed','Serializable-Job geschlossen');
reset role;
select * from finish();
rollback;
