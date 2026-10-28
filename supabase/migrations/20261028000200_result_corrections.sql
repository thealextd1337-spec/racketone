-- 0.3.0 „Ergebnis bearbeiten“ (Protokoll 5): zwei neue Event-Typen nach Matchende.
--   ResultCorrected      {"sets":[{"kind":"REGULAR","score":{"A":6,"B":4},"tiebreak":{"A":7,"B":5},"incomplete":false},…]}
--   GameServersCorrected {"servers":[{"set":0,"game":3,"player":"<Platz-ID>","side":"A"},…]}
-- Die Engine (core-scoring) faltet sie; der Server speichert sie wie jedes andere Event (append-only, keine eigene
-- Projektion). Feed und Besucheransicht zeigen das korrigierte Ergebnis, weil der Client die Projektion (summary,
-- winner) aus dem korrigierten Zustand hochlädt; neu reicht sanitize_projection die Kennzeichnung `corrected` durch.
-- Redaktion bei Kontolöschung betrifft nur Klarnamen in PlayerAssigned – die neuen Events enthalten keine Namen.
--
-- Ältere Apps (0.2.0, Protokoll 4) können die Typen nicht dekodieren (akzeptiert, Review P2-3): Ein Cloud-Pull scheitert
-- dort für das betroffene Match sicher (Pull-Fehlerzustand des Matches, kein Absturz, keine Datenänderung); die übrigen
-- Matches laufen weiter. Uhr↔Handy schützt der Updatezustand (minV = 5).
--
-- Ersetzt werden nur: export_payload, export_summary, sanitize_projection (vollständig, Attribute wie zuvor) und
-- push_event (per Seam, Signatur/Owner/ACL/Konfiguration bleiben exakt erhalten).

-- Typ-CHECK ohne lange exklusive Sperre: erst NOT VALID anlegen, dann validieren (nur SHARE UPDATE EXCLUSIVE).
alter table public.match_events drop constraint match_events_type_check;
alter table public.match_events add constraint match_events_type_check check (type in (
  'MatchCreated', 'PointWon', 'Undo', 'ContinueDecision', 'Correction', 'MatchEnded',
  'Paused', 'Resumed', 'ScorerChanged', 'PointDetailSet', 'ServerChosen', 'PlayerAssigned',
  'ResultCorrected', 'GameServersCorrected')) not valid;
alter table public.match_events validate constraint match_events_type_check;

-- Nur der Ersteller korrigiert Ergebnis und Aufschläger (wie PlayerAssigned); P2-4.
do $$
declare
  v_definition text;
  v_marker text := E'  elsif v_type = ''PlayerAssigned'' then\n';
begin
  select pg_get_functiondef('private.push_event(uuid,jsonb)'::regprocedure) into v_definition;
  if array_length(string_to_array(v_definition, v_marker), 1) <> 2 then
    raise exception 'Unerwartete push_event-Seam';
  end if;
  execute replace(v_definition, v_marker,
    E'  elsif v_type in (''ResultCorrected'', ''GameServersCorrected'') then\n'
    || E'    -- 0.3.0: Ergebnis-Korrekturen nach Matchende nur vom Ersteller (Client: MatchSession.canCorrectResult).\n'
    || E'    if v_m.created_by <> p_uid then\n'
    || E'      return jsonb_build_object(''id'', v_id, ''result'', ''rejected'', ''reason'', ''correction_forbidden'');\n'
    || E'    end if;\n'
    || v_marker);
end;
$$;

-- Export (K2-Whitelist): Satzstände vollständig, Aufschläger je Spiel mit Seite, aber ohne Spieler-ID (wie ServerChosen).
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
                                                   '[{"kind":"string","score":{"A":"number","B":"number"},"tiebreak":{"A":"number","B":"number"},"incomplete":"boolean"}]'::jsonb))
    when 'GameServersCorrected' then jsonb_build_object('type', p_type, 'servers', private.export_shape(p_payload -> 'servers',
                                                        '[{"set":"number","game":"number","side":"string"}]'::jsonb))
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

