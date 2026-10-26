-- Welle 2: Event-Kanonisierung beim Upload (00700 guard_redacted_names) wird für alle Geräte sichtbar.
-- Bisher ersetzte der Server Namen neuer PlayerAssigned-Events mit redigierter Identität still, ohne E zu
-- erhöhen. Das schreibende Gerät lädt den Redaktionstransfer nur bei E-Wechsel und behielt den Klarnamen.
-- Jetzt: E steigt einmal je Transaktion und Match. Der nächste Batch mit altem E erhält 40001, danach
-- liefern Pull und Transfer das kanonische Event. Das Manifest bleibt unverändert, also event-only wie 220.
-- Bewusst keine Ablehnung statt Bereinigung: E bliebe gleich, der Client fände nach dem Neustart nichts
-- Neues und sendete denselben Batch ohne Ende erneut.
-- Teilnehmer zählen nicht: der Transfer trägt keine Teilnehmer, der Katalog liefert die kanonische Zeile
-- bereits über server_version (touch_parent_match).
-- Bekannte Grenze: Altbestand, der vor 00900 still kanonisiert wurde, erhält keinen Nachtragsschritt.

-- Nur dieser Trigger setzt redacted_at beim Einfügen; Profillöschungen ändern Events per UPDATE und
-- zählen E dort selbst.
create function private.trigger_redaction_epoch() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_done text:=coalesce(current_setting('advantly.trigger_redaction_matches',true),'');
begin
  -- Transaktionslokal. Ein Teilrollback (Savepoint) setzt Zähler und Marker gemeinsam zurück.
  if position(new.match_id::text in v_done)=0 then
    update public.matches set redaction_epoch=redaction_epoch+1 where id=new.match_id;
    perform set_config('advantly.trigger_redaction_matches',v_done||new.match_id::text||',',true);
  end if;
  return null;
end;
$$;

create trigger events_redaction_epoch after insert on public.match_events
  for each row when (new.redacted_at is not null) execute function private.trigger_redaction_epoch();

revoke all on function private.trigger_redaction_epoch() from public,anon,authenticated,service_role;
