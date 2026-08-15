import importlib.util
import json
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
        if self.path == "/api/web/health":
            raw = json.dumps({"status": "UP", "service": "openreach"}).encode()
            self.send_response(200)
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
        self.assertEqual(Handler.received[-1][0], "/api/web/health")

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

    def test_image_search_default_region_is_auto(self):
        self.client.image_search("lake", limit=8)
        self.assertEqual(Handler.received[-1][0], "/api/web/image-search")
        self.assertEqual(Handler.received[-1][1]["region"], "auto")

    def test_read(self):
        self.client.read("https://example.com", max_chars=20000)
        self.assertEqual(Handler.received[-1][0], "/api/web/read")
        self.assertEqual(Handler.received[-1][1]["maxChars"], 20000)


if __name__ == "__main__":
    unittest.main()
