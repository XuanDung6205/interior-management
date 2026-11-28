"""Verify shared SQL against disposable MariaDB databases inside Docker.

Run with the Compose tools image, which supplies PyMySQL and DB_* environment.
Only newly created furniture_shop_test_tv2_sql_* schemas are used and cleaned up.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re

from build_tv2_catalog_sql import DEFAULT_DATABASE, FULL_FILENAME, DATA_FILENAME, OUTPUT_DIRECTORY, schema_sql
from tv3_schema import LATEST_TABLES


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", default="furniture_shop_test_tv2_sql_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"))
    parser.add_argument("--legacy-seed", type=Path, help="Optional original 62-SKU SQL, for an upgrade check.")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"furniture_shop_test_tv2_sql_[a-z0-9_]+", args.prefix):
        parser.error("Only furniture_shop_test_tv2_sql_* is allowed.")
    import pymysql
    from pymysql.constants import CLIENT
    connection = pymysql.connect(host=os.environ.get("DB_HOST", "mariadb"),
        port=int(os.environ.get("DB_PORT", "3306")), user=os.environ.get("DB_USERNAME", "root"),
        password=os.environ["DB_PASSWORD"], charset="utf8mb4", autocommit=True,
        client_flag=CLIENT.MULTI_STATEMENTS)
    created = []
    checks = []
    try:
        with connection.cursor() as cursor:
            def execute(sql, params=None):
                cursor.execute(sql, params)
                rows = cursor.fetchall()
                while cursor.nextset():
                    pass
                return rows

            def scalar(sql):
                return execute(sql)[0][0]

            def snapshot():
                return {row[0]: row[1:] for row in execute("""
                    SELECT v.sku,v.id,v.product_id,v.name,v.variant_key,v.color_name,v.color_hex,
                           v.width_mm,v.depth_mm,v.height_mm,v.price,v.compare_at_price,v.status,
                           b.on_hand_quantity,b.reserved_quantity
                    FROM product_variants v JOIN inventory_balances b ON b.variant_id=v.id
                    JOIN warehouses w ON w.id=b.warehouse_id AND w.code='MAIN'
                    WHERE v.sku LIKE 'NN-DEMO-%' ORDER BY v.sku
                """)}

            data = (OUTPUT_DIRECTORY / DATA_FILENAME).read_text(encoding="utf-8")
            full = (OUTPUT_DIRECTORY / FULL_FILENAME).read_text(encoding="utf-8")
            reference = None
            for suffix in ("fresh", "replica", "upgrade"):
                database = args.prefix + "_" + suffix
                if execute("SELECT SCHEMA_NAME FROM information_schema.schemata WHERE schema_name=%s", (database,)):
                    raise RuntimeError("Test database already exists; select another prefix.")
                # Track only our new schema so cleanup never touches existing databases.
                created.append(database)
                if suffix != "upgrade":
                    execute(full.replace(f"`{DEFAULT_DATABASE}`", f"`{database}`"))
                else:
                    execute(schema_sql(database))
                    execute(args.legacy_seed.read_text(encoding="utf-8") if args.legacy_seed else data)
                tables = {r[0] for r in execute("SHOW TABLES")}
                assert tables == LATEST_TABLES, "Schema inventory mismatch"
                assert scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='products' AND column_name='version'") == 1
                assert scalar("SELECT COUNT(*) FROM users") == 0, "Shared SQL must not contain user records"
                if suffix == "upgrade":
                    legacy_count = len(snapshot())
                    execute("UPDATE product_variants SET price=1111.11,status='INACTIVE' WHERE sku='NN-DEMO-01-01'")
                    execute("UPDATE product_variants SET price=2222.22 WHERE sku='NN-DEMO-01-01-BE-L'")
                    execute("UPDATE inventory_balances b JOIN product_variants v ON v.id=b.variant_id SET b.on_hand_quantity=7 WHERE v.sku='NN-DEMO-01-01'")
                    original = snapshot()
                    execute(data)
                    upgraded = snapshot()
                    assert all(upgraded.get(sku) == row for sku, row in original.items()), "Upgrade changed existing SKU data"
                    execute(data)
                    assert snapshot() == upgraded, "Rerun changed SKU IDs, prices, status or inventory"
                    checks.append(f"Upgrade from {legacy_count} to 150 SKUs and repeated seed preserve existing data")
                    before_version = scalar("SELECT SUM(version) FROM products")
                    try:
                        execute(data.replace("COMMIT;", "INSERT INTO tv2_deliberately_missing_table(id) VALUES(1);\nCOMMIT;"))
                        raise AssertionError("Expected a deliberate SQL error")
                    except pymysql.MySQLError:
                        connection.rollback()
                    assert scalar("SELECT SUM(version) FROM products") == before_version
                    assert snapshot() == upgraded
                    checks.append("A failed seed transaction rolls back catalog versions and data")
                current = snapshot()
                assert len(current) == 150
                assert scalar("SELECT COUNT(*) FROM products") == 50
                assert not execute("SELECT p.id FROM products p LEFT JOIN product_variants v ON v.product_id=p.id GROUP BY p.id HAVING COUNT(v.id)<>3")
                assert scalar("SELECT COUNT(*) FROM products p WHERE NOT EXISTS(SELECT 1 FROM product_images i WHERE i.product_id=p.id)") == 0
                assert scalar("SELECT COUNT(*) FROM product_variants WHERE price<=0 OR width_mm<=0 OR depth_mm<=0 OR height_mm<=0 OR color_name IS NULL") == 0
                assert scalar("SELECT COUNT(*) FROM products p WHERE NOT EXISTS(SELECT 1 FROM collection_products cp WHERE cp.product_id=p.id)") == 0
                assert scalar("SELECT COUNT(*) FROM commerce_vector_reindex_outbox WHERE state='PENDING'") == 50
                assert scalar("SELECT name FROM products WHERE slug='demo-sofa-01-01'") == "Sofa vải Mộc An"
                if suffix == "fresh":
                    reference = current
                elif suffix == "replica":
                    assert current == reference, "Fresh imports did not produce the same SKU IDs and data"
                    checks.append("Two fresh SQL imports produce identical product/SKU IDs and business data")
            checks.append("All imports: 49 tables, 50 products / 150 SKUs, three variants per product, UTF-8 and linked images/collections/stock")
            report = {"verifiedAtUtc": datetime.now(timezone.utc).isoformat(), "engine": scalar("SELECT VERSION()"),
                "schema": "V001-V015", "products": 50, "skus": 150, "checks": checks,
                "scope": "Disposable Docker MariaDB only; no user records or secrets in shared SQL."}
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False, indent=2))
    finally:
        connection.rollback()
        with connection.cursor() as cursor:
            for database in reversed(created):
                cursor.execute(f"DROP DATABASE IF EXISTS `{database}`")
        connection.close()


if __name__ == "__main__":
    main()
