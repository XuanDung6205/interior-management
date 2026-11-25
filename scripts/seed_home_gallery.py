"""Add Nếp Nhà's original gallery images to the isolated TV3 demo database.

Requires DB_PASSWORD in the environment. Only furniture_shop_tv3_dev is allowed.
The image files remain in frontend/public/images; MariaDB stores their URLs.
"""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SLIDES = (
    ("gallery-living-room.jpg", "Góc phòng khách", "Sofa kem và bàn trà gỗ trong phòng khách đón nắng"),
    ("gallery-dining-room.jpg", "Bữa cơm nhà", "Bàn ăn gỗ sồi và ghế mây cạnh cửa sổ"),
    ("gallery-bedroom.jpg", "Nơi nghỉ ngơi", "Giường gỗ thấp với chăn ga màu kem trong phòng ngủ"),
    ("gallery-reading-corner.jpg", "Góc đọc sách", "Ghế thư giãn bên tủ sách gỗ và cửa sổ"),
    ("gallery-entryway.jpg", "Lối vào ấm áp", "Tủ console gỗ và gương tròn ở lối vào"),
    ("gallery-soft-living.jpg", "Một chiều chậm", "Ghế bành màu kem cạnh tủ gỗ và rèm cửa"),
)


def main():
    database = os.environ.get("DB_NAME", "furniture_shop_tv3_dev")
    if database != "furniture_shop_tv3_dev":
        raise RuntimeError("This script only writes to furniture_shop_tv3_dev.")
    password = os.environ.get("DB_PASSWORD")
    if not password:
        raise RuntimeError("Supply DB_PASSWORD in the process environment.")
    for filename, _, _ in SLIDES:
        if not (ROOT / "frontend/public/images" / filename).is_file():
            raise RuntimeError(f"Missing original gallery image: {filename}")

    client = [os.environ.get("MARIADB_CLIENT", r"C:\Program Files\MariaDB 12.2\bin\mariadb.exe"),
              "--protocol=tcp", "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
              "--port=" + os.environ.get("DB_PORT", "3306"),
              "--user=" + os.environ.get("DB_USERNAME", "root"),
              "--database=" + database, "--default-character-set=utf8mb4",
              "--batch", "--skip-column-names"]
    env = dict(os.environ, MYSQL_PWD=password)
    statements = ["START TRANSACTION;"]
    for index, (filename, title, alt) in enumerate(SLIDES, start=1):
        url = "/images/" + filename
        statements.append(
            "INSERT INTO banners(title,image_url,alt_text,placement,sort_order,is_active) "
            f"SELECT '{title}','{url}','{alt}','HOME_GALLERY',{index * 10},TRUE "
            "WHERE NOT EXISTS (SELECT 1 FROM banners "
            f"WHERE placement='HOME_GALLERY' AND image_url='{url}');"
        )
    statements.append("COMMIT;")
    result = subprocess.run(client, input="\n".join(statements), text=True,
                            encoding="utf-8", capture_output=True, env=env)
    if result.returncode:
        raise RuntimeError("MariaDB command failed; check connectivity and schema.")
    print("Six original Home gallery image URLs are present in the demo banners table.")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
