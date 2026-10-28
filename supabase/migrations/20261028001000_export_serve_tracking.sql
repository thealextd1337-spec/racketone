-- 0.3.0 „Aufschläge“ (Ja / Nur Doppelfehler / Nein): MatchMeta.serveTracking gehört zur Datenauskunft (K2-Whitelist).
-- Die App schreibt das Feld nur bei „Nur Doppelfehler“; serveStats bleibt für ältere App-Stände erhalten.
create or replace function private.export_meta(p_value jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_value,'{"surface":"string","environment":"string","venue":"string","mixed":"boolean","watchWorkout":"boolean","serveStats":"boolean","serveTracking":"string","courtPace":"number"}'::jsonb);
$$;
