"""Integration checks against a named local MariaDB schema; no third-party packages.

MYSQL_PWD must be supplied in the process environment. Credentials are never saved.
Usage: python docs/database/verify_schema.py --database furniture_shop
"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from tv3_schema import BASE_TABLES, HISTORICAL_TABLES, LATEST_TABLES, verify_vector_columns

TABLES = BASE_TABLES


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="furniture_shop")
    parser.add_argument("--client", default=r"C:\Program Files\MariaDB 12.2\bin\mariadb.exe")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default="3306")
    parser.add_argument("--case", action="append", help="Run only a named rejection case (repeatable)")
    inventories = parser.add_mutually_exclusive_group()
    inventories.add_argument("--operations", action="store_true", help="Verify latest runtime (49 exact commerce tables)")
    inventories.add_argument("--historical", action="store_true", help="Verify historical V008-V012 runtime (47 exact tables)")
    args = parser.parse_args()
    if not re.fullmatch(r"furniture_shop(?:_test_[a-z0-9_]+)?", args.database):
        parser.error("Only furniture_shop or its explicitly named test schemas are allowed")
    if "MYSQL_PWD" not in os.environ:
        parser.error("Supply MYSQL_PWD in the process environment")

    def sql(statement, expect_error=None):
        result = subprocess.run(
            [args.client, "--protocol=tcp", f"--host={args.host}", f"--port={args.port}",
             "--user=root", "--connect-timeout=5", "--default-character-set=utf8mb4",
             "--batch", "--skip-column-names", f"--database={args.database}"],
            input=statement, text=True, encoding="utf-8", capture_output=True,
        )
        if expect_error is not None:
            if result.returncode == 0 or not re.search(rf"ERROR {expect_error}\b", result.stderr):
                raise AssertionError(f"Expected error {expect_error}: {result.stderr or result.stdout}")
            return
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    actual = set(sql("SELECT TABLE_NAME FROM information_schema.TABLES "
                     "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE';").splitlines())
    expected=LATEST_TABLES if args.operations else HISTORICAL_TABLES if args.historical else TABLES
    if actual != expected:
        raise AssertionError(f"Missing: {sorted(expected-actual)}; unexpected: {sorted(actual-expected)}")
    if args.operations:
        verify_vector_columns(sql("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE FROM information_schema.COLUMNS "
                                  "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('vector_store','commerce_vector_reindex_outbox');"))
    assert sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
               "AND (ENGINE <> 'InnoDB' OR TABLE_COLLATION NOT LIKE 'utf8mb4%');") == "0"
    assert sql("SELECT GROUP_CONCAT(code ORDER BY code) FROM roles;") == "ADMIN,CUSTOMER,STAFF"
    assert sql("SELECT COUNT(*) FROM warehouses WHERE code='MAIN';") == "1"
    print(f"PASS: {len(expected)} expected tables, InnoDB/utf8mb4, initial roles and warehouse")

    # Each invocation is a new connection. Both valid and invalid fixtures are
    # transaction-scoped and are rolled back explicitly or on connection close.
    prefix = "START TRANSACTION;\n" + Path(__file__).with_name("verification-fixture.sql").read_text(encoding="utf-8")
    invalid = [
        ("negative SKU price", "UPDATE product_variants SET price=-1 WHERE id=@v1;", 4025),
        ("over-reserved inventory", "UPDATE inventory_balances SET reserved_quantity=11 WHERE variant_id=@v1;", 4025),
        ("duplicate SKU", "UPDATE product_variants SET sku='VERIFY-SKU-1' WHERE id=@v2;", 1062),
        ("duplicate cart line", "INSERT INTO cart_items(cart_id,variant_id,quantity) VALUES(@cart,@v1,1);", 1062),
        ("zero quantity", "UPDATE cart_items SET quantity=0 WHERE cart_id=@cart;", 4025),
        ("image for wrong product", "INSERT INTO product_images(product_id,variant_id,url) VALUES(@p2,@v1,'/wrong.jpg');", 1452),
        ("shipment item for wrong order", "INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(@shipment,@o1,@oi2,1);", 1452),
        ("return item for wrong order", "INSERT INTO return_items(return_request_id,order_id,order_item_id,quantity,reason) VALUES(@ret,@o1,@oi2,1,'invalid');", 1452),
        ("review for another buyer", "INSERT INTO reviews(user_id,order_item_id,order_id,product_id,rating,content) VALUES(@u2,@oi1,@o1,@p1,5,'invalid');", 1452),
        ("invalid rating", "INSERT INTO reviews(user_id,order_item_id,order_id,product_id,rating,content) VALUES(@u1,@oi1,@o1,@p1,6,'invalid');", 4025),
        ("duplicate gateway callback", "INSERT INTO payment_events(payment_id,provider,provider_event_id,event_type) VALUES(@pay,'TEST','VERIFY-EVENT','PAID');", 1062),
        ("cross-order refund", "INSERT INTO refunds(order_id,payment_id,return_request_id,idempotency_key,amount,reason) VALUES(@o2,@pay,NULL,'verify-refund-cross',10,'invalid');", 1452),
        ("invalid coupon percent", "INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('VERIFY-BAD','PERCENT',101,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY));", 4025),
        ("cart without owner", "INSERT INTO carts(status) VALUES('ACTIVE');", 4025),
        ("two default shipping addresses", "INSERT INTO user_addresses(user_id,recipient_name,phone,address_line1,is_default) VALUES(@u1,'Second','0900000000','Other',1);", 1062),
        ("delete purchased variant", "DELETE FROM product_variants WHERE id=@v1;", 1451),
        ("redemption for wrong coupon", "INSERT INTO coupon_redemptions(coupon_id,order_id,user_id,discount_amount) VALUES(@cb,@o1,@u1,10);", 1452),
        ("redemption on order without coupon", "INSERT INTO coupon_redemptions(coupon_id,order_id,user_id,discount_amount) VALUES(@ca,@o2,@u2,10);", 1452),
        ("refund without stable operation key", "INSERT INTO refunds(order_id,payment_id,amount,reason) VALUES(@o1,@pay,10,'invalid');", 1364),
        ("duplicate refund operation", "INSERT INTO refunds(order_id,payment_id,idempotency_key,amount,reason) VALUES(@o1,@pay,'verify-refund-stable',10,'first'),(@o1,@pay,'verify-refund-stable',10,'retry');", 1062),
    ]
    if args.case:
        unknown = set(args.case) - {case[0] for case in invalid}
        if unknown:
            parser.error(f"Unknown test cases: {sorted(unknown)}")
        invalid = [case for case in invalid if case[0] in args.case]
    for name, statement, code in invalid:
        sql(prefix + statement, expect_error=code)
        print(f"PASS: rejects {name}")

    result = sql(prefix + """
        INSERT INTO coupon_redemptions(coupon_id,order_id,user_id,discount_amount) VALUES(@ca,@o1,@u1,10);
        INSERT INTO refunds(order_id,payment_id,idempotency_key,amount,reason) VALUES(@o1,@pay,'verify-valid-refund',10,'Verification');
        UPDATE products SET name='Changed catalog name' WHERE id=@p1;
        UPDATE product_variants SET price=999 WHERE id=@v1;
        UPDATE user_addresses SET address_line1='Changed address' WHERE user_id=@u1;
        SELECT CONCAT(product_name,':',CAST(unit_price AS CHAR)) FROM order_items WHERE id=@oi1;
        SELECT address_line1 FROM order_addresses WHERE order_id=@o1;
        ROLLBACK;
    """)
    assert result.splitlines() == ["Original sofa:100.00", "Original delivery address"], result
    print("PASS: valid coupon/refund records and historical price/name/address snapshots")
    remaining = sql("SELECT (SELECT COUNT(*) FROM users WHERE username LIKE 'VERIFY-%') "
                    "+ (SELECT COUNT(*) FROM products WHERE slug LIKE 'verify-%');")
    assert remaining == "0", remaining
    print("PASS: all test fixtures rolled back")
    print(f"VERIFIED: {args.database}; {len(expected)} tables; {len(invalid)+2} behavior checks")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
