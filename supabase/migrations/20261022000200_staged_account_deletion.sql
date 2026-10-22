-- Gestaffelte Kontolöschung (Integratorentscheid „Zielarchitektur Kontolöschung“, Option 1 „pro Match atomar“).
-- Bisher lief die ganze Löschung in EINER Transaktion unter lock_sync, also Stillstand für alle Nutzer (Review
-- 0780489, P2-2): mit gemischter Historie 17 s / 84 s / 822 s bei 10 000 / 25 000 / 100 000 eigenen Zeilen (lokal,
-- PG 17). Jetzt drei Phasen, jede Einheit eine Transaktion; ein Job-Aufruf hält die Sperre höchstens ~1,3 s:
--  A  account_deletion_begin: Status 'deleting', Sitzungen/Bindungen weg, globale Grabsteine, Manifestentscheid,
--     globale Player-Änderungen, alles Nicht-Matchlokale für Fremde weg, Arbeitsliste der betroffenen Matches.
--  B  account_deletion_redact: je Aufruf eine Matchmenge VOLLSTÄNDIG – dieselben matchlokalen Anweisungen wie die
--     drei Löschtrigger (00700/01100/20261022000100) und die Profilkaskade, nur für diese Matches, samt Epoche.
--  C  purge_account wie bisher. Die Trigger sehen die Fortschrittszeile und überspringen die erledigte Arbeit.
-- Gleicher Endzustand wie purge_account: Die matchlokalen Anweisungen lesen nur Zeilen ihres Matches; die nach A
-- vorgezogenen globalen Änderungen (Player, Freundschaften, Einladungen, Sitzungen, Support, eigene Turnierrollen)
-- liest keine dieser Anweisungen und keiner der Helfer. Ausnahme ist nur die Höhe von visibility_epoch (G):
-- purge_account erhöht G von Freunden über die noch bestehenden Freundschaften mehrfach, hier genügt die Erhöhung beim
-- Löschen der Freundschaft. Der Client setzt G als monoton und je Generation zugriffsstabil voraus
-- (ReplicaLifecycleStore.applyAccess: gleiches G mit anderer Zugriffsstufe ist ein Fehler). Deshalb erhöht jede Phase G
-- jedes Kontos, dessen Zugriff sie ändert, in derselben Transaktion; A tut das ausdrücklich für den Turnier-Staff.
-- Mehrere Löschungen zugleich: A eines Kontos wartet, solange eine laufende Löschung noch gemeinsame Matches offen hat
-- (sonst sähe deren B die globalen Änderungen des zweiten Kontos zu früh). Ein Löschweg außerhalb des Jobs schließt
-- diese Matches vorher ab. Der Endzustand gleicht so zwei nacheinander laufenden purge_account. Ausnahme: Hat die
-- laufende Löschung schon drei Fehlversuche, wartet A nicht (kein Verhungern), dann gilt nur „keine Klarnamen“.
-- public.admin_purge_due_accounts bleibt ungestaffelt (Wartungsweg mit Karenzparameter, kein Client).

alter table public.profiles drop constraint profiles_status_check;
alter table public.profiles add constraint profiles_status_check
  check (status in ('onboarding', 'active', 'deletion_requested', 'deleting'));

-- Ab A wirkt das Konto wie gelöscht: has_profile (u. a. sync_pull, cloud_list_*/cloud_revoke_*,
-- reject_player_assignment, is_match_member) und jede Kontositzung lehnen ab. require_active, is_active und
-- friend_ids verlangen ohnehin 'active'.
create or replace function private.has_profile(p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.profiles p where p.id = p_profile and p.status not in ('onboarding', 'deleting'));
$$;

-- Wie 20261018000200, zusätzlich der Kontostatus: Eine nach A neu angelegte Auth-Sitzung (erneuter Login) bleibt geschlossen.
create or replace function private.require_account_session() returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_claims jsonb := auth.jwt();
  v_session uuid;
  v_exp numeric;
begin
  perform private.require_account_read_committed();
  if jsonb_typeof(v_claims->'exp') is distinct from 'number' then
    perform private.fail('cloud_session_changed', '42501');
  end if;
  begin
    v_session := nullif(v_claims->>'session_id','')::uuid;
    v_exp := (v_claims->>'exp')::numeric;
  exception when invalid_text_representation or numeric_value_out_of_range then
    perform private.fail('cloud_session_changed', '42501');
  end;
  if v_uid is null or v_session is null or v_exp is null
    or not (v_exp > extract(epoch from clock_timestamp())) then
    perform private.fail('cloud_session_changed', '42501');
  end if;
  if not exists (select 1 from auth.sessions s where s.id = v_session and s.user_id = v_uid
      and (s.not_after is null or s.not_after > clock_timestamp()))
    or exists (select 1 from public.profiles p where p.id = v_uid and p.status = 'deleting') then
    perform private.fail('cloud_session_changed', '42501');
  end if;
  return v_uid;
end;
$$;

