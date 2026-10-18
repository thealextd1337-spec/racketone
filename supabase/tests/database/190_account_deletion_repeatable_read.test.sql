-- Status und Job autorisieren keinen veralteten Snapshot.
begin isolation level repeatable read;
select plan(2);
select tests.create_user('k3_rr');
select tests.authenticate_as('k3_rr');
select throws_ok('select public.account_deletion_status_v1()','42501','cloud_session_changed','RR-Status geschlossen');
reset role;
set local role service_role;
select throws_ok('select private.run_account_deletion_job_v1()','42501','cloud_session_changed','RR-Job geschlossen');
reset role;
select * from finish();
rollback;
