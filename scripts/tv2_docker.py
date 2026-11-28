"""Docker-only schema/seed helper. Credentials come from Compose environment.

Does not drop databases, modify stock on rerun, or seed a non-demo database.
"""
import argparse
import hashlib
import os
import re
from pathlib import Path
import sys
import bcrypt
import pymysql
from pymysql.constants import CLIENT
from tv3_schema import migration_paths, LATEST_TABLES, MIGRATION_RANGE
from seed_demo_catalog import sql_rows

ROOT=Path(__file__).resolve().parents[1]


def database_name(value):
    if not re.fullmatch(r'furniture_shop(?:_tv3_dev|_test_[a-z0-9_]+)?',value):
        raise ValueError('Unsupported database name.')
    return value


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['migrate','seed','prepare-tests'])
    parser.add_argument('--database',default=os.environ.get('DB_NAME','furniture_shop_tv3_dev'),type=database_name)
    args=parser.parse_args()
    if args.action=='prepare-tests': args.database='furniture_shop_test_tv2'
    connection=pymysql.connect(host=os.environ.get('DB_HOST','mariadb'),port=int(os.environ.get('DB_PORT','3306')),
        user=os.environ.get('DB_USERNAME','root'),password=os.environ['DB_PASSWORD'],charset='utf8mb4',
        autocommit=True,client_flag=CLIENT.MULTI_STATEMENTS)
    try:
        with connection.cursor() as cursor:
            def sql(statement,params=None):
                cursor.execute(statement,params)
                rows=cursor.fetchall()
                while cursor.nextset():
                    pass
                return rows
            sql("SET SESSION time_zone='+00:00'")
            if args.action in {'migrate','prepare-tests'}:
                paths=migration_paths()
                count=sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s',(args.database,))[0][0]
                if count==0:
                    # Existing empty schema is retained; bootstrap CREATE is rewritten deliberately.
                    sql(f'CREATE DATABASE IF NOT EXISTS `{args.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
                    bootstrap=paths[0].read_text(encoding='utf-8')
                    bootstrap=bootstrap.replace('CREATE DATABASE furniture_shop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', '')
                    bootstrap=bootstrap.replace('USE furniture_shop;',f'USE `{args.database}`;')
                    sql(bootstrap)
                else:
                    sql(f'USE `{args.database}`')
                    if not {'products','users','product_variants'}.issubset({r[0] for r in sql('SHOW TABLES')}):
                        raise ValueError('Existing schema is not the furniture application; no migration applied.')
                for path in paths[1:]:
                    sql(path.read_text(encoding='utf-8'))
                tables={r[0] for r in sql('SHOW TABLES')}
                if tables!=LATEST_TABLES: raise ValueError('Schema table inventory mismatch.')
                if not sql("SHOW COLUMNS FROM products LIKE 'version'"): raise ValueError('Catalog version column missing.')
                if args.action=='prepare-tests':
                    sql('CREATE DATABASE IF NOT EXISTS furniture_shop_test_legacy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
                print(f'Verified {args.database}: {MIGRATION_RANGE}, {len(tables)} tables.')
                return
            if args.database!='furniture_shop_tv3_dev' and not args.database.startswith('furniture_shop_test_'):
                raise ValueError('Seed only accepts isolated development/test databases.')
            password=os.environ.get('TV3_DEMO_PASSWORD','')
            if len(password)<12: raise ValueError('Set TV3_DEMO_PASSWORD with at least 12 characters in .env.')
            sql(f'USE `{args.database}`')
            hashed=bcrypt.hashpw(password.encode(),bcrypt.gensalt()).decode()
            connection.begin()
            for username,role,name in [('tv2.admin','ADMIN','Quản trị catalog'),('tv2.staff','STAFF','Nhân viên catalog'),('tv2.customer','CUSTOMER','Khách hàng demo')]:
                sql('INSERT INTO users(username,email,password_hash,full_name) VALUES(%s,%s,%s,%s) ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash)',
                    (username,username+'@example.invalid',hashed,name))
                sql('INSERT IGNORE INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u CROSS JOIN roles r WHERE u.username=%s AND r.code=%s',(username,role))
            connection.commit()
            sql(sql_rows())
            print('Seeded isolated catalog demo; tv2.admin / tv2.staff / tv2.customer use TV3_DEMO_PASSWORD from .env.')
    finally:
        connection.close()


if __name__=='__main__':
    try:
        main()
    except Exception as error:
        # Never expose statements, server diagnostics or credentials.
        print('FAIL: '+(str(error) if isinstance(error,(ValueError,KeyError)) else type(error).__name__+'; check database health/schema.'),file=sys.stderr)
        sys.exit(1)
