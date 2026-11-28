# Bàn giao Thành viên 2 — catalog nội thất

Nhánh làm việc: **Nhon**. Module chạy trong `CommerceApplication` (Spring Boot REST + React), giữ nguyên các adapter checkout/kho đã tích hợp trên main. Không triển khai catalog vào MVC/Thymeleaf legacy.

## Chạy toàn bộ bằng Docker

Yêu cầu Docker Desktop đang mở và Docker Compose v2. Cổng giữ nguyên **8080 / 3306 / 6379**. Dừng MariaDB chạy riêng trên máy nếu nó chiếm 3306; không dùng đồng thời hai server trên cổng này.

1. Copy `.env.example` thành `.env`, tự đặt `DB_PASSWORD` và `TV3_DEMO_PASSWORD` (ít nhất 12 ký tự). `JWT_SECRET` chỉ phục vụ bộ test legacy: chuỗi Base64 của tối thiểu 32 byte ngẫu nhiên. Không commit `.env`.
2. Chạy lần lượt tại root repo:

```sh
docker compose build app migrate demo
docker compose up -d mariadb redis
docker compose run --rm migrate migrate
docker compose run --rm demo
docker compose up -d app
```

Truy cập **http://localhost:8080**; quản trị **http://localhost:8080/admin/catalog**. Tài khoản demo `tv2.admin`, `tv2.staff`, `tv2.customer` dùng mật khẩu `TV3_DEMO_PASSWORD` bạn đặt. Tài khoản chỉ tạo khi chạy seed trên database cô lập. Seed không được dùng cho `furniture_shop` thật.

DB mặc định là **furniture_shop_tv3_dev**, cùng DB demo đã được các luồng TV3 hỗ trợ. Trong HeidiSQL: host `127.0.0.1`, port `3306`, user `root`, password từ `DB_PASSWORD`, chọn `furniture_shop_tv3_dev`. Server MariaDB phải đang chạy trong Docker. Từ container, DB host là `mariadb`, Redis host là `redis`; không dùng `localhost` để gọi container khác. Compose truyền các biến này cho app.

`migrate` áp dụng V001–V015: bootstrap khi schema rỗng, migration cộng thêm khi schema đã có. Không drop/recreate DB, không dùng `ddl-auto=update`. V015 thêm `products.version` và các index catalog, vẫn 49 bảng. Sao lưu DB có dữ liệu trước khi nâng schema. Dừng bằng `docker compose stop`; `docker compose down` giữ named volume. Không thêm `-v` nếu cần giữ dữ liệu.

Nếu thay `DB_PASSWORD` sau lần khởi tạo volume, phải đổi mật khẩu tài khoản MariaDB trong DB tương ứng; thay `.env` không tự đổi mật khẩu đã lưu. Không xóa volume có dữ liệu để xử lý mật khẩu.

Kiểm tra:

```sh
docker compose ps
docker compose exec redis redis-cli ping
docker compose logs --tail 60 app
```

## SQL gửi cả nhóm để đồng bộ

Dữ liệu hiện có **50 sản phẩm / 150 SKU**, mỗi sản phẩm ba phiên bản. Gửi [hai file SQL và hướng dẫn HeidiSQL](database/demo/README.md) cùng nhánh `Nhon`: `TV2_FULL_DATABASE_DEMO.sql` cho DB mới (đủ 49 bảng + dữ liệu), `TV2_CATALOG_DATA.sql` cho DB đã đủ V015. Bản data nâng từ 62 SKU cũ lên 150, giữ ID/giá/trạng thái/tồn đã sửa; không hard-code lại ID dùng ở giỏ/đơn/kho. SQL được sinh từ migration + seed có version và vector outbox; dùng `python scripts/build_tv2_catalog_sql.py --check` để kiểm tra file luôn khớp source.

## Phạm vi hoàn thành

