"""Bounded SQL load fixture on ONE newly-created database, never a business E2E.

Requires scripts/tv3_database.py create/seed and V008 first. No create, reset,
delete, external payment call or plaintext credential file is implemented here.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess

DATABASE = 'furniture_shop_test_perf_ops_20261119'


def safe_database(name):
    if name != DATABASE:
        raise ValueError('Only the explicitly assigned disposable performance database is allowed.')
    return name


def require_fresh(row):
    if (row.get('databaseName') != DATABASE or int(row.get('orders', -1)) != 0 or
        int(row.get('fixtures', -1)) != 0 or int(row.get('users', 0)) != 2 or
        int(row.get('tables', 0)) != 47):
        raise ValueError('Expected freshly created V001–V008 47-table database with only two seeded demo users.')


def fixture_sql():
    parts = ["START TRANSACTION;", "SET time_zone='+00:00';",
        "SET @owner=(SELECT id FROM users WHERE username='tv3.customer');",
        "SET @staff=(SELECT id FROM users WHERE username='tv3.admin');",
        "SET @warehouse=(SELECT id FROM warehouses WHERE code='MAIN');",
        "SET @category=(SELECT id FROM categories WHERE slug='tv3-phong-khach');",
        "INSERT INTO coupons(code,name,discount_type,discount_value,starts_at,ends_at) VALUES('PERF10','SQL load fixture only','PERCENT',10,'2025-01-01','2030-01-01');",
        "SET @coupon=LAST_INSERT_ID();"]
    # 250 furniture models x four color/size SKUs; no downloaded images.
    names = ('Sofa', 'Ban an', 'Ghe thu gian', 'Tu go', 'Ke sach')
    for n in range(1, 251):
        parts.append(f"INSERT INTO products(category_id,name,slug,warranty_months,assembly_required,status) VALUES(@category,'{names[n%5]} mau {n}','perf-product-{n}',24,{n%2},'ACTIVE');")
        parts.append('SET @product=LAST_INSERT_ID();')
        for color in range(4):
            sku = (n-1)*4 + color+1
            price = 500000 + sku*10000
            parts.append(f"INSERT INTO product_variants(product_id,sku,variant_key,name,price,color_name,width_mm,depth_mm,height_mm) VALUES(@product,'PERF-SKU-{sku:04}','color-{color}','Mau {color}',{price},'Mau {color}',1200,600,800);")
    parts.append("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT @warehouse,id,10000 FROM product_variants WHERE sku LIKE 'PERF-SKU-%';")
    for n in range(1, 1001):
        status = ('PENDING_CONFIRMATION', 'CONFIRMED', 'PROCESSING', 'CANCELLED')[n % 4]
        lines = [(1+(n*7+j*311) % 1000, 1+(n+j) % 3) for j in range(3)]
        subtotal = sum((500000+sku*10000)*qty for sku, qty in lines)
        discount = subtotal//10 if n % 3 == 0 else 0
        shipping = 0 if subtotal >= 10000000 else 50000
        total = subtotal-discount+shipping
        coupon = '@coupon' if discount else 'NULL'
        code = "'PERF10'" if discount else 'NULL'
        confirmed = "'2026-11-01 12:00:00'" if status in ('CONFIRMED', 'PROCESSING') else 'NULL'
        cancelled = "'2026-11-02 12:00:00'" if status == 'CANCELLED' else 'NULL'
        parts.append(f"INSERT INTO orders(order_number,user_id,idempotency_key,status,subtotal,discount_amount,shipping_fee,grand_total,coupon_id,coupon_code_snapshot,placed_at,confirmed_at,cancelled_at) VALUES('PERF-ORDER-{n}',@owner,'perf-load-{n}','{status}',{subtotal},{discount},{shipping},{total},{coupon},{code},DATE_ADD('2026-10-01',INTERVAL {n} MINUTE),{confirmed},{cancelled});")
        parts.append('SET @order=LAST_INSERT_ID();')
        for idx, (sku, qty) in enumerate(lines):
            price = 500000+sku*10000
            # Existing checkout stores coupon discount on the header, never twice.
            line_discount = 0
            parts.append(f"INSERT INTO order_items(order_id,product_id,variant_id,product_name,variant_name,sku,warranty_months,assembly_required,quantity,unit_price,discount_amount) SELECT @order,p.id,v.id,p.name,v.name,v.sku,24,p.assembly_required,{qty},{price},{line_discount} FROM product_variants v JOIN products p ON p.id=v.product_id WHERE v.sku='PERF-SKU-{sku:04}';")
            parts.append(f"INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity,released_quantity) SELECT id,@warehouse,variant_id,quantity,{qty if status=='CANCELLED' else 0} FROM order_items WHERE id=LAST_INSERT_ID();")
        payment_status = 'CANCELLED' if status == 'CANCELLED' else 'SUCCEEDED' if status == 'PROCESSING' else 'PENDING'
        bank = n % 5 == 0
        parts.append(f"INSERT INTO payments(order_id,method,provider,idempotency_key,amount,status,paid_at,verified_by,created_at) VALUES(@order,'{'BANK_TRANSFER' if bank else 'COD'}','{'MANUAL_BANK' if bank else 'COD'}','perf-payment-{n}',{total},'{payment_status}',{'NULL' if payment_status!='SUCCEEDED' else confirmed},{'@staff' if payment_status=='SUCCEEDED' else 'NULL'},DATE_ADD('2026-10-01',INTERVAL {n} MINUTE));")
        parts.append("INSERT INTO order_addresses(order_id,recipient_name,phone,address_line1,province_name) VALUES(@order,'Nguoi nhan fixture','0000000000','Dia chi SQL test fixture','Ha Noi');")
        parts.append(f"INSERT INTO order_status_history(order_id,to_status,note) VALUES(@order,'{status}','SQL performance fixture; not business E2E');")
        if discount:
            parts.append(f"INSERT INTO coupon_redemptions(coupon_id,order_id,user_id,discount_amount,status) VALUES(@coupon,@order,@owner,{discount},'{'RELEASED' if status=='CANCELLED' else 'RESERVED' if status=='PENDING_CONFIRMATION' else 'APPLIED'}');")
    parts.extend([
        "UPDATE inventory_balances b JOIN (SELECT r.variant_id,SUM(r.remaining_quantity) AS held FROM inventory_reservations r GROUP BY r.variant_id) x ON x.variant_id=b.variant_id SET b.reserved_quantity=x.held WHERE b.warehouse_id=@warehouse;",
        "INSERT INTO carts(user_id) VALUES(@owner);", 'SET @cart=LAST_INSERT_ID();',
        "INSERT INTO cart_items(cart_id,variant_id,quantity) SELECT @cart,id,2 FROM product_variants WHERE sku IN ('PERF-SKU-0001','PERF-SKU-0312','PERF-SKU-0623');",
        'COMMIT;'])
    return '\n'.join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--client', default=r'C:\Program Files\MariaDB 12.2\bin\mariadb.exe')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        database = safe_database(args.database)
        host = os.environ.get('DB_HOST', '127.0.0.1')
        port = int(os.environ.get('DB_PORT', '3306'))
        if host not in ('localhost', '127.0.0.1', '::1') or not 1 <= port <= 65535:
            raise ValueError('Loopback MariaDB required.')
        if not os.environ.get('DB_USERNAME') or not os.environ.get('DB_PASSWORD'):
            raise ValueError('Environment DB_USERNAME/DB_PASSWORD required.')
        env = dict(os.environ, MYSQL_PWD=os.environ['DB_PASSWORD'])
        command = [args.client, '--no-defaults', '--protocol=tcp', '--host='+host,
                   '--port='+str(port), '--user='+os.environ['DB_USERNAME'],
                   '--database='+database, '--default-character-set=utf8mb4', '--batch']
        def sql(statement):
            result = subprocess.run(command, input=statement, text=True, encoding='utf-8',
                                    capture_output=True, env=env, timeout=120)
            if result.returncode:
                raise ValueError('Fixture SQL failed; database must be inspected, never blindly retried.')
            lines = result.stdout.strip().splitlines()
            return [dict(zip(lines[0].split('\t'), row.split('\t'))) for row in lines[1:]] if lines else []
        identity = sql("SELECT DATABASE() AS databaseName,(SELECT COUNT(*) FROM orders) AS orders,(SELECT COUNT(*) FROM products WHERE slug LIKE 'perf-product-%') AS fixtures,(SELECT COUNT(*) FROM users WHERE username IN ('tv3.customer','tv3.admin')) AS users,(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE') AS tables;")
        require_fresh(identity[0] if identity else {})
        sql(fixture_sql())
        counts = sql("SELECT 'orders' AS tableName,COUNT(*) AS rowCount FROM orders UNION ALL SELECT 'order_items',COUNT(*) FROM order_items UNION ALL SELECT 'product_variants',COUNT(*) FROM product_variants UNION ALL SELECT 'payments',COUNT(*) FROM payments UNION ALL SELECT 'coupon_redemptions',COUNT(*) FROM coupon_redemptions UNION ALL SELECT 'cart_items',COUNT(*) FROM cart_items;")
        mix = sql('SELECT o.status,p.method,p.status AS paymentStatus,COUNT(*) AS rowCount FROM orders o JOIN payments p ON p.order_id=o.id GROUP BY o.status,p.method,p.status;')
        report = {'database': database, 'capturedAtUtc': datetime.now(timezone.utc).isoformat(),
                  'dataset': counts, 'mix': mix, 'source': 'Explicit bounded SQL test data, not real checkout/fulfillment E2E.',
                  'size': {'addedSkus': 1000, 'orders': 1000, 'orderItems': 3000},
                  'guard': 'Exact database identity, empty order history, no perf prefix and 47 base tables required.',
                  'expiry': 'No deadlines backdated/created; no scheduler/provider/network calls.',
                  'cleanup': 'No destructive teardown implemented; entire explicitly disposable DB may be removed by its owner.'}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        print(json.dumps({'output': str(args.output), 'dataset': counts}))
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired):
        print('FAIL: disposable identity/freshness/environment guard or fixture SQL failed; no raw SQL/server errors emitted.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
