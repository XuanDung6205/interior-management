"""Guard the explicit opt-in for seeding the primary database."""

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import seed_demo_catalog as catalog


class CatalogTargetTest(unittest.TestCase):
    def test_demo_remains_the_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(catalog.target_database([]), "furniture_shop_tv3_dev")

    def test_ci_database_from_environment_still_works(self):
        with patch.dict(os.environ, {"DB_NAME": "furniture_shop_test_ci_demo"}, clear=True):
            self.assertEqual(catalog.target_database([]), "furniture_shop_test_ci_demo")

    def test_primary_requires_explicit_name_and_flag(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                catalog.target_database(["--database", "furniture_shop"])
            self.assertEqual(catalog.target_database(["--database", "furniture_shop", "--allow-main-db"]),
                             "furniture_shop")
        with patch.dict(os.environ, {"DB_NAME": "furniture_shop"}, clear=True):
            with self.assertRaises(RuntimeError):
                catalog.target_database(["--allow-main-db"])

    def test_flag_does_not_apply_to_other_databases(self):
        with patch.dict(os.environ, {}, clear=True):
            for name in ("furniture_shop_tv3_dev", "mysql", "furniture_shop_extra"):
                with self.subTest(name=name), self.assertRaises(RuntimeError):
                    catalog.target_database(["--database", name, "--allow-main-db"])


if __name__ == "__main__":
    unittest.main()
