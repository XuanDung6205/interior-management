"""Behavior checks for the portable, same-origin demo release tool."""
import subprocess
import sys
import unittest
import importlib.util
import json
import os
import tempfile
from unittest.mock import patch
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('tv3_release', ROOT / 'scripts/tv3_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def example_release(directory, extra=None):
    jar = Path(directory) / release.JAR_NAME
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Start-Class: ' + release.START_CLASS + '\r\n')
        archive.writestr('BOOT-INF/classes/static/index.html', '<script src="/assets/app.js"></script><link href="/assets/app.css">')
        archive.writestr('BOOT-INF/classes/static/assets/app.js', 'const app = true;')
        archive.writestr('BOOT-INF/classes/static/assets/app.css', 'body{}')
        for name, value in (extra or {}).items():
            archive.writestr(name, value)
    checksum = release.digest(jar)
    (jar.parent / 'release.json').write_text(json.dumps({'formatVersion': 1, 'artifact': jar.name, 'sha256': checksum,
                                                       'schemaMigrations': 'V001-V014', 'schemaTables': 49}))
    (jar.parent / (jar.name + '.sha256')).write_text(checksum + '  ' + jar.name + '\n')
    return jar


class ReleaseCliTest(unittest.TestCase):
    def test_help_documents_build_and_run(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/tv3_release.py'), '--help'],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('build', result.stdout)
        self.assertIn('run', result.stdout)

    def test_production_existing_environment_url_cannot_be_selected_by_demo_runner(self):
        with patch.dict(os.environ, {'DB_PASSWORD': 'private', 'DB_URL': 'jdbc:mariadb://localhost/furniture_shop'}):
            for database in ['furniture_shop', 'mysql', 'furniture_shop_tv3_dev;DROP DATABASE mysql', '../demo']:
                with self.assertRaisesRegex(ValueError, 'Demo run only accepts'):
                    release.prepare_run('missing.jar', database, 8080)

    def test_verified_demo_overrides_inherited_database_and_binds_loopback(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = example_release(directory)
            with patch.dict(os.environ, {'DB_PASSWORD': 'private', 'DB_URL': 'jdbc:mariadb://localhost/furniture_shop',
                                         'DB_HOST': '127.0.0.1', 'DB_PORT': '3306'}, clear=True), patch.object(release, 'java_command', return_value='java'):
                command, env = release.prepare_run(jar, 'furniture_shop_test_restore_demo', 8082)
            self.assertIn(str(jar.resolve()), command)
            self.assertFalse(any('private' in part for part in command))
            self.assertEqual(env['DB_URL'], 'jdbc:mariadb://127.0.0.1:3306/furniture_shop_test_restore_demo')
            self.assertEqual(env['SERVER_ADDRESS'], '127.0.0.1')
            self.assertEqual(env['SERVER_PORT'], '8082')
            self.assertEqual(env['SPRING_PROFILES_ACTIVE'], 'commerce')

    def test_tampered_jar_or_metadata_cannot_run(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'DB_PASSWORD': 'private'}, clear=True):
            jar = example_release(directory)
            with jar.open('ab') as stream:
                stream.write(b'tampering')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                release.prepare_run(jar, 'furniture_shop_tv3_dev', 8080)

    def test_obsolete_schema_metadata_cannot_run_as_current_release(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'DB_PASSWORD': 'private'}, clear=True):
            jar = example_release(directory)
            metadata_path = jar.parent / 'release.json'
            metadata = json.loads(metadata_path.read_text())
            metadata.update(schemaMigrations='V001-V012', schemaTables=47)
            metadata_path.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'schema'):
                release.prepare_run(jar, 'furniture_shop_tv3_dev', 8080)

    def test_inherited_spring_java_and_external_configuration_cannot_override_guarded_database(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = example_release(directory)
            unsafe = {'DB_PASSWORD': 'private', 'SPRING_DATASOURCE_URL': 'jdbc:mariadb://localhost/furniture_shop',
                      'SPRING_DATASOURCE_HIKARI_JDBC_URL': 'jdbc:mariadb://localhost/mysql',
                      'SPRING_APPLICATION_JSON': '{"spring":{"jpa":{"hibernate":{"ddl-auto":"create-drop"}}}}',
                      'SPRING_CONFIG_LOCATION': 'file:external.properties', 'SPRING_JPA_HIBERNATE_DDL_AUTO': 'create-drop',
                      'SERVER_ADDRESS': '0.0.0.0', 'JAVA_TOOL_OPTIONS': '-Dspring.datasource.url=unsafe',
                      '_JAVA_OPTIONS': '-Dspring.jpa.hibernate.ddl-auto=create-drop', 'JDK_JAVA_OPTIONS': '-Dunsafe=true'}
            with patch.dict(os.environ, unsafe, clear=True), patch.object(release, 'java_command', return_value='java'):
                command, env = release.prepare_run(jar, 'furniture_shop_test_restore_demo', 8082)
            for name in unsafe.keys() - {'DB_PASSWORD', 'SERVER_ADDRESS'}:
                self.assertNotIn(name, env)
            self.assertIn('--spring.config.location=classpath:/', command)
            self.assertIn('--spring.datasource.url=jdbc:mariadb://127.0.0.1:3306/furniture_shop_test_restore_demo', command)
            self.assertIn('--spring.datasource.hikari.jdbc-url=jdbc:mariadb://127.0.0.1:3306/furniture_shop_test_restore_demo', command)
            self.assertIn('--spring.jpa.hibernate.ddl-auto=validate', command)
            self.assertIn('--spring.sql.init.mode=never', command)
            self.assertEqual(env['SERVER_ADDRESS'], '127.0.0.1')

    def test_missing_frontend_asset_rejected_even_when_archive_is_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'bad.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF', 'Start-Class: ' + release.START_CLASS + '\n')
                archive.writestr('BOOT-INF/classes/static/index.html', '<script src="/assets/missing.js"></script>')
            with self.assertRaisesRegex(ValueError, 'missing a referenced'):
                release.inspect_jar(jar)

    def test_legacy_templates_and_environment_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ['BOOT-INF/classes/templates/login.html', 'BOOT-INF/classes/.env']:
                jar = example_release(directory, {name: 'private-data'})
                with self.assertRaises(ValueError):
                    release.inspect_jar(jar)

    def test_build_failure_preserves_existing_release_instead_of_publishing_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = example_release(directory)
            before = previous.read_bytes()
            with patch.object(release, 'RELEASE_DIR', Path(directory)), patch.object(release, 'java_command', return_value='java'), \
                    patch.object(release.shutil, 'which', return_value='npm'), \
                    patch.object(release, 'run_step', side_effect=RuntimeError('build failed')):
                with self.assertRaises(RuntimeError):
                    release.build()
            self.assertEqual(previous.read_bytes(), before)

    def test_invalid_ports_hosts_and_missing_password_fail_before_java(self):
        for env, port in [({'DB_PASSWORD': 'private', 'DB_HOST': 'remote.example'}, 8080),
                          ({'DB_PASSWORD': 'private', 'DB_PORT': '3306/bad'}, 8080),
                          ({'DB_PASSWORD': 'private'}, 0), ({}, 8080)]:
            with patch.dict(os.environ, env, clear=True), patch.object(release, 'java_command') as java:
                with self.assertRaises(ValueError):
                    release.prepare_run('missing.jar', 'furniture_shop_tv3_dev', port)
                java.assert_not_called()


if __name__ == '__main__':
    unittest.main()
