-- Plan 0.6.5 (docs/plan-0.6.4.md §3.1, gebündelt in 0.7.0, Entscheide D1 = Ja, D2 = mit Einladungen): Ein fremder Gast,
-- der mit dem gelöschten Konto verknüpft war (kind 'guest', owner_id ≠ Konto, profile_id = Konto; Zuordnung oder Claim),
-- wird bei der Kontolöschung wieder ein unverknüpfter Gast des Anlegers mit seinem Namen: keine Schreibsperre
-- (player_redacted), kein Manifest- oder Redaktionseintrag, PlayerAssigned-Payloads bleiben. Rechtsgrundlage Art. 6
-- Abs. 1 lit. f (Spielhistorie des Anlegers); Widerspruch bzw. Löschung (Art. 21/17) über admin_redact_guest_name.
-- Unverändert: Plätze, auf denen das Konto direkt stand, und eigene unzugeordnete Gäste → „Gelöschter Spieler“;
-- eigene, Dritten zugeordnete Gäste gehen an die Dritten über (20261022000200).
--
-- Umsetzung: private.release_linked_guests entkoppelt diese Gäste VOR jeder Redaktionsentscheidung, auf beiden Wegen:
--  - Job (Phase A, account_deletion_begin) vor den Grabsteinen; v_retired, das Manifest und damit auch Phase B sehen
--    den Gast nicht mehr. Nach A kann niemand den Gast erneut mit dem Konto verknüpfen (Status 'deleting',
--    Freundschaften weg, require_active scheitert).
--  - Löschweg außerhalb des Jobs (purge_account, Dashboard, admin_purge_due_accounts, Onboarding-Bereinigung):
--    capture_profile_redactions (erster Profil-Löschtrigger) nach account_deletion_settle, vor den Grabsteinen.
-- Die übrigen Wege brauchen dadurch keine Änderung: redact_linked_names und anonymize_profile treffen nur Zeilen mit
-- profile_id = Konto, Phase B nur Zeilen dieses Kontos und seiner v_retired, guard_redacted_names und der späte
-- Manifest-Trigger nur Grabsteine, Schreibsperren und redigierte Slots, die für den Gast nie entstehen. Die beiden
-- Pauschal-Updates des Playernamens sind zusätzlich auf eigene Spieler des Kontos beschränkt.

