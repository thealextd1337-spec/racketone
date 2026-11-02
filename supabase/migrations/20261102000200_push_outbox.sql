-- Plan Live-Mitspieler PR 5 (docs/plan-live-mitspieler.md §3.3/§8): Push-Versand aus ops.notification_outbox.
-- private.notify → Outbox (+ actor_id) → AFTER-INSERT-Trigger und pg_cron je Minute → pg_net → Edge Function
-- push-dispatch (PR 6) → claim_push_batch_v1 → FCM → ack_push_batch_v1. Nachrichten enthalten nur IDs und die Art.
-- Vault (Integrationsvertrag, die Migration legt keine Geheimnisse an): `data_exports_project_url` (dieselbe API-Basis
-- wie der Export-Cleanup) und `push_dispatch_secret` (≥ 32 Zeichen, identisch als PUSH_DISPATCH_SECRET in der Function).

alter table ops.notification_outbox
  add column actor_id uuid references public.profiles (id) on delete cascade,
  -- Lease nach dem Claim bzw. frühester nächster Versuch nach einem Fehler.
  add column claimed_until timestamptz;
alter table ops.notification_outbox drop constraint notification_outbox_kind_check;
alter table ops.notification_outbox add constraint notification_outbox_kind_check check (kind in (
  'friend_request', 'friend_accepted', 'match_shared', 'scorer_invited', 'scorer_responded', 'scorer_changed',
  'visitor_invited', 'player_assigned', 'player_assignment_rejected', 'player_claimed'));
create index notification_outbox_recipient_idx on ops.notification_outbox (profile_id, kind, created_at);

-- Arten mit Push (§8). player_assignment_rejected/player_claimed bleiben reine Outbox-Einträge ohne Versand.
create function private.push_kinds()
returns text[] language sql immutable set search_path = '' as $$
  select array['friend_request', 'friend_accepted', 'player_assigned', 'match_shared', 'visitor_invited',
               'scorer_invited', 'scorer_responded', 'scorer_changed'];
$$;

-- Auslöser ist ohne Angabe der Aufrufer; bisherige Aufrufer bleiben unverändert (Standardwert auth.uid()).
-- Zusammenfassen: gleiche Art, gleicher Empfänger und gleiches Match (sonst gleicher Auslöser) innerhalb von 10 min
-- erzeugt keinen zweiten Eintrag, solange der vorige noch nicht gesendet ist (danach kann sich der Inhalt geändert
-- haben: Einladung → Widerruf → erneut, Antwort abgelehnt → angenommen). Benachrichtigungen an sich selbst entstehen nicht.
drop function private.notify(uuid, text, jsonb);
create function private.notify(p_profile uuid, p_kind text, p_payload jsonb default '{}'::jsonb,
                               p_actor uuid default auth.uid())
returns void language sql security definer set search_path = '' as $$
  insert into ops.notification_outbox (profile_id, kind, payload, actor_id)
  select p_profile, p_kind, p_payload, p_actor
  where p_profile is distinct from p_actor
    and not exists (
      select 1 from ops.notification_outbox o
      where o.profile_id = p_profile and o.kind = p_kind and o.created_at > now() - interval '10 minutes'
        and o.sent_at is null
        and coalesce(o.payload ->> 'matchId', o.actor_id::text, '')
            = coalesce(p_payload ->> 'matchId', p_actor::text, ''));
$$;
revoke all on function private.notify(uuid, text, jsonb, uuid) from public, anon, authenticated;
grant execute on function private.notify(uuid, text, jsonb, uuid) to service_role;

-- Prüfung beim Versand (Stand jetzt, nicht beim Eintragen): Empfänger aktiv, nicht der Auslöser, Auslöser aktiv und
-- nicht blockiert, Match noch sichtbar bzw. Freundschaft (Anfrage) noch vorhanden, mindestens ein Push-Token.
-- match_shared, player_assigned und scorer_changed nur für Matches, die in den letzten 24 h begonnen haben: Ein
-- Erstbackup alter Matches meldet sonst jedes einzelne. Maßgeblich ist der Beginn laut Client (started_at), nicht
-- der Status – der bleibt beim Backup bis zur Projektion (nach dem letzten Event-Batch) auf in_progress.
create function private.push_deliverable(p_row ops.notification_outbox)
returns boolean language sql stable security definer set search_path = '' as $$
  select p_row.kind = any (private.push_kinds())
    and private.cloud_profile_allowed(p_row.profile_id)
    and (p_row.actor_id is null
         or (p_row.actor_id <> p_row.profile_id and private.is_active(p_row.actor_id)
             and not private.is_blocked(p_row.profile_id, p_row.actor_id)))
    and case
          when p_row.payload ? 'matchId' then exists (
            select 1 from public.matches m
            where m.id = (p_row.payload ->> 'matchId')::uuid and m.deleted_at is null
              and (p_row.kind not in ('match_shared', 'player_assigned', 'scorer_changed')
                   or coalesce(m.started_at, m.created_at) > now() - interval '24 hours')
              and (private.is_match_member(m.id, p_row.profile_id) or private.is_match_visitor(m.id, p_row.profile_id)))
          when p_row.actor_id is null then false
          when p_row.kind = 'friend_request' then exists (
            select 1 from public.friendships f
            where f.requester_id = p_row.actor_id and f.addressee_id = p_row.profile_id and f.status = 'pending')
          else private.are_friends(p_row.profile_id, p_row.actor_id)
        end
    and exists (select 1 from public.devices d
                where d.profile_id = p_row.profile_id and d.push_token is not null and d.revoked_at is null);
