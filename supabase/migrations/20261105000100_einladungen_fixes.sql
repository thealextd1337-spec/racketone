-- Plan Einladungen §2.1 S3/S4 (Release 0.6.4), nur Fehlerkorrekturen an redeem_invite. Die App ruft den RPC noch nicht auf.
-- S3: Ein vom Anleger gelöschter Gast ließ sich per offenem Claim-Link noch übernehmen. Jetzt `invalid_invite` (wie ein
--     unbekannter Link), der Gast-Datensatz ist dabei gesperrt. Redigierte Gäste lehnt schon die 007-Sperre ab.
-- S4: Ein Freundes-Link an jemanden, der schon befreundet ist, wurde verbraucht und schickte ein falsches
--     `friend_accepted`. Jetzt `{kind, friendId, alreadyFriends: true}` ohne Verbrauch und ohne Push. Allgemein geht
--     `friend_accepted` nur noch raus, wenn die Freundschaft durch das Einlösen neu entsteht (auch beim Claim unter Freunden).
-- Blockierte (beide Richtungen) lehnt redeem_invite schon heute mit `invalid_invite` ab; unverändert.
-- Änderung am aktuellen Stand per Seam, keine Kopie des Funktionskörpers; Signatur, Owner, ACL, security definer und
-- search_path bleiben.
do $$
declare
  v_def text := pg_get_functiondef('public.redeem_invite(text)'::regprocedure);
  v_seam record;
begin
  for v_seam in select * from (values
    ($s$  if v_inv.kind = 'claim_player' then
$s$, $s$  -- 0.6.4 S4: unter Freunden bleibt ein Freundes-Link gültig, kein Push.
  if v_inv.kind = 'friend' and private.are_friends(v_uid, v_inv.created_by) then
    return jsonb_build_object('kind', v_inv.kind, 'friendId', v_inv.created_by, 'alreadyFriends', true);
  end if;

  if v_inv.kind = 'claim_player' then
    -- 0.6.4 S3: gelöschter Gast ist nicht mehr übernehmbar.
    perform 1 from public.players p where p.id = v_inv.player_id and p.deleted_at is null for update;
    if not found then
      perform private.fail('invalid_invite');
    end if;
$s$),
    ($s$  perform private.notify(v_inv.created_by, 'friend_accepted', jsonb_build_object('profileId', v_uid));
$s$, $s$  -- 0.6.4: nur bei neu entstandener Freundschaft (found des Inserts davor)
  if found then
    perform private.notify(v_inv.created_by, 'friend_accepted', jsonb_build_object('profileId', v_uid));
  end if;
$s$)
  ) seams(marker, replacement) loop
    if (length(v_def) - length(replace(v_def, v_seam.marker, ''))) / length(v_seam.marker) <> 1 then
      raise exception 'redeem_invite: Seam nicht eindeutig: %', v_seam.marker;
    end if;
    v_def := replace(v_def, v_seam.marker, v_seam.replacement);
  end loop;
  execute v_def;
end;
$$;
