-- K4/K5: Kontosuche, minimaler Status, Sperre, Geräte-/Sitzungswiderruf und Löschrücknahme durch admin.
begin;
select no_plan();
select tests.create_user('k5_target');
select tests.create_user('k5_twin');
select tests.create_user('k5_friend');
select tests.create_user('k5_support');
select tests.create_user('k5_admin');
select tests.create_user('k5_revoked');
update public.profiles set alias = 'Same Alias' where id in (tests.uid('k5_twin'), tests.uid('k5_friend'));
insert into ops.support_members(id, role) values (tests.uid('k5_support'), 'support'), (tests.uid('k5_admin'), 'admin'),
  (tests.uid('k5_revoked'), 'admin');
update ops.support_members set revoked_at = now() where id = tests.uid('k5_revoked');
select tests.publish_consent_documents();
select tests.accept_current_consents(tests.uid('k5_target'));
select tests.accept_current_consents(tests.uid('k5_twin'));
select set_config('tests.k5_code', (select friend_code from public.profiles where id = tests.uid('k5_target')), true);

create function pg_temp.operator(p_identifier text, p_aal text default 'aal2') returns void language plpgsql as $$
begin
  perform tests.authenticate_as(p_identifier);
  perform set_config('request.jwt.claims', (current_setting('request.jwt.claims')::jsonb
    || jsonb_build_object('aal', p_aal))::text, true);
end;
$$;
grant execute on function pg_temp.operator(text, text) to authenticated;
-- Erwartete Erfolge: Ein Fehler (etwa nach einer Mutation) wird zum lesbaren Fehlschlag statt die Datei abzubrechen.
create function pg_temp.try(p_query text) returns text language plpgsql as $$
declare v_result text;
begin
  execute p_query into v_result;
  return v_result;
exception when others then
  return 'ERROR: ' || sqlerrm;
end;
$$;
grant execute on function pg_temp.try(text) to authenticated;

-- Rechte: nur RPCs, keine Tabellen oder Helfer.
select ok(not has_table_privilege('authenticated', 'private.account_suspensions', 'SELECT'), 'Keine direkte Sperrtabelle');
select ok(not has_table_privilege('authenticated', 'ops.account_audit', 'SELECT'), 'Kein direktes Konto-Audit');
select ok(not has_table_privilege('service_role', 'ops.account_audit', 'UPDATE'), 'Audit nicht änderbar für service_role');
select is_empty($$select p.oid::regprocedure from pg_proc p where p.oid in ('private.account_status(uuid)'::regprocedure,
  'private.require_support_admin()'::regprocedure, 'private.is_suspended(uuid)'::regprocedure,
  'private.account_target(uuid,uuid)'::regprocedure, 'private.support_reason(text)'::regprocedure)
  and exists (select 1 from unnest(array['anon','authenticated','service_role']) r where has_function_privilege(r, p.oid, 'execute'))$$,
  'Private Kontohelfer bleiben privat');
select ok(not has_function_privilege('anon', 'public.admin_account_lookup(text)', 'execute'), 'Kein anonymer Zugriff');

-- Suche: Rolle und AAL2 bei jedem Aufruf.
select tests.authenticate_as('k5_target');
select throws_ok($$select public.admin_account_lookup('k5_twin')$$, 'P0001', 'support_forbidden', 'Normales Konto sucht nicht');
select pg_temp.operator('k5_support', 'aal1');
select throws_ok($$select public.admin_account_lookup('Same Alias')$$, 'P0001', 'support_mfa_required', 'support ohne AAL2 verweigert');
select pg_temp.operator('k5_revoked');
select throws_ok($$select public.admin_account_lookup('Same Alias')$$, 'P0001', 'support_forbidden', 'Widerrufene Mitgliedschaft trotz AAL2');
select pg_temp.operator('k5_support');
select is(public.admin_account_lookup(tests.uid('k5_target')::text)->'account'->>'profileId', tests.uid('k5_target')::text,
  'Exakte UUID');
select is(public.admin_account_lookup(upper(tests.uid('k5_target')::text))->'account'->>'profileId',
  tests.uid('k5_target')::text, 'UUID unabhängig von Groß-/Kleinschreibung');
select is(public.admin_account_lookup(lower(current_setting('tests.k5_code')))
  ->'account'->>'profileId', tests.uid('k5_target')::text, 'Freundescode wie find_profile normalisiert');
