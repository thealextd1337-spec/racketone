# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]>=3.2,<4", "websockets>=15,<17"]
# ///
"""Harness-Grenzen ohne Datenbank, Remoteverbindung oder echte Secrets prüfen.

uv run supabase/tests/170_cloud_device_binding_harness_test.py
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import import_module
import io
import ipaddress
import os
import socket
import sys
import threading
import unittest
from unittest.mock import patch


sys.dont_write_bytecode = True
live = import_module("170_cloud_device_binding_live")
DSN = "host=127.0.0.1 port=54547 dbname=advantly_u1_test user=postgres"


class HarnessSafetyTest(unittest.TestCase):
    def test_connection_target_is_explicit_numeric_loopback(self):
        for host in ["localhost", "127.0.0.1", "::1"]:
            with self.subTest(host=host), patch("socket.getaddrinfo", side_effect=AssertionError("Keine DNS-Abfrage erlaubt")):
                params = live.local_connection_parameters(DSN.replace("127.0.0.1", host))
                self.assertEqual(params["host"], params["hostaddr"])
                self.assertTrue(ipaddress.ip_address(params["hostaddr"]).is_loopback)
                self.assertEqual(params["sslmode"], "disable")
                self.assertEqual(params["gssencmode"], "disable")

    def test_dsn_overrides_are_rejected_before_connect(self):
        dsns = [DSN + " hostaddr=203.0.113.90", DSN + " hostaddr=127.0.0.1,203.0.113.90",
                DSN + " service=remote", DSN + " servicefile=remote", DSN + " passfile=remote",
                DSN.replace("127.0.0.1", "example.invalid"), DSN.replace("127.0.0.1", "127.0.0.1,203.0.113.90"),
                DSN.replace("advantly_u1_test", "postgresql://remote/db"),
                "postgresql://u:fake-secret@localhost/db?hostaddr=203.0.113.90", DSN + " password='fake-secret"]
        for dsn in dsns:
            self.assert_main_rejects(dsn, {})

    def test_environment_overrides_are_rejected_before_connect(self):
        for key in ["PGHOSTADDR", "PGHOST", "PGPORT", "PGSERVICE", "PGSERVICEFILE", "PGSYSCONFDIR", "PGDATABASE", "PGOPTIONS"]:
            with self.subTest(key=key):
                self.assert_main_rejects(DSN, {key: "fake-secret"})

    def assert_main_rejects(self, dsn, environment):
        output = io.StringIO()
        with patch.dict(os.environ, {"ADVANTLY_CLOUD_TEST_DB_URL": dsn, **environment}), \
                patch.object(sys, "argv", ["live", "--confirm-disposable", "--sql-only"]), \
                patch.object(live.psycopg, "connect") as connect, patch("sys.stderr", output):
            with self.assertRaises(SystemExit):
                live.main()
            connect.assert_not_called()
            self.assertNotIn("fake-secret", output.getvalue())

    def test_remote_or_ambiguous_http_url_is_rejected_before_open(self):
        for url in ["http://example.invalid/", "http://127.0.0.1@example.invalid/", "https://127.0.0.1/",
                    "http://127.0.0.1/#fragment", "http://u:fake-secret@127.0.0.1/"]:
            with self.subTest(url=url), patch.object(live, "build_opener") as opener:
                with self.assertRaises(ValueError):
                    live.local_http_json(url, {})
                opener.assert_not_called()

    def test_redirects_and_proxy_environment_never_forward_credentials(self):
        received = []
        status = [302]
        remote = [False]

        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.headers)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"{}")

            do_POST = do_GET

            def log_message(self, *args):
                pass

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)

        class Redirect(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(status[0])
                location = "http://203.0.113.90/sink" if remote[0] else f"http://127.0.0.1:{target.server_port}/sink"
                self.send_header("Location", location)
                self.end_headers()

            do_POST = do_GET

            def log_message(self, *args):
                pass

        origin = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (target, origin)]
        for thread in threads:
            thread.start()
        resolver = socket.getaddrinfo

        def loopback_only(host, *args, **kwargs):
            if not ipaddress.ip_address(host).is_loopback:
                raise AssertionError("Unerlaubtes Netzwerkziel; kein Remote-Socket geöffnet")
            return resolver(host, *args, **kwargs)

        try:
            headers = {"Authorization": "Bearer fake-bearer", "apikey": "fake-api-key",
                       "X-Advantly-Installation-Proof": "fake-proof"}
            with patch("socket.getaddrinfo", side_effect=loopback_only), patch.dict(os.environ, {
                "http_proxy": "http://203.0.113.90:1234", "HTTP_PROXY": "http://203.0.113.90:1234", "no_proxy": "", "NO_PROXY": ""}):
                for code in [301, 302, 303, 307, 308]:
                    for method in ["GET", "POST"]:
                        for remote_target in [False, True]:
                            with self.subTest(code=code, method=method, remote=remote_target):
                                status[0], remote[0] = code, remote_target
                                with self.assertRaisesRegex(AssertionError, "HTTP-Redirect verweigert"):
                                    live.local_http_json(f"http://127.0.0.1:{origin.server_port}/start", headers,
                                                         {} if method == "POST" else None, method)
                                self.assertEqual(received, [])
        finally:
            for server in (origin, target):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
