-- Vertragsänderung „späte Referenzen auf global redigierte Spieler“ (p2-fortsetzung, Integratorentscheidung).
-- Bisher füllte nur capture_profile_redactions (00700) das Manifest, und zwar zum Löschzeitpunkt aus den Matches,
-- die X da schon referenzierten. Ein späterer Upload mit X lieferte redactedPlayerIds=[], obwohl der Server X
-- als global redigiert kennt; der Client behielt den Verzeichnisnamen.
-- Jetzt: Beim Einfügen von match_events (Legacy player.id, V2 playerId, Genesis-Lineup A/B) und match_participants
-- (player_id, UUID-Slot) wird (match,X) in match_redacted_players eingetragen, wenn genau der globale Zweig von
-- redacted_event_identity gilt: redacted_player_identity(X) and not redaction_live_profile_context(match,X).
-- Dieselben Extraktoren wie die Löscherfassung (00700).
-- Bewusst NICHT in match_redacted_names: das wäre ein dauerhafter matchlokaler Beleg und würde spätere Events einer
-- lebenden Person mit eindeutigem Profilkontext im selben Match redigieren (die von N1–N7 geschlossene Klasse).
-- Die Namensbereinigung leistet serverseitig bereits die globale Identität (00700), der Client braucht nur das Manifest.
-- Bleiben event-only: reine player_write_redactions, Profil-IDs, matchlokale Namen und ID-Kollisionen mit einem
-- direkten lebenden Profil-FK im selben Match. Das Manifest enthält weiter nur positiv belegte globale Player-IDs.
-- E steigt einmal je Transaktion und Match und nur, wenn eine neue Manifestzeile entsteht. Der Marker ist derselbe
-- wie in 00900, also kein Doppelschritt, wenn dasselbe Insert auch ein redigiertes Event schreibt.
-- Teilnehmer zählen auch beim Upsert (ON CONFLICT DO UPDATE von player_id): auch das ist eine späte Referenz.
-- Bekannte Grenzen: Ein Teilnehmer, dessen playerId der Writer verwirft (player_not_owned), hinterlässt keine
-- Referenz und damit keinen Eintrag. Altbestand späterer Uploads vor dieser Migration erhält keinen Nachtrag.

create function private.capture_late_redacted_players(p_match uuid,p_ids uuid[]) returns void
language plpgsql security definer set search_path = '' as $$
declare v_ids uuid[]; v_new boolean; v_done text;
begin
  select coalesce(array_agg(r.player_id),'{}'::uuid[]) into v_ids from private.redacted_player_identities r
    where r.player_id=any(p_ids) and not private.redaction_live_profile_context(p_match,r.player_id);
  if cardinality(v_ids)=0 then return; end if;
  with added as (
    insert into private.match_redacted_players(match_id,player_id) select p_match,unnest(v_ids)
      on conflict do nothing returning 1
  ) select exists(select 1 from added) into v_new;
  v_done:=coalesce(current_setting('advantly.trigger_redaction_matches',true),'');
  if v_new and position(p_match::text in v_done)=0 then
    update public.matches set redaction_epoch=redaction_epoch+1 where id=p_match;
    perform set_config('advantly.trigger_redaction_matches',v_done||p_match::text||',',true);
  end if;
end;
$$;

create function private.trigger_late_redaction_manifest() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_ids uuid[];
begin
  if tg_table_name='match_participants' then
    -- Textslots können keine UUID sein (32–41 Zeichen); spart redaction_uuid den Ausnahmepfad.
    v_ids:=array[new.player_id,case when length(new.slot_id) between 32 and 41 then private.redaction_uuid(new.slot_id) end];
  elsif new.type='MatchCreated' then
    v_ids:=private.redaction_lineup_ids(new.payload);
  else
    v_ids:=array[private.redaction_uuid(new.payload#>>'{player,id}'),
      case when new.payload->>'assignmentVersion'='2' and not (new.payload ? 'player')
        then private.redaction_uuid(new.payload->>'playerId') end];
  end if;
  -- Schnellpfad ohne Treffer: eine PK-Abfrage, kein Insert und damit kein Statementtrigger.
  if exists(select 1 from private.redacted_player_identities r where r.player_id=any(v_ids)) then
    perform private.capture_late_redacted_players(new.match_id,v_ids);
  end if;
  return null;
end;
$$;

create trigger events_late_redaction_manifest after insert on public.match_events
  for each row when (new.type in ('PlayerAssigned','MatchCreated')) execute function private.trigger_late_redaction_manifest();
create trigger participants_late_redaction_manifest after insert or update of player_id,slot_id on public.match_participants
  for each row execute function private.trigger_late_redaction_manifest();

revoke all on function private.capture_late_redacted_players(uuid,uuid[]),private.trigger_late_redaction_manifest()
  from public,anon,authenticated,service_role;
