# Nếp Nhà — web bán nội thất

Runtime commerce dùng **Spring Boot REST API + React + MariaDB**, main class `org.example.eshop.commerce.CommerceApplication`. Code MVC/AI legacy được giữ riêng, không nạp vào commerce. Schema runtime cần đủ migration V001–V014 với 49 bảng InnoDB (danh sách trong `scripts/tv3_schema.py`); không dùng `ddl-auto=update` để thay migration.

## Chạy và bàn giao

- [Build/chạy demo bằng một JAR](docs/TV3_RELEASE.md): React và API cùng origin, không cần mở Vite/Docker khi chạy gói.
- [Hướng dẫn phát triển và demo](docs/TV3_HUONG_DAN_CHAY_VA_DEMO.md): chạy backend/frontend riêng.
- [Bàn giao nghiệp vụ TV3–TV4](docs/TV3_BAN_GIAO_VA_DEMO.md): phân quyền, dữ liệu demo, giao/lắp/trả/hoàn thủ công, thông báo và COD24giờ.
- [In phiếu đơn và xuất CSV](docs/TV3_IN_DON_VA_XUAT_CSV.md): in/Lưu PDF theo quyền và tải tất cả đơn theo bộ lọc ngày/trạng thái/từ khóa.
- [Backup/restore MariaDB](docs/TV3_BACKUP_RESTORE.md): snapshot riêng tư, checksum, phục hồi vào DB test mới và đối chiếu đúng 49 bảng (bản lịch sử trước V013 là 47 bảng).
- [GitHub CI](docs/TV3_CI.md): integration MariaDB, test React, gói JAR, recovery và Chrome cùng origin; trạng thái hosted execution phải đọc cùng bằng chứng thực tế.
- [Schema và migration](docs/database/README.md), [OpenAPI](docs/api/tv3-commerce.yaml).

## Gói demo

Từ root repo, khi có JDK17+, Node theo `frontend/package.json` và Python3.11+:

```powershell
python scripts/tv3_release.py build
```

Sau khi đã chuẩn bị DB demo V001–V014 và cấp `DB_PASSWORD` qua môi trường:

```powershell
python scripts/tv3_release.py run --database furniture_shop_tv3_dev --port 8080
```

Mở `http://127.0.0.1:8080`. Startup không tự tạo schema/tài khoản; xem hướng dẫn release trước khi dùng DB mới. `.env.example` chỉ là mẫu tên biến, Spring không tự nạp file này. Không commit mật khẩu, backup hoặc khóa provider; `target/` và `backups/` được Git bỏ qua.

Thanh toán hỗ trợ COD, chuyển khoản có nhân viên đối soát chứng từ và **SePay Test mode** (mục dưới); nhóm đã chốt SePay thay VNPAY từ 25/11/2026. Hoàn tiền thủ công hoặc qua SePay Sandbox, không vượt số đã thu. Thông báo hiện giao vào hộp thư web (`/notifications`), chưa gửi email/SMS.

`docker-compose.yml` có MariaDB và Redis cho môi trường phát triển; commerce đã có cấu hình Redis cache nhưng chưa dùng cho dữ liệu nào. Không cache giá, tồn, coupon, quyền hay trạng thái thu/hoàn tiền: checkout luôn đọc MariaDB.

## SePay Test mode (TV3)

Luồng QR + Webhook HMAC-SHA256 và đối soát API v2: [Hướng dẫn chạy Sandbox](docs/TV3_SEPAY_SANDBOX.md), [hợp đồng API](docs/api/TV3_SEPAY_SANDBOX_CONTRACT.md). Cấu hình mặc định tắt; khóa nằm ngoài Git trong `.env.sepay.local`.
