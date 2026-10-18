-- Nur echte pg_cron-Kataloge prüfen; der PG17-Stub belegt keine Planung oder Ausführung.
begin;
select plan(4);
select ok(case when exists(select 1 from pg_extension where extname='pg_cron')
  then public.account_deletion_job_status_v1()->'scheduler'->>'status' in ('unknown','running','succeeded','failed')
  else public.account_deletion_job_status_v1()->'scheduler'->>'status'='unavailable' end,
  'Keine erfundene Scheduler-Ausführung');
do $$
declare v_before jsonb; v_after jsonb; v_ok boolean;
begin
  if exists(select 1 from pg_extension where extname='pg_cron') then
    execute $q$select coalesce(jsonb_agg(to_jsonb(j) order by jobid),'[]'::jsonb)
      from cron.job j where jobname<>'advantly-account-deletion-v1'$q$ into v_before;
    execute $q$select count(*)=1 and bool_and(active and schedule='47 * * * *' and username=current_user
      and command='set statement_timeout = ''60s''; select private.run_account_deletion_job_v1();')
      from cron.job where jobname='advantly-account-deletion-v1'$q$ into v_ok;
    perform set_config('tests.k3cron_initial',coalesce(v_ok,false)::text,true);
    -- Derselbe benannte Migrationsaufruf zweimal: keine doppelten Jobs, kein fremder Job verändert.
    perform cron.schedule('advantly-account-deletion-v1','47 * * * *',
      'set statement_timeout = ''60s''; select private.run_account_deletion_job_v1();');
    perform cron.schedule('advantly-account-deletion-v1','47 * * * *',
      'set statement_timeout = ''60s''; select private.run_account_deletion_job_v1();');
    execute $q$select count(*)=1 and bool_and(active and schedule='47 * * * *' and username=current_user
      and command='set statement_timeout = ''60s''; select private.run_account_deletion_job_v1();')
      from cron.job where jobname='advantly-account-deletion-v1'$q$ into v_ok;
    perform set_config('tests.k3cron_idempotent',coalesce(v_ok,false)::text,true);
    execute $q$select coalesce(jsonb_agg(to_jsonb(j) order by jobid),'[]'::jsonb)
      from cron.job j where jobname<>'advantly-account-deletion-v1'$q$ into v_after;
    perform set_config('tests.k3cron_others',(v_before=v_after)::text,true);
  end if;
end $$;
select case when exists(select 1 from pg_extension where extname='pg_cron')
  then ok(current_setting('tests.k3cron_initial')::boolean,'Migration registriert genau einen aktiven Job mit Frist vor SELECT')
  else skip('pg_cron fehlt: tatsächliche Planung im Supabase-Stack nachweisen') end;
select case when exists(select 1 from pg_extension where extname='pg_cron')
  then ok(current_setting('tests.k3cron_idempotent')::boolean,'Benannte Planung bleibt bei Wiederholung eindeutig')
  else skip('pg_cron fehlt: echte Schedule-Idempotenz offen') end;
select case when exists(select 1 from pg_extension where extname='pg_cron')
  then ok(current_setting('tests.k3cron_others')::boolean,'Andere Jobs unverändert')
  else skip('pg_cron fehlt: andere Schedulerjobs nicht simulieren') end;
select * from finish();
rollback;
