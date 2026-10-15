-- Advantly P2-Spike · Grundlagen
-- Schemas, Versionszähler für den Delta-Pull, Länder-/Altersregeln (F13).
-- Plan: docs/p2-plan.md · Datenmodell: docs/03-backend-datenmodell.md (§3, Abgleich mit dem Spike in §13).

-- private: Hilfsfunktionen für RLS und RPCs (nicht über die API exponiert).
-- ops:     Betriebsdaten (Benachrichtigungs-Outbox); nur Server/Edge Functions.
create schema if not exists private;
create schema if not exists ops;

revoke all on schema private from public;
revoke all on schema ops from public;
-- RLS-Policies rufen private.*-Helfer im Kontext des Aufrufers auf → USAGE für authenticated nötig.
-- Das Schema ist trotzdem nicht über PostgREST erreichbar (config.toml: api.schemas).
grant usage on schema private to authenticated, service_role;
grant usage on schema ops to service_role;

-- Globaler, monotoner Versionszähler für sync_pull (Cursor statt Uhrzeit, 03 §3.1).
create sequence private.sync_version_seq;

-- Setzt server_version/updated_at bei jedem Insert/Update einer syncbaren Zeile.
create or replace function private.touch_row()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.server_version := nextval('private.sync_version_seq');
  new.updated_at := now();
  return new;
end;
$$;

-- Fehler mit stabilem Code für Clients und Tests (Meldung = maschinenlesbarer Grund).
create or replace function private.fail(p_reason text, p_errcode text default 'P0001')
returns void
language plpgsql
set search_path = ''
as $$
begin
  raise exception using errcode = p_errcode, message = p_reason;
end;
$$;

-- Länder-/Altersregeln (F13): Mindestalter für die Einwilligung nach Art. 8 DSGVO je Wohnsitzland.
-- AT 14 (§ 4 Abs. 4 DSG), DE 16. Nicht gelistete Länder sind (noch) nicht freigeschaltet (Veröffentlichungsplan §2D).
create table public.country_age_rules (
  country   text primary key check (country ~ '^[A-Z]{2}$'),
  min_age   smallint not null check (min_age between 13 and 18),
  allowed   boolean not null default true,
  legal_note text not null
);

insert into public.country_age_rules (country, min_age, allowed, legal_note) values
  ('AT', 14, true, 'Art. 8 DSGVO i. V. m. § 4 Abs. 4 DSG'),
  ('DE', 16, true, 'Art. 8 Abs. 1 DSGVO'),
  ('LI', 16, true, 'Art. 8 Abs. 1 DSGVO (EWR)'),
  ('CH', 16, true, 'revDSG ohne feste Grenze; vorsichtig wie DE (offene Frage P2-Q2)');

alter table public.country_age_rules enable row level security;
create policy country_age_rules_read on public.country_age_rules
  for select to anon, authenticated using (true);
