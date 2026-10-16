begin;
select no_plan();
select tests.create_user('support_owner');
select tests.create_user('support_other');
select tests.create_user('operator');
insert into ops.support_members(id,role) values(tests.uid('operator'),'support');

select ok(not has_table_privilege('authenticated','ops.support_tickets','SELECT'),'Keine direkte Ticket-Leseberechtigung');
select ok(not has_table_privilege('anon','ops.support_tickets','INSERT'),'Kein direkter Gast-Insert');
select ok(not has_function_privilege('authenticated','private.support_ticket_json(uuid,boolean)','EXECUTE'),'Privater JSON-Helfer gesperrt');
select tests.authenticate_anon();
select set_config('request.headers','{}',true);
select is(public.submit_support('10000000-0000-4000-8000-000000000001',repeat('a',64),'bug','Problem','Beschreibung')->>'error','support_unavailable','Gast ohne vertrauenswürdige IP abgewiesen');
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.91"}',true);
select is(public.submit_support('10000000-0000-4000-8000-000000000001',repeat('a',64),'bug','Problem','Beschreibung')->>'receipt','ADV-10000000-0000-4000-8000-000000000001','Gast erhält Quittung');
select is(public.submit_support('10000000-0000-4000-8000-000000000001',repeat('a',64),'bug','Problem','Beschreibung')->>'receipt','ADV-10000000-0000-4000-8000-000000000001','Identischer Retry erhält dieselbe Quittung');
select is(public.submit_support('10000000-0000-4000-8000-000000000001',repeat('b',64),'bug','Problem','Beschreibung')->>'error','support_identity_changed','UUID allein erlaubt keine Quittung');
select is(public.submit_support('10000000-0000-4000-8000-000000000001',repeat('a',64),'bug','Anders','Beschreibung')->>'error','support_conflict','Retry mit verändertem Inhalt abgewiesen');
select is(public.submit_support('10000000-0000-4000-8000-000000000002',repeat('a',64),'bug','Problem','Beschreibung',null,'{"token":"secret"}')->>'error','support_invalid','Keine beliebigen Diagnosefelder');
select throws_ok('select public.my_support_tickets()','42501',null,'Gast hat keinen Lesezugriff');
reset role;
select is((select count(*)::int from ops.support_tickets),1,'Retries erzeugen genau ein Ticket');
select is((select used from ops.support_budgets where key_hash=sha256(convert_to('ip:203.0.113.91','UTF8'))),1,'Retry verbraucht kein weiteres Kontingent');

select tests.authenticate_as('support_owner');
select is(public.submit_support('10000000-0000-4000-8000-000000000001',repeat('a',64),'bug','Problem','Beschreibung')->>'error','support_identity_changed','Login übernimmt kein Gastticket');
select is(public.submit_support('20000000-0000-4000-8000-000000000001',repeat('c',64),'feature','Mehr Funktionen','Wunsch',null,'{"app_version":"1.0"}')->>'id','20000000-0000-4000-8000-000000000001','Konto kann einreichen');
select is(jsonb_array_length(public.my_support_tickets()),1,'Nur eigene Tickets sichtbar');
select ok(not (public.my_support_tickets()->0 ? 'receipt_hash'),'Kein Quittungsgeheimnis im Ergebnis');
select throws_ok('select public.admin_support_list()','P0001','support_forbidden','Normales Konto hat keine Adminrechte');
select tests.authenticate_as('support_other');
select is(public.my_support_tickets(),'[]'::jsonb,'Fremdes Konto sieht nichts');
select throws_ok($$select public.admin_support_ticket('20000000-0000-4000-8000-000000000001')$$,'P0001','support_forbidden','Auch bekannte UUID öffnet kein fremdes Ticket');

select tests.authenticate_as('operator');
select throws_ok('select public.admin_support_list()','P0001','support_mfa_required','Provisionierung ohne MFA reicht nicht');
select set_config('request.jwt.claims',jsonb_build_object('sub',tests.uid('operator'),'role','authenticated','aal','aal2')::text,true);
select is(jsonb_array_length(public.admin_support_list()),2,'Operator mit MFA sieht Inbox');
select is(jsonb_array_length(public.admin_support_members()),1,'Nur aktive Operatoren zuweisbar');
select is(public.admin_support_update('20000000-0000-4000-8000-000000000001','planned','high',tests.uid('operator'),null,null,1)->>'status','planned','Statusänderung mit passender Version');
select throws_ok($$select public.admin_support_update('20000000-0000-4000-8000-000000000001','closed','normal',null,null,null,1)$$,'P0001','support_conflict','Veraltete Version überschreibt keine Änderungen');
select throws_ok($$select public.admin_support_update('10000000-0000-4000-8000-000000000001','resolved','normal',null,null,null,1)$$,'P0001','support_invalid','Behobener Fehler braucht Versionsbezug');
select is(public.admin_support_update('10000000-0000-4000-8000-000000000001','resolved','normal',null,null,'1.2.3',1)->>'resolution_version','1.2.3','Fehler mit Versionsbezug abschließbar');
select is(public.admin_support_message('20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','Intern',true)->>'version','3','Interne Notiz erzeugt Version');
select is(public.admin_support_message('20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','Intern',true)->>'version','3','Notiz-Retry bleibt idempotent');
select throws_ok($$select public.admin_support_message('20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','Intern',false)$$,'P0001','support_conflict','Retry darf Sichtbarkeit nicht ändern');
select is(public.admin_support_message('20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000002','Antwort',false)->>'version','4','Öffentliche Antwort angelegt');
select is(jsonb_array_length(public.admin_support_ticket('20000000-0000-4000-8000-000000000001')->'messages'),2,'Operator sieht beide Nachrichtentypen');
select tests.authenticate_as('support_owner');
select is(jsonb_array_length(public.my_support_tickets()->0->'messages'),1,'Eigentümer sieht ausschließlich die öffentliche Antwort');
select is(public.my_support_tickets()->0->'messages'->0->>'body','Antwort','Richtiger Antworttext');
select ok(not (public.my_support_tickets()->0 ? 'assignee'),'Interne Zuordnung bleibt privat');
select is(jsonb_array_length(public.export_my_data()->'support'),1,'Kontoexport umfasst eigene Meldungen');
select ok((public.export_my_data()->'support')::text not like '%Intern%','Export enthält keine internen Notizen');
reset role;
select is((select count(*)::int from ops.support_audit where ticket_id='20000000-0000-4000-8000-000000000001'),3,'Audit zählt Änderung und zwei Nachrichten einmal');
update ops.support_members set revoked_at=now() where id=tests.uid('operator');
select tests.authenticate_as('operator');
select set_config('request.jwt.claims',jsonb_build_object('sub',tests.uid('operator'),'role','authenticated','aal','aal2')::text,true);
select throws_ok('select public.admin_support_list()','P0001','support_forbidden','Widerruf wirkt trotz bestehendem MFA-JWT');

