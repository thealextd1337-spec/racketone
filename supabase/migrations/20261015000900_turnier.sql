-- Advantly P2-Spike · Turnierdirektor-Modus (F22) – Grundgerüst
-- Turnier/Plätze schreibt die Leitung direkt (RLS). Turnier-Matches legt die Leitung per sync_push mit
-- tournamentId/courtId an (Status scheduled ohne startedAt), Zähler weist sie per invite_scorer zu.

create or replace function public.add_tournament_staff(p_tournament_id uuid, p_profile_id uuid, p_role text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  if not exists (select 1 from public.tournaments t where t.id = p_tournament_id and t.director_id = v_uid) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_role not in ('co_director', 'scorer') then
    perform private.fail('invalid_role');
  end if;
  if p_profile_id is null or p_profile_id = v_uid or not private.are_friends(v_uid, p_profile_id)
     or private.is_blocked(v_uid, p_profile_id) then
    perform private.fail('staff_must_be_friend');
  end if;
  insert into public.tournament_staff (tournament_id, profile_id, role)
  values (p_tournament_id, p_profile_id, p_role)
  on conflict (tournament_id, profile_id) do update set role = excluded.role;
end;
$$;

create or replace function public.remove_tournament_staff(p_tournament_id uuid, p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  delete from public.tournament_staff s
   where s.tournament_id = p_tournament_id and s.profile_id = p_profile_id
     and (p_profile_id = v_uid
          or exists (select 1 from public.tournaments t where t.id = p_tournament_id and t.director_id = v_uid));
  if not found then
    perform private.fail('forbidden', '42501');
  end if;
end;
$$;

-- Live-Übersicht aller Plätze für Leitung und Helfer (Initialstand; Updates über Kanal tournament:<id>).
create or replace function public.tournament_overview(p_tournament_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not private.is_tournament_staff(p_tournament_id, v_uid) then
    perform private.fail('forbidden', '42501');
  end if;
  return (
    select jsonb_build_object(
      'tournament', jsonb_build_object('id', t.id, 'name', t.name, 'sport', t.sport, 'status', t.status,
                                       'startsOn', t.starts_on, 'endsOn', t.ends_on),
      'channel', 'tournament:' || t.id::text || ':' || t.access_epoch::text,
      'courts', (select coalesce(jsonb_agg(jsonb_build_object('id', c.id, 'name', c.name) order by c.sort_order, c.name),
                                 '[]'::jsonb)
                 from public.tournament_courts c where c.tournament_id = t.id),
      'matches', (select coalesce(jsonb_agg(
                    private.visitor_view(m.id) || jsonb_build_object(
                      'courtId', m.court_id, 'scheduledAt', m.scheduled_at,
                      'scorers', (select coalesce(jsonb_agg(jsonb_build_object(
                                     'profileId', r.profile_id, 'alias', pr.alias, 'role', r.role, 'status', r.status)),
                                   '[]'::jsonb)
                                  from public.match_roles r join public.profiles pr on pr.id = r.profile_id
                                  where r.match_id = m.id))
                    order by m.scheduled_at nulls last, m.id), '[]'::jsonb)
                  from public.matches m where m.tournament_id = t.id and m.deleted_at is null))
    from public.tournaments t
    where t.id = p_tournament_id);
end;
$$;
