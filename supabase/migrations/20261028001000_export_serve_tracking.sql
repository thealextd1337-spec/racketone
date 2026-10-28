-- 0.3.0 „Aufschläge“ (Ja / Nur Doppelfehler / Nein): MatchMeta.serveTracking gehört zur Datenauskunft (K2-Whitelist).
-- Die App schreibt das Feld nur bei „Nur Doppelfehler“; serveStats bleibt für ältere App-Stände erhalten.
create or replace function private.export_meta(p_value jsonb) returns jsonb
language sql immutable set search_path = '' as $$
 select private.export_shape(p_value,'{"surface":"string","environment":"string","venue":"string","mixed":"boolean","watchWorkout":"boolean","serveStats":"boolean","serveTracking":"string","courtPace":"number"}'::jsonb);
$$;

-- Export-Budget (data_export_snapshot_v1: 4 MiB JSON, Edge Function gleich): Im Modus „Ja“ schreibt die App je Punkt
-- zusätzlich ein PointDetailSet („1. Aufschlag“ automatisch). Mit voller Hülle (id, deviceId, deviceSeq, wallClockMs,
-- parentId, doppelter Typ) kostet es rund 340 B je Punkt: 40 Bo3 à 160 Punkte lagen damit über dem Budget (pgTAP 200).
-- Kompakt bleiben nur id, Ordnung (lamport), Typ, Eigentum und – bei eigenen Events – Ziel und Aufschlag-Info (~170 B);
-- „letztes gilt“ ist über lamport weiter nachvollziehbar, die CSV behält die Spalte payload. Fachlich geht nichts
-- verloren: Gerät und Zeit des Punktes stehen am Ziel-Event (PointWon). Das Budget bleibt (Edge Function JSON_LIMIT).
-- Stand 20261027000300, geändert nur im Zweig PointDetailSet der Events.
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
                   'events', (select coalesce(jsonb_agg(case
                                 -- Aufschlag-Info (je Punkt im Modus „Ja“): kompakt, ohne Hülle und doppelten Typ
                                 when e.type = 'PointDetailSet' and e.author_id = v_uid
                                 then jsonb_build_object('id', e.id, 'lamport', e.lamport, 'type', e.type, 'mine', true,
                                                         'payload', private.export_payload(e.type, e.payload) - 'type')
                                 when e.type = 'PointDetailSet'
                                 then jsonb_build_object('id', e.id, 'lamport', e.lamport, 'type', e.type, 'mine', false)
                                 when e.author_id = v_uid
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

