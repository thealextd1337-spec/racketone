-- H0: Telemetrie-Sichtbarkeit (F19/F29) kontoweit mit derselben Konfliktregel wie der Client
-- (shared/core-model TelemetryPreference: Lamport-Zähler, dann Restriktivität off > private > share, dann origin
-- lexikografisch). Gesundheitsdaten selbst bleiben bis P3 lokal. Dazu zwei Lücken aus Paket D:
-- my_friend_code hinter der Cloud-Bindung und die eigene Blockliste mit Alias.

-- counter 0 = nie geändert (Standard, ohne origin). origin = Installations-ID des ändernden Geräts (Client ≤ 64 Zeichen);
-- nur sichtbares ASCII, damit "C"-Sortierung und Kotlin-String-Vergleich dieselbe Reihenfolge ergeben.
alter table public.profile_settings
  add column telemetry_counter bigint not null default 0,
  add column telemetry_origin text,
  add constraint profile_settings_telemetry_version check (
    telemetry_counter >= 0 and (telemetry_counter = 0) = (telemetry_origin is null)
    and (telemetry_origin is null or telemetry_origin ~ '^[!-~]{1,64}$'));

-- Direktes UPDATE würde Zähler und Konfliktregel umgehen; ab jetzt nur noch über die RPC.
revoke update (telemetry_visibility) on public.profile_settings from authenticated;

-- Übernimmt den gesendeten Stand nur, wenn er nach der totalen Ordnung größer ist, und liefert immer den Serverstand
-- ({visibility, counter, origin}). p_counter = 0 (Client-Standard) schreibt nichts und liest nur – so holt ein Gerät
-- ohne eigene Änderung den Kontostand in einem Aufruf. Konto nur aus dem JWT, nie als Parameter.
create function public.cloud_set_telemetry_visibility_v1(p_visibility text, p_counter bigint, p_origin text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_result jsonb;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := private.require_active();
  if p_counter is null or p_counter < 0 or (p_counter > 0 and (
       p_visibility is null or p_visibility not in ('share', 'private', 'off')
       or p_origin is null or p_origin !~ '^[!-~]{1,64}$')) then
    perform private.fail('invalid_telemetry_setting');
  end if;
  if p_counter > 0 then
    update public.profile_settings s
       set telemetry_visibility = p_visibility, telemetry_counter = p_counter, telemetry_origin = p_origin,
           updated_at = now()
     where s.profile_id = v_uid
       and (p_counter, array_position(array['share', 'private', 'off'], p_visibility), p_origin collate "C")
         > (s.telemetry_counter, array_position(array['share', 'private', 'off'], s.telemetry_visibility),
            coalesce(s.telemetry_origin, '') collate "C");
  end if;
  select jsonb_build_object('visibility', s.telemetry_visibility, 'counter', s.telemetry_counter,
                            'origin', s.telemetry_origin)
    into strict v_result from public.profile_settings s where s.profile_id = v_uid;
  return v_result;
end;
$$;

-- Paket D: Der eigene Freundescode gehört wie alle sozialen RPCs hinter Bindung und Installationsnachweis.
create or replace function public.my_friend_code()
returns text language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_code text;
begin
  perform private.require_cloud_binding();
  v_uid := private.require_active();
  select p.friend_code into v_code from public.profiles p where p.id = v_uid;
  return v_code;
end;
$$;

-- Paket D: eigene Blockliste mit dem Alias der Blockierten (RLS gibt fremde Profile sonst nicht heraus).
-- Bewusst nur Alias, kein Benutzername/Freundescode; nicht aktive Profile ohne Alias.
create function public.cloud_my_blocks_v1()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := private.require_active();
  return (select coalesce(jsonb_agg(jsonb_build_object('profileId', b.blocked_id,
            'alias', case when p.status = 'active' then p.alias end, 'createdAt', b.created_at)
            order by b.created_at desc, b.blocked_id), '[]'::jsonb)
    from public.user_blocks b join public.profiles p on p.id = b.blocked_id
    where b.blocker_id = v_uid);
end;
$$;

revoke all on function public.cloud_set_telemetry_visibility_v1(text, bigint, text), public.cloud_my_blocks_v1()
  from public, anon, authenticated, service_role;
grant execute on function public.cloud_set_telemetry_visibility_v1(text, bigint, text), public.cloud_my_blocks_v1()
  to authenticated;
