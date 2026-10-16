-- P2-C: zufällige Einmal-Tickets, keine Speicherung von Geburtsdaten.
-- Identitätsbindung ersetzt beim nativen Google-Login fehlende benutzerdefinierte Metadaten.
create table private.age_tickets (
  token_hash bytea primary key,
  device_hash bytea not null,
  country text not null references public.country_age_rules(country),
  checked_at timestamptz not null default clock_timestamp(),
  min_age smallint not null,
  expires_at timestamptz not null default clock_timestamp() + interval '10 minutes',
  identity_hash bytea,
  provider text check (provider in ('email', 'google')),
  consumed_at timestamptz
);
create unique index age_tickets_open_device on private.age_tickets(device_hash) where consumed_at is null;
create unique index age_tickets_identity on private.age_tickets(identity_hash);
create table private.age_attempts (
  key_hash bytea primary key,
  started_at timestamptz not null,
  attempts int not null,
  blocked_until timestamptz
);
alter table private.age_tickets enable row level security;
alter table private.age_attempts enable row level security;
revoke all on private.age_tickets, private.age_attempts from public, anon, authenticated;

create function private.age_hash(p_value text) returns bytea
language sql immutable strict set search_path = '' as $$
  select sha256(convert_to(p_value, 'UTF8'));
$$;

-- Nur der vorgeschaltete, vertrauenswürdige Proxy darf diese Header setzen; staging muss Spoofing ausschließen.
-- Supabase dokumentiert X-Forwarded-For[1]; Cloudflare hat Vorrang, X-Real-IP wird nie vertraut.
create function private.age_client_ip() returns text
language plpgsql stable set search_path = '' as $$
declare
  v_headers jsonb := coalesce(nullif(current_setting('request.headers', true), ''), '{}')::jsonb;
  v_ip text;
begin
  v_ip := coalesce(nullif(btrim(v_headers ->> 'cf-connecting-ip'), ''),
    nullif(btrim(split_part(v_headers ->> 'x-forwarded-for', ',', 1)), ''));
  if v_ip is null or v_ip like '%/%' then return null; end if;
  return host(v_ip::inet);
exception when invalid_text_representation then return null;
end;
$$;

-- Nur private Tabellen: keine Versionsvergabe, daher ausdrücklich KEIN globaler Sync-Lock.
-- Jeder UPSERT sperrt seine Budgetzeile atomar. Ablehnungen als JSON erhalten die Budgetbuchung.
create function private.age_request_budget(p_action text, p_device bytea default null) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v_now timestamptz := clock_timestamp();
  v_ip text := private.age_client_ip();
  v_key bytea;
  v_budget private.age_attempts%rowtype;
  v_limit int;
begin
  foreach v_key in array array[private.age_hash('global'),
    private.age_hash(p_action || ':ip:' || coalesce(v_ip, 'missing')), p_device] loop
    continue when v_key is null;
    insert into private.age_attempts as a(key_hash, started_at, attempts) values (v_key, v_now, 1)
    on conflict (key_hash) do update set
      attempts = case when a.started_at <= v_now - interval '1 minute' then 1 else a.attempts + 1 end,
      started_at = case when a.started_at <= v_now - interval '1 minute' then v_now else a.started_at end
    returning * into v_budget;
    if v_budget.blocked_until > v_now then return 'age_retry_later'; end if;
    v_limit := case when v_key = private.age_hash('global') then 1000
      when v_key = p_device then 10 when v_ip is null then 3
      when p_action = 'issue' then 10 else 30 end;
    if v_budget.attempts > v_limit then return 'rate_limit_exceeded'; end if;
  end loop;
  return null;
end;
$$;

-- Zweite Linie für deaktivierte Hooks und Admin/Invite-Pfade: auch ein irrtümlich aktives Profil
-- ohne Altersnachweis erhält weder aktive RPCs (require_active) noch aktive RLS-Rechte.
create or replace function private.is_active(p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.profiles p join public.profile_settings s on s.profile_id = p.id
    where p.id = p_profile and p.status = 'active' and s.age_confirmed_at is not null);
$$;

