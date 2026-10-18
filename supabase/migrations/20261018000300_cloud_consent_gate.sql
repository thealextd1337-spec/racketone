-- Gemeinsame Cloud-Grenze: Zustimmung ergänzt U1/K4, ohne Kontorechte zu sperren.
-- Keine Textfreigabe, keine Seed-/Auth-Sitzungsänderung und keine neuen öffentlichen RPCs.
create or replace function private.require_current_consents() returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid;
begin
  perform private.require_account_read_committed();
  perform private.lock_sync();
  -- Eigene VOLATILE-Anweisungen nach dem Lock: weder Sitzung noch Zustimmung aus altem Snapshot.
  v_uid := private.require_account_session();
  if not private.has_current_consents(v_uid) then
    perform private.fail('consent_required', '42501');
  end if;
  return v_uid;
end;
$$;

create or replace function private.require_cloud_profile()
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_uid uuid;
begin
  perform private.require_cloud_isolation();
  perform private.lock_sync();
  v_uid := private.require_active();
  -- U1-Fehlervertrag für fehlende/fremde/widerrufene Auth-Sitzungen erhalten.
  perform private.cloud_auth_session();
  perform private.require_current_consents();
  return v_uid;
end;
$$;

-- RLS bleibt rein lesend. Wartende direkte Writes werden zusätzlich vom bestehenden
-- VOLATILE-Statement-Trigger über require_cloud_binding()/require_cloud_profile() geprüft.
create or replace function private.cloud_profile_allowed(p_profile uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and private.has_current_consents(p_profile);
$$;

revoke all on function private.require_current_consents(), private.require_cloud_profile(),
  private.cloud_profile_allowed(uuid) from public, anon, authenticated, service_role;
