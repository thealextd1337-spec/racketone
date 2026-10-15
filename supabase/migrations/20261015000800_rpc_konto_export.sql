-- Advantly P2-Spike · Account-Löschung (Art. 17, 14 Tage Karenz) und Datenexport (Art. 15/20)

-- Löschung beantragen: sofort unsichtbar für andere (Suche, Freundeslisten, Visitor), Push-Tokens weg,
-- offene Zähler-Einladungen abgelehnt. Health-Daten gibt es erst ab P3 (dann hier sofort hart löschen).
create or replace function public.request_account_deletion()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  perform private.lock_sync();
  update public.profiles set status = 'deletion_requested' where id = v_uid;
  update public.profile_settings set deletion_requested_at = now(), updated_at = now() where profile_id = v_uid;
  update public.devices set push_token = null, push_provider = null where profile_id = v_uid;
  update public.match_visitors set revoked_at = now()
   where revoked_at is null and (profile_id = v_uid or invited_by = v_uid);
  update public.match_roles set status = 'declined', responded_at = now()
   where profile_id = v_uid and status = 'invited';
  update public.invites set revoked_at = now() where created_by = v_uid and used_at is null and revoked_at is null;
  -- Feeds der Freunde ändern sich ohne neue Match-Version → Vollabgleich auslösen
  perform private.bump_visibility(private.friend_ids(v_uid));
end;
$$;

-- Login innerhalb der Karenz: Löschung abbrechen.
create or replace function public.cancel_account_deletion()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  perform private.lock_sync();
  update public.profiles set status = 'active' where id = v_uid and status = 'deletion_requested';
  if not found then
    perform private.fail('no_deletion_pending');
  end if;
  update public.profile_settings set deletion_requested_at = null, updated_at = now() where profile_id = v_uid;
  perform private.bump_visibility(private.friend_ids(v_uid));
end;
$$;

