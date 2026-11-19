"""Fixed read-only MariaDB EXPLAIN/dataset/index evidence for isolated local TV3 DBs.

Uses installed MariaDB CLI. DB_PASSWORD/DB_USERNAME are environment-only;
password goes only through child MYSQL_PWD, never argv or report. No arbitrary SQL.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess


def safe_database(value):
    if not re.fullmatch(r'furniture_shop_(?:tv3_dev|test_[a-z0-9_]+)', value):
        raise ValueError('Only furniture_shop_tv3_dev or furniture_shop_test_* is allowed.')
    return value


def local_host(value):
    if value not in {'localhost', '127.0.0.1', '::1'}:
        raise ValueError('Only loopback MariaDB is allowed.')
    return value


def queries(user_id):
    if not isinstance(user_id, int) or user_id < 1:
        raise ValueError('A positive user ID is required.')
    # Representative exact list projections from OrderQueryService and
    # AdminOrderQueryService; this helper does not execute these SELECTs.
    projection = """SELECT o.id,o.order_number,o.status,o.currency,o.grand_total,o.placed_at,
       (SELECT CAST(COALESCE(SUM(i.quantity),0) AS SIGNED) FROM order_items i WHERE i.order_id=o.id) AS itemCount,
       CASE WHEN o.grand_total=0 THEN 'NOT_REQUIRED'
            WHEN (SELECT COALESCE(SUM(p.amount),0) FROM payments p WHERE p.order_id=o.id AND p.status='SUCCEEDED')>=o.grand_total THEN 'SUCCEEDED'
            WHEN EXISTS(SELECT 1 FROM payments p WHERE p.order_id=o.id AND p.status='SUCCEEDED') THEN 'PARTIALLY_PAID'
            WHEN o.status='CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END AS paymentStatus,
       CASE WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status='IN_TRANSIT') THEN 'IN_TRANSIT'
            WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status='DELIVERED') THEN 'DELIVERED'
            WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status IN ('FAILED','RETURNED')) THEN 'EXCEPTION'
            WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status<>'CANCELLED') THEN 'PENDING'
            ELSE 'NOT_CREATED' END AS shipmentStatus"""
    customer = projection + f' FROM orders o WHERE o.user_id={user_id}'
    admin = projection + ",u.id,u.username,u.full_name,u.email,a.recipient_name,a.phone FROM orders o LEFT JOIN users u ON u.id=o.user_id LEFT JOIN order_addresses a ON a.order_id=o.id AND a.address_type='SHIPPING' WHERE 1=1"
    payment = """SELECT p.id,p.order_id,p.method,p.provider,p.status,p.amount,p.currency,
       p.provider_transaction_id,p.confirmation_note,p.verified_by,p.paid_at,p.expires_at,p.created_at,
       v.full_name,o.grand_total,o.order_number,o.status,u.id,u.username,u.full_name
       FROM payments p JOIN orders o ON o.id=p.order_id LEFT JOIN users u ON u.id=o.user_id
       LEFT JOIN users v ON v.id=p.verified_by WHERE 1=1"""
    return {
        'customerOrdersPage0': customer + ' ORDER BY o.placed_at DESC,o.id DESC LIMIT 10 OFFSET 0',
        'customerOrdersCount': f'SELECT COUNT(*) FROM orders o WHERE o.user_id={user_id}',
        'adminOrdersPage0': admin + ' ORDER BY o.placed_at DESC,o.id DESC LIMIT 10 OFFSET 0',
        'adminOrdersDeepPage9Size100': admin + ' ORDER BY o.placed_at DESC,o.id DESC LIMIT 100 OFFSET 900',
        'adminOrdersCount': "SELECT COUNT(*) FROM orders o LEFT JOIN users u ON u.id=o.user_id LEFT JOIN order_addresses a ON a.order_id=o.id AND a.address_type='SHIPPING' WHERE 1=1",
        'adminOrdersCancelledPage0': admin + " AND o.status='CANCELLED' ORDER BY o.placed_at DESC,o.id DESC LIMIT 10 OFFSET 0",
        'adminOrdersCancelledPage0Size100': admin + " AND o.status='CANCELLED' ORDER BY o.placed_at DESC,o.id DESC LIMIT 100 OFFSET 0",
        'adminPaymentsPage0': payment + ' ORDER BY p.created_at DESC,p.id DESC LIMIT 10 OFFSET 0',
        'adminPaymentsCount': 'SELECT COUNT(*) FROM payments p JOIN orders o ON o.id=p.order_id LEFT JOIN users u ON u.id=o.user_id WHERE 1=1',
        'adminPaymentsPendingPage0': payment + " AND p.status='PENDING' ORDER BY p.created_at DESC,p.id DESC LIMIT 10 OFFSET 0",
        'adminPaymentsPendingPage0Size100': payment + " AND p.status='PENDING' ORDER BY p.created_at DESC,p.id DESC LIMIT 100 OFFSET 0",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--customer-id', type=int, required=True)
    parser.add_argument('--client', default=r'C:\Program Files\MariaDB 12.2\bin\mariadb.exe')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        database = safe_database(args.database)
        host = local_host(os.environ.get('DB_HOST', '127.0.0.1'))
        port = int(os.environ.get('DB_PORT', '3306'))
        if not 1 <= port <= 65535 or not os.environ.get('DB_PASSWORD') or not os.environ.get('DB_USERNAME'):
            raise ValueError('Set valid DB_PASSWORD/DB_USERNAME and port in environment.')
        plans = queries(args.customer_id)
        env = dict(os.environ, MYSQL_PWD=os.environ['DB_PASSWORD'])
        command = [args.client, '--no-defaults', '--protocol=tcp', '--host=' + host, '--port=' + str(port),
                   '--user=' + os.environ['DB_USERNAME'], '--database=' + database,
                   '--default-character-set=utf8mb4', '--batch']
        def sql(statement):
            result = subprocess.run(command, input=statement + ';', text=True, encoding='utf-8',
                                    capture_output=True, env=env, timeout=30)
            if result.returncode:
                raise ValueError('MariaDB read-only evidence command failed.')
            lines = result.stdout.strip().splitlines()
            if not lines:
                return []
            columns = lines[0].split('\t')
            return [dict(zip(columns, line.split('\t'))) for line in lines[1:]]
        identity = sql('SELECT DATABASE() AS databaseName,VERSION() AS version')
        if not identity or identity[0].get('databaseName') != database:
            raise ValueError('Database identity mismatch.')
        counts = sql("SELECT 'users' AS tableName,COUNT(*) AS rowCount FROM users UNION ALL SELECT 'orders',COUNT(*) FROM orders UNION ALL SELECT 'order_items',COUNT(*) FROM order_items UNION ALL SELECT 'payments',COUNT(*) FROM payments UNION ALL SELECT 'refunds',COUNT(*) FROM refunds UNION ALL SELECT 'carts',COUNT(*) FROM carts UNION ALL SELECT 'cart_items',COUNT(*) FROM cart_items UNION ALL SELECT 'product_variants',COUNT(*) FROM product_variants UNION ALL SELECT 'inventory_reservations',COUNT(*) FROM inventory_reservations UNION ALL SELECT 'coupon_redemptions',COUNT(*) FROM coupon_redemptions UNION ALL SELECT 'commerce_notification_outbox',COUNT(*) FROM commerce_notification_outbox UNION ALL SELECT 'commerce_notifications',COUNT(*) FROM commerce_notifications")
        indexes = sql("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,CARDINALITY FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('orders','payments','refunds','order_items','shipments','order_addresses','carts','cart_items') ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX")
        report = {'schemaVersion': 1, 'capturedAtUtc': datetime.now(timezone.utc).isoformat(),
                  'database': database, 'server': identity[0]['version'], 'dataset': counts,
                  'existingIndexes': indexes,
                  'plans': {name: {'sql': statement, 'explain': sql('EXPLAIN ' + statement)}
                            for name, statement in plans.items()},
                  'limitations': ['EXPLAIN estimates only; SELECT list projections are not executed.',
                                  'No DDL/DML, ANALYZE TABLE, cache flush, optimizer hints or index changes.',
                                  'Pool utilization, query runtime, Redis and provider failures are not measured.']}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'output': str(args.output), 'plans': len(plans), 'tablesCounted': len(counts)}))
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired):
        print('FAIL: isolated database, local connection, credentials or fixed EXPLAIN validation failed.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
