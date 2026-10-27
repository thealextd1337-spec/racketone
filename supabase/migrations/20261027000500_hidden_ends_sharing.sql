-- P2-E (Review): „Aus meinem Verlauf ausblenden“ beendet auch das Teilen.
-- 1) Der Löschweg `hidden_only` (Ersteller löscht ein Match mit verknüpften Freunden) setzte nur hidden; can_view_match
--    und sync_pull werten hidden nicht aus, Freunde sahen das Match also weiter im Feed. Jetzt zusätzlich
--    share_with_friends = false. Sonst unverändert gegenüber 20261015000500 (create or replace, gleiche Signatur,
--    gleiche SET-Klausel; Rechte/Owner bleiben). set_my_participation(hidden) sendet der Client mit share = false.
-- 2) share_matches_default ändert keine Sichtbarkeit (gelesen nur beim Anlegen des eigenen Platzes in push_participants);
--    der Generationstrigger auf profile_settings lief trotzdem darauf und erzwang Vollscan + restart_required auf allen
--    eigenen Geräten. Trigger wie in 20261027000300, nur ohne share_matches_default.

drop trigger cloud_access_generation on public.profile_settings;
create trigger cloud_access_generation
  after insert or delete or update of profile_id, country, age_confirmed_at, age_rule_min_age
  on public.profile_settings for each row execute function private.cloud_access_generation();

-- Match-Kopf anlegen/ändern (nur Ersteller) inkl. Teilnehmer. Löschen = Tombstone; sind andere Accounts
-- verknüpft, blendet der Ersteller das Match nur für sich aus (geteilte Matches gehören allen, 03 §8.3).
create or replace function private.push_match(p_uid uuid, p_m jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id      uuid;
  v_match   public.matches%rowtype;
  v_tour    uuid;
  v_court   uuid;
  v_prev    uuid;
  v_result  text;
  v_notes   jsonb := '[]'::jsonb;
begin
  begin
    v_id := (p_m ->> 'id')::uuid;
    v_tour := (p_m ->> 'tournamentId')::uuid;
    v_court := (p_m ->> 'courtId')::uuid;
    v_prev := (p_m ->> 'previousMatchId')::uuid;
  exception when data_exception then
    return jsonb_build_object('id', p_m ->> 'id', 'result', 'rejected', 'reason', 'invalid');
  end;
  if v_id is null then
    return jsonb_build_object('id', null, 'result', 'rejected', 'reason', 'invalid');
  end if;

  select * into v_match from public.matches where id = v_id for update;
  begin
    if not found then
      if v_tour is not null and not private.is_tournament_director(v_tour, p_uid) then
        return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'tournament_forbidden');
      end if;
      if v_court is not null and not exists (
           select 1 from public.tournament_courts c where c.id = v_court and c.tournament_id = v_tour) then
        return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'court_invalid');
      end if;
      if v_prev is not null and not private.can_view_match(v_prev, p_uid) then
        v_prev := null;
      end if;
      insert into public.matches (id, created_by, sport, discipline, format, meta, previous_match_id, tournament_id,
                                  court_id, scheduled_at, started_at, status)
      values (v_id, p_uid, p_m ->> 'sport', p_m ->> 'discipline', p_m -> 'format', coalesce(p_m -> 'meta', '{}'::jsonb),
              v_prev, v_tour, v_court, (p_m ->> 'scheduledAt')::timestamptz, (p_m ->> 'startedAt')::timestamptz,
              case when p_m ->> 'startedAt' is null then 'scheduled' else 'in_progress' end);
      v_result := 'created';
    elsif v_match.created_by <> p_uid then
      return jsonb_build_object('id', v_id, 'result', 'not_owner');
    elsif coalesce((p_m ->> 'deleted')::boolean, false) then
      if exists (select 1 from public.match_participants mp
                 where mp.match_id = v_id and mp.profile_id is not null and mp.profile_id <> p_uid) then
        update public.match_participants set hidden = true, share_with_friends = false
         where match_id = v_id and profile_id = p_uid;
        return jsonb_build_object('id', v_id, 'result', 'hidden_only');
      end if;
      update public.matches set deleted_at = coalesce(deleted_at, now()) where id = v_id;
      return jsonb_build_object('id', v_id, 'result', 'deleted');
    else
      if v_prev is not null and not private.can_view_match(v_prev, p_uid) then
        v_prev := v_match.previous_match_id;
      end if;
      update public.matches
         set meta = coalesce(p_m -> 'meta', meta),
             previous_match_id = coalesce(v_prev, previous_match_id),
             scheduled_at = coalesce((p_m ->> 'scheduledAt')::timestamptz, scheduled_at),
             started_at = coalesce(started_at, (p_m ->> 'startedAt')::timestamptz),
             status = case when status = 'scheduled' and p_m ->> 'startedAt' is not null then 'in_progress' else status end
       where id = v_id;
      v_result := 'updated';
    end if;

    if jsonb_typeof(p_m -> 'participants') = 'array' then
      v_notes := private.push_participants(p_uid, v_id, p_m -> 'participants');
    end if;
  exception when data_exception or integrity_constraint_violation then
    return jsonb_build_object('id', v_id, 'result', 'rejected', 'reason', 'invalid', 'detail', sqlerrm);
  end;
  return jsonb_build_object('id', v_id, 'result', v_result, 'notes', v_notes);
end;
$$;
