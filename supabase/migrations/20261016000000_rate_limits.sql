-- P2-A: serverseitige Budgets pro Konto; keine API-Grants auf Betriebsdaten.
-- Eine Zeile je Konto/Budget, festes 60-s-Fenster ab dem ersten erfolgreichen Aufruf.
-- Transaktions-Rollback nimmt auch das Budget zurück (siehe README).
create table ops.rate_limits (
  profile_id uuid not null references public.profiles(id) on delete cascade,
  bucket text not null check (bucket in ('search', 'invitation')),
  window_started_at timestamptz not null,
  used integer not null check (used > 0),
  primary key (profile_id, bucket)
);
alter table ops.rate_limits enable row level security;
revoke all on ops.rate_limits from public, anon, authenticated;
grant select, insert, update, delete on ops.rate_limits to service_role;

create or replace function private.consume_rate_limit(p_bucket text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_now timestamptz := clock_timestamp();
  v_limit integer;
begin
  v_limit := case p_bucket when 'search' then 30 when 'invitation' then 20 end;
  if v_limit is null then
    perform private.fail('invalid_rate_limit_bucket');
  end if;
  -- UPSERT ist atomar. Die Versionssperre wurde vor dieser Zeilensperre genommen.
  insert into ops.rate_limits as r (profile_id, bucket, window_started_at, used)
  values (v_uid, p_bucket, v_now, 1)
  on conflict (profile_id, bucket) do update
    set window_started_at = case when r.window_started_at <= v_now - interval '1 minute'
                                 then v_now else r.window_started_at end,
        used = case when r.window_started_at <= v_now - interval '1 minute' then 1 else r.used + 1 end
    where r.window_started_at <= v_now - interval '1 minute' or r.used < v_limit;
  if not found then
    perform private.fail('rate_limit_exceeded', 'P0001');
  end if;
end;
$$;
revoke all on function private.consume_rate_limit(text) from public, anon, authenticated;

-- Bestehende RPC-Verträge bleiben gleich; alle Einladungswege teilen dasselbe Budget.
create or replace function public.find_profile(p_query text)
returns table (id uuid, alias text, username text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_q   text := btrim(coalesce(p_query, ''));
begin
  perform private.consume_rate_limit('search');
  return query
    select p.id, p.alias, p.username
    from public.profiles p
    where p.status = 'active'
      and p.id <> v_uid
      and ((p.discoverable and p.username = lower(v_q)) or p.friend_code = upper(replace(v_q, '-', '')))
      and not private.is_blocked(v_uid, p.id)
    limit 1;
end;
$$;

create or replace function public.send_friend_request(p_target uuid, p_source text default 'search')
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid    uuid := private.require_active();
  v_f      public.friendships%rowtype;
  v_source text := case when p_source in ('search', 'code', 'qr', 'link') then p_source else 'search' end;
begin
  perform private.consume_rate_limit('invitation');
  if p_target = v_uid then
    perform private.fail('cannot_befriend_self');
  end if;
  -- Blockierte und inaktive Profile sind nicht unterscheidbar von „nicht vorhanden“.
  if p_target is null or not private.is_active(p_target) or private.is_blocked(v_uid, p_target) then
    perform private.fail('profile_not_found');
  end if;

  select * into v_f from public.friendships f
   where least(f.requester_id, f.addressee_id) = least(v_uid, p_target)
     and greatest(f.requester_id, f.addressee_id) = greatest(v_uid, p_target)
   for update;

  if found then
    if v_f.status = 'accepted' then
      return 'already_friends';
    elsif v_f.status = 'pending' and v_f.requester_id = v_uid then
      return 'requested';
    elsif v_f.status = 'pending' then
      update public.friendships set status = 'accepted', responded_at = now()
       where requester_id = v_f.requester_id and addressee_id = v_f.addressee_id;
      perform private.notify(p_target, 'friend_accepted', jsonb_build_object('profileId', v_uid));
      return 'accepted';
    else -- declined: erneute Anfrage frühestens nach 30 Tagen (03 §6.2)
      if v_f.requester_id = v_uid and v_f.responded_at > now() - interval '30 days' then
        perform private.fail('request_cooldown');
      end if;
      delete from public.friendships
       where requester_id = v_f.requester_id and addressee_id = v_f.addressee_id;
    end if;
  end if;

  insert into public.friendships (requester_id, addressee_id, status, source)
  values (v_uid, p_target, 'pending', v_source);
  perform private.notify(p_target, 'friend_request', jsonb_build_object('profileId', v_uid));
  return 'requested';
end;
$$;

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
  perform private.consume_rate_limit('invitation');
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
  perform private.consume_rate_limit('invitation');
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
  perform private.consume_rate_limit('invitation');
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

create or replace function public.invite_scorer(p_match_id uuid, p_profile_id uuid, p_role text default 'scorer')
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_m   public.matches%rowtype;
begin
  perform private.consume_rate_limit('invitation');
  select * into v_m from public.matches m where m.id = p_match_id;
  if not found or v_m.deleted_at is not null then
    perform private.fail('match_not_found');
  end if;
  if not (v_m.created_by = v_uid
          or (v_m.tournament_id is not null and private.is_tournament_director(v_m.tournament_id, v_uid))) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_profile_id = v_uid then
    perform private.fail('cannot_invite_self');
  end if;
  if p_role not in ('scorer', 'director') then
    perform private.fail('invalid_role');
  end if;
  if p_profile_id is null or not private.is_active(p_profile_id) or private.is_blocked(v_uid, p_profile_id)
     or not (private.are_friends(v_uid, p_profile_id)
             or exists (select 1 from public.match_participants mp
                        where mp.match_id = p_match_id and mp.profile_id = p_profile_id)
             or (v_m.tournament_id is not null and private.is_tournament_staff(v_m.tournament_id, p_profile_id))) then
    perform private.fail('scorer_not_allowed');
  end if;
  if private.is_match_visitor(p_match_id, p_profile_id) then
    perform private.fail('is_visitor');
  end if;

  insert into public.match_roles as r (match_id, profile_id, role, status, invited_by)
  values (p_match_id, p_profile_id, p_role, 'invited', v_uid)
  on conflict (match_id, profile_id) do update
    set role = excluded.role,
        status = case when r.status = 'accepted' and r.role = excluded.role then 'accepted' else 'invited' end,
        invited_by = excluded.invited_by,
        invited_at = case when r.status = 'accepted' and r.role = excluded.role then r.invited_at else now() end,
        responded_at = case when r.status = 'accepted' and r.role = excluded.role then r.responded_at end;
  perform private.notify(p_profile_id, 'scorer_invited', jsonb_build_object('matchId', p_match_id, 'role', p_role));
end;
$$;

create or replace function public.add_match_visitor(p_match_id uuid, p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_m   public.matches%rowtype;
begin
  perform private.consume_rate_limit('invitation');
  select * into v_m from public.matches m where m.id = p_match_id;
  if not found or v_m.deleted_at is not null then
    perform private.fail('match_not_found');
  end if;
  if not (v_m.created_by = v_uid
          or exists (select 1 from public.match_participants mp
                     where mp.match_id = p_match_id and mp.profile_id = v_uid
                       and mp.link_status in ('self', 'linked', 'assigned', 'confirmed'))) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_profile_id is null or not private.are_friends(v_uid, p_profile_id) or private.is_blocked(v_uid, p_profile_id) then
    perform private.fail('visitor_must_be_friend');
  end if;
  if private.is_match_member(p_match_id, p_profile_id) then
    perform private.fail('already_member');
  end if;
  insert into public.match_visitors as v (match_id, profile_id, invited_by)
  values (p_match_id, p_profile_id, v_uid)
  on conflict (match_id, profile_id) do update
    set invited_by = excluded.invited_by, revoked_at = null, created_at = now();
  perform private.notify(p_profile_id, 'visitor_invited', jsonb_build_object('matchId', p_match_id));
end;
$$;

create or replace function public.add_tournament_staff(p_tournament_id uuid, p_profile_id uuid, p_role text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  perform private.consume_rate_limit('invitation');
  if not exists (select 1 from public.tournaments t where t.id = p_tournament_id and t.director_id = v_uid) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_role not in ('co_director', 'scorer') then
    perform private.fail('invalid_role');
  end if;
  if p_profile_id is null or p_profile_id = v_uid or not private.are_friends(v_uid, p_profile_id)
     or private.is_blocked(v_uid, p_profile_id) then
    perform private.fail('staff_must_be_friend');
  end if;
  insert into public.tournament_staff (tournament_id, profile_id, role)
  values (p_tournament_id, p_profile_id, p_role)
  on conflict (tournament_id, profile_id) do update set role = excluded.role;
end;
$$;