- Quản trị năm nhóm phân loại: danh mục cha/con, phòng, thương hiệu, chất liệu, bộ sưu tập. Có mô tả, ảnh/logo, thứ tự, quốc gia/chăm sóc theo loại; ẩn thay vì xóa mất liên kết. Ẩn danh mục cha sẽ ẩn các danh mục con trong cùng transaction; mở cha không tự mở lại con, cần bật con theo chủ đích.
- Quản trị aggregate sản phẩm: mô tả, SEO, bảo hành, lắp đặt, nhiều phòng/bộ sưu tập; SKU có mã duy nhất, khóa phiên bản, màu/HEX, số đo mm, cân nặng kg, giá Decimal, giá tham chiếu, ngày chuẩn bị, trạng thái và nhiều chất liệu.
- Gallery nhiều ảnh, sắp thứ tự, alt text, ảnh chung hoặc ảnh gắn SKU; thông số bổ sung. Tạo SKU mới, lưu, rồi gắn ảnh riêng theo ID SKU vừa được cấp.
- Khách duyệt `/sale`, `/categories/{slug}`, `/collections/{slug}`; tìm tên/mô tả/SKU, lọc giá/chất liệu/màu/nhãn kích thước/số đo/phòng/thương hiệu/danh mục/bộ sưu tập, sắp xếp, phân trang. Chọn danh mục cha lấy cả con đang hoạt động.
- Chi tiết `/products/{slug}` có gallery theo SKU, chọn phiên bản, giá, số lượng, chất liệu, thông số, bảo hành/chăm sóc và thêm đúng `variantId` vào giỏ.
- Responsive, điều khiển có nhãn, trạng thái tải/lỗi/thử lại; giữ danh sách trong lúc đổi bộ lọc. 50 thumbnail JPEG 480px cho ảnh catalog; ảnh gốc dùng ở chi tiết, có lazy loading/decoding async và fallback.

12 bảng phụ trách: `categories`, `rooms`, `brands`, `materials`, `collections`, `products`, `product_variants`, `product_rooms`, `collection_products`, `variant_materials`, `product_images`, `product_specifications`. Không tạo thêm bảng catalog trùng schema đã thống nhất.

## Hợp đồng tích hợp

Chi tiết API: [TV2_CATALOG_API.md](api/TV2_CATALOG_API.md).

**TV3:** `product_variants.id` là ID mua hàng. Giá là `DECIMAL(15,2)`/`BigDecimal`, trả JSON chuỗi tiền; số đo mm. Adapter giỏ và checkout đọc trực tiếp MariaDB, bao gồm trạng thái sản phẩm/SKU/danh mục. Checkout không đọc cache hoặc giá client. Snapshot đơn hiện có không bị sửa khi catalog thay đổi.

**TV4:** TV2 không chỉnh số tồn và không tự tạo tồn khi admin tạo SKU. SKU mới hiển thị tồn 0; TV4 nhập hàng qua màn hình Kho. Seed chỉ `INSERT IGNORE` tồn ban đầu cho SKU chưa có, không reset lượng tồn/giữ hàng đã thay đổi.

**TV5:** Mỗi lần lưu sản phẩm ghi audit và `commerce_vector_reindex_outbox` trong cùng transaction. Worker vector hiện có xử lý bất đồng bộ; không gọi AI trong thao tác lưu catalog. Cache gợi ý được invalidated sau commit. Worker discovery hiện có tiếp tục phát hiện thay đổi taxonomy cho document. Danh mục bị ẩn làm document không còn công khai, worker xử lý xóa vector tương ứng.

**TV1:** Giữ session/CSRF và rule ADMIN/STAFF của `/api/admin/**`. Dùng Redis connection đã cấu hình. Không thêm tài khoản/mật khẩu mặc định vào source.

`PUT` sản phẩm phải gửi lại `version` đọc từ GET. Sai phiên bản trả **409 CATALOG_STALE**; giao diện cho tải lại. SKU ID và ID ảnh gắn SKU phải thuộc chính sản phẩm. Bỏ một SKU cũ khỏi payload chỉ chuyển `INACTIVE`, không xóa các tham chiếu giỏ/đơn/kho. Đổi trạng thái SKU ngay trong form là cách dùng thông thường. Không cho sản phẩm ACTIVE thiếu SKU ACTIVE hoặc thiếu ảnh.

## Cache và truy vấn

