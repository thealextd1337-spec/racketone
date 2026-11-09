-- Plan Einladungen §2.1 S1/S2/S5–S8 und §3.5 (Release 0.7.0): Vorschau und Widerruf von Einladungen, ein offener
-- Claim-Link je Gast, höchstens 50 offene Einladungen je Konto, Aufräumjob und Push an den Anleger beim Claim.
-- redeem_invite bleibt unverändert (S3/S4 seit 20261105000100). Abwärtskompatibel: ältere Apps rufen keine
-- Einladungs-RPCs auf und ignorieren unbekannte Push-Arten.
-- Nebenläufigkeit: Alle Einladungs-RPCs nehmen über require_active zuerst lock_sync (globale Transaktionssperre, wie
-- Zuordnung, Claim und Kontolöschung). Damit laufen zwei create_invite desselben Kontos strikt nacheinander, und die
-- Zählung von S6 sieht die zuvor bestätigte Einladung (READ COMMITTED erzwingt require_cloud_binding). Eine eigene
-- Konto-Sperre wäre doppelt.

-- S5/S6 per Seam am aktuellen Stand; Signatur, Owner, ACL, security definer und search_path bleiben.
do $$
declare
  v_def text := pg_get_functiondef('public.create_invite(text,uuid,integer)'::regprocedure);
  v_marker text := $s$  insert into public.invites (token_hash, kind, created_by, player_id, expires_at)
$s$;
begin
  if (length(v_def) - length(replace(v_def, v_marker, ''))) / length(v_marker) <> 1 then
    raise exception 'create_invite: Seam nicht eindeutig';
  end if;
  execute replace(v_def, v_marker, $s$  -- 0.7.0 S5: ein neuer Claim-Link zieht offene ältere Links desselben Gasts zurück.
  if p_kind = 'claim_player' then
    update public.invites set revoked_at = now()
     where created_by = v_uid and kind = 'claim_player' and player_id = p_player_id
       and used_at is null and revoked_at is null and expires_at > now();
  end if;
  -- 0.7.0 S6: höchstens 50 offene Einladungen je Konto (nach S5, ein Ersatzlink zählt nicht doppelt).
  if (select count(*) from public.invites i
      where i.created_by = v_uid and i.used_at is null and i.revoked_at is null and i.expires_at > now()) >= 50 then
    perform private.fail('too_many_open_invites');
  end if;
$s$ || v_marker);
end;
$$;

