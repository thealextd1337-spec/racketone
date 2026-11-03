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

-- Review H5: Eine Blockierung mit dem Ersteller sperrt die Live-Daten laufender Matches auf allen Lesewegen, nicht nur in
-- cloud_my_live_matches_v1 (20261102000100, dort schon gleichwertig über is_blocked).
--
-- Entscheidung (Datenschutzerklärung §4.3 „Live für Mitspieler“: „Wer Sie blockiert hat oder von Ihnen blockiert wurde,
-- sieht nichts“): Die Sperre gilt für laufende Matches (in_progress, paused, awaiting_decision) – in jeder Richtung der
-- Blockierung, für Mitglieder, Zuschauer, Feed-Leser und Turnierhelfer, nie für den Ersteller selbst. Beendete gemeinsame
-- Matches bleiben bewusst sichtbar: §4.3 verspricht für den Verlauf („erscheint auch in seinem Verlauf“) keine Sperre, die
-- Verknüpfung löst nur „War ich nicht“. Nach Matchende erscheint das Match daher wieder im Abgleich (neue serverVersion).
-- Blockieren und Entsperren erhöhen die Sichtbarkeitsepoche beider Konten (user_blocks_visibility): Der Client lädt den
-- Katalog neu, ein gesperrtes laufendes Match fällt dort weg (ABSENT), nach dem Entsperren kommt es zurück.
-- Gesperrt werden: Basistabellen matches/match_participants/match_events per restriktiver RLS-Policy (damit auch
-- pull_match_events, match_event_watermarks und deren cloud_*_v1-Hüllen, die als Aufrufer lesen), sync_pull,
-- get_visitor_match (damit cloud_get_visitor_match_v1), my_visitor_matches, tournament_overview sowie Readiness und
-- Redaktions-Paging (nach der Epochenprüfung, damit veraltete Clients weiter restart_required bekommen).
-- Antwort jeweils wie ohne Recht (forbidden bzw. leer), kein eigener Fehlercode als Orakel.
-- Nicht betroffen: Zählerwechsel- und Push-Prüfungen (eigene Regeln). Der Konto-Export stellt solche Matches nur zurück
-- (Abschnitt am Ende).
create function private.blocked_from_live(p_match uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.matches m
                 where m.id = p_match and m.created_by <> p_profile
                   and m.status in ('in_progress', 'paused', 'awaiting_decision')
                   and private.is_blocked(p_profile, m.created_by));
$$;
revoke all on function private.blocked_from_live(uuid, uuid) from public, anon, authenticated, service_role;
grant execute on function private.blocked_from_live(uuid, uuid) to authenticated;

create policy live_not_blocked on public.matches as restrictive for select to authenticated
  using (not private.blocked_from_live(id, (select auth.uid())));
create policy live_not_blocked on public.match_participants as restrictive for select to authenticated
  using (not private.blocked_from_live(match_id, (select auth.uid())));
create policy live_not_blocked on public.match_events as restrictive for select to authenticated
  using (not private.blocked_from_live(match_id, (select auth.uid())));

-- Gleiche Seam-Technik wie 20261028000200: Funktionskörper per Textersetzung, Signatur/Owner/ACL/Konfiguration bleiben.
do $$
declare
  v_seam record;
  v_definition text;
