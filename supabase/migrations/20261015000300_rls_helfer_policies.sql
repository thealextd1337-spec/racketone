-- Advantly P2-Spike · RLS-Hilfsfunktionen und Policies
-- Helfer sind SECURITY DEFINER (umgehen RLS → keine Rekursion zwischen Policies), liegen im nicht exponierten
-- Schema private, haben search_path = '' und liefern nur Booleans/IDs für den angegebenen bzw. aufrufenden Nutzer.

-- Profil existiert und ist nicht mehr im Onboarding (aktiv oder in der Lösch-Karenz) → darf Eigenes lesen.
create or replace function private.has_profile(p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.profiles p where p.id = p_profile and p.status <> 'onboarding');
$$;

-- Aktiv = Altersabfrage bestanden und keine Löschung beantragt → darf schreiben und wird anderen angezeigt.
create or replace function private.is_active(p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.profiles p where p.id = p_profile and p.status = 'active');
$$;

-- Akzeptierte Freunde (nur aktive Profile; Profile in Lösch-Karenz sind ausgeblendet).
create or replace function private.friend_ids(p_profile uuid default auth.uid())
returns uuid[] language sql stable security definer set search_path = '' as $$
  select coalesce(array_agg(x.other), '{}')
  from (
    select case when f.requester_id = p_profile then f.addressee_id else f.requester_id end as other
    from public.friendships f
    where f.status = 'accepted' and p_profile in (f.requester_id, f.addressee_id)
  ) x
  join public.profiles p on p.id = x.other and p.status = 'active';
$$;

create or replace function private.are_friends(p_a uuid, p_b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.friendships f
    where f.status = 'accepted'
      and least(f.requester_id, f.addressee_id) = least(p_a, p_b)
      and greatest(f.requester_id, f.addressee_id) = greatest(p_a, p_b))
    and private.is_active(p_a) and private.is_active(p_b);
$$;

create or replace function private.is_blocked(p_a uuid, p_b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.user_blocks b
    where (b.blocker_id = p_a and b.blocked_id = p_b) or (b.blocker_id = p_b and b.blocked_id = p_a));
$$;

