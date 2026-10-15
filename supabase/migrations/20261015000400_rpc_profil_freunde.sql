-- Advantly P2-Spike · RPCs: Onboarding (Länder-/Altersabfrage), Suche, Freundschaften, Blockieren
-- Alle RPCs: SECURITY DEFINER mit search_path = '' und expliziten Prüfungen (Direktschreiben ist per Grant entzogen).
-- Fehler: SQLSTATE P0001 (fachlich) bzw. 42501 (Berechtigung), Meldung = stabiler Grund-Schlüssel.

create or replace function private.require_active()
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    perform private.fail('not_authenticated', '42501');
  end if;
  if not private.is_active(v_uid) then
    perform private.fail('profile_not_active', '42501');
  end if;
  -- Versionssperre vor allen Zeilensperren der RPC (Commit-Reihenfolge der Versionen, siehe private.touch_row)
  perform pg_advisory_xact_lock(private.sync_lock_key());
  return v_uid;
end;
$$;

-- F13: Länder-/Altersabfrage. Die App fragt Land und Geburtsdatum VOR der Kontoanlage (kein Konto für zu Junge);
-- der Server prüft nach dem Login erneut und speichert nur Land, Zeitpunkt und angewandte Grenze –
-- nicht das Geburtsdatum (Datenminimierung). Profile, die im Onboarding hängen bleiben, räumt ein Job auf.
create or replace function public.complete_onboarding(
  p_country text,
  p_birth_date date,
  p_alias text,
  p_username text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid    uuid := auth.uid();
  v_status text;
  v_rule   public.country_age_rules%rowtype;
  v_alias  text := btrim(coalesce(p_alias, ''));
  v_user   text := lower(nullif(btrim(coalesce(p_username, '')), ''));
begin
  if v_uid is null then
    perform private.fail('not_authenticated', '42501');
  end if;
  select p.status into v_status from public.profiles p where p.id = v_uid for update;
  if v_status is null then
    perform private.fail('profile_missing');
  elsif v_status <> 'onboarding' then
    perform private.fail('already_onboarded');
  end if;

  select * into v_rule from public.country_age_rules r where r.country = upper(btrim(coalesce(p_country, '')));
  if not found or not v_rule.allowed then
    perform private.fail('country_not_supported');
  end if;
  if p_birth_date is null or p_birth_date > current_date or p_birth_date < date '1900-01-01' then
    perform private.fail('invalid_birth_date');
  end if;
  if extract(year from age(current_date, p_birth_date)) < v_rule.min_age then
    perform private.fail('age_below_minimum');
  end if;
  if length(v_alias) not between 1 and 40 then
    perform private.fail('invalid_alias');
  end if;
  if v_user is not null and (v_user !~ '^[a-z0-9_.]{3,20}$') then
    perform private.fail('invalid_username');
  end if;
  if v_user is not null and exists (select 1 from public.profiles p where p.username = v_user and p.id <> v_uid) then
    perform private.fail('username_taken');
  end if;

  update public.profiles set status = 'active', alias = v_alias, username = v_user where id = v_uid;
  update public.profile_settings
     set country = v_rule.country, age_confirmed_at = now(), age_rule_min_age = v_rule.min_age, updated_at = now()
   where profile_id = v_uid;
end;
$$;

-- Exakte Suche (keine Präfix-/Volltextsuche → keine Enumeration): Benutzername (nur wenn auffindbar) oder Freundescode.
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

-- Freundschaftsanfrage. Ergebnis: requested | accepted (Gegenanfrage lag vor) | already_friends.
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

create or replace function public.respond_friend_request(p_requester uuid, p_accept boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  update public.friendships
     set status = case when p_accept then 'accepted' else 'declined' end, responded_at = now()
   where requester_id = p_requester and addressee_id = v_uid and status = 'pending';
  if not found then
    perform private.fail('no_pending_request');
  end if;
  if p_accept then
    perform private.notify(p_requester, 'friend_accepted', jsonb_build_object('profileId', v_uid));
  end if;
end;
$$;

-- Visitor-Freigaben hängen an der Freundschaft (F16) → beim Entfreunden/Blockieren beidseitig widerrufen.
create or replace function private.revoke_visitor_grants_between(p_a uuid, p_b uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.match_visitors
     set revoked_at = now()
   where revoked_at is null
     and ((invited_by = p_a and profile_id = p_b) or (invited_by = p_b and profile_id = p_a));
$$;

-- Entfreunden bzw. eigene offene Anfrage zurückziehen.
create or replace function public.remove_friend(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  delete from public.friendships f
   where least(f.requester_id, f.addressee_id) = least(v_uid, p_other)
     and greatest(f.requester_id, f.addressee_id) = greatest(v_uid, p_other);
  perform private.revoke_visitor_grants_between(v_uid, p_other);
end;
$$;

create or replace function public.block_user(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  if p_other = v_uid or not exists (select 1 from public.profiles p where p.id = p_other) then
    perform private.fail('profile_not_found');
  end if;
  insert into public.user_blocks (blocker_id, blocked_id) values (v_uid, p_other) on conflict do nothing;
  delete from public.friendships f
   where least(f.requester_id, f.addressee_id) = least(v_uid, p_other)
     and greatest(f.requester_id, f.addressee_id) = greatest(v_uid, p_other);
  perform private.revoke_visitor_grants_between(v_uid, p_other);
end;
$$;

create or replace function public.unblock_user(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  delete from public.user_blocks where blocker_id = v_uid and blocked_id = p_other;
end;
$$;