-- Kalenderdatum des Wohnsitzlandes, unabhängig von DB-/Geräte-Zeitzone; 29.02. wird in Nicht-Schaltjahren am 01.03. alt.
create function private.age_result(p_country text, p_birth_date date, p_now timestamptz)
returns text language plpgsql stable security definer set search_path = '' as $$
declare
  v_rule public.country_age_rules%rowtype;
  v_today date;
begin
  select * into v_rule from public.country_age_rules where country = upper(btrim(p_country));
  if not found or not v_rule.allowed then return 'country_not_supported'; end if;
  v_today := (p_now at time zone case v_rule.country when 'AT' then 'Europe/Vienna'
    when 'DE' then 'Europe/Berlin' when 'CH' then 'Europe/Zurich' when 'LI' then 'Europe/Vaduz' else 'UTC' end)::date;
  if p_birth_date is null or p_birth_date < date '1900-01-01' or p_birth_date > v_today then
    return 'invalid_birth_date';
  end if;
  if extract(year from age(v_today, p_birth_date)) < v_rule.min_age then return 'age_below_minimum'; end if;
  return 'ok';
end;
$$;

-- Auch Ablehnungen werden gezählt: fachliche Fehler als JSON statt Exception (sonst Rollback der Budgetbuchung).
create function public.issue_age_ticket(p_country text, p_birth_date date, p_device_secret text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_now timestamptz := clock_timestamp();
  v_device bytea;
  v_result text;
  v_token text;
  v_min smallint;
begin
  -- Erst Transportbudget buchen, auch bei ungültigem Installationsgeheimnis.
  v_device := case when length(coalesce(p_device_secret, '')) between 43 and 128
    then private.age_hash('device:' || p_device_secret) end;
  v_result := private.age_request_budget('issue', v_device);
  if v_result is not null then return jsonb_build_object('error', v_result); end if;
  if v_device is null then return jsonb_build_object('error', 'invalid_device_secret'); end if;
  delete from private.age_tickets where device_hash = v_device and consumed_at is null;
  v_result := private.age_result(p_country, p_birth_date, v_now);
  if v_result = 'age_below_minimum' then
    update private.age_attempts set blocked_until = v_now + interval '24 hours' where key_hash = v_device;
  end if;
  if v_result <> 'ok' then return jsonb_build_object('error', v_result); end if;
  select min_age into v_min from public.country_age_rules where country = upper(btrim(p_country));
  v_token := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  -- Pro Installation nur ein offenes Ticket; Abbruch/Neustart widerruft das vorherige.
  delete from private.age_tickets where device_hash = v_device and consumed_at is null;
  insert into private.age_tickets(token_hash, device_hash, country, min_age)
    values (private.age_hash(v_token), v_device, upper(btrim(p_country)), v_min);
  return jsonb_build_object('ticket', v_token, 'expiresIn', 600);
end;
$$;

create function public.bind_age_ticket(p_ticket text, p_provider text, p_identity text)
returns jsonb language plpgsql security definer set search_path = '' set lock_timeout = '500ms' as $$
declare
  v_hash bytea := private.age_hash(p_ticket);
  v_identity bytea;
  v_ticket private.age_tickets%rowtype;
  v_error text;
  v_now timestamptz := clock_timestamp();
begin
  v_error := private.age_request_budget('bind');
  if v_error is not null then return jsonb_build_object('error', v_error); end if;
  if p_provider is null or p_provider not in ('email', 'google') or length(coalesce(btrim(p_identity), '')) not between 1 and 320 then
    return jsonb_build_object('error', 'invalid_identity');
  end if;
  v_identity := private.age_hash(p_provider || ':' || case when p_provider = 'email'
    then lower(btrim(p_identity)) else p_identity end);
  -- Nur konkurrierende Bindungen DIESER Identität serialisieren; kein Lock auf Sync oder öffentliche Tabellen.
  -- Vor der Ticketzeile nehmen, damit paralleles Ersetzen ohne Unique-Rennen abgeschlossen werden kann.
  perform pg_advisory_xact_lock(hashtextextended('age-identity:' || encode(v_identity, 'hex'), 0));
  select * into v_ticket from private.age_tickets where token_hash = v_hash for update;
  if not found or v_ticket.consumed_at is not null or v_ticket.expires_at <= v_now then
    return jsonb_build_object('error', 'age_ticket_expired');
  end if;
  if v_ticket.identity_hash is not null and (v_ticket.identity_hash <> v_identity or v_ticket.provider <> p_provider) then
    return jsonb_build_object('error', 'age_ticket_bound');
  end if;
  -- Offene Fremdbindung ersetzen; einen laufenden Hook→Insert höchstens eine Minute schützen.
  delete from private.age_tickets where identity_hash = v_identity and token_hash <> v_hash
    and (consumed_at is null or consumed_at <= v_now - interval '1 minute' or expires_at <= v_now);
  if exists (select 1 from private.age_tickets where identity_hash = v_identity and token_hash <> v_hash) then
    return jsonb_build_object('error', 'age_ticket_bound');
  end if;
  update private.age_tickets set identity_hash = v_identity, provider = p_provider,
    expires_at = least(expires_at, v_now + interval '2 minutes') where token_hash = v_hash;
  return '{}'::jsonb;
end;
$$;

create function public.before_user_created(event jsonb)
returns jsonb language plpgsql security definer set search_path = ''
set lock_timeout = '500ms' set statement_timeout = '2s' as $$
declare
  v_provider text := event #>> '{user,app_metadata,provider}';
  v_identity text;
  v_count int;
begin
  if v_provider = 'email' then
    v_identity := lower(btrim(event #>> '{user,email}'));
  elsif v_provider = 'google' then
    -- Auth setzt diese Claims aus dem verifizierten Google-ID-Token, nicht aus options.data.
    v_identity := event #>> '{user,user_metadata,sub}';
  end if;
  if v_identity is null or v_identity = '' then
    return jsonb_build_object('error', jsonb_build_object('http_code', 403, 'message', 'age_ticket_required'));
  end if;
  -- Die Hook-UUID externer Provider ist vorläufig und darf nicht zur Zuordnung beim Insert dienen.
  update private.age_tickets set consumed_at = clock_timestamp()
    where identity_hash = private.age_hash(v_provider || ':' || v_identity) and provider = v_provider
      and consumed_at is null and expires_at > clock_timestamp()
      and (v_provider = 'google' or token_hash = private.age_hash(event #>> '{user,user_metadata,age_ticket}'));
  get diagnostics v_count = row_count;
  if v_count <> 1 then
    return jsonb_build_object('error', jsonb_build_object('http_code', 403, 'message', 'age_ticket_required'));
  end if;
  return '{}'::jsonb;
end;
$$;
revoke all on function public.before_user_created(jsonb) from public, anon, authenticated, service_role;
grant execute on function public.before_user_created(jsonb) to supabase_auth_admin;
grant usage on schema public to supabase_auth_admin;

-- Übernimmt ausschließlich den serverseitigen Nachweis. Admin-/pgTAP-Fixtures per direktem SQL bleiben möglich.
create or replace function private.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_provider text := new.raw_app_meta_data ->> 'provider';
  v_identity text;
  v_ticket private.age_tickets%rowtype;
begin
  perform private.lock_sync();
  v_identity := case v_provider when 'email' then lower(btrim(new.email))
    when 'google' then new.raw_user_meta_data ->> 'sub' end;
  -- Gleiche verifizierte Provider-Identität wie im Hook, auch wenn GoTrue eine neue UUID erzeugt.
  delete from private.age_tickets
    where provider = v_provider and identity_hash = private.age_hash(v_provider || ':' || v_identity)
      and consumed_at > clock_timestamp() - interval '1 minute' and expires_at > clock_timestamp()
      and (v_provider = 'google' or token_hash = private.age_hash(new.raw_user_meta_data ->> 'age_ticket'))
    returning * into v_ticket;
  insert into public.profiles(id) values (new.id);
  insert into public.profile_settings(profile_id, country, age_confirmed_at, age_rule_min_age)
    values (new.id, v_ticket.country, v_ticket.checked_at, v_ticket.min_age);
  -- AFTER INSERT: NEW zu ändern reicht nicht; das bereits gespeicherte Metadatum gezielt entfernen.
  update auth.users set raw_user_meta_data = raw_user_meta_data - 'age_ticket'
    where id = new.id and raw_user_meta_data ? 'age_ticket';
  return new;
end;
$$;

create function public.finish_onboarding(p_alias text, p_username text default null)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_status text;
  v_alias text := btrim(coalesce(p_alias, ''));
  v_username text := lower(nullif(btrim(p_username), ''));
begin
  perform private.lock_sync();
  if v_uid is null then perform private.fail('not_authenticated', '42501'); end if;
  select status into v_status from public.profiles where id = v_uid for update;
  if not found then perform private.fail('profile_missing'); end if;
  if v_status <> 'onboarding' then perform private.fail('already_onboarded'); end if;
  if not exists (select 1 from public.profile_settings where profile_id = v_uid and age_confirmed_at is not null) then
    perform private.fail('age_ticket_required');
  end if;
  if length(v_alias) not between 1 and 40 or v_alias ~ '[[:cntrl:]]' then perform private.fail('invalid_alias'); end if;
  if v_username is not null and v_username !~ '^[a-z0-9_.]{3,20}$' then perform private.fail('invalid_username'); end if;
  if v_username in ('admin', 'advantly', 'support', 'moderator', 'system', 'deleted', 'null', 'official') then
    perform private.fail('username_reserved');
  end if;
  begin
    update public.profiles set status = 'active', alias = v_alias, username = v_username where id = v_uid;
  exception when unique_violation then perform private.fail('username_taken'); end;
end;
$$;

-- Selbstbeschreibung nie als Amt-/Supportkonto ausgeben; gilt auch für den alten Spike-RPC.
alter table public.profiles add constraint username_not_reserved check
  (username not in ('admin', 'advantly', 'support', 'moderator', 'system', 'deleted', 'null', 'official'));
alter table public.profiles add constraint alias_no_control_characters check (alias !~ '[[:cntrl:]]');

-- Ein Abbruch darf nur ein eigenes noch nicht verbrauchtes Ticket widerrufen; Besitz des Zufallstokens genügt.
create function public.cancel_age_ticket(p_ticket text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if private.age_request_budget('cancel') is not null then return; end if;
  delete from private.age_tickets where token_hash = private.age_hash(p_ticket) and consumed_at is null;
end;
$$;

create function public.my_auth_profile() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_profile public.profiles%rowtype;
begin
  perform private.lock_sync();
  if v_uid is null then perform private.fail('not_authenticated', '42501'); end if;
  select * into v_profile from public.profiles where id = v_uid;
  if not found then perform private.fail('profile_missing'); end if;
  return jsonb_build_object('profileId', v_uid, 'onboarded', v_profile.status = 'active',
    'deletionRequested', v_profile.status = 'deletion_requested');
end;
$$;

-- Kurze Aufbewahrung auch bei abgebrochenem Login. Keine E-Mail, Google-sub, IP oder Geburtstage im Klartext.
create function private.purge_age_gate() returns void
language plpgsql security definer set search_path = '' set lock_timeout = '5s' set statement_timeout = '60s' as $$
begin
  delete from private.age_tickets where expires_at < clock_timestamp() - interval '1 hour';
  delete from private.age_attempts where started_at < clock_timestamp() - interval '25 hours'
    and coalesce(blocked_until, '-infinity'::timestamptz) < clock_timestamp();
end;
$$;
do $$ begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('advantly-purge-age-gate', '29 * * * *', 'set statement_timeout = ''60s''; select private.purge_age_gate();');
  end if;
end $$;
revoke all on function private.age_hash(text), private.age_result(text,date,timestamptz), private.purge_age_gate(),
  private.age_client_ip(), private.age_request_budget(text,bytea)
  from public, anon, authenticated;
revoke all on function public.issue_age_ticket(text,date,text), public.bind_age_ticket(text,text,text),
  public.cancel_age_ticket(text), public.finish_onboarding(text,text), public.my_auth_profile() from public, anon, authenticated;
grant execute on function public.issue_age_ticket(text,date,text), public.bind_age_ticket(text,text,text), public.cancel_age_ticket(text) to anon, authenticated;
grant execute on function public.finish_onboarding(text,text), public.my_auth_profile() to authenticated;
-- Der alte Spike-RPC darf keinen alternativen Weg zum Aktivieren ungeprüfter Profile bieten.
revoke execute on function public.complete_onboarding(text,date,text,text) from anon, authenticated;