select is(public.admin_account_lookup('K5_Target')->'account'->>'profileId', tests.uid('k5_target')::text, 'Exakter Alias');
select throws_ok($$select public.admin_account_lookup('K5_Targ')$$, 'P0001', 'account_not_found', 'Kein Teiltreffer');
select throws_ok($$select public.admin_account_lookup('k5_target')$$, 'P0001', 'account_not_found', 'Alias exakt mit Groß/Klein');
select throws_ok($$select public.admin_account_lookup('Same Alias')$$, 'P0001', 'account_ambiguous', 'Mehrdeutig ohne Liste');
select throws_ok($$select public.admin_account_lookup('   ')$$, 'P0001', 'support_invalid', 'Leere Suche');
select throws_ok($$select public.admin_account_lookup(repeat('x', 121))$$, 'P0001', 'support_invalid', 'Zu lange Suche');
select is(public.admin_account_lookup('K5_Target')->>'viewerRole', 'support', 'Rolle für die Oberfläche');
select throws_ok($$select public.admin_account_lookup(tests.uid('k5_support')::text)$$, 'P0001', 'account_not_found',
  'Eigenes Konto per UUID nicht auffindbar');
select throws_ok($$select public.admin_account_lookup('K5_Support')$$, 'P0001', 'account_not_found',
  'Eigenes Konto per Alias nicht auffindbar');
select is((select array_agg(k order by k) from jsonb_object_keys(public.admin_account_lookup('K5_Target')->'account') k),
  array['activeDevices','alias','canCancelDeletion','consentStatus','consentVersions','deletionRequestedAt',
    'earliestPurgeAt','lastActivityDay','profileId','revokedDevices','status','suspended','suspendedAt'],
  'Minimaler Status: genau diese Felder (keine E-Mail, Matches, Health, Geburtsdaten)');
select is(public.admin_account_lookup('K5_Target')->'account'->>'consentStatus', 'CURRENT', 'Zustimmungsstatus');
select is(public.admin_account_lookup('K5_Target')->'account'->'consentVersions',
  '{"TERMS":"cloud-test-v1","PRIVACY":"cloud-test-v1"}'::jsonb, 'Akzeptierte Zustimmungsversion');
select is(public.admin_account_lookup(tests.uid('k5_friend')::text)->'account'->'consentVersions',
  '{"TERMS":null,"PRIVACY":null}'::jsonb, 'Ohne Zustimmung keine Version');
select set_config('tests.k5_day', public.admin_account_lookup('K5_Target')->'account'->>'lastActivityDay', true);
reset role;
select is(current_setting('tests.k5_day'), (select to_char(greatest(max(d.last_seen_at), max(b.created_at)) at time zone 'UTC',
  'YYYY-MM-DD') from public.devices d join private.cloud_session_bindings b on b.profile_id = d.profile_id
  where d.profile_id = tests.uid('k5_target')), 'Letzte Aktivität nur auf den Tag genau');
select pg_temp.operator('k5_support');

-- Zustandsändernde Aktionen: nur admin, AAL2, Begründung. Ziel ist ein Nebenkonto: Lässt eine Mutation eine Aktion
-- durch, bleibt das als einzelner Fehlschlag sichtbar und bricht den Ablauf am Zielkonto nicht ab.
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, 'Missbrauch gemeldet')$$,
  'P0001', 'support_admin_required', 'support darf nicht sperren');
select throws_ok($$select public.admin_account_revoke_devices(tests.uid('k5_friend'), 'Gerät verloren')$$,
  'P0001', 'support_admin_required', 'support darf keine Geräte widerrufen');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_friend'), gen_random_uuid(), 'Nutzerwunsch')$$,
  'P0001', 'support_admin_required', 'support darf keine Löschung zurücknehmen');
select pg_temp.operator('k5_admin', 'aal1');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, 'Missbrauch gemeldet')$$,
  'P0001', 'support_mfa_required', 'admin ohne AAL2 sperrt nicht');
select throws_ok($$select public.admin_account_revoke_devices(tests.uid('k5_friend'), 'Gerät verloren')$$,
  'P0001', 'support_mfa_required', 'admin ohne AAL2 widerruft nicht');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_friend'), gen_random_uuid(), 'Nutzerwunsch')$$,
  'P0001', 'support_mfa_required', 'admin ohne AAL2 nimmt nicht zurück');
