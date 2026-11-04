-- Review 0.6.3 (Plan §3.6): visitor_view liefert je Platz das Flag `deletedProfile` (match_participants.deleted_profile).
-- Zuschauer-, Live- (cloud_my_live_matches_v1, Realtime) und Turnieransicht erkannten redigierte Plätze bisher nur am
-- Snapshot-Namen „Gelöschter Spieler“; ein Gast dieses Namens galt dann als gelöscht. Wie sync_pull (`deletedProfile` je
-- Teilnehmer) ist jetzt das Flag maßgeblich. Neues, optionales Feld: 0.6.2 ignoriert unbekannte Schlüssel und bleibt beim
-- Namensvergleich. Keine neue Datenart, der Name verrät die Redaktion schon heute.
-- Ersatz über eine geprüfte Textstelle wie in den Vormigrationen, keine Kopie des Funktionskörpers: Signatur, Owner, ACL,
-- SECURITY DEFINER und search_path bleiben.
do $$
declare
  v_old text := pg_get_functiondef('private.visitor_view(uuid)'::regprocedure);
  v_marker text := 'else mp.display_name end)';
begin
  if array_length(string_to_array(v_old, v_marker), 1) <> 2 then
    raise exception 'visitor_view: Namensausdruck der Teams nicht eindeutig gefunden';
  end if;
  execute replace(v_old, v_marker, 'else mp.display_name end, ''deletedProfile'', mp.deleted_profile)');
end;
$$;