`CATALOG_CACHE_ENABLED=true` bật JSON Redis cho DTO công khai (facets, trang kết quả, thông tin sản phẩm), **TTL 60 giây**. Namespace có fingerprint URL DB để không trộn demo/test. Key gồm đầy đủ bộ lọc, sort, page, size. Không dùng Redis `KEYS *`; invalidation thay generation sau commit. Giao dịch rollback không invalidated cache.

Tồn khả dụng được batch đọc trực tiếp MariaDB trong mỗi request, kể cả khi DTO cache hit. Redis lỗi: bỏ qua cache, đọc DB, thử lại sau 5 giây; lỗi DB vẫn được trả đúng. Nếu Redis ngắt đúng lúc ghi, instance ghi giữ yêu cầu invalidation cho lần kết nối lại. Trên nhiều instance bị chia cắt mạng, nội dung/giá hiển thị có thể cũ tối đa TTL; checkout vẫn kiểm tra giá và tồn thật.

Trang danh sách dùng count + truy vấn SKU đại diện + một batch tồn; mọi điều kiện SKU nằm chung một subquery, chọn SKU rẻ nhất thỏa tất cả điều kiện. Không query kho/material/ảnh theo từng card trong vòng lặp Java. Detail dùng các truy vấn cố định cho product, variants, images, specs, material và một batch tồn. Index V015 hỗ trợ lookup theo product/status/price, ảnh/product/sort và liên kết taxonomy.

## Kiểm thử và demo

```sh
# Chuẩn bị hai schema test cô lập, không chạm DB demo:
docker compose build migrate java-tests frontend-tests
docker compose run --rm migrate prepare-tests
docker compose run --rm java-tests
docker compose run --rm frontend-tests
```

Test catalog Java: `CatalogIntegrationTest`, `CatalogCacheTest`. Test React: `CatalogManagement.test.jsx` cùng regression có sẵn. Java integration yêu cầu `TV3_TEST_DB_URL` trỏ `furniture_shop_test_*`; service test đã đặt biến này. Test live AI mặc định bỏ qua vì cần API key và opt-in riêng. CI chạy trên nhánh `Nhon`.

Kịch bản nghiệm thu:

1. Khách mở `/sale`, lọc chất liệu + màu + ngân sách + chiều rộng; đổi sort/trang và mở sản phẩm.
2. Mở `demo-sofa-01-01`: thử 3 SKU, thay giá/số đo theo SKU, xem các ảnh và chú thích ảnh tham khảo, thêm giỏ.
3. Đăng nhập admin vào `/admin/catalog`: tạo danh mục con, thương hiệu/chất liệu/bộ sưu tập; tạo sản phẩm DRAFT và SKU; thêm ảnh/thông số; xuất bản ACTIVE.
4. TV4 nhập hàng cho SKU mới, khách thấy lượng tồn mới và thêm đúng SKU vào giỏ.
5. Mở hai cửa sổ sửa cùng sản phẩm: cửa sổ thứ hai lưu version cũ nhận 409; tải lại. Thử cha là chính con của mình phải bị chặn.
6. Đổi giá rồi xem giỏ/checkout: dùng giá DB mới, giữ ID và tồn cũ. Chuyển sản phẩm/SKU sang ngừng bán và xác nhận khách không mua được.
7. Tắt Redis bằng `docker compose stop redis`, duyệt/lưu catalog vẫn được; bật lại và xác nhận dữ liệu mới. Không dùng thao tác này khi nhóm khác đang đo hiệu năng.

Bằng chứng kiểm thử thực tế và giới hạn xác minh: [TV2_VERIFICATION.md](TV2_VERIFICATION.md).

Browser E2E: sau khi app healthy, đặt `TV3_E2E_BASE_URL=http://127.0.0.1:8080`, `TV3_DEMO_PASSWORD` và `TV2_E2E_ADMIN_USERNAME=tv2.admin` trong môi trường rồi chạy `npm --prefix frontend run test:e2e -- e2e/catalog-sale.spec.js e2e/tv2-catalog.spec.js`. CI dùng tài khoản `tv3.admin` do seed release hiện có tạo.
