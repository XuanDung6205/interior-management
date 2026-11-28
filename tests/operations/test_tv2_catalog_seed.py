"""Guard stable seed identities and ensure inventory cannot be reset by demo seed."""
import importlib.util
from pathlib import Path
import sys
import unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import seed_demo_catalog as seed

class CatalogSeedTests(unittest.TestCase):
    def test_seed_uses_stable_skus_and_only_initializes_inventory(self):
        sql=seed.sql_rows()
        self.assertEqual(sql,seed.sql_rows())
        self.assertTrue(sql.startswith('START TRANSACTION;'))
        self.assertTrue(sql.endswith('COMMIT;'))
        self.assertNotIn('UPDATE inventory_balances',sql)
        self.assertNotIn('DELETE FROM product_variants',sql)
        self.assertNotIn('REPLACE INTO',sql)
        self.assertNotIn("status='ACTIVE';",sql)
        self.assertIn('INSERT IGNORE INTO inventory_balances',sql)
        self.assertIn('INSERT IGNORE INTO collection_products',sql)
        self.assertEqual(sum('INSERT INTO product_variants(' in line for line in sql.splitlines()),62)
    def test_sql_quotes_are_independent_of_backslash_mode(self):
        value="gỗ 'sồi' \\ thử"
        self.assertEqual(seed.quote(value),"CONVERT(X'"+value.encode().hex()+"' USING utf8mb4)")
