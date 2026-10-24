-- K5 Kontosupport: exakte Kontosuche, minimaler Kontostatus und begrenzte Admin-Aktionen mit eigenem Audit.
-- Rollen nach Planungsstandard K5: `support` liest Status, zustandsändernde Aktionen nur `admin`. Keine neuen Rechte,
-- Mitglieder provisioniert weiterhin der Betreiber. Jede RPC prüft Mitgliedschaft und AAL2 bei jedem Aufruf frisch
-- (private.require_support), schreibende zusätzlich erst nach lock_sync (frischer Snapshot).
-- K4-U (Entscheid 24.10.2026): Widerruf eines Geräts aufheben, durch ein anderes eigenes Gerät oder durch admin mit
-- Meldung nach dem Widerruf; nie durch das widerrufene Gerät selbst. Geräte- und Installations-ID bleiben erhalten.
-- Sperre: wirkt auf die eigenen Cloud-/Sync-/Social-Wege des Kontos (require_active, RLS-Gate), nicht auf Export,
-- Löschantrag, Zustimmung, eigene Geräteverwaltung und Support (DSGVO). Sichtbarkeit für andere bleibt unverändert,
-- daher keine Epochenänderung. Kein Zugriff auf auth.sessions/auth.users; GoTrue-Ban bleibt ein Betreiberschritt.

create table private.account_suspensions (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  suspended_at timestamptz not null default clock_timestamp()
);
alter table private.account_suspensions enable row level security;
revoke all on private.account_suspensions from public, anon, authenticated, service_role;
create trigger zz_lock_sync before insert or update or delete on private.account_suspensions
  for each statement execute function private.lock_sync_statement();

-- Wie ops.support_audit: kurze Begründung, nur Zählwerte/IDs in changes. Zielkonto gelöscht → Bezug und Freitext weg.
create table ops.account_audit (
  id bigint generated always as identity primary key,
  actor_id uuid references auth.users(id) on delete set null,
  target_id uuid references auth.users(id) on delete set null,
  action text not null check (action in
    ('account_suspend', 'account_unsuspend', 'account_revoke_devices', 'account_cancel_deletion', 'device_unrevoke')),
  reason text,
  changes jsonb not null default '{}'::jsonb check (jsonb_typeof(changes) = 'object'),
  created_at timestamptz not null default now()
);
create index account_audit_target on ops.account_audit(target_id);
create index account_audit_cleanup on ops.account_audit(created_at);
alter table ops.account_audit enable row level security;
revoke all on ops.account_audit from public, anon, authenticated, service_role;
grant select on ops.account_audit to service_role;

-- Die FK-Aktion ON DELETE SET NULL ist ein UPDATE und löst diesen Trigger aus. Geräte-IDs fallen mit dem Zielbezug weg.
create function private.account_audit_decouple() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.reason := null;
  new.changes := new.changes - array['device_id', 'caller_device_id'];
  return new;
end;
$$;
create trigger account_audit_decouple before update of target_id on ops.account_audit
  for each row when (old.target_id is not null and new.target_id is null)
  execute function private.account_audit_decouple();

-- Wie ops.support_audit schon in Phase A: Nur account_deletion_begin setzt 'deleting'. Ziel entkoppeln leert per
-- account_audit_decouple auch die Begründung; ein gelöschter Admin verliert den Akteursbezug.
create function private.account_audit_deleting() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update ops.account_audit set target_id = null where target_id = new.id;
  update ops.account_audit set actor_id = null where actor_id = new.id;
  return null;
end;
$$;
create trigger profiles_account_audit_deleting after update of status on public.profiles
  for each row when (new.status = 'deleting' and old.status is distinct from 'deleting')
  execute function private.account_audit_deleting();

create function private.is_suspended(p_profile uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from private.account_suspensions s where s.profile_id = p_profile);
$$;

-- Wie 20261015000400, zusätzlich die Sperre. Eigener Grund vor profile_not_active, damit die App ihn anzeigen kann.
create or replace function private.require_active()
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    perform private.fail('not_authenticated', '42501');
  end if;
  if private.is_suspended(v_uid) then
    perform private.fail('account_suspended', '42501');
  end if;
  if not private.is_active(v_uid) then
    perform private.fail('profile_not_active', '42501');
  end if;
  perform private.lock_sync();
  return v_uid;
