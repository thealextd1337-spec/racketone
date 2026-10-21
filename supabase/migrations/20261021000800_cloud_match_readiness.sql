-- Welle 2 / D1: Readiness-Vektor eines Mitgliedsmatches, gebunden an G, E und H.
-- Nur lesend und additiv; Vorbild und Gate-Reihenfolge wie cloud_pull_match_redaction_v1.
-- Redigierte Events bleiben Teil des Vektors: Redaktion entfernt Inhalte, nicht Sequenzen.

create function public.cloud_match_readiness_v1(
  p_match_id uuid, p_generation bigint, p_redaction_epoch integer, p_high_watermark bigint default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_generation bigint;
  v_epoch integer;
  v_version bigint;
  v_max bigint;
  v_h bigint;
  v_devices integer;
  v_watermarks jsonb;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := auth.uid();
  -- Mitgliedschaft vor Epochendetails: weder Feed noch Visitor erhalten einen Seitenkanal.
  if not private.is_match_member(p_match_id,v_uid) then
    perform private.fail('forbidden','42501');
  end if;
  -- 0 ist gültiger Initialstand für Epochen und leere Matches.
  if p_generation is null or p_generation<0 or p_redaction_epoch is null or p_redaction_epoch<0
     or p_high_watermark<0 then
    perform private.fail('invalid_cursor');
  end if;
  select s.visibility_epoch into strict v_generation from public.profile_settings s where s.profile_id=v_uid;
  select m.redaction_epoch,m.server_version into strict v_epoch,v_version from public.matches m where m.id=p_match_id;
  if p_generation<>v_generation or p_redaction_epoch<>v_epoch then
    perform private.fail('restart_required','40001');
  end if;
  select coalesce(max(e.server_seq),0) into v_max from public.match_events e where e.match_id=p_match_id;
  v_h := coalesce(p_high_watermark,v_max);
  if v_h>v_max then perform private.fail('invalid_cursor'); end if;
  -- 257 statt 256 lesen genügt, um die Grenze ohne vollständige Gerätezählung zu erkennen.
  select count(*),coalesce(jsonb_agg(jsonb_build_object('deviceId',w.device_id,'maxSeq',w.max_seq,
      'eventCount',w.event_count) order by w.device_id),'[]'::jsonb)
    into v_devices,v_watermarks from (
      select e.device_id,max(e.device_seq) as max_seq,count(*) as event_count
      from public.match_events e where e.match_id=p_match_id and e.server_seq<=v_h
      group by e.device_id order by e.device_id limit 257
    ) w;
  if v_devices>256 then perform private.fail('readiness_too_large'); end if;
  return jsonb_build_object('matchId',p_match_id,'generation',v_generation,'redactionEpoch',v_epoch,
    'highWatermark',v_h,'serverVersion',v_version,'watermarks',v_watermarks);
end;
$$;

revoke all on function public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)
  from public,anon,authenticated,service_role;
grant execute on function public.cloud_match_readiness_v1(uuid,bigint,integer,bigint) to authenticated;
