-- Doppelte Team-Position lehnt nur dieses Match ab, nicht den ganzen Upload (Plan 0.6.3 §3.3).
--
-- match_participants.participant_team_position_uq (match_id, team, position) ist DEFERRABLE INITIALLY DEFERRED, damit ein
-- Positionstausch A1 ↔ A2 in einem Upload durchgeht. Die Prüfung lief dadurch erst beim Commit, also außerhalb des
-- Exception-Blocks von private.push_match: Ein Verstoß rollte den ganzen cloud_sync_push_v1-Aufruf zurück (HTTP-Fehler),
-- die App wiederholte den Batch mit denselben Daten und kam nicht weiter.
--
-- Jetzt prüft push_match die Constraint direkt nach allen Teilnehmern eines Matches (`set constraints … immediate`),
-- noch im inneren Exception-Block. Ein Verstoß (unique_violation, Klasse 23) landet im bestehenden Handler und ergibt
-- `rejected/invalid` mit `detail` nur für dieses Match; die Subtransaktion stellt dabei auch den Modus wieder her.
-- Danach gilt wieder `deferred`, ein Tausch innerhalb der Teilnehmerliste bleibt erlaubt.
-- Signatur, Owner, ACL, SECURITY DEFINER und search_path bleiben: Ersatz über eine geprüfte Textstelle wie in den
-- Vormigrationen.
do $$
declare
  v_definition text;
  v_marker text := E'      v_notes := private.push_participants(p_uid, v_id, p_m -> ''participants'');\n';
begin
  select pg_get_functiondef('private.push_match(uuid,jsonb)'::regprocedure) into v_definition;
  if array_length(string_to_array(v_definition, v_marker), 1) <> 2 then
    raise exception 'Unerwartete push_match-Seam';
  end if;
  execute replace(v_definition, v_marker, v_marker
    || E'      -- Doppelte Position jetzt prüfen (im Exception-Block), nicht erst beim Commit des ganzen Batches.\n'
    || E'      set constraints public.participant_team_position_uq immediate;\n'
    || E'      set constraints public.participant_team_position_uq deferred;\n');
end;
$$;
