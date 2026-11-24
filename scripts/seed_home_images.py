"""Attach original Nếp Nhà images to the three TV3 demo SKUs.

Only the disposable furniture_shop_tv3_dev database is accepted. Supply
DB_PASSWORD in the process environment. Image files live in frontend/public;
MariaDB stores their paths so the web server can serve them efficiently.
"""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = (
    ("tv3-modena", "TV3-MODENA-KEM", "sofa-modena.jpg", "Sofa Modena màu kem"),
    ("tv3-oslo", "TV3-OSLO-SOI", "ban-tra-oslo.jpg", "Bàn trà Oslo gỗ sồi"),
    ("tv3-aalto", "TV3-AALTO-BE", "ghe-aalto.jpg", "Ghế thư giãn Aalto màu beige"),
)


def main():
    database = os.environ.get("DB_NAME", "furniture_shop_tv3_dev")
    if database != "furniture_shop_tv3_dev":
        raise RuntimeError("This script only writes to furniture_shop_tv3_dev.")
    password = os.environ.get("DB_PASSWORD")
    if not password:
        raise RuntimeError("Supply DB_PASSWORD in the process environment.")
    for _, _, filename, _ in FIXTURES:
        if not (ROOT / "frontend/public/images" / filename).is_file():
            raise RuntimeError(f"Missing original image: {filename}")

    client = [os.environ.get("MARIADB_CLIENT", r"C:\Program Files\MariaDB 12.2\bin\mariadb.exe"),
              "--protocol=tcp", "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
              "--port=" + os.environ.get("DB_PORT", "3306"),
              "--user=" + os.environ.get("DB_USERNAME", "root"),
              "--database=" + database, "--default-character-set=utf8mb4",
              "--batch", "--skip-column-names"]
    env = dict(os.environ, MYSQL_PWD=password)

    def query(sql):
        result = subprocess.run(client, input=sql, text=True, encoding="utf-8",
                                capture_output=True, env=env)
        if result.returncode:
            raise RuntimeError("MariaDB command failed; check connectivity and schema.")
        return result.stdout.strip()

    actual = query("SELECT p.slug,v.sku FROM products p JOIN product_variants v "
                   "ON v.product_id=p.id WHERE p.slug IN "
                   "('tv3-modena','tv3-oslo','tv3-aalto') ORDER BY p.slug,v.sku;")
    expected = sorted((slug, sku) for slug, sku, _, _ in FIXTURES)
    if sorted(tuple(line.split("\t")) for line in actual.splitlines()) != expected:
        raise RuntimeError("Demo product/SKU rows do not match; no data changed.")

    statements = ["START TRANSACTION;"]
    for slug, sku, filename, alt in FIXTURES:
        url = "/images/" + filename
        statements.append(
            "INSERT INTO product_images(product_id,variant_id,url,alt_text,sort_order) "
            f"SELECT p.id,v.id,'{url}','{alt}',-10 FROM products p "
            f"JOIN product_variants v ON v.product_id=p.id WHERE p.slug='{slug}' "
            f"AND v.sku='{sku}' AND NOT EXISTS "
            f"(SELECT 1 FROM product_images i WHERE i.product_id=p.id AND i.url='{url}');"
        )
    statements.append("COMMIT;")
    query("\n".join(statements))
    print("Original image paths are present for all three TV3 demo SKUs.")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
