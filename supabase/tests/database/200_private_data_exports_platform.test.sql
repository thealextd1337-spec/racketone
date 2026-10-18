-- Echte Plattform-Nachweise. Fehlende Extensions/Dienste sind Fehler, keine grünen Skips.
begin;
select plan(6);
create function tests.k2_platform() returns jsonb language plpgsql as $$
declare v_bucket boolean:=false; v_job boolean:=false;
begin
 if to_regclass('storage.buckets') is not null then
   execute $q$select count(*)=1 and bool_and(not public and file_size_limit=16777216 and allowed_mime_types=array['application/zip'])
     from storage.buckets where id='private-data-exports'$q$ into v_bucket;
 end if;
 if to_regclass('cron.job') is not null then
   execute $q$select count(*)=1 and bool_and(active and schedule='*/5 * * * *'
     and command='select private.schedule_data_export_cleanup_v1();')
     from cron.job where jobname='advantly-data-export-cleanup-v1'$q$ into v_job;
 end if;
 return jsonb_build_object('bucket',v_bucket,'cron',v_job);
end;
$$;
select ok((tests.k2_platform()->>'bucket')::boolean,'real private bucket with MIME/size limit (requires Storage)');
select ok((tests.k2_platform()->>'cron')::boolean,'real named active cron schedule, command contains no secret (requires pg_cron)');
select ok(exists(select 1 from pg_extension where extname='pg_net'),'real pg_net installed');
select ok(not has_function_privilege('authenticated','private.schedule_data_export_cleanup_v1()','EXECUTE'),'user cannot invoke scheduler');
select ok(not has_function_privilege('service_role','private.schedule_data_export_cleanup_v1()','EXECUTE'),'service API cannot invoke scheduler');
select is((select count(*)::integer from pg_policies where schemaname='storage' and
  (coalesce(qual,'')||coalesce(with_check,'')) like '%private-data-exports%'),0,'no client bucket policies');
select * from finish();
rollback;
