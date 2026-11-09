-- 0.7.0 Review: redeem_invite meldete bei einem redigierten Gast `player_redacted`. Wer einen Claim-Link besitzt, konnte
-- daran per direktem API-Aufruf erkennen, dass der Gast widersprochen hat bzw. gelöscht wurde. Jetzt `invalid_invite`
-- wie bei einem gelöschten oder unbekannten Gast (preview_invite liefert schon nur {status: invalid}); kein Orakel.
-- assign_player/create_invite behalten `player_redacted`: Dort fragt nur der Anleger des Gasts selbst.
-- Nur der Fehlergrund ändert sich, die 007-Sperre bleibt einzeilig (220_…_concurrency.py entfernt sie per Regex).
do $$
declare
  v_def text := pg_get_functiondef('public.redeem_invite(text)'::regprocedure);
  v_marker text := $s$perform private.fail('player_redacted');$s$;
begin
  if (length(v_def) - length(replace(v_def, v_marker, ''))) / length(v_marker) <> 1 then
    raise exception 'redeem_invite: Seam nicht eindeutig: %', v_marker;
  end if;
  execute replace(v_def, v_marker, $s$perform private.fail('invalid_invite');$s$);
end;
$$;
