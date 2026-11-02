-- Review H4 (Plan Live-Mitspieler §3.4/§7): Ein Rollenwiderruf beendet das Zählen eines fremden Geräts auch serverseitig.
--
-- Bisher änderten Widerruf (revoke_scorer), Blockieren und Entfreunden nur den Rollenstatus. Solange kein neues
-- ScorerChanged folgte, blieb das Gerät in der Engine Zähler, und can_submit_events nahm seine neuen Punkte an – der
-- Server konnte sie nicht von Nachlieferungen unterscheiden. Dasselbe galt für verknüpfte Mitspieler ohne Rolle, die seit
-- 20261102000300 nicht mehr übernehmen dürfen, aber mit alten Apps noch Zähler sein können.
--
-- Berechtigungsepoche: Wer Events hochladen, aber nicht (mehr) übernehmen darf (can_submit_events ohne can_take_over:
-- widerrufene Rolle, Mitspieler ohne Rolle), lädt je Gerät nur noch Events bis zum höchsten Lamport hoch, den der Server
-- von diesem Gerät im Match schon hat. Höhere lehnt push_event mit `scoring_revoked` ab (nicht wiederholen).
-- Der Stand muss nicht beim Widerruf festgehalten werden: Solange das Recht fehlt, kommt kein höherer Lamport hinzu, das
-- Maximum bleibt also genau der Stand beim Rechteverlust – auch bei einem Upload, der parallel zum Widerruf committet.
-- Eine erneut angenommene Rolle hebt die Grenze auf. Ersteller, angenommene Rollen und Turnierleitung sind nicht betroffen.
--
-- Die Engine bleibt unverändert: Bis zum nächsten ScorerChanged gilt das widerrufene Gerät dort weiter als Zähler, seine
-- Nachlieferungen ≤ Stand wirken. Das Zählen geht erst weiter, wenn der Ersteller ScorerChanged auf eines seiner Geräte
-- erzeugt (can_take_over erlaubt das jederzeit).
do $$
declare
  v_definition text;
  v_marker text := E'  insert into public.match_events (id, match_id, device_id, device_seq, lamport, wall_clock_ms, parent_id, type,\n';
begin
  select pg_get_functiondef('private.push_event(uuid,jsonb)'::regprocedure) into v_definition;
  if array_length(string_to_array(v_definition, v_marker), 1) <> 2 then
    raise exception 'Unerwartete push_event-Seam';
  end if;
  execute replace(v_definition, v_marker,
    E'  -- Review H4: ohne Übernahmerecht nur Nachlieferungen bis zum bekannten Stand dieses Geräts (Berechtigungsepoche).\n'
    || E'  if not private.can_take_over(v_match, p_uid) then\n'
    || E'    if v_lamport > coalesce((select max(e.lamport) from public.match_events e\n'
    || E'                             where e.match_id = v_match and e.device_id = v_device), -1) then\n'
    || E'      return jsonb_build_object(''id'', v_id, ''result'', ''rejected'', ''reason'', ''scoring_revoked'');\n'
    || E'    end if;\n'
    || E'  end if;\n\n'
    || v_marker);
end;
$$;
