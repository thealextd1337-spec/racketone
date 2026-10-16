-- P2-A-Review: bestehende Funktionen aus main nur vorwärts gerichtet anpassen.
-- Wartung darf hinter langen Sync-Lesern höchstens 5 s auf die globale Versionssperre warten.
-- SET-Klauseln gelten auch für verschachtelte Aufrufe; beim Verlassen wird der Aufruferwert restauriert.
-- Ein äußerer Scheduler muss statement_timeout vor seinem SELECT setzen (siehe README).
alter function public.admin_purge_due_accounts(interval) set lock_timeout = '5s';
alter function public.admin_purge_due_accounts(interval) set statement_timeout = '60s';

-- JSON-Aufbau verwendet STABLE-Ausdrücke; IMMUTABLE war zu streng (plpgsql_check-Warnungen).
alter function private.sanitize_projection(jsonb) stable;
