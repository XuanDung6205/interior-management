"""Run the packaged demo or SePay CLI with a private Sandbox configuration.

No token/secret appears in command arguments or output. Never calls a live API.
The CLI OAuth session is separate from API Access and requires `sepay login` once.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import urllib.error
import tv3_release

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {'SEPAY_ENABLED', 'SEPAY_BANK', 'SEPAY_ACCOUNT_NUMBER', 'SEPAY_ACCOUNT_HOLDER',
           'SEPAY_WEBHOOK_SECRET', 'SEPAY_API_TOKEN', 'SEPAY_WEBHOOK_ID',
           'SEPAY_AUTOMATION_ENABLED', 'SEPAY_PAYMENT_TTL_SECONDS',
           'SEPAY_RECONCILIATION_INTERVAL_SECONDS', 'SEPAY_BATCH_SIZE',
           'SEPAY_LATE_RECONCILIATION_HOURS', 'SEPAY_REFUNDS_ENABLED'}


def private_environment(path):
    values = {}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key not in ALLOWED or key in values or any(ord(c) < 32 for c in value):
            raise ValueError('Invalid private SePay configuration. Only documented SEPAY_* keys are allowed.')
        values[key] = value
    env = dict(os.environ)
    # Never let an inherited CLI --api-base or alternate environment redirect OAuth credentials.
    for name in list(env):
        if name.startswith('SEPAY_'):
            del env[name]
    env.update(values, SEPAY_NO_UPDATE_NOTIFIER='1')
    return env


def sepay_cli():
    installed = ROOT / 'target/sepay-work/cli/sepay.exe'
    command = str(installed) if installed.is_file() else shutil.which('sepay')
    if not command:
        raise ValueError('Install official SePay CLI first; see docs/TV3_SEPAY_SANDBOX.md.')
    return command


def api_check(env):
    token = env.get('SEPAY_API_TOKEN', '')
    if not token:
        raise ValueError('Missing private SEPAY_API_TOKEN.')
    request = urllib.request.Request('https://userapi-sandbox.sepay.vn/v2/bank-accounts',
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json',
                 'User-Agent': 'InteriorManagement-Sandbox/1.0'})
    # For this diagnostics-only GET do not follow redirects with the token.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=10) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError('Sandbox response exceeded the diagnostics size limit.')
            body = json.loads(raw)
    except (urllib.error.URLError, OSError, json.JSONDecodeError):
        raise ValueError('Cannot verify Sandbox API access; no secret or provider body is printed.') from None
    accounts = body.get('data')
    if body.get('status') != 'success' or not isinstance(accounts, list):
        raise ValueError('Unexpected Sandbox account response.')
    found = any(a.get('account_number') == env.get('SEPAY_ACCOUNT_NUMBER') for a in accounts)
    print(json.dumps({'sandboxApiConnected': True, 'configuredTestAccountFound': found}))
    if not found:
        raise ValueError('Configured test account is not in this Sandbox company.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['check', 'run', 'login', 'listen'])
    parser.add_argument('--config', type=Path, default=ROOT / '.env.sepay.local')
    parser.add_argument('--database', default='furniture_shop_tv3_dev')
    parser.add_argument('--port', type=int, default=8086)
    parser.add_argument('--windows-trust', action='store_true', help='Use Windows trusted root certificates for this Java process (Windows only; TLS remains verified).')
    args = parser.parse_args()
    try:
        if not 1 <= args.port <= 65535:
            raise ValueError('Invalid local application port.')
        env = private_environment(args.config)
        if args.action == 'check':
            api_check(env)
            return 0
        if args.action == 'run':
            # prepare_run keeps the existing packaged-config, local DB and checksum guards.
            command, child = tv3_release.prepare_run(tv3_release.RELEASE_DIR / tv3_release.JAR_NAME,
                                                    args.database, args.port)
            for key in ALLOWED:
                child.pop(key, None)
                if key in env:
                    child[key] = env[key]
            if args.windows_trust:
                if os.name != 'nt':
                    raise ValueError('--windows-trust is available only on Windows.')
                command[1:1] = ['-Djavax.net.ssl.trustStoreType=Windows-ROOT', '-Djavax.net.ssl.trustStore=NONE']
            print('Starting local SePay Sandbox demo on port ' + str(args.port), flush=True)
            return subprocess.call(command, env=child, cwd=ROOT)
        command = [sepay_cli()]
        if args.action == 'login':
            command += ['login', '--profile', 'interior-sandbox', '--scopes', 'read']
        else:
            webhook = env.get('SEPAY_WEBHOOK_ID', '')
            if not re.fullmatch(r'[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}', webhook):
                raise ValueError('Set the nonsecret SEPAY_WEBHOOK_ID from the Dashboard.')
            command += ['webhook', 'listen', '--env', 'sandbox', '--profile', 'interior-sandbox',
                        '--webhook', webhook, '--forward',
                        f'localhost:{args.port}/api/payments/sepay/webhook', '--no-color']
        return subprocess.call(command, env=env, cwd=ROOT)
    except (ValueError, OSError):
        # Config/HTTP exceptions can include sensitive strings; use only a controlled summary.
        print('SePay setup failed. Check the private config, CLI login, local DB and release; no secret values are printed.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