-- Endgültige Löschung (nach Karenz, 03 §8.3). Läuft als BEFORE-DELETE-Trigger auf profiles, damit jeder
-- Löschweg (Löschjob, auth.admin.deleteUser der Edge Function, Dashboard) dieselben Regeln anwendet:
--  1. Matches des Nutzers mit anderen verknüpften Accounts → Ersteller wechselt (erster Teilnehmer nach Team/Platz);
--     Matches nur mit Gästen → gelöscht (Events kaskadierend)
--  2. Zuweisungen fremder Dummys an den Nutzer → gelöst (Platz zeigt wieder den Dummy-Namen)
--  3. eigene Teilnehmer-Zeilen in verbleibenden Matches → „Gelöschter Spieler“ (Snapshot, Flag für Lokalisierung)
--  4. beanspruchte/zugewiesene eigene Dummys gehen an die verknüpfte Person über, übrige Spieler werden gelöscht
--  5. Lösch-Ausnahme vom unveränderlichen Log (Review P2 #4): Klarnamen der Person in Event-Payloads
--     (PlayerAssigned.player.displayName mit ihrer Profil- oder Self-ID) werden ersetzt; redacted_at markiert das
--     Event, redaction_epoch des Matches steigt → Clients laden die Events neu und überschreiben ihre lokale Kopie
--     (Ausnahme von „bitgleich“, 02 §7.3: Name ohne Wirkung auf Score/Zuweisung). Re-Uploads alter Kopien gelten
--     weiter als Duplikat (private.same_event).
--  6. danach Kaskade (Einstellungen, Freundschaften, Blocks, Geräte, Rollen, Visitor, Outbox …)
-- Sign-in-with-Apple-Token-Widerruf übernimmt die Edge Function delete-account (O5).
create or replace function private.anonymize_profile()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_m   record;
  v_p   record;
  v_new uuid;
begin
  with redacted as (
    update public.match_events e
       set payload = jsonb_set(e.payload, '{player,displayName}', to_jsonb('Gelöschter Spieler'::text)),
           redacted_at = now()
     where e.type = 'PlayerAssigned'
       and jsonb_typeof(e.payload -> 'player') = 'object'
       and (e.payload -> 'player' ->> 'id') in (
             select p.id::text from public.players p where p.owner_id = old.id and p.kind = 'self'
             union all select old.id::text)
    returning e.match_id
  )
  update public.matches m set redaction_epoch = redaction_epoch + 1
   where m.id in (select r.match_id from redacted r);

  for v_m in select m.id from public.matches m where m.created_by = old.id loop
    select mp.profile_id into v_new
    from public.match_participants mp
    where mp.match_id = v_m.id and mp.profile_id is not null and mp.profile_id <> old.id
    order by mp.team, mp.position
    limit 1;
    if v_new is null then
      delete from public.matches where id = v_m.id;
    else
      update public.matches set created_by = v_new where id = v_m.id;
    end if;
  end loop;

  for v_p in select p.id from public.players p where p.profile_id = old.id and p.owner_id <> old.id loop
    perform private.unlink_player(v_p.id);
  end loop;

  update public.match_participants
     set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false,
         display_name = 'Gelöschter Spieler', deleted_profile = true, player_id = null
   where profile_id = old.id;

  update public.players
     set owner_id = profile_id, assigned_by = null
   where owner_id = old.id and kind = 'guest' and profile_id is not null and profile_id <> old.id;
  update public.match_participants mp set player_id = null
   where mp.player_id in (select p.id from public.players p where p.owner_id = old.id);
  delete from public.players where owner_id = old.id;
  update public.players set assigned_by = null where assigned_by = old.id;
  return old;
end;
$$;

create trigger profiles_anonymize_before_delete
  before delete on public.profiles
  for each row execute function private.anonymize_profile();

-- Auth-Löschwege (Review P2 R3 #1): Ein DELETE auf auth.users sperrt Auth-Zeilen und löst dann über die Kaskade auf
-- profiles die Versionssperre aus – umgekehrt zu purge_account → Deadlock. Deshalb nimmt jede löschende Anweisung auf
-- auth.users die Versionssperre per BEFORE-STATEMENT-Trigger vor ihren Zeilensperren. Zusätzlich gilt als Betriebsregel:
-- Konten werden ausschließlich über private.purge_account / admin_purge_due_accounts gelöscht (Sperre zuerst); die
-- Edge Function delete-account ruft diesen Weg auf und benutzt kein auth.admin.deleteUser direkt.
create trigger zz_lock_sync
  before delete on auth.users
  for each statement execute function private.lock_sync_statement();

create or replace function private.purge_account(p_profile uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  select private.lock_sync();
  delete from auth.users where id = p_profile;
$$;

-- Für den Löschjob (nur service_role): alle Profile, deren Karenz abgelaufen ist.
create or replace function public.admin_purge_due_accounts(p_grace interval default interval '14 days')
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id    uuid;
  v_count integer := 0;
begin
  perform private.lock_sync();
  for v_id in
    select s.profile_id from public.profile_settings s
    join public.profiles p on p.id = s.profile_id
    where p.status = 'deletion_requested' and s.deletion_requested_at <= now() - p_grace
  loop
    perform private.purge_account(v_id);
    v_count := v_count + 1;
  end loop;
  return v_count;
end;
$$;

-- PointDetail ohne Personenbezug (`by`); JSON-null und Nicht-Objekte → kein Detail (Review P2 R3 #2).
create or replace function private.export_detail(p_detail jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select case when jsonb_typeof(p_detail) = 'object' then p_detail - 'by' end;
$$;

-- Payload eines eigenen Events für den Export, Whitelist je Eventtyp (Review P2 R2 #2): fachliche Werte bleiben, alle
-- Personen- und Geräte-Referenzen (Lineup-/Spieler-IDs, scorerDeviceId, Klarnamen anderer, createdBy) entfallen.
create or replace function private.export_payload(p_type text, p_payload jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select jsonb_strip_nulls(case p_type
    when 'MatchCreated' then jsonb_build_object('type', p_type, 'config', p_payload -> 'config', 'meta', p_payload -> 'meta')
    when 'PointWon' then jsonb_build_object('type', p_type, 'side', p_payload -> 'side',
                                            'detail', private.export_detail(p_payload -> 'detail'))
    when 'Undo' then jsonb_build_object('type', p_type, 'target', p_payload -> 'target')
    when 'ContinueDecision' then jsonb_build_object('type', p_type, 'anotherSet', p_payload -> 'anotherSet',
                                                    'nextSetKind', p_payload -> 'nextSetKind')
    when 'Correction' then jsonb_build_object('type', p_type, 'kind', p_payload -> 'kind', 'side', p_payload -> 'side',
                                              'serverNumber', p_payload -> 'serverNumber')
    when 'MatchEnded' then jsonb_build_object('type', p_type, 'reason', p_payload -> 'reason', 'side', p_payload -> 'side')
    when 'PointDetailSet' then jsonb_build_object('type', p_type, 'target', p_payload -> 'target',
                                                  'detail', private.export_detail(p_payload -> 'detail'))
    when 'ServerChosen' then jsonb_build_object('type', p_type, 'set', p_payload -> 'set', 'side', p_payload -> 'side')
    else jsonb_build_object('type', p_type)   -- Paused, Resumed, ScorerChanged, PlayerAssigned: nur der Typ
  end);
$$;

-- Export eigener Daten als JSON (Art. 15/20). Später erzeugt die Edge Function daraus ZIP (JSON + CSV).
-- Feste Whitelist (Review P2): nur eigene Daten und Matches, an denen der Nutzer beteiligt ist; keine fremden
-- Profil-IDs (Ersteller, Einladende), keine fremden Geräte-IDs, keine Push-Tokens, keine Einstellungen Dritter.
-- Events: eigene (Autor = ich) vollständig; Events anderer Personen nur als Typ/Ordnung ohne Gerät und Payload.
create or replace function public.export_my_data()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  return jsonb_build_object(
    'exportedAt', now(),
    'profile', (select jsonb_build_object('id', p.id, 'alias', p.alias, 'username', p.username,
                                          'friendCode', p.friend_code, 'discoverable', p.discoverable,
                                          'status', p.status, 'createdAt', p.created_at)
                from public.profiles p where p.id = v_uid),
    'settings', (select jsonb_build_object('country', s.country, 'ageConfirmedAt', s.age_confirmed_at,
                                           'telemetryVisibility', s.telemetry_visibility,
                                           'shareMatchesDefault', s.share_matches_default, 'locale', s.locale,
                                           'deletionRequestedAt', s.deletion_requested_at)
                 from public.profile_settings s where s.profile_id = v_uid),
    'devices', (select coalesce(jsonb_agg(jsonb_build_object('id', d.id, 'kind', d.kind, 'platform', d.platform,
                                                             'model', d.model, 'appVersion', d.app_version,
                                                             'lastSeenAt', d.last_seen_at)), '[]'::jsonb)
                from public.devices d where d.profile_id = v_uid),
    'friends', (select coalesce(jsonb_agg(jsonb_build_object(
                   'alias', o.alias, 'status', f.status, 'outgoing', f.requester_id = v_uid,
                   'createdAt', f.created_at)), '[]'::jsonb)
                from public.friendships f
                join public.profiles o on o.id = case when f.requester_id = v_uid then f.addressee_id else f.requester_id end
                where v_uid in (f.requester_id, f.addressee_id)),
    'blockedCount', (select count(*) from public.user_blocks b where b.blocker_id = v_uid),
    'players', (select coalesce(jsonb_agg(case when p.owner_id = v_uid
                   then jsonb_build_object('id', p.id, 'kind', p.kind, 'displayName', p.display_name,
                                           'assignedToFriend', p.profile_id is not null and p.profile_id <> v_uid,
                                           'deleted', p.deleted_at is not null)
                   else jsonb_build_object('id', p.id, 'kind', p.kind, 'displayName', p.display_name,
                                           'assignedToMe', true, 'source', p.assignment_source) end), '[]'::jsonb)
                from public.players p where p.owner_id = v_uid or p.profile_id = v_uid),
    'matches', (select coalesce(jsonb_agg(jsonb_build_object(
                   'id', m.id, 'createdByMe', m.created_by = v_uid, 'sport', m.sport, 'discipline', m.discipline,
                   'format', m.format, 'meta', m.meta, 'status', m.status, 'resultReason', m.result_reason,
                   'winner', m.winner, 'startedAt', m.started_at, 'endedAt', m.ended_at, 'summary', m.summary,
                   'participants', (select jsonb_agg(jsonb_build_object(
                                       'team', mp.team, 'position', mp.position, 'displayName', mp.display_name,
                                       'isMe', mp.profile_id = v_uid) order by mp.team, mp.position)
                                    from public.match_participants mp where mp.match_id = m.id),
                   'events', (select coalesce(jsonb_agg(case when e.author_id = v_uid
                                 then jsonb_build_object('id', e.id, 'deviceId', e.device_id, 'deviceSeq', e.device_seq,
                                                         'lamport', e.lamport, 'wallClockMs', e.wall_clock_ms,
                                                         'parentId', e.parent_id, 'type', e.type,
                                                         'payload', private.export_payload(e.type, e.payload),
                                                         'mine', true)
                                 else jsonb_build_object('id', e.id, 'lamport', e.lamport, 'wallClockMs', e.wall_clock_ms,
                                                         'type', e.type, 'mine', false) end
                                 order by e.lamport, e.device_id, e.id), '[]'::jsonb)
                              from public.match_events e where e.match_id = m.id))), '[]'::jsonb)
                from public.matches m
                where m.created_by = v_uid
                   or exists (select 1 from public.match_participants mp
                              where mp.match_id = m.id and mp.profile_id = v_uid)),
    'roles', (select coalesce(jsonb_agg(jsonb_build_object('matchId', r.match_id, 'role', r.role, 'status', r.status,
                                                           'invitedAt', r.invited_at, 'respondedAt', r.responded_at)),
                              '[]'::jsonb)
              from public.match_roles r where r.profile_id = v_uid),
    'visitorOf', (select coalesce(jsonb_agg(jsonb_build_object('matchId', v.match_id, 'createdAt', v.created_at,
                                                               'revokedAt', v.revoked_at)), '[]'::jsonb)
                  from public.match_visitors v where v.profile_id = v_uid),
    'entitlements', (select coalesce(jsonb_agg(jsonb_build_object('entitlement', e.entitlement, 'source', e.source,
                                                                  'productId', e.product_id, 'expiresAt', e.expires_at)),
                                     '[]'::jsonb)
                     from public.entitlements e where e.profile_id = v_uid));
end;
$$;
