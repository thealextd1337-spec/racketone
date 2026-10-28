-- XFF braucht eine ausdrückliche lokale/Self-Host-Freigabe; Hosted bleibt ohne sie geschlossen.
begin;
select plan(20);
-- Leerer Custom-GUC entspricht fehlender Freigabe, unabhängig von lokalen DB-Defaults.
set local app.settings.trust_xff = '';
select set_config('request.headers','{"x-forwarded-for":"203.0.113.1, 10.0.0.1"}',true);
select is(private.age_client_ip(), null::text, 'XFF is ignored without explicit opt-in');
set local app.settings.trust_xff = 'off';
select is(private.age_client_ip(), null::text, 'XFF is ignored with trust disabled');
set local app.settings.trust_xff = 'true';
select is(private.age_client_ip(), null::text, 'Only the exact on value enables XFF');
set local app.settings.trust_xff = '';
select set_config('request.headers','{"cf-connecting-ip":"192.0.2.7","x-forwarded-for":"203.0.113.1"}',true);
select is(private.age_client_ip(), '192.0.2.7', 'Cloudflare works without XFF opt-in');
select set_config('request.headers','{"x-real-ip":"203.0.113.99"}',true);
select is(private.age_client_ip(), null::text, 'X-Real-IP is ignored without opt-in');
set local app.settings.trust_xff = 'on';
select is(private.age_client_ip(), null::text, 'X-Real-IP is also ignored with XFF opt-in');
select set_config('request.headers','{"x-forwarded-for":" 2001:0db8::1, 10.0.0.1"}',true);
select is(private.age_client_ip(), '2001:db8::1', 'Opt-in uses the first XFF address and canonicalizes IPv6');
select set_config('request.headers','{"cf-connecting-ip":"192.0.2.7","x-forwarded-for":"203.0.113.1"}',true);
select is(private.age_client_ip(), '192.0.2.7', 'Cloudflare takes precedence even with XFF opt-in');
select set_config('request.headers','{"cf-connecting-ip":"invalid","x-forwarded-for":"203.0.113.1"}',true);
select is(private.age_client_ip(), null::text, 'Invalid Cloudflare never falls through to XFF');
select set_config('request.headers','{"x-forwarded-for":"192.0.2.0/24"}',true);
select is(private.age_client_ip(), null::text, 'Opt-in still rejects network masks');

-- Die echte anonyme RPC muss bei wechselnden gefälschten Headern dasselbe Dreierbudget buchen.
set local app.settings.trust_xff = '';
delete from private.age_attempts;
select set_config('request.headers','{"x-forwarded-for":"203.0.113.1"}',true);
set local role anon;
select is(public.issue_age_ticket('XX','2000-01-01',repeat('1',64))->>'error',
  'country_not_supported', 'First untrusted XFF request uses shared fallback');
select set_config('request.headers','{"x-forwarded-for":"203.0.113.2"}',true);
select is(public.issue_age_ticket('XX','2000-01-01',repeat('2',64))->>'error',
  'country_not_supported', 'Second spoofed IP still uses shared fallback');
select set_config('request.headers','{"x-forwarded-for":"203.0.113.3"}',true);
select is(public.issue_age_ticket('XX','2000-01-01',repeat('3',64))->>'error',
  'country_not_supported', 'Third spoofed IP exhausts shared fallback');
select set_config('request.headers','{"x-forwarded-for":"203.0.113.4","x-real-ip":"203.0.113.5"}',true);
select is(public.issue_age_ticket('XX','2000-01-01',repeat('4',64))->>'error',
  'rate_limit_exceeded', 'Spoofed XFF and X-Real-IP cannot create another budget');
reset role;
select is((select attempts from private.age_attempts where key_hash=private.age_hash('issue:ip:missing')),
  4, 'All four requests hit the missing-IP budget');
select is((select count(*)::int from private.age_attempts where key_hash in (
  private.age_hash('issue:ip:203.0.113.1'), private.age_hash('issue:ip:203.0.113.2'),
  private.age_hash('issue:ip:203.0.113.3'), private.age_hash('issue:ip:203.0.113.4'),
  private.age_hash('issue:ip:203.0.113.5'))), 0, 'No budget is created for spoofed addresses');
select set_config('request.headers','{"cf-connecting-ip":"192.0.2.7","x-forwarded-for":"203.0.113.4"}',true);
select is(public.issue_age_ticket('XX','2000-01-01',repeat('5',64))->>'error',
  'country_not_supported', 'Cloudflare has its own budget without opt-in');
set local app.settings.trust_xff = 'on';
select set_config('request.headers','{"x-forwarded-for":"203.0.113.4"}',true);
select is(public.issue_age_ticket('XX','2000-01-01',repeat('6',64))->>'error',
  'country_not_supported', 'Explicitly trusted XFF gets an IP budget');
select is((select attempts from private.age_attempts where key_hash=private.age_hash('issue:ip:203.0.113.4')),
  1, 'Opt-in books the XFF address');
set local app.settings.trust_xff = 'off';
select is(public.issue_age_ticket('XX','2000-01-01',repeat('7',64))->>'error',
  'rate_limit_exceeded', 'Disabling trust returns to the exhausted fallback budget');
select * from finish();
rollback;
