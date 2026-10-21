-- Begrenzter Schreibschutz nach K3. Kein neuer RPC, kein Client-/Protokoll-4-Release.
-- Batchzusatz: lifecycle={generation:G,redactionEpochs:{"matchUUID":E,...}}.
-- G = profile_settings.visibility_epoch, E = matches.redaction_epoch.
-- E ist für jedes beim Preflight vorhandene Match nötig (auch E=0). Neue Matches
-- benötigen noch keinen E-Eintrag und starten bei 0; Player-only nutzt ein leeres Objekt.
-- Ohne lifecycle bleiben ausschließlich von Redaktion unbetroffene Altbatches erlaubt.
-- Fehlende/veraltete/ungültige Belege: 40001 restart_required, vor ALLEN Batchwrites.
-- Ein gültiger Beleg erlaubt Score und kanonisiert bekannte Namensidentitäten.
-- Bewusste Grenze: unbekannte Offline-IDs und freie Namen ohne belegte Identität
-- lassen sich nicht zuordnen. Keine Namenssuche und kein automatischer Legacy-Fallback.

-- Matchunabhängige Identitäten sind nötig für verknüpfte Gäste OHNE Match.
-- Kein Profil-FK: die nachgewiesene ID muss den K3-Unlink/die Löschung überleben.
-- Dauerhaft nur UUIDs, keine Namen oder Profilzuordnung: keine TTL, da Offline-
-- Kopien zeitlich unbegrenzt eintreffen können.
create table private.redacted_player_identities (
  player_id uuid primary key
);
alter table private.redacted_player_identities enable row level security;
revoke all on private.redacted_player_identities from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.redacted_player_identities
  for each statement execute function private.lock_sync_statement();
create index match_redacted_players_player_idx on private.match_redacted_players(player_id);

-- Tatsächlich gelöschte Player-UUIDs dürfen nie eine neue Identität erhalten.
-- Dieser Zeilenbeleg sperrt nur Neuanlagen: weder Redaktionsautorität noch Manifest.
-- Frühere Harddeletes ohne erhaltene Herkunft sind nicht rekonstruierbar.
create table private.deleted_player_identities (player_id uuid primary key);
alter table private.deleted_player_identities enable row level security;
revoke all on private.deleted_player_identities from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.deleted_player_identities
  for each statement execute function private.lock_sync_statement();
create function private.capture_deleted_player_identity() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into private.deleted_player_identities values(old.id) on conflict do nothing;
  return old;
end;
$$;
create trigger players_capture_deleted_identity before delete on public.players
  for each row execute function private.capture_deleted_player_identity();

-- Eine aktuelle Profilzuordnung pensioniert Serverwrites auch dann, wenn eine
-- widersprechende historische Identität globale Clientredaktion ausschließt.
create table private.player_write_redactions (player_id uuid primary key);
alter table private.player_write_redactions enable row level security;
revoke all on private.player_write_redactions from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.player_write_redactions
  for each statement execute function private.lock_sync_statement();

-- Slotbelege überleben das Entfernen/Wiedereinfügen einer Teilnehmerzeile, aber
-- nicht das physische Löschen des Matches. Slots vermitteln keinerlei Ownerrechte.
create table private.match_redacted_slots (
  match_id uuid not null references public.matches(id) on delete cascade,
  slot_id text not null,
  primary key(match_id,slot_id)
);
alter table private.match_redacted_slots enable row level security;
revoke all on private.match_redacted_slots from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.match_redacted_slots
  for each statement execute function private.lock_sync_statement();

-- Dieselbe UUID-Semantik wie die Writer: auch Großschreibung/Klammern dürfen
-- weder den Epochentest noch eine bekannte Identität umgehen. Ungültig bleibt null.
create function private.redaction_uuid(p_id text) returns uuid
language plpgsql immutable security definer set search_path = '' as $$
begin
  return p_id::uuid;
exception when invalid_text_representation then return null;
end;
$$;