select pg_temp.operator('k5_revoked');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, 'Missbrauch gemeldet')$$,
  'P0001', 'support_forbidden', 'Widerrufener admin sperrt nicht');
select pg_temp.operator('k5_admin');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, null)$$,
  'P0001', 'support_invalid', 'Sperre ohne Begründung');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, ' ' || chr(160) || E'\t')$$,
  'P0001', 'support_invalid', 'Sperre mit leerer Begründung');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, repeat('x', 501))$$,
  'P0001', 'support_invalid', 'Sperre mit zu langer Begründung');
select throws_ok($$select public.admin_account_revoke_devices(tests.uid('k5_friend'), '')$$,
  'P0001', 'support_invalid', 'Widerruf ohne Begründung');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_friend'), gen_random_uuid(), null)$$,
  'P0001', 'support_invalid', 'Rücknahme ohne Begründung');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_admin'), true, 'Selbsttest')$$,
  'P0001', 'support_invalid', 'Kein Eingriff ins eigene Konto');
select throws_ok($$select public.admin_account_set_suspended('30000000-0000-4000-8000-0000000000aa', true, 'Unbekannt')$$,
  'P0001', 'account_not_found', 'Unbekanntes Konto');
reset role;
select is((select count(*)::int from ops.account_audit), 0, 'Abgewiesene Aktionen schreiben kein Audit');
select is((select count(*)::int from private.account_suspensions), 0, 'Abgewiesene Aktionen sperren nicht');

-- Sperren: wirkt auf Login-Status und alle Cloud-Wege des Kontos, nicht auf Kontorechte und nicht auf andere.
select tests.authenticate_as('k5_target');
select lives_ok($$select public.cloud_sync_pull_v1(0, 1)$$, 'Vor der Sperre: Pull erlaubt');
select pg_temp.operator('k5_admin');
select is((public.admin_account_set_suspended(tests.uid('k5_target'), true, '  Missbrauch laut Ticket  ')->>'suspended')::boolean,
  true, 'admin sperrt');
select is((public.admin_account_set_suspended(tests.uid('k5_target'), true, 'Doppelklick')->>'suspended')::boolean,
  true, 'Erneutes Sperren idempotent');
reset role;
select is((select count(*)::int from ops.account_audit where target_id = tests.uid('k5_target')), 1,
  'Idempotentes Sperren schreibt ein Audit');
select results_eq($$select action, reason, actor_id from ops.account_audit where target_id = tests.uid('k5_target')$$,
  $$values ('account_suspend'::text, 'Missbrauch laut Ticket'::text, tests.uid('k5_admin'))$$,
  'Audit: Aktion, getrimmte Begründung, Akteur');
select ok(private.is_active(tests.uid('k5_target')), 'Sichtbarkeit für andere unverändert (kein G-Wechsel)');
select tests.authenticate_as('k5_target');
select throws_ok($$select public.cloud_sync_pull_v1(0, 1)$$, '42501', 'account_suspended', 'Gesperrt: Pull');
select throws_ok($$select public.cloud_sync_push_v1('{}')$$, '42501', 'account_suspended', 'Gesperrt: Push');
select throws_ok($$select public.bind_cloud_session_v1((select installation_id from tests.cloud_context
  where profile_id = tests.uid('k5_target')), (select device_id from tests.cloud_context where profile_id = tests.uid('k5_target')))$$,
  '42501', 'account_suspended', 'Gesperrt: Bind');
select throws_ok($$select public.find_profile('k5_twin')$$, '42501', 'account_suspended', 'Gesperrt: Social-RPC');
select is_empty($$select 1 from public.devices$$, 'Gesperrt: direkte Tabellen per RLS leer');
select is((public.my_auth_profile()->>'suspended')::boolean, true, 'Login-Profil meldet Sperre');
select lives_ok($$select public.export_my_data()$$, 'Gesperrt: Export bleibt');
select lives_ok($$select public.my_support_tickets()$$, 'Gesperrt: Support bleibt');
select lives_ok($$select public.cloud_list_devices_v1()$$, 'Gesperrt: eigene Geräteliste bleibt');
select lives_ok($$select public.account_deletion_status_v1()$$, 'Gesperrt: Löschstatus bleibt');
select tests.authenticate_as('k5_twin');
select is((public.my_auth_profile()->>'suspended')::boolean, false, 'Anderes Konto ungesperrt');
select lives_ok($$select public.cloud_sync_pull_v1(0, 1)$$, 'Anderes Konto synchronisiert weiter');
select pg_temp.operator('k5_admin');
select is((public.admin_account_set_suspended(tests.uid('k5_target'), false, 'Klärung abgeschlossen')->>'suspended')::boolean,
  false, 'admin entsperrt');
