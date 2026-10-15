-- Advantly P2-Spike · Spieler/Dummys, Turniere, Matches, Teilnehmer, Rollen, Visitor, Event-Log, Zuweisungen

-- Spieler-Einträge mit lokaler Client-ID (UUIDv7 aus der P1-Spielertabelle).
--  kind = self:  frühere/lokale Self-IDs eines Profils (Handy, Uhr; W1-I-Aliase). Events sind unveränderlich und
--                verweisen auf diese IDs → kein Umschlüsseln auf profiles.id (Abweichung von 03 E5, siehe 03 §13).
--  kind = guest: Dummy-/Gastspieler ohne App; Name vergibt nur der Anleger (F18). profile_id = zugewiesener
--                bzw. beanspruchender Account (Zuweisung durch den Erfasser oder Claim per Link).
create table public.players (
  id                uuid primary key,
  owner_id          uuid not null references public.profiles (id) on delete cascade,
  kind              text not null check (kind in ('self', 'guest')),
  display_name      text not null check (length(display_name) between 1 and 40),
  profile_id        uuid references public.profiles (id) on delete set null,
  assigned_by       uuid references public.profiles (id) on delete set null,
  assigned_at       timestamptz,
  assignment_source text check (assignment_source in ('assignment', 'claim')),
  deleted_at        timestamptz,
  server_version    bigint not null default 0,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  constraint self_player_is_owner check (kind <> 'self' or profile_id is null or profile_id = owner_id),
  constraint guest_link_consistent check (
    kind <> 'guest' or (profile_id is null) = (assignment_source is null))
);
create index players_owner_idx on public.players (owner_id, server_version);
create index players_profile_idx on public.players (profile_id);
create trigger players_touch before insert or update on public.players
  for each row execute function private.touch_row();

-- „War ich nicht“: gesperrte Kombination Dummy ↔ Account (keine erneute Zuweisung).
create table public.player_assignment_rejections (
  player_id  uuid not null references public.players (id) on delete cascade,
  profile_id uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (player_id, profile_id)
);

-- Turnierdirektor-Modus (F22) – Grundgerüst: Turnier, Plätze, Helfer (Co-Direktor/Zähler).
create table public.tournaments (
  id             uuid primary key default gen_random_uuid(),
  director_id    uuid not null references public.profiles (id) on delete cascade,
  name           text not null check (length(btrim(name)) between 1 and 80),
  sport          text not null check (sport in ('tennis', 'padel', 'pickleball', 'badminton', 'table_tennis')),
  starts_on      date,
  ends_on        date,
  status         text not null default 'planned' check (status in ('planned', 'running', 'finished', 'cancelled')),
  server_version bigint not null default 0,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  check (ends_on is null or starts_on is null or ends_on >= starts_on)
);
create index tournaments_director_idx on public.tournaments (director_id);
create trigger tournaments_touch before insert or update on public.tournaments
  for each row execute function private.touch_row();

create table public.tournament_courts (
  id            uuid primary key default gen_random_uuid(),
  tournament_id uuid not null references public.tournaments (id) on delete cascade,
  name          text not null check (length(btrim(name)) between 1 and 40),
  sort_order    smallint not null default 0,
  unique (tournament_id, name)
);

create table public.tournament_staff (
  tournament_id uuid not null references public.tournaments (id) on delete cascade,
  profile_id    uuid not null references public.profiles (id) on delete cascade,
  role          text not null check (role in ('co_director', 'scorer')),
  created_at    timestamptz not null default now(),
  primary key (tournament_id, profile_id)
);
create index tournament_staff_profile_idx on public.tournament_staff (profile_id);

