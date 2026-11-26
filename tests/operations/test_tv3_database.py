"""Exercise migration order and fail-closed inventory through the CLI boundary."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
from schema_fixture import LATEST_TABLES, LATEST_COLUMNS

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("tv3_database", ROOT / "scripts/tv3_database.py")
database = importlib.util.module_from_spec(spec)
spec.loader.exec_module(database)


class DatabaseToolTests(unittest.TestCase):
    def run_cli(self, action, tables=LATEST_TABLES, columns=LATEST_COLUMNS, embedding="vector(768)", statements=None):
        statements = [] if statements is None else statements

        def client(argv, **kwargs):
            self.assertFalse(any("unit-secret" in part for part in argv))
            sql = kwargs["input"]
            statements.append(sql)
            output = "\n".join(f"{name}\tBASE TABLE\tInnoDB" for name in sorted(tables)) if "information_schema.TABLES" in sql else ""
            if "information_schema.COLUMNS" in sql:
                output = embedding if "SELECT COLUMN_TYPE" in sql else columns
            return subprocess.CompletedProcess(argv, 0, stdout=output, stderr="")

        with patch.dict(os.environ, {"DB_PASSWORD": "unit-secret"}, clear=True), \
                patch.object(sys, "argv", ["tv3_database.py", action, "--database", "furniture_shop_test_schema_unit"]), \
                patch.object(database.subprocess, "run", side_effect=client), contextlib.redirect_stdout(io.StringIO()):
            database.main()
        return statements

    def test_create_applies_vector_migrations_in_order_and_verifies_exact_inventory(self):
        statements = self.run_cli("create")
        content = "\n".join(statements)
        self.assertIn("CREATE TABLE IF NOT EXISTS vector_store", content)
        self.assertIn("catalog_fingerprint", content)
        self.assertLess(content.index("CREATE TABLE IF NOT EXISTS vector_store"), content.index("catalog_fingerprint"))
        self.assertTrue(any("information_schema.TABLES" in sql for sql in statements))

    def test_create_rejects_same_count_unknown_table_after_migration(self):
        with self.assertRaisesRegex(RuntimeError, "table"):
            self.run_cli("create", (LATEST_TABLES - {"users"}) | {"unknown_table"})

    def test_migrate_applies_additive_vector_files_without_bootstrap(self):
        statements = self.run_cli("migrate")
        content = "\n".join(statements)
        self.assertNotIn("CREATE DATABASE", content)
        self.assertIn("CREATE TABLE IF NOT EXISTS vector_store", content)
        self.assertIn("catalog_fingerprint", content)

    def test_verify_only_reads_inventory_without_applying_migrations(self):
        statements = self.run_cli("verify")
        self.assertEqual(2, len(statements))
        self.assertTrue(all(sql.startswith("SELECT ") for sql in statements))

    def test_exact_49_tables_without_v014_columns_are_rejected(self):
        for action in ["create", "migrate", "verify"]:
            with self.subTest(action=action), self.assertRaisesRegex(RuntimeError, "V014"):
                self.run_cli(action, columns="vector_store\tembedding\tvector(768)")

    def test_incompatible_vector_dimension_is_rejected_without_changing_generic_vectors(self):
        for action in ["create", "migrate", "verify"]:
            statements = []
            with self.subTest(action=action), self.assertRaisesRegex(RuntimeError, "VECTOR\\(768\\)"):
                self.run_cli(action, columns=LATEST_COLUMNS.replace("vector(768)", "vector(1536)"),
                             embedding="vector(1536)", statements=statements)
            if action == "migrate":
                self.assertTrue(all(sql.startswith("SELECT ") for sql in statements), "Mismatch must fail before additive DDL")


if __name__ == "__main__":
    unittest.main()
