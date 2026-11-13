-- Feed/Zuschauer: Match-Tiebreak-Sätze kennzeichnen. Bisher kamen nur [[a,b],…] an; ein beendeter Champions-Tiebreak
-- (10:8) war von einem Vorteilssatz (10:8 Games) nicht zu unterscheiden. Neu: `matchTiebreakSets` = 0-basierte Indizes
-- in `sets`, aus `matchTiebreak` am Satz (Summary) oder der Liste im Live-Stand. Ausgabe von `sets` unverändert, ältere
-- Clients ignorieren das neue Feld. Wirkt beim Lesen, daher auch für schon hochgeladene Summaries; kein Backfill.
-- Sonst wie 20261028000200 (vollständig ersetzt, STABLE, search_path leer).

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
  v_index integer := 0;
  v_mtb   jsonb := '[]'::jsonb;
begin
  if p is null or jsonb_typeof(p) <> 'object' then
    return null;
  end if;
  if jsonb_typeof(p -> 'sets') = 'array' and jsonb_array_length(p -> 'sets') <= 9 then
    for v_set in select value from jsonb_array_elements(p -> 'sets') loop
      -- Client-Upload (ScoreSummary.sets): {"a":6,"b":4,…}; nur a/b, Tiebreak-Punkte bleiben draußen.
      v_pair := private.int_pair(case when jsonb_typeof(v_set) = 'object'
        then jsonb_build_array(v_set -> 'a', v_set -> 'b') else v_set end, 99);
      if v_pair is null then
        v_sets := null;
        exit;
      end if;
      v_sets := v_sets || jsonb_build_array(v_pair);
      -- Summary-Format: Match-Tiebreak steht am Satz.
      if jsonb_typeof(v_set) = 'object' and v_set -> 'matchTiebreak' = 'true'::jsonb then
        v_mtb := v_mtb || to_jsonb(v_index);
      end if;
      v_index := v_index + 1;
    end loop;
    if v_sets is not null then
      v_out := v_out || jsonb_build_object('sets', v_sets);
      -- Live-Format: Paare plus Indexliste; nur gültige, eindeutige Indizes, aufsteigend.
      if jsonb_typeof(p -> 'matchTiebreakSets') = 'array' and jsonb_array_length(p -> 'matchTiebreakSets') <= 9 then
        v_mtb := v_mtb || coalesce((select jsonb_agg(e) from jsonb_array_elements(p -> 'matchTiebreakSets') e
                                    where jsonb_typeof(e) = 'number' and (e #>> '{}') ~ '^[0-9]$'), '[]'::jsonb);
      end if;
      v_mtb := coalesce((select jsonb_agg(distinct (e #>> '{}')::integer order by (e #>> '{}')::integer)
                         from jsonb_array_elements(v_mtb) e
                         where (e #>> '{}')::integer < jsonb_array_length(v_sets)), '[]'::jsonb);
      if jsonb_array_length(v_mtb) > 0 then
        v_out := v_out || jsonb_build_object('matchTiebreakSets', v_mtb);
      end if;
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