reset role;
delete from auth.users where id=tests.uid('support_owner');
select is((select count(*)::int from ops.support_tickets where id='20000000-0000-4000-8000-000000000001'),0,'Kontolöschung entfernt Ticket');
select is((select count(*)::int from ops.support_messages),0,'Kontolöschung entfernt Nachrichten');
select tests.authenticate_anon();
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.92"}',true);
do $$ begin for i in 1..10 loop
  perform public.submit_support(('40000000-0000-4000-8000-' || lpad(i::text,12,'0'))::uuid,repeat('d',64),'feedback','Titel','Text');
end loop; end $$;
select is(public.submit_support('40000000-0000-4000-8000-000000000011',repeat('d',64),'feedback','Titel','Text')->>'error','support_rate_limited','Elfte Gastmeldung in einer Stunde begrenzt');
reset role;
select is((select used from ops.support_budgets where key_hash=sha256(convert_to('ip:203.0.113.92','UTF8'))),11,'Abgewiesene Budgetbuchung bleibt erhalten');
select ok(not has_function_privilege('authenticated','private.purge_support()','EXECUTE'),'Aufräumen nur durch Betrieb');
update ops.support_tickets set updated_at=now()-interval '181 days' where status='resolved';
select private.purge_support();
select is((select count(*)::int from ops.support_tickets where id='10000000-0000-4000-8000-000000000001'),0,'Abgeschlossene Meldung nach Aufbewahrungsfrist gelöscht');
select is((select count(*)::int from ops.support_tickets),10,'Offene Meldungen bleiben erhalten');

-- Viele Wiederholungen einer gesperrten Quelle dürfen das globale Kontingent nicht aufbrauchen.
select tests.authenticate_anon();
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.92"}',true);
do $$ begin for i in 12..40 loop
  perform public.submit_support(('40000000-0000-4000-8000-' || lpad(i::text,12,'0'))::uuid,repeat('d',64),'feedback','Titel','Text');
end loop; end $$;
reset role;
select is((select used from ops.support_budgets where key_hash=sha256(convert_to('global','UTF8'))),12,'Gesperrte Quelle verbraucht kein globales Kontingent');
select tests.authenticate_anon();
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.93"}',true);
select is(public.submit_support('50000000-0000-4000-8000-000000000001',repeat('e',64),'feedback','Titel','Text')->>'receipt',
  'ADV-50000000-0000-4000-8000-000000000001','Andere Quelle wird trotz Wiederholungen angenommen');
reset role;

-- Ein privilegierter Fixture-Sprung prüft den echten 500er Grenzwert ohne 500 Ticketzeilen.
update ops.support_budgets set used=499 where key_hash=sha256(convert_to('global','UTF8'));
select is((select used from ops.support_budgets where key_hash=sha256(convert_to('global','UTF8'))),499,'Globalbudget-Fixture gesetzt');
select tests.authenticate_anon();
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.94"}',true);
select is(public.submit_support('60000000-0000-4000-8000-000000000001',repeat('f',64),'feedback','Titel','Text')->>'receipt',
  'ADV-60000000-0000-4000-8000-000000000001','500. Meldung wird angenommen');
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.95"}',true);
select is(public.submit_support('60000000-0000-4000-8000-000000000002',repeat('f',64),'feedback','Titel','Text')->>'error',
  'support_rate_limited','501. Meldung wird begrenzt');
select set_config('request.headers','{"cf-connecting-ip":"203.0.113.94"}',true);
select is(public.submit_support('60000000-0000-4000-8000-000000000001',repeat('f',64),'feedback','Titel','Text')->>'receipt',
  'ADV-60000000-0000-4000-8000-000000000001','Retry nach Erreichen des Globalbudgets bleibt erlaubt');
reset role;
select is((select used from ops.support_budgets where key_hash=sha256(convert_to('global','UTF8'))),501,'Globalbudget zählt den abgewiesenen 501. Versuch');
select * from finish();
rollback;
