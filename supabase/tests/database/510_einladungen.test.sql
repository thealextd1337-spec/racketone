-- Plan Einladungen §6 (Release 0.7.0, Migration 20261109000100): preview_invite mit allen Status (invalid ohne weitere
-- Felder), revoke_invite, create_invite S5 (ein offener Claim-Link je Gast) und S6 (50 offene Einladungen), TTL,
-- Aufräumjob S7, Push player_claimed S8, Rechte und Cloud-Bindung. redeem_invite bleibt unverändert (490).
begin;
set local timezone = 'UTC';
select plan(70);
select tests.world();
select tests.publish_consent_documents('ei-510');
select tests.accept_current_consents(id) from tests.users;
-- Alias ausdrücklich setzen (initcap im Helfer hängt von der Kollation ab).
update public.profiles set alias = 'Alice 510' where id = tests.uid('alice');

create function pg_temp.as_user(p_name text, p_sql text) returns text language plpgsql as $$
declare v text;
begin
  perform tests.authenticate_as(p_name);
  execute p_sql into v;
  reset role;
  return v;
exception when others then
  reset role;
  return 'ERR ' || sqlstate || ' ' || sqlerrm;
end $$;
-- Budget je Aufruf zurücksetzen: Die Datei ruft mehr als 20 Einladungs-RPCs je Minute auf.
create function pg_temp.invite(p_kind text, p_player uuid default null, p_ttl integer default 20160, p_name text default 'alice')
returns text language plpgsql as $$
begin
  delete from ops.rate_limits;
  return pg_temp.as_user(p_name, format('select public.create_invite(%L, %L, %s)', p_kind, p_player, p_ttl));
end $$;
create function pg_temp.preview(p_name text, p_token text) returns text language plpgsql as $$
begin
  delete from ops.rate_limits;
  return pg_temp.as_user(p_name, format('select public.preview_invite(%L)::text', p_token));
end $$;
create function pg_temp.status(p_name text, p_token text) returns text language sql as $$
  select case when v like '{%' then v::jsonb ->> 'status' else v end from pg_temp.preview(p_name, p_token) v $$;
create function pg_temp.redeem(p_name text, p_token text) returns text language plpgsql as $$
begin
  delete from ops.rate_limits;
  return pg_temp.as_user(p_name, format('select public.redeem_invite(%L)::text', p_token));
end $$;
create function pg_temp.revoke(p_name text, p_id uuid) returns text language plpgsql as $$
declare v text;
begin
  delete from ops.rate_limits;
  v := pg_temp.as_user(p_name, format('select public.revoke_invite(%L)::text', p_id));
  return case when v = '' then 'ok' else v end;
end $$;
create function pg_temp.inv(p_token text) returns public.invites language sql as $$
  select * from public.invites where token_hash = sha256(convert_to(p_token, 'UTF8')) $$;