-- Match-Kopf. Wahrheit ist das Event-Log; Kopf und Projektionen (summary, live_state) liefert der Client
-- (eine Engine, 03 E9). Status/Gründe in Kleinschreibung der Engine-Werte (MatchStatus, EndReason).
create table public.matches (
  id                      uuid primary key,                       -- MatchId (UUIDv7, Client)
  created_by              uuid not null references public.profiles (id),
  sport                   text not null check (sport in ('tennis', 'padel', 'pickleball', 'badminton', 'table_tennis')),
  discipline              text not null check (discipline in ('singles', 'doubles')),
  format                  jsonb not null check (jsonb_typeof(format) = 'object'),   -- MatchFormat
  meta                    jsonb not null default '{}'::jsonb check (jsonb_typeof(meta) = 'object'),
  previous_match_id       uuid references public.matches (id) on delete set null,
  tournament_id           uuid references public.tournaments (id) on delete set null,
  court_id                uuid references public.tournament_courts (id) on delete set null,
  scheduled_at            timestamptz,
  started_at              timestamptz,
  ended_at                timestamptz,
  status                  text not null default 'in_progress'
                            check (status in ('scheduled', 'in_progress', 'paused', 'awaiting_decision', 'finished')),
  result_reason           text check (result_reason in ('retired', 'walkover', 'abandoned', 'stopped')),
  winner                  text check (winner in ('A', 'B')),
  summary                 jsonb check (summary is null or jsonb_typeof(summary) = 'object'),
  live_state              jsonb check (live_state is null or jsonb_typeof(live_state) = 'object'),
  projection_event_count  integer not null default 0 check (projection_event_count >= 0),
  deleted_at              timestamptz,
  server_version          bigint not null default 0,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  check (status = 'scheduled' or started_at is not null)
);
create index matches_created_by_idx on public.matches (created_by, server_version);
create index matches_tournament_idx on public.matches (tournament_id) where tournament_id is not null;
create trigger matches_touch before insert or update on public.matches
  for each row execute function private.touch_row();

-- Teilnehmer je Lineup-Platz. slot_id = PlayerId im Lineup (UUID oder 'placeholder:…', 02 §7.6).
-- link_status: self (eigener Platz) · pending (Freund eingetragen, Bestätigung offen) · confirmed (bestätigt
-- oder per Link beansprucht) · assigned (Dummy-Zuweisung, sofort wirksam, Widerspruch möglich).
create table public.match_participants (
  match_id           uuid not null references public.matches (id) on delete cascade,
  slot_id            text not null check (length(slot_id) between 1 and 80),
  team               text not null check (team in ('A', 'B')),
  position           smallint not null check (position in (1, 2)),
  player_id          uuid references public.players (id) on delete set null,
  profile_id         uuid references public.profiles (id) on delete set null,
  link_status        text check (link_status in ('self', 'pending', 'confirmed', 'assigned')),
  display_name       text not null check (length(display_name) between 1 and 40),   -- Snapshot
  share_with_friends boolean not null default false,
  hidden             boolean not null default false,
  deleted_profile    boolean not null default false,       -- „Gelöschter Spieler“ (Client lokalisiert)
  linked_at          timestamptz,
  server_version     bigint not null default 0,
  updated_at         timestamptz not null default now(),
  primary key (match_id, slot_id),
  constraint participant_team_position_uq unique (match_id, team, position) deferrable initially deferred,
  constraint participant_link_consistent check ((profile_id is null) = (link_status is null))
);
create unique index match_participants_profile_once
  on public.match_participants (match_id, profile_id) where profile_id is not null;
create index match_participants_profile_idx on public.match_participants (profile_id, match_id);
create index match_participants_player_idx on public.match_participants (player_id);
create trigger match_participants_touch before insert or update on public.match_participants
  for each row execute function private.touch_row();

-- Abgelehnte Teilnahme an einem Match („das war ich nicht“ bei pending) → kein erneutes Verknüpfen.
create table public.match_link_rejections (
  match_id   uuid not null references public.matches (id) on delete cascade,
  profile_id uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (match_id, profile_id)
);

-- Zähler-Personen (F17) und externer Director. Kein autoritativer Zählerstand: welches Gerät zählt,
-- entscheidet die Engine aus MatchCreated/ScorerChanged (02 §7.5). Die Zeile regelt nur Rechte.
create table public.match_roles (
  match_id       uuid not null references public.matches (id) on delete cascade,
  profile_id     uuid not null references public.profiles (id) on delete cascade,
  role           text not null check (role in ('scorer', 'director')),
  status         text not null default 'invited' check (status in ('invited', 'accepted', 'declined', 'revoked')),
  device_id      uuid references public.devices (id) on delete set null,   -- Zielgerät nach Annahme
  invited_by     uuid references public.profiles (id) on delete set null,
  invited_at     timestamptz not null default now(),
  responded_at   timestamptz,
  server_version bigint not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (match_id, profile_id)
);
create index match_roles_profile_idx on public.match_roles (profile_id, status);
create trigger match_roles_touch before insert or update on public.match_roles
  for each row execute function private.touch_row();

