-- Kontolöschung skaliert nicht mit globalen Textslot-Teilnehmern (Review 77b6317, P1 vorbestehend).
-- Ursache: Die Löschpfade aus 00700 vergleichen private.redaction_uuid(mp.slot_id) mit den gelöschten IDs in einem
-- OR mit mp.player_id. Ohne passenden Index wertet der Planer die Funktion für JEDE Teilnehmerzeile aus, im
-- referenced-Join sogar je Zeile und ID. Für Textslots ('slot-n', 'placeholder:…') endet jeder Aufruf im
-- Ausnahmepfad (Subtransaktion) – bei einigen 10 000 Zeilen läuft das DELETE ins 10-s-Statementlimit.
-- Abhilfe nach dem N3-Muster (00700, Ereignisindizes): Partieller Ausdrucksindex nur über UUID-förmige Slots und
-- dieselben Bedingungen als UNION getrennter, indexgestützter Zweige. Die Auswahl bleibt Zeile für Zeile identisch:
-- redaction_uuid liefert nur für 32–41 Zeichen eine UUID (PostgreSQL-Eingabe: 32 Hexziffern, bis zu 7 Bindestriche,
-- optional geschweifte Klammern); kürzere und längere Slots ergeben dort immer null. Ebenso scannte
-- redaction_other_profile_context(null,…) je pensionierter ID alle verknüpften Teilnehmer; jetzt prüft derselbe,
-- unveränderte Helfer nur materialisierte Kandidatenmatches. Keine Prüfung entfällt, keine Budgetänderung,
-- Owner/ACL/SECURITY DEFINER/search_path wie in 00600/00700 (CREATE OR REPLACE behält Owner und ACL).

-- Der Prädikatstest läuft vor dem Indexausdruck: Textslots außerhalb von 32–41 Zeichen erreichen beim Schreiben den
-- Ausnahmepfad nie. 32–41 Zeichen lange Nicht-UUIDs laufen bei jedem Insert und Nicht-HOT-Update hinein (≈30 µs/Zeile),
-- gültige UUID-Slots kosten den Funktionsaufruf (≈17 µs/Zeile). Clientslots ('placeholder:'+UUID, 48 Zeichen) nie.
create index match_participants_redaction_slot_idx on public.match_participants
  (private.redaction_uuid(slot_id)) where length(slot_id) between 32 and 41;