create function private.release_linked_guests(p_profile uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_guests uuid[];
begin
  select coalesce(array_agg(g.id), '{}') into v_guests from (
    select p.id from public.players p
     where p.profile_id = p_profile and p.owner_id <> p_profile and p.kind = 'guest'
     order by p.id for update) g;
  if cardinality(v_guests) = 0 then return; end if;
  -- Verknüpfung der Plätze ändert sich: neue Epoche in derselben Transaktion (Clients laden die Matches neu).
  update public.matches set redaction_epoch = redaction_epoch + 1
   where id = any(array(select distinct mp.match_id from public.match_participants mp
                        where mp.player_id = any(v_guests) and mp.profile_id = p_profile));
  -- Alle Verknüpfungsarten, auch Altbestand. Der Name bleibt: link_player_participants hat ihn nie geändert.
  update public.match_participants
     set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false
   where player_id = any(v_guests) and profile_id = p_profile;
  update public.players set profile_id = null, assigned_by = null, assigned_at = null, assignment_source = null
   where id = any(v_guests);
end;
$$;
revoke all on function private.release_linked_guests(uuid) from public, anon, authenticated, service_role;

do $$
declare
  v_def text;
  v_seam record;
begin
  for v_seam in select * from (values
    ('private.account_deletion_begin(uuid)',
     $s$  insert into private.redacted_profile_identities values(p_profile) on conflict do nothing;
$s$, $s$  -- 0.7.0: verknüpfte fremde Gäste vor Grabsteinen und Manifestentscheid entkoppeln (Name bleibt).
  perform private.release_linked_guests(p_profile);
  insert into private.redacted_profile_identities values(p_profile) on conflict do nothing;
$s$),
    ('private.account_deletion_begin(uuid)',
     $s$  update public.players set display_name='Gelöschter Spieler' where profile_id=p_profile;
$s$, $s$  update public.players set display_name='Gelöschter Spieler' where profile_id=p_profile and owner_id=p_profile;
$s$),
    ('private.capture_profile_redactions()',
     $s$  perform private.account_deletion_settle(old.id);
$s$, $s$  perform private.account_deletion_settle(old.id);
  -- 0.7.0: wie Phase A, vor Grabsteinen und Manifestentscheid.
  perform private.release_linked_guests(old.id);
$s$),
    ('private.redact_linked_names()',
     $s$  update public.players set display_name='Gelöschter Spieler' where profile_id=old.id;
$s$, $s$  update public.players set display_name='Gelöschter Spieler' where profile_id=old.id and owner_id=old.id;
$s$)
  ) seams(signature, marker, replacement) loop
    v_def := pg_get_functiondef(v_seam.signature::regprocedure);
    if (length(v_def) - length(replace(v_def, v_seam.marker, ''))) / length(v_seam.marker) <> 1 then
      raise exception '%: Seam nicht eindeutig: %', v_seam.signature, v_seam.marker;
    end if;
    execute replace(v_def, v_seam.marker, v_seam.replacement);
  end loop;
end;
$$;

-- Widerspruch bzw. Löschverlangen zu einem Gastnamen (plan-0.6.4 §2): Support-Admin mit AAL2 wie die übrigen
-- zustandsändernden Kontoaktionen (20261023000100), Begründung Pflicht, Audit in ops.account_audit ohne Gastnamen
-- (Ziel = Anleger, damit fällt die Begründung mit dessen Kontolöschung weg). Redigiert den Gast wie die Kontolöschung
-- einen pensionierten Gast: Schreibsperre, Manifest (match_redacted_players, redigierte Slots und Namen), Teilnehmer-,
-- Player- und PlayerAssigned-Namen, Epoche je Match in derselben Transaktion. Eine Verknüpfung wird vorher gelöst,
-- offene Einladungen für den Gast werden zurückgezogen. Ein zweiter Aufruf ändert nichts und schreibt kein Audit.
alter table ops.account_audit drop constraint account_audit_action_check;
alter table ops.account_audit add constraint account_audit_action_check check (action in
  ('account_suspend', 'account_unsuspend', 'account_revoke_devices', 'account_cancel_deletion', 'device_unrevoke',
   'guest_name_redact'));

create function public.admin_redact_guest_name(p_player_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' set lock_timeout = '2s'
  set statement_timeout = '30s' as $$
declare
  v_actor  uuid;
  v_reason text;
  v_owner  uuid;
  v_ids    uuid[] := array[p_player_id];
  v_ms     uuid[];
begin
  perform private.lock_sync();
  v_actor := private.require_support_admin();
  v_reason := private.support_reason(p_reason);
  select p.owner_id into v_owner from public.players p where p.id = p_player_id and p.kind = 'guest' for update;
  if not found then
    perform private.fail('player_not_found');
  end if;
  if private.player_write_redacted(p_player_id::text) then
    return jsonb_build_object('playerId', p_player_id, 'changed', false, 'matchCount', 0);
  end if;

  -- Betroffene Matches wie account_deletion_matches (dieselben indexgestützten Bezüge), für eine ID.
  select coalesce(array_agg(x.match_id order by x.match_id), '{}') into v_ms from (
      select mp.match_id from public.match_participants mp where mp.player_id = any(v_ids)
      union select mp.match_id from public.match_participants mp
        where private.redaction_uuid(mp.slot_id) = any(v_ids) and length(mp.slot_id) between 32 and 41
      union select e.match_id from public.match_events e
        where private.redaction_uuid(e.payload#>>'{player,id}') = any(v_ids) and e.type = 'PlayerAssigned'
      union select e.match_id from public.match_events e
        where private.redaction_uuid(e.payload->>'playerId') = any(v_ids)
          and e.type = 'PlayerAssigned' and e.payload->>'assignmentVersion' = '2' and not (e.payload ? 'player')
      union select e.match_id from public.match_events e
        where private.redaction_lineup_ids(e.payload) && v_ids and e.type = 'MatchCreated'
      union select c.match_id from private.match_player_profile_contexts c where c.player_id = p_player_id
    ) x;

  -- Verknüpfung lösen (wie unassign_player), danach gilt der Gast nirgends mehr als lebende Identität.
  perform private.unlink_player(p_player_id);
  insert into private.player_write_redactions(player_id) values (p_player_id) on conflict do nothing;
  insert into private.redacted_player_identities(player_id) values (p_player_id) on conflict do nothing;
  insert into private.match_redacted_names(match_id, identity_id)
    select unnest(v_ms), p_player_id on conflict do nothing;
  insert into private.match_redacted_players(match_id, player_id)
    select unnest(v_ms), p_player_id on conflict do nothing;
  insert into private.match_redacted_slots(match_id, slot_id)
    select mp.match_id, mp.slot_id from public.match_participants mp
     where mp.match_id = any(v_ms)
       and (mp.player_id = p_player_id
            or (length(mp.slot_id) between 32 and 41 and private.redaction_uuid(mp.slot_id) = p_player_id))
    on conflict do nothing;
  -- guard_redacted_names entkoppelt dabei auch direkt mit Profilen verknüpfte Plätze dieses Gasts.
  update public.match_participants mp set display_name = 'Gelöschter Spieler', deleted_profile = true
   where mp.match_id = any(v_ms)
     and (mp.player_id = p_player_id
          or (length(mp.slot_id) between 32 and 41 and private.redaction_uuid(mp.slot_id) = p_player_id));
  update public.players set display_name = 'Gelöschter Spieler' where id = p_player_id;
  update public.match_events e set
      payload = jsonb_set(e.payload, '{player,displayName}', to_jsonb('Gelöschter Spieler'::text)),
      redacted_at = coalesce(e.redacted_at, now())
   where e.match_id = any(v_ms) and e.type = 'PlayerAssigned' and jsonb_typeof(e.payload->'player') = 'object'
     and private.redaction_uuid(e.payload#>>'{player,id}') = p_player_id;
  update public.matches set redaction_epoch = redaction_epoch + 1 where id = any(v_ms);
  update public.invites set revoked_at = now()
   where player_id = p_player_id and used_at is null and revoked_at is null;

  insert into ops.account_audit(actor_id, target_id, action, reason, changes)
    values (v_actor, v_owner, 'guest_name_redact', v_reason,
      jsonb_build_object('player_id', p_player_id, 'matches', cardinality(v_ms)));
  return jsonb_build_object('playerId', p_player_id, 'changed', true, 'matchCount', cardinality(v_ms));
end;
$$;
revoke all on function public.admin_redact_guest_name(uuid, text) from public, anon, authenticated, service_role;
grant execute on function public.admin_redact_guest_name(uuid, text) to authenticated;
