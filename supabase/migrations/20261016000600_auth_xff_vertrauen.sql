-- Hosted überschreibt cf-connecting-ip am Cloudflare-Gateway. XFF[1] kann clientgewählt sein.
-- Nur lokale/Self-Host-Konfiguration darf XFF ausdrücklich freigeben; hier KEIN DB-Default.
-- CREATE OR REPLACE erhält Owner/Grants; Aufrufkontext, STABLE und leerer search_path bleiben gleich.
create or replace function private.age_client_ip() returns text
language plpgsql stable security invoker set search_path = '' as $$
declare
  v_headers jsonb := coalesce(nullif(current_setting('request.headers', true), ''), '{}')::jsonb;
  v_ip text;
begin
  v_ip := nullif(btrim(v_headers ->> 'cf-connecting-ip'), '');
  if v_ip is null and current_setting('app.settings.trust_xff', true) = 'on' then
    v_ip := nullif(btrim(split_part(v_headers ->> 'x-forwarded-for', ',', 1)), '');
  end if;
  if v_ip is null or v_ip like '%/%' then return null; end if;
  return host(v_ip::inet);
exception when invalid_text_representation then return null;
end;
$$;