select tests.authenticate_as('k5_target');
select lives_ok($$select public.cloud_sync_pull_v1(0, 1)$$, 'Nach Entsperren: Pull wieder erlaubt');
reset role;
select is((select action from ops.account_audit where target_id = tests.uid('k5_target') order by id desc limit 1),
  'account_unsuspend', 'Entsperren protokolliert');

-- G6: Ein anderes gebundenes eigenes Gerät belebt eine widerrufene Geräte-ID nicht per Upload wieder.
insert into public.devices(id, profile_id, kind, platform) values ('30000000-0000-4000-8000-000000000001',
  tests.uid('k5_target'), 'phone', 'android');
select tests.authenticate_as('k5_target');
select lives_ok($$select public.cloud_revoke_device_v1('30000000-0000-4000-8000-000000000001')$$, 'Altes Handy widerrufen');
select is(public.cloud_sync_push_v1('{"devices":[{"id":"30000000-0000-4000-8000-000000000001",
  "kind":"phone","platform":"android"}]}')->'devices',
  '[{"id":"30000000-0000-4000-8000-000000000001","result":"rejected","reason":"device_revoked"}]'::jsonb,
  'Geschwistergerät belebt widerrufene ID nicht (Einzelablehnung, Upload selbst läuft)');
reset role;
select ok((select revoked_at is not null from public.devices where id = '30000000-0000-4000-8000-000000000001'),
  'Widerruf bleibt bestehen');

-- Geräte- und Sitzungswiderruf durch admin: Push-Token weg, Pull/Push zu, kein Eingriff in auth.sessions.
update public.devices set push_provider = 'fcm', push_token = 'k5-token'
  where id = (select device_id from tests.cloud_context where profile_id = tests.uid('k5_target'));
select pg_temp.operator('k5_admin');
select is((public.admin_account_revoke_devices(tests.uid('k5_target'), 'Gerät gestohlen')->>'activeDevices')::int, 0,
  'admin widerruft alle Geräte');
select is((public.admin_account_revoke_devices(tests.uid('k5_target'), 'Nochmals')->>'activeDevices')::int, 0,
  'Erneuter Widerruf idempotent');
reset role;
select is((select jsonb_agg(changes) from ops.account_audit
    where target_id = tests.uid('k5_target') and action = 'account_revoke_devices'),
  '[{"devices":1,"installations":1,"sessions":1}]'::jsonb, 'Audit zählt nur, ohne Gerätedetails');
select is((select count(*)::int from ops.account_audit where target_id = tests.uid('k5_target') and action = 'account_revoke_devices'),
  1, 'Idempotenter Widerruf schreibt ein Audit');
select ok((select bool_and(push_token is null and push_provider is null and revoked_at is not null) from public.devices
  where profile_id = tests.uid('k5_target')), 'Push-Token entfernt, alle Geräte widerrufen');
select is((select count(*)::int from auth.sessions where user_id = tests.uid('k5_target')), 1, 'auth.sessions unverändert');
select tests.authenticate_as('k5_target');
select throws_ok($$select public.cloud_sync_pull_v1(0, 1)$$, '42501', 'device_revoked', 'Nach Widerruf: Pull');
select throws_ok($$select public.cloud_sync_push_v1('{}')$$, '42501', 'device_revoked', 'Nach Widerruf: Push');
select throws_ok($$select public.bind_cloud_session_v1((select installation_id from tests.cloud_context
  where profile_id = tests.uid('k5_target')), (select device_id from tests.cloud_context where profile_id = tests.uid('k5_target')))$$,
  '42501', 'device_revoked', 'Nach Widerruf: keine Selbstentsperrung durch erneute Bindung');
select tests.authenticate_as('k5_twin');
select lives_ok($$select public.cloud_sync_pull_v1(0, 1)$$, 'Fremdes Konto unberührt');

