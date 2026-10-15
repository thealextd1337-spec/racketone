-- Advantly P2-Spike · Profile, Einstellungen, Freundschaften, Blockieren, Geräte

-- Freundescode: 8 Zeichen Crockford-Base32 (40 Bit) aus Zufallsbytes einer UUIDv4 (ohne Versions-/Variantenbits).
create or replace function private.new_friend_code()
returns text
language plpgsql
volatile
set search_path = ''
as $$
declare
  v_alphabet constant text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  v_bytes bytea := uuid_send(gen_random_uuid());
  v_code text := '';
  v_idx int;
begin
  foreach v_idx in array array[0, 1, 2, 3, 4, 5, 10, 11] loop
    v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, v_idx) % 32) + 1, 1);
  end loop;
  return v_code;
end;
$$;

-- Öffentliches Profil (F18): nur der eigene Alias ist für Freunde/Mitspieler/Visitor sichtbar.
-- Status: onboarding (Länder-/Altersabfrage offen) → active → deletion_requested (14 Tage Karenz).
create table public.profiles (
  id             uuid primary key references auth.users (id) on delete cascade,
  status         text not null default 'onboarding'
                   check (status in ('onboarding', 'active', 'deletion_requested')),
  alias          text check (alias is null or (length(btrim(alias)) between 1 and 40 and alias = btrim(alias))),
  username       text unique check (username ~ '^[a-z0-9_.]{3,20}$'),
  friend_code    text not null unique default private.new_friend_code(),
  discoverable   boolean not null default true,   -- per Benutzername auffindbar (Code/QR/Link gehen immer)
  server_version bigint not null default 0,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint active_needs_alias check (status = 'onboarding' or alias is not null)
);
create trigger profiles_touch before insert or update on public.profiles
  for each row execute function private.touch_row();

-- Private Einstellungen: nur der Nutzer selbst (eigene Tabelle, weil RLS nicht spaltenweise filtert).
create table public.profile_settings (
  profile_id            uuid primary key references public.profiles (id) on delete cascade,
  country               text references public.country_age_rules (country),
  age_confirmed_at      timestamptz,          -- Altersprüfung bestanden (Geburtsdatum wird NICHT gespeichert)
  age_rule_min_age      smallint,             -- zum Zeitpunkt angewandte Grenze
  -- F19: Telemetrie (Puls, Schritte, Kalorien): share = in gemeinsamer Auswertung sichtbar,
  -- private = nur ich (Standard), off = keine Erfassung. Health-Speicherung erst P3.
  telemetry_visibility  text not null default 'private' check (telemetry_visibility in ('share', 'private', 'off')),
  share_matches_default boolean not null default false,   -- Privacy by Default (F12/O1)
  locale                text not null default 'de' check (locale in ('de', 'en')),
  deletion_requested_at timestamptz,
  updated_at            timestamptz not null default now()
);

-- Beim Anlegen eines Auth-Nutzers: Profil (Status onboarding) + Einstellungen.
create or replace function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id) values (new.id);
  insert into public.profile_settings (profile_id) values (new.id);
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

-- Blockieren ist gerichtet und überstimmt Freundschaft, Suche, Visitor und Zuweisung.
create table public.user_blocks (
  blocker_id uuid not null references public.profiles (id) on delete cascade,
  blocked_id uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
create index user_blocks_blocked_idx on public.user_blocks (blocked_id);

-- Eine Zeile pro Paar (egal welche Richtung), Status pending/accepted/declined (03 §3.3.2).
create table public.friendships (
  requester_id   uuid not null references public.profiles (id) on delete cascade,
  addressee_id   uuid not null references public.profiles (id) on delete cascade,
  status         text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
  source         text not null default 'search'
                   check (source in ('search', 'code', 'qr', 'link', 'claim')),
  created_at     timestamptz not null default now(),
  responded_at   timestamptz,
  server_version bigint not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (requester_id, addressee_id),
  check (requester_id <> addressee_id)
);
create unique index friendships_pair_uq on public.friendships
  (least(requester_id, addressee_id), greatest(requester_id, addressee_id));
create index friendships_addressee_idx on public.friendships (addressee_id, status);
create trigger friendships_touch before insert or update on public.friendships
  for each row execute function private.touch_row();

-- Geräte: stabile Installations-ID (= DeviceId der Engine, UUIDv7). Eine ID gehört genau einem Profil.
-- Uhren werden vom gekoppelten Handy registriert (gleiches Profil); Wear OS standalone registriert sich selbst.
create table public.devices (
  id            uuid primary key,
  profile_id    uuid not null references public.profiles (id) on delete cascade,
  kind          text not null check (kind in ('phone', 'watch')),
  platform      text not null check (platform in ('android', 'ios', 'wearos', 'watchos', 'garmin')),
  paired_with   uuid references public.devices (id) on delete set null,
  standalone    boolean not null default false,
  model         text check (length(model) <= 80),
  app_version   text check (length(app_version) <= 40),
  push_provider text check (push_provider in ('fcm', 'apns')),
  push_token    text,
  last_seen_at  timestamptz not null default now(),
  revoked_at    timestamptz,
  created_at    timestamptz not null default now()
);
create index devices_profile_idx on public.devices (profile_id);
create unique index devices_push_token_uq on public.devices (push_token) where push_token is not null;

-- Benachrichtigungs-Outbox (03 §5.7): nur IDs und Titel-Schlüssel, nie Spielstände oder Gesundheitsdaten.
create table ops.notification_outbox (
  id         bigint generated always as identity primary key,
  profile_id uuid not null references public.profiles (id) on delete cascade,
  kind       text not null check (kind in (
               'friend_request', 'friend_accepted', 'match_shared', 'scorer_invited', 'scorer_responded',
               'visitor_invited', 'player_assigned', 'player_assignment_rejected', 'player_claimed')),
  payload    jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  sent_at    timestamptz,
  attempts   smallint not null default 0,
  last_error text
);
create index notification_outbox_pending_idx on ops.notification_outbox (created_at) where sent_at is null;

create or replace function private.notify(p_profile uuid, p_kind text, p_payload jsonb default '{}'::jsonb)
returns void
language sql
security definer
set search_path = ''
as $$
  insert into ops.notification_outbox (profile_id, kind, payload) values (p_profile, p_kind, p_payload);
$$;
