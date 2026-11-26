"""Kịch bản đo tải cục bộ cho các endpoint TV5 (chỉ dùng thư viện chuẩn Python).

Đo độ trễ p50/p95, số lỗi và thông lượng cho các API nội dung, gợi ý và dashboard.
Chỉ cho phép chạy vào địa chỉ loopback (localhost / 127.0.0.1).
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.cookiejar
import json
import math
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener

PUBLIC_ROUTES = {
    'banners': '/api/commerce/content/banners?placement=HOME_HERO',
    'articles': '/api/commerce/content/articles?page=0&size=9',
    'recommendations': '/api/commerce/recommendations?limit=6',
}

STAFF_ROUTES = {
    'dashboard': '/api/admin/dashboard/summary?days=30',
}


def validate_local_origin(base_url):
    parsed = urlsplit(base_url)
    if parsed.scheme not in {'http', 'https'} or parsed.hostname not in {'localhost', '127.0.0.1', '::1'}:
        raise ValueError('Chỉ cho phép chạy load test trên localhost / 127.0.0.1.')
    return base_url.rstrip('/')


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    index = min(len(sorted_values) - 1, max(0, math.ceil(len(sorted_values) * pct / 100.0) - 1))
    return round(sorted_values[index], 2)


def make_opener(base_url, username, password):
    jar = http.cookiejar.CookieJar()
    opener = build_opener(HTTPCookieProcessor(jar))
    if username and password:
        # Lấy CSRF token trước khi đăng nhập bằng cookie session
        csrf_req = Request(base_url + '/api/csrf', headers={'Accept': 'application/json'})
        with opener.open(csrf_req, timeout=10) as resp:
            csrf = json.loads(resp.read().decode('utf-8'))
        form = urlencode({'username': username, 'password': password}).encode('utf-8')
        login_req = Request(
            base_url + '/api/session/login',
            data=form,
            method='POST',
            headers={
                'Accept': 'application/json',
                'Content-Type': 'application/x-www-form-urlencoded',
                csrf['headerName']: csrf['token'],
            },
        )
        with opener.open(login_req, timeout=10) as resp:
            if resp.status != 200:
                raise RuntimeError('Đăng nhập thất bại trước khi đo tải.')
    return opener


def run_worker(base_url, path, requests_per_user, opener, timeout):
    latencies = []
    errors = 0
    for _ in range(requests_per_user):
        start = time.perf_counter()
        try:
            req = Request(base_url + path, headers={'Accept': 'application/json'})
            with opener.open(req, timeout=timeout) as resp:
                resp.read()
                if resp.status != 200:
                    errors += 1
                else:
                    latencies.append((time.perf_counter() - start) * 1000.0)
        except (HTTPError, URLError, TimeoutError):
            errors += 1
    return latencies, errors


def benchmark_route(base_url, path, vus, iterations, username, password, timeout):
    openers = [make_opener(base_url, username, password) for _ in range(vus)]
    all_latencies = []
    total_errors = 0
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=vus) as pool:
        futures = [pool.submit(run_worker, base_url, path, iterations, op, timeout) for op in openers]
        for fut in futures:
            lats, errs = fut.result()
            all_latencies.extend(lats)
            total_errors += errs
    elapsed = max(0.001, time.perf_counter() - started)
    all_latencies.sort()
    total_requests = vus * iterations
    return {
        'path': path,
        'totalRequests': total_requests,
        'errors': total_errors,
        'errorRatePercent': round((total_errors / total_requests) * 100.0, 2) if total_requests else 0.0,
        'rps': round(total_requests / elapsed, 2),
        'p50Ms': percentile(all_latencies, 50),
        'p95Ms': percentile(all_latencies, 95),
        'maxMs': round(all_latencies[-1], 2) if all_latencies else 0.0,
    }


def main():
    parser = argparse.ArgumentParser(description='Đo hiệu năng cục bộ các API TV5.')
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--vus', type=int, default=10, help='Số người dùng ảo đồng thời')
    parser.add_argument('--iterations', type=int, default=20, help='Số request mỗi người dùng ảo')
    parser.add_argument('--label', default='cold', help='Nhãn lượt đo (vd: cold hoặc warm)')
    parser.add_argument('--staff-user', default='', help='Tài khoản staff để đo thêm API dashboard')
    parser.add_argument('--staff-password', default='', help='Mật khẩu staff (chỉ truyền khi chạy cục bộ)')
    parser.add_argument('--timeout', type=float, default=10.0)
    args = parser.parse_args()

    base_url = validate_local_origin(args.base_url)
    results = []
    for name, path in PUBLIC_ROUTES.items():
        row = benchmark_route(base_url, path, args.vus, args.iterations, '', '', args.timeout)
        row['name'] = name
        results.append(row)

    if args.staff_user and args.staff_password:
        for name, path in STAFF_ROUTES.items():
            row = benchmark_route(base_url, path, args.vus, args.iterations, args.staff_user, args.staff_password, args.timeout)
            row['name'] = name
            results.append(row)

    report = {
        'label': args.label,
        'measuredAtUtc': datetime.now(timezone.utc).isoformat(),
        'vus': args.vus,
        'iterationsPerVu': args.iterations,
        'results': results,
    }
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
