-- Plan Einladungen §2.1 S3/S4 (Release 0.6.4): redeem_invite übernimmt keine gelöschten bzw. redigierten Gäste, verbraucht
-- einen Freundes-Link unter Freunden nicht und schickt dann kein „hat angenommen“. Blockierte lösen nichts ein. Signatur,
-- security definer, search_path und Rechte bleiben unverändert.
begin;
set local timezone = 'UTC';
select plan(27);
select tests.world();
select tests.publish_consent_documents('ei-490');
select tests.accept_current_consents(id) from tests.users;

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
create function pg_temp.invite(p_kind text, p_player uuid default null) returns text language plpgsql as $$
declare v text;
begin
  perform tests.authenticate_as('alice');
  v := public.create_invite(p_kind, p_player);
  reset role;
  return v;
end $$;
create function pg_temp.redeem(p_name text, p_token text) returns text language sql as $$
  select pg_temp.as_user(p_name, format('select public.redeem_invite(%L)::text', p_token)) $$;
-- Antwort-Feld oder die Fehlermeldung unverändert.
create function pg_temp.field(p text, k text) returns text language sql as $$
  select case when p like '{%' then p::jsonb ->> k else p end $$;
create function pg_temp.outbox(p_kind text) returns int language sql as $$
  select count(*)::int from ops.notification_outbox where profile_id = tests.uid('alice') and kind = p_kind $$;
create function pg_temp.unused(p_token text) returns boolean language sql as $$
  select used_at is null and used_by is null from public.invites
  where token_hash = sha256(convert_to(p_token, 'UTF8')) $$;

-- alice: Self und drei Gäste mit je einem Match.
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
    jsonb_build_object('id', '49100000-0000-4000-8000-00000000000a', 'kind', 'self', 'displayName', 'Ich'),
    jsonb_build_object('id', '49100000-0000-4000-8000-000000000001', 'kind', 'guest', 'displayName', 'Geloescht'),
    jsonb_build_object('id', '49100000-0000-4000-8000-000000000002', 'kind', 'guest', 'displayName', 'Redigiert'),
    jsonb_build_object('id', '49100000-0000-4000-8000-000000000003', 'kind', 'guest', 'displayName', 'Bob als Gast')),
  'matches', (select jsonb_agg(tests.match(('49000000-0000-4000-8000-00000000000' || g)::uuid, jsonb_build_array(
      tests.part('49100000-0000-4000-8000-00000000000a', 'A', 1, 'Alice', null, '49100000-0000-4000-8000-00000000000a'),
      tests.part(('49100000-0000-4000-8000-00000000000' || g), 'B', 1, 'Gast', null, ('49100000-0000-4000-8000-00000000000' || g)::uuid))))
    from generate_series(1, 3) g))),
  'players', 1), 'ok', 'Gäste und Matches hochgeladen');
reset role;

-- S3: Gast nach dem Erzeugen des Links gelöscht.
select set_config('tests.deleted', pg_temp.invite('claim_player', '49100000-0000-4000-8000-000000000001'), true);
select is(pg_temp.as_user('alice', $$select tests.result(public.sync_push(jsonb_build_object('players', jsonb_build_array(
  jsonb_build_object('id', '49100000-0000-4000-8000-000000000001', 'kind', 'guest', 'displayName', 'Geloescht', 'deleted', true)))),
  'players', 0)$$), 'ok', 'alice löscht den Gast');
select is(pg_temp.redeem('carol', current_setting('tests.deleted')), 'ERR P0001 invalid_invite', 'gelöschter Gast: ungültig');
select is((select profile_id from public.players where id = '49100000-0000-4000-8000-000000000001'), null, 'gelöschter Gast bleibt frei');
select ok(pg_temp.unused(current_setting('tests.deleted')), 'Link nicht verbraucht');
select is((select count(*)::int from public.match_participants where profile_id = tests.uid('carol')), 0, 'carol bekommt kein Match');
select ok(not private.are_friends(tests.uid('alice'), tests.uid('carol')), 'keine Freundschaft entstanden');

