import importlib.util
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / "scripts" / "openreach.py"
spec = importlib.util.spec_from_file_location("openreach_skill", MODULE)
openreach = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = openreach
assert spec.loader is not None
spec.loader.exec_module(openreach)


class Handler(BaseHTTPRequestHandler):
    received = []

    def do_GET(self):
        Handler.received.append((self.path, None))
        if self.path == "/":
            raw = b"<html><title>OpenReach</title></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
        else:
            raw = b'{}'
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        Handler.received.append((self.path, body))
        if self.path == "/api/web/search" and body == {}:
            raw = json.dumps({
                "status": 400,
                "code": "VALIDATION_ERROR",
                "message": "query: must not be blank",
            }).encode("utf-8")
            self.send_response(400)
        else:
            raw = json.dumps({"ok": True, "path": self.path, "body": body}).encode("utf-8")
            self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *_args):
        pass


class OpenReachClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()

    def setUp(self):
        Handler.received.clear()
        self.client = openreach.OpenReachClient(self.base_url, timeout=2)

    def test_doctor(self):
        result = self.client.health()
        self.assertEqual(result["status"], "UP")
        self.assertEqual(Handler.received[-1][0], "/")


    def test_check_missing_config_performs_zero_network_requests(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "config.json"
            Handler.received.clear()
            result = openreach.check_initialized(path, timeout=2)
            self.assertFalse(result["initialized"])
            self.assertEqual(result["reason"], "CONFIG_MISSING")
            self.assertEqual(result["networkProbes"], 0)
            self.assertTrue(result["userActionRequired"])
            self.assertEqual(result["nextAction"], "ASK_USER_FOR_SERVICE_ADDRESS")
            self.assertIn("<OPENREACH_BASE_URL>", result["message"])
            self.assertEqual(Handler.received, [])
            self.assertFalse(path.exists())

    def test_check_missing_config_does_not_use_env_or_localhost_fallback(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "config.json"
            old = os.environ.get("OPENREACH_BASE_URL")
            os.environ["OPENREACH_BASE_URL"] = self.base_url
            Handler.received.clear()
            try:
                result = openreach.check_initialized(path, timeout=2)
                self.assertFalse(result["initialized"])
                self.assertEqual(result["reason"], "CONFIG_MISSING")
                self.assertEqual(result["networkProbes"], 0)
                self.assertEqual(Handler.received, [])
            finally:
                if old is None:
                    os.environ.pop("OPENREACH_BASE_URL", None)
                else:
                    os.environ["OPENREACH_BASE_URL"] = old


    def test_resolve_base_url_without_config_or_env_never_guesses_default_host(self):
        with tempfile.TemporaryDirectory() as td:
            old_config = openreach.CONFIG_PATH
            old_env = os.environ.pop("OPENREACH_BASE_URL", None)
            openreach.CONFIG_PATH = Path(td) / "config.json"
            try:
                with self.assertRaises(openreach.OpenReachError) as ctx:
                    openreach.resolve_base_url()
                message = str(ctx.exception)
                self.assertIn("<OPENREACH_BASE_URL>", message)
                self.assertIn("do not guess", message.lower())
            finally:
                openreach.CONFIG_PATH = old_config
                if old_env is not None:
                    os.environ["OPENREACH_BASE_URL"] = old_env

    def test_init_cli_help_contains_no_example_ip(self):
        help_text = openreach.build_parser().format_help()
        init_parser = next(
            action for action in openreach.build_parser()._actions
            if isinstance(action, openreach.argparse._SubParsersAction)
        ).choices["init"]
        init_help = init_parser.format_help()
        combined = help_text + init_help
        self.assertNotIn("192.168.", combined)
        self.assertNotIn("10.0.0.", combined)
        self.assertIn("user", init_help.lower())

    def test_check_existing_config_performs_exactly_one_side_effect_free_api_probe(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "config.json"
            path.write_text(json.dumps({"base_url": self.base_url}), encoding="utf-8")
            before = path.read_text(encoding="utf-8")
            Handler.received.clear()
            result = openreach.check_initialized(path, timeout=2)
            self.assertTrue(result["initialized"])
            self.assertEqual(result["networkProbes"], 1)
            self.assertEqual(result["probe"], "POST /api/web/search")
            self.assertEqual(result["code"], "VALIDATION_ERROR")
            self.assertEqual(Handler.received, [("/api/web/search", {})])
            self.assertEqual(path.read_text(encoding="utf-8"), before)

    def test_check_invalid_config_does_not_probe_or_repair(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "config.json"
            path.write_text("{invalid", encoding="utf-8")
            before = path.read_text(encoding="utf-8")
            Handler.received.clear()
            result = openreach.check_initialized(path, timeout=2)
            self.assertFalse(result["initialized"])
            self.assertEqual(result["reason"], "CONFIG_INVALID")
            self.assertEqual(result["networkProbes"], 0)
            self.assertEqual(Handler.received, [])
            self.assertEqual(path.read_text(encoding="utf-8"), before)

    def test_initialize_checks_health_then_saves_config(self):
        with tempfile.TemporaryDirectory() as td:
            old = openreach.CONFIG_PATH
            openreach.CONFIG_PATH = Path(td) / "config.json"
            try:
                result = openreach.initialize(self.base_url, timeout=2)
                self.assertEqual(result["status"], "OK")
                saved = json.loads(openreach.CONFIG_PATH.read_text())
                self.assertEqual(saved["base_url"], self.base_url)
                self.assertEqual(openreach.resolve_base_url(), self.base_url)
            finally:
                openreach.CONFIG_PATH = old

    def test_search_default_region_is_auto(self):
        result = self.client.search("agent", limit=5, provider="auto")
        self.assertTrue(result["ok"])
        self.assertEqual(Handler.received[-1][0], "/api/web/search")
        self.assertEqual(Handler.received[-1][1]["region"], "auto")
        self.assertEqual(Handler.received[-1][1]["timeRange"], "any")

    def test_search_time_range_is_forwarded(self):
        self.client.search("agent", limit=3, region="US", time_range="week")
        payload = Handler.received[-1][1]
        self.assertEqual(payload["timeRange"], "week")
        self.assertEqual(payload["region"], "US")

    def test_image_search_default_region_is_auto(self):
        self.client.image_search("lake", limit=8)
        self.assertEqual(Handler.received[-1][0], "/api/web/image-search")
        self.assertEqual(Handler.received[-1][1]["region"], "auto")

    def test_read(self):
        self.client.read("https://example.com", max_chars=20000)
        self.assertEqual(Handler.received[-1][0], "/api/web/read")
        self.assertEqual(Handler.received[-1][1]["maxChars"], 20000)


    def test_read_rejects_private_attachment_url_before_network(self):
        Handler.received.clear()
        with self.assertRaises(openreach.OpenReachError) as ctx:
            self.client.read("http://172.16.114.23:8999/images/chat/example.png")
        self.assertIn("READ_TARGET_PRIVATE", str(ctx.exception))
        self.assertEqual(Handler.received, [])

    def test_read_rejects_non_standard_public_port_before_network(self):
        Handler.received.clear()
        with self.assertRaises(openreach.OpenReachError) as ctx:
            self.client.read("https://example.com:8443/article")
        self.assertIn("READ_TARGET_PORT", str(ctx.exception))
        self.assertEqual(Handler.received, [])

    def test_read_rejects_binary_image_url_before_network(self):
        Handler.received.clear()
        with self.assertRaises(openreach.OpenReachError) as ctx:
            self.client.read("https://example.com/assets/photo.png")
        self.assertIn("READ_TARGET_BINARY", str(ctx.exception))
        self.assertEqual(Handler.received, [])


if __name__ == "__main__":
    unittest.main()
