import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "ci_verify_tests.py"


class VerifyTestsTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "The CI integration execution gate is missing")
        spec = importlib.util.spec_from_file_location("ci_verify_tests", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.reports = self.root / "reports"
        self.source.mkdir()
        self.reports.mkdir()
        (self.source / "DatabaseTest.java").write_text('''package example;
@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*test_.*")
class DatabaseTest {}''')

    def report(self, cases):
        (self.reports / "TEST-example.DatabaseTest.xml").write_text(
            '<testsuite><properties><property name="DB_PASSWORD" value="private-secret"/></properties>'
            + cases + '<system-out>private-cookie</system-out></testsuite>')

    def test_requires_database_integrations_to_appear_in_reports(self):
        self.report('<testcase classname="example.UnitTest" name="test"/>')
        with self.assertRaisesRegex(ValueError, "missing"):
            self.module.summarize(self.source, self.reports)

    def test_rejects_skipped_database_tests(self):
        self.report('<testcase classname="example.DatabaseTest" name="test"><skipped/></testcase>')
        with self.assertRaisesRegex(ValueError, "skipped"):
            self.module.summarize(self.source, self.reports)

    def test_rejects_any_failed_test(self):
        self.report('<testcase classname="example.DatabaseTest" name="test"><failure>private-data</failure></testcase>')
        with self.assertRaisesRegex(ValueError, "failed"):
            self.module.summarize(self.source, self.reports)

    def test_sanitized_summary_has_counts_without_private_report_data(self):
        self.report('<testcase classname="example.DatabaseTest" name="private-account"/>')
        summary = self.module.summarize(self.source, self.reports)
        self.assertEqual(summary["database_classes"], 1)
        self.assertEqual(summary["tests"], 1)
        self.assertNotIn("private", json.dumps(summary))
        self.assertNotIn("DB_PASSWORD", json.dumps(summary))

    def test_allows_opt_in_live_provider_skip(self):
        self.report('<testcase classname="example.DatabaseTest" name="test"/>'
                    '<testcase classname="example.GeminiTest" name="test"><skipped/></testcase>')
        self.assertEqual(self.module.summarize(self.source, self.reports)["skipped"], 1)

    def test_refuses_to_pass_if_no_database_test_sources_were_discovered(self):
        (self.source / "DatabaseTest.java").unlink()
        self.report('<testcase classname="example.UnitTest" name="test"/>')
        with self.assertRaisesRegex(ValueError, "No database"):
            self.module.summarize(self.source, self.reports)

    def test_discovers_plural_test_class_names(self):
        original = self.source / "DatabaseTest.java"
        (self.source / "DatabaseTests.java").write_text(
            original.read_text().replace("DatabaseTest", "DatabaseTests"))
        original.unlink()
        self.report('<testcase classname="example.DatabaseTests" name="test"/>')
        self.assertEqual(self.module.summarize(self.source, self.reports)["database_classes"], 1)

    def test_requires_legacy_database_context_to_execute_too(self):
        (self.source / "LegacyTests.java").write_text('''package example;
@EnabledIfEnvironmentVariable(named = "LEGACY_TEST_DB_URL", matches = ".*test_legacy_.*")
class LegacyTests {}''')
        self.report('<testcase classname="example.DatabaseTest" name="test"/>')
        with self.assertRaisesRegex(ValueError, "missing.*example.LegacyTests"):
            self.module.summarize(self.source, self.reports)

    def test_rejects_skipped_legacy_database_context(self):
        original = self.source / "DatabaseTest.java"
        original.write_text(original.read_text().replace("TV3_TEST_DB_URL", "LEGACY_TEST_DB_URL"))
        self.report('<testcase classname="example.DatabaseTest" name="test"><skipped/></testcase>')
        with self.assertRaisesRegex(ValueError, "skipped"):
            self.module.summarize(self.source, self.reports)


if __name__ == "__main__":
    unittest.main()
