-- Plan Live-Mitspieler PR 1 (docs/plan-live-mitspieler.md §3.1/§8): laufende Matches anderer Konten, in denen ich
-- Mitspieler, Zähler-Person oder Zuschauer bin. Nur lesend, Gate-Reihenfolge wie cloud_get_visitor_match_v1.
-- Sicht nur über die bestehenden Regeln (is_match_member/is_match_visitor); Entfreunden/Blockieren widerrufen Visitor,
-- „War ich nicht“ löst die Verknüpfung. Zusätzlich blendet eine Blockierung mit dem Ersteller das Match aus, auch für
-- verknüpfte Mitspieler (Mitgliedschaft bleibt sonst bestehen). Ausgeblendete und gelöschte Matches fehlen.
-- Projektion und Namen ausschließlich aus visitor_view (Whitelist ohne Telemetrie). Turnierleitung ohne eigene Rolle
-- ist nicht enthalten (eigene Live-Übersicht tournament_overview).
create function public.cloud_my_live_matches_v1()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := auth.uid();
  return (select coalesce(jsonb_agg(jsonb_build_object(
             'matchId', m.id,
             'relation', case
               when exists (select 1 from public.match_participants mp
                            where mp.match_id = m.id and mp.profile_id = v_uid) then 'player'
               when exists (select 1 from public.match_roles r where r.match_id = m.id and r.profile_id = v_uid
                              and r.status in ('invited', 'accepted')) then 'scorer'
               else 'visitor' end,
             'sport', m.sport, 'discipline', m.discipline,
             'participants', vv.v -> 'teams',
             'projection', vv.v -> 'live',
             'scorerConnected', coalesce((vv.v -> 'live' ->> 'scorerConnected')::boolean, false),
             'updatedAt', m.updated_at)
           order by m.updated_at desc, m.id), '[]'::jsonb)
    -- visitor_view erst für die gefilterten Matches (OFFSET 0 als Planungsgrenze), nicht für jede Teilnahme.
    from (select m.* from public.matches m
    -- Kandidaten ohne Join (Planner-Statistik, siehe Bereinigungspfade), danach die maßgebliche Rechteprüfung.
    where m.id = any (array(
            select mp.match_id from public.match_participants mp where mp.profile_id = v_uid
            union select r.match_id from public.match_roles r
                  where r.profile_id = v_uid and r.status in ('invited', 'accepted')
            union select v.match_id from public.match_visitors v where v.profile_id = v_uid and v.revoked_at is null))
      and m.created_by <> v_uid
      and m.deleted_at is null
      and m.status in ('in_progress', 'paused', 'awaiting_decision')
      and m.updated_at > now() - interval '12 hours'
      and not private.is_blocked(v_uid, m.created_by)
      and not exists (select 1 from public.match_participants mp
                      where mp.match_id = m.id and mp.profile_id = v_uid and mp.hidden)
      and (private.is_match_member(m.id, v_uid) or private.is_match_visitor(m.id, v_uid))
    offset 0) m
    cross join lateral (select private.visitor_view(m.id) as v offset 0) vv);
end;
$$;

revoke all on function public.cloud_my_live_matches_v1() from public, anon, authenticated, service_role;
grant execute on function public.cloud_my_live_matches_v1() to authenticated;
