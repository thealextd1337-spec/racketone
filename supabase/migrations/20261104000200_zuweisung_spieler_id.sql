-- Plan 0.6.3 §3.5: „Das war ich nicht“ im Match-Detail des Freundes. reject_player_assignment braucht die Spieler-ID des
-- zugeordneten Gasts, sync_pull gab sie bisher nur dem Anleger. Jetzt sieht sie auch die Person, der der Gast
-- zugeordnet ist, und nur an ihrem eigenen `assigned`-Platz. Keine neue Datenart: dieselbe ID kommt ihr ohnehin mit dem
-- zugeordneten Spieler im Pull (players: profile_id = ich). Änderung am aktuellen Stand per Seam, keine Kopie des
-- Funktionskörpers; Signatur, Owner, ACL, security definer und search_path bleiben.
do $$
declare
  v_old text := pg_get_functiondef('public.sync_pull(bigint,integer)'::regprocedure);
  v_new text := replace(v_old, $r$'playerId', case when pl.owner_id = v_uid then mp.player_id end$r$,
                        $r$'playerId', case when pl.owner_id = v_uid
                                                 or (mp.profile_id = v_uid and mp.link_status = 'assigned')
                                            then mp.player_id end$r$);
begin
  if v_new = v_old then
    raise exception 'sync_pull: Spieler-ID der Teilnehmer nicht gefunden';
  end if;
  execute v_new;
end;
$$;
