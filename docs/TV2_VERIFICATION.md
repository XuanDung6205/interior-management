# Kiểm chứng catalog TV2 — 28/11/2026

Nguồn: nhánh `Nhon`, môi trường local Docker Compose: MariaDB 12.2.2, Redis 7, Java 21, Node 24. Không dùng MariaDB chạy riêng trên host.

## Đã kiểm chứng trên dịch vụ thật

- Migration V001–V015 trên DB demo và DB test: đủ **49 bảng**; chạy lại migration không thay thế dữ liệu.
- Seed thành công từ image Docker: **50 sản phẩm demo / 62 SKU**, 3 bộ sưu tập, cây danh mục và gallery. Seed lần hai giữ nguyên ID; chủ động sửa giá/trạng thái/tồn một SKU rồi chạy lại vẫn giữ các giá trị đã sửa (sau test đã phục hồi dữ liệu demo).
- `scripts/tv2_catalog_smoke.py` qua HTTP: facets, bộ lọc kết hợp, range sai trả 400, chặn khách vào admin, login/CSRF, tạo sản phẩm, xuất bản, sửa giá, đọc giá mới ngay, giữ SKU ID, 409 version cũ và archive → public 404.
- Redis có key catalog thực tế (không chỉ cấu hình). Tắt container Redis rồi chạy lại đầy đủ smoke đọc/lưu vẫn thành công qua DB. Bật Redis và chạy lại smoke thành công, không restart ứng dụng.
- **50 thumbnail**: 1.925.215 byte so với 13.928.382 byte ảnh gốc, giảm **86,18%**; ảnh gốc vẫn dùng cho gallery chi tiết.

Số đo HTTP sau Redis phục hồi, 20 request mỗi route:

| Route | Median | P95 |
| --- | ---: | ---: |
| Danh sách 12 sản phẩm | 50,24 ms | 106,24 ms |
| Chi tiết sofa 3 SKU | 43,70 ms | 61,93 ms |

[Bằng chứng JSON](performance/tv2-catalog-2026-11-28.json) gồm cả Redis online/offline/recovery và số byte ảnh. Máy đồng thời chạy build/test; đây là mốc demo, **không phải** phép so sánh có kiểm soát để kết luận tốc độ cache hoặc load test của cả nhóm. TV5 tiếp tục đo tải tổng thể trên fixture lớn.

## Kiểm thử tự động

- Helper/schema/release/seed: **67 tests passed** (`python3 -m unittest discover -s tests/operations -p 'test_*.py'`).
- React toàn bộ: **215 tests passed / 20 files**, chạy từ source cuối cùng trong Docker (412,29 giây), bao gồm catalog, điều hướng, tài khoản, checkout, kho, thanh toán/hoàn tiền và TV5. Không còn ca thất bại hoặc bỏ qua trong lượt này.
- Chrome trên JAR trong Docker: **5 tests passed** (35,9 giây), kiểm tra 50 sản phẩm, menu phòng, giữ grid/phân trang/cuộn đầu trang, bộ lọc/bộ sưu tập, chọn SKU/gallery/thêm giỏ, lưu giá thập phân và màn hình admin ở 390px. Có kiểm tra không tràn ngang trên mobile.
- Java chạy Docker: **27 tests passed**, gồm 11 ca catalog/cache và 16 ca checkout adapter, bảo mật, catalog bridge, vector runtime. Catalog kiểm tra DB MariaDB thật: đúng SKU khi kết hợp bộ lọc, danh mục cha/con, validation, giữ SKU/tồn, giá checkout, rollback, version, archive, CSRF/quyền, audit/vector outbox. Unit cache kiểm tra cache hit, fallback, lỗi DB không bị nuốt và invalidation sau commit.

Bản bàn giao đã tích hợp commit `533cd82` mới nhất của nhóm về cờ seed DB chính; mặc định Docker vẫn seed DB demo cô lập. Helper tests bao gồm bốn ca bảo vệ opt-in từ commit đó.

## Phạm vi và cách chạy lại

Lệnh Docker và kịch bản nghiệm thu trong [bàn giao TV2](TV2_CATALOG_HANDOFF.md). Các test database chỉ chấp nhận `furniture_shop_test_*`. Smoke mutation chỉ chạy khi runtime xác nhận DB demo/test cô lập; fixture kiểm chứng được archive, không xóa lịch sử.

Chưa thực hiện load test toàn nhóm hay gọi Gemini/nhà cung cấp thanh toán thật trong nhiệm vụ TV2. Các tác vụ provider vẫn cần người phụ trách bật cấu hình sandbox/live riêng; catalog và Docker không yêu cầu API key để demo.
