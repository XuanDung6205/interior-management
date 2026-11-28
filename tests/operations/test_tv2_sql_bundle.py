"""The handoff SQL must stay portable, complete and in sync with its sources."""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import build_tv2_catalog_sql as bundle
from tv3_schema import MIGRATIONS


class CatalogSqlBundleTests(unittest.TestCase):
    def test_committed_artifacts_match_registered_sources(self):
        for name, expected in bundle.artifacts().items():
            self.assertEqual((bundle.OUTPUT_DIRECTORY / name).read_text(encoding="utf-8"), expected)

    def test_full_bundle_is_self_contained_and_contains_all_migrations_in_order(self):
        sql = bundle.full_sql("furniture_shop_test_tv2_bundle")
        self.assertIn("CREATE DATABASE `furniture_shop_test_tv2_bundle`", sql)
        self.assertIn("USE `furniture_shop_test_tv2_bundle`;", sql)
        positions = [sql.index("-- BEGIN " + name) for name in MIGRATIONS]
        self.assertEqual(positions, sorted(positions))
        self.assertLess(positions[-1], sql.index("START TRANSACTION;"))
        self.assertNotIn("SOURCE ", sql)
        self.assertNotIn("DROP DATABASE", sql)
        self.assertNotIn("TRUNCATE TABLE", sql)
        self.assertNotIn("INSERT INTO users", sql)
        self.assertNotIn("FOREIGN_KEY_CHECKS=0", sql)

    def test_data_file_keeps_the_selected_database_and_announces_its_effects(self):
        sql = bundle.data_sql()
        self.assertNotIn("CREATE DATABASE", sql)
        self.assertNotIn("USE furniture_shop", sql)
        self.assertIn("UPDATE products SET version=version+1", sql)
        self.assertIn("INSERT INTO commerce_vector_reindex_outbox", sql)
        self.assertEqual(sql.count("START TRANSACTION;"), 1)
        self.assertEqual(sql.count("COMMIT;"), 1)

    def test_generated_database_names_cannot_inject_sql(self):
        for name in ("mysql", "other_app", "furniture_shop`; DROP DATABASE mysql; --", "furniture_shop_test_X"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                bundle.full_sql(name)
