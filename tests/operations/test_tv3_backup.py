"""Recovery guards run without a server; real roundtrip is a separate operator check."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/tv3_backup.py"
spec = importlib.util.spec_from_file_location("tv3_backup", SCRIPT)
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


def fixture_sql():
    return "\n".join(
        f"CREATE TABLE `table_{i}` (`id` int NOT NULL, `value` varchar(100), PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
        f"INSERT INTO `table_{i}` (`id`, `value`) VALUES (1,'hello; -- not SQL \\' quote');"
        for i in range(47)
    )


class FakeDatabase:
    def __init__(self, sql=None):
        self.content = sql or fixture_sql()
        self.databases = {"furniture_shop_tv3_dev": self.content}
        self.created = []
        self.imported = []
        self.fail_dump = False
        self.fail_import = False

    def inspect(self, name):
        return {f"table_{i}" for i in range(47)}

    def dump(self, name, destination):
        destination.write_bytes(self.databases[name].encode())
        if self.fail_dump:
            raise backup.BackupError("dump failed")

    def create(self, name):
        if name in self.databases:
            raise backup.BackupError("exists")
        self.databases[name] = ""
        self.created.append(name)

    def import_sql(self, name, sql):
        self.imported.append(name)
        if self.fail_import:
            raise backup.BackupError("import failed")
        self.databases[name] = sql


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "snapshot"
        self.db = FakeDatabase()

    def snapshot(self):
        backup.create_backup(self.db, "furniture_shop_tv3_dev", self.directory)
        return self.directory

    def rewrite_manifest(self, change):
        path = self.directory / "manifest.json"
        value = json.loads(path.read_text())
        change(value)
        path.write_text(json.dumps(value))

    def test_roundtrip_verifies_exact_47_tables_and_rows(self):
        self.snapshot()
        result = backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_unit")
        self.assertEqual(47, len(result["tables"]))
        self.assertEqual(47, sum(t["rows"] for t in result["tables"].values()))
        self.assertEqual(["furniture_shop_test_restore_unit"], self.db.imported)

    def test_source_and_restore_allowlists_fail_before_any_database_creation(self):
        for name in ["other", "furniture_shop_test_", "furniture_shop;DROP DATABASE x", "furniture_shop_test_" + "a" * 70]:
            with self.subTest(name=name), self.assertRaises(backup.BackupError):
                backup.create_backup(self.db, name, self.directory)
        self.snapshot()
        for name in ["furniture_shop", "furniture_shop_tv3_dev", "furniture_shop_test_other", "furniture_shop_test_restore_x;DROP"]:
            with self.subTest(name=name), self.assertRaises(backup.BackupError):
                backup.restore_backup(self.db, self.directory, name)
        self.assertEqual([], self.db.created)

    def test_existing_even_empty_database_is_never_imported_or_removed(self):
        self.snapshot()
        self.db.databases["furniture_shop_test_restore_existing"] = ""
        with self.assertRaises(backup.BackupError):
            backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_existing")
        self.assertIn("furniture_shop_test_restore_existing", self.db.databases)
        self.assertEqual([], self.db.imported)

    def test_dump_failure_does_not_publish_a_manifest_or_reuse_directory(self):
        self.db.fail_dump = True
        with self.assertRaises(backup.BackupError):
            self.snapshot()
        self.assertFalse((self.directory / "manifest.json").exists())
        self.db.fail_dump = False
        with self.assertRaises(backup.BackupError):
            self.snapshot()

    def test_checksum_tampering_fails_before_create(self):
        self.snapshot()
        with (self.directory / "dump.sql").open("a") as file:
            file.write("DROP DATABASE furniture_shop;")
        with self.assertRaises(backup.BackupError):
            backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_unit")
        self.assertEqual([], self.db.created)

    def test_manifest_path_fields_types_names_and_digest_cannot_be_tampered(self):
        self.snapshot()
        original = (self.directory / "manifest.json").read_bytes()
        changes = [lambda m: m.update(dump_file="../outside.sql"),
                   lambda m: m.update(version=True), lambda m: m.update(content_type="text/plain"),
                   lambda m: m.update(source="other"), lambda m: m.update(extra="field"),
                   lambda m: m["tables"].update({"x`;DROP DATABASE x;--": next(iter(m["tables"].values()))}),
                   lambda m: m["tables"]["table_0"].update(rows=True),
                   lambda m: m["tables"]["table_0"].update(data_sha256="0" * 64)]
        for change in changes:
            (self.directory / "manifest.json").write_bytes(original)
            self.rewrite_manifest(change)
            with self.subTest(change=change), self.assertRaises(backup.BackupError):
                backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_unit")
        self.assertEqual([], self.db.created)

    def test_recomputed_checksum_does_not_allow_database_directives_or_expressions(self):
        attacks = ["USE\nfurniture_shop;", "CREATE /*evil*/ DATABASE furniture_shop;",
                   "/*!50000 USE furniture_shop */;", "/*M!100100 USE furniture_shop */;",
                   "INSERT INTO `furniture_shop`.`users` (`id`) VALUES (1);",
                   "INSERT INTO `table_0` (`id`) VALUES ((SELECT id FROM users));",
                   "\\! echo exploit\n", "CREATE TABLE `evil` (`x` int) ENGINE=CONNECT;"]
        self.snapshot()
        original = (self.directory / "dump.sql").read_text()
        for attack in attacks:
            text = original + "\n" + attack
            (self.directory / "dump.sql").write_text(text)
            self.rewrite_manifest(lambda m: m.update(sha256=hashlib.sha256(text.encode()).hexdigest()))
            with self.subTest(attack=attack), self.assertRaises(backup.BackupError):
                backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_unit")
        self.assertEqual([], self.db.created)

    def test_partial_import_is_retained_for_diagnosis_without_automatic_drop(self):
        self.snapshot()
        self.db.fail_import = True
        with self.assertRaises(backup.BackupError):
            backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_partial")
        self.assertIn("furniture_shop_test_restore_partial", self.db.databases)

    def test_imported_content_mismatch_is_reported(self):
        self.snapshot()
        real_import = self.db.import_sql
        self.db.import_sql = lambda name, sql: real_import(name, sql.replace("hello;", "changed;"))
        with self.assertRaises(backup.BackupError):
            backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_corrupt")

    def test_native_compact_housekeeping_comments_and_sql_like_string_values(self):
        sql = ("/*M!999999\\- enable the sandbox mode */\n"
               "/*!40101 SET @saved_cs_client     = @@character_set_client */;\n"
               "/*!40101 SET character_set_client = utf8mb4 */;\n" + fixture_sql() +
               "\n/*!40101 SET character_set_client = @saved_cs_client */;\n")
        sql = sql.replace("hello; -- not SQL", "USE furniture_shop; /*!50000 DROP DATABASE x */")
        self.db.content = sql
        self.db.databases["furniture_shop_tv3_dev"] = sql
        self.snapshot()
        result = backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_native")
        self.assertEqual(47, sum(t["rows"] for t in result["tables"].values()))

    def test_backup_rejects_unsupported_or_missing_snapshot_tables(self):
        for sql in [fixture_sql().replace("ENGINE=InnoDB", "ENGINE=MyISAM", 1),
                    fixture_sql().replace("`table_0`", "`different_table`", 2),
                    fixture_sql().replace("CREATE TABLE `table_0`", "CREATE TABLE `source`.`table_0`", 1),
                    fixture_sql() + "\n/*!40101 SET character_set_client = utf8mb4; USE furniture_shop */;"]:
            with self.subTest(sql=sql[:80]), tempfile.TemporaryDirectory() as temp:
                db = FakeDatabase(sql)
                directory = Path(temp) / "snapshot"
                with self.assertRaises(backup.BackupError):
                    backup.create_backup(db, "furniture_shop_tv3_dev", directory)
                self.assertFalse((directory / "manifest.json").exists())

    def test_sources_with_views_noninnodb_or_triggers_are_refused(self):
        with patch.dict("os.environ", {"DB_PASSWORD": "unit-secret"}):
            db = backup.MariaDB("mariadb", "mariadb-dump")
        ordinary = "\n".join(f"table_{i}\tBASE TABLE\tInnoDB" for i in range(47))
        for result, unsupported in [(ordinary.replace("BASE TABLE", "VIEW", 1), "0"),
                                    (ordinary.replace("InnoDB", "MyISAM", 1), "0"),
                                    (ordinary, "1")]:
            with self.subTest(unsupported=unsupported), patch.object(db, "query", side_effect=[result, unsupported]):
                with self.assertRaises(backup.BackupError):
                    db.inspect("furniture_shop_tv3_dev")

    def test_password_is_only_in_child_environment_and_stderr_is_not_disclosed(self):
        with patch.dict("os.environ", {"DB_PASSWORD": "unit-secret", "DB_USERNAME": "tester"}), patch.object(backup.subprocess, "run") as run:
            run.return_value.returncode = 1
            run.return_value.stderr = b"ERROR 1064 (42000) at line 9: password unit-secret and customer data"
            db = backup.MariaDB("mariadb", "mariadb-dump")
            with self.assertRaises(backup.BackupError) as error:
                db.query("SELECT 1")
            self.assertNotIn("unit-secret", str(error.exception))
            self.assertIn("1064", str(error.exception))
            self.assertIn("42000", str(error.exception))
            self.assertIn("line 9", str(error.exception))
            argv = run.call_args.args[0]
            self.assertFalse(any("unit-secret" in item or "--password" in item for item in argv))
            self.assertIn("--no-defaults", argv)
            self.assertEqual("unit-secret", run.call_args.kwargs["env"]["MYSQL_PWD"])

    def test_dump_disables_mariadb_12_autocommit_directives(self):
        # MariaDB 12 enables --no-autocommit by default, even with --compact.
        # Omitting its explicit inverse creates SET @... and COMMIT statements
        # outside the intentionally narrow recovery dialect.
        with patch.dict("os.environ", {"DB_PASSWORD": "unit-secret"}), patch.object(backup.subprocess, "run") as run:
            run.return_value.returncode = 0
            db = backup.MariaDB("mariadb", "mariadb-dump")
            db.dump("furniture_shop_tv3_dev", Path(self.temp.name) / "output.sql")
            self.assertIn("--skip-no-autocommit", run.call_args.args[0])

    def test_table_check_constraints_preserve_compound_operators_in_import_sql(self):
        sql = fixture_sql().replace("PRIMARY KEY (`id`)",
            "PRIMARY KEY (`id`), CHECK (`id` >= 0 AND `id` <= 100 AND `id` <> 50 AND `id` != 51)", 1)
        _, restored = backup.analyze_dump(sql.encode())
        self.assertIn("`id` >= 0", restored)
        self.assertIn("`id` <= 100", restored)
        self.assertIn("`id` <> 50", restored)
        self.assertIn("`id` != 51", restored)


if __name__ == "__main__":
    unittest.main()