create function pg_temp.expires(p_token text) returns text language sql as $$
  select to_char((pg_temp.inv(p_token)).expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') $$;
create function pg_temp.open_count() returns int language sql as $$
  select count(*)::int from public.invites where created_by = tests.uid('alice') and used_at is null
    and revoked_at is null and expires_at > now() $$;

-- alice: Self und fünf Gäste. Test1 steht in drei Matches; in M2 steht bob schon direkt, M3 hat bob abgelehnt.
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
    jsonb_build_object('id', '51100000-0000-4000-8000-00000000000a', 'kind', 'self', 'displayName', 'Ich'),
    jsonb_build_object('id', '51100000-0000-4000-8000-000000000001', 'kind', 'guest', 'displayName', 'Test1'),
    jsonb_build_object('id', '51100000-0000-4000-8000-000000000002', 'kind', 'guest', 'displayName', 'Geloescht'),
    jsonb_build_object('id', '51100000-0000-4000-8000-000000000003', 'kind', 'guest', 'displayName', 'Redigiert'),
    jsonb_build_object('id', '51100000-0000-4000-8000-000000000004', 'kind', 'guest', 'displayName', 'Vergeben'),
    jsonb_build_object('id', '51100000-0000-4000-8000-000000000005', 'kind', 'guest', 'displayName', 'Zweiter')),
  'matches', jsonb_build_array(
    tests.match('51000000-0000-4000-8000-000000000001', jsonb_build_array(
      tests.part('51100000-0000-4000-8000-00000000000a', 'A', 1, 'Alice', null, '51100000-0000-4000-8000-00000000000a'),
      tests.part('51100000-0000-4000-8000-000000000001', 'B', 1, 'Test1', null, '51100000-0000-4000-8000-000000000001'))),
    tests.match('51000000-0000-4000-8000-000000000002', jsonb_build_array(
      tests.part('51100000-0000-4000-8000-00000000000a', 'A', 1, 'Alice', null, '51100000-0000-4000-8000-00000000000a'),
      tests.part('51100000-0000-4000-8000-0000000000b0', 'A', 2, 'Bob', tests.uid('bob')),
      tests.part('51100000-0000-4000-8000-000000000001', 'B', 1, 'Test1', null, '51100000-0000-4000-8000-000000000001'))),
    tests.match('51000000-0000-4000-8000-000000000003', jsonb_build_array(
      tests.part('51100000-0000-4000-8000-00000000000a', 'A', 1, 'Alice', null, '51100000-0000-4000-8000-00000000000a'),
      tests.part('51100000-0000-4000-8000-000000000001', 'B', 1, 'Test1', null, '51100000-0000-4000-8000-000000000001'))),
    tests.match('51000000-0000-4000-8000-000000000004', jsonb_build_array(
      tests.part('51100000-0000-4000-8000-00000000000a', 'A', 1, 'Alice', null, '51100000-0000-4000-8000-00000000000a'),
      tests.part('51100000-0000-4000-8000-000000000005', 'B', 1, 'Zweiter', null, '51100000-0000-4000-8000-000000000005')))))),
  'players', 1), 'ok', 'Gäste und Matches hochgeladen');
reset role;
insert into public.match_link_rejections (match_id, profile_id)
  values ('51000000-0000-4000-8000-000000000003', tests.uid('bob'));

-- Freundes-Link: ok für Nichtfreunde mit genau diesen Feldern, already_friends für Freunde, own für alice.
select set_config('tests.friend', pg_temp.invite('friend'), true);
select ok(current_setting('tests.friend') ~ '^[0-9a-f]{64}$', 'create_invite liefert weiter 64 Hex-Zeichen');
select is(pg_temp.preview('carol', current_setting('tests.friend')), jsonb_build_object('status', 'ok', 'kind', 'friend',
    'inviterId', tests.uid('alice'), 'inviterAlias', 'Alice 510',
    'expiresAt', pg_temp.expires(current_setting('tests.friend')))::text,
  'friend ok: status, kind, inviterId, inviterAlias, expiresAt – kein guestName/matchCount');
select ok(pg_temp.expires(current_setting('tests.friend')) ~ '^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$', 'expiresAt als UTC-ISO mit Z');
select is(pg_temp.status('bob', current_setting('tests.friend')), 'already_friends', 'Freund: already_friends');
select is(pg_temp.status('alice', current_setting('tests.friend')), 'own', 'eigener Link: own');
select ok((pg_temp.inv(current_setting('tests.friend'))).used_at is null, 'Vorschau verbraucht nichts');

