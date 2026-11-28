"""Seed 50 original demo products into a selected furniture database.

Each SKU uses one distinct illustrative JPEG in frontend/public/images/catalog.
Run with DB_PASSWORD in the environment. The primary DB requires an explicit flag.
Re-running does not reset stock or orders.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import hashlib

from demo_catalog_images import PHOTOS, filename

ROOT = Path(__file__).resolve().parents[1]
STYLES = [
    ("Mộc An", "Gỗ tự nhiên", "#AA815C", 0),
    ("Sương Mai", "Kem sáng", "#E5D9C7", 9),
    ("Nắng Hạ", "Nâu mật ong", "#B9804F", 15),
    ("Trầm Thu", "Nâu trầm", "#70543E", 7),
    ("Bình Minh", "Be ấm", "#C9B8A1", 12),
]

# name, category slug, room slug, material slug, image, width/depth/height mm,
# starting price VND, warranty months, assembly, product description.
FAMILIES = [
    ("Sofa vải", "sofa", "phong-khach", "vai-boc", "sofa-modena.jpg", 2050, 880, 790, 12900000, 24, True, "Sofa vải dáng thấp với chỗ ngồi rộng, phù hợp góc tiếp khách ấm cúng."),
    ("Sofa thư giãn", "sofa", "phong-khach", "vai-boc", "gallery-soft-living.jpg", 1900, 910, 800, 11900000, 24, True, "Sofa mềm mại với tựa lưng cao, dành cho buổi chiều đọc sách và nghỉ ngơi."),
    ("Bàn trà gỗ", "ban-tra", "phong-khach", "go-soi", "ban-tra-oslo.jpg", 1050, 550, 420, 3190000, 12, False, "Bàn trà mặt rộng, đường nét gọn và mặt gỗ dễ phối trong phòng khách."),
    ("Ghế bành", "ghe-thu-gian", "phong-khach", "vai-boc", "ghe-aalto.jpg", 780, 830, 810, 4290000, 18, False, "Ghế bành tựa êm, vừa vặn cho một góc thư giãn riêng."),
    ("Bàn ăn", "ban-an", "phong-an", "go-soi", "gallery-dining-room.jpg", 1600, 850, 750, 7490000, 24, True, "Bàn ăn gỗ đủ chỗ cho cả nhà quây quần mỗi ngày."),
    ("Ghế ăn", "ghe-an", "phong-an", "go-soi", "gallery-dining-room.jpg", 480, 520, 790, 1890000, 12, False, "Ghế ăn gọn nhẹ với tựa lưng thoải mái cho bữa cơm dài."),
    ("Giường ngủ", "giuong", "phong-ngu", "go-soi", "gallery-bedroom.jpg", 1800, 2100, 900, 10900000, 24, True, "Giường ngủ khung gỗ thấp tạo cảm giác nhẹ nhàng cho phòng ngủ."),
    ("Tủ lưu trữ", "tu-ke", "phong-khach", "go-soi", "gallery-entryway.jpg", 1200, 420, 850, 6590000, 18, True, "Tủ lưu trữ cửa kín giúp cất gọn vật dụng trong nhà."),
    ("Kệ sách", "tu-ke", "phong-khach", "go-soi", "gallery-reading-corner.jpg", 900, 330, 1800, 5290000, 18, True, "Kệ nhiều tầng dành cho sách, đồ trang trí và những món đồ nhỏ."),
    ("Bàn console", "ban-console", "phong-khach", "go-soi", "gallery-entryway.jpg", 1150, 380, 780, 3890000, 12, False, "Bàn console mảnh gọn đặt ở lối vào hoặc sau sofa."),
]

CATEGORIES = [
    ("Sofa", "sofa"), ("Bàn trà", "ban-tra"), ("Ghế thư giãn", "ghe-thu-gian"),
    ("Bàn ăn", "ban-an"), ("Ghế ăn", "ghe-an"), ("Giường ngủ", "giuong"),
    ("Tủ và kệ", "tu-ke"), ("Bàn console", "ban-console"),
]
ROOMS = [("Phòng khách", "phong-khach"), ("Phòng ăn", "phong-an"), ("Phòng ngủ", "phong-ngu")]
MATERIALS = [("Gỗ sồi", "go-soi"), ("Vải bọc", "vai-boc")]


def quote(value):
    return "CONVERT(X'" + str(value).encode("utf-8").hex() + "' USING utf8mb4)"


def sql_rows():
    statements = ["START TRANSACTION;"]
    statements.append("INSERT INTO brands(name,slug,description,country_code) VALUES('Nếp Nhà Studio','nep-nha-studio','Thương hiệu demo của project nội thất','VN') ON DUPLICATE KEY UPDATE name=VALUES(name);")
    for name, slug in CATEGORIES:
        statements.append(f"INSERT INTO categories(name,slug) VALUES({quote(name)},{quote('demo-' + slug)}) ON DUPLICATE KEY UPDATE name=VALUES(name);")
    for name, slug in ROOMS:
        statements.append(f"INSERT INTO rooms(name,slug) VALUES({quote(name)},{quote('demo-' + slug)}) ON DUPLICATE KEY UPDATE name=VALUES(name);")
    for name, slug in MATERIALS:
        statements.append(f"INSERT INTO materials(name,slug) VALUES({quote(name)},{quote('demo-' + slug)}) ON DUPLICATE KEY UPDATE name=VALUES(name);")
    for family_index, (family, category, room, material, image, width, depth, height, base_price, warranty, assembly, description) in enumerate(FAMILIES, start=1):
        for style_index, (style, color, hex_color, uplift) in enumerate(STYLES, start=1):
            name = f"{family} {style}"
            slug = f"demo-{category}-{family_index:02d}-{style_index:02d}"
            sku = f"NN-DEMO-{family_index:02d}-{style_index:02d}"
            price = base_price + uplift * 100000
            compare = ((price * 115 // 100 + 99999) // 100000) * 100000 if style_index in (2, 4) else None
            product = f"(SELECT id FROM products WHERE slug={quote(slug)})"
            variant = f"(SELECT id FROM product_variants WHERE sku={quote(sku)})"
            image_url = "/images/catalog/" + filename(family_index, style_index)
            alt_text = "Ảnh minh họa cho " + name
            statements.append(
                "INSERT INTO products(category_id,brand_id,name,slug,short_description,description,care_instructions,warranty_months,assembly_required,status,seo_title,seo_description) "
                f"SELECT c.id,b.id,{quote(name)},{quote(slug)},{quote(description)},"
                f"{quote(description + ' Phiên bản ' + style + ' mang sắc ' + color.lower() + '. Thông số và giá chỉ dùng để minh họa trong project nhóm.')},"
                f"{quote('Lau nhẹ bằng khăn mềm; tránh ngâm nước và để sát nguồn nhiệt.')},{warranty},{int(assembly)},'ACTIVE',{quote(name + ' | Nếp Nhà')},{quote(description)} "
                f"FROM categories c CROSS JOIN brands b WHERE c.slug={quote('demo-' + category)} AND b.slug='nep-nha-studio' "
                "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(products.id);"
            )
            statements.append(
                "INSERT INTO product_variants(product_id,sku,variant_key,name,color_name,color_hex,size_label,width_mm,depth_mm,height_mm,price,compare_at_price,lead_time_days,status) "
                f"SELECT p.id,{quote(sku)},'default',{quote(style)},{quote(color)},{quote(hex_color)},"
                f"{quote(f'{width} × {depth} × {height} mm')},{width},{depth},{height},{price},"
                f"{compare if compare else 'NULL'},3,'ACTIVE' FROM products p WHERE p.slug={quote(slug)} "
                "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(product_variants.id);"
            )
            statements.append(f"INSERT IGNORE INTO product_rooms(product_id,room_id) SELECT {product},id FROM rooms WHERE slug={quote('demo-' + room)};")
            if family_index == 8:
                statements.append(f"INSERT IGNORE INTO product_rooms(product_id,room_id) SELECT {product},id FROM rooms WHERE slug='demo-phong-ngu';")
            statements.append(f"INSERT IGNORE INTO variant_materials(variant_id,material_id) SELECT {variant},id FROM materials WHERE slug={quote('demo-' + material)};")
            statements.append(
                f"UPDATE product_images SET url={quote(image_url)},alt_text={quote(alt_text)} "
                f"WHERE product_id={product} AND url IN ({quote('/images/' + image)},'/images/gallery-living-room.jpg');"
                if family_index == 10 else
                f"UPDATE product_images SET url={quote(image_url)},alt_text={quote(alt_text)} "
                f"WHERE product_id={product} AND url={quote('/images/' + image)};"
            )
            statements.append(
                "INSERT INTO product_images(product_id,url,alt_text,sort_order) "
                f"SELECT {product},{quote(image_url)},{quote(alt_text)},0 "
                f"WHERE NOT EXISTS(SELECT 1 FROM product_images WHERE product_id={product} AND url={quote(image_url)});"
            )
            for order, spec_name, spec_value, unit in [
                (1, "Chiều rộng", width, "mm"), (2, "Chiều sâu", depth, "mm"),
                (3, "Chiều cao", height, "mm"), (4, "Chất liệu chính", "Gỗ sồi" if material == "go-soi" else "Vải bọc", None),
            ]:
                statements.append(
                    "INSERT INTO product_specifications(product_id,name,value,unit,sort_order) "
                    f"VALUES({product},{quote(spec_name)},{quote(spec_value)},{quote(unit) if unit else 'NULL'},{order}) "
                    "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);"
                )
            statements.append(
                "INSERT IGNORE INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) "
                f"SELECT w.id,{variant},12 FROM warehouses w WHERE w.code='MAIN';"
            )
    statements.extend(catalog_extensions())
    statements.append("COMMIT;")
    return "\n".join(statements)


def target_database(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database")
    parser.add_argument("--allow-main-db", action="store_true",
                        help="Explicitly permit seeding the primary furniture_shop database after backing it up.")
    args = parser.parse_args(argv)
    database = args.database or os.environ.get("DB_NAME", "furniture_shop_tv3_dev")
    if database == "furniture_shop":
        if args.database != "furniture_shop" or not args.allow_main_db:
            raise RuntimeError("furniture_shop requires --database furniture_shop --allow-main-db and a verified backup.")
    elif database in ("furniture_shop_tv3_dev", "furniture_shop_test_ci_demo"):
        if args.allow_main_db:
            raise RuntimeError("--allow-main-db may only be used with furniture_shop.")
    else:
        raise RuntimeError("Only furniture_shop, furniture_shop_tv3_dev or furniture_shop_test_ci_demo can be seeded.")
    return database


def catalog_extensions():
    """Cover TV2 workflows while preserving existing SKU IDs and real stock changes."""
    statements=[]
    for name,slug in ROOMS:
        statements.append(f"INSERT INTO categories(name,slug,sort_order) VALUES({quote('Nội thất ' + name.lower())},{quote('demo-noi-that-' + slug)},0) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);")
    parents={'sofa':'phong-khach','ban-tra':'phong-khach','ghe-thu-gian':'phong-khach','ban-an':'phong-an','ghe-an':'phong-an','giuong':'phong-ngu','tu-ke':'phong-khach','ban-console':'phong-khach'}
    for child,parent in parents.items():
        statements.append(f"UPDATE categories c JOIN categories p ON p.slug={quote('demo-noi-that-'+parent)} SET c.parent_id=p.id WHERE c.slug={quote('demo-'+child)} AND c.parent_id IS NULL;")
    for name,slug in [('Mây đan','demo-may-dan'),('Thép sơn tĩnh điện','demo-thep')]:
        statements.append(f"INSERT INTO materials(name,slug) VALUES({quote(name)},{quote(slug)}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);")
    collections=[('Nhà ấm mỗi ngày','demo-nha-am','Nội thất dịu nhẹ cho những khoảnh khắc cả nhà bên nhau.'),('Mộc & tối giản','demo-moc-toi-gian','Thiết kế gỗ mộc, đường nét gọn và công năng cho không gian vừa đủ.'),('Góc nghỉ an yên','demo-an-yen','Một góc nhỏ để đọc, nghỉ và tận hưởng nhịp sống chậm.')]
    for order,(name,slug,description) in enumerate(collections):
        statements.append(f"INSERT INTO collections(name,slug,description,sort_order) VALUES({quote(name)},{quote(slug)},{quote(description)},{order}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);")
    for family_index,family in enumerate(FAMILIES,start=1):
        category=family[1]
        for style_index in range(1,6):
            slug=f'demo-{category}-{family_index:02d}-{style_index:02d}'
            collection='demo-moc-toi-gian' if family[3]=='go-soi' else 'demo-nha-am'
            statements.append(f"INSERT IGNORE INTO collection_products(collection_id,product_id) SELECT c.id,p.id FROM collections c CROSS JOIN products p WHERE c.slug={quote(collection)} AND p.slug={quote(slug)};")
            if family_index in (1,4,7,9):
                statements.append(f"INSERT IGNORE INTO collection_products(collection_id,product_id) SELECT c.id,p.id FROM collections c CROSS JOIN products p WHERE c.slug='demo-an-yen' AND p.slug={quote(slug)};")
        if family_index not in (1,3,4,5,7,8):
            continue
        product=f"(SELECT id FROM products WHERE slug={quote(f'demo-{category}-{family_index:02d}-01')})"
        base_sku=f'NN-DEMO-{family_index:02d}-01'
        for suffix,color,hex_value,width_delta,price_delta in [('BE-L','Be ấm','#C9B8A1',200,500000),('NAU-M','Nâu trầm','#70543E',100,200000)]:
            sku=base_sku+'-'+suffix
            width=family[5]+width_delta
            statements.append(f"INSERT INTO product_variants(product_id,sku,variant_key,name,color_name,color_hex,size_label,width_mm,depth_mm,height_mm,price,lead_time_days,status) VALUES({product},{quote(sku)},{quote(suffix.lower())},{quote(color+' / '+str(width)+' mm')},{quote(color)},{quote(hex_value)},{quote(str(width)+' mm')},{width},{family[6]},{family[7]},{family[8]+price_delta},5,'ACTIVE') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);")
            variant=f"(SELECT id FROM product_variants WHERE sku={quote(sku)})"
            statements.append(f"INSERT IGNORE INTO variant_materials(variant_id,material_id) SELECT {variant},id FROM materials WHERE slug={quote('demo-'+family[3])};")
            statements.append(f"INSERT IGNORE INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT w.id,{variant},8 FROM warehouses w WHERE w.code='MAIN';")
        # Room photos are explicitly labelled inspiration, never a claimed view of a different SKU.
        room_photo={'phong-khach':'gallery-living-room.jpg','phong-an':'gallery-dining-room.jpg','phong-ngu':'gallery-bedroom.jpg'}[family[2]]
        for order,photo in [(1,room_photo),(2,'gallery-reading-corner.jpg')]:
            url='/images/'+photo
            alt='Ảnh tham khảo bố trí không gian, không phải ảnh SKU đang chọn'
            statements.append(f"INSERT INTO product_images(product_id,url,alt_text,sort_order) SELECT {product},{quote(url)},{quote(alt)},{order} WHERE NOT EXISTS(SELECT 1 FROM product_images WHERE product_id={product} AND url={quote(url)});")
    return statements


def main():
    database = target_database()
    password = os.environ.get("DB_PASSWORD")
    if not password:
        raise RuntimeError("Supply DB_PASSWORD in the environment.")
    hashes = set()
    for family in range(1, len(PHOTOS) + 1):
        for style in range(1, len(PHOTOS[family - 1]) + 1):
            image = ROOT / "frontend/public/images/catalog" / filename(family, style)
            if not image.is_file():
                raise RuntimeError(f"Missing catalog image: {image.name}")
            digest = hashlib.sha256(image.read_bytes()).digest()
            if digest in hashes:
                raise RuntimeError(f"Duplicate catalog image: {image.name}")
            hashes.add(digest)
    client = [os.environ.get("MARIADB_CLIENT") or shutil.which("mariadb") or r"C:\Program Files\MariaDB 12.2\bin\mariadb.exe",
              "--no-defaults", "--protocol=tcp", "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
              "--port=" + os.environ.get("DB_PORT", "3306"),
              "--user=" + os.environ.get("DB_USERNAME", "root"), "--database=" + database,
              "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
    result = subprocess.run(client, input=sql_rows(), text=True, encoding="utf-8", capture_output=True,
                            env=dict(os.environ, MYSQL_PWD=password))
    if result.returncode:
        raise RuntimeError("MariaDB seed failed; check connection and V001-V015 schema.")
    print(f"Seeded 50 demo products and 62 SKUs into {database}, with categories, details, illustrations and initial MAIN stock.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, FileNotFoundError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