create or replace function private.is_tournament_staff(p_tournament uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.has_profile(p_profile) and (
    exists (select 1 from public.tournaments t where t.id = p_tournament and t.director_id = p_profile)
    or exists (select 1 from public.tournament_staff s where s.tournament_id = p_tournament and s.profile_id = p_profile));
$$;

create or replace function private.is_tournament_director(p_tournament uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and (
    exists (select 1 from public.tournaments t where t.id = p_tournament and t.director_id = p_profile)
    or exists (select 1 from public.tournament_staff s
               where s.tournament_id = p_tournament and s.profile_id = p_profile and s.role = 'co_director'));
$$;

-- Mitglied = Ersteller, verknüpfter Teilnehmer, eingeladene/aktive Zähler-Person oder Director,
-- Turnierleitung/-helfer. Visitor sind ausdrücklich KEINE Mitglieder.
create or replace function private.is_match_member(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.has_profile(p_profile) and (
    exists (select 1 from public.matches m where m.id = p_match and m.created_by = p_profile)
    or exists (select 1 from public.match_participants mp where mp.match_id = p_match and mp.profile_id = p_profile)
    or exists (select 1 from public.match_roles r
               where r.match_id = p_match and r.profile_id = p_profile and r.status in ('invited', 'accepted'))
    or exists (select 1 from public.matches m
               where m.id = p_match and m.tournament_id is not null
                 and private.is_tournament_staff(m.tournament_id, p_profile)));
$$;

-- Im Feed sichtbar: Mitglieder + Freunde eines Teilnehmers, der teilt (03 E6). Basistabellen lesen nur Mitglieder.
create or replace function private.can_view_match(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_match_member(p_match, p_profile)
      or (private.is_active(p_profile) and exists (
            select 1 from public.match_participants mp
            where mp.match_id = p_match and mp.share_with_friends
              and mp.profile_id = any (private.friend_ids(p_profile))));
$$;

-- Upload-Recht für Events (Identität, NICHT die aktive Zähler-Rolle – die prüft die Engine beim Replay).
-- Widerrufene Zähler dürfen verspätete Events noch hochladen (historische Gültigkeit, 03 §5.3),
-- aber nicht mehr übernehmen (siehe can_take_over).
create or replace function private.can_submit_events(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and (
    exists (select 1 from public.matches m where m.id = p_match and m.created_by = p_profile)
    or exists (select 1 from public.match_participants mp where mp.match_id = p_match and mp.profile_id = p_profile)
    or exists (select 1 from public.match_roles r
               where r.match_id = p_match and r.profile_id = p_profile and r.status in ('accepted', 'revoked'))
    or exists (select 1 from public.matches m
               where m.id = p_match and m.tournament_id is not null
                 and private.is_tournament_director(m.tournament_id, p_profile)));
$$;

-- Darf „Zählen übernehmen“ (ScorerChanged erzeugen bzw. Ziel eines Zählerwechsels sein).
create or replace function private.can_take_over(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and (
    exists (select 1 from public.matches m where m.id = p_match and m.created_by = p_profile)
    or exists (select 1 from public.match_participants mp where mp.match_id = p_match and mp.profile_id = p_profile)
    or exists (select 1 from public.match_roles r
               where r.match_id = p_match and r.profile_id = p_profile and r.status = 'accepted')
    or exists (select 1 from public.matches m
               where m.id = p_match and m.tournament_id is not null
                 and private.is_tournament_director(m.tournament_id, p_profile)));
$$;

-- Visitor (F25): sehen ein Match dauerhaft bis zum Widerruf (kein Ablauf nach Matchende).
create or replace function private.is_match_visitor(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and exists (
    select 1
    from public.match_visitors v
    join public.matches m on m.id = v.match_id
    where v.match_id = p_match and v.profile_id = p_profile and v.revoked_at is null
      and m.deleted_at is null);
$$;

-- Realtime-Autorisierung (private Kanäle) mit Kanalversion (Review P2 #1): Supabase prüft die Policy nur beim
-- Beitritt. Jeder Rechteentzug erhöht die Version (access_epoch); Trigger senden nur auf den aktuellen Kanal, ein
-- Beitritt zu einer alten Version wird abgelehnt. Bestehende Abos auf der alten Version erhalten nur noch das
-- inhaltslose Ereignis „rotate“ (Hinweis: aktuellen Kanal per RPC neu holen).
--   match:<id>:<epoch>          Engine-Events nur für Mitglieder (Spieler-Anzeige, Director, Zähler)
--   match-live:<id>:<epoch>     bereinigte Live-Sicht für Mitglieder und Visitor
--   tournament:<id>:<epoch>     Live-Übersicht für Turnierleitung/-helfer
create or replace function private.can_receive_realtime(p_topic text, p_profile uuid default auth.uid())
returns boolean language plpgsql stable security definer set search_path = '' as $$
declare
  v_parts text[] := regexp_match(coalesce(p_topic, ''),
    '^(match|match-live|tournament):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):([0-9]{1,18})$');
  v_id    uuid;
  v_epoch bigint;
begin
  if v_parts is null then
    return false;
  end if;
  v_id := v_parts[2]::uuid;
  v_epoch := v_parts[3]::bigint;
  if v_parts[1] = 'tournament' then
    return exists (select 1 from public.tournaments t where t.id = v_id and t.access_epoch = v_epoch)
       and private.is_tournament_staff(v_id, p_profile);
  end if;
  if not exists (select 1 from public.matches m where m.id = v_id and m.access_epoch = v_epoch) then
    return false;
  end if;
  if v_parts[1] = 'match' then
    return private.is_match_member(v_id, p_profile);
  end if;
  return private.is_match_member(v_id, p_profile) or private.is_match_visitor(v_id, p_profile);
end;
$$;

create or replace function private.bump_access_epoch(p_matches uuid[])
returns void language sql security definer set search_path = '' as $$
  update public.matches set access_epoch = access_epoch + 1 where id = any (p_matches);
$$;

-- Rechteentzug → Kanalversion erhöhen; Sichtbarkeitsverlust → visibility_epoch der Betroffenen (Review P2 #7).
create or replace function private.participant_access_change()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_lost      boolean;
  v_share_off boolean;
begin
  if old.profile_id is null then
    return null;
  end if;
  v_lost := tg_op = 'DELETE' or new.profile_id is distinct from old.profile_id;
  v_share_off := old.share_with_friends and (v_lost or not new.share_with_friends);
  if v_lost then
    perform private.bump_access_epoch(array[old.match_id]);
    perform private.bump_visibility(array[old.profile_id]);
  end if;
  if v_share_off then
    perform private.bump_visibility(private.friend_ids(old.profile_id));
  end if;
  return null;
end;
$$;

create or replace function private.role_access_change()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if old.status in ('invited', 'accepted')
     and (tg_op = 'DELETE' or new.status not in ('invited', 'accepted') or new.profile_id <> old.profile_id) then
    perform private.bump_access_epoch(array[old.match_id]);
    perform private.bump_visibility(array[old.profile_id]);
  end if;
  return null;
end;
$$;

create or replace function private.visitor_access_change()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if old.revoked_at is null and (tg_op = 'DELETE' or new.revoked_at is not null) then
    perform private.bump_access_epoch(array[old.match_id]);
  end if;
  return null;
end;
$$;

create or replace function private.staff_access_change()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.tournaments set access_epoch = access_epoch + 1 where id = old.tournament_id;
  perform private.bump_access_epoch(array(select m.id from public.matches m where m.tournament_id = old.tournament_id));
  perform private.bump_visibility(array[old.profile_id]);
  return null;
end;
$$;

create trigger match_participants_access after update or delete on public.match_participants
  for each row execute function private.participant_access_change();
create trigger match_roles_access after update or delete on public.match_roles
  for each row execute function private.role_access_change();
create trigger match_visitors_access after update or delete on public.match_visitors
  for each row execute function private.visitor_access_change();
create trigger tournament_staff_access after delete on public.tournament_staff
  for each row execute function private.staff_access_change();

-- Blockieren ändert Sichtbarkeit beidseitig (Freundschaft wird zusätzlich gelöscht).
create or replace function private.block_visibility()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'DELETE' then
    perform private.bump_visibility(array[old.blocker_id, old.blocked_id]);
  else
    perform private.bump_visibility(array[new.blocker_id, new.blocked_id]);
  end if;
  return null;
end;
$$;
create trigger user_blocks_visibility after insert or delete on public.user_blocks
  for each row execute function private.block_visibility();

-- ---------------------------------------------------------------------------------------------------------
-- RLS aktivieren (alle Tabellen in public; Prüfung im pgTAP-Test 010)
alter table public.profiles                     enable row level security;
alter table public.profile_settings             enable row level security;
alter table public.user_blocks                  enable row level security;
alter table public.friendships                  enable row level security;
alter table public.devices                      enable row level security;
alter table public.players                      enable row level security;
alter table public.player_assignment_rejections enable row level security;
alter table public.tournaments                  enable row level security;
alter table public.tournament_courts            enable row level security;
alter table public.tournament_staff             enable row level security;
alter table public.matches                      enable row level security;
alter table public.match_participants           enable row level security;
alter table public.match_link_rejections        enable row level security;
alter table public.match_roles                  enable row level security;
alter table public.match_visitors               enable row level security;
alter table public.match_events                 enable row level security;
alter table public.invites                      enable row level security;
alter table public.entitlements                 enable row level security;

-- PROFILE: eigenes; aktive Freunde (Alias, Benutzername). Fremde nur über find_profile().
create policy profiles_select on public.profiles for select to authenticated
  using (id = (select auth.uid()) or (status = 'active' and id = any ((select private.friend_ids())::uuid[])));
create policy profiles_update_own on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy profile_settings_select_own on public.profile_settings for select to authenticated
  using (profile_id = (select auth.uid()));
create policy profile_settings_update_own on public.profile_settings for update to authenticated
  using (profile_id = (select auth.uid())) with check (profile_id = (select auth.uid()));

create policy user_blocks_select_own on public.user_blocks for select to authenticated
  using (blocker_id = (select auth.uid()));

create policy friendships_select_own on public.friendships for select to authenticated
  using ((select auth.uid()) in (requester_id, addressee_id));

create policy devices_select_own on public.devices for select to authenticated
  using (profile_id = (select auth.uid()));
create policy devices_delete_own on public.devices for delete to authenticated
  using (profile_id = (select auth.uid()));

-- SPIELER: Anleger; zugewiesene Person sieht „ihren“ Dummy (für „War ich nicht“).
create policy players_select on public.players for select to authenticated
  using (owner_id = (select auth.uid()) or profile_id = (select auth.uid()));
create policy player_assignment_rejections_select on public.player_assignment_rejections for select to authenticated
  using (profile_id = (select auth.uid())
         or exists (select 1 from public.players p where p.id = player_id and p.owner_id = (select auth.uid())));

-- TURNIER: Leitung schreibt direkt (RLS), Helfer lesen.
create policy tournaments_select on public.tournaments for select to authenticated
  using (private.is_tournament_staff(id));
create policy tournaments_insert on public.tournaments for insert to authenticated
  with check (director_id = (select auth.uid()) and (select private.is_active()));
create policy tournaments_update on public.tournaments for update to authenticated
  using (director_id = (select auth.uid())) with check (director_id = (select auth.uid()));
create policy tournaments_delete on public.tournaments for delete to authenticated
  using (director_id = (select auth.uid()));

create policy tournament_courts_select on public.tournament_courts for select to authenticated
  using (private.is_tournament_staff(tournament_id));
create policy tournament_courts_write on public.tournament_courts for all to authenticated
  using (private.is_tournament_director(tournament_id))
  with check (private.is_tournament_director(tournament_id));

create policy tournament_staff_select on public.tournament_staff for select to authenticated
  using (private.is_tournament_staff(tournament_id));

-- MATCHES/TEILNEHMER/EVENTS: nur Mitglieder lesen die Basistabellen (Review P2 #2). Freunde eines teilenden
-- Teilnehmers (Feed) bekommen ausschließlich die bereinigte Sicht aus sync_pull – keine fremden Profil-IDs,
-- Ersteller, Autoren oder Geräte-IDs. Schreiben ausschließlich über RPCs (sync_push u. a.).
create policy matches_select on public.matches for select to authenticated
  using (private.is_match_member(id));
create policy match_participants_select on public.match_participants for select to authenticated
  using (private.is_match_member(match_id));
create policy match_events_select on public.match_events for select to authenticated
  using (private.is_match_member(match_id));
create policy match_link_rejections_select on public.match_link_rejections for select to authenticated
  using (profile_id = (select auth.uid())
         or exists (select 1 from public.matches m where m.id = match_id and m.created_by = (select auth.uid())));
create policy match_roles_select on public.match_roles for select to authenticated
  using (profile_id = (select auth.uid()) or private.is_match_member(match_id));
create policy match_visitors_select on public.match_visitors for select to authenticated
  using (profile_id = (select auth.uid()) or private.is_match_member(match_id));

create policy invites_select_own on public.invites for select to authenticated
  using (created_by = (select auth.uid()));
create policy entitlements_select_own on public.entitlements for select to authenticated
  using (profile_id = (select auth.uid()));