-- Claim-Link: ok mit guestName und matchCount; matchCount = Antwort von redeem_invite (ohne M2 und M3).
select set_config('tests.claim', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000001'), true);
select is(pg_temp.preview('bob', current_setting('tests.claim')), jsonb_build_object('status', 'ok', 'kind', 'claim_player',
    'inviterId', tests.uid('alice'), 'inviterAlias', 'Alice 510', 'guestName', 'Test1', 'matchCount', 1,
    'expiresAt', pg_temp.expires(current_setting('tests.claim')))::text,
  'claim ok: zusätzlich guestName und matchCount (M2: bob steht schon drin, M3: abgelehnt)');
select is(pg_temp.preview('carol', current_setting('tests.claim'))::jsonb ->> 'matchCount', '3', 'carol würde alle drei übernehmen');
select is(pg_temp.redeem('bob', current_setting('tests.claim'))::jsonb ->> 'matchCount', '1', 'redeem übernimmt genau so viele');
select is(pg_temp.preview('carol', current_setting('tests.claim')), '{"status": "used"}', 'benutzter Link: nur {status: used}');
select is(pg_temp.status('bob', current_setting('tests.claim')), 'already_claimed', 'eigener Claim (Antwort verloren): already_claimed');
select is(pg_temp.status('alice', current_setting('tests.claim')), 'own', 'auch benutzt bleibt er für alice own');

-- S8: player_claimed an alice, nur IDs, zustellbar solange der Claim besteht.
update public.devices set push_token = 'tok-510-alice-phone', push_provider = 'fcm' where id = tests.device('a1');
select ok('player_claimed' = any (private.push_kinds()), 'player_claimed ist Push-Art');
select is((select private.push_message(o) from ops.notification_outbox o
   where o.profile_id = tests.uid('alice') and o.kind = 'player_claimed'),
  jsonb_build_object('v', 1, 'kind', 'player_claimed', 'profileId', tests.uid('bob'),
    'playerId', '51100000-0000-4000-8000-000000000001'), 'Nachricht nur mit Art, Einlöser und Gast-ID');
select ok((select private.push_deliverable(o) from ops.notification_outbox o
   where o.profile_id = tests.uid('alice') and o.kind = 'player_claimed'), 'Claim besteht: zustellbar');
select is(pg_temp.as_user('alice', $$select public.unassign_player('51100000-0000-4000-8000-000000000001')::text$$),
  '', 'alice löst die Verknüpfung');
select ok(not (select private.push_deliverable(o) from ops.notification_outbox o
   where o.profile_id = tests.uid('alice') and o.kind = 'player_claimed'), 'Claim gelöst: nicht mehr zustellbar');
-- Zweiter Claim desselben Einlösers (anderer Gast) innerhalb von 10 min fällt nicht mit dem ersten zusammen.
select set_config('tests.claim2', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000005'), true);
select is(pg_temp.redeem('bob', current_setting('tests.claim2'))::jsonb ->> 'matchCount', '1', 'bob übernimmt Zweiter');
select is((select count(*)::int from ops.notification_outbox where profile_id = tests.uid('alice') and kind = 'player_claimed'),
  2, 'zwei getrennte player_claimed-Einträge');
select ok((select private.push_deliverable(o) from ops.notification_outbox o where o.profile_id = tests.uid('alice')
   and o.kind = 'player_claimed' and o.payload ->> 'playerId' = '51100000-0000-4000-8000-000000000005'),
  'zweiter Claim zustellbar');
set local role service_role;
select is((select count(*)::int from jsonb_array_elements(public.claim_push_batch_v1(100)) e
   where e -> 'message' ->> 'kind' = 'player_claimed'), 1, 'claim_push_batch liefert nur den bestehenden Claim');
reset role;

-- already_claimed: Link offen, Gast inzwischen anderweitig zugeordnet.
select set_config('tests.taken', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000004'), true);
select is(pg_temp.as_user('alice', $$select public.assign_player('51100000-0000-4000-8000-000000000004', tests.uid('vera'))::text$$),
  '0', 'Gast an vera zugeordnet');
select is(pg_temp.status('carol', current_setting('tests.taken')), 'already_claimed', 'verknüpfter Gast: already_claimed');
select is(pg_temp.preview('carol', current_setting('tests.taken'))::jsonb ?| array['guestName', 'matchCount'], false,
  'already_claimed ohne guestName/matchCount');

-- expired
select set_config('tests.expired', pg_temp.invite('friend'), true);
update public.invites set expires_at = now() - interval '1 second' where id = (pg_temp.inv(current_setting('tests.expired'))).id;
select is(pg_temp.preview('carol', current_setting('tests.expired')), '{"status": "expired"}', 'abgelaufen: nur {status: expired}');
-- Selbst eingelöster Freundes-Link (Antwort verloren): already_friends statt used.
select set_config('tests.mine', pg_temp.invite('friend'), true);
select ok(pg_temp.redeem('carol', current_setting('tests.mine')) like '{%', 'carol löst Freundes-Link ein');
select is(pg_temp.preview('carol', current_setting('tests.mine'))::jsonb - 'expiresAt', jsonb_build_object('status', 'already_friends',
    'kind', 'friend', 'inviterId', tests.uid('alice'), 'inviterAlias', 'Alice 510'), 'eigener Freundes-Link: already_friends');
select is(pg_temp.status('vera', current_setting('tests.mine')), 'used', 'für andere bleibt er used');

-- invalid: unbekannt, Blockierung beide Richtungen, Ersteller nicht aktiv, Gast gelöscht bzw. redigiert – nur status.
select is(pg_temp.preview('carol', repeat('0', 64)), '{"status": "invalid"}', 'unbekannt: nur {status: invalid}');
select is(pg_temp.preview('carol', null), '{"status": "invalid"}', 'null-Token: invalid');
insert into public.user_blocks (blocker_id, blocked_id) values (tests.uid('dave'), tests.uid('alice'));
select is(pg_temp.preview('dave', current_setting('tests.friend')), '{"status": "invalid"}', 'dave blockiert alice: invalid');
select is(pg_temp.preview('dave', current_setting('tests.expired')), '{"status": "invalid"}', 'blockiert verdeckt auch expired');
delete from public.user_blocks;
insert into public.user_blocks (blocker_id, blocked_id) values (tests.uid('alice'), tests.uid('dave'));
select is(pg_temp.preview('dave', current_setting('tests.friend')), '{"status": "invalid"}', 'alice blockiert dave: invalid');
delete from public.user_blocks;
update public.profiles set status = 'deletion_requested' where id = tests.uid('alice');
select is(pg_temp.preview('carol', current_setting('tests.friend')), '{"status": "invalid"}', 'Ersteller in Löschung: invalid');
update public.profiles set status = 'active' where id = tests.uid('alice');
select set_config('tests.deleted', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000002'), true);
update public.players set deleted_at = now() where id = '51100000-0000-4000-8000-000000000002';
select is(pg_temp.preview('carol', current_setting('tests.deleted')), '{"status": "invalid"}', 'gelöschter Gast: invalid');
select set_config('tests.redacted', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000003'), true);
insert into private.player_write_redactions (player_id) values ('51100000-0000-4000-8000-000000000003');
select is(pg_temp.preview('carol', current_setting('tests.redacted')), '{"status": "invalid"}', 'redigierter Gast: invalid');
select is(pg_temp.status('alice', current_setting('tests.redacted')), 'invalid', 'auch für den Ersteller');

-- S5: zweiter Claim-Link für Test1 zieht den ersten zurück.
select set_config('tests.c1', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000001'), true);
select set_config('tests.c2', pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000001'), true);
select is(pg_temp.preview('carol', current_setting('tests.c1')), '{"status": "revoked"}', 'S5: alter Claim-Link nur {status: revoked}');
select is(pg_temp.status('carol', current_setting('tests.c2')), 'ok', 'S5: neuer Claim-Link ok');
select is((select count(*)::int from public.invites where player_id = '51100000-0000-4000-8000-000000000001'
   and used_at is null and revoked_at is null and expires_at > now()), 1, 'genau ein offener Claim-Link je Gast');
select is(pg_temp.redeem('carol', current_setting('tests.c1')), 'ERR P0001 invalid_invite', 'zurückgezogener Link ist ungültig');

-- revoke_invite: nur Ersteller, nur offene Links.
select is(pg_temp.revoke('carol', (pg_temp.inv(current_setting('tests.c2'))).id), 'ERR P0001 invite_not_found',
  'fremder Link: invite_not_found');
select is(pg_temp.revoke('alice', '00000000-0000-4000-8000-000000000000'), 'ERR P0001 invite_not_found', 'unbekannt: invite_not_found');
select is(pg_temp.revoke('alice', (pg_temp.inv(current_setting('tests.claim'))).id), 'ERR P0001 invite_not_found',
  'benutzter Link: invite_not_found');
select is(pg_temp.revoke('alice', (pg_temp.inv(current_setting('tests.expired'))).id), 'ERR P0001 invite_not_found',
  'abgelaufener Link: invite_not_found');
select is(pg_temp.revoke('alice', (pg_temp.inv(current_setting('tests.c2'))).id), 'ok', 'Ersteller widerruft offenen Link');
select is(pg_temp.status('carol', current_setting('tests.c2')), 'revoked', 'danach revoked');
select is(pg_temp.revoke('alice', (pg_temp.inv(current_setting('tests.c2'))).id), 'ERR P0001 invite_not_found',
  'zweiter Widerruf: invite_not_found');

-- TTL 10 min (QR).
select set_config('tests.qr', pg_temp.invite('friend', null, 10), true);
select is((pg_temp.inv(current_setting('tests.qr'))).expires_at, now() + interval '10 minutes', 'TTL 10 min: expires_at = jetzt + 10 min');

-- S6: höchstens 50 offene Einladungen; abgelaufene, benutzte und widerrufene zählen nicht.
insert into public.invites (token_hash, kind, created_by, expires_at, used_at, revoked_at)
  select sha256(convert_to('s6-closed-' || g, 'UTF8')), 'friend', tests.uid('alice'),
    case when g % 3 = 0 then now() - interval '1 minute' else now() + interval '1 day' end,
    case when g % 3 = 1 then now() end, case when g % 3 = 2 then now() end
  from generate_series(1, 30) g;
insert into public.invites (token_hash, kind, created_by, expires_at)
  select sha256(convert_to('s6-open-' || g, 'UTF8')), 'friend', tests.uid('alice'), now() + interval '1 day'
  from generate_series(1, 49 - pg_temp.open_count()) g;
select is(pg_temp.open_count(), 49, '49 offene Einladungen');
select ok(pg_temp.invite('friend') ~ '^[0-9a-f]{64}$', '50. offene Einladung geht');
select is(pg_temp.invite('friend'), 'ERR P0001 too_many_open_invites', '51.: too_many_open_invites');
select is(pg_temp.invite('claim_player', '51100000-0000-4000-8000-000000000001'), 'ERR P0001 too_many_open_invites',
  'auch Claim-Links');
select is(pg_temp.open_count(), 50, 'nichts angelegt');
select is(pg_temp.invite('friend', null, 20160, 'bob') ~ '^[0-9a-f]{64}$', true, 'Grenze gilt je Konto');
select is(pg_temp.revoke('alice', (pg_temp.inv(current_setting('tests.qr'))).id), 'ok', 'alice widerruft einen');
select ok(pg_temp.invite('friend') ~ '^[0-9a-f]{64}$', 'danach wieder eine frei');

-- S7: Aufräumjob löscht nur Zeilen, deren Nutzung, Widerruf bzw. Ablauf über 30 Tage zurückliegt.
delete from public.invites;
insert into public.invites (token_hash, kind, created_by, expires_at, used_at, revoked_at, created_at)
  values (sha256('\x01'::bytea), 'friend', tests.uid('alice'), now() - interval '20 days', now() - interval '31 days', null, now() - interval '40 days'),
         (sha256('\x02'::bytea), 'friend', tests.uid('alice'), now() - interval '20 days', now() - interval '29 days', null, now() - interval '40 days'),
         (sha256('\x03'::bytea), 'friend', tests.uid('alice'), now() - interval '20 days', null, now() - interval '31 days', now() - interval '40 days'),
         (sha256('\x04'::bytea), 'friend', tests.uid('alice'), now() - interval '20 days', null, now() - interval '29 days', now() - interval '40 days'),
         (sha256('\x05'::bytea), 'friend', tests.uid('alice'), now() - interval '31 days', null, null, now() - interval '40 days'),
         (sha256('\x06'::bytea), 'friend', tests.uid('alice'), now() - interval '29 days', null, null, now() - interval '40 days'),
         (sha256('\x07'::bytea), 'friend', tests.uid('alice'), now() + interval '1 day', null, null, now() - interval '40 days');
create function pg_temp.invites_cron() returns boolean language plpgsql as $$
declare v_ok boolean;
begin
  execute $q$select count(*) = 1 and bool_and(active) from cron.job where jobname = 'advantly-invites-cleanup-v1'
    and schedule = '37 3 * * *' and command = 'select private.purge_invites();'$q$ into v_ok;
  return v_ok;
end;
$$;
select case when exists (select 1 from pg_extension where extname = 'pg_cron')
  then ok(pg_temp.invites_cron(), 'Cronjob advantly-invites-cleanup-v1 täglich')
  else skip('pg_cron fehlt: Planung im Supabase-Stack nachweisen') end;
select lives_ok($$select private.purge_invites()$$, 'purge_invites läuft');
select is((select array_agg(get_byte(token_hash, 0) order by token_hash) from public.invites),
  (select array_agg(get_byte(sha256(x), 0) order by sha256(x)) from unnest(array['\x02'::bytea, '\x04', '\x06', '\x07']) x),
  'nur alte benutzte, widerrufene und abgelaufene Zeilen gelöscht');

-- Rechte, Funktionsköpfe, Cloud-Bindung.
select ok(not has_function_privilege('anon', 'public.preview_invite(text)', 'execute')
  and not has_function_privilege('anon', 'public.revoke_invite(uuid)', 'execute'), 'anon darf keine neuen RPCs ausführen');
select ok(has_function_privilege('authenticated', 'public.preview_invite(text)', 'execute')
  and has_function_privilege('authenticated', 'public.revoke_invite(uuid)', 'execute'), 'authenticated darf');
select ok(not has_function_privilege('authenticated', 'private.purge_invites()', 'execute')
  and not has_function_privilege('service_role', 'private.purge_invites()', 'execute'), 'Aufräumjob nicht öffentlich');
select is((select array_agg(concat_ws('|', p.prosecdef, p.proconfig::text, pg_get_function_result(p.oid)) order by p.proname)
  from pg_proc p where p.oid in ('public.preview_invite(text)'::regprocedure, 'public.revoke_invite(uuid)'::regprocedure)),
  array['t|{"search_path=\"\""}|jsonb', 't|{"search_path=\"\""}|void'], 'security definer, leerer search_path');
select is((select concat_ws('|', p.prosecdef, p.proconfig::text, pg_get_function_result(p.oid), p.proowner::regrole,
    has_function_privilege('anon', p.oid, 'execute'), has_function_privilege('authenticated', p.oid, 'execute'))
  from pg_proc p where p.oid = 'public.create_invite(text,uuid,integer)'::regprocedure),
  't|{"search_path=\"\""}|text|postgres|f|t', 'create_invite: Kopf und Rechte unverändert');
select tests.authenticate_as('carol');
select throws_ok($$select token_hash from public.invites$$, '42501', null, 'token_hash für authenticated unlesbar');
select set_config('request.headers', (current_setting('request.headers')::jsonb
  || jsonb_build_object('x-advantly-installation-proof', repeat('b2', 32)))::text, true);
select throws_ok($$select public.preview_invite(repeat('0', 64))$$, '42501', 'device_not_authorized',
  'preview_invite verlangt Cloud-Bindung');
select throws_ok($$select public.revoke_invite('00000000-0000-4000-8000-000000000000')$$, '42501', 'device_not_authorized',
  'revoke_invite verlangt Cloud-Bindung');
select tests.authenticate_anon();
select throws_ok($$select public.preview_invite(repeat('0', 64))$$, '42501', null, 'anon: preview_invite verweigert');
reset role;
select * from finish();
rollback;
