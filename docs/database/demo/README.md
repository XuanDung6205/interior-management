# SQL đồng bộ dữ liệu Thành viên 2

Gửi thư mục này cùng nhánh **Nhon** cho các thành viên. Dùng **MariaDB 12.2 trong Docker** theo `docker-compose.yml`. Bộ dữ liệu có **50 sản phẩm / 150 SKU**, mỗi sản phẩm ba SKU; 11 danh mục cha/con, ba phòng, bốn chất liệu, ba bộ sưu tập, ảnh/thông số và tồn ban đầu ở kho MAIN.

| File | Dùng khi nào | Nội dung |
| --- | --- | --- |
| [TV2_FULL_DATABASE_DEMO.sql](TV2_FULL_DATABASE_DEMO.sql) | Máy mới, chưa có database demo | Tạo `furniture_shop_tv3_dev`, toàn bộ 49 bảng V001–V015 và dữ liệu TV2 |
| [TV2_CATALOG_DATA.sql](TV2_CATALOG_DATA.sql) | Đã có database với đủ V001–V015 | Thêm/bổ sung dữ liệu TV2 vào database đang chọn, giữ ID/giá/trạng thái/tồn của SKU cũ |

## Import bằng HeidiSQL

1. Chạy `docker compose up -d mariadb redis`. Kết nối HeidiSQL tới `127.0.0.1:3306`, user `root`, mật khẩu `DB_PASSWORD` của máy mình.
2. Máy mới: mở **File → Load SQL file**, chọn `TV2_FULL_DATABASE_DEMO.sql`, encoding UTF-8, chạy **toàn bộ file**. File tự tạo/chọn `furniture_shop_tv3_dev`. Chỉ dùng file này khi database đó chưa tồn tại; bật dừng khi gặp lỗi trong SQL client.
3. Đã có database: áp dụng đủ migration đến V015, chọn đúng database trên HeidiSQL, mở `TV2_CATALOG_DATA.sql` và chạy toàn bộ trong một connection. Không chạy lại file full lên DB đang dùng. Nếu seed báo lỗi, dừng và chạy `ROLLBACK;` trong connection đó trước khi sửa lỗi/chạy lại.
4. Đặt `.env` của ứng dụng có `DB_NAME=furniture_shop_tv3_dev` (hoặc tên DB nhóm đang dùng), rồi `docker compose up -d app`.

Tài khoản demo được tạo riêng bằng `docker compose run --rm demo`, dùng `TV3_DEMO_PASSWORD` mỗi máy tự đặt trong `.env`. SQL chia sẻ chứa cấu trúc các module và dữ liệu catalog, không lấy tài khoản hoặc lịch sử giao dịch từ máy cá nhân. Khi dùng DB chính `furniture_shop`, sử dụng tài khoản do TV1 cấp.

Ảnh nằm trong `frontend/public/images`, SQL lưu đường dẫn ảnh. Các thành viên cần lấy cả code/ảnh từ nhánh `Nhon`; gửi riêng SQL không mang theo ảnh.

## Quy ước để đồng bộ

- Import file full giống nhau vào database mới sẽ có cùng ID sản phẩm/SKU và dữ liệu ban đầu; đã kiểm tra trên hai database độc lập.
- Nếu database đã có dữ liệu, seed tra theo slug/mã SKU và giữ ID hiện có. Các module giỏ/đơn/kho phải tra `product_variants.id` từ mã `sku`, tránh tự giả định ID bằng một số cố định.
- Tạm ngừng chỉnh catalog qua giao diện trong lúc import. Mỗi lần seed tăng `products.version` để cửa sổ quản trị đang mở phải tải lại, đồng thời đưa các sản phẩm demo vào vector outbox hiện có. Giao diện công khai có Redis TTL 60 giây nên có thể cần tối đa 60 giây để thấy SKU vừa thêm bằng SQL.
- Tồn ban đầu: SKU gốc 12, SKU bổ sung 8. Chạy lại giữ tồn đã điều chỉnh, giá, trạng thái và các SKU do thành viên khác tạo. DB có dữ liệu riêng có thể nhiều hơn 50 sản phẩm / 150 SKU.

Kiểm tra sau import:

```sql
SELECT COUNT(*) AS demo_products
FROM products WHERE slug LIKE 'demo-%';

SELECT COUNT(*) AS demo_skus
FROM product_variants WHERE sku LIKE 'NN-DEMO-%';

SELECT p.slug, v.id AS variant_id, v.sku, v.price, v.status
FROM product_variants v JOIN products p ON p.id=v.product_id
WHERE v.sku LIKE 'NN-DEMO-%'
ORDER BY p.slug, v.sku;
```

DB mới cho kết quả 50 sản phẩm và 150 SKU. Ví dụ `NN-DEMO-01-01` có hai phiên bản thêm là `NN-DEMO-01-01-BE-L`, `NN-DEMO-01-01-NAU-M`. Các mã cũ trong bản 62 SKU được giữ lại.

## Tái tạo và kiểm chứng

SQL được sinh từ migration và `scripts/seed_demo_catalog.py`, không phải bản dump DB cá nhân:

```sh
python scripts/build_tv2_catalog_sql.py
python scripts/build_tv2_catalog_sql.py --check
```

Khi cần file full cho một tên database khác, xuất sang thư mục riêng:

```sh
python scripts/build_tv2_catalog_sql.py --database furniture_shop --output-dir target/tv2-sql-main
```

Chỉ import file full vào database mới tương ứng. Không sửa tên DB bằng thay thế hàng loạt trong dữ liệu SQL.

Kiểm chứng lại trong Docker (tự tạo và dọn các DB `furniture_shop_test_tv2_sql_*`, không đổi DB demo):

```sh
docker compose build migrate
docker compose run --rm --no-deps --entrypoint python migrate scripts/verify_tv2_catalog_sql.py
```

[Bằng chứng import/nâng cấp/rollback](../../performance/tv2-sql-sync-2026-11-28.json). [Hướng dẫn chạy và hợp đồng bàn giao TV2](../../TV2_CATALOG_HANDOFF.md).