$$;

-- Nachricht nach §8: nur Art und IDs. profileId ist immer der Auslöser.
create function private.push_message(p_row ops.notification_outbox)
returns jsonb language sql immutable set search_path = '' as $$
  select jsonb_strip_nulls(jsonb_build_object('v', 1, 'kind', p_row.kind, 'matchId', p_row.payload ->> 'matchId',
                                              'profileId', p_row.actor_id, 'role', p_row.payload ->> 'role'));
$$;

-- Nur für push-dispatch (service_role). Je Eintrag: id, message (FCM data) und targets (alle Push-Token des
-- Empfängers). Nicht zustellbare Einträge und solche über 30 Push je Empfänger und Stunde werden gelöscht.
-- Jeder Claim zählt als Versuch; ohne Ack wird der Eintrag nach 2 min erneut geliefert.
create function public.claim_push_batch_v1(p_limit integer default 100)
returns jsonb language plpgsql security definer set search_path = '' set lock_timeout = '2s'
  set statement_timeout = '10s' as $$
declare
  v_row ops.notification_outbox;
  v_out jsonb := '[]'::jsonb;
begin
  perform private.lock_sync();
  if p_limit is null or p_limit not between 1 and 500 then
    perform private.fail('invalid_limit');
  end if;
  for v_row in
    select * from ops.notification_outbox o
    where o.sent_at is null and o.attempts < 5 and (o.claimed_until is null or o.claimed_until < now())
      and o.kind = any (private.push_kinds())
    order by o.id
    limit p_limit
    for update skip locked
  loop
    if not private.push_deliverable(v_row)
       or (select count(*) from ops.notification_outbox o
           where o.profile_id = v_row.profile_id
             and (o.sent_at > now() - interval '1 hour' or (o.sent_at is null and o.claimed_until > now()))) >= 30 then
      delete from ops.notification_outbox where id = v_row.id;
      continue;
    end if;
    update ops.notification_outbox
       set attempts = attempts + 1, claimed_until = now() + interval '2 minutes'
     where id = v_row.id;
    v_out := v_out || jsonb_build_array(jsonb_build_object(
      'id', v_row.id,
      'message', private.push_message(v_row),
      'targets', (select jsonb_agg(jsonb_build_object('deviceId', d.id, 'provider', d.push_provider,
                                                      'token', d.push_token) order by d.id)
                  from public.devices d
                  where d.profile_id = v_row.profile_id and d.push_token is not null and d.revoked_at is null)));
  end loop;
  return v_out;
end;
$$;

-- Ergebnisse je Ziel: [{id, token, result: sent|error|invalid_token, error?}]. Ein gesendetes Ziel genügt für den
-- Eintrag; ungültige Token werden überall geleert; Fehler geben den Eintrag mit Wartezeit (Versuche × 1 min) frei.
create function public.ack_push_batch_v1(p_results jsonb)
returns void language plpgsql security definer set search_path = '' set lock_timeout = '2s'
  set statement_timeout = '10s' as $$
begin
  perform private.lock_sync();
  if jsonb_typeof(p_results) is distinct from 'array' or jsonb_array_length(p_results) > 2000
     or exists (select 1 from jsonb_array_elements(p_results) r
                where jsonb_typeof(r) <> 'object' or jsonb_typeof(r -> 'id') <> 'number'
                   or r ->> 'result' is null or r ->> 'result' not in ('sent', 'error', 'invalid_token')) then
    perform private.fail('invalid_request');
  end if;
  update public.devices set push_token = null, push_provider = null
   where push_token = any (array(select r ->> 'token' from jsonb_array_elements(p_results) r
                                 where r ->> 'result' = 'invalid_token' and r ->> 'token' is not null));
  update ops.notification_outbox set sent_at = clock_timestamp(), last_error = null
   where sent_at is null
     and id = any (array(select (r ->> 'id')::bigint from jsonb_array_elements(p_results) r
                         where r ->> 'result' = 'sent'));
  update ops.notification_outbox o
     set claimed_until = clock_timestamp() + o.attempts * interval '1 minute',
         last_error = left(coalesce(e.error, e.result), 200)
    from (select distinct on ((r ->> 'id')::bigint) (r ->> 'id')::bigint as id, r ->> 'result' as result,
                 r ->> 'error' as error
          from jsonb_array_elements(p_results) r where r ->> 'result' <> 'sent'
          order by (r ->> 'id')::bigint) e
   where o.id = e.id and o.sent_at is null;
