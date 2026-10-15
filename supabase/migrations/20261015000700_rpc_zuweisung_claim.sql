-- Advantly P2-Spike · Dummy-Zuweisung („War ich nicht“), Einladungslinks (Freund/Claim), Teilnahme bestätigen

-- Verknüpft alle Teilnehmer-Zeilen eines Dummys mit einem Account (überspringt Matches, in denen die Person
-- schon steht oder ihre Teilnahme abgelehnt hat). Ergebnis: Anzahl verknüpfter Matches.
create or replace function private.link_player_participants(p_player uuid, p_profile uuid, p_status text)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  update public.match_participants mp
     set profile_id = p_profile, link_status = p_status, linked_at = now(), hidden = false, share_with_friends = false
   where mp.player_id = p_player
     and mp.profile_id is null
     and not exists (select 1 from public.match_participants o
                     where o.match_id = mp.match_id and o.profile_id = p_profile)
     and not exists (select 1 from public.match_link_rejections r
                     where r.match_id = mp.match_id and r.profile_id = p_profile);
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- Zuweisung durch den Erfasser (entschieden 2026-10-15): sofort wirksam für alle bisherigen Matches mit dem
-- Dummy; Person wird benachrichtigt und kann „War ich nicht“ wählen. Ziel muss ein Freund sein.
create or replace function public.assign_player(p_player_id uuid, p_profile_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid   uuid := private.require_active();
  v_p     public.players%rowtype;
  v_count integer;
begin
  select * into v_p from public.players p where p.id = p_player_id for update;
  if not found or v_p.owner_id <> v_uid or v_p.kind <> 'guest' or v_p.deleted_at is not null then
    perform private.fail('player_not_found');
  end if;
  if v_p.profile_id is not null then
    perform private.fail('already_assigned');
  end if;
  if p_profile_id is null or p_profile_id = v_uid or not private.are_friends(v_uid, p_profile_id)
     or private.is_blocked(v_uid, p_profile_id) then
    perform private.fail('assignee_must_be_friend');
  end if;
  if exists (select 1 from public.player_assignment_rejections r
             where r.player_id = p_player_id and r.profile_id = p_profile_id) then
    perform private.fail('assignment_rejected_before');
  end if;

  update public.players
     set profile_id = p_profile_id, assigned_by = v_uid, assigned_at = now(), assignment_source = 'assignment'
   where id = p_player_id;
  v_count := private.link_player_participants(p_player_id, p_profile_id, 'assigned');
  perform private.notify(p_profile_id, 'player_assigned',
                         jsonb_build_object('playerId', p_player_id, 'byProfileId', v_uid, 'matchCount', v_count));
  return v_count;
end;
$$;

-- Nimmt eine Zuweisung/einen Claim zurück (gemeinsamer Kern für „War ich nicht“ und Rücknahme durch den Anleger).
create or replace function private.unlink_player(p_player uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_profile uuid;
begin
  select p.profile_id into v_profile from public.players p where p.id = p_player for update;
  if v_profile is null then
    return null;
  end if;
  update public.match_participants
     set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false
   where player_id = p_player and profile_id = v_profile and link_status in ('assigned', 'confirmed');
  update public.players
     set profile_id = null, assigned_by = null, assigned_at = null, assignment_source = null
   where id = p_player;
  return v_profile;
end;
$$;

-- „War ich nicht“: die zugewiesene Person löst die Verknüpfung; erneute Zuweisung an sie ist danach gesperrt.
create or replace function public.reject_player_assignment(p_player_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_p   public.players%rowtype;
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  select * into v_p from public.players p where p.id = p_player_id;
  if not found or v_p.kind <> 'guest' or v_p.profile_id is distinct from v_uid then
    perform private.fail('not_assigned_to_you');
  end if;
  perform private.unlink_player(p_player_id);
  insert into public.player_assignment_rejections (player_id, profile_id) values (p_player_id, v_uid)
  on conflict do nothing;
  perform private.notify(v_p.owner_id, 'player_assignment_rejected',
                         jsonb_build_object('playerId', p_player_id, 'byProfileId', v_uid));
end;
$$;

-- Anleger nimmt die eigene Zuweisung zurück (z. B. falsche Person gewählt).
create or replace function public.unassign_player(p_player_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  if not exists (select 1 from public.players p where p.id = p_player_id and p.owner_id = v_uid and p.kind = 'guest') then
    perform private.fail('player_not_found');
  end if;
  perform private.unlink_player(p_player_id);
end;
$$;

-- Einladungslink/QR. Token = 2 × UUIDv4 (≈ 244 Bit Zufall), DB speichert nur sha256. Ablauf: Link 14 Tage,
-- QR „persönlich treffen“ kürzer (p_ttl_minutes). Einmalig nutzbar.
create or replace function public.create_invite(p_kind text, p_player_id uuid default null,
                                                p_ttl_minutes integer default 20160)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid   uuid := private.require_active();
  v_token text := encode(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid()), 'hex');
begin
  if p_kind not in ('friend', 'claim_player') then
    perform private.fail('invalid_invite_kind');
  end if;
  if p_kind = 'claim_player' and not exists (
       select 1 from public.players p
       where p.id = p_player_id and p.owner_id = v_uid and p.kind = 'guest' and p.profile_id is null
         and p.deleted_at is null) then
    perform private.fail('player_not_found');
  end if;
  insert into public.invites (token_hash, kind, created_by, player_id, expires_at)
  values (sha256(convert_to(v_token, 'UTF8')), p_kind, v_uid,
          case when p_kind = 'claim_player' then p_player_id end,
          now() + make_interval(mins => least(greatest(coalesce(p_ttl_minutes, 20160), 1), 20160)));
  return v_token;
end;
$$;

-- Einladung einlösen: Freundschaft sofort (Zeigen/Teilen = Zustimmung); bei claim_player zusätzlich alle
-- Matches des Dummys übernehmen (Status confirmed – selbst beansprucht).
create or replace function public.redeem_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid   uuid := private.require_active();
  v_inv   public.invites%rowtype;
  v_count integer := 0;
begin
  select * into v_inv from public.invites i
   where i.token_hash = sha256(convert_to(coalesce(p_token, ''), 'UTF8'))
   for update;
  if not found or v_inv.used_at is not null or v_inv.revoked_at is not null or v_inv.expires_at <= now() then
    perform private.fail('invalid_invite');
  end if;
  if v_inv.created_by = v_uid then
    perform private.fail('own_invite');
  end if;
  if private.is_blocked(v_uid, v_inv.created_by) or not private.is_active(v_inv.created_by) then
    perform private.fail('invalid_invite');
  end if;

  if v_inv.kind = 'claim_player' then
    if exists (select 1 from public.players p where p.id = v_inv.player_id and p.profile_id is not null) then
      perform private.fail('already_assigned');
    end if;
    update public.players
       set profile_id = v_uid, assigned_by = v_inv.created_by, assigned_at = now(), assignment_source = 'claim'
     where id = v_inv.player_id;
    v_count := private.link_player_participants(v_inv.player_id, v_uid, 'confirmed');
    perform private.notify(v_inv.created_by, 'player_claimed',
                           jsonb_build_object('playerId', v_inv.player_id, 'byProfileId', v_uid, 'matchCount', v_count));
  end if;

  -- Freundschaft herstellen bzw. bestätigen
  delete from public.friendships f
   where least(f.requester_id, f.addressee_id) = least(v_uid, v_inv.created_by)
     and greatest(f.requester_id, f.addressee_id) = greatest(v_uid, v_inv.created_by)
     and f.status <> 'accepted';
  insert into public.friendships (requester_id, addressee_id, status, source, responded_at)
  select v_inv.created_by, v_uid, 'accepted', case when v_inv.kind = 'claim_player' then 'claim' else 'link' end, now()
  where not exists (select 1 from public.friendships f
                    where least(f.requester_id, f.addressee_id) = least(v_uid, v_inv.created_by)
                      and greatest(f.requester_id, f.addressee_id) = greatest(v_uid, v_inv.created_by));
  perform private.notify(v_inv.created_by, 'friend_accepted', jsonb_build_object('profileId', v_uid));

  update public.invites set used_by = v_uid, used_at = now() where id = v_inv.id;
  return jsonb_build_object('kind', v_inv.kind, 'friendId', v_inv.created_by, 'playerId', v_inv.player_id,
                            'matchCount', v_count);
end;
$$;

-- Geteiltes Match bestätigen/ablehnen (04 §3.5): pending → confirmed bzw. Verknüpfung lösen + Sperre.
create or replace function public.respond_match_participation(p_match_id uuid, p_accept boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  if p_accept then
    update public.match_participants set link_status = 'confirmed'
     where match_id = p_match_id and profile_id = v_uid and link_status = 'pending';
    if not found then
      perform private.fail('no_pending_participation');
    end if;
  else
    update public.match_participants
       set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false
     where match_id = p_match_id and profile_id = v_uid and link_status in ('pending', 'confirmed', 'assigned');
    if not found then
      perform private.fail('no_participation');
    end if;
    insert into public.match_link_rejections (match_id, profile_id) values (p_match_id, v_uid) on conflict do nothing;
  end if;
end;
$$;

-- Eigene Sichtbarkeit je Match: mit meinen Freunden teilen (03 E6) bzw. aus meiner Liste ausblenden.
create or replace function public.set_my_participation(p_match_id uuid, p_share_with_friends boolean,
                                                       p_hidden boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  update public.match_participants
     set share_with_friends = coalesce(p_share_with_friends, share_with_friends),
         hidden = coalesce(p_hidden, hidden)
   where match_id = p_match_id and profile_id = v_uid;
  if not found then
    perform private.fail('no_participation');
  end if;
end;
$$;
