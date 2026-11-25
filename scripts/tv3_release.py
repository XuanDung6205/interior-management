"""Build/run a same-origin React + Spring Boot demo without Vite or Docker.

Build requires Node, npm and JDK17+; run requires only JDK17+ and an existing
isolated MariaDB schema. Credentials are inherited from the process environment.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RELEASE_DIR = ROOT / 'target' / 'releases'
JAR_NAME = 'nep-nha-demo.jar'
START_CLASS = 'org.example.eshop.commerce.CommerceApplication'


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def java_command():
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidate = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if not candidate.is_file():
            raise ValueError('JAVA_HOME does not contain a Java executable.')
        return str(candidate)
    executable = shutil.which('java')
    if not executable:
        raise ValueError('JDK17+ is required; set JAVA_HOME or add java to PATH.')
    return executable


def inspect_jar(path):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
        if f'Start-Class: {START_CLASS}' not in manifest:
            raise ValueError('The archive does not start the commerce application.')
        index_name = 'BOOT-INF/classes/static/index.html'
        index = archive.read(index_name).decode('utf-8')
        assets = re.findall(r'(?:src|href)=["\'](/assets/[^"\']+)["\']', index)
        if not any(asset.endswith('.js') for asset in assets):
            raise ValueError('The archive has no bundled React entry script.')
        if any('BOOT-INF/classes/static' + asset not in names for asset in assets):
            raise ValueError('The archive is missing a referenced frontend asset.')
        if any(name.startswith('BOOT-INF/classes/templates/') for name in names):
            raise ValueError('A commerce release must not contain legacy templates.')
        if any(Path(name).name.startswith('.env') for name in names):
            raise ValueError('An environment file was included in the archive.')
        return len(assets)


def run_step(command, cwd):
    result = subprocess.run(command, cwd=cwd, check=False)
    if result.returncode:
        raise RuntimeError('Release build stopped because a build step failed.')


def build(offline=False):
    java_command()
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
    if not npm:
        raise ValueError('Node/npm is required to build the React application.')
    run_step([npm, 'ci', '--no-audit', '--no-fund'] + (['--offline'] if offline else []), ROOT / 'frontend')
    run_step([npm, 'run', 'build'], ROOT / 'frontend')
    wrapper = ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    command = ([str(wrapper)] if os.name == 'nt' else ['sh', str(wrapper)])
    command += (['-o'] if offline else []) + ['--batch-mode', '--no-transfer-progress', '-Pcommerce-release', '-DskipTests', 'clean', 'package']
    run_step(command, ROOT)
    candidates = list((ROOT / 'target' / 'commerce-release-build').glob('*.jar'))
    if len(candidates) != 1:
        raise ValueError('Expected exactly one executable commerce JAR.')
    source = candidates[0]
    assets = inspect_jar(source)
    RELEASE_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.release-', dir=RELEASE_DIR) as temporary:
        staging = Path(temporary)
        jar = staging / JAR_NAME
        shutil.copyfile(source, jar)
        checksum = digest(jar)
        (staging / (JAR_NAME + '.sha256')).write_text(checksum + '  ' + JAR_NAME + '\n', encoding='ascii')
        metadata = {'formatVersion': 1, 'artifact': JAR_NAME, 'sha256': checksum,
                    'builtAtUtc': datetime.now(timezone.utc).isoformat(), 'javaMinimum': 17,
                    'mainClass': START_CLASS, 'schemaMigrations': 'V001-V010',
                    'frontendAssets': assets, 'testsExecutedByBuild': False}
        (staging / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
        # Metadata is published last; run validates the pair before executing it.
        for name in [JAR_NAME, JAR_NAME + '.sha256', 'release.json']:
            os.replace(staging / name, RELEASE_DIR / name)
    print('Built ' + str(RELEASE_DIR / JAR_NAME))
    print('Tests are a separate CI/check step; the packaging command does not run them.')


def prepare_run(jar, database, port):
    if not re.fullmatch(r'furniture_shop_(?:tv3_dev|test_[a-z0-9_]+)', database):
        raise ValueError('Demo run only accepts furniture_shop_tv3_dev or furniture_shop_test_* databases.')
    if not 1 <= port <= 65535:
        raise ValueError('HTTP port must be between1 and65535.')
    if not os.environ.get('DB_PASSWORD'):
        raise ValueError('Supply DB_PASSWORD in the process environment.')
    host = os.environ.get('DB_HOST', '127.0.0.1')
    if host not in {'127.0.0.1', 'localhost', '::1'}:
        raise ValueError('The native demo runner only connects to local MariaDB.')
    db_port = os.environ.get('DB_PORT', '3306')
    if not db_port.isdigit() or not 1 <= int(db_port) <= 65535:
        raise ValueError('DB_PORT must be a valid TCP port.')
    jar = Path(jar).resolve(strict=True)
    metadata = json.loads((jar.parent / 'release.json').read_text(encoding='utf-8'))
    checksum = digest(jar)
    if metadata.get('formatVersion') != 1 or metadata.get('artifact') != jar.name or metadata.get('sha256') != checksum:
        raise ValueError('Release metadata/checksum does not match the JAR; build again.')
    checksum_file = (jar.parent / (jar.name + '.sha256')).read_text(encoding='ascii').split()
    if checksum_file != [checksum, jar.name]:
        raise ValueError('Release SHA256 file does not match the JAR.')
    inspect_jar(jar)
    jdbc_host = '[' + host + ']' if host == '::1' else host
    jdbc_url = f'jdbc:mariadb://{jdbc_host}:{db_port}/{database}'
    # A developer shell may retain Spring/JVM overrides from another project.
    # Remove them before Java starts and use only the packaged configuration.
    jvm_overrides = {'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH'}
    env = {name: value for name, value in os.environ.items()
           if not name.upper().startswith(('SPRING_', 'SERVER_')) and name.upper() not in jvm_overrides}
    env.update(DB_URL=jdbc_url, DB_USERNAME=os.environ.get('DB_USERNAME', 'root'),
               SERVER_PORT=str(port), SERVER_ADDRESS='127.0.0.1', SPRING_PROFILES_ACTIVE='commerce')
    command = [java_command(), '-Dspring.devtools.restart.enabled=false', '-jar', str(jar),
               '--spring.config.location=classpath:/', '--spring.profiles.active=commerce',
               '--spring.datasource.url=' + jdbc_url, '--spring.datasource.hikari.jdbc-url=' + jdbc_url,
               '--spring.jpa.hibernate.ddl-auto=validate', '--spring.sql.init.mode=never',
               '--server.address=127.0.0.1', '--server.port=' + str(port)]
    # No SQL initialization, migrations, seed or credential file is executed here.
    return command, env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    builder = commands.add_parser('build', help='Build React and package the executable commerce JAR.')
    builder.add_argument('--offline', action='store_true', help='Use only cached npm/Maven dependencies.')
    runner = commands.add_parser('run', help='Verify and run an already built JAR on a local demo/test DB.')
    runner.add_argument('--database', required=True)
    runner.add_argument('--port', type=int, default=8080)
    runner.add_argument('--jar', type=Path, default=RELEASE_DIR / JAR_NAME)
    args = parser.parse_args()
    try:
        if args.action == 'build':
            build(args.offline)
            return 0
        command, env = prepare_run(args.jar, args.database, args.port)
        print(f'Starting the same-origin demo at http://127.0.0.1:{args.port}; database {args.database}.', flush=True)
        if os.name != 'nt':
            # Preserve the PID so CI/service termination also stops the JVM.
            os.execvpe(command[0], command, env)
        return subprocess.call(command, cwd=ROOT, env=env)
    except (ValueError, OSError, RuntimeError, zipfile.BadZipFile, KeyError) as error:
        # Error messages never include command output, environment or DB passwords.
        print('Release error: ' + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    sys.exit(main())
