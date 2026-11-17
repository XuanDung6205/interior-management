"""Safety and statistics checks; no live application is contacted."""
import json
import os
from pathlib import Path
from types import SimpleNamespace
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

import read_load as load
import explain_read as explain


class SafetyTests(unittest.TestCase):
    def test_session_does_not_inherit_tls_keylog_file(self):
        with patch.dict(os.environ, {'SSLKEYLOGFILE': str(Path(__file__).parent / 'missing' / 'tls-secrets')}):
            load.Session('http://localhost:1', 1)

    def test_rejects_remote_credential_urls_and_non_origin_urls(self):
        for url in ('http://example.com', 'http://127.0.0.1.evil.invalid',
                    'http://user:secret@localhost:8080', 'http://localhost/api',
                    'http://localhost?x=1', 'ftp://localhost', 'http://localhost#x'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                load.local_origin(url)
        self.assertEqual(load.local_origin('http://127.0.0.1:8080/'), 'http://127.0.0.1:8080')

    def test_runtime_must_explicitly_attest_isolation(self):
        for info in ({}, {'isolatedData': 1}, {'isolatedData': 'true'},
                     {'application': 'other', 'isolatedData': True}):
            with self.subTest(info=info), self.assertRaises(ValueError):
                load.require_isolation(info)
        load.require_isolation({'application': 'tv3-commerce', 'isolatedData': True})

    def test_no_arbitrary_paths_or_business_posts(self):
        session = load.Session('http://localhost:1', 1)
        for path, method in (('/api/cart/items', 'POST'), ('/api/session/logout', 'POST'),
                             ('//example.com', 'GET'), ('/api/orders/1', 'DELETE'),
                             ('/api/admin/refunds', 'GET'), ('/api/orders?size=10000', 'GET')):
            with self.subTest(path=path, method=method), self.assertRaises(ValueError):
                session.call(path, method)

    def test_credentials_and_read_calls_require_prior_isolation_check(self):
        session = load.Session('http://localhost:1', 1)
        for path, method, body in (('/api/cart', 'GET', None),
                                   ('/api/session/login', 'POST', b'username=secret')):
            with self.subTest(path=path), self.assertRaises(ValueError):
                session.call(path, method, body)

    def test_redirect_never_reaches_destination(self):
        reached = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                reached.append(self.path)
                self.send_response(302)
                self.send_header('Location', '/destination')
                self.end_headers()
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            session = load.Session(f'http://127.0.0.1:{server.server_port}', 2)
            with self.assertRaises(load.RequestFailure):
                session.call('/api/commerce/runtime')
            self.assertEqual(reached, ['/api/commerce/runtime'])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_explain_cannot_select_real_database_or_remote_host(self):
        for database in ('furniture_shop', 'furniture_shop_test_', 'furniture_shop_tv3_dev;DROP DATABASE x'):
            with self.subTest(database=database), self.assertRaises(ValueError):
                explain.safe_database(database)
        for database in ('furniture_shop_tv3_dev', 'furniture_shop_test_read_1'):
            explain.safe_database(database)
        with self.assertRaises(ValueError):
            explain.local_host('db.example.com')


class StatisticsTests(unittest.TestCase):
    def test_nearest_rank_and_errors_have_distinct_counts(self):
        report = load.summarize([(10.0, None), (20.0, None), (30.0, 'HTTP_503'),
                                 (40.0, None), (50.0, None)], 2.0)
        self.assertEqual(report['count'], 5)
        self.assertEqual(report['errors'], 1)
        self.assertEqual(report['errorKinds'], {'HTTP_503': 1})
        self.assertEqual(report['p50Ms'], 30.0)
        self.assertEqual(report['p95Ms'], 50.0)
        self.assertEqual(report['maxMs'], 50.0)
        self.assertEqual(report['requestsPerSecond'], 2.5)
        self.assertEqual(report['successes'], 4)

    def test_empty_samples_are_not_invented_measurements(self):
        result = load.summarize([], 2)
        self.assertIsNone(result['p95Ms'])
        self.assertIsNone(result['p50Ms'])
        self.assertEqual(result['count'], 0)

    def test_error_output_contains_category_only(self):
        failure = load.RequestFailure('HTTP_401')
        result = load.summarize([(4.0, failure.kind)], 1)
        self.assertNotIn('password', json.dumps(result))
        self.assertEqual(result['errorKinds'], {'HTTP_401': 1})


class LocalProtocolTests(unittest.TestCase):
    def test_full_run_keeps_sessions_separate_excludes_setup_and_omits_bodies(self):
        # A missing CSRF header, shared worker jar, unguarded route, retained
        # response body or counted warmup would break this real HTTP boundary.
        calls, logged_in = [], {}
        class Handler(BaseHTTPRequestHandler):
            def reply(self, payload, cookie=None, status=200):
                raw = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                if cookie:
                    self.send_header('Set-Cookie', cookie)
                self.end_headers()
                self.wfile.write(raw)
            def do_GET(self):
                calls.append(('GET', self.path))
                if self.path == '/api/commerce/runtime':
                    self.reply({'application': 'tv3-commerce', 'isolatedData': True})
                elif self.path == '/api/csrf':
                    self.reply({'token': 'csrf-sensitive-token', 'headerName': 'X-CSRF-TOKEN'})
                elif self.path == '/api/session':
                    role = logged_in[self.headers['Cookie']]
                    self.reply({'authenticated': True, 'user': {'roles': [role], 'username': 'private-user'}})
                elif self.path == '/api/cart':
                    self.reply({'itemCount': 2, 'items': [{'private': 'secret-customer-data'}]})
                elif self.path in load.READ_PATHS:
                    self.reply({'totalElements': 13, 'page': 0, 'size': 10,
                                'items': [{'private': 'secret-customer-data'}]})
                else:
                    self.reply({}, status=400)
            def do_POST(self):
                calls.append(('POST', self.path))
                body = parse_qs(self.rfile.read(int(self.headers['Content-Length'])).decode())
                if self.path != '/api/session/login' or self.headers.get('X-CSRF-TOKEN') != 'csrf-sensitive-token':
                    self.reply({}, status=403)
                    return
                cookie = f'JSESSIONID=session{len(logged_in)}'
                logged_in[cookie] = 'CUSTOMER' if body['username'] == ['customer'] else 'STAFF'
                self.reply({'authenticated': True}, cookie=cookie)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            args = SimpleNamespace(base_url=f'http://127.0.0.1:{server.server_port}',
                                   timeout=2, workers=2, rounds=3, warmup_rounds=1,
                                   think_ms=0, scheduler_note='test', db_pool_note='test',
                                   dataset_note='test', window_note='test')
            with patch.dict(os.environ, {'TV3_PERF_CUSTOMER_USERNAME': 'customer',
                                         'TV3_PERF_CUSTOMER_PASSWORD': 'credential-secret',
                                         'TV3_PERF_STAFF_USERNAME': 'staff',
                                         'TV3_PERF_STAFF_PASSWORD': 'credential-secret'}):
                report = load.run(args)
            self.assertEqual(calls[0], ('GET', '/api/commerce/runtime'))
            self.assertEqual(len(logged_in), 4)
            self.assertEqual([path for method, path in calls if method == 'POST'], ['/api/session/login'] * 4)
            self.assertEqual(report['overall']['count'], 24)
            self.assertEqual(report['overall']['errors'], 0)
            self.assertEqual(report['endpoints']['adminPayments']['count'], 6)
            self.assertEqual(report['configuration']['warmup']['excludedRequests'], 8)
            self.assertEqual(report['dataset']['before']['customerOrders']['totalElements'], 13)
            for secret in ('credential-secret', 'secret-customer-data', 'private-user', 'csrf-sensitive-token', 'JSESSIONID'):
                self.assertNotIn(secret, json.dumps(report))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
