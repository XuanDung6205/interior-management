"""Recovery guards run without a server; real roundtrip is a separate operator check."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch
from schema_fixture import HISTORICAL_TABLES, LATEST_TABLES, fixture_sql

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/tv3_backup.py"
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location("tv3_backup", SCRIPT)
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class FakeDatabase:
    def __init__(self, sql=None):
        self.content = sql or fixture_sql()
        self.databases = {"furniture_shop_tv3_dev": self.content}
        self.created = []
        self.imported = []
        self.fail_dump = False
        self.fail_import = False

    def inspect(self, name):
        return set(LATEST_TABLES if "CREATE TABLE `vector_store`" in self.databases[name] else HISTORICAL_TABLES)

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
                   lambda m: m["tables"]["users"].update(rows=True),
                   lambda m: m["tables"]["users"].update(data_sha256="0" * 64)]
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
                   "INSERT INTO `users` (`id`) VALUES ((SELECT id FROM users));",
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
                    fixture_sql().replace("`users`", "`different_table`", 2),
                    fixture_sql().replace("CREATE TABLE `users`", "CREATE TABLE `source`.`users`", 1),
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
        ordinary = "\n".join(f"{name}\tBASE TABLE\tInnoDB" for name in sorted(HISTORICAL_TABLES))
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

    def test_latest_native_vector_snapshot_roundtrips_all_49_tables_and_binary_rows(self):
        self.db = FakeDatabase(fixture_sql(latest=True))
        self.snapshot()
        manifest = backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_vector")
        self.assertEqual(LATEST_TABLES, set(manifest["tables"]))
        self.assertEqual(1, manifest["tables"]["vector_store"]["rows"])
        self.assertIn("0x0000803F00000000", self.db.databases["furniture_shop_test_restore_vector"])
        self.assertEqual("commerce-49", manifest["schema_inventory"])

    def test_historical_v1_manifest_remains_restorable_without_schema_revision(self):
        self.snapshot()
        self.rewrite_manifest(lambda m: (m.update(version=1), m.pop("schema_inventory", None)))
        manifest = backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_historical")
        self.assertEqual(HISTORICAL_TABLES, set(manifest["tables"]))

    def test_manifest_version_cannot_relabel_latest_tables_as_historical(self):
        self.db = FakeDatabase(fixture_sql(latest=True))
        self.snapshot()
        self.rewrite_manifest(lambda m: (m.update(version=1), m.pop("schema_inventory", None)))
        with self.assertRaisesRegex(backup.BackupError, "inventory"):
            backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_false_history")
        self.assertEqual([], self.db.created)

    def test_same_count_arbitrary_dump_and_manifest_table_names_are_rejected_offline(self):
        self.snapshot()
        sql = fixture_sql().replace("`users`", "`unknown_table`")
        tables, _ = backup.analyze_dump(fixture_sql().encode())
        tables["unknown_table"] = tables.pop("users")
        (self.directory / "dump.sql").write_text(sql, encoding="utf-8")
        self.rewrite_manifest(lambda m: m.update(tables=tables, sha256=hashlib.sha256(sql.encode()).hexdigest()))
        with self.assertRaises(backup.BackupError):
            backup.restore_backup(self.db, self.directory, "furniture_shop_test_restore_unknown")
        self.assertEqual([], self.db.created)

    def test_same_count_unknown_dump_table_is_not_accepted_as_commerce_schema(self):
        with self.assertRaisesRegex(backup.BackupError, "inventory"):
            backup.analyze_dump(fixture_sql().replace("`users`", "`unknown_table`").encode())

    def test_inventory_inspection_rejects_same_count_substitution(self):
        ordinary = "\n".join(f"{name}\tBASE TABLE\tInnoDB" for name in sorted(HISTORICAL_TABLES))
        with patch.dict("os.environ", {"DB_PASSWORD": "unit-secret"}):
            db = backup.MariaDB("mariadb", "mariadb-dump")
        with patch.object(db, "query", side_effect=[ordinary.replace("users\t", "unknown_table\t"), "0"]):
            with self.assertRaises(backup.BackupError):
                db.inspect("furniture_shop_tv3_dev")

    def test_vector_literal_functions_and_external_index_options_remain_rejected(self):
        sql = fixture_sql(latest=True)
        for attack in [sql.replace("0x0000803F00000000", "VEC_FromText('[1,0]')"),
                       sql.replace("`DISTANCE`=cosine", "`DISTANCE`=cosine DATA DIRECTORY='/tmp/evil'")]:
            with self.subTest(attack=attack[-100:]), self.assertRaises(backup.BackupError):
                backup.analyze_dump(attack.encode())


if __name__ == "__main__":
    unittest.main()