-- Fortschritt: Zeile ab A bis C (Kaskade mit dem Profil). retired_ids/redacted_player_ids sind v_retired und
-- v_players aus capture_profile_redactions, einmal in A berechnet wie dort einmal je Löschung.
-- pending_matches ist die Arbeitsliste: sortierte Obermenge der Matches, die purge_account matchweise ändert. Ein
-- zusätzliches oder inzwischen gelöschtes Match bleibt unverändert (jede Anweisung filtert auf Bezüge zum Konto).
-- B rückt nur next_match vor; als Tabelle kostete allein das Einfügen bei 100 000 Zeilen 0,4–0,6 s in A.
-- block_size: Matches je B-Block; der Job halbiert ihn nach einem Block über Budget oder Statementlimit und
-- verdoppelt ihn nach einem schnellen Block wieder (höchstens 50).
create table private.account_deletion_progress (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  started_at timestamptz not null,
  last_step_at timestamptz not null,
  retired_ids uuid[] not null,
  redacted_player_ids uuid[] not null,
  pending_matches uuid[] not null,
  next_match integer not null default 1,
  block_size integer not null default 50 check (block_size >= 1)
);
alter table private.account_deletion_progress enable row level security;
revoke all on private.account_deletion_progress from public, anon, authenticated, service_role;

-- Bisher lief „author_id on delete set null“ ohne Index als Scan aller Events (5,9 s von C bei 100 000 Zeilen).
-- B setzt author_id matchweise; Arbeitsliste und Restkaskade finden die Zeilen jetzt über diesen Index.
create index match_events_author_idx on public.match_events(author_id) where author_id is not null;

