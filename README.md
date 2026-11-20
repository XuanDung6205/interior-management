# Nếp Nhà — web bán nội thất

Runtime commerce dùng **Spring Boot REST API + React + MariaDB**, main class `org.example.eshop.commerce.CommerceApplication`. Code MVC/AI legacy được giữ riêng, không nạp vào commerce. Schema runtime cần V001–V009 với47bảng; không dùng `ddl-auto=update` để thay migration.

## Chạy và bàn giao

- [Build/chạy demo bằng một JAR](docs/TV3_RELEASE.md): React và API cùng origin, không cần mở Vite/Docker khi chạy gói.
- [Hướng dẫn phát triển và demo](docs/TV3_HUONG_DAN_CHAY_VA_DEMO.md): chạy backend/frontend riêng.
- [Bàn giao nghiệp vụ TV3–TV4](docs/TV3_BAN_GIAO_VA_DEMO.md): phân quyền, dữ liệu demo, giao/lắp/trả/hoàn thủ công, thông báo và COD24giờ.
- [Backup/restore MariaDB](docs/TV3_BACKUP_RESTORE.md): snapshot riêng tư, checksum, phục hồi vào DB test mới và đối chiếu47bảng.
- [GitHub CI](docs/TV3_CI.md): integration MariaDB, test React, gói JAR, recovery và Chrome cùng origin; trạng thái hosted execution phải đọc cùng bằng chứng thực tế.
- [Schema và migration](docs/database/README.md), [OpenAPI](docs/api/tv3-commerce.yaml).

## Gói demo

Từ root repo, khi có JDK17+, Node theo `frontend/package.json` và Python3.11+:

```powershell
python scripts/tv3_release.py build
```

Sau khi đã chuẩn bị DB demo V001–V009 và cấp `DB_PASSWORD` qua môi trường:

```powershell
python scripts/tv3_release.py run --database furniture_shop_tv3_dev --port 8080
```

Mở `http://127.0.0.1:8080`. Startup không tự tạo schema/tài khoản; xem hướng dẫn release trước khi dùng DB mới. `.env.example` chỉ là mẫu tên biến, Spring không tự nạp file này. Không commit mật khẩu, backup hoặc khóa provider; `target/` và `backups/` được Git bỏ qua.

Đã có thu/hoàn **thủ công có đối chiếu chứng từ** và demo cô lập; VNPAY sandbox/webhook/đối soát/hoàn online vẫn chờ tích hợp riêng. Thông báo hiện giao vào hộp thư web, chưa gửi email/SMS.