begin
  for v_seam in select * from (values
    ('public.sync_pull(bigint,integer)',
     'and (m.created_by = v_uid or private.can_view_match(m.id, v_uid))',
     'and (m.created_by = v_uid or private.can_view_match(m.id, v_uid)) and not private.blocked_from_live(m.id, v_uid)'),
    ('public.get_visitor_match(uuid)',
     'or not (private.is_match_visitor(p_match_id, v_uid) or private.is_match_member(p_match_id, v_uid)) then',
     'or not (private.is_match_visitor(p_match_id, v_uid) or private.is_match_member(p_match_id, v_uid))' || E'\n'
       || '     or private.blocked_from_live(p_match_id, v_uid) then'),
    ('public.my_visitor_matches()',
     'where v.profile_id = v_uid and private.is_match_visitor(v.match_id, v_uid)',
     'where v.profile_id = v_uid and private.is_match_visitor(v.match_id, v_uid)'
       || ' and not private.blocked_from_live(v.match_id, v_uid)'),
    ('public.tournament_overview(uuid)',
     'from public.matches m where m.tournament_id = t.id and m.deleted_at is null',
     'from public.matches m where m.tournament_id = t.id and m.deleted_at is null'
       || ' and not private.blocked_from_live(m.id, v_uid)'),
    ('public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)',
     E'    perform private.fail(''restart_required'',''40001'');\n  end if;\n',
     E'    perform private.fail(''restart_required'',''40001'');\n  end if;\n'
       || E'  if private.blocked_from_live(p_match_id,v_uid) then\n    perform private.fail(''forbidden'',''42501'');\n  end if;\n'),
    ('public.cloud_pull_match_redaction_v1(uuid,bigint,integer,uuid,bigint,bigint,integer,integer)',
     E'    perform private.fail(''restart_required'',''40001'');\n  end if;\n',
     E'    perform private.fail(''restart_required'',''40001'');\n  end if;\n'
       || E'  if private.blocked_from_live(p_match_id,v_uid) then\n    perform private.fail(''forbidden'',''42501'');\n  end if;\n')
  ) s(signature, old, new) loop
    select pg_get_functiondef(v_seam.signature::regprocedure) into v_definition;
    if array_length(string_to_array(v_definition, v_seam.old), 1) <> 2 then
      raise exception 'Unerwartete Seam in %', v_seam.signature;
    end if;
    execute replace(v_definition, v_seam.old, v_seam.new);
  end loop;
end;
$$;

-- Konto-Export (Art. 15) bei Blockierung: export_my_data lieferte verknüpfte laufende Matches samt Events auch dann, wenn
-- Exportierender und Ersteller sich blockiert haben – ein wiederholter Export hätte die Live-Sperre oben umgangen.
-- Entscheidung (03.11.2026): Solche Matches (blocked_from_live: laufend, fremder Ersteller, Blockierung in einer der
-- beiden Richtungen) fehlen im Export bis zum Matchende; danach sind sie wie jedes beendete gemeinsame Match enthalten.
-- Die Auskunft ist damit nur aufgeschoben, nicht verweigert: Die Daten bleiben vollständig gespeichert, und das Manifest
-- nennt neutral die Anzahl zurückgestellter Matches (deferredLiveMatches, ohne IDs oder Namen – sonst wieder ein Orakel
-- für den laufenden Stand). Eigene Matches (Ersteller) sind nie betroffen, blocked_from_live schließt sie aus.
-- Rollen (roles) und Zuschauerrechte (visitorOf) bleiben im Export: nur Match-ID und eigene Zeitstempel, kein Spielstand.
-- Stand 20261028001000; Seam-Technik wie oben, export_my_data ist security definer (RLS greift dort nicht).
do $$
declare
  v_seam record;
  v_definition text;
begin
  select pg_get_functiondef('public.export_my_data()'::regprocedure) into v_definition;
  for v_seam in select * from (values
    ($s$    'blockedCount', (select count(*) from public.user_blocks b where b.blocker_id = v_uid),
$s$,
     $s$    'blockedCount', (select count(*) from public.user_blocks b where b.blocker_id = v_uid),
    'deferredLiveMatches', (select count(*) from public.matches m
                            where exists (select 1 from public.match_participants mp
                                          where mp.match_id = m.id and mp.profile_id = v_uid)
                              and private.blocked_from_live(m.id, v_uid)),
$s$),
    ($s$                where m.created_by = v_uid
                   or exists (select 1 from public.match_participants mp
                              where mp.match_id = m.id and mp.profile_id = v_uid)),
$s$,
     $s$                where (m.created_by = v_uid
                   or exists (select 1 from public.match_participants mp
                              where mp.match_id = m.id and mp.profile_id = v_uid))
                  and not private.blocked_from_live(m.id, v_uid)),
$s$)
  ) s(old, new) loop
    if array_length(string_to_array(v_definition, v_seam.old), 1) <> 2 then
      raise exception 'Unerwartete Seam in export_my_data';
    end if;
    v_definition := replace(v_definition, v_seam.old, v_seam.new);
  end loop;
  execute v_definition;
end;
$$;
