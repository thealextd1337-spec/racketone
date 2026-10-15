-- Advantly P2-Spike · Rechte (Least Privilege)
-- Supabase vergibt per Default-Privileges alles an anon/authenticated. Hier wird alles entzogen und gezielt
-- freigegeben; RLS bleibt die zweite Linie. Neue Migrationen müssen ihre Grants selbst setzen
-- (die Default-Privileges unten verhindern automatische Freigaben), Test 010 prüft das.

revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke all on all tables in schema ops from public, anon, authenticated;
revoke all on all sequences in schema ops from public, anon, authenticated;
revoke all on all sequences in schema private from public, anon, authenticated;

-- Lesen (RLS filtert)
grant select on public.country_age_rules to anon, authenticated;
grant select on
  public.profiles, public.profile_settings, public.user_blocks, public.friendships, public.devices,
  public.players, public.player_assignment_rejections,
  public.tournaments, public.tournament_courts, public.tournament_staff,
  public.matches, public.match_participants, public.match_link_rejections, public.match_roles,
  public.match_visitors, public.match_events, public.invites, public.entitlements
  to authenticated;

-- Direktes Schreiben nur, wo RLS allein genügt; alles andere über RPCs.
grant update (alias, username, discoverable) on public.profiles to authenticated;
grant update (telemetry_visibility, share_matches_default, locale) on public.profile_settings to authenticated;
grant delete on public.devices to authenticated;
grant insert, update, delete on public.tournaments, public.tournament_courts to authenticated;

-- service_role ohne TRUNCATE (Review P2 R3 #1): TRUNCATE nimmt zuerst eine Tabellensperre und umgeht die
-- Statement-Sperr-Reihenfolge. Wartung nur als Owner mit `select private.lock_sync();` vorab (supabase/README.md).
grant select, insert, update, delete, references, trigger on all tables in schema public to service_role;
revoke truncate on all tables in schema public from service_role, authenticated, anon;
grant all on all tables in schema ops to service_role;
grant usage, select on all sequences in schema ops to service_role;

-- Funktionen: Standard-EXECUTE für PUBLIC/anon entziehen, nur die RPCs freigeben.
revoke execute on all functions in schema public from public, anon, authenticated;
revoke execute on all functions in schema private from public, anon, authenticated;

grant execute on function
  public.complete_onboarding(text, date, text, text),
  public.find_profile(text),
  public.send_friend_request(uuid, text),
  public.respond_friend_request(uuid, boolean),
  public.remove_friend(uuid),
  public.block_user(uuid),
  public.unblock_user(uuid),
  public.register_device(uuid, text, text, uuid, boolean, text, text),
  public.sync_push(jsonb),
  public.sync_pull(bigint, integer),
  public.pull_match_events(uuid, bigint, integer),
  public.match_event_watermarks(uuid),
  public.invite_scorer(uuid, uuid, text),
  public.respond_scorer_invitation(uuid, boolean, uuid),
  public.revoke_scorer(uuid, uuid),
  public.add_match_visitor(uuid, uuid),
  public.revoke_match_visitor(uuid, uuid),
  public.get_visitor_match(uuid),
  public.my_visitor_matches(),
  public.assign_player(uuid, uuid),
  public.reject_player_assignment(uuid),
  public.unassign_player(uuid),
  public.create_invite(text, uuid, integer),
  public.redeem_invite(text),
  public.reject_match_participation(uuid),
  public.set_my_participation(uuid, boolean, boolean),
  public.request_account_deletion(),
  public.cancel_account_deletion(),
  public.export_my_data(),
  public.add_tournament_staff(uuid, uuid, text),
  public.remove_tournament_staff(uuid, uuid),
  public.tournament_overview(uuid)
  to authenticated;

-- RLS-Policies werten diese Helfer im Kontext des Aufrufers aus.
grant execute on function
  private.has_profile(uuid),
  private.is_active(uuid),
  private.friend_ids(uuid),
  private.is_tournament_staff(uuid, uuid),
  private.is_tournament_director(uuid, uuid),
  private.is_match_member(uuid, uuid),
  private.can_view_match(uuid, uuid),
  private.can_receive_realtime(text, uuid)
  to authenticated;

grant execute on all functions in schema public to service_role;
grant execute on all functions in schema private to service_role;
revoke execute on function public.admin_purge_due_accounts(interval) from authenticated;

-- Künftige Objekte dieses Rollen-Owners nicht mehr automatisch freigeben.
alter default privileges in schema public revoke execute on functions from public;
alter default privileges in schema public revoke all on functions from anon, authenticated;
alter default privileges in schema public revoke all on tables from anon, authenticated;
-- Neue Tabellen bekämen über Supabase-Default-Privileges sonst wieder TRUNCATE für service_role (Review P2 R4).
alter default privileges in schema public revoke truncate on tables from service_role;
alter default privileges in schema public revoke all on sequences from anon, authenticated;
