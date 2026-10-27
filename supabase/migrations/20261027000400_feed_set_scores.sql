-- Feed/Visitor: Satzstände im tatsächlichen Client-Format. Der Client lädt `summary` als ScoreSummary hoch
-- (sets = [{"a":6,"b":4,"tiebreakA":…}]); sanitize_projection kannte nur [[a,b]] und verwarf die Sätze, Feed und
-- Visitor sahen nur Sieger/Status. Bereinigt wird beim Lesen (sync_pull für Nicht-Mitglieder, visitor_view), daher
-- wirkt die Korrektur sofort auf bereits hochgeladene Projektionen; kein Backfill. Ausgabe weiter [[a,b],…] mit
-- denselben Grenzen (≤ 9 Sätze, 0–99, ein ungültiger Satz verwirft alle), altes Paarformat bleibt gültig.
-- STABLE wie seit 20261016000400 (create or replace übernähme sonst wieder IMMUTABLE).

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
  return v_out;
end;
$$;
