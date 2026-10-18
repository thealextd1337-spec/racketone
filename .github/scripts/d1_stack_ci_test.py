# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""Gezielte Regressionen für die D1-CI-Schranken, XML-Abnahme und Fehlerbereinigung."""
import asyncio
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import AsyncMock, Mock, patch

import d1_stack_ci as d1


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.temp = Path(self.directory.name)
        self.root = self.temp / "checkout"
        self.root.mkdir()
        self.env = {"GITHUB_ACTIONS": "true", "SUPABASE_URL": d1.API,
                    "ADVANTLY_EXPORT_TEST_DB_URL": d1.DB, "RUNNER_TEMP": str(self.temp),
                    "SUPABASE_ANON_KEY": "anon", "SUPABASE_SERVICE_ROLE_KEY": "service"}

    def test_explicit_ci_confirmation_and_exact_endpoints(self):
        self.assertEqual(self.temp.resolve(), d1.guard(True, self.env, self.root))
        with self.assertRaises(ValueError):
            d1.guard(False, self.env, self.root)
        for key, value in [("GITHUB_ACTIONS", "false"), ("PGHOST", "127.0.0.1"),
                           ("PGSERVICEFILE", "ignored"), ("SUPABASE_URL", "https://example.com"),
                           ("SUPABASE_URL", d1.API + "/"), ("SUPABASE_URL", "http://localhost:54321"),
                           ("SUPABASE_URL", d1.API + "?redirect=1"),
                           ("ADVANTLY_EXPORT_TEST_DB_URL", d1.DB + "?host=example.com"),
                           ("SUPABASE_ANON_KEY", ""), ("SUPABASE_SERVICE_ROLE_KEY", "")]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                d1.guard(True, dict(self.env, **{key: value}), self.root)

    def test_fixture_outside_checkout_exclusive_and_private(self):
        inside = self.root / "temp"
        inside.mkdir()
        for location in [self.root, inside]:
            with self.assertRaises(ValueError):
                d1.guard(True, dict(self.env, RUNNER_TEMP=str(location)), self.root)
        a = d1.write_fixture(self.temp, {"secret": "first"})
        b = d1.write_fixture(self.temp, {"secret": "second"})
        self.assertNotEqual(a, b)
        self.assertEqual({"secret": "first"}, json.loads(a.read_text()))
        self.assertEqual(self.temp.resolve(), a.parent.resolve())
        if os.name != "nt":
            self.assertEqual(0o600, stat.S_IMODE(a.stat().st_mode))
        with patch.object(d1.json, "dump", side_effect=RuntimeError("write failed")):
            with self.assertRaises(RuntimeError):
                d1.write_fixture(self.temp, {})
        self.assertEqual({a, b}, set(self.temp.glob("advantly-d1-*.json")))

    def test_no_proxy_or_redirect(self):
        stack = d1.Stack()
        self.assertFalse(any(getattr(h, "proxies", {}) for h in stack.opener.handlers))
        self.assertIsNone(d1.NoRedirect().redirect_request(None, None, None, None, None, None))


class XmlTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "result.xml"

    def xml(self, **changes):
        # Tatsächliche Gradle-KMP-JUnit-Form; classname bleibt vollständig qualifiziert.
        values = {"tests": "1", "failures": "0", "errors": "0", "skipped": "0", "name": "CloudPullStackTest[jvm]"}
        values.update(changes)
        attrs = " ".join(f'{k}="{v}"' for k, v in values.items())
        return f'<testsuite {attrs}><testcase classname="{d1.CLASS}" name="{d1.METHOD}[jvm]" /></testsuite>'

    def test_only_exact_executed_method_passes(self):
        self.path.write_text(self.xml())
        d1.verify_xml(self.path)

    def test_missing_malformed_wrong_counters_or_method_fail(self):
        with self.assertRaises(FileNotFoundError):
            d1.verify_xml(self.path)
        variants = ["<invalid", self.xml(tests="0"), self.xml(tests="2"), self.xml(failures="1"),
                    self.xml(errors="1"), self.xml(skipped="1"), self.xml(name="Other"),
                    self.xml(name=d1.CLASS), self.xml(name="CloudPullStackTest[android]"),
                    self.xml().replace(f'{d1.METHOD}[jvm]', d1.METHOD),
                    self.xml().replace(f'{d1.METHOD}[jvm]', f'{d1.METHOD}[android]'),
                    self.xml().replace(d1.METHOD, "otherMethod"),
                    self.xml().replace(f'classname="{d1.CLASS}"', 'classname="Other"'),
                    self.xml().replace(" /></testsuite>", "><skipped /></testcase></testsuite>"),
                    self.xml().replace(" /></testsuite>", "><failure /></testcase></testsuite>"),
                    self.xml().replace(" /></testsuite>", "><error /></testcase></testsuite>"),
                    self.xml().replace("</testsuite>", "<testcase /></testsuite>")]
        for xml in variants:
            with self.subTest(xml=xml), self.assertRaises(Exception):
                self.path.write_text(xml)
                d1.verify_xml(self.path)

    def test_mismatch_diagnostics_never_emit_xml_values(self):
        self.path.write_text(self.xml(name="SECRET_SUITE").replace(d1.METHOD, "SECRET_METHOD"))
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaises(RuntimeError):
            d1.verify_xml(self.path)
        self.assertNotIn("SECRET", output.getvalue())
        self.assertIn("suite_ok=False", output.getvalue())
        self.assertIn("method_ok=False", output.getvalue())
        self.assertIn("counters_ok=True", output.getvalue())


