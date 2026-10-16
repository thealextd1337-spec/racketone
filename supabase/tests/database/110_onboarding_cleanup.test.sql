-- P2-A: Ablaufgrenze, vollständige Kaskade, Batchgröße und Scheduler-Konfiguration.
begin;
select plan(16);
select tests.create_user('stale_onboarding', false);
select tests.create_user('boundary_onboarding', false);
select tests.create_user('recent_onboarding', false);
select tests.create_user('old_active');
select tests.create_user('old_deletion');
update public.profiles set created_at = now() - interval '2 days';
update public.profiles set created_at = now() - interval '24 hours' where id = tests.uid('boundary_onboarding');
update public.profiles set created_at = now() - interval '24 hours' + interval '1 microsecond'
 where id = tests.uid('recent_onboarding');
update public.profiles set status = 'deletion_requested' where id = tests.uid('old_deletion');
insert into ops.rate_limits values (tests.uid('stale_onboarding'), 'search', now() - interval '2 days', 1);

select is(private.purge_stale_onboarding(), 2, 'Altes Onboarding einschließlich exakt 24 Stunden wird gelöscht');
select is_empty($$ select 1 from auth.users where id in (tests.uid('stale_onboarding'), tests.uid('boundary_onboarding')) $$,
                'Auth-Nutzer werden vollständig entfernt');
select is_empty($$ select 1 from public.profiles where id = tests.uid('stale_onboarding') $$, 'Profil kaskadiert');
select is_empty($$ select 1 from public.profile_settings where profile_id = tests.uid('stale_onboarding') $$, 'Einstellungen kaskadieren');
select is_empty($$ select 1 from ops.rate_limits where profile_id = tests.uid('stale_onboarding') $$, 'Budgets kaskadieren');
select isnt_empty($$ select 1 from auth.users where id = tests.uid('recent_onboarding') $$, 'Knapp unter 24 Stunden bleibt bestehen');
select isnt_empty($$ select 1 from auth.users where id = tests.uid('old_active') $$, 'Aktive Konten bleiben bestehen');
select isnt_empty($$ select 1 from auth.users where id = tests.uid('old_deletion') $$, 'Löschkarenz wird nicht umgangen');
select is(private.purge_stale_onboarding(), 0, 'Wiederholung ist idempotent');
select ok(not has_function_privilege('anon', 'private.purge_stale_onboarding()', 'execute')
          and not has_function_privilege('authenticated', 'private.purge_stale_onboarding()', 'execute'),
          'Kein Client darf den Aufräumjob ausführen');
set local role service_role;
select is(private.purge_stale_onboarding(), 0, 'Service-Rolle darf Wartung ausführen');
reset role;

do $$ begin
  for i in 1..101 loop perform tests.create_user('batch_' || i, false); end loop;
end $$;
update public.profiles set created_at = now() - interval '2 days' where status = 'onboarding' and id <> tests.uid('recent_onboarding');
select is(private.purge_stale_onboarding(), 100, 'Ein Lauf ist auf 100 Konten begrenzt');
select is(private.purge_stale_onboarding(), 1, 'Nächster Lauf erledigt Rest');
select is(private.purge_stale_onboarding(), 0, 'Danach keine verwaisten Konten');

-- Pflicht im Supabase-Stack: fehlendes pg_cron darf nicht als Skip/grün durchgehen.
select has_extension('pg_cron', 'pg_cron ist installiert');
do $$
declare v_ok boolean := false;
begin
  -- Ohne Erweiterung bleibt das Ergebnis rot; der Guard ermöglicht die übrige lokale Funktionsprüfung.
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    execute $q$select count(*) = 1 and count(*) filter (
        where schedule = '17 * * * *'
          and command = 'set statement_timeout = ''60s''; select private.purge_stale_onboarding();'
          and active) = 1
      from cron.job where jobname = 'advantly-purge-stale-onboarding'$q$ into v_ok;
  end if;
  perform set_config('tests.cron_ok', v_ok::text, true);
end;
$$;
select ok(current_setting('tests.cron_ok')::boolean, 'pg_cron: genau ein benannter aktiver Job mit Schedule und Command');
select * from finish();
rollback;
