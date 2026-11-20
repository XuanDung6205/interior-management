import importlib.util
import json
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "ci_packaged_smoke.py"


class PackagedSmokeTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "The packaged HTTP smoke helper is missing")
        spec = importlib.util.spec_from_file_location("ci_packaged_smoke", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def probe(self, overrides=None):
        responses = {
            "/api/commerce/runtime": (200, "application/json", json.dumps({"application": "tv3-commerce", "isolatedData": True}).encode()),
            "/cart": (200, "text/html", b'<html><script type="module" src="/assets/app.js"></script></html>'),
            "/orders/123": (200, "text/html", b'<html><script type="module" src="/assets/app.js"></script></html>'),
            "/assets/app.js": (200, "application/javascript", b"export const app = true;"),
            "/assets/ci-missing.js": (404, "application/json", b"{}"),
            "/api/ci-missing": (404, "application/json", b"{}"),
        }
        responses.update(overrides or {})
        return self.module.check(lambda path: responses[path])

    def test_checks_same_origin_deep_link_assets_and_api(self):
        self.assertEqual(self.probe()["checks"], 6)

    def test_rejects_a_live_database_before_other_requests(self):
        with self.assertRaisesRegex(ValueError, "isolated"):
            self.probe({"/api/commerce/runtime": (200, "application/json", b'{"application":"tv3-commerce","isolatedData":false}')})

    def test_rejects_api_falling_back_to_spa(self):
        with self.assertRaisesRegex(ValueError, "API"):
            self.probe({"/api/ci-missing": (200, "text/html", b"<html>spa</html>")})

    def test_rejects_missing_assets_falling_back_to_spa(self):
        with self.assertRaisesRegex(ValueError, "Missing asset"):
            self.probe({"/assets/ci-missing.js": (200, "text/html", b"<html>spa</html>")})

    def test_rejects_broken_deep_links(self):
        with self.assertRaisesRegex(ValueError, "Deep link"):
            self.probe({"/orders/123": (404, "application/json", b"{}")})

    def test_only_accepts_loopback_http_base_url(self):
        for value in ["https://example.com", "http://example.com", "http://127.0.0.1:8080/path", "http://user:pass@127.0.0.1"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.module.validate_base_url(value)
        self.assertEqual(self.module.validate_base_url("http://127.0.0.1:8080"), "http://127.0.0.1:8080")


if __name__ == "__main__":
    unittest.main()
