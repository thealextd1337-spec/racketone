-- Plan Live-Mitspieler PR 8 (docs/plan-live-mitspieler.md §3.4/§7): Zählen an ein anderes Konto weitergeben.

-- §7: Übernehmen (ScorerChanged erzeugen, Projektion schreiben) nur Ersteller, angenommene Zähl-Rolle und
-- Turnierleitung – nicht mehr bloß verknüpfte Mitspieler (alte Apps und direkte API-Aufrufe umgehen die App-Prüfung).
-- Verspätete Events früherer Zähler bleiben über can_submit_events hochladbar.
create or replace function private.can_take_over(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and (
    exists (select 1 from public.matches m where m.id = p_match and m.created_by = p_profile)
    or exists (select 1 from public.match_roles r
               where r.match_id = p_match and r.profile_id = p_profile and r.status = 'accepted')
    or exists (select 1 from public.matches m
               where m.id = p_match and m.tournament_id is not null
                 and private.is_tournament_director(m.tournament_id, p_profile)));
$$;

-- sync_pull: Rollen-Geräte (roles.deviceId) für alle Mitglieder, damit der abgebende Zähler ScorerChanged auf das
-- angenommene Gerät erzeugen kann. Rollen liefert sync_pull ohnehin nur an Mitglieder. Änderung am aktuellen Stand
-- (inkl. nachträglich eingefügter Bindungsprüfung), keine Kopie des Funktionskörpers.
do $$
declare
  v_old text := pg_get_functiondef('public.sync_pull(bigint,integer)'::regprocedure);
  v_new text := replace(v_old, $r$'deviceId', case when r.profile_id = v_uid then r.device_id end$r$,
                        $r$'deviceId', r.device_id$r$);
begin
  if v_new = v_old then
    raise exception 'sync_pull: Rollen-Gerät nicht gefunden';
  end if;
  execute v_new;
end;
$$;

-- Bei angenommener Rolle darf das Gerät gewechselt werden (erneute Annahme); widerrufene Geräte nie.
create or replace function public.respond_scorer_invitation(p_match_id uuid, p_accept boolean,
                                                            p_device_id uuid default null)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := private.require_active();
  v_r   public.match_roles%rowtype;
begin
  perform private.require_cloud_binding();
  select * into v_r from public.match_roles r
   where r.match_id = p_match_id and r.profile_id = v_uid
     and (r.status = 'invited' or (r.status = 'accepted' and p_accept))
   for update;
  if not found then
    perform private.fail('no_pending_invitation');
  end if;
  if p_accept and p_device_id is not null
     and not exists (select 1 from public.devices d
                     where d.id = p_device_id and d.profile_id = v_uid and d.revoked_at is null) then
    perform private.fail('device_not_owned', '42501');
  end if;
  update public.match_roles
     set status = case when p_accept then 'accepted' else 'declined' end,
         device_id = case when p_accept then p_device_id end,
         responded_at = now()
   where match_id = p_match_id and profile_id = v_uid;
  if v_r.invited_by is not null then
    perform private.notify(v_r.invited_by, 'scorer_responded',
                           jsonb_build_object('matchId', p_match_id, 'accepted', p_accept));
  end if;
end;
$$;

-- Blockieren und Entfreunden (einzige Aufrufer) widerrufen neben Visitor-Rechten auch Zähl-Rollen zwischen beiden:
-- eingeladen von der anderen Person oder in einem Match, das die andere Person angelegt hat.
create or replace function private.revoke_visitor_grants_between(p_a uuid, p_b uuid)
returns void language sql security definer set search_path = '' as $$
  update public.match_visitors
     set revoked_at = now()
   where revoked_at is null
     and ((invited_by = p_a and profile_id = p_b) or (invited_by = p_b and profile_id = p_a));
  update public.match_roles r
     set status = 'revoked', responded_at = now()
   where r.status in ('invited', 'accepted')
     and ((r.profile_id = p_b and (r.invited_by = p_a
            or r.match_id = any (array(select m.id from public.matches m where m.created_by = p_a))))
       or (r.profile_id = p_a and (r.invited_by = p_b
            or r.match_id = any (array(select m.id from public.matches m where m.created_by = p_b)))));
$$;

-- Zählerwechsel an alle Beteiligten (Ersteller, verknüpfte Mitspieler, angenommene Rollen) außer dem Auslöser.
create function private.notify_scorer_changed()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  perform private.notify(x.profile_id, 'scorer_changed', jsonb_build_object('matchId', new.match_id), new.author_id)
  from (select m.created_by as profile_id from public.matches m where m.id = new.match_id
        union select mp.profile_id from public.match_participants mp
              where mp.match_id = new.match_id and mp.profile_id is not null
        union select r.profile_id from public.match_roles r
              where r.match_id = new.match_id and r.status = 'accepted') x
  where x.profile_id is distinct from new.author_id;
  return null;
end;
$$;
create trigger match_events_scorer_changed after insert on public.match_events
  for each row when (new.type = 'ScorerChanged') execute function private.notify_scorer_changed();
revoke all on function private.notify_scorer_changed() from public, anon, authenticated, service_role;
