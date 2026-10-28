-- Welle 8 / Zuschauer (F16/F25): aktive Visitor eines Matches für die Verwaltung in der App.
-- Nur lesend und additiv; Gate-Reihenfolge wie cloud_match_readiness_v1. Aufrufen darf, wer auch benennen darf
-- (Ersteller oder verknüpfter Spieler, Regel aus add_match_visitor); Visitor selbst sehen keine anderen Visitor.
-- canRevoke spiegelt revoke_match_visitor: Benennender oder Match-Ersteller (der Visitor selbst ruft hier nicht auf).

create function public.cloud_match_visitors_v1(p_match_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_m   public.matches%rowtype;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := auth.uid();
  select * into v_m from public.matches m where m.id = p_match_id;
  -- Fehlend/gelöscht und unberechtigt sind für Aufrufer nicht unterscheidbar.
  if not found or v_m.deleted_at is not null
     or not (v_m.created_by = v_uid
             or exists (select 1 from public.match_participants mp
                        where mp.match_id = p_match_id and mp.profile_id = v_uid
                          and mp.link_status in ('self', 'linked', 'assigned', 'confirmed'))) then
    perform private.fail('forbidden', '42501');
  end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
                   'profileId', v.profile_id, 'alias', pr.alias, 'addedBy', v.invited_by,
                   'canRevoke', v.invited_by = v_uid or v_m.created_by = v_uid)
                 order by pr.alias, v.profile_id), '[]'::jsonb)
          from public.match_visitors v
          join public.profiles pr on pr.id = v.profile_id
          where v.match_id = p_match_id and v.revoked_at is null
            and private.is_match_visitor(p_match_id, v.profile_id));
end;
$$;

revoke all on function public.cloud_match_visitors_v1(uuid) from public,anon,authenticated,service_role;
grant execute on function public.cloud_match_visitors_v1(uuid) to authenticated;