-- Löschrücknahme nur mit Meldung nach dem Antrag (Zielkonto oder Gast mit Zuordnungsbeleg); Token-Refresh/Login
-- nimmt nichts zurück.
select tests.authenticate_as('k5_target');
select is(public.submit_support('30000000-0000-4000-8000-000000000101', repeat('a', 64), 'bug', 'Alt', 'Zähler hängt')->>'id',
  '30000000-0000-4000-8000-000000000101', 'Ältere, themenfremde Meldung');
select tests.authenticate_as('k5_twin');
select is(public.submit_support('30000000-0000-4000-8000-000000000102', repeat('b', 64), 'feedback', 'Fremd', 'Fremd')->>'id',
  '30000000-0000-4000-8000-000000000102', 'Fremde Meldung');
select tests.authenticate_as('k5_target');
select public.request_account_deletion();
select is(public.my_auth_profile()->>'deletionRequested', 'true', 'Profilabruf (wie Token-Refresh/Login) nimmt nicht zurück');
select is(public.submit_support('30000000-0000-4000-8000-000000000104', repeat('c', 64), 'feedback', 'Löschung zurück',
  'Bitte Löschung zurücknehmen')->>'id', '30000000-0000-4000-8000-000000000104', 'Nutzerwunsch nach dem Antrag');
reset role;
-- In der Testtransaktion ist now() fest: Antrag und alte Meldung ausdrücklich in die Vergangenheit legen.
update public.profile_settings set deletion_requested_at = now() - interval '1 hour' where profile_id = tests.uid('k5_target');
update ops.support_tickets set created_at = now() - interval '2 hours' where id = '30000000-0000-4000-8000-000000000101';
insert into ops.support_tickets(id, owner_id, receipt_hash, payload_hash, category, title, body)
  values ('30000000-0000-4000-8000-000000000103', null, sha256('k5'), sha256('k5'), 'feedback', 'Gast', 'Löschung stoppen');
select is((select status from public.profiles where id = tests.uid('k5_target')), 'deletion_requested', 'Löschung bleibt beantragt');
select pg_temp.operator('k5_support');
select is(public.admin_account_lookup('K5_Target')->'account'->>'canCancelDeletion', 'true', 'Status zeigt Rücknahmemöglichkeit');
select is(public.admin_account_lookup('K5_Target')->'account'->>'status', 'deletion_requested', 'Status zeigt Löschantrag');
select pg_temp.operator('k5_admin');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), null, 'Nutzerwunsch')$$,
  'P0001', 'deletion_ticket_required', 'Ohne Meldung keine Rücknahme');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), '30000000-0000-4000-8000-000000000102',
  'Nutzerwunsch')$$, 'P0001', 'deletion_ticket_invalid', 'Meldung eines anderen Kontos belegt nichts');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), '30000000-0000-4000-8000-0000000001ff',
  'Nutzerwunsch')$$, 'P0001', 'deletion_ticket_invalid', 'Unbekannte Meldung belegt nichts');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), '30000000-0000-4000-8000-000000000101',
  'Nutzerwunsch')$$, 'P0001', 'deletion_ticket_stale', 'Meldung von vor dem Antrag belegt nichts');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), '30000000-0000-4000-8000-000000000103',
  'Gastmeldung')$$, 'P0001', 'deletion_ticket_evidence_required', 'Gastmeldung ohne Zuordnungsbeleg');
select is(pg_temp.try($$select public.admin_account_cancel_deletion(tests.uid('k5_target'),
  '30000000-0000-4000-8000-000000000104', 'Nutzerwunsch per Meldung')->>'status'$$), 'active',
  'admin nimmt Löschung mit eigener Meldung zurück');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), '30000000-0000-4000-8000-000000000104',
  'Nochmals')$$, 'P0001', 'no_deletion_pending', 'Keine zweite Rücknahme');
reset role;
select ok(not exists (select 1 from private.consent_deletion_origins where profile_id = tests.uid('k5_target'))
  and (select deletion_requested_at is null from public.profile_settings where profile_id = tests.uid('k5_target')),
  'Rücknahme räumt wie cancel_account_deletion auf');
select ok((select strpos(prosrc, 's.deletion_requested_at > clock_timestamp() - interval ''14 days''') > 0
    and proconfig @> array['TimeZone=UTC'] from pg_proc
  where oid = 'public.admin_account_cancel_deletion(uuid,uuid,text)'::regprocedure),
  'Gleiche UTC-Frist wie cancel_account_deletion');
