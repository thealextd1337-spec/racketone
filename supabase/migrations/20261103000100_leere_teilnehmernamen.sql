-- Leere Namen kippen keine Matches mehr (Staging-Befund 03.11.2026: 0 Matches, 6309 zurückgerollte Inserts).
--
-- Die App legt ihren Self-Player ohne Namen an (die UI zeigt „Du“) und lädt unbesetzte Plätze (`placeholder:…`, etwa das
-- Uhr-Schnellmatch) ohne Namen hoch. push_participants schrieb btrim('') und verletzte match_participants_display_name_check
-- (1–40 Zeichen); push_match fing das als `rejected/invalid` ab und rollte das ganze Match zurück – bei jedem Match mit
-- Self-Platz, also praktisch immer. Ebenso lehnte push_players den namenlosen Self-Player ab, womit kein Self-Link entstand.
--
-- 1) push_participants: Leerer oder reiner Whitespace-Name nicht redigierter Plätze wird zu „?“ (sprachneutral; alle
--    App-Versionen zeigen den Snapshot unverändert an, verknüpfte Konten ohnehin mit ihrem Alias). Überlange Namen
--    werden auf 40 Zeichen gekürzt statt das Match abzulehnen. Redigierte Plätze bleiben „Gelöschter Spieler“.
-- 2) push_players: Ein namenloser Self-Player bekommt den Alias des Kontos; Gäste ohne Namen bleiben ungültig.
--    Überlange Namen werden ebenfalls gekürzt.
-- Die Constraints bleiben unverändert (1–40). Signatur, Owner, ACL, SECURITY DEFINER und search_path bleiben erhalten:
-- beide Funktionen werden wie in den Vormigrationen über eine geprüfte Textstelle ersetzt.
do $$
declare
  v_definition text;
  v_marker text := 'else btrim(v_p ->> ''displayName'') end, v_redacted, v_share';
begin
  select pg_get_functiondef('private.push_participants(uuid,uuid,jsonb)'::regprocedure) into v_definition;
  if array_length(string_to_array(v_definition, v_marker), 1) <> 2 then
    raise exception 'Unerwartete push_participants-Seam';
  end if;
  execute replace(v_definition, v_marker,
    'else coalesce(nullif(btrim(left(btrim(v_p ->> ''displayName''), 40)), ''''), ''?'') end, v_redacted, v_share');

  v_marker := E'      v_name := btrim(v_p ->> ''displayName'');\n';
  select pg_get_functiondef('private.push_players(uuid,jsonb)'::regprocedure) into v_definition;
  if array_length(string_to_array(v_definition, v_marker), 1) <> 2 then
    raise exception 'Unerwartete push_players-Seam';
  end if;
  execute replace(v_definition, v_marker,
    E'      v_name := btrim(left(btrim(v_p ->> ''displayName''), 40));\n'
    || E'      -- Namenloser Self-Player (App zeigt „Du“): Alias des Kontos statt Ablehnung.\n'
    || E'      if v_kind = ''self'' and coalesce(v_name, '''') = '''' then\n'
    || E'        v_name := (select pr.alias from public.profiles pr where pr.id = p_uid);\n'
    || E'      end if;\n');
end;
$$;