-- Arbeitsliste eines Kontos, vor allen Änderungen: dieselben Bezüge, über die Trigger und Kaskade Matches auswählen
-- (IDs wie dort: Profil und v_retired, dazu alle eigenen Player). „= any“ und „&&“ nutzen die Ausdrucksindizes (als
-- Join 2,5 s bei 100 000 Zeilen, so 0,26 s). Liest nichts, was Grabsteine oder Manifest ändern.
create function private.account_deletion_matches(p_profile uuid) returns uuid[]
language plpgsql stable security definer set search_path = '' as $$
declare v_ids uuid[]; v_matches uuid[];
begin
  select coalesce(array_agg(p.id), '{}') into v_ids from public.players p
    where p.owner_id=p_profile or p.profile_id=p_profile;
  v_ids := v_ids || p_profile;
  select coalesce(array_agg(x.match_id order by x.match_id), '{}') into v_matches from (
      select mp.match_id from public.match_participants mp where mp.profile_id=p_profile
      union select mp.match_id from public.match_participants mp where mp.player_id=any(v_ids)
      union select mp.match_id from public.match_participants mp
        where private.redaction_uuid(mp.slot_id)=any(v_ids) and length(mp.slot_id) between 32 and 41
      union select e.match_id from public.match_events e
        where private.redaction_uuid(e.payload#>>'{player,id}')=any(v_ids) and e.type='PlayerAssigned'
      union select e.match_id from public.match_events e
        where private.redaction_uuid(e.payload->>'playerId')=any(v_ids)
          and e.type='PlayerAssigned' and e.payload->>'assignmentVersion'='2' and not (e.payload ? 'player')
      union select e.match_id from public.match_events e
        where private.redaction_lineup_ids(e.payload) && v_ids and e.type='MatchCreated'
      union select r.match_id from private.match_redacted_names r where r.identity_id=any(v_ids)
      union select c.match_id from private.match_player_profile_contexts c where c.profile_id=p_profile
      union select m.id from public.matches m where m.created_by=p_profile
      union select e.match_id from public.match_events e where e.author_id=p_profile
      union select r.match_id from public.match_roles r where r.profile_id=p_profile or r.invited_by=p_profile
      union select v.match_id from public.match_visitors v where v.profile_id=p_profile or v.invited_by=p_profile
      union select m.id from public.matches m join public.tournaments t on t.id=m.tournament_id
        where t.director_id=p_profile
      union select m.id from public.matches m join public.tournament_courts c on c.id=m.court_id
        join public.tournaments t on t.id=c.tournament_id where t.director_id=p_profile
    ) x;
  return v_matches;
end;
$$;

-- Älteste andere laufende Löschung (unter drei Fehlversuchen), deren offene Matches p_matches berühren. Hash-Join
-- über beide Listen; „= any“ wäre bei zwei großen Konten quadratisch.
create function private.account_deletion_blocker(p_profile uuid, p_matches uuid[]) returns uuid
language sql stable security definer set search_path = '' as $$
  select g.profile_id from private.account_deletion_progress g
    left join private.account_deletion_failures f on f.profile_id = g.profile_id
   where g.profile_id <> p_profile and coalesce(f.attempts, 0) < 3
     and exists (select 1 from unnest(g.pending_matches[g.next_match:]) o(id) join unnest(p_matches) n(id) on n.id = o.id)
   order by g.started_at, g.profile_id limit 1;
$$;

-- Löschweg außerhalb des Jobs (Dashboard, purge_account ohne A): erst die gemeinsamen Matches laufender Löschungen
-- abschließen, wie es nacheinander laufende purge_account täten.
create function private.account_deletion_settle(p_profile uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v_matches uuid[]; v_other uuid;
begin
  if not exists (select 1 from private.account_deletion_progress where profile_id <> p_profile) then return; end if;
  v_matches := private.account_deletion_matches(p_profile);
  loop
    v_other := private.account_deletion_blocker(p_profile, v_matches);
    exit when v_other is null;
    perform private.account_deletion_redact(v_other, 500);
  end loop;
end;
$$;

-- Phase A. Aufrufer prüft Status und Frist (Löschjob). Liefert false ohne Änderung, solange eine andere laufende
-- Löschung gemeinsame Matches offen hat (Job versucht es später). Reihenfolge der globalen Schritte wie in purge_account:
-- Grabsteine und Manifestentscheid (capture_profile_redactions, Teil 1, unverändert aus 01100), Umbenennung der
-- verknüpften Player (redact_linked_names), Entlinken und Gast-Übergabe (anonymize_profile/unlink_player, nur die
-- Playerzeilen: deren Teilnehmer-Update findet in purge_account nach redact_linked_names keine Zeile mehr).
create function private.account_deletion_begin(p_profile uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_players uuid[]; v_retired uuid[]; v_matches uuid[];
begin
  perform private.lock_sync();
  v_matches := private.account_deletion_matches(p_profile);
  if private.account_deletion_blocker(p_profile, v_matches) is not null then return false; end if;
  insert into private.redacted_profile_identities values(p_profile) on conflict do nothing;
  select coalesce(array_agg(p.id),'{}'::uuid[]) into v_retired from public.players p
    where (p.owner_id=p_profile and p.kind='self') or p.profile_id=p_profile;
  insert into private.player_write_redactions(player_id)
    select unnest(v_retired) on conflict do nothing;
  with candidates as materialized (
    select mp.profile_id as id,mp.match_id from public.match_participants mp
      where mp.profile_id=any(v_retired) and mp.profile_id<>p_profile and not mp.deleted_profile
    union select mp.player_id,mp.match_id from public.match_participants mp
      where mp.player_id=any(v_retired) and mp.profile_id<>p_profile and not mp.deleted_profile
    union select c.player_id,mp.match_id from private.match_player_profile_contexts c
      join public.match_participants mp on mp.match_id=c.match_id and mp.slot_id=c.slot_id
      where c.player_id=any(v_retired) and mp.profile_id<>p_profile and not mp.deleted_profile
    union select private.redaction_uuid(mp.slot_id),mp.match_id from public.match_participants mp
      where length(mp.slot_id) between 32 and 41 and private.redaction_uuid(mp.slot_id)=any(v_retired)
        and mp.profile_id<>p_profile and not mp.deleted_profile
  ) select coalesce(array_agg(p.id),'{}'::uuid[]) into v_players from public.players p
    where p.id=any(v_retired) and not private.redaction_identity_is_live(p.id)
      and not exists(select 1 from candidates k
        where k.id=p.id and private.redaction_other_profile_context(k.match_id,p.id,p_profile));
  insert into private.redacted_player_identities(player_id)
    select unnest(v_players) on conflict do nothing;

  insert into private.account_deletion_progress
    (profile_id, started_at, last_step_at, retired_ids, redacted_player_ids, pending_matches)
    values (p_profile, clock_timestamp(), clock_timestamp(), v_retired, v_players, v_matches);

  update public.players set display_name='Gelöschter Spieler' where profile_id=p_profile;
  update public.players set profile_id=null, assigned_by=null, assigned_at=null, assignment_source=null
    where profile_id=p_profile and owner_id<>p_profile;
  update public.players set owner_id=profile_id, assigned_by=null
    where owner_id=p_profile and kind='guest' and profile_id is not null and profile_id<>p_profile;

  -- Nicht matchlokal, für Fremde ab jetzt weg: Freundschaften und Anfragen, Blockierungen, Einladungen, eigene
  -- Turnierrollen, Support. Profilalias zeigen find_profile, RLS, Freundesliste, Teilnehmer und Visitor-Sicht nur für
  -- 'active'/'deletion_requested'; Rollenalias und eigene Turniere blendet diese Migration aus (Turniere bleiben bis C,
  -- weil purge_account deren Matches vor dem Staff entkoppelt: ein früheres Staff-DELETE erhöhte access_epoch).
  delete from public.friendships where p_profile in (requester_id, addressee_id);
  delete from public.user_blocks where p_profile in (blocker_id, blocked_id);
  delete from public.invites where created_by=p_profile;
  update public.invites set used_by=null where used_by=p_profile;
  delete from public.tournament_staff where profile_id=p_profile;
  delete from ops.support_tickets where owner_id=p_profile;
  update ops.support_messages set author_id=null where author_id=p_profile;
  update ops.support_audit set actor_id=null where actor_id=p_profile;
  delete from ops.support_members where id=p_profile;
  -- Sitzungen und Gerätebindungen; im gehosteten Auth zusätzlich kein erneuter Login bis C.
  delete from private.cloud_installations where profile_id=p_profile;  -- Bindungen per Kaskade
  delete from auth.sessions where user_id=p_profile;
  if exists (select 1 from information_schema.columns
      where table_schema='auth' and table_name='users' and column_name='banned_until') then
    execute 'update auth.users set banned_until = now() + interval ''100 years'' where id = $1' using p_profile;
  end if;
  -- Staff und Co-Leitung der eigenen Turniere verlieren ab jetzt den Zugriff (is_tournament_staff/_director, Pull):
  -- neue Generation in derselben Transaktion. purge_account erhöht dieselben Profile erst über die Turnierkaskade.
  perform private.bump_visibility(array(select s.profile_id from public.tournament_staff s
    join public.tournaments t on t.id = s.tournament_id where t.director_id = p_profile));
  update public.profiles set status='deleting' where id=p_profile;
  return true;
end;
$$;

-- Turniere eines Kontos in Löschung sind ab A für niemanden mehr sichtbar oder bearbeitbar (Staff, Co-Leitung,
-- Pull-Kandidaten, Mitgliedschaft über das Turnier). Sonst wie 20261015000300.
create or replace function private.is_tournament_staff(p_tournament uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.has_profile(p_profile)
    and exists (select 1 from public.tournaments t join public.profiles d on d.id = t.director_id
      where t.id = p_tournament and d.status <> 'deleting') and (
    exists (select 1 from public.tournaments t where t.id = p_tournament and t.director_id = p_profile)
    or exists (select 1 from public.tournament_staff s where s.tournament_id = p_tournament and s.profile_id = p_profile));
$$;
create or replace function private.is_tournament_director(p_tournament uuid, p_profile uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select private.is_active(p_profile)
    and exists (select 1 from public.tournaments t join public.profiles d on d.id = t.director_id
      where t.id = p_tournament and d.status <> 'deleting') and (
    exists (select 1 from public.tournaments t where t.id = p_tournament and t.director_id = p_profile)
    or exists (select 1 from public.tournament_staff s
               where s.tournament_id = p_tournament and s.profile_id = p_profile and s.role = 'co_director'));
$$;

-- Phase B: höchstens p_limit Matches der Arbeitsliste vollständig. Anweisungen in der Reihenfolge der Trigger
-- (capture_profile_redactions, redact_linked_names, anonymize_profile) und der Profilkaskade, jeweils unverändert bis
-- auf „Match in der Menge“. Teilnehmerkontext, Epoche und Namen hängen nur von Zeilen desselben Matches ab; die
-- Epoche steigt in derselben Transaktion wie die Redaktion. Liefert die Anzahl bearbeiteter Matches (0 = fertig).
create function private.account_deletion_redact(p_profile uuid, p_limit integer) returns integer
language plpgsql security definer set search_path = '' as $$
declare v_retired uuid[]; v_players uuid[]; v_ms uuid[]; v_matches uuid[]; v_next integer;
begin
  perform private.lock_sync();
  select retired_ids,redacted_player_ids,pending_matches[next_match:next_match+p_limit-1],next_match
    into v_retired,v_players,v_ms,v_next
    from private.account_deletion_progress where profile_id=p_profile for update;
  if not found or cardinality(v_ms)=0 then return 0; end if;

  -- capture_profile_redactions (01100), matchlokaler Teil.
  with ids as materialized (
    select p_profile as id
    union select unnest(v_retired)
  ), event_refs as materialized (
    select e.match_id,i.id from ids i join public.match_events e
      on private.redaction_uuid(e.payload#>>'{player,id}')=i.id where e.type='PlayerAssigned' and e.match_id=any(v_ms)
    union
    select e.match_id,i.id from ids i join public.match_events e
      on private.redaction_uuid(e.payload->>'playerId')=i.id
      where e.type='PlayerAssigned' and e.payload->>'assignmentVersion'='2' and not (e.payload ? 'player')
        and e.match_id=any(v_ms)
    union
    select e.match_id,i.id from ids i join public.match_events e
      on private.redaction_lineup_ids(e.payload) @> array[i.id] where e.type='MatchCreated' and e.match_id=any(v_ms)
  ), referenced as (
    select e.match_id,e.id from event_refs e
    where (e.id<>p_profile and not private.redaction_other_profile_context(e.match_id,e.id,p_profile))
      or (e.id=p_profile and (not private.redaction_live_player_context(e.match_id,e.id)
        or exists(select 1 from public.match_participants mp where mp.match_id=e.match_id and mp.profile_id=p_profile)))
    union select mp.match_id,i.id from public.match_participants mp join ids i on mp.player_id=i.id
      where (mp.profile_id is null or mp.profile_id=p_profile) and mp.match_id=any(v_ms)
    union select mp.match_id,i.id from public.match_participants mp join ids i
      on private.redaction_uuid(mp.slot_id)=i.id and length(mp.slot_id) between 32 and 41
      where (mp.profile_id is null or mp.profile_id=p_profile) and mp.match_id=any(v_ms)
    union select mp.match_id,p_profile from public.match_participants mp
      where mp.profile_id=p_profile and mp.match_id=any(v_ms)
    union select mp.match_id,mp.player_id from public.match_participants mp
      where mp.profile_id=p_profile and mp.player_id is not null and mp.match_id=any(v_ms)
    union select c.match_id,c.player_id from private.match_player_profile_contexts c
      join public.match_participants mp on mp.match_id=c.match_id and mp.slot_id=c.slot_id and mp.profile_id=c.profile_id
      where c.profile_id=p_profile and c.match_id=any(v_ms)
  ), captured as (
    insert into private.match_redacted_names(match_id,identity_id)
      select distinct match_id,id from referenced on conflict do nothing returning match_id
  ) update public.matches set redaction_epoch=redaction_epoch+1 where id=any(array(
    select match_id from captured
    union select match_id from public.match_participants where profile_id=p_profile and match_id=any(v_ms)));
  insert into private.match_redacted_slots(match_id,slot_id)
    select mp.match_id,mp.slot_id from public.match_participants mp where mp.profile_id=p_profile and mp.match_id=any(v_ms)
    union select mp.match_id,mp.slot_id from public.match_participants mp
      where mp.profile_id is null and mp.player_id=any(v_retired) and mp.match_id=any(v_ms)
    union select mp.match_id,mp.slot_id from public.match_participants mp
      where mp.profile_id is null and length(mp.slot_id) between 32 and 41 and private.redaction_uuid(mp.slot_id)=any(v_retired)
        and mp.match_id=any(v_ms)
    on conflict do nothing;
  insert into private.match_redacted_players(match_id,player_id)
    select r.match_id,r.identity_id from private.match_redacted_names r
    where r.identity_id=any(v_players) and r.match_id=any(v_ms)
    on conflict do nothing;

  -- redact_linked_names (01100), matchlokaler Teil. Dessen v_players (aktuell zugeordnet und schreibgesperrt) ist
  -- genau v_retired: dieselbe Playerauswahl, und A hat alle in player_write_redactions eingetragen.
  select coalesce(array_agg(distinct match_id),'{}'::uuid[]) into v_matches from (
    select match_id from public.match_participants where profile_id=p_profile and match_id=any(v_ms)
    union select match_id from private.match_redacted_names
      where (identity_id=p_profile or identity_id=any(v_retired)) and match_id=any(v_ms)
  ) affected;
  update public.match_participants mp set display_name='Gelöschter Spieler',deleted_profile=true
    from (
      select match_id,slot_id from public.match_participants where profile_id=p_profile and match_id=any(v_ms)
      union select match_id,slot_id from public.match_participants
        where profile_id is null and player_id=any(v_retired) and match_id=any(v_ms)
      union select match_id,slot_id from public.match_participants
        where profile_id is null and length(slot_id) between 32 and 41 and private.redaction_uuid(slot_id)=any(v_retired)
          and match_id=any(v_ms)
    ) hit
    where mp.match_id=hit.match_id and mp.slot_id=hit.slot_id;
  with redacted as (
    update public.match_events e set
      payload=jsonb_set(e.payload,'{player,displayName}',to_jsonb('Gelöschter Spieler'::text)),redacted_at=coalesce(e.redacted_at,now())
    where e.match_id=any(v_matches) and e.type='PlayerAssigned' and jsonb_typeof(e.payload->'player')='object'
      and private.redacted_event_identity(e.match_id,e.payload#>>'{player,id}')
      and (e.payload#>>'{player,displayName}' is distinct from 'Gelöschter Spieler' or e.redacted_at is null)
    returning e.match_id
  ) update public.matches set redaction_epoch=redaction_epoch+1
    where id=any(array(select distinct match_id from redacted));

  -- anonymize_profile (20261022000100), matchlokaler Teil.
  update public.matches m set created_by = n.profile_id
    from (select distinct on (mp.match_id) mp.match_id, mp.profile_id
            from public.matches o join public.match_participants mp on mp.match_id = o.id
           where o.created_by = p_profile and o.id = any(v_ms) and mp.profile_id is not null and mp.profile_id <> p_profile
           order by mp.match_id, mp.team, mp.position) n
   where m.id = n.match_id;
  delete from public.matches where created_by = p_profile and id = any(v_ms);
  update public.match_participants
     set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false,
         display_name = 'Gelöschter Spieler', deleted_profile = true, player_id = null
   where profile_id = p_profile and match_id = any(v_ms);
  update public.match_participants mp set player_id = null
   where mp.player_id in (select p.id from public.players p where p.owner_id = p_profile) and mp.match_id = any(v_ms);

  -- Profilkaskade, matchlokale Anteile: match_roles/match_visitors on delete cascade bzw. set null,
  -- match_events.author_id set null, Turniermatches des Kontos (court_id, dann tournament_id set null).
  delete from public.match_roles where profile_id = p_profile and match_id = any(v_ms);
  update public.match_roles set invited_by = null where invited_by = p_profile and match_id = any(v_ms);
  delete from public.match_visitors where (profile_id = p_profile or invited_by = p_profile) and match_id = any(v_ms);
  update public.match_events set author_id = null where author_id = p_profile and match_id = any(v_ms);
  update public.matches set court_id = null where id = any(v_ms) and court_id in (select c.id
    from public.tournament_courts c join public.tournaments t on t.id = c.tournament_id where t.director_id = p_profile);
  update public.matches set tournament_id = null where id = any(v_ms)
    and tournament_id in (select t.id from public.tournaments t where t.director_id = p_profile);

  update private.account_deletion_progress set next_match=v_next+cardinality(v_ms), last_step_at=clock_timestamp()
    where profile_id=p_profile;
  return cardinality(v_ms);
end;
$$;

revoke all on function private.account_deletion_begin(uuid), private.account_deletion_redact(uuid,integer),
  private.account_deletion_matches(uuid), private.account_deletion_blocker(uuid,uuid[]),
  private.account_deletion_settle(uuid)
  from public, anon, authenticated, service_role;

-- C: Liegt eine Fortschrittszeile vor, ist der globale Teil erledigt. capture_profile_redactions arbeitet nur noch
-- die Restliste ab (im Löschjob leer; ein Löschweg außerhalb des Jobs, etwa das Dashboard, schließt so B ab),
-- redact_linked_names hat nichts mehr zu tun. anonymize_profile läuft unverändert und findet nur die eigenen
-- Player. Ohne Fortschrittszeile bleibt redact_linked_names byte-gleich; capture_profile_redactions schließt vorher nur
-- gemeinsame Matches anderer laufender Löschungen ab (account_deletion_settle, ohne solche ein Nullschritt).
do $$
declare v_definition text; v_fn record;
begin
  for v_fn in select * from (values
    ('private.capture_profile_redactions()', E'begin\n  insert into private.redacted_profile_identities',
     E'  if exists (select 1 from private.account_deletion_progress where profile_id = old.id) then\n'
     ||E'    while private.account_deletion_redact(old.id, 500) > 0 loop end loop;\n    return old;\n  end if;\n'
     ||E'  perform private.account_deletion_settle(old.id);\n'),
    ('private.redact_linked_names()', E'begin\n  -- Typisierte',
     E'  if exists (select 1 from private.account_deletion_progress where profile_id = old.id) then\n'
     ||E'    return old;\n  end if;\n')
  ) f(signature, marker, guard) loop
    select pg_get_functiondef(v_fn.signature::regprocedure) into v_definition;
    if array_length(string_to_array(v_definition, v_fn.marker), 1) <> 2 then
      raise exception 'Unerwartete Seam %', v_fn.signature;
    end if;
    execute replace(v_definition, v_fn.marker, E'begin\n' || v_fn.guard || substring(v_fn.marker from 7));
  end loop;
end;
$$;

-- Rollenalias: sync_pull und tournament_overview lasen ihn ohne Statusprüfung. Nach C fehlt die Rolle ganz; ab A
-- fehlt der Alias (alias ist im Wire-Vertrag optional), die Rolle selbst ist matchlokal und geht in B.
-- Support: Ohne Kontositzung angenommen; ab A wie nach C nicht mehr für dieses Konto (dann wäre der Owner weg).
do $$
declare v_definition text; v_fn record;
begin
  for v_fn in select * from (values
    ('public.sync_pull(bigint,integer)', '''alias'', pr.alias',
     '''alias'', case when pr.status <> ''deleting'' then pr.alias end'),
    ('public.tournament_overview(uuid)', '''alias'', pr.alias',
     '''alias'', case when pr.status <> ''deleting'' then pr.alias end'),
    ('public.submit_support(uuid,text,text,text,text,text,jsonb)',
     '  select * into v_existing from ops.support_tickets where id = p_id;',
     E'  if exists (select 1 from public.profiles where id = auth.uid() and status = ''deleting'') then\n'
     ||E'    return jsonb_build_object(''error'',''support_unavailable'');\n  end if;\n'
     ||'  select * into v_existing from ops.support_tickets where id = p_id;')
  ) f(signature, marker, replacement) loop
    select pg_get_functiondef(v_fn.signature::regprocedure) into v_definition;
    if array_length(string_to_array(v_definition, v_fn.marker), 1) <> 2 then
      raise exception 'Unerwartete Seam %', v_fn.signature;
    end if;
    execute replace(v_definition, v_fn.marker, v_fn.replacement);
  end loop;
end;
$$;

-- Löschjob: ein Aufruf = eine Transaktion mit höchstens ~1 s Sperrhaltezeit (Budget, danach beginnt keine weitere
-- Einheit). Einheiten: A je fälligem Konto (höchstens 100 je Aufruf), ein B-Block (block_size Matches) oder C einer
-- laufenden Löschung (reihum, ältester letzter Schritt zuerst). Reihenfolge: zuerst weniger Fehlversuche, bei Gleichstand
-- A vor B/C. Ein scheiterndes Konto kommt so hinter alle anderen Einheiten und hält weder A noch B/C anderer Konten auf.
-- Unterblock je Einheit wie 3701ab9: Ein Fehler rollt nur diese Einheit zurück (A, Matchblock oder C, nie ein halbes
-- Match), zählt einen Fehlversuch und schließt das Konto für den Rest des Aufrufs aus; eine erfolgreiche Einheit setzt
-- den Zähler zurück. Nur nach dem Statementlimit endet der Aufruf (danach gäbe es keine Frist mehr). Ein Matchblock über
-- Budget oder Statementlimit läuft beim nächsten Mal halb so groß, bis 1; einer unter einem Viertel des Budgets wieder
-- doppelt so groß, bis 50. Ein zurückgestelltes A (gemeinsame Matches mit
-- einer laufenden Löschung) zählt nicht als Einheit. advantly.account_deletion_budget überschreibt das Budget (nur Tests).
create or replace function private.run_account_deletion_job_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' set statement_timeout = '60s' as $$
declare
  v_started timestamptz := clock_timestamp();
  v_completed timestamptz;
  v_budget interval := interval '1 second';
  v_units integer := 0;
  v_begun integer := 0;
  v_count integer := 0;
  v_skip uuid[] := '{}';
  v_stop boolean := false;
  v_deferred boolean;
  v_id uuid;
  v_kind text;
  v_tries integer;
  v_next uuid;
  v_next_kind text;
  v_next_tries integer;
  v_block integer;
  v_unit_started timestamptz;
  v_failed text;
  v_error text;
begin
  -- Kein veralteter Snapshot nach Warten, auch nicht bei direktem Service-Aufruf.
  perform private.require_account_read_committed();
  begin
    v_budget := coalesce(nullif(current_setting('advantly.account_deletion_budget', true), '')::interval, v_budget);
  exception when others then null;
  end;
  begin
    perform private.lock_sync();
  exception
    when lock_not_available then v_error := 'lock_timeout';
    when query_canceled then v_error := 'statement_canceled';
    when others then v_error := 'account_deletion_failed';
  end;
  v_stop := v_error is not null;
  while not v_stop and (v_units = 0 or clock_timestamp() - v_started < v_budget) loop
    v_id := null;
    v_next := null;
    -- Auswahl erst NACH der globalen Sperre; alle normalen Recovery-/Schreibwege nehmen dieselbe Sperre.
    if v_begun < 100 then
      select p.id, coalesce(f.attempts, 0) into v_id, v_tries
        from public.profiles p join public.profile_settings s on s.profile_id = p.id
        left join private.account_deletion_failures f on f.profile_id = p.id
        where p.status = 'deletion_requested' and s.deletion_requested_at <= clock_timestamp() - interval '14 days'
          and p.id <> all (v_skip)
        order by coalesce(f.attempts, 0), s.deletion_requested_at, p.id limit 1
        for update of p, s;
      v_kind := 'begin';
    end if;
    if v_count < 100 then
      select g.profile_id, coalesce(f.attempts, 0), g.block_size,
          case when g.next_match <= cardinality(g.pending_matches) then 'redact' else 'purge' end
        into v_next, v_next_tries, v_block, v_next_kind
        from private.account_deletion_progress g
        left join private.account_deletion_failures f on f.profile_id = g.profile_id
        where g.profile_id <> all (v_skip)
        order by coalesce(f.attempts, 0), g.last_step_at, g.profile_id limit 1
        for update of g;
      if v_next is not null and (v_id is null or v_next_tries < v_tries) then
        v_id := v_next;
        v_kind := v_next_kind;
      end if;
    end if;
    exit when v_id is null;
    v_deferred := false;
    v_failed := null;
    v_unit_started := clock_timestamp();
    begin
      if v_kind = 'begin' then
        v_deferred := not private.account_deletion_begin(v_id);
        v_begun := v_begun + case when v_deferred then 0 else 1 end;
      elsif v_kind = 'redact' then
        perform private.account_deletion_redact(v_id, v_block);
      else
        perform private.purge_account(v_id);
        v_count := v_count + 1;
      end if;
      if not v_deferred then
        delete from private.account_deletion_failures where profile_id = v_id;
      end if;
    exception
      -- Rollt nur diese Einheit zurück; ausschließlich feste Fehlerklassen speichern.
      when lock_not_available then v_failed := 'lock_timeout';
      when query_canceled then v_failed := 'statement_canceled';
      when others then v_failed := 'account_deletion_failed';
    end;
    if v_deferred or v_failed is not null then
      v_skip := v_skip || v_id;
    end if;
    continue when v_deferred;
    v_units := v_units + 1;
    if v_failed is not null then
      v_error := coalesce(v_error, v_failed);
      v_stop := v_failed = 'statement_canceled';
      insert into private.account_deletion_failures as f values (v_id, 1, clock_timestamp(), v_failed)
        on conflict (profile_id) do update set attempts = f.attempts + 1, last_failed_at = excluded.last_failed_at,
          last_error_code = excluded.last_error_code;
    end if;
    if v_kind = 'redact' and (v_failed = 'statement_canceled'
        or (v_budget > interval '0' and clock_timestamp() - v_unit_started > v_budget)) then
      update private.account_deletion_progress set block_size = greatest(block_size / 2, 1) where profile_id = v_id;
    elsif v_kind = 'redact' and v_failed is null and v_block < 50
        and clock_timestamp() - v_unit_started < v_budget / 4 then
      -- Kurzzeitige Last soll den Block nicht dauerhaft klein halten.
      update private.account_deletion_progress set block_size = least(block_size * 2, 50) where profile_id = v_id;
    end if;
  end loop;
  v_completed := clock_timestamp();
  insert into private.account_deletion_job_state as j
    (singleton, started_at, completed_at, status, deleted_count, error_code)
    values (true, v_started, v_completed, case when v_error is null then 'succeeded' else 'failed' end, v_count, v_error)
    on conflict (singleton) do update set started_at = excluded.started_at, completed_at = excluded.completed_at,
      status = excluded.status, deleted_count = excluded.deleted_count, error_code = excluded.error_code
      where j.completed_at <= excluded.completed_at;
  return jsonb_build_object('contractVersion', 1, 'startedAt', v_started, 'completedAt', v_completed,
    'status', case when v_error is null then 'succeeded' else 'failed' end,
    'deletedCount', v_count, 'errorCode', v_error);
end;
$$;

-- Wie 20261022000100, zusätzlich laufende gestaffelte Löschungen und das Alter der ältesten, nur als Zahlen.
create or replace function public.account_deletion_job_status_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' as $$
declare
  v_run jsonb;
  v_cron jsonb := jsonb_build_object('status', 'unavailable');
  v_stalled integer;
  v_staged integer;
  v_oldest timestamptz;
begin
  perform private.lock_sync();
  select jsonb_build_object('status', j.status, 'startedAt', j.started_at, 'completedAt', j.completed_at,
    'deletedCount', j.deleted_count, 'errorCode', j.error_code) into v_run
    from private.account_deletion_job_state j where singleton;
  select count(*) into v_stalled from private.account_deletion_failures where attempts >= 3;
  select count(*), min(started_at) into v_staged, v_oldest from private.account_deletion_progress;
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    -- Keine return_message: diese kann SQL-/Providertexte enthalten. Kein Job-/Run-Identifier im Ergebnis.
    execute $q$select jsonb_build_object('status', case
          when r.status = 'succeeded' then 'succeeded'
          when r.status = 'failed' then 'failed'
          when r.status in ('starting', 'running', 'connecting', 'sending') then 'running' else 'unknown' end,
        'startedAt', r.start_time, 'completedAt', r.end_time,
        'errorCode', case when r.status = 'failed' then 'scheduler_failed' end)
      from cron.job_run_details r join cron.job j on j.jobid = r.jobid
      where j.jobname = 'advantly-account-deletion-v1' order by r.runid desc limit 1$q$ into v_cron;
    v_cron := coalesce(v_cron, jsonb_build_object('status', 'unknown'));
  end if;
  return jsonb_build_object('contractVersion', 1, 'observedAt', clock_timestamp(),
    'lastCommittedRun', coalesce(v_run, jsonb_build_object('status', 'unknown')),
    'scheduler', v_cron, 'stalledAccountCount', v_stalled, 'stagedDeletionCount', v_staged,
    'oldestStagedDeletionAgeSeconds', floor(extract(epoch from clock_timestamp() - v_oldest))::bigint);
end;
$$;

-- Planung: alle 5 Sekunden (pg_cron ≥ 1.5), Statement-Frist wie bisher vor dem SELECT. Ein 100 000-Zeilen-Konto
-- braucht rund 115 Aufrufe. Laufprotokolle dieses Jobs älter als 7 Tage räumt ein täglicher Job ab.
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('advantly-account-deletion-v1', '5 seconds',
      'set statement_timeout = ''60s''; select private.run_account_deletion_job_v1();');
    perform cron.schedule('advantly-account-deletion-history-v1', '41 3 * * *',
      $q$delete from cron.job_run_details where jobid in (select jobid from cron.job
        where jobname = 'advantly-account-deletion-v1') and end_time < now() - interval '7 days'$q$);
  else
    raise warning 'pg_cron unavailable: account deletion scheduler execution is unverified';
  end if;
end;
$$;