class LifecycleTest(unittest.TestCase):
    def test_cleanup_always_runs_and_primary_is_preserved(self):
        for primary in [None, RuntimeError("primary")]:
            for cleanup in [None, ValueError("cleanup")]:
                with self.subTest(primary=primary, cleanup=cleanup):
                    stack = Mock()
                    stack.cleanup.side_effect = cleanup
                    action = Mock(side_effect=primary)
                    with contextlib.redirect_stderr(io.StringIO()):
                        if primary is not None or cleanup is not None:
                            with self.assertRaises(type(primary or cleanup)) as raised:
                                d1.execute(stack, action)
                            self.assertIs(primary or cleanup, raised.exception)
                        else:
                            d1.execute(stack, action)
                    stack.cleanup.assert_called_once()

    def test_restore_failure_still_removes_fixture_and_both_accounts(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = d1.Stack()
            stack.fixture = d1.write_fixture(Path(directory), {})
            stack.previous = [("TERMS", "unreleased"), ("PRIVACY", "unreleased")]
            stack.users = ["a", "b"]
            stack.admin = Mock(side_effect=[RuntimeError("first delete"), {}])
            with patch.object(d1.psycopg, "connect", side_effect=RuntimeError("restore")):
                with self.assertRaises(RuntimeError):
                    stack.cleanup()
            self.assertFalse(stack.fixture.exists())
            self.assertEqual([("/a", "DELETE"), ("/b", "DELETE")], [c.args for c in stack.admin.call_args_list])

    def test_gradle_reruns_exact_method_without_cached_or_stale_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "result.xml"
            xml.write_text("stale")
            process = Mock(returncode=0)
            process.wait = AsyncMock(return_value=0)
            create = AsyncMock(return_value=process)
            with patch.object(d1, "XML", xml), patch.object(d1.asyncio, "create_subprocess_exec", create), \
                    patch.object(d1.subprocess, "run", return_value=Mock(stdout='', stderr='openjdk version "21.0.1"')), \
                    patch.dict(os.environ, {"HTTPS_PROXY": "secret-proxy", "JAVA_TOOL_OPTIONS": "secret-option"}):
                with self.assertRaises(FileNotFoundError):
                    asyncio.run(d1.run_gradle(Path(directory) / "fixture.json"))
            self.assertFalse(xml.exists())
            args, kwargs = create.call_args
            self.assertIn(f"{d1.CLASS}.{d1.METHOD}", args)
            for flag in ["--tests", "--rerun-tasks", "--no-build-cache", "--max-workers=2"]:
                self.assertIn(flag, args)
            self.assertNotIn("HTTPS_PROXY", kwargs["env"])
            self.assertNotIn("JAVA_TOOL_OPTIONS", kwargs["env"])
            self.assertEqual(d1.ROOT, kwargs["cwd"])

    def test_wrong_jdk_stops_before_gradle(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(d1, "XML", Path(directory) / "result.xml"), \
                    patch.object(d1.subprocess, "run", return_value=Mock(stdout='', stderr='openjdk version "22.0.1"')), \
                    patch.object(d1.asyncio, "create_subprocess_exec", AsyncMock()) as create:
                with self.assertRaises(RuntimeError):
                    asyncio.run(d1.run_gradle(Path(directory) / "fixture.json"))
                create.assert_not_called()

    def test_gradle_failure_cannot_pass_with_successful_xml_or_emit_error_values(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "result.xml"

            async def failed():
                xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                               '<failure>SECRET CloudPullStackTest.kt:123</failure></testsuite>')
                return 1

            process = Mock(returncode=1)
            process.wait = failed
            output = io.StringIO()
            with patch.object(d1, "XML", xml), \
                    patch.object(d1.subprocess, "run", return_value=Mock(stdout='', stderr='openjdk version "21.0.1"')), \
                    patch.object(d1.asyncio, "create_subprocess_exec", AsyncMock(return_value=process)), \
                    contextlib.redirect_stderr(output):
                with self.assertRaises(RuntimeError):
                    asyncio.run(d1.run_gradle(Path(directory) / "fixture.json"))
            self.assertIn("CloudPullStackTest.kt:123", output.getvalue())
            self.assertNotIn("SECRET", output.getvalue())


if __name__ == "__main__":
    unittest.main()