-- Benannte Visitor (F16): Freunde des Benennenden, nur Live-Stand und Ergebnis, nie Health/Event-Log.
create table public.match_visitors (
  match_id   uuid not null references public.matches (id) on delete cascade,
  profile_id uuid not null references public.profiles (id) on delete cascade,
  invited_by uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  primary key (match_id, profile_id)
);
create index match_visitors_profile_idx on public.match_visitors (profile_id) where revoked_at is null;

-- Append-only Event-Log, Spalten wie die lokale Tabelle match_event (shared/data) bzw. ScoreEvent.
-- Idempotenz über id; (match_id, device_id, device_seq) eindeutig wie lokal; server_seq = Pull-Cursor.
create table public.match_events (
  id            uuid primary key,
  match_id      uuid not null references public.matches (id) on delete cascade,
  device_id     uuid not null,                 -- Gerätebindung prüft sync_push (bewusst ohne FK, Geräte sind entfernbar)
  device_seq    bigint not null check (device_seq > 0),
  lamport       bigint not null check (lamport > 0),
  wall_clock_ms bigint not null check (wall_clock_ms >= 0),
  parent_id     uuid,
  type          text not null check (type in (
                  'MatchCreated', 'PointWon', 'Undo', 'ContinueDecision', 'Correction', 'MatchEnded',
                  'Paused', 'Resumed', 'ScorerChanged', 'PointDetailSet', 'ServerChosen', 'PlayerAssigned')),
  payload       jsonb not null check (jsonb_typeof(payload) = 'object' and payload ->> 'type' = type),
  author_id     uuid references public.profiles (id) on delete set null,   -- serverseitig = auth.uid()
  server_seq    bigint generated always as identity,
  received_at   timestamptz not null default now(),
  constraint match_events_device_seq_uq unique (match_id, device_id, device_seq)
);
create unique index match_events_server_seq_uq on public.match_events (match_id, server_seq);
create index match_events_order_idx on public.match_events (match_id, lamport, device_id, id);
create unique index match_events_one_genesis on public.match_events (match_id) where type = 'MatchCreated';

-- Einladungslinks/QR: Freundschaft oder Dummy-Claim. DB speichert nur sha256(token).
create table public.invites (
  id          uuid primary key default gen_random_uuid(),
  token_hash  bytea not null unique,
  kind        text not null check (kind in ('friend', 'claim_player')),
  created_by  uuid not null references public.profiles (id) on delete cascade,
  player_id   uuid references public.players (id) on delete cascade,
  expires_at  timestamptz not null,
  used_by     uuid references public.profiles (id) on delete set null,
  used_at     timestamptz,
  revoked_at  timestamptz,
  created_at  timestamptz not null default now(),
  check (kind <> 'claim_player' or player_id is not null)
);
create index invites_creator_idx on public.invites (created_by);

-- Entitlements (Freemium vorbereitet, 03 §3.3.6): nur lesen; Schreiben nur RevenueCat-Webhook (service_role).
create table public.entitlements (
  profile_id  uuid not null references public.profiles (id) on delete cascade,
  entitlement text not null check (entitlement ~ '^[a-z_]{2,32}$'),
  source      text not null check (source in ('app_store', 'play_store', 'promotional')),
  product_id  text,
  expires_at  timestamptz,
  updated_at  timestamptz not null default now(),
  primary key (profile_id, entitlement)
);

-- Änderungen an Teilnehmern/Rollen/Visitor heben die Version des Matches → erscheint im nächsten sync_pull
-- (auch bei Sichtbarkeitsgewinn, 03 §5.5).
create or replace function private.touch_parent_match()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_match uuid;
begin
  if tg_op = 'DELETE' then
    v_match := old.match_id;
  else
    v_match := new.match_id;
  end if;
  -- touch_row (BEFORE UPDATE auf matches) vergibt die neue Version
  update public.matches set server_version = server_version where id = v_match;
  return null;
end;
$$;

create trigger match_participants_touch_match after insert or update or delete on public.match_participants
  for each row execute function private.touch_parent_match();
create trigger match_roles_touch_match after insert or update or delete on public.match_roles
  for each row execute function private.touch_parent_match();
create trigger match_visitors_touch_match after insert or update or delete on public.match_visitors
  for each row execute function private.touch_parent_match();
