# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""Schranken der Pilotprüfungen ohne Netz: Ziel nur lokal oder bestätigtes staging, Ausgabe ohne Geheimnisse.

uv run tools/pilot-checks/test_stack.py
"""
import contextlib
import io
import unittest
from unittest.mock import patch

import check_release_gate
import stack

REF = "abcdefghijklmnopqrst"
LOCAL = {"SUPABASE_URL": "http://127.0.0.1:54321", "SUPABASE_ANON_KEY": "anon",
         "ADVANTLY_PILOT_DB_URL": "postgresql://postgres:postgres@127.0.0.1:54322/postgres"}
STAGING = {"SUPABASE_URL": f"https://{REF}.supabase.co", "SUPABASE_ANON_KEY": "anon",
           "ADVANTLY_PILOT_DB_URL": f"postgresql://postgres.{REF}:geheim@aws-0-eu-central-1.pooler.supabase.com:5432/postgres"}


class TargetTest(unittest.TestCase):
    def test_local_needs_no_flag_and_forces_plain_loopback(self):
        kind, api, db = stack.resolve_target(LOCAL, None)
        self.assertEqual((kind, api, db["hostaddr"], db["sslmode"]), ("local", LOCAL["SUPABASE_URL"], "127.0.0.1",
                                                                      "disable"))
        with self.assertRaises(ValueError):
            stack.resolve_target(LOCAL, REF)

    def test_staging_only_with_matching_confirmation(self):
        self.assertEqual(stack.resolve_target(STAGING, REF)[0], "staging")
        direct = {**STAGING, "ADVANTLY_PILOT_DB_URL": f"postgresql://postgres:x@db.{REF}.supabase.co:5432/postgres"}
        self.assertEqual(stack.resolve_target(direct, REF)[2]["sslmode"], "require")
        for env, flag in [(STAGING, None), (STAGING, "tsrqponmlkjihgfedcba"), (STAGING, REF.upper()),
                          ({**STAGING, "SUPABASE_URL": f"http://{REF}.supabase.co"}, REF),
                          ({**STAGING, "SUPABASE_URL": f"https://{REF}.supabase.co.evil.example"}, REF),
                          ({**STAGING, "SUPABASE_URL": f"https://{REF}.supabase.co/rest"}, REF),
                          ({**STAGING, "ADVANTLY_PILOT_DB_URL": "postgresql://postgres.other:x@aws-0-eu-central-1"
                                                               ".pooler.supabase.com/postgres"}, REF),
                          ({**STAGING, "ADVANTLY_PILOT_DB_URL": LOCAL["ADVANTLY_PILOT_DB_URL"]}, REF),
                          ({**LOCAL, "ADVANTLY_PILOT_DB_URL": STAGING["ADVANTLY_PILOT_DB_URL"]}, REF),
                          ({**STAGING, "ADVANTLY_PILOT_DB_URL": STAGING["ADVANTLY_PILOT_DB_URL"] + "?service=x"}, REF),
                          ({**STAGING, "PGHOST": "127.0.0.1"}, REF)]:
            with self.subTest(env=env.get("SUPABASE_URL"), flag=flag), self.assertRaises(ValueError):
                stack.resolve_target(env, flag)

    def test_remote_host_without_flag_is_refused_before_any_connection(self):
        out = io.StringIO()
        with patch.object(stack, "Stack") as created, contextlib.redirect_stderr(out), \
                self.assertRaises(SystemExit):
            stack.run("x", lambda s: None, [], STAGING)
        created.assert_not_called()
        self.assertNotIn("geheim", out.getvalue())


class OutcomeTest(unittest.TestCase):
    def outcome(self, body):
        out = io.StringIO()
        with patch.object(stack, "Stack"), contextlib.redirect_stdout(out):
            code = stack.run("x", body, [], LOCAL)
        return code, out.getvalue()

    def test_pass_fail_inconclusive_without_leaking_messages(self):
        self.assertEqual(self.outcome(lambda s: None)[0], 0)

        def fail(s):
            stack.check(False, "Prüfschritt")
        code, text = self.outcome(fail)
        self.assertEqual(code, 1)
        self.assertIn("FAIL", text)
        self.assertIn("Prüfschritt", text)

        def crash(s):
            raise KeyError("token-secret")
        code, text = self.outcome(crash)
        self.assertEqual(code, 1)
        self.assertNotIn("token-secret", text)

        def unknown(s):
            raise stack.Inconclusive("Signup aus")
        self.assertEqual(self.outcome(unknown)[0], 2)


class MigrationContentTest(unittest.TestCase):
    TEXT = "-- Kopf\ncreate table t(a int);\n\ncreate function f() returns int language sql as $$ select 1; $$;\n"
    # So zerlegt die CLI (SplitAndTrim): Kommentar gehört zur folgenden Anweisung, ';' und Rand-Leerraum fehlen.
    STATEMENTS = ["-- Kopf\ncreate table t(a int)", "create function f() returns int language sql as $$ select 1; $$"]

    def test_only_the_exact_file_matches_its_stored_statements(self):
        same = check_release_gate.same_content
        self.assertTrue(same(self.TEXT, self.STATEMENTS))
        self.assertTrue(same(self.TEXT.replace("\n", "\r\n"), [s.replace("\n", "\r\n") for s in self.STATEMENTS]))
        for text, statements in [(self.TEXT.replace("a int", "b int"), self.STATEMENTS),
                                 (self.TEXT.replace("a int", "a bigint"), self.STATEMENTS),
                                 (self.TEXT.replace("-- Kopf", "-- Kopf geändert"), self.STATEMENTS),
                                 (self.TEXT + "drop table t;\n", self.STATEMENTS),
                                 (self.TEXT, self.STATEMENTS[:1]),
                                 (self.TEXT, self.STATEMENTS[::-1]),
                                 (self.TEXT, None)]:
            with self.subTest(text=text, statements=statements):
                self.assertFalse(same(text, statements))


if __name__ == "__main__":
    unittest.main()
