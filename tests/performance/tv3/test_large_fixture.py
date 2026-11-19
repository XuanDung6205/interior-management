"""Isolation checks for the disposable load fixture; no database connection."""
import unittest
import large_fixture as fixture


class FixtureSafetyTests(unittest.TestCase):
    def test_exact_disposable_database_only(self):
        for name in ('furniture_shop', 'furniture_shop_tv3_dev', 'furniture_shop_test_other',
                     fixture.DATABASE + ';DROP DATABASE x'):
            with self.assertRaises(ValueError):
                fixture.safe_database(name)
        self.assertEqual(fixture.safe_database(fixture.DATABASE), fixture.DATABASE)

    def test_identity_and_empty_business_history_required(self):
        fixture.require_fresh({'databaseName': fixture.DATABASE, 'orders': '0',
                               'fixtures': '0', 'users': '2', 'tables': '47'})
        for changed in ({'databaseName': 'furniture_shop'}, {'orders': '1'},
                        {'fixtures': '1'}, {'tables': '45'}, {'users': '0'}):
            with self.assertRaises(ValueError):
                fixture.require_fresh({'databaseName': fixture.DATABASE, 'orders': '0',
                                       'fixtures': '0', 'users': '2', 'tables': '47', **changed})

    def test_bounded_fixed_sql_has_no_disable_constraints_or_external_payment(self):
        sql = fixture.fixture_sql()
        self.assertLess(len(sql), 3000000)
        self.assertNotIn('FOREIGN_KEY_CHECKS', sql)
        self.assertNotIn('DROP ', sql)
        self.assertNotIn('DELETE ', sql)
        self.assertNotIn('GATEWAY', sql)
        self.assertIn('START TRANSACTION', sql)
        self.assertIn('COMMIT', sql)
        self.assertIn('PERF-ORDER-1000', sql)
        self.assertNotIn('PERF-ORDER-1001', sql)


if __name__ == '__main__':
    unittest.main()
