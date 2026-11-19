"""Guarded local TV3 read benchmark. Python standard library only.

Only /api/session/login may POST (real session + CSRF); measured requests are
fixed GET routes. Credentials, cookies and response bodies are not saved.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.cookiejar
import json
import math
import os
from pathlib import Path
import platform
import ssl
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import (Request, build_opener, HTTPCookieProcessor,
                            HTTPRedirectHandler, HTTPSHandler, ProxyHandler)

ENDPOINTS = {
    'cart': ('customer', '/api/cart'),
    'customerOrders': ('customer', '/api/orders?page=0&size=10'),
    'adminOrders': ('staff', '/api/admin/orders?page=0&size=10'),
    'adminPayments': ('staff', '/api/admin/payments?page=0&size=10'),
    'adminOrdersDeep': ('staff', '/api/admin/orders?page=9&size=100'),
    'adminPaymentsPending': ('staff', '/api/admin/payments?page=0&size=100&status=PENDING'),
    'adminOrdersCancelled': ('staff', '/api/admin/orders?page=0&size=100&status=CANCELLED'),
    'operations': ('staff', '/api/admin/operations'),
}
READ_PATHS = frozenset(path for _, path in ENDPOINTS.values()) | {
    '/api/commerce/runtime', '/api/csrf', '/api/session',
}


def local_origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme not in {'http', 'https'} or
        parsed.hostname not in {'localhost', '127.0.0.1', '::1'} or
        parsed.username is not None or parsed.password is not None or
        parsed.path not in {'', '/'} or parsed.query or parsed.fragment):
        raise ValueError('A loopback HTTP(S) origin without credentials/path/query is required.')
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError('Invalid local port.')
    return value.rstrip('/')


def require_isolation(info):
    if not isinstance(info, dict) or info.get('application') != 'tv3-commerce' or info.get('isolatedData') is not True:
        raise ValueError('Runtime must explicitly attest tv3-commerce isolatedData:true.')


class RequestFailure(Exception):
    def __init__(self, kind):
        self.kind = kind
        super().__init__(kind)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Session:
    def __init__(self, base_url, timeout):
        self.base_url = local_origin(base_url)
        self.timeout = timeout
        self.isolated = False
        # Explicit validated context avoids inheriting SSLKEYLOGFILE secrets.
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        tls.load_default_certs()
        # Ignore machine proxy settings; never forward credentials or redirects.
        self.opener = build_opener(ProxyHandler({}), NoRedirect(), HTTPSHandler(context=tls),
                                   HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, method='GET', body=None, headers=None):
        if not ((method == 'GET' and path in READ_PATHS and body is None) or
                (method == 'POST' and path == '/api/session/login' and body is not None)):
            raise ValueError('Only fixed read routes and session login are allowed.')
        if not self.isolated and path != '/api/commerce/runtime':
            raise ValueError('The first request must verify runtime isolation.')
        request_headers = {'Accept': 'application/json'}
        request_headers.update(headers or {})
        if body is not None:
            request_headers['Content-Type'] = 'application/x-www-form-urlencoded'
        request = Request(self.base_url + path, method=method, data=body, headers=request_headers)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                if response.status != 200:
                    raise RequestFailure('HTTP_' + str(response.status))
                payload = response.read(8 * 1024 * 1024 + 1)
                if len(payload) > 8 * 1024 * 1024:
                    raise RequestFailure('RESPONSE_TOO_LARGE')
                if 'application/json' not in response.headers.get('Content-Type', ''):
                    raise RequestFailure('INVALID_RESPONSE')
        except HTTPError as error:
            # Deliberately never emit server body, URL, reason or headers.
            error.close()
            raise RequestFailure('HTTP_' + str(error.code)) from None
        except (URLError, TimeoutError, OSError):
            raise RequestFailure('NETWORK_ERROR') from None
        try:
            result = json.loads(payload)
        except (ValueError, UnicodeError):
            raise RequestFailure('INVALID_JSON') from None
        if not isinstance(result, dict):
            raise RequestFailure('INVALID_RESPONSE')
        if path == '/api/commerce/runtime':
            require_isolation(result)
            self.isolated = True
        return result

    def login(self, username, password, role):
        self.call('/api/commerce/runtime')
        token = self.call('/api/csrf')
        if not isinstance(token.get('token'), str) or token.get('headerName') != 'X-CSRF-TOKEN':
            raise RequestFailure('INVALID_CSRF_RESPONSE')
        self.call('/api/session/login', 'POST', urlencode({'username': username, 'password': password}).encode(),
                  {token['headerName']: token['token']})
        # Check the newly authenticated session rather than trusting login alone.
        session = self.call('/api/session')
        roles = (session.get('user') or {}).get('roles', [])
        allowed = {'CUSTOMER'} if role == 'customer' else {'STAFF', 'ADMIN'}
        if session.get('authenticated') is not True or not allowed.intersection(roles):
            raise RequestFailure('ROLE_CHECK_FAILED')


def summarize(samples, seconds):
    values = sorted(elapsed for elapsed, _ in samples)
    errors = Counter(kind for _, kind in samples if kind is not None)
    def rank(percentile):
        return round(values[max(0, math.ceil(percentile * len(values)) - 1)], 3) if values else None
    return {'count': len(values), 'successes': len(values) - sum(errors.values()),
            'errors': sum(errors.values()), 'errorKinds': dict(sorted(errors.items())),
            'errorRatePercent': round(100 * sum(errors.values()) / len(values), 3) if values else None,
            'p50Ms': rank(.50), 'p95Ms': rank(.95),
            'maxMs': round(max(values), 3) if values else None,
            'requestsPerSecond': round(len(values) / seconds, 3) if seconds > 0 else None}


def operations_snapshot(value):
    # Explicit safe fields only: never retain expiry order identifiers, customer
    # text, datasource URLs, credentials or future endpoint additions.
    fields = {'pool': ('active', 'idle', 'total', 'awaiting', 'max', 'minIdle', 'available'),
              'jobs': ('enabled', 'expiryEnabled', 'notificationEnabled', 'confirmationTtlSeconds'),
              'outbox': ('pending', 'retry', 'dead', 'sent')}
    return {group: {key: value.get(group, {}).get(key) for key in keys
                    if isinstance(value.get(group, {}).get(key), (int, bool))}
            for group, keys in fields.items()}


def dataset_snapshot(sessions):
    snapshot = {}
    for name, (role, path) in ENDPOINTS.items():
        value = sessions[role].call(path)
        if name == 'operations':
            continue
        if name == 'cart':
            snapshot[name] = {'itemCount': value.get('itemCount'), 'lines': len(value.get('items', []))}
        else:
            snapshot[name] = {'totalElements': value.get('totalElements'),
                              'page': value.get('page'), 'size': value.get('size')}
    return snapshot


def credentials(role):
    prefix = 'TV3_PERF_' + role.upper()
    username, password = os.environ.get(prefix + '_USERNAME'), os.environ.get(prefix + '_PASSWORD')
    if not username or not password:
        raise ValueError('Set ' + prefix + '_USERNAME and ' + prefix + '_PASSWORD in the environment.')
    return username, password


def run(args):
    base = local_origin(args.base_url)
    # Mandatory first HTTP request; not even credentials are read beforehand.
    Session(base, args.timeout).call('/api/commerce/runtime')
    roles = {role: credentials(role) for role in ('customer', 'staff')}
    workers = []
    for _ in range(args.workers):
        sessions = {role: Session(base, args.timeout) for role in roles}
        for role, (username, password) in roles.items():
            sessions[role].login(username, password, role)
        workers.append(sessions)
    before = dataset_snapshot(workers[0])
    snapshots = [{'phase': 'before', 'sampledAtUtc': datetime.now(timezone.utc).isoformat(),
                  **operations_snapshot(workers[0]['staff'].call('/api/admin/operations'))}]
    snapshot_lock = threading.Lock()
    for sessions in workers:
        for _ in range(args.warmup_rounds):
            for role, path in ENDPOINTS.values():
                sessions[role].call(path)
    start_gate = threading.Barrier(args.workers + 1)
    def measure(sessions):
        result = {name: [] for name in ENDPOINTS}
        start_gate.wait()
        for _ in range(args.rounds):
            for name, (role, path) in ENDPOINTS.items():
                start = time.perf_counter()
                kind = None
                try:
                    value = sessions[role].call(path)
                    if name == 'operations':
                        with snapshot_lock:
                            snapshots.append({'phase': 'measured', 'sampledAtUtc': datetime.now(timezone.utc).isoformat(),
                                              **operations_snapshot(value)})
                except RequestFailure as failure:
                    kind = failure.kind
                result[name].append(((time.perf_counter() - start) * 1000, kind))
                if args.think_ms:
                    time.sleep(args.think_ms / 1000)
        return result
    started_at = datetime.now(timezone.utc).isoformat()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(measure, sessions) for sessions in workers]
        started = time.perf_counter()
        start_gate.wait()
        measurements = [future.result() for future in futures]
        seconds = time.perf_counter() - started
    after = dataset_snapshot(workers[0])
    snapshots.append({'phase': 'after', 'sampledAtUtc': datetime.now(timezone.utc).isoformat(),
                      **operations_snapshot(workers[0]['staff'].call('/api/admin/operations'))})
    per_endpoint = {}
    all_samples = []
    for name in ENDPOINTS:
        samples = [sample for measured in measurements for sample in measured[name]]
        all_samples.extend(samples)
        per_endpoint[name] = {'method': 'GET', 'path': ENDPOINTS[name][1], **summarize(samples, seconds)}
    return {'schemaVersion': 2, 'startedAtUtc': started_at,
            'endedAtUtc': datetime.now(timezone.utc).isoformat(),
            'runtime': {'application': 'tv3-commerce', 'isolatedData': True, 'baseUrl': base},
            'configuration': {'workers': args.workers, 'sessionsPerWorker': 2,
                              'roundsPerWorker': args.rounds,
                              'pagination': 'Fixed per endpoint; page0/size10, page0/size100 filtered, page9/size100 deep',
                              'thinkTimePerRequestMs': args.think_ms, 'timeoutSeconds': args.timeout,
                              'scheduler': args.scheduler_note, 'databasePool': args.db_pool_note,
                              'schedule': 'closed loop; eight fixed GETs in order per worker; no retry',
                              'warmup': {'roundsPerWorker': args.warmup_rounds,
                                         'excludedRequests': args.workers * args.warmup_rounds * len(ENDPOINTS),
                                         'cacheState': 'uncontrolled; no flush or cold-cache claim'},
                              'measurementWindow': args.window_note},
            'machine': {'os': platform.platform(), 'python': platform.python_version(),
                        'logicalCpus': os.cpu_count(), 'processor': platform.processor()},
            'dataset': {'before': before, 'after': after, 'unchangedSummary': before == after,
                        'note': args.dataset_note, 'createdByHarness': False},
            'statistics': {'percentileMethod': 'nearest rank; all attempted requests including failures',
                           'latencyScope': 'client wall time including HTTP connection/read/JSON; excludes think time',
                           'throughputScope': 'count / shared measured wall seconds; includes think/scheduling',
                           'setupExcluded': 'runtime checks, logins, dataset snapshots and warmup',
                           'measuredSeconds': round(seconds, 6)},
            'endpoints': per_endpoint, 'overall': summarize(all_samples, seconds),
            'operations': {'source': 'real staff-protected GET /api/admin/operations; sampled by workload, not continuous maxima',
                           'snapshots': snapshots,
                           'peakObserved': {key: max((s['pool'].get(key, 0) for s in snapshots), default=0)
                                            for key in ('active', 'total', 'awaiting')}},
            'limitations': ['Bounded local dataset/workload; not production capacity evidence.',
                            'No business writes, provider calls, Redis test or SQL execution timing.',
                            'Session login is the only POST; separate cookie jars per worker and role.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--rounds', type=int, default=20)
    parser.add_argument('--warmup-rounds', type=int, default=2)
    parser.add_argument('--think-ms', type=float, default=25)
    parser.add_argument('--timeout', type=float, default=10)
    parser.add_argument('--scheduler-note', required=True, help='Observed scheduler/job state; use unknown when unverified.')
    parser.add_argument('--db-pool-note', required=True, help='Pool configuration/evidence; do not claim telemetry.')
    parser.add_argument('--dataset-note', required=True)
    parser.add_argument('--window-note', required=True, help='Record coordination excluding E2E/build activity.')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if (not 1 <= args.workers <= 8 or not 1 <= args.rounds <= 1000 or
        not 0 <= args.warmup_rounds <= 20 or not math.isfinite(args.think_ms) or
        not 0 <= args.think_ms <= 1000 or not math.isfinite(args.timeout) or not 0 < args.timeout <= 60):
        parser.error('Invalid bounded local benchmark configuration.')
    try:
        report = run(args)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        print(json.dumps({'output': str(args.output), 'overall': report['overall']}, ensure_ascii=False))
        return 1 if report['overall']['errors'] else 0
    except (ValueError, RequestFailure):
        # Categories are controlled; never print exception causes or response bodies.
        print('FAIL: runtime isolation, environment credentials, authentication or local request validation failed.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