-- Dieselben UUID-Formen wie die Writer; Genesis kann String- und Objekt-IDs
-- enthalten. Materialisierte Ausdrucksindizes vermeiden globale Eventscans.
create function private.redaction_lineup_ids(p_payload jsonb) returns uuid[]
language sql immutable security definer set search_path = '' as $$
  select coalesce(array_agg(distinct id) filter(where id is not null),'{}'::uuid[])
  from (
    select private.redaction_uuid(x#>>'{}') as id from jsonb_array_elements(
      case when jsonb_typeof(p_payload#>'{lineup,A}')='array' then p_payload#>'{lineup,A}' else '[]'::jsonb end ||
      case when jsonb_typeof(p_payload#>'{lineup,B}')='array' then p_payload#>'{lineup,B}' else '[]'::jsonb end) x
    union all
    select private.redaction_uuid(x->>'id') from jsonb_array_elements(
      case when jsonb_typeof(p_payload#>'{lineup,A}')='array' then p_payload#>'{lineup,A}' else '[]'::jsonb end ||
      case when jsonb_typeof(p_payload#>'{lineup,B}')='array' then p_payload#>'{lineup,B}' else '[]'::jsonb end) x
  ) identities
$$;
create index match_events_redaction_legacy_idx on public.match_events
  (private.redaction_uuid(payload#>>'{player,id}'),match_id) where type='PlayerAssigned';
create index match_events_redaction_v2_idx on public.match_events
  (private.redaction_uuid(payload->>'playerId'),match_id)
  where type='PlayerAssigned' and payload->>'assignmentVersion'='2' and not (payload ? 'player');
create index match_events_redaction_lineup_idx on public.match_events
  using gin(private.redaction_lineup_ids(payload)) where type='MatchCreated';

-- Profil- und Player-UUIDs sind getrennte Domaenen. Nur echte Playerbelege
-- duerfen im clientseitig GLOBAL wirkenden redactedPlayerIds-Manifest stehen.
create table private.redacted_profile_identities (profile_id uuid primary key);
alter table private.redacted_profile_identities enable row level security;
revoke all on private.redacted_profile_identities from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.redacted_profile_identities
  for each statement execute function private.lock_sync_statement();

-- Historische Teilnehmer-/Eventzuordnung ist ausschliesslich matchlokale Autoritaet.
-- Sie enthaelt keine Namen und darf niemals in das globale Player-Manifest gelangen.
create table private.match_redacted_names (
  match_id uuid not null references public.matches(id) on delete cascade,
  identity_id uuid not null,
  primary key(match_id,identity_id)
);
create index match_redacted_names_identity_idx on private.match_redacted_names(identity_id,match_id);
alter table private.match_redacted_names enable row level security;
revoke all on private.match_redacted_names from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.match_redacted_names
  for each statement execute function private.lock_sync_statement();

create function private.redacted_profile_identity(p_id text) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from private.redacted_profile_identities where profile_id=private.redaction_uuid(p_id))
$$;

-- Eine lebende Profil-ID oder ein aktuell mit einem anderen lebenden Profil
-- belegter Player ist kein globaler Löschbeleg. Der Löschtrigger trägt old.id
-- zuerst in die typisierte Profiltabelle ein, solange die FK-Zeile noch existiert.
create function private.redaction_identity_is_live(p_id uuid) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from public.profiles f where f.id=p_id
    and not exists(select 1 from private.redacted_profile_identities d where d.profile_id=f.id))
  or exists(select 1 from public.players p where p.id=p_id and (
    (p.kind='self' and not exists(select 1 from private.redacted_profile_identities d where d.profile_id=p.owner_id))
    or (p.profile_id is not null and not exists(
      select 1 from private.redacted_profile_identities d where d.profile_id=p.profile_id))))
$$;

create function private.redacted_player_identity(p_id text) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from private.redacted_player_identities where player_id=private.redaction_uuid(p_id))
$$;

create function private.player_write_redacted(p_id text) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from private.player_write_redactions where player_id=private.redaction_uuid(p_id))
$$;

-- Ein echter Teilnehmer-Player-FK belegt die Identität auch nach dem K3-Unlink
-- bzw. ON DELETE SET NULL des Players. Gültig bleibt sie ausschließlich bei
-- demselben aktuellen Slot-/Profilkontext; keine frei erfundene UUID-Zuordnung.
create table private.match_player_profile_contexts (
  match_id uuid not null references public.matches(id) on delete cascade,
  slot_id text not null,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  player_id uuid not null,
  primary key(match_id,slot_id,profile_id,player_id)
);
create index match_player_profile_contexts_player_idx on private.match_player_profile_contexts(player_id,match_id);
create index match_player_profile_contexts_profile_idx on private.match_player_profile_contexts(profile_id,match_id);
alter table private.match_player_profile_contexts enable row level security;
revoke all on private.match_player_profile_contexts from public,anon,authenticated,service_role;
create trigger zz_lock_sync before insert or update or delete on private.match_player_profile_contexts
  for each statement execute function private.lock_sync_statement();
create function private.capture_player_profile_context() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op in ('UPDATE','DELETE') then
    if tg_op='DELETE' or new.profile_id is distinct from old.profile_id
      or new.match_id is distinct from old.match_id or new.slot_id is distinct from old.slot_id
      or new.deleted_profile then
      delete from private.match_player_profile_contexts where match_id=old.match_id and slot_id=old.slot_id;
    end if;
  end if;
  if tg_op<>'DELETE' and new.profile_id is not null and new.player_id is not null
    and not new.deleted_profile and not private.redacted_profile_identity(new.profile_id::text) then
    insert into private.match_player_profile_contexts values(new.match_id,new.slot_id,new.profile_id,new.player_id)
      on conflict do nothing;
  end if;
  return null;
end;
$$;
create trigger participants_capture_profile_context after insert or update or delete on public.match_participants
  for each row execute function private.capture_player_profile_context();
insert into private.match_player_profile_contexts
  select match_id,slot_id,profile_id,player_id from public.match_participants
  where profile_id is not null and player_id is not null and not deleted_profile;

-- Positiver lebender Teilnehmer-FK ist vom heutigen Player-Link unabhängig.
-- p_match=null prüft alle Matches für die global wirkende Manifestentscheidung.
create function private.redaction_other_profile_context(p_match uuid,p_id uuid,p_profile uuid) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from public.match_participants mp join public.profiles f on f.id=mp.profile_id
    where (p_match is null or mp.match_id=p_match) and not mp.deleted_profile
      and (p_profile is null or mp.profile_id<>p_profile)
      and not private.redacted_profile_identity(mp.profile_id::text)
      and (mp.profile_id=p_id or mp.player_id=p_id
        or exists(select 1 from private.match_player_profile_contexts c
          where c.match_id=mp.match_id and c.slot_id=mp.slot_id and c.profile_id=mp.profile_id and c.player_id=p_id)
        or exists(select 1 from public.players p where p.id=p_id and private.redaction_uuid(mp.slot_id)=p.id
          and mp.profile_id=case when p.kind='self' then p.owner_id else p.profile_id end)))
$$;

-- Dieselbe typisierte Matchautorität gilt auch im Profilkollisionszweig.
-- Ein aktueller oder gültiger historischer Teilnehmer-FK benötigt keine heutige
-- Playerzeile oder deren heutige Profilzuordnung. Niemals globales UUID-Veto.
create function private.redaction_live_player_context(p_match uuid,p_id uuid) returns boolean
language sql security definer set search_path = '' as $$
  select p_match is not null and private.redaction_other_profile_context(p_match,p_id,null)
$$;

-- Ein späteres Profil darf dieselbe UUID wie ein pensionierter Player tragen.
-- Ausschließlich der direkte aktuelle Profil-FK belegt dann die Profildomäne
-- in diesem Match. Player-/Slotbelege heben globale Playerredaktion nicht auf.
-- Widersprechender aktueller/historischer Player-FK zu einem anderen lebenden
-- Profil macht dieselbe untypisierte Event-ID im Match mehrdeutig: keine Ausnahme.
create function private.redaction_live_profile_context(p_match uuid,p_profile uuid) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from public.match_participants mp join public.profiles f on f.id=mp.profile_id
    where mp.match_id=p_match and mp.profile_id=p_profile and not mp.deleted_profile
      and not private.redacted_profile_identity(p_profile::text)
      and not private.redaction_other_profile_context(p_match,p_profile,p_profile))
$$;

create function private.redacted_event_identity(p_match uuid,p_id text) returns boolean
language sql security definer set search_path = '' as $$
  select (private.redacted_player_identity(p_id)
      and not private.redaction_live_profile_context(p_match,private.redaction_uuid(p_id)))
    or (private.player_write_redacted(p_id)
      and not private.redaction_other_profile_context(p_match,private.redaction_uuid(p_id),null))
    or (private.redacted_profile_identity(p_id) and not private.redaction_live_player_context(p_match,private.redaction_uuid(p_id)))
    or exists(select 1 from private.match_redacted_names
      where match_id=p_match and identity_id=private.redaction_uuid(p_id))
$$;

-- Ein bestehender lebender Profil-FK ist typisierte Autorität über diesen Slot.
-- Eine zufällig gleiche Player-/Slot-UUID darf diese Verknüpfung nicht löschen.
-- Neue Slots und bereits redigierte Slots erhalten dadurch keinerlei Ausnahme.
create function private.redaction_live_participant(p_match uuid,p_slot text,p_profile uuid) returns boolean
language sql security definer set search_path = '' as $$
  select exists(select 1 from public.match_participants mp
    where mp.match_id=p_match and mp.slot_id=p_slot and mp.profile_id=p_profile
      and not mp.deleted_profile and not private.redacted_profile_identity(mp.profile_id::text)
      and not exists(select 1 from private.match_redacted_slots r where r.match_id=p_match and r.slot_id=p_slot))
$$;

-- Additiver Ersatz der 006-Erfassung: kein Teilnehmer-/Slot-UUID-Export ohne
-- aktuellen Playerbeleg. Auch ein veralteter FK-Link darf einen heute anders
-- zugewiesenen Player nur innerhalb seines historischen Matches redigieren.
create or replace function private.capture_profile_redactions() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_players uuid[]; v_retired uuid[];
begin
  insert into private.redacted_profile_identities values(old.id) on conflict do nothing;
  select coalesce(array_agg(p.id),'{}'::uuid[]) into v_retired from public.players p
    where (p.owner_id=old.id and p.kind='self') or p.profile_id=old.id;
  insert into private.player_write_redactions(player_id)
    select unnest(v_retired) on conflict do nothing;
  select coalesce(array_agg(p.id),'{}'::uuid[]) into v_players from public.players p
    where p.id=any(v_retired) and not private.redaction_identity_is_live(p.id)
      and not private.redaction_other_profile_context(null,p.id,old.id);
  insert into private.redacted_player_identities(player_id)
    select unnest(v_players) on conflict do nothing;

  -- Profilreferenzen und aktuelle Playerreferenzen invalidieren das Match auch
  -- dann, wenn nur Eventbelege und keine global auslieferbaren Player-IDs bleiben.
  with ids as materialized (
    select old.id as id
    union select unnest(v_retired)
  ), event_refs as materialized (
    -- Erst echte ID-Treffer deduplizieren, dann typisierte Kontexte prüfen.
    -- Ohne diese Barriere darf der Planner die teure Prüfung vor den ID-Join
    -- ziehen und für jede Kombination aus sämtlichen IDs und Events ausführen.
    select e.match_id,i.id from ids i join public.match_events e
      on private.redaction_uuid(e.payload#>>'{player,id}')=i.id where e.type='PlayerAssigned'
    union
    select e.match_id,i.id from ids i join public.match_events e
      on private.redaction_uuid(e.payload->>'playerId')=i.id
      where e.type='PlayerAssigned' and e.payload->>'assignmentVersion'='2' and not (e.payload ? 'player')
    union
    select e.match_id,i.id from ids i join public.match_events e
      on private.redaction_lineup_ids(e.payload) @> array[i.id] where e.type='MatchCreated'
  ), referenced as (
    select e.match_id,e.id from event_refs e
    where (e.id<>old.id and not private.redaction_other_profile_context(e.match_id,e.id,old.id))
      or (e.id=old.id and (not private.redaction_live_player_context(e.match_id,e.id)
        or exists(select 1 from public.match_participants mp where mp.match_id=e.match_id and mp.profile_id=old.id)))
    union select mp.match_id,i.id from public.match_participants mp join ids i on
      (mp.player_id=i.id or private.redaction_uuid(mp.slot_id)=i.id)
      and (mp.profile_id is null or mp.profile_id=old.id)
    union select mp.match_id,old.id from public.match_participants mp
      where mp.profile_id=old.id
    union select mp.match_id,mp.player_id from public.match_participants mp
      where mp.profile_id=old.id and mp.player_id is not null
    union select c.match_id,c.player_id from private.match_player_profile_contexts c
      join public.match_participants mp on mp.match_id=c.match_id and mp.slot_id=c.slot_id and mp.profile_id=c.profile_id
      where c.profile_id=old.id
  ), captured as (
    insert into private.match_redacted_names(match_id,identity_id)
      select distinct match_id,id from referenced on conflict do nothing returning match_id
  ) update public.matches set redaction_epoch=redaction_epoch+1 where id in (
    select match_id from captured union select match_id from public.match_participants where profile_id=old.id);

  insert into private.match_redacted_slots(match_id,slot_id)
    select mp.match_id,mp.slot_id from public.match_participants mp
    where mp.profile_id=old.id or (mp.profile_id is null and (
      mp.player_id=any(v_retired) or private.redaction_uuid(mp.slot_id)=any(v_retired)))
    on conflict do nothing;
  insert into private.match_redacted_players(match_id,player_id)
    select r.match_id,r.identity_id from private.match_redacted_names r
    where r.identity_id=any(v_players)
    on conflict do nothing;
  return old;
end;
$$;

-- Neue Event-ID mit betroffener Legacy-/V2-/Genesis-Identität. Bestehende IDs sind
-- strikt unveränderlich (push_event: duplicate/id_conflict) und bleiben ohne Beleg No-op.
create function private.redaction_push_event_affected(p_item jsonb) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_match uuid:=private.redaction_uuid(p_item->>'matchId');
begin
  if exists(select 1 from public.match_events where id=private.redaction_uuid(p_item->>'id')) then return false; end if;
  return private.redacted_event_identity(v_match,p_item#>>'{payload,player,id}')
    or private.redacted_event_identity(v_match,p_item#>>'{payload,playerId}')
    -- Auch ein neuer Matchkopf befreit betroffene Genesis-IDs nicht von G.
    or (p_item->>'type'='MatchCreated' and exists(select 1 from unnest(private.redaction_lineup_ids(p_item->'payload')) refs(id)
      where private.redacted_event_identity(v_match,refs.id::text)));
end;
$$;

-- VOLATILE: jede Abfrage sieht unter RC den Stand NACH dem globalen Lock-Wait.
-- RR/Serializable bleiben bereits am unveränderten Cloud-Binding geschlossen.
create function private.guard_push_lifecycle(p_uid uuid,p_batch jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_lifecycle jsonb:=p_batch->'lifecycle';
  v_present boolean:=p_batch ? 'lifecycle';
  v_generation bigint;
  v_key text;
  v_value jsonb;
  v_item jsonb;
  v_part jsonb;
  v_match record;
  v_affected boolean:=false;
begin
  foreach v_key in array array['devices','players','matches','events'] loop
    if p_batch ? v_key and jsonb_typeof(p_batch->v_key)<>'array' then
      perform private.fail('invalid_batch');
    end if;
  end loop;
  if v_present then
    if jsonb_typeof(v_lifecycle) is distinct from 'object'
       or not (v_lifecycle ?& array['generation','redactionEpochs']) then
      perform private.fail('restart_required','40001');
    end if;
    if exists(select 1 from jsonb_object_keys(v_lifecycle) k where k not in ('generation','redactionEpochs'))
       or jsonb_typeof(v_lifecycle->'generation') is distinct from 'number'
       or (v_lifecycle->>'generation') !~ '^(0|[1-9][0-9]*)$'
       or jsonb_typeof(v_lifecycle->'redactionEpochs') is distinct from 'object' then
      perform private.fail('restart_required','40001');
    end if;
    if (v_lifecycle->>'generation')::numeric>9223372036854775807 then
      perform private.fail('restart_required','40001');
    end if;
    for v_key,v_value in select key,value from jsonb_each(v_lifecycle->'redactionEpochs') loop
      if v_key !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
         or jsonb_typeof(v_value) is distinct from 'number'
         or (v_value#>>'{}') !~ '^(0|[1-9][0-9]*)$' then
        perform private.fail('restart_required','40001');
      end if;
      if (v_value#>>'{}')::numeric>2147483647 then perform private.fail('restart_required','40001'); end if;
    end loop;
    select visibility_epoch into strict v_generation from public.profile_settings where profile_id=p_uid;
    if (v_lifecycle->>'generation')::bigint<>v_generation then
      perform private.fail('restart_required','40001');
    end if;
  end if;

  -- Das tatsächliche Wireformat: Matchköpfe in matches[].id, Events in events[].matchId.
  -- Keine erste Mutation/Teilantwort vor Abschluss ALLER Epochentests.
  for v_match in
    select m.id,m.redaction_epoch from public.matches m join (
      select private.redaction_uuid(x->>'id') as id from jsonb_array_elements(coalesce(p_batch->'matches','[]')) x
      union select private.redaction_uuid(x->>'matchId') from jsonb_array_elements(coalesce(p_batch->'events','[]')) x
        -- Bestehende IDs sind strikt unveränderlich: push_event liefert ausschließlich
        -- duplicate/id_conflict. Ohne Beleg bleibt dieser reine No-op kompatibel.
        where v_present or not exists(select 1 from public.match_events e where e.id=private.redaction_uuid(x->>'id'))
    ) requested on requested.id=m.id
  loop
    if v_present then
      if not ((v_lifecycle->'redactionEpochs') ? v_match.id::text)
         or (v_lifecycle#>>array['redactionEpochs',v_match.id::text])::integer<>v_match.redaction_epoch then
        perform private.fail('restart_required','40001');
      end if;
    elsif v_match.redaction_epoch>0 then
      perform private.fail('restart_required','40001');
    end if;
  end loop;
  if v_present then return; end if;

  for v_item in select value from jsonb_array_elements(coalesce(p_batch->'players','[]')) loop
    v_affected:=v_affected or private.player_write_redacted(v_item->>'id');
  end loop;
  for v_item in select value from jsonb_array_elements(coalesce(p_batch->'events','[]')) loop
    v_affected:=v_affected or private.redaction_push_event_affected(v_item);
  end loop;
  for v_item in select value from jsonb_array_elements(coalesce(p_batch->'matches','[]')) loop
    if jsonb_typeof(v_item->'participants')='array' then
      for v_part in select value from jsonb_array_elements(v_item->'participants') loop
        v_affected:=v_affected or private.redacted_profile_identity(v_part->>'profileId')
          or exists(select 1 from private.match_redacted_slots r
            where r.match_id=private.redaction_uuid(v_item->>'id') and r.slot_id=v_part->>'slotId');
        if not private.redaction_live_participant(private.redaction_uuid(v_item->>'id'),v_part->>'slotId',
          private.redaction_uuid(v_part->>'profileId')) then
          v_affected:=v_affected or private.player_write_redacted(v_part->>'playerId')
            or private.player_write_redacted(v_part->>'slotId')
            or exists(select 1 from public.match_participants mp
            where mp.match_id=private.redaction_uuid(v_item->>'id') and mp.slot_id=v_part->>'slotId'
              and (mp.deleted_profile or private.player_write_redacted(mp.player_id::text)
                or private.player_write_redacted(mp.slot_id)));
        end if;
      end loop;
    end if;
  end loop;
  if v_affected then perform private.fail('restart_required','40001'); end if;
end;
$$;

-- Der bestehende Writer schreibt Matchköpfe/Teilnehmer VOR den Events. Entzieht
-- derselbe Batch eine positive Kontextausnahme, die ein neues Event braucht, ist
-- dieses Event ohne Beleg betroffen: 40001 vor dem ersten Eventwrite und vor jeder
-- Antwort; die Exception verwirft alle bisherigen Batchwrites derselben Transaktion.
-- Geprüft wird der tatsächliche Writerstand, daher bleiben unveränderte und
-- abgewiesene Köpfe kompatibel. Neue Kontexte helfen nicht: der Preflight sah den
-- Ausgangsstand. Nur Matches mit Teilnehmerliste im Batch, sonst keine Zweitprüfung;
-- MATERIALIZED zwingt diese Auswahl vor den teuren Helfer (sonst wertet der Planer
-- ihn für jedes Event aus). Bei 40001 bleiben nur Sequenzlücken (sync_version_seq,
-- notification_outbox_id_seq) der zurückgerollten Writes; lock_sync serialisiert
-- die Commits, der Pull-Cursor überspringt daher nichts.
create function private.guard_push_lifecycle_context(p_batch jsonb) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if p_batch ? 'lifecycle' then return; end if;
  if exists(with candidates as materialized (
      select e from jsonb_array_elements(coalesce(p_batch->'events','[]')) e
      where private.redaction_uuid(e->>'matchId') in (select private.redaction_uuid(m->>'id')
        from jsonb_array_elements(coalesce(p_batch->'matches','[]')) m where jsonb_typeof(m->'participants')='array'))
    select 1 from candidates c where private.redaction_push_event_affected(c.e)) then
    perform private.fail('restart_required','40001');
  end if;
end;
$$;

-- Gemeinsame historische Seam erhält Signatur, Owner, ACL, Konfiguration und alle
-- vorhandenen Auth-/Consent-/Owner-/Quellenprüfungen. Der v1-Wrapper ruft sie auf.
do $$
declare
  v_definition text;
  v_marker text:=E'  for v_item in select value from jsonb_array_elements(coalesce(p -> ''devices'', ''[]''::jsonb)) loop';
  v_events text:=E'  for v_item in select value from jsonb_array_elements(coalesce(p -> ''events'', ''[]''::jsonb)) loop';
begin
  select pg_get_functiondef('public.sync_push(jsonb)'::regprocedure) into v_definition;
  if position(v_marker in v_definition)=0 or array_length(string_to_array(v_definition,v_events),1)<>2 then
    raise exception 'Unerwartete sync_push-Seam';
  end if;
  v_definition:=replace(v_definition,v_events,E'  perform private.guard_push_lifecycle_context(p);\n\n'||v_events);
  execute replace(v_definition,v_marker,E'  perform private.guard_push_lifecycle(v_uid,p);\n\n'||v_marker);
end;
$$;

-- Teilnehmer-IDs prüfen, BEVOR der bestehende Writer fremde/nicht mehr vorhandene
-- Spieler und Profile verwirft. Die vorhandene Owner-/Freundschaftsprüfung bleibt
-- vollständig erhalten; der Zusatz überträgt ausschließlich den Redaktionsstatus.
do $$
declare v_definition text;
begin
  select pg_get_functiondef('private.push_participants(uuid,uuid,jsonb)'::regprocedure) into v_definition;
  if position('  v_had_row    boolean;' in v_definition)=0
     or position('    insert into public.match_participants as mp' in v_definition)=0
     or position('display_name, share_with_friends, linked_at)' in v_definition)=0
     or position('btrim(v_p ->> ''displayName''), v_share' in v_definition)=0
     or position('          display_name = excluded.display_name,' in v_definition)=0 then
    raise exception 'Unerwartete push_participants-Seam';
  end if;
  v_definition:=replace(v_definition,'  v_had_row    boolean;',E'  v_had_row    boolean;\n  v_redacted   boolean;');
  v_definition:=replace(v_definition,'    insert into public.match_participants as mp',
    E'    v_redacted := private.redacted_profile_identity(v_p ->> ''profileId'')\n'
    ||E'      or (not private.redaction_live_participant(p_match,v_slot,v_profile)\n'
    ||E'      and (private.player_write_redacted(v_p ->> ''playerId'') or private.player_write_redacted(v_slot)));\n'
    ||E'    if v_redacted then v_profile := null; v_status := null; v_share := false; end if;\n\n'
    ||'    insert into public.match_participants as mp');
  v_definition:=replace(v_definition,'display_name, share_with_friends, linked_at)',
    'display_name, deleted_profile, share_with_friends, linked_at)');
  v_definition:=replace(v_definition,'btrim(v_p ->> ''displayName''), v_share',
    'case when v_redacted then ''Gelöschter Spieler'' else btrim(v_p ->> ''displayName'') end, v_redacted, v_share');
  v_definition:=replace(v_definition,'          display_name = excluded.display_name,',
    E'          display_name = excluded.display_name,\n          deleted_profile = mp.deleted_profile or excluded.deleted_profile,');
  execute v_definition;
end;
$$;

-- BEFORE-Zeilentrigger kanonisieren VOR den bestehenden 1–40-Zeichen-CHECKs.
-- Dies erlaubt keine beliebigen leeren Namen und erweitert keine Schreibrechte.
create function private.guard_redacted_names() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_redacted boolean; v_live_link boolean; v_existing public.match_participants;
begin
  if tg_table_name='players' then
    if private.player_write_redacted(new.id::text) then new.display_name:='Gelöschter Spieler'; end if;
  elsif tg_table_name='match_participants' then
    -- INSERT ... ON CONFLICT ruft zunächst INSERT-Trigger auf: alten Slot explizit lesen.
    select * into v_existing from public.match_participants where match_id=new.match_id and slot_id=new.slot_id;
    v_live_link:=private.redaction_live_participant(new.match_id,new.slot_id,new.profile_id);
    v_redacted:=new.deleted_profile or private.redacted_profile_identity(new.profile_id::text)
      or exists(select 1 from private.match_redacted_slots where match_id=new.match_id and slot_id=new.slot_id)
      or coalesce(v_existing.deleted_profile,false)
      or (not v_live_link and (private.player_write_redacted(new.player_id::text) or private.player_write_redacted(new.slot_id)
        or private.player_write_redacted(v_existing.player_id::text) or private.player_write_redacted(v_existing.slot_id)));
    if tg_op='UPDATE' then
      v_redacted:=v_redacted or old.deleted_profile or (not v_live_link and (
        private.player_write_redacted(old.player_id::text) or private.player_write_redacted(old.slot_id)));
    end if;
    if v_redacted then
      new.display_name:='Gelöschter Spieler'; new.deleted_profile:=true;
      new.profile_id:=null; new.link_status:=null; new.linked_at:=null;
      new.share_with_friends:=false;
      insert into private.match_redacted_slots values(new.match_id,new.slot_id) on conflict do nothing;
    end if;
  elsif tg_table_name='match_events' and new.type='PlayerAssigned'
      and jsonb_typeof(new.payload->'player')='object'
      and private.redacted_event_identity(new.match_id,new.payload#>>'{player,id}') then
    new.payload:=jsonb_set(new.payload,'{player,displayName}',to_jsonb('Gelöschter Spieler'::text));
    new.redacted_at:=coalesce(new.redacted_at,now());
  end if;
  return new;
end;
$$;
create trigger players_redacted_name before insert or update on public.players
  for each row execute function private.guard_redacted_names();
create trigger participants_redacted_name before insert or update on public.match_participants
  for each row execute function private.guard_redacted_names();
create trigger events_redacted_name before insert on public.match_events
  for each row execute function private.guard_redacted_names();

create function private.redact_linked_names() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_players uuid[]; v_matches uuid[];
begin
  -- Typisierte Erfassung lief bereits. Nur aktuell zugeordnete Player bereinigen;
  -- historische Zuordnungen beeinflussen weder deren neue Profile noch andere Matches.
  select coalesce(array_agg(p.id),'{}'::uuid[]) into v_players from public.players p
    where ((p.owner_id=old.id and p.kind='self') or p.profile_id=old.id)
      and private.player_write_redacted(p.id::text);
  select array_agg(distinct match_id) into v_matches from (
    select match_id from public.match_participants where profile_id=old.id
    union select match_id from private.match_redacted_names where identity_id=old.id or identity_id=any(v_players)
  ) affected;
  update public.players set display_name='Gelöschter Spieler' where profile_id=old.id;
  update public.match_participants mp set display_name='Gelöschter Spieler',deleted_profile=true
    where mp.profile_id=old.id or (mp.profile_id is null and (
      mp.player_id=any(v_players) or private.redaction_uuid(mp.slot_id)=any(v_players)));
  with redacted as (
    update public.match_events e set
      payload=jsonb_set(e.payload,'{player,displayName}',to_jsonb('Gelöschter Spieler'::text)),redacted_at=coalesce(e.redacted_at,now())
    where e.match_id=any(v_matches) and e.type='PlayerAssigned' and jsonb_typeof(e.payload->'player')='object'
      and private.redacted_event_identity(e.match_id,e.payload#>>'{player,id}')
      and (e.payload#>>'{player,displayName}' is distinct from 'Gelöschter Spieler' or e.redacted_at is null)
    returning e.match_id
  ) update public.matches set redaction_epoch=redaction_epoch+1 where id in (select match_id from redacted);
  return old;
end;
$$;
create trigger profiles_01_redact_linked_names before delete on public.profiles
  for each row execute function private.redact_linked_names();

-- Neue Player-IDs dürfen keine fremde oder bereits gelöschte Profil-ID übernehmen.
-- Bestehende Kollisionen bleiben les-/umbenennbar und werden über Matchkontext
-- behandelt; die Existenz einer späteren Playerzeile widerruft keinen Profilbeleg.
do $$
declare v_definition text; v_marker text:='        insert into public.players (id, owner_id, kind, display_name, profile_id, deleted_at)';
begin
  select pg_get_functiondef('private.push_players(uuid,jsonb)'::regprocedure) into v_definition;
  if position(v_marker in v_definition)=0 then raise exception 'Unerwartete push_players-Seam'; end if;
  execute replace(v_definition,v_marker,
    E'        -- 007: new player identity fence.\n'
    ||E'        if private.redacted_profile_identity(v_id::text) or private.player_write_redacted(v_id::text)\n'
    ||E'          or exists(select 1 from private.deleted_player_identities where player_id=v_id) or exists(select 1 from public.profiles f\n'
    ||E'          where f.id=v_id and (f.id<>p_uid or v_kind<>''self'')) then\n'
    ||E'          v_out := v_out || jsonb_build_object(''id'',v_id,''result'',''rejected'',''reason'',''player_identity_conflict'');\n'
    ||E'          continue;\n        end if;\n'||v_marker);
end;
$$;

-- Eine pensionierte ID kann nicht erneut zugewiesen/beansprucht werden. Ein
-- ausdrücklicher Fehler ersetzt den bisherigen Erfolg mit still entlinktem Slot.
-- Prüfung jeweils nach den vorhandenen Auth-/Owner-/Tokenprüfungen, vor Writes.
do $$
declare v_definition text; v_path record;
begin
  for v_path in select * from (values
    ('public.assign_player(uuid,uuid)','  if v_p.profile_id is not null then',
     E'  -- 007: retired player fence.\n  if private.player_write_redacted(p_player_id::text) then perform private.fail(''player_redacted''); end if;\n'),
    ('public.create_invite(text,uuid,integer)','  insert into public.invites',
     E'  -- 007: retired player fence.\n  if p_kind=''claim_player'' and private.player_write_redacted(p_player_id::text) then perform private.fail(''player_redacted''); end if;\n'),
    ('public.redeem_invite(text)','  if v_inv.kind = ''claim_player'' then',
     E'  -- 007: retired player fence.\n  if v_inv.kind=''claim_player'' and private.player_write_redacted(v_inv.player_id::text) then perform private.fail(''player_redacted''); end if;\n'),
    ('private.link_player_participants(uuid,uuid,text)','  update public.match_participants mp',
     E'  -- 007: retired player fence.\n  if private.player_write_redacted(p_player::text) then perform private.fail(''player_redacted''); end if;\n')
  ) paths(signature,marker,guard) loop
    select pg_get_functiondef(v_path.signature::regprocedure) into v_definition;
    if position(v_path.marker in v_definition)=0 then raise exception 'Unerwartete Player-Zuweisungs-Seam'; end if;
    execute replace(v_definition,v_path.marker,v_path.guard||v_path.marker);
  end loop;
end;
$$;

-- K3s bisherige untypisierte Self-/Profil-Payload-Suche darf die strengere
-- Erfassung nicht nachtraeglich umgehen. Der uebrige K3-Loeschablauf bleibt erhalten.
do $$
declare v_definition text; v_start integer; v_end integer;
begin
  select pg_get_functiondef('private.anonymize_profile()'::regprocedure) into v_definition;
  v_start:=position('  with redacted as (' in v_definition);
  v_end:=position('  for v_m in select m.id from public.matches' in v_definition);
  if v_start=0 or v_end<=v_start then raise exception 'Unerwartete anonymize_profile-Seam'; end if;
  execute substring(v_definition from 1 for v_start-1)||substring(v_definition from v_end);
end;
$$;

-- 006 speicherte untypisierte Slot-/Profil-/Player-UUIDs ohne Herkunft. Daraus
-- laesst sich nach K3 kein globaler Playerbeleg rekonstruieren. Nichts globalisieren.
-- Diese serverseitigen Altbelege bleiben matchlokal. Lebende Profil-/Self-IDs
-- werden auch dort ausgeschlossen; aktuell neu zugewiesene Gaeste duerfen lokal
-- historische Namen schuetzen, ohne ihre heutige Identitaet global zu loeschen.
-- Weitere lokale Quellen sind redacted_at/deleted_profile; niemals Namensgleichheit.
insert into private.match_redacted_names(match_id,identity_id)
  select r.match_id,r.player_id from private.match_redacted_players r
  where not exists(select 1 from public.profiles f where f.id=r.player_id)
    and not exists(select 1 from public.players p where p.id=r.player_id and p.kind='self')
    and not private.redaction_other_profile_context(r.match_id,r.player_id,null)
  union select e.match_id,private.redaction_uuid(e.payload#>>'{player,id}') from public.match_events e
  where e.type='PlayerAssigned' and e.redacted_at is not null
    and private.redaction_uuid(e.payload#>>'{player,id}') is not null
    and not private.redaction_other_profile_context(e.match_id,private.redaction_uuid(e.payload#>>'{player,id}'),null)
  union select mp.match_id,mp.player_id from public.match_participants mp
    where mp.deleted_profile and mp.player_id is not null
  on conflict do nothing;
insert into private.match_redacted_slots(match_id,slot_id)
  select match_id,slot_id from public.match_participants where deleted_profile on conflict do nothing;
with removed as (delete from private.match_redacted_players returning match_id)
  update public.matches set redaction_epoch=redaction_epoch+1 where id in (select match_id from removed);
update public.match_participants mp set display_name='Gelöschter Spieler',deleted_profile=true
  where mp.deleted_profile;
with redacted as (
  update public.match_events e set payload=jsonb_set(e.payload,'{player,displayName}',to_jsonb('Gelöschter Spieler'::text)),
    redacted_at=coalesce(e.redacted_at,now())
  where e.type='PlayerAssigned' and jsonb_typeof(e.payload->'player')='object'
    and private.redacted_event_identity(e.match_id,e.payload#>>'{player,id}')
    and (e.payload#>>'{player,displayName}' is distinct from 'Gelöschter Spieler' or e.redacted_at is null)
  returning e.match_id
) update public.matches set redaction_epoch=redaction_epoch+1 where id in (select match_id from redacted);

revoke all on function private.redaction_uuid(text),private.redacted_player_identity(text),private.guard_push_lifecycle(uuid,jsonb),
  private.guard_redacted_names(),private.redact_linked_names(),private.redacted_profile_identity(text),
  private.redaction_identity_is_live(uuid),private.redacted_event_identity(uuid,text),private.redaction_live_participant(uuid,text,uuid),
  private.redaction_live_player_context(uuid,uuid),private.player_write_redacted(text),
  private.redaction_other_profile_context(uuid,uuid,uuid),private.capture_deleted_player_identity(),
  private.capture_player_profile_context(),private.redaction_live_profile_context(uuid,uuid),private.redaction_lineup_ids(jsonb),
  private.redaction_push_event_affected(jsonb),private.guard_push_lifecycle_context(jsonb)
  from public,anon,authenticated,service_role;
