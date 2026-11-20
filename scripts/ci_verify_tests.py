"""Fail CI when disposable-database integrations are missing or skipped.

Surefire XML can contain environment variables and response bodies. Only numeric
counts are written to the shareable summary; never copy the raw XML to artifacts.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def summarize(source, reports):
    required = set()
    for java in Path(source).rglob("*.java"):
        content = java.read_text(encoding="utf-8")
        if re.search(r'@EnabledIfEnvironmentVariable\s*\(\s*named\s*=\s*"(?:TV3|LEGACY)_TEST_DB_URL"', content):
            package = re.search(r"\bpackage\s+([\w.]+)\s*;", content)
            if package is None:
                raise ValueError("Database test source has no package")
            required.add(package.group(1) + "." + java.stem)
    if not required:
        raise ValueError("No database test sources discovered")
    counts = {"tests": 0, "skipped": 0, "failed": 0, "database_classes": len(required)}
    observed = {name: {"tests": 0, "skipped": 0} for name in required}
    for report in Path(reports).glob("TEST-*.xml"):
        tree = ET.parse(report)
        for case in tree.iter("testcase"):
            counts["tests"] += 1
            skipped = case.find("skipped") is not None
            counts["skipped"] += int(skipped)
            counts["failed"] += int(case.find("failure") is not None or case.find("error") is not None)
            name = case.get("classname", "").split("$")[0]
            if name in observed:
                observed[name]["tests"] += 1
                observed[name]["skipped"] += int(skipped)
    if counts["failed"]:
        raise ValueError("Java tests failed; inspect the test step")
    missing = sorted(name for name, value in observed.items() if not value["tests"])
    if missing:
        raise ValueError("Database integrations missing from reports: " + ", ".join(missing))
    skipped = sorted(name for name, value in observed.items() if value["skipped"])
    if skipped:
        raise ValueError("Database integrations skipped: " + ", ".join(skipped))
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("src/test/java"))
    parser.add_argument("--reports", type=Path, default=Path("target/surefire-reports"))
    parser.add_argument("--output", type=Path, default=Path("target/ci-results/java-summary.json"))
    args = parser.parse_args()
    try:
        summary = summarize(args.source, args.reports)
    except (ValueError, OSError, ET.ParseError) as error:
        # Parsing errors can include report content. Print only controlled gate
        # errors; paths and XML diagnostics remain private to this process.
        print(str(error) if type(error) is ValueError else "Could not read Java test reports", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {summary['database_classes']} database integration classes; {summary['tests']} Java tests, {summary['skipped']} optional skips.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