end;
$$;

-- Push-Token nur am gebundenen Gerät des Aufrufers; null leert. Dasselbe Token an anderen Geräten (auch anderer
-- Konten nach Kontowechsel auf demselben Handy) wird geleert. Jeder Aufruf zählt als Aktivität (180-Tage-Regel).
create function public.cloud_set_push_token_v1(p_provider text, p_token text)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_device uuid;
begin
  perform private.lock_sync();
  v_device := private.require_cloud_binding();
  if p_token is not null
     and (p_provider is null or p_provider not in ('fcm', 'apns') or p_token !~ '^[A-Za-z0-9:_.-]{16,}$'
          or length(p_token) > 4096) then
    perform private.fail('invalid_push_token');
  end if;
  update public.devices set push_token = null, push_provider = null where push_token = p_token and id <> v_device;
  update public.devices
     set push_token = p_token, push_provider = case when p_token is not null then p_provider end, last_seen_at = now()
   where id = v_device;
end;
$$;

-- Aufbewahrung: alles nach 7 Tagen, fehlgeschlagene nach 5 Versuchen; Push-Token ohne Aktivität seit 180 Tagen.
create function private.purge_push_outbox()
returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s'
  set statement_timeout = '60s' as $$
begin
  perform private.lock_sync();
  delete from ops.notification_outbox
   where created_at < now() - interval '7 days'
      or (sent_at is null and attempts >= 5 and (claimed_until is null or claimed_until < now()));
  update public.devices set push_token = null, push_provider = null
   where push_token is not null and last_seen_at < now() - interval '180 days';
end;
$$;

-- Anstoß von push-dispatch, nur wenn etwas offen ist. Fehlendes Setup → push_scheduler_unavailable (Cron-Log).
create function private.schedule_push_dispatch_v1()
returns bigint language plpgsql security definer set search_path = '' as $$
declare
  v_url text;
  v_secret text;
  v_request bigint;
begin
  if not exists (select 1 from ops.notification_outbox o
                 where o.sent_at is null and o.attempts < 5 and (o.claimed_until is null or o.claimed_until < now())
                   and o.kind = any (private.push_kinds())) then
    return null;
  end if;
  if to_regclass('vault.decrypted_secrets') is null or not exists (select 1 from pg_extension where extname = 'pg_net') then
    raise exception using message = 'push_scheduler_unavailable';
  end if;
  execute 'select decrypted_secret from vault.decrypted_secrets where name = ''data_exports_project_url''' into v_url;
  execute 'select decrypted_secret from vault.decrypted_secrets where name = ''push_dispatch_secret''' into v_secret;
  if v_url is null or v_secret is null or length(v_secret) < 32 then
    raise exception using message = 'push_scheduler_unavailable';
  end if;
  -- 30 s: push-dispatch endet spätestens nach rund 22 s (Budget und Frist in handler.ts). Ein Verbindungsabbruch
  -- mitten im Batch ließe geleaste Einträge ohne Ack zurück (erneuter Versand erst nach 2 min).
  execute 'select net.http_post(url := $1, headers := $2, body := $3, timeout_milliseconds := 30000)'
    into v_request using v_url || '/functions/v1/push-dispatch',
      jsonb_build_object('Content-Type', 'application/json', 'x-push-dispatch-secret', v_secret), '{}'::jsonb;
  return v_request;
end;
$$;

-- pg_net reiht die Anfrage transaktional ein (Versand erst nach dem Commit). Best effort: ein fehlendes Setup darf
-- keine fachliche Transaktion abbrechen, der Cronjob wiederholt jede Minute.
create function private.dispatch_push_after_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  begin
    perform private.schedule_push_dispatch_v1();
  exception when others then
    null;
  end;
  return null;
end;
$$;
create trigger notification_outbox_dispatch after insert on ops.notification_outbox
  for each statement execute function private.dispatch_push_after_insert();

revoke all on function private.push_kinds(), private.push_deliverable(ops.notification_outbox),
  private.push_message(ops.notification_outbox), private.purge_push_outbox(), private.schedule_push_dispatch_v1(),
  private.dispatch_push_after_insert()
  from public, anon, authenticated, service_role;
revoke all on function public.claim_push_batch_v1(integer), public.ack_push_batch_v1(jsonb),
  public.cloud_set_push_token_v1(text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.claim_push_batch_v1(integer), public.ack_push_batch_v1(jsonb) to service_role;
grant execute on function public.cloud_set_push_token_v1(text, text) to authenticated;

do $$ begin
  if exists (select 1 from pg_available_extensions where name = 'pg_net') then create extension if not exists pg_net; end if;
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
    perform cron.schedule('advantly-push-dispatch-v1', '* * * * *', 'select private.schedule_push_dispatch_v1();');
    perform cron.schedule('advantly-push-cleanup-v1', '53 * * * *', 'select private.purge_push_outbox();');
  else
    raise warning 'pg_cron unavailable: push dispatch retry and cleanup unscheduled';
  end if;
end $$;
