"""Create/seed only disposable TV3 databases, never the real furniture_shop.

Requires DB_PASSWORD (and TV3_DEMO_PASSWORD for seed). Uses installed CLI/JDK;
passwords travel in child process environments, never command arguments/files.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["create","seed","migrate"])
    parser.add_argument("--database",required=True)
    parser.add_argument("--client",default=r"C:\Program Files\MariaDB 12.2\bin\mariadb.exe")
    args=parser.parse_args()
    if not re.fullmatch(r"furniture_shop_(?:tv3_dev|test_[a-z0-9_]+)",args.database):
        parser.error("Only furniture_shop_tv3_dev or furniture_shop_test_* is allowed; production is refused.")
    if not os.environ.get("DB_PASSWORD"):
        parser.error("Supply DB_PASSWORD in the process environment.")
    env=dict(os.environ,MYSQL_PWD=os.environ["DB_PASSWORD"])
    client=[args.client,"--protocol=tcp","--host="+env.get("DB_HOST","127.0.0.1"),"--port="+env.get("DB_PORT","3306"),"--user="+env.get("DB_USERNAME","root"),"--default-character-set=utf8mb4","--batch","--skip-column-names"]
    def sql(statement,database=None):
        command=client+(["--database="+database] if database else [])
        result=subprocess.run(command,input=statement,text=True,encoding="utf-8",capture_output=True,env=env)
        if result.returncode:
            # Avoid printing statements/hashed credentials included by CLI error output.
            codes=re.findall(r"ERROR \d+[^\n]*",result.stderr)
            raise RuntimeError("; ".join(codes) or "MariaDB command failed; check connectivity and schema.")
        return result.stdout.strip()
    if args.action=="migrate":
        sql((ROOT/"docs/database/V002__orders_request_fingerprint.sql").read_text(encoding="utf-8"),args.database)
        sql((ROOT/"docs/database/V003__shipments_idempotency.sql").read_text(encoding="utf-8"),args.database)
        print("Applied additive V002 and V003 migrations to isolated DB "+args.database+".")
        return
    if args.action=="create":
        bootstrap=(ROOT/"docs/database/V001__create_furniture_shop.sql").read_text(encoding="utf-8")
        assert bootstrap.count("CREATE DATABASE furniture_shop ")==1 and bootstrap.count("USE furniture_shop;")==1
        bootstrap=bootstrap.replace("CREATE DATABASE furniture_shop ",f"CREATE DATABASE {args.database} ").replace("USE furniture_shop;",f"USE {args.database};")
        sql(bootstrap)
        sql((ROOT/"docs/database/V002__orders_request_fingerprint.sql").read_text(encoding="utf-8"),args.database)
        sql((ROOT/"docs/database/V003__shipments_idempotency.sql").read_text(encoding="utf-8"),args.database)
        print("Created "+args.database+" with approved 45-table schema and V002/V003 migrations. Existing DBs were not overwritten.")
        return
    if len(os.environ.get("TV3_DEMO_PASSWORD",""))<10:
        parser.error("Supply TV3_DEMO_PASSWORD with at least 10 characters; it is not printed.")
    candidates=[]
    if os.environ.get("JAVA_HOME"):candidates.append(Path(os.environ["JAVA_HOME"])/"bin/java.exe")
    for directory in [Path(r"C:\Program Files\Amazon Corretto"),Path(r"C:\Program Files\Java")]:
        if directory.exists():candidates.extend(sorted(directory.glob("*/bin/java.exe"),reverse=True))
    java=next((str(p) for p in candidates if p.exists()),None) or shutil.which("java")
    jars=Path.home()/".m2/repository"
    crypto=sorted((jars/"org/springframework/security/spring-security-crypto").glob("*/*.jar"))
    logging=sorted((jars/"commons-logging/commons-logging").glob("*/*.jar"))
    if not java or not crypto or not logging:
        parser.error("Run the Maven build first so JDK and spring-security-crypto/commons-logging are available.")
    hashed=subprocess.run([java,"-cp",os.pathsep.join(map(str,[crypto[-1],logging[-1]])),str(ROOT/"scripts/HashDemoPassword.java")],capture_output=True,text=True,env=env,timeout=30)
    if hashed.returncode or not re.fullmatch(r"\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}",hashed.stdout):
        raise RuntimeError("Could not hash the demo password; no seed data was written.")
    password_hash=hashed.stdout
    seed="START TRANSACTION;\n"
    for username,role,name in [("tv3.admin","ADMIN","Quản trị TV3"),("tv3.customer","CUSTOMER","Khách hàng TV3")]:
        seed+=f"INSERT INTO users(username,email,password_hash,full_name,status) VALUES('{username}','{username}@example.invalid','{password_hash}','{name}','ACTIVE') ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),status='ACTIVE';\n"
        seed+=f"INSERT IGNORE INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u CROSS JOIN roles r WHERE u.username='{username}' AND r.code='{role}';\n"
    seed+="INSERT INTO categories(name,slug) VALUES('Nội thất phòng khách','tv3-phong-khach') ON DUPLICATE KEY UPDATE name=VALUES(name);\n"
    fixtures=[("Sofa Modena","tv3-modena","TV3-MODENA-KEM","Kem / 2 mét","12900000.00","photo-1555041469-a586c61ea9bc"),("Bàn trà Oslo","tv3-oslo","TV3-OSLO-SOI","Gỗ sồi tự nhiên","2490000.00","photo-1533090161767-e6ffed986c88"),("Ghế thư giãn Aalto","tv3-aalto","TV3-AALTO-BE","Vải beige","3890000.00","photo-1567538096630-e0c55bd6374c")]
    for name,slug,sku,variant,price,photo in fixtures:
        seed+=f"INSERT INTO products(category_id,name,slug,short_description,status,warranty_months) SELECT id,'{name}','{slug}','Dữ liệu nội thất demo riêng cho TV3','ACTIVE',24 FROM categories WHERE slug='tv3-phong-khach' ON DUPLICATE KEY UPDATE name=VALUES(name);\n"
        seed+=f"INSERT INTO product_variants(product_id,sku,variant_key,name,price,color_name) SELECT id,'{sku}','default','{variant}',{price},'Tự nhiên' FROM products WHERE slug='{slug}' ON DUPLICATE KEY UPDATE price=VALUES(price);\n"
        seed+=f"INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT w.id,v.id,50 FROM warehouses w CROSS JOIN product_variants v WHERE w.code='MAIN' AND v.sku='{sku}' ON DUPLICATE KEY UPDATE on_hand_quantity=GREATEST(on_hand_quantity,reserved_quantity,50);\n"
        seed+=f"INSERT INTO product_images(product_id,url,alt_text) SELECT p.id,'https://images.unsplash.com/{photo}?auto=format&fit=crop&w=640&q=80','{name}' FROM products p WHERE p.slug='{slug}' AND NOT EXISTS(SELECT 1 FROM product_images i WHERE i.product_id=p.id);\n"
    seed+="COMMIT;"
    sql(seed,args.database)
    print("Seeded isolated DB "+args.database+": tv3.admin / tv3.customer, three furniture SKUs, MAIN inventory. Use the password you supplied; it was not saved or printed.")

if __name__=="__main__":
    try:main()
    except (RuntimeError,subprocess.TimeoutExpired) as error:
        print("FAIL: "+str(error),file=sys.stderr);sys.exit(1)
