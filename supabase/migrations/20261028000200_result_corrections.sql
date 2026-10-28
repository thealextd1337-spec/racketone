-- 0.3.0 „Ergebnis bearbeiten“ (Protokoll 5): zwei neue Event-Typen nach Matchende.
--   ResultCorrected      {"sets":[{"kind":"REGULAR","score":{"A":6,"B":4},"tiebreak":{"A":7,"B":5}|null},…]}
--   GameServersCorrected {"servers":[{"set":0,"game":3,"player":"<Platz-ID>"},…]}
-- Die Engine (core-scoring) faltet sie; der Server speichert sie wie jedes andere Event (append-only, keine eigene
-- Projektion). Feed und Besucheransicht zeigen das korrigierte Ergebnis ohne weitere Änderung, weil der Client die
-- Projektion (summary, winner) aus dem korrigierten Zustand hochlädt; sanitize_projection bleibt unverändert.
-- Redaktion bei Kontolöschung betrifft nur Klarnamen in PlayerAssigned – die neuen Events enthalten keine Namen.
-- Ältere Apps (0.2.0, Protokoll 4) können die Typen nicht dekodieren: Ein Cloud-Pull scheitert dort für das betroffene
-- Match sicher (Fehlerzustand, kein Absturz, keine Datenänderung); Uhr↔Handy schützt der Updatezustand (minV = 5).

alter table public.match_events drop constraint match_events_type_check;
alter table public.match_events add constraint match_events_type_check check (type in (
  'MatchCreated', 'PointWon', 'Undo', 'ContinueDecision', 'Correction', 'MatchEnded',
  'Paused', 'Resumed', 'ScorerChanged', 'PointDetailSet', 'ServerChosen', 'PlayerAssigned',
  'ResultCorrected', 'GameServersCorrected'));

-- Export (K2-Whitelist): Satzstände vollständig, Aufschläger je Spiel wie ServerChosen/Correction ohne Spieler-ID.
create or replace function private.export_payload(p_type text, p_payload jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  -- Nur optionale Felder der bisherigen Eventhülle auslassen. Fachobjekte behalten
  -- ausdrückliches Null (every:null ist semantisch verschieden vom Default 6).
  select coalesce(jsonb_object_agg(e.key,e.value),'{}'::jsonb)
  from jsonb_each(case p_type
    when 'MatchCreated' then jsonb_build_object('type', p_type, 'config', private.export_format(p_payload -> 'config'), 'meta', private.export_meta(p_payload -> 'meta'))
    when 'PointWon' then jsonb_build_object('type', p_type, 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb),
                                            'detail', private.export_detail(p_payload -> 'detail'))
    when 'Undo' then jsonb_build_object('type', p_type, 'target', private.export_shape(p_payload -> 'target', '"string"'::jsonb))
    when 'ContinueDecision' then jsonb_build_object('type', p_type, 'anotherSet', private.export_shape(p_payload -> 'anotherSet', '"boolean"'::jsonb),
                                                    'nextSetKind', private.export_shape(p_payload -> 'nextSetKind', '"string"'::jsonb))
    when 'Correction' then jsonb_build_object('type', p_type, 'kind', private.export_shape(p_payload -> 'kind', '"string"'::jsonb), 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb),
                                              'serverNumber', private.export_shape(p_payload -> 'serverNumber', '"number"'::jsonb))
    when 'MatchEnded' then jsonb_build_object('type', p_type, 'reason', private.export_shape(p_payload -> 'reason', '"string"'::jsonb), 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb))
    when 'PointDetailSet' then jsonb_build_object('type', p_type, 'target', private.export_shape(p_payload -> 'target', '"string"'::jsonb),
                                                  'detail', private.export_detail(p_payload -> 'detail'))
    when 'ServerChosen' then jsonb_build_object('type', p_type, 'set', private.export_shape(p_payload -> 'set', '"number"'::jsonb), 'side', private.export_shape(p_payload -> 'side', '"string"'::jsonb))
    when 'ResultCorrected' then jsonb_build_object('type', p_type, 'sets', private.export_shape(p_payload -> 'sets',
                                                   '[{"kind":"string","score":{"A":"number","B":"number"},"tiebreak":{"A":"number","B":"number"}}]'::jsonb))
    when 'GameServersCorrected' then jsonb_build_object('type', p_type, 'servers', private.export_shape(p_payload -> 'servers',
                                                        '[{"set":"number","game":"number"}]'::jsonb))
    else jsonb_build_object('type', p_type)   -- Paused, Resumed, ScorerChanged, PlayerAssigned: nur der Typ
  end) e
  where e.value <> 'null'::jsonb;
$$;

-- ScoreSummary.corrected (Kennzeichnung „Ergebnis manuell korrigiert“) gehört zum exportierten Listen-Cache.
create or replace function private.export_summary(p_value jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_value,'{"winner":"string","reason":"string","setsWonA":"number","setsWonB":"number","sets":[{"a":"number","b":"number","tiebreakA":"number","tiebreakB":"number","matchTiebreak":"boolean"}],"current":{"a":"number","b":"number","tiebreakA":"number","tiebreakB":"number","matchTiebreak":"boolean"},"completedSets":[{"kind":"string","games":{"A":"number","B":"number"},"tiebreak":{"A":"number","B":"number"},"points":{"A":"number","B":"number"},"winner":"string"}],"currentState":{"setIndex":"number","setKind":"string","gameKind":"string","games":{"A":"number","B":"number"},"points":{"A":"number","B":"number"},"display":{"A":"string","B":"string"},"callout":"string","deuceCount":"number","decidingPoint":"boolean"},"lastEventAtMs":"number","eventCount":"number","corrected":"boolean"}'::jsonb);
$$;

revoke all on function private.export_payload(text, jsonb), private.export_summary(jsonb) from public, anon, authenticated, service_role;
