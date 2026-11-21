# Chạy bản demo React + Spring Boot bằng một JAR

Bản release phục vụ giao diện React và REST API trên **cùng một địa chỉ**. Máy chạy không cần mở Vite hoặc Docker. Các trang sâu như `/orders/123`, `/admin/orders/123/aftercare` mở lại/refresh được; `/api/*` và `/assets/*` không được chuyển thành HTML React.

Đây là gói runtime commerce hiện có, chưa tích hợp VNPAY sandbox hoặc các module ngoài phạm vi đã bàn giao.

## Build từ source

Cần JDK17+ (đã dùng Corretto21), Node đúng `frontend/package.json` (Node22.12+ hoặc24+), Python3.11+, npm và kết nối tải dependency ở lần đầu. Đặt `JAVA_HOME` trỏ tới JDK thật, không trỏ thư mục `bin`. Tại thư mục chứa `pom.xml`:

```powershell
python scripts/tv3_release.py build
```

Công cụ chạy `npm ci`, build Vite, rồi Maven profile `commerce-release` để tạo executable JAR. Profile dùng thư mục build riêng và loại templates/CSS/JS legacy; ảnh cũ trong `static/images` còn được giữ để tương thích đường dẫn catalog. Maven không thay đổi schema, không seed và không đọc `.env` vào gói.

Chỉ khi cache npm/Maven đã đầy đủ mới dùng `build --offline`. Nếu dependency chưa được cache, lệnh này dừng và không công bố gói mới. Kiểm thử chạy riêng trong CI hoặc trước khi build; build dùng `-DskipTests` và metadata ghi rõ điều đó.

Trên Windows, dừng tiến trình đang chạy `target/releases/nep-nha-demo.jar` trước khi build lại; JVM giữ khóa file nên hệ điều hành có thể từ chối thay JAR đang mở. Nếu bước công bố gói báo lỗi, không coi dòng Maven BUILD SUCCESS phía trước là bản release mới đã sẵn sàng; chờ công cụ báo `Built ...nep-nha-demo.jar`.

Kết quả tại `target/releases/`:

- `nep-nha-demo.jar`: React build và Spring Boot commerce cùng dependency Java.
- `nep-nha-demo.jar.sha256`: checksum SHA256 của JAR.
- `release.json`: checksum, thời điểm build UTC, main class, yêu cầu Java và phiên bản migration.

Ba file này không chứa dữ liệu MariaDB hoặc mật khẩu. Khi chuyển gói sang máy khác, giữ cả ba file cùng thư mục. Gói sinh ra nằm trong `target/`, không commit binary vào source. Không chạy build đồng thời từ hai terminal trong cùng checkout.

## Chuẩn bị DB

Runtime yêu cầu schema V001–V009 với47bảng. Với dữ liệu demo mới, dùng script tạo/seed như [hướng dẫn bàn giao](TV3_BAN_GIAO_VA_DEMO.md); với dữ liệu đã backup, dùng [backup/restore](TV3_BACKUP_RESTORE.md) để khôi phục vào DB test mới. Không trỏ test/seed/restore vào DB chính.

```powershell
$env:DB_PASSWORD = Read-Host 'Mật khẩu MariaDB cho terminal này'
$env:DB_USERNAME = 'root'
python scripts/tv3_database.py create --database furniture_shop_tv3_dev
$env:TV3_DEMO_PASSWORD = Read-Host 'Đặt mật khẩu demo tối thiểu10ký tự'
python scripts/tv3_database.py seed --database furniture_shop_tv3_dev
Remove-Item Env:TV3_DEMO_PASSWORD
```

Chỉ chạy `create` khi DB chưa tồn tại; DB có sẵn thì dùng `migrate`, không tạo lại. Seed không reset tồn đã có. Startup của JAR không tự migrate, khôi phục hoặc tạo tài khoản. Mật khẩu MariaDB và mật khẩu tài khoản web là hai thông tin riêng.

## Chạy demo trên laptop

Trong terminal đã có `DB_PASSWORD` và JDK:

```powershell
$env:PAYMENT_BANK_MODE = 'DEMO'
python scripts/tv3_release.py run --database furniture_shop_tv3_dev --port 8080
```

Mở `http://127.0.0.1:8080`. Nếu8080đang có ứng dụng khác, chọn `--port 8082`; không cần mở frontend5173. Dừng bằng Ctrl+C. Runner kiểm tra checksum, main class và tài nguyên trước khi chạy; chỉ chấp nhận DB demo/test và MariaDB loopback. Runner loại các biến `SPRING_*`, `SERVER_*` và tùy chọn JVM thừa kế, chỉ nạp config trong JAR và cố định datasource/schema-validation/bind-loopback bằng tham số ứng dụng không chứa secret. Biến cấu hình nghiệp vụ như `PAYMENT_BANK_MODE`, `COMMERCE_JOBS_ENABLED` và `ORDER_CONFIRMATION_TTL_SECONDS` vẫn dùng được.

Biến `PAYMENT_BANK_MODE=DEMO` chỉ dành DB cô lập và hiển thị chứng từ minh họa, không có chuyển tiền thật. Có thể giữ mặc định UNCONFIGURED để chỉ dùng COD. Jobs nền mặc định bật; `COMMERCE_JOBS_ENABLED=false` tắt lịch nhưng staff vẫn chạy batch có kiểm tra tại `/admin/operations`. Hạn COD mặc định24giờ, không sửa khi đóng gói.

Runner cần Python vì kiểm tra gói và chọn DB trước khi khởi động Java. Khi máy chỉ có JDK, có thể kiểm tra checksum bằng công cụ hệ điều hành rồi chạy trực tiếp:

```powershell
$jar = 'target/releases/nep-nha-demo.jar'
$expected = ((Get-Content "$jar.sha256") -split '\s+')[0]
if ((Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'JAR checksum mismatch' }
$env:DB_URL = 'jdbc:mariadb://127.0.0.1:3306/furniture_shop_tv3_dev'
$env:SERVER_ADDRESS = '127.0.0.1'
java -jar $jar
```

Lệnh Java trực tiếp không có guard chọn database của runner; người chạy phải đặt đúng URL demo. Không đặt password/JWT/khóa ngân hàng vào tham số `java -D...` hoặc sửa application properties trong JAR. Kết thúc phiên có thể xóa các biến nhạy cảm khỏi terminal.

## Kiểm tra bản đã đóng gói

```powershell
python scripts/ci_packaged_smoke.py --base-url http://127.0.0.1:8080
$env:TV3_E2E_BASE_URL = 'http://127.0.0.1:8080'
$env:TV3_DEMO_PASSWORD = Read-Host 'Mật khẩu tài khoản demo đã seed'
cd frontend
npm run test:e2e
```

Chrome E2E ghi nghiệp vụ thật lên DB demo/test; dùng DB restore riêng nếu cần giữ nguyên dữ liệu demo. Kiểm thử bao gồm giỏ, checkout, quản trị đơn/coupon, thu/hoàn thủ công, giao/lắp/trả hàng và hộp thư thông báo. Browser headless mặc định là Chrome trên máy; CI dùng `TV3_E2E_CHANNEL=chromium` đã cài riêng. Các bước API/schema không được thay bằng kiểm tra HTML đơn thuần.

Workflow, điều kiện không được skip integration và giới hạn hosted execution nằm ở [TV3_CI.md](TV3_CI.md). Checksum giúp phát hiện gói bị đổi/hỏng; nhận JAR từ nguồn nhóm tin cậy, không coi checksum kèm file là chữ ký xác thực nhà phát hành.