-- S1: Vorschau vor dem Einlösen, nur lesend. Ungültig (unbekannt, Blockierung in beide Richtungen, Ersteller nicht
-- aktiv bzw. in Löschung, Gast gelöscht oder redigiert) liefert nur {status: invalid}, wie redeem_invite kein Orakel.
-- guestName/matchCount nur für claim_player mit ok. matchCount zählt dieselben Teilnehmerzeilen, die
-- link_player_participants beim Einlösen verknüpfen würde (ohne Matches, in denen der Aufrufer schon steht oder die er
-- per „War ich nicht“ abgelehnt hat) – also genau die matchCount-Antwort von redeem_invite.
create function public.preview_invite(p_token text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid    uuid := private.require_active();
  v_inv    public.invites%rowtype;
  v_player public.players%rowtype;
  v_status text;
begin
  perform private.require_cloud_binding();
  perform private.consume_rate_limit('search');
  select * into v_inv from public.invites i where i.token_hash = sha256(convert_to(coalesce(p_token, ''), 'UTF8'));
  if not found or private.is_blocked(v_uid, v_inv.created_by) or not private.is_active(v_inv.created_by) then
    return jsonb_build_object('status', 'invalid');
  end if;
  if v_inv.kind = 'claim_player' then
    select * into v_player from public.players p where p.id = v_inv.player_id;
    if not found or v_player.deleted_at is not null or private.player_write_redacted(v_inv.player_id::text) then
      return jsonb_build_object('status', 'invalid');
    end if;
  end if;
  v_status := case
    when v_inv.created_by = v_uid then 'own'
    when v_inv.revoked_at is not null then 'revoked'
    when v_inv.used_at is not null then 'used'
    when v_inv.expires_at <= now() then 'expired'
    when v_inv.kind = 'claim_player' and v_player.profile_id is not null then 'already_claimed'
    when v_inv.kind = 'friend' and private.are_friends(v_uid, v_inv.created_by) then 'already_friends'
    else 'ok' end;
  return jsonb_build_object('status', v_status, 'kind', v_inv.kind, 'inviterId', v_inv.created_by,
      'inviterAlias', (select f.alias from public.profiles f where f.id = v_inv.created_by),
      'expiresAt', to_char(v_inv.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
    || case when v_inv.kind = 'claim_player' and v_status = 'ok' then jsonb_build_object(
         'guestName', v_player.display_name,
         'matchCount', (select count(*)::integer from public.match_participants mp
           where mp.player_id = v_inv.player_id and mp.profile_id is null
             and not exists (select 1 from public.match_participants o
                             where o.match_id = mp.match_id and o.profile_id = v_uid)
             and not exists (select 1 from public.match_link_rejections r
                             where r.match_id = mp.match_id and r.profile_id = v_uid)))
       else '{}'::jsonb end;
end;
$$;

-- S2: Widerruf durch den Ersteller, nur offene Links (nicht benutzt, nicht widerrufen, nicht abgelaufen).
create function public.revoke_invite(p_invite_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := private.require_active();
begin
  perform private.require_cloud_binding();
  perform private.consume_rate_limit('invitation');
  update public.invites set revoked_at = now()
   where id = p_invite_id and created_by = v_uid and used_at is null and revoked_at is null and expires_at > now();
  if not found then
    perform private.fail('invite_not_found');
  end if;
end;
$$;

revoke all on function public.preview_invite(text), public.revoke_invite(uuid) from public, anon;
grant execute on function public.preview_invite(text), public.revoke_invite(uuid) to authenticated;

-- S7: Einladungen 30 Tage nach Nutzung, Widerruf bzw. Ablauf löschen (Datenschutz §4, E12).
create function private.purge_invites()
returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s'
  set statement_timeout = '60s' as $$
begin
  perform private.lock_sync();
  delete from public.invites where coalesce(used_at, revoked_at, expires_at) < now() - interval '30 days';
end;
$$;
revoke all on function private.purge_invites() from public, anon, authenticated, service_role;

-- S8: player_claimed wird zugestellt (E8). Nachricht nur mit IDs: profileId (Einlöser) und playerId (Gast).
create or replace function private.push_kinds()
returns text[] language sql immutable set search_path = '' as $$
  select array['friend_request', 'friend_accepted', 'player_assigned', 'match_shared', 'visitor_invited',
               'scorer_invited', 'scorer_responded', 'scorer_changed', 'player_claimed'];
$$;

create or replace function private.push_message(p_row ops.notification_outbox)
returns jsonb language sql immutable set search_path = '' as $$
  select jsonb_strip_nulls(jsonb_build_object('v', 1, 'kind', p_row.kind, 'matchId', p_row.payload ->> 'matchId',
                                              'profileId', p_row.actor_id, 'role', p_row.payload ->> 'role',
                                              'playerId', case when p_row.kind = 'player_claimed'
                                                               then p_row.payload ->> 'playerId' end));
$$;

-- Zustellbar nur, solange der Claim beim Versand noch besteht (nicht gelöst, Gast nicht gelöscht oder redigiert).
-- Zusammenfassen in notify: Schlüssel für player_claimed ist Gast und Einlöser, Claims verschiedener Gäste bzw.
-- verschiedener Einlöser fallen nicht zusammen.
do $$
declare
  v_def text;
  v_seam record;
begin
  for v_seam in select * from (values
    ('private.push_deliverable(ops.notification_outbox)', $s$          when p_row.actor_id is null then false
$s$, $s$          when p_row.kind = 'player_claimed' then exists (
            select 1 from public.players p
            where p.id = private.redaction_uuid(p_row.payload ->> 'playerId') and p.owner_id = p_row.profile_id
              and p.profile_id = p_row.actor_id and p.assignment_source = 'claim' and p.deleted_at is null
              and not private.player_write_redacted(p.id::text))
          when p_row.actor_id is null then false
$s$),
    ('private.notify(uuid,text,jsonb,uuid)', $s$coalesce(o.payload ->> 'matchId', o.actor_id::text, '')$s$,
      $s$coalesce(o.payload ->> 'matchId',
                     case when p_kind = 'player_claimed' then (o.payload ->> 'playerId') || ':' || o.actor_id end,
                     o.actor_id::text, '')$s$),
    ('private.notify(uuid,text,jsonb,uuid)', $s$coalesce(p_payload ->> 'matchId', p_actor::text, '')$s$,
      $s$coalesce(p_payload ->> 'matchId',
                     case when p_kind = 'player_claimed' then (p_payload ->> 'playerId') || ':' || p_actor end,
                     p_actor::text, '')$s$)
  ) seams(signature, marker, replacement) loop
    v_def := pg_get_functiondef(v_seam.signature::regprocedure);
    if (length(v_def) - length(replace(v_def, v_seam.marker, ''))) / length(v_seam.marker) <> 1 then
      raise exception '%: Seam nicht eindeutig: %', v_seam.signature, v_seam.marker;
    end if;
    execute replace(v_def, v_seam.marker, v_seam.replacement);
  end loop;
end;
$$;

do $$ begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
    perform cron.schedule('advantly-invites-cleanup-v1', '37 3 * * *', 'select private.purge_invites();');
  else
    raise warning 'pg_cron unavailable: invites cleanup unscheduled';
  end if;
end $$;