select tests.authenticate_as('k5_target');
select public.request_account_deletion();
select pg_temp.operator('k5_admin');
select is(pg_temp.try($$select public.admin_account_cancel_deletion(tests.uid('k5_target'),
  '30000000-0000-4000-8000-000000000103', '  Gastmeldung nennt Freundescode und Alias  ')->>'status'$$), 'active',
  'Gastmeldung mit Zuordnungsbeleg genügt');
reset role;
select is((select jsonb_agg(changes order by id) from ops.account_audit where action = 'account_cancel_deletion'),
  '[{"ticket_id":"30000000-0000-4000-8000-000000000104","ticket_kind":"account"},
    {"ticket_id":"30000000-0000-4000-8000-000000000103","ticket_kind":"guest"}]'::jsonb, 'Audit verweist auf den Nutzerwunsch');
select tests.authenticate_as('k5_target');
select public.request_account_deletion();
reset role;
update public.profile_settings set deletion_requested_at = clock_timestamp() - interval '336 hours'
  where profile_id = tests.uid('k5_target');
select pg_temp.operator('k5_admin');
select throws_ok($$select public.admin_account_cancel_deletion(tests.uid('k5_target'), '30000000-0000-4000-8000-000000000103',
  'Gastmeldung nennt Freundescode und Alias')$$, 'P0001', 'no_deletion_pending', 'Nach 14 Tagen keine Rücknahme');
reset role;
update public.profiles set status = 'deleting' where id = tests.uid('k5_friend');
select pg_temp.operator('k5_admin');
select throws_ok($$select public.admin_account_set_suspended(tests.uid('k5_friend'), true, 'Zu spät')$$,
  'P0001', 'account_not_found', 'Konto in Löschung ist kein Ziel');
select pg_temp.operator('k5_support');
select throws_ok($$select public.admin_account_lookup(tests.uid('k5_friend')::text)$$, 'P0001', 'account_not_found',
  'Suche blendet Konto in Löschung aus');
select is(pg_temp.try($$select public.admin_account_lookup('Same Alias')->'account'->>'profileId'$$), tests.uid('k5_twin')::text,
  'Alias eines Kontos in Löschung macht die Suche nicht mehrdeutig');

-- Entkopplung schon in Phase A wie beim Support-Audit: weder Zielbezug noch Freitext, Akteur eines gelöschten Admins weg.
reset role;
select is((select count(*)::int from ops.account_audit where target_id = tests.uid('k5_target') and reason is not null), 5,
  'Fünf Audits mit Begründung vor der Löschung');
select ok(private.account_deletion_begin(tests.uid('k5_target')), 'Phase A des Zielkontos');
select is((select status from public.profiles where id = tests.uid('k5_target')), 'deleting', 'Ziel in Löschung');
select is((select count(*)::int from ops.account_audit where target_id is null and reason is null), 5,
  'Phase A: Zielbezug und Begründung entfernt');
select is((select count(*)::int from ops.account_audit where actor_id = tests.uid('k5_admin')), 5, 'Akteur bleibt nachvollziehbar');
update public.profiles set status = 'deletion_requested' where id = tests.uid('k5_admin');
select ok(private.account_deletion_begin(tests.uid('k5_admin')), 'Phase A des Admins');
select is((select count(*)::int from ops.account_audit where actor_id is null), 5, 'Phase A: Akteur des Admins entkoppelt');
-- Ohne Phase A (direkte Auth-Löschung) entkoppelt weiterhin die FK samt Begründung.
insert into ops.account_audit(target_id, action, reason) values (tests.uid('k5_twin'), 'account_suspend', 'Name im Freitext');
delete from auth.users where id = tests.uid('k5_twin');
select is((select count(*)::int from ops.account_audit where target_id is null and reason is null), 6,
  'Gelöschtes Ziel: Bezug und Begründung entfernt');

-- Aufbewahrung ein Jahr über den bestehenden Support-Cleanup.
insert into ops.account_audit(action, reason, created_at) values ('account_suspend', 'alt', now() - interval '366 days');
select private.purge_support();
select is((select count(*)::int from ops.account_audit), 6, 'Audit älter als ein Jahr entfernt');

select * from finish();
rollback;
