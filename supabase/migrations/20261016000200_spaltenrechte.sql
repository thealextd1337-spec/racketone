-- P2-A: RLS filtert Zeilen, keine Spalten. Fremde Freundescodes und interne Token bleiben privat.
revoke select on public.profiles from authenticated;
grant select (id, status, alias, username, discoverable, server_version, created_at, updated_at)
  on public.profiles to authenticated;

-- Eigener Code über geprüften RPC, damit SELECT auf befreundeten Profilen ihn nicht offenlegt.
create or replace function public.my_friend_code()
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_code text;
begin
  select p.friend_code into v_code from public.profiles p where p.id = v_uid;
  return v_code;
end;
$$;
revoke all on function public.my_friend_code() from public, anon, authenticated;
grant execute on function public.my_friend_code() to authenticated, service_role;

revoke select on public.invites from authenticated;
grant select (id, kind, created_by, player_id, expires_at, used_by, used_at, revoked_at, created_at)
  on public.invites to authenticated;
revoke select on public.devices from authenticated;
grant select (id, profile_id, kind, platform, paired_with, standalone, model, app_version,
              last_seen_at, revoked_at, created_at) on public.devices to authenticated;
-- device_id nur für die eigene Rolle über sync_pull, nicht für alle Match-Mitglieder direkt.
revoke select on public.match_roles from authenticated;
grant select (match_id, profile_id, role, status, invited_by, invited_at, responded_at,
              server_version, updated_at) on public.match_roles to authenticated;

-- Direkte Turnierpflege nur für fachliche Felder; Kanalversionen/Zeitstempel verwaltet der Server.
revoke insert, update on public.tournaments from authenticated;
grant insert (id, director_id, name, sport, starts_on, ends_on, status)
  on public.tournaments to authenticated;
grant update (name, sport, starts_on, ends_on, status) on public.tournaments to authenticated;
revoke update on public.tournament_courts from authenticated;
grant update (name, sort_order) on public.tournament_courts to authenticated;