end;
$$;

-- RLS-Gate (has_cloud_binding) der eigenen Sitzung; is_active bleibt für die Sicht anderer unverändert.
create or replace function private.cloud_profile_allowed(p_profile uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile) and private.has_current_consents(p_profile)
    and not private.is_suspended(p_profile);
$$;

-- Wie 20261018000200, zusätzlich `suspended` für den BLOCKED-Grund in der App. Bleibt für gesperrte Konten erreichbar.
create or replace function public.my_auth_profile() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_profile public.profiles%rowtype;
begin
  perform private.lock_sync();
  if v_uid is null then perform private.fail('not_authenticated', '42501'); end if;
  select * into v_profile from public.profiles where id = v_uid;
  if not found then perform private.fail('profile_missing'); end if;
  perform private.require_account_session();
  return jsonb_build_object('profileId', v_uid, 'onboarded', v_profile.status = 'active',
    'deletionRequested', v_profile.status = 'deletion_requested',
    'consentStatus', private.consent_state(v_uid, 'en')->>'status',
    'suspended', private.is_suspended(v_uid));
end;
$$;

create function private.require_support_admin() returns uuid
language plpgsql stable security definer set search_path = '' as $$
declare v_actor uuid := private.require_support();
begin
  if not exists (select 1 from ops.support_members m where m.id = v_actor and m.role = 'admin' and m.revoked_at is null) then
    perform private.fail('support_admin_required');
  end if;
  return v_actor;
end;
$$;

-- Gleiche Regel wie admin_support_update: 1 bis 500 Zeichen nach Unicode-Trim.
create function private.support_reason(p_reason text) returns text
language plpgsql security definer set search_path = '' as $$
declare v_reason text := btrim(p_reason, E' \t\n\r\f' || chr(11) || chr(160) || chr(5760) || chr(8192) || chr(8193)
  || chr(8194) || chr(8195) || chr(8196) || chr(8197) || chr(8198) || chr(8199) || chr(8200) || chr(8201) || chr(8202)
  || chr(8232) || chr(8233) || chr(8239) || chr(8287) || chr(12288) || chr(65279));
begin
  if v_reason is null or length(v_reason) not between 1 and 500 then perform private.fail('support_invalid'); end if;
  return v_reason;
end;
$$;

-- Minimaler Kontostatus: keine E-Mail, kein Land, keine Matches/Health/Freunde/Gerätemodelle/Tokens. Widerrufene Geräte
-- nur mit ID, Art und Zeitpunkt, damit admin das freizugebende Gerät wählen kann (K4-U).
create function private.account_status(p_profile uuid) returns jsonb
language sql stable security definer set search_path = '' set timezone = 'UTC' as $$
  select jsonb_build_object('profileId', p.id, 'alias', p.alias, 'status', p.status,
    'suspended', su.profile_id is not null, 'suspendedAt', su.suspended_at,
    'deletionRequestedAt', s.deletion_requested_at,
    'earliestPurgeAt', s.deletion_requested_at + interval '14 days',
    -- Dieselben Bedingungen wie cancel_account_deletion bzw. account_deletion_status_v1.
    'canCancelDeletion', p.status = 'deletion_requested' and o.profile_id is not null
      and coalesce(s.deletion_requested_at > clock_timestamp() - interval '14 days', false),
    'activeDevices', (select count(*) from public.devices d where d.profile_id = p.id and d.revoked_at is null),
    'lastActivityDay', date_trunc('day', greatest(
      (select max(d.last_seen_at) from public.devices d where d.profile_id = p.id),
      (select max(b.created_at) from private.cloud_session_bindings b where b.profile_id = p.id)))::date,
    'consentStatus', private.consent_state(p.id, 'en')->>'status',
    'consentVersions', (select jsonb_object_agg(t.document_type, (select h.version from private.consent_history h
        where h.profile_id = p.id and h.document_type = t.document_type and h.accepted order by h.id desc limit 1))
      from (values ('TERMS'), ('PRIVACY')) t(document_type)),
    -- shortcut: höchstens die 20 jüngsten Widerrufe; reicht für den Pilot, sonst Liste blättern.
    'revokedDevices', (select coalesce(jsonb_agg(jsonb_build_object('deviceId', r.id, 'kind', r.kind,
        'platform', r.platform, 'revokedAt', r.revoked_at) order by r.revoked_at desc, r.id), '[]'::jsonb)
      from (select d.id, d.kind, d.platform, greatest(d.revoked_at, i.revoked_at) as revoked_at from public.devices d
        left join private.cloud_installations i on i.device_id = d.id
        where d.profile_id = p.id and (d.revoked_at is not null or i.revoked_at is not null)
        order by 4 desc, d.id limit 20) r))
  from public.profiles p
  left join public.profile_settings s on s.profile_id = p.id
  left join private.account_suspensions su on su.profile_id = p.id
  left join private.consent_deletion_origins o on o.profile_id = p.id
  where p.id = p_profile;
