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

-- Höchstens 10 aktive Visitor pro Match (Nutzerentscheid 28.10.2026). Eigene Funktion, damit Tests die Grenze in
-- ihrer Transaktion senken können. Erneutes Benennen eines bereits aktiven Visitors zählt nicht doppelt.
create function private.max_match_visitors()
returns integer language sql immutable set search_path = '' as $$ select 10 $$;

-- Wie 20261016000000_rate_limits.sql plus Cloud-Bindung (20261018000100) und Obergrenze. Die Zeilensperre auf dem
-- Match serialisiert parallele Benennungen, damit die Grenze auch bei gleichzeitigen Aufrufen hält.
create or replace function public.add_match_visitor(p_match_id uuid, p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_m   public.matches%rowtype;
begin
  perform private.require_cloud_binding();
  perform private.consume_rate_limit('invitation');
  select * into v_m from public.matches m where m.id = p_match_id for update;
  if not found or v_m.deleted_at is not null then
    -- Fehlend/gelöscht und unberechtigt sind für Aufrufer nicht unterscheidbar.
    perform private.fail('forbidden', '42501');
  end if;
  if not (v_m.created_by = v_uid
          or exists (select 1 from public.match_participants mp
                     where mp.match_id = p_match_id and mp.profile_id = v_uid
                       and mp.link_status in ('self', 'linked', 'assigned', 'confirmed'))) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_profile_id is null or not private.are_friends(v_uid, p_profile_id) or private.is_blocked(v_uid, p_profile_id) then
    perform private.fail('visitor_must_be_friend');
  end if;
  if private.is_match_member(p_match_id, p_profile_id) then
    perform private.fail('already_member');
  end if;
  if (select count(*) from public.match_visitors v
      where v.match_id = p_match_id and v.revoked_at is null and v.profile_id <> p_profile_id)
     >= private.max_match_visitors() then
    perform private.fail('visitor_limit_reached');
  end if;
  insert into public.match_visitors as v (match_id, profile_id, invited_by)
  values (p_match_id, p_profile_id, v_uid)
  on conflict (match_id, profile_id) do update
    set invited_by = excluded.invited_by, revoked_at = null, created_at = now();
  perform private.notify(p_profile_id, 'visitor_invited', jsonb_build_object('matchId', p_match_id));
end;
$$;
