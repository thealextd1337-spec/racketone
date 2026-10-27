-- H0: Telemetrie-Sichtbarkeit (F19/F29) kontoweit mit derselben Konfliktregel wie der Client
-- (shared/core-model TelemetryPreference: Lamport-Zähler, dann Restriktivität off > private > share, dann origin
-- lexikografisch). Gesundheitsdaten selbst bleiben bis P3 lokal. Dazu zwei Lücken aus Paket D:
-- my_friend_code hinter der Cloud-Bindung und die eigene Blockliste mit Alias.

-- counter 0 = nie geändert (Standard, ohne origin). origin = Installations-ID des ändernden Geräts (Client ≤ 64 Zeichen);
-- nur sichtbares ASCII, damit "C"-Sortierung und Kotlin-String-Vergleich dieselbe Reihenfolge ergeben.
alter table public.profile_settings
  add column telemetry_counter bigint not null default 0,
  add column telemetry_origin text,
  add constraint profile_settings_telemetry_version check (
    telemetry_counter >= 0 and (telemetry_counter = 0) = (telemetry_origin is null)
    and (telemetry_origin is null or telemetry_origin ~ '^[!-~]{1,64}$'));

-- Direktes UPDATE würde Zähler und Konfliktregel umgehen; ab jetzt nur noch über die RPC.
revoke update (telemetry_visibility) on public.profile_settings from authenticated;

-- Die Telemetrie-Wahl ändert keine Sichtbarkeit (keine RLS/Funktion liest sie); eine Generationserhöhung erzwänge
-- nur Vollscans und restart_required auf allen eigenen Geräten. Gleiche Funktion, Trigger nur ohne telemetry_*:
-- die übrigen Schlüssel von private.cloud_access_generation() für profile_settings (20261018000600).
drop trigger cloud_access_generation on public.profile_settings;
create trigger cloud_access_generation
  after insert or delete or update of profile_id, share_matches_default, country, age_confirmed_at, age_rule_min_age
  on public.profile_settings for each row execute function private.cloud_access_generation();

-- Übernimmt den gesendeten Stand nur, wenn er nach der totalen Ordnung größer ist, und liefert immer den Serverstand
-- ({visibility, counter, origin}). p_counter = 0 (Client-Standard) schreibt nichts und liest nur – so holt ein Gerät
-- ohne eigene Änderung den Kontostand in einem Aufruf. Konto nur aus dem JWT, nie als Parameter.
create function public.cloud_set_telemetry_visibility_v1(p_visibility text, p_counter bigint, p_origin text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_result jsonb;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := private.require_active();
  -- Obergrenze 2^53: Long.MAX würde die Wahl einfrieren (changedBy liefe über) und bleibt in JSON verlustfrei.
  if p_counter is null or p_counter < 0 or p_counter >= 9007199254740992 or (p_counter > 0 and (
       p_visibility is null or p_visibility not in ('share', 'private', 'off')
       or p_origin is null or p_origin !~ '^[!-~]{1,64}$')) then
    perform private.fail('invalid_telemetry_setting');
  end if;
  if p_counter > 0 then
    update public.profile_settings s
       set telemetry_visibility = p_visibility, telemetry_counter = p_counter, telemetry_origin = p_origin,
           updated_at = now()
     where s.profile_id = v_uid
       and (p_counter, array_position(array['share', 'private', 'off'], p_visibility), p_origin collate "C")
         > (s.telemetry_counter, array_position(array['share', 'private', 'off'], s.telemetry_visibility),
            coalesce(s.telemetry_origin, '') collate "C");
  end if;
  select jsonb_build_object('visibility', s.telemetry_visibility, 'counter', s.telemetry_counter,
                            'origin', s.telemetry_origin)
    into strict v_result from public.profile_settings s where s.profile_id = v_uid;
  return v_result;
end;
$$;

-- Paket D: Der eigene Freundescode gehört wie alle sozialen RPCs hinter Bindung und Installationsnachweis.
create or replace function public.my_friend_code()
returns text language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_code text;
begin
  perform private.require_cloud_binding();
  v_uid := private.require_active();
  select p.friend_code into v_code from public.profiles p where p.id = v_uid;
  return v_code;
end;
$$;

-- Paket D: eigene Blockliste mit dem Alias der Blockierten (RLS gibt fremde Profile sonst nicht heraus).
-- Bewusst nur Alias, kein Benutzername/Freundescode; nicht aktive Profile ohne Alias.
create function public.cloud_my_blocks_v1()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := private.require_active();
  return (select coalesce(jsonb_agg(jsonb_build_object('profileId', b.blocked_id,
            'alias', case when p.status = 'active' then p.alias end, 'createdAt', b.created_at)
            order by b.created_at desc, b.blocked_id), '[]'::jsonb)
    from public.user_blocks b join public.profiles p on p.id = b.blocked_id
    where b.blocker_id = v_uid);
end;
$$;

revoke execute on function public.my_friend_code() from service_role;
revoke all on function public.cloud_set_telemetry_visibility_v1(text, bigint, text), public.cloud_my_blocks_v1()
  from public, anon, authenticated, service_role;
grant execute on function public.cloud_set_telemetry_visibility_v1(text, bigint, text), public.cloud_my_blocks_v1()
  to authenticated;

-- Art. 15: Export (Stand 20261018000500) unverändert bis auf Zähler und Ursprung der Telemetrie-Wahl.
create or replace function public.export_my_data()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null or not exists (select 1 from public.profiles where id = v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  perform private.lock_sync();
  perform private.require_account_session();
  return jsonb_build_object(
    'support', coalesce((select jsonb_agg(private.export_support_ticket(t.id) order by t.created_at, t.id) from ops.support_tickets t where t.owner_id=v_uid),'[]'::jsonb),
    'consentHistory', (select coalesce(jsonb_agg(jsonb_build_object(
      'type', h.document_type, 'version', h.version, 'language', h.language,
      'accepted', h.accepted, 'decidedAt', h.decided_at) order by h.id), '[]'::jsonb)
      from private.consent_history h where h.profile_id = v_uid),
    'exportedAt', now(),
    'profile', (select jsonb_build_object('id', p.id, 'alias', p.alias, 'username', p.username,
                                          'friendCode', p.friend_code, 'discoverable', p.discoverable,
                                          'status', p.status, 'createdAt', p.created_at)
                from public.profiles p where p.id = v_uid),
    'settings', (select jsonb_build_object('country', s.country, 'ageConfirmedAt', s.age_confirmed_at,
                                           'telemetryVisibility', s.telemetry_visibility,
                                           'telemetryCounter', s.telemetry_counter, 'telemetryOrigin', s.telemetry_origin,
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
                   'format', private.export_format(m.format), 'meta', private.export_meta(m.meta), 'status', m.status, 'resultReason', m.result_reason,
                   'winner', m.winner, 'startedAt', m.started_at, 'endedAt', m.ended_at, 'summary', private.export_summary(m.summary),
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