$$;

-- Ziel einer Admin-Aktion: existierend, nicht in Löschung (Phase A wirkt wie gelöscht), nie das eigene Konto.
create function private.account_target(p_profile uuid, p_actor uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if p_profile is null or p_profile = p_actor then perform private.fail('support_invalid'); end if;
  perform 1 from public.profiles p where p.id = p_profile and p.status <> 'deleting' for update;
  if not found then perform private.fail('account_not_found'); end if;
end;
$$;

-- Exakt: UUID, Freundescode (Schreibweise wie find_profile) oder Alias. Kein Teiltreffer, keine Liste. Konten in
-- Löschung (ab Phase A für alle anderen Wege weg) und das eigene Konto gelten als nicht gefunden.
create function public.admin_account_lookup(p_query text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_actor uuid := private.require_support();
  v_query text := btrim(p_query);
  v_ids uuid[];
  v_role text;
  v_account jsonb;
begin
  if v_query is null or length(v_query) not between 1 and 120 then perform private.fail('support_invalid'); end if;
  if v_query ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    v_ids := array(select p.id from public.profiles p
      where p.id = v_query::uuid and p.status <> 'deleting' and p.id <> v_actor);
  else
    v_ids := array(select p.id from public.profiles p
      where (p.friend_code = upper(replace(v_query, '-', '')) or p.alias = v_query)
        and p.status <> 'deleting' and p.id <> v_actor limit 2);
  end if;
  if cardinality(v_ids) = 0 then perform private.fail('account_not_found'); end if;
  if cardinality(v_ids) > 1 then perform private.fail('account_ambiguous'); end if;
  v_role := (select m.role from ops.support_members m where m.id = v_actor);
  v_account := private.account_status(v_ids[1]);
  -- Geräte-IDs braucht nur admin für die Freigabe; support sieht Art, Plattform und Zeitpunkt.
  if v_role is distinct from 'admin' then
    v_account := jsonb_set(v_account, '{revokedDevices}', (select coalesce(jsonb_agg(d.value - 'deviceId' order by d.n),
      '[]'::jsonb) from jsonb_array_elements(v_account -> 'revokedDevices') with ordinality d(value, n)));
  end if;
  return jsonb_build_object('viewerRole', v_role, 'account', v_account);
end;
$$;

create function public.admin_account_set_suspended(p_profile_id uuid, p_suspended boolean, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_actor uuid;
  v_reason text;
  v_changed integer;
begin
  perform private.lock_sync();
  v_actor := private.require_support_admin();
  v_reason := private.support_reason(p_reason);
  if p_suspended is null then perform private.fail('support_invalid'); end if;
  perform private.account_target(p_profile_id, v_actor);
  if p_suspended then
    insert into private.account_suspensions(profile_id) values (p_profile_id) on conflict do nothing;
  else
    delete from private.account_suspensions where profile_id = p_profile_id;
  end if;
  get diagnostics v_changed = row_count;
  -- Audit nur bei Zustandsänderung; ein Doppelklick erzeugt keinen zweiten Eintrag.
  if v_changed > 0 then
    insert into ops.account_audit(actor_id, target_id, action, reason)
      values (v_actor, p_profile_id, case when p_suspended then 'account_suspend' else 'account_unsuspend' end, v_reason);
  end if;
  return private.account_status(p_profile_id);
end;
$$;

-- Alle Installationen, Cloud-Sitzungsbindungen und Geräte samt Push-Token. Auth-Sitzungen bleiben unberührt:
-- Neue Anmeldung bindet eine neue Installation, alte Geräte-IDs bleiben gesperrt (wie cloud_revoke_device_v1).
create function public.admin_account_revoke_devices(p_profile_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_actor uuid;
  v_reason text;
  v_installations integer;
  v_sessions integer;
  v_devices integer;
begin
  perform private.lock_sync();
  v_actor := private.require_support_admin();
  v_reason := private.support_reason(p_reason);
  perform private.account_target(p_profile_id, v_actor);
  update private.cloud_installations set revoked_at = clock_timestamp()
    where profile_id = p_profile_id and revoked_at is null;
  get diagnostics v_installations = row_count;
  update private.cloud_session_bindings set revoked_at = clock_timestamp()
    where profile_id = p_profile_id and revoked_at is null;
  get diagnostics v_sessions = row_count;
  update public.devices set revoked_at = coalesce(revoked_at, clock_timestamp()), push_token = null, push_provider = null
    where profile_id = p_profile_id and (revoked_at is null or push_token is not null or push_provider is not null);
  get diagnostics v_devices = row_count;
  if v_installations + v_sessions + v_devices > 0 then
    insert into ops.account_audit(actor_id, target_id, action, reason, changes)
      values (v_actor, p_profile_id, 'account_revoke_devices', v_reason,
        jsonb_build_object('devices', v_devices, 'installations', v_installations, 'sessions', v_sessions));
  end if;
  return private.account_status(p_profile_id);
end;
$$;

-- Dokumentierter Nutzerwunsch (Entscheide 24.10.2026): Pflicht ist eine Meldung, die nach p_since entstand, vom Zielkonto
-- oder als Gastmeldung. Bei einer Gastmeldung belegt die Begründung die Zuordnung; der Server prüft dafür nur eine
-- Mindestlänge (Inhalt prüft er nicht). Fehler mit Präfix je Aktion (deletion_/device_ticket_*); liefert die Meldungsart.
create function private.account_ticket_kind(p_profile uuid, p_ticket uuid, p_since timestamptz, p_reason text,
  p_prefix text) returns text
language plpgsql stable security definer set search_path = '' as $$
declare v_ticket ops.support_tickets%rowtype;
begin
  if p_ticket is null then perform private.fail(p_prefix || '_ticket_required'); end if;
  select * into v_ticket from ops.support_tickets t where t.id = p_ticket;
  if not found or v_ticket.owner_id <> p_profile then perform private.fail(p_prefix || '_ticket_invalid'); end if;
  if v_ticket.created_at < p_since then perform private.fail(p_prefix || '_ticket_stale'); end if;
  if v_ticket.owner_id is null and length(p_reason) < 20 then perform private.fail(p_prefix || '_ticket_evidence_required'); end if;
  return case when v_ticket.owner_id is null then 'guest' else 'account' end;
end;
$$;

-- Löschrücknahme nur mit Meldung nach dem Löschantrag. Frist und Wiederherstellung exakt wie cancel_account_deletion
-- (20261018000400); 300 prüft die gleiche Bedingung.
create function public.admin_account_cancel_deletion(p_profile_id uuid, p_ticket_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC' as $$
declare
  v_actor uuid;
  v_reason text;
  v_previous text;
  v_requested timestamptz;
  v_kind text;
begin
  perform private.lock_sync();
  v_actor := private.require_support_admin();
  v_reason := private.support_reason(p_reason);
  perform private.account_target(p_profile_id, v_actor);
  if p_ticket_id is null then perform private.fail('deletion_ticket_required'); end if;
  select o.previous_status, s.deletion_requested_at into v_previous, v_requested from private.consent_deletion_origins o
    join public.profiles p on p.id = o.profile_id join public.profile_settings s on s.profile_id = p.id
    where p.id = p_profile_id and p.status = 'deletion_requested' and s.deletion_requested_at > clock_timestamp() - interval '14 days'
    for update of p, o, s;
  if not found then perform private.fail('no_deletion_pending'); end if;
  v_kind := private.account_ticket_kind(p_profile_id, p_ticket_id, v_requested, v_reason, 'deletion');
  update public.profiles set status = v_previous where id = p_profile_id;
  delete from private.consent_deletion_origins where profile_id = p_profile_id;
  update public.profile_settings set deletion_requested_at = null, updated_at = now() where profile_id = p_profile_id;
  perform private.bump_visibility(private.friend_ids(p_profile_id));
  insert into ops.account_audit(actor_id, target_id, action, reason, changes)
    values (v_actor, p_profile_id, 'account_cancel_deletion', v_reason,
      jsonb_build_object('ticket_id', p_ticket_id, 'ticket_kind', v_kind));
  return private.account_status(p_profile_id);
end;
$$;

-- K4-U: Widerrufszeitpunkt (Bezug der Ticketregel) eines eigenen widerrufenen Geräts, sperrt die Gerätezeile. Unbekannt
-- und fremd ergeben denselben Grund, damit sich fremde Geräte-IDs nicht ausforschen lassen.
create function private.device_revoked_at(p_profile uuid, p_device uuid) returns timestamptz
language plpgsql security definer set search_path = '' as $$
declare v_revoked timestamptz;
begin
  perform 1 from public.devices d where d.id = p_device and d.profile_id = p_profile for update;
  if not found then perform private.fail('device_not_found'); end if;
  select greatest(d.revoked_at, (select i.revoked_at from private.cloud_installations i where i.device_id = d.id))
    into v_revoked from public.devices d where d.id = p_device;
  if v_revoked is null then perform private.fail('device_not_revoked'); end if;
  return v_revoked;
end;
$$;

-- Hebt nur den Widerruf von Gerät und Installation auf; dieselbe Geräte- und Installations-ID samt Proof gelten wieder.
-- Sitzungsbindungen bleiben Tombstones: Das Gerät braucht eine neue Anmeldung (Fünf-Minuten-Grenze der Erstbindung).
-- Zugriffsänderung → neue Generation G: Kein Gerät schreibt mit einem Stand von vor der Freigabe weiter
-- (restart_required, frischer Pull). E bleibt, am Matchinhalt ändert sich nichts. Push-Token setzt erst die App neu.
create function private.device_restore(p_profile uuid, p_device uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.devices set revoked_at = null where id = p_device and profile_id = p_profile;
  update private.cloud_installations set revoked_at = null where device_id = p_device and profile_id = p_profile;
  perform private.bump_visibility(array[p_profile]);
end;
$$;

-- K4-U Nutzerweg: ein anderes eigenes, gebundenes und nicht widerrufenes Gerät (Installationsnachweis wie Push/Pull).
-- Selbst-Aufhebung (dieselbe Installation oder das Gerät der eigenen Sitzungsbindung) hat einen eigenen Grund vor allen
-- übrigen Prüfungen. require_cloud_binding schließt Sperre, fehlende Zustimmung und ein widerrufenes aufrufendes Gerät.
create function public.cloud_unrevoke_device_v1(p_device_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_auth record;
  v_cap record;
  v_caller uuid;
  v_revoked timestamptz;
begin
  perform private.require_cloud_isolation();
  perform private.lock_sync();
  select * into strict v_auth from private.cloud_auth_session();
  select * into strict v_cap from private.cloud_capability();
  if exists (select 1 from private.cloud_installations i where i.profile_id = v_auth.profile_id
               and i.installation_id = v_cap.installation_id and i.device_id = p_device_id)
     or exists (select 1 from private.cloud_session_bindings b
               where b.session_id = v_auth.session_id and b.device_id = p_device_id) then
    perform private.fail('device_unrevoke_self', '42501');
  end if;
  if exists (select 1 from public.profiles p where p.id = v_auth.profile_id and p.status = 'deletion_requested') then
    perform private.fail('account_deletion_pending', '42501');
  end if;
  v_caller := private.require_cloud_binding();
  v_revoked := private.device_revoked_at(v_auth.profile_id, p_device_id);
  -- Nur ein Gerät, das schon vor dem Widerruf gebunden war. Sonst gäbe eine zweite App-Instanz mit Mail-Code auf dem
  -- widerrufenen Handy (oder ein neues Gerät nach dem Widerruf aller Geräte durch admin) den Widerruf still wieder frei.
  if not exists (select 1 from private.cloud_installations i where i.device_id = v_caller and i.created_at < v_revoked) then
    perform private.fail('device_unrevoke_caller_too_new', '42501');
  end if;
  perform private.device_restore(v_auth.profile_id, p_device_id);
  insert into ops.account_audit(actor_id, target_id, action, changes)
    values (v_auth.profile_id, v_auth.profile_id, 'device_unrevoke',
      jsonb_build_object('via', 'device', 'device_id', p_device_id, 'caller_device_id', v_caller));
  return jsonb_build_object('deviceId', p_device_id, 'revoked', false);
end;
$$;

-- K4-U (Entscheid 24.10.2026): Absendergerät einer Kontomeldung aus der Bindung der Sitzung (JWT session_id), beim
-- Anlegen festgehalten. Eigene Tabelle, damit es weder in Nutzerantworten noch im Inbox-JSON (to_jsonb) erscheint.
-- Keine Zeile = kein bekanntes Gerät. `revoked`: Bindung, Installation oder Gerät war beim Melden widerrufen.
create table private.support_ticket_devices (
  ticket_id uuid primary key references ops.support_tickets(id) on delete cascade,
  device_id uuid not null,
  revoked boolean not null
);
alter table private.support_ticket_devices enable row level security;
revoke all on private.support_ticket_devices from public, anon, authenticated, service_role;

-- submit_support bleibt unverändert (läuft schon unter lock_sync); ein Gast oder fremder Owner hinterlässt keine Zeile.
create function private.support_ticket_device() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_session uuid;
begin
  if new.owner_id is null or new.owner_id is distinct from auth.uid() then return null; end if;
  begin
    v_session := (auth.jwt() ->> 'session_id')::uuid;
  exception when invalid_text_representation then
    return null;
  end;
  insert into private.support_ticket_devices(ticket_id, device_id, revoked)
    select new.id, b.device_id, b.revoked_at is not null or i.revoked_at is not null or d.revoked_at is not null
    from private.cloud_session_bindings b
    join private.cloud_installations i using (profile_id, installation_id, device_id)
    join public.devices d on d.id = b.device_id
    where b.session_id = v_session and b.profile_id = new.owner_id;
  return null;
end;
$$;
create trigger support_ticket_device after insert on ops.support_tickets
  for each row execute function private.support_ticket_device();

-- K4-U Adminweg: admin mit AAL2, Begründung und Meldung nach dem Widerruf (Regel wie die Löschrücknahme). Eine
-- Kontomeldung zählt nur von einem anderen eigenen, beim Melden nicht widerrufenen Gerät; die Löschrücknahme braucht
-- das nicht (cancel_account_deletion steht jeder Kontositzung offen, auch der des widerrufenen Geräts). Gesperrte
-- Konten und Konten in Löschung bleiben ausgeschlossen: erst entsperren bzw. die Löschung zurücknehmen.
create function public.admin_account_unrevoke_device(p_profile_id uuid, p_device_id uuid, p_ticket_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_actor uuid;
  v_reason text;
  v_kind text;
  v_source private.support_ticket_devices%rowtype;
begin
  perform private.lock_sync();
  v_actor := private.require_support_admin();
  v_reason := private.support_reason(p_reason);
  perform private.account_target(p_profile_id, v_actor);
  if private.is_suspended(p_profile_id) then perform private.fail('account_suspended'); end if;
  if exists (select 1 from public.profiles p where p.id = p_profile_id and p.status = 'deletion_requested') then
    perform private.fail('account_deletion_pending');
  end if;
  v_kind := private.account_ticket_kind(p_profile_id, p_ticket_id, private.device_revoked_at(p_profile_id, p_device_id),
    v_reason, 'device');
  if v_kind = 'account' then
    select * into v_source from private.support_ticket_devices s where s.ticket_id = p_ticket_id;
    if not found then perform private.fail('device_ticket_device_unknown'); end if;
    if v_source.device_id = p_device_id or v_source.revoked then perform private.fail('device_ticket_from_revoked_device'); end if;
  end if;
  perform private.device_restore(p_profile_id, p_device_id);
  -- Eine Meldung darf mehrere Geräte freigeben (Handy und Uhr zurück); ticket_uses > 1 macht das im Audit sichtbar.
  insert into ops.account_audit(actor_id, target_id, action, reason, changes)
    values (v_actor, p_profile_id, 'device_unrevoke', v_reason, jsonb_build_object('via', 'admin',
      'device_id', p_device_id, 'ticket_id', p_ticket_id, 'ticket_kind', v_kind, 'ticket_uses',
      1 + (select count(*) from ops.account_audit a where a.changes ->> 'ticket_id' = p_ticket_id::text)));
  return private.account_status(p_profile_id);
end;
$$;

-- Wie 20261016000700, zusätzlich das Konto-Audit nach einem Jahr (gleicher Cronjob advantly-support-cleanup).
create or replace function private.purge_support() returns void
language plpgsql security definer set search_path = '' set lock_timeout = '5s' set statement_timeout = '60s' as $$
begin
  perform private.lock_sync();
  delete from ops.support_tickets where id in (select id from ops.support_tickets
    where status in ('resolved','closed') and updated_at < now() - interval '180 days' order by updated_at limit 500);
  delete from ops.support_budgets where started_at < now() - interval '2 hours';
  delete from ops.support_audit where created_at < now() - interval '1 year';
  delete from ops.account_audit where created_at < now() - interval '1 year';
end;
$$;

alter function public.admin_account_set_suspended(uuid,boolean,text) set lock_timeout = '2s';
alter function public.admin_account_set_suspended(uuid,boolean,text) set statement_timeout = '5s';
alter function public.admin_account_revoke_devices(uuid,text) set lock_timeout = '2s';
alter function public.admin_account_revoke_devices(uuid,text) set statement_timeout = '5s';
alter function public.admin_account_cancel_deletion(uuid,uuid,text) set lock_timeout = '2s';
alter function public.admin_account_cancel_deletion(uuid,uuid,text) set statement_timeout = '5s';
alter function public.admin_account_unrevoke_device(uuid,uuid,uuid,text) set lock_timeout = '2s';
alter function public.admin_account_unrevoke_device(uuid,uuid,uuid,text) set statement_timeout = '5s';

revoke all on function private.account_audit_decouple(), private.account_audit_deleting(), private.is_suspended(uuid),
  private.require_support_admin(), private.support_reason(text), private.account_status(uuid), private.account_target(uuid,uuid),
  private.account_ticket_kind(uuid,uuid,timestamptz,text,text), private.device_revoked_at(uuid,uuid),
  private.device_restore(uuid,uuid), private.support_ticket_device()
  from public, anon, authenticated, service_role;
revoke all on function public.admin_account_lookup(text), public.admin_account_set_suspended(uuid,boolean,text),
  public.admin_account_revoke_devices(uuid,text), public.admin_account_cancel_deletion(uuid,uuid,text),
  public.admin_account_unrevoke_device(uuid,uuid,uuid,text), public.cloud_unrevoke_device_v1(uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.admin_account_lookup(text), public.admin_account_set_suspended(uuid,boolean,text),
  public.admin_account_revoke_devices(uuid,text), public.admin_account_cancel_deletion(uuid,uuid,text),
  public.admin_account_unrevoke_device(uuid,uuid,uuid,text), public.cloud_unrevoke_device_v1(uuid) to authenticated;