-- Feed/Besucher: wie 20261027000400, zusätzlich die Kennzeichnung `corrected` (nur boolean, sonst weggelassen).
create or replace function private.sanitize_projection(p jsonb)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
  v_out  jsonb := '{}'::jsonb;
  v_sets jsonb := '[]'::jsonb;
  v_set  jsonb;
  v_pair jsonb;
  v_flag jsonb;
  v_flags jsonb := '[]'::jsonb;
begin
  if p is null or jsonb_typeof(p) <> 'object' then
    return null;
  end if;
  if jsonb_typeof(p -> 'sets') = 'array' and jsonb_array_length(p -> 'sets') <= 9 then
    for v_set in select value from jsonb_array_elements(p -> 'sets') loop
      -- Client-Upload (ScoreSummary.sets): {"a":6,"b":4,…}; nur a/b, Tiebreak-Details bleiben draußen.
      v_pair := private.int_pair(case when jsonb_typeof(v_set) = 'object'
        then jsonb_build_array(v_set -> 'a', v_set -> 'b') else v_set end, 99);
      if v_pair is null then
        v_sets := null;
        exit;
      end if;
      v_sets := v_sets || jsonb_build_array(v_pair);
    end loop;
    if v_sets is not null then
      v_out := v_out || jsonb_build_object('sets', v_sets);
    end if;
  end if;
  if private.int_pair(p -> 'games', 99) is not null then
    v_out := v_out || jsonb_build_object('games', private.int_pair(p -> 'games', 99));
  end if;
  if jsonb_typeof(p -> 'points') = 'array' and jsonb_array_length(p -> 'points') = 2
     and jsonb_typeof(p -> 'points' -> 0) = 'string' and jsonb_typeof(p -> 'points' -> 1) = 'string'
     and (p -> 'points' ->> 0) ~ '^[0-9A-Za-z]{1,3}$' and (p -> 'points' ->> 1) ~ '^[0-9A-Za-z]{1,3}$' then
    v_out := v_out || jsonb_build_object('points', jsonb_build_array(p -> 'points' ->> 0, p -> 'points' ->> 1));
  end if;
  if jsonb_typeof(p -> 'serving') = 'string' and p ->> 'serving' in ('A', 'B') then
    v_out := v_out || jsonb_build_object('serving', p ->> 'serving');
  end if;
  if jsonb_typeof(p -> 'flags') = 'array' and jsonb_array_length(p -> 'flags') <= 6 then
    for v_flag in select value from jsonb_array_elements(p -> 'flags') loop
      if jsonb_typeof(v_flag) = 'string' and (v_flag #>> '{}') ~ '^[A-Z_]{1,24}$' then
        v_flags := v_flags || jsonb_build_array(v_flag #>> '{}');
      end if;
    end loop;
    v_out := v_out || jsonb_build_object('flags', v_flags);
  end if;
  if jsonb_typeof(p -> 'status') = 'string'
     and p ->> 'status' in ('in_progress', 'paused', 'awaiting_decision', 'finished') then
    v_out := v_out || jsonb_build_object('status', p ->> 'status');
  end if;
  if jsonb_typeof(p -> 'winner') = 'string' and p ->> 'winner' in ('A', 'B') then
    v_out := v_out || jsonb_build_object('winner', p ->> 'winner');
  end if;
  if jsonb_typeof(p -> 'tiebreak') = 'boolean' then
    v_out := v_out || jsonb_build_object('tiebreak', (p ->> 'tiebreak')::boolean);
  end if;
  if jsonb_typeof(p -> 'scorerConnected') = 'boolean' then
    v_out := v_out || jsonb_build_object('scorerConnected', (p ->> 'scorerConnected')::boolean);
  end if;
  if jsonb_typeof(p -> 'updatedAtMs') = 'number' and (p ->> 'updatedAtMs') ~ '^[0-9]{1,15}$' then
    v_out := v_out || jsonb_build_object('updatedAtMs', (p ->> 'updatedAtMs')::bigint);
  end if;
  if jsonb_typeof(p -> 'corrected') = 'boolean' then
    v_out := v_out || jsonb_build_object('corrected', (p ->> 'corrected')::boolean);
  end if;
  return v_out;
end;
$$;