-- Unverändert gegenüber 00700 bis auf die markierten Teilnehmerzugriffe.
create or replace function private.capture_profile_redactions() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_players uuid[]; v_retired uuid[];
begin
  insert into private.redacted_profile_identities values(old.id) on conflict do nothing;
  select coalesce(array_agg(p.id),'{}'::uuid[]) into v_retired from public.players p
    where (p.owner_id=old.id and p.kind='self') or p.profile_id=old.id;
  insert into private.player_write_redactions(player_id)
    select unnest(v_retired) on conflict do nothing;
  -- 01100: statt redaction_other_profile_context(null,…) (ein Scan aller verknüpften Teilnehmer je ID) derselbe
  -- Helfer je Kandidatenmatch. Jede Zeile, die ihn wahr macht, hat einen lebenden fremden Profil-FK und verweist
  -- per Profil-FK, Player-FK, Kontext oder UUID-Slot auf die ID; die vier Zweige sind eine Obermenge davon.
  -- Zweig 1 (Profil-FK) und Zweig 4 (UUID-Slot) ändern die Entscheidung nie: Eine lebende Profil-ID schließt
  -- redaction_identity_is_live vorher aus, und der UUID-Slotbezug im Helfer verlangt Profil-FK = Owner bzw.
  -- Player-Profil, für pensionierte IDs also old.id, das „<>old.id“ ausfiltert. Zweig 2 (Player-FK) wirkt nur ohne
  -- Kontextzeile (Altbestand). Alle Zweige bleiben als Absicherung gegen künftige Helferänderungen.
  with candidates as materialized (
    select mp.profile_id as id,mp.match_id from public.match_participants mp
      where mp.profile_id=any(v_retired) and mp.profile_id<>old.id and not mp.deleted_profile
    union select mp.player_id,mp.match_id from public.match_participants mp
      where mp.player_id=any(v_retired) and mp.profile_id<>old.id and not mp.deleted_profile
    union select c.player_id,mp.match_id from private.match_player_profile_contexts c
      join public.match_participants mp on mp.match_id=c.match_id and mp.slot_id=c.slot_id
      where c.player_id=any(v_retired) and mp.profile_id<>old.id and not mp.deleted_profile
    union select private.redaction_uuid(mp.slot_id),mp.match_id from public.match_participants mp
      where length(mp.slot_id) between 32 and 41 and private.redaction_uuid(mp.slot_id)=any(v_retired)
        and mp.profile_id<>old.id and not mp.deleted_profile
  ) select coalesce(array_agg(p.id),'{}'::uuid[]) into v_players from public.players p
    where p.id=any(v_retired) and not private.redaction_identity_is_live(p.id)
      and not exists(select 1 from candidates k
        where k.id=p.id and private.redaction_other_profile_context(k.match_id,p.id,old.id));
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
    -- 01100: player_id- und Slotzweig getrennt, je über den eigenen Index.
    union select mp.match_id,i.id from public.match_participants mp join ids i on mp.player_id=i.id
      where mp.profile_id is null or mp.profile_id=old.id
    union select mp.match_id,i.id from public.match_participants mp join ids i
      on private.redaction_uuid(mp.slot_id)=i.id and length(mp.slot_id) between 32 and 41
      where mp.profile_id is null or mp.profile_id=old.id
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

  -- 01100: dieselben drei Bedingungen wie 00700, als indexgestützte Zweige.
  insert into private.match_redacted_slots(match_id,slot_id)
    select mp.match_id,mp.slot_id from public.match_participants mp where mp.profile_id=old.id
    union select mp.match_id,mp.slot_id from public.match_participants mp
      where mp.profile_id is null and mp.player_id=any(v_retired)
    union select mp.match_id,mp.slot_id from public.match_participants mp
      where mp.profile_id is null and length(mp.slot_id) between 32 and 41 and private.redaction_uuid(mp.slot_id)=any(v_retired)
    on conflict do nothing;
  insert into private.match_redacted_players(match_id,player_id)
    select r.match_id,r.identity_id from private.match_redacted_names r
    where r.identity_id=any(v_players)
    on conflict do nothing;
  return old;
end;
$$;

-- Unverändert gegenüber 00700 bis auf die Teilnehmerauswahl des Namens-Updates.
create or replace function private.redact_linked_names() returns trigger
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
  -- 01100: Auswahl wie 00700 (Profil-FK, Player-FK, UUID-Slot), als indexgestützte Zweige.
  update public.match_participants mp set display_name='Gelöschter Spieler',deleted_profile=true
    from (
      select match_id,slot_id from public.match_participants where profile_id=old.id
      union select match_id,slot_id from public.match_participants where profile_id is null and player_id=any(v_players)
      union select match_id,slot_id from public.match_participants
        where profile_id is null and length(slot_id) between 32 and 41 and private.redaction_uuid(slot_id)=any(v_players)
    ) hit
    where mp.match_id=hit.match_id and mp.slot_id=hit.slot_id;
  with redacted as (
    update public.match_events e set
      payload=jsonb_set(e.payload,'{player,displayName}',to_jsonb('Gelöschter Spieler'::text)),redacted_at=coalesce(e.redacted_at,now())
    where e.match_id=any(v_matches) and e.type='PlayerAssigned' and jsonb_typeof(e.payload->'player')='object'
      and private.redacted_event_identity(e.match_id,e.payload#>>'{player,id}')
      and (e.payload#>>'{player,displayName}' is distinct from 'Gelöschter Spieler' or e.redacted_at is null)
    returning e.match_id
  -- Primärschlüssel aus der Treffermenge statt IN/Join: Die CTE-Schätzung ist immer 1 Zeile. Bei veralteter
  -- matches-Statistik wählte der Planer für IN und Join einen Nested Loop über alle Matches (Matches × Treffer).
  -- Je betroffenem Match weiterhin genau ein Anstieg.
  ) update public.matches set redaction_epoch=redaction_epoch+1
    where id=any(array(select distinct match_id from redacted));
  return old;
end;
$$;
