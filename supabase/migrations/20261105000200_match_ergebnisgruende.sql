-- Die App überträgt alle ResultReason-Werte kleingeschrieben. COMPLETED und DRAW fehlten in der Constraint:
-- apply_projection lieferte deshalb invalid, und beendete Matches blieben bei Mitspielern/Zuschauern live.
-- Bestehende Clients behalten diese Projektionen ausstehend und übertragen sie beim nächsten Abgleich erneut.
alter table public.matches drop constraint matches_result_reason_check;
alter table public.matches add constraint matches_result_reason_check
  check (result_reason in ('completed', 'draw', 'retired', 'walkover', 'abandoned', 'stopped'));
