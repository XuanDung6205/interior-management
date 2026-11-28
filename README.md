# Nếp Nhà — web bán nội thất

Runtime commerce dùng **Spring Boot REST API + React + MariaDB**, main class `org.example.eshop.commerce.CommerceApplication`. Code MVC/AI legacy được giữ riêng, không nạp vào commerce. Schema runtime cần đủ migration V001–V015 với 49 bảng InnoDB (danh sách trong `scripts/tv3_schema.py`); không dùng `ddl-auto=update` để thay migration.

## Chạy và bàn giao

- **[Thành viên 2: catalog và Docker](docs/TV2_CATALOG_HANDOFF.md)** — quản trị sản phẩm/SKU/phân loại, bộ lọc, gallery, demo và hợp đồng bàn giao cho TV3–TV5.
- [SQL đồng bộ cho cả nhóm: 50 sản phẩm / 150 SKU](docs/database/demo/README.md) — có file full DB mới và file data cho DB đã có.
- [Hợp đồng API catalog](docs/api/TV2_CATALOG_API.md), [bằng chứng kiểm thử TV2](docs/TV2_VERIFICATION.md).

- [Build/chạy demo bằng một JAR](docs/TV3_RELEASE.md): React và API cùng origin, không cần mở Vite/Docker khi chạy gói.
- [Hướng dẫn phát triển và demo](docs/TV3_HUONG_DAN_CHAY_VA_DEMO.md): chạy backend/frontend riêng.
- [Bàn giao nghiệp vụ TV3–TV4](docs/TV3_BAN_GIAO_VA_DEMO.md): phân quyền, dữ liệu demo, giao/lắp/trả/hoàn thủ công, thông báo và COD24giờ.
- [In phiếu đơn và xuất CSV](docs/TV3_IN_DON_VA_XUAT_CSV.md): in/Lưu PDF theo quyền và tải tất cả đơn theo bộ lọc ngày/trạng thái/từ khóa.
- [Backup/restore MariaDB](docs/TV3_BACKUP_RESTORE.md): snapshot riêng tư, checksum, phục hồi vào DB test mới và đối chiếu đúng 49 bảng (bản lịch sử trước V013 là 47 bảng).
- [GitHub CI](docs/TV3_CI.md): integration MariaDB, test React, gói JAR, recovery và Chrome cùng origin; trạng thái hosted execution phải đọc cùng bằng chứng thực tế.
- [Schema và migration](docs/database/README.md), [OpenAPI](docs/api/tv3-commerce.yaml).
- [Nạp 50 sản phẩm và ảnh demo](docs/demo-catalog.md) vào database đã chọn.

## Gói demo

Từ root repo, khi có JDK17+, Node theo `frontend/package.json` và Python3.11+:

```powershell
python scripts/tv3_release.py build
```

Sau khi đã chuẩn bị DB demo V001–V015 và cấp `DB_PASSWORD` qua môi trường:

```powershell
python scripts/tv3_release.py run --database furniture_shop_tv3_dev --port 8080
```

Mở `http://127.0.0.1:8080`. Startup không tự tạo schema/tài khoản; xem hướng dẫn release trước khi dùng DB mới. `.env.example` chỉ là mẫu tên biến, Spring không tự nạp file này. Không commit mật khẩu, backup hoặc khóa provider; `target/` và `backups/` được Git bỏ qua.

Thanh toán hỗ trợ COD, chuyển khoản có nhân viên đối soát chứng từ và **SePay Test mode** (mục dưới); nhóm đã chốt SePay thay VNPAY từ 25/11/2026. Hoàn tiền thủ công hoặc qua SePay Sandbox, không vượt số đã thu. Thông báo hiện giao vào hộp thư web (`/notifications`), chưa gửi email/SMS.

`docker-compose.yml` chạy ứng dụng, MariaDB, Redis và migration trong Docker; xem hướng dẫn TV2 để đặt biến môi trường và seed demo. Catalog dùng Redis JSON TTL 60 giây cho dữ liệu hiển thị; tồn khả dụng luôn đọc live. Checkout lấy giá, tồn, coupon và quyền trực tiếp từ MariaDB. Không cache quyết định thu/hoàn tiền.

## SePay Test mode (TV3)

Luồng QR + Webhook HMAC-SHA256 và đối soát API v2: [Hướng dẫn chạy Sandbox](docs/TV3_SEPAY_SANDBOX.md), [hợp đồng API](docs/api/TV3_SEPAY_SANDBOX_CONTRACT.md). Cấu hình mặc định tắt; khóa nằm ngoài Git trong `.env.sepay.local`.
