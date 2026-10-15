-- Freundschaften: Anfrage, Annahme, Gegenanfrage, Ablehnung, Blockieren, Einladungslink/QR.
begin;
select plan(19);

select tests.world();

select tests.authenticate_as('carol');
select is(public.send_friend_request(tests.uid('dave'), 'code'), 'requested', 'Anfrage carol → dave');
select is(public.send_friend_request(tests.uid('dave')), 'requested', 'Wiederholung ist idempotent');
select throws_ok($$ select public.send_friend_request(tests.uid('carol')) $$, 'P0001', 'cannot_befriend_self',
                 'Keine Freundschaft mit sich selbst');

select tests.authenticate_as('alice');
select is_empty($$ select 1 from public.friendships where tests.uid('dave') in (requester_id, addressee_id) $$,
                'Fremde sehen Anfragen anderer nicht');
select throws_ok($$ select public.respond_friend_request(tests.uid('carol'), true) $$, 'P0001', 'no_pending_request',
                 'Niemand nimmt stellvertretend an');
select throws_ok($$ insert into public.friendships (requester_id, addressee_id, status)
                    values (tests.uid('alice'), tests.uid('dave'), 'accepted') $$, '42501', null,
                 'Freundschaften nicht direkt schreibbar');

select tests.authenticate_as('dave');
select set_config('tests.dave_epoch', public.sync_pull(0) ->> 'visibilityEpoch', true);
select is(public.send_friend_request(tests.uid('carol')), 'accepted', 'Gegenanfrage nimmt die offene Anfrage an');
select ok((public.sync_pull(0) ->> 'visibilityEpoch')::bigint > current_setting('tests.dave_epoch')::bigint,
          'neue Freundschaft löst Vollabgleich aus (ältere geteilte Matches des Freundes)');
select results_eq($$ select status from public.friendships where tests.uid('carol') in (requester_id, addressee_id) and tests.uid('dave') in (requester_id, addressee_id) $$,
                  $$ values ('accepted'::text) $$, 'genau eine Zeile pro Paar, akzeptiert');
select isnt_empty($$ select 1 from public.profiles where id = tests.uid('carol') $$, 'Freund sieht Profil');

-- Ablehnen + Wartezeit
select tests.authenticate_as('vera');
select is(public.send_friend_request(tests.uid('dave')), 'requested', 'Anfrage vera → dave');
select tests.authenticate_as('dave');
select lives_ok($$ select public.respond_friend_request(tests.uid('vera'), false) $$, 'dave lehnt ab');
select tests.authenticate_as('vera');
select throws_ok($$ select public.send_friend_request(tests.uid('dave')) $$, 'P0001', 'request_cooldown',
                 'Erneute Anfrage erst nach 30 Tagen');

-- Blockieren beendet Freundschaft, Suche und Anfragen
select tests.authenticate_as('dave');
select lives_ok($$ select public.block_user(tests.uid('carol')) $$, 'dave blockiert carol');
select tests.authenticate_as('carol');
select is_empty($$ select 1 from public.profiles where id = tests.uid('dave') $$, 'Blockierter sieht Profil nicht mehr');
select is_empty($$ select 1 from public.find_profile('dave') $$, 'Blockierte finden sich nicht');
select throws_ok($$ select public.send_friend_request(tests.uid('dave')) $$, 'P0001', 'profile_not_found',
                 'Blockierte können nicht anfragen (nicht unterscheidbar von „nicht vorhanden“)');

-- Einladungslink/QR: Teilen = Zustimmung, einmalig
select tests.authenticate_as('carol');
select set_config('tests.token', public.create_invite('friend', null, 10), true);
select tests.authenticate_as('vera');
select is((public.redeem_invite(current_setting('tests.token'))) ->> 'kind', 'friend', 'QR/Link einlösen → Freundschaft');
select throws_ok($$ select public.redeem_invite(current_setting('tests.token')) $$, 'P0001', 'invalid_invite',
                 'Einladung ist einmalig');

select * from finish();
rollback;