-- Redigierter Gast (Kontolöschung o. Ä. nach dem Erzeugen): weiterhin abgelehnt.
select set_config('tests.redacted', pg_temp.invite('claim_player', '49100000-0000-4000-8000-000000000002'), true);
insert into private.player_write_redactions (player_id) values ('49100000-0000-4000-8000-000000000002');
select is(pg_temp.redeem('carol', current_setting('tests.redacted')), 'ERR P0001 player_redacted', 'redigierter Gast: abgelehnt');
select is((select profile_id from public.players where id = '49100000-0000-4000-8000-000000000002'), null, 'redigierter Gast bleibt frei');
select ok(pg_temp.unused(current_setting('tests.redacted')), 'Link nicht verbraucht');

-- S4: Freundes-Link an einen Freund (bob) – nicht verbraucht, kein Push.
select set_config('tests.friend', pg_temp.invite('friend'), true);
select is(pg_temp.redeem('bob', current_setting('tests.friend')),
  jsonb_build_object('kind', 'friend', 'friendId', tests.uid('alice'), 'alreadyFriends', true)::text, 'schon befreundet: alreadyFriends');
select ok(pg_temp.unused(current_setting('tests.friend')), 'Link bleibt gültig');
select is(pg_temp.outbox('friend_accepted'), 0, 'kein friend_accepted an alice');
select is((select count(*)::int from public.friendships where status = 'accepted'
  and least(requester_id, addressee_id) = least(tests.uid('alice'), tests.uid('bob'))
  and greatest(requester_id, addressee_id) = greatest(tests.uid('alice'), tests.uid('bob'))), 1, 'Freundschaft unverändert');
-- Derselbe Link funktioniert danach für eine neue Person.
select is(pg_temp.field(pg_temp.redeem('carol', current_setting('tests.friend')), 'kind'), 'friend', 'carol löst denselben Link ein');
select ok(private.are_friends(tests.uid('alice'), tests.uid('carol')), 'carol ist jetzt befreundet');
select ok(not pg_temp.unused(current_setting('tests.friend')), 'jetzt verbraucht');
select is(pg_temp.outbox('friend_accepted'), 1, 'genau ein friend_accepted (carol)');

-- Claim durch einen Freund: Claim geht, die Freundschaft bleibt, kein zweites „hat angenommen“.
select set_config('tests.claim', pg_temp.invite('claim_player', '49100000-0000-4000-8000-000000000003'), true);
select is(pg_temp.field(pg_temp.redeem('bob', current_setting('tests.claim')), 'matchCount'), '1', 'bob übernimmt seinen Gast');
select is(pg_temp.outbox('player_claimed'), 1, 'player_claimed an alice');
select is(pg_temp.outbox('friend_accepted'), 1, 'kein friend_accepted für die bestehende Freundschaft');

-- Blockiert, in beiden Richtungen: ungültig wie ein unbekannter Link, nichts verbraucht.
select set_config('tests.blocked', pg_temp.invite('friend'), true);
insert into public.user_blocks (blocker_id, blocked_id) values (tests.uid('dave'), tests.uid('alice'));
select is(pg_temp.redeem('dave', current_setting('tests.blocked')), 'ERR P0001 invalid_invite', 'dave blockiert alice: ungültig');
delete from public.user_blocks;
insert into public.user_blocks (blocker_id, blocked_id) values (tests.uid('alice'), tests.uid('dave'));
select is(pg_temp.redeem('dave', current_setting('tests.blocked')), 'ERR P0001 invalid_invite', 'alice blockiert dave: ungültig');
select ok(pg_temp.unused(current_setting('tests.blocked')) and not private.are_friends(tests.uid('alice'), tests.uid('dave')),
  'nicht verbraucht, keine Freundschaft');

-- Funktionskopf unverändert.
select is((select concat_ws('|', p.prosecdef, p.proconfig::text, pg_get_function_result(p.oid), p.proowner::regrole)
  from pg_proc p where p.oid = 'public.redeem_invite(text)'::regprocedure),
  't|{"search_path=\"\""}|jsonb|postgres', 'security definer, search_path, Rückgabetyp, Owner');
select ok(has_function_privilege('authenticated', 'public.redeem_invite(text)', 'execute'), 'authenticated darf ausführen');
select ok(not has_function_privilege('anon', 'public.redeem_invite(text)', 'execute'), 'anon nicht');
select * from finish();
rollback;
