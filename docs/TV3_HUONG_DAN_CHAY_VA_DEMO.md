# Chạy TV3-01–06 — React + Spring Boot + MariaDB

## Phần đã có

- REST contract và lỗi JSON; session login/logout, CSRF, phân quyền STAFF/ADMIN, token giỏ khách HttpOnly lưu hash trong DB.
- Giỏ theo SKU lưu DB: xem/thêm/sửa/xóa/xóa hết, gộp sau đăng nhập, cảnh báo ngừng bán/hết hàng và giữ giỏ sau refresh. Giỏ không giữ tồn kho.
- Quản trị coupon: phân trang/tìm kiếm/tạo/sửa một phần/bật tắt/xóa mã chưa dùng, xung đột cho mã đã dùng; tính thử phần trăm/số tiền/cap/minimum/thời hạn.
- Giao diện React cho khách và quản trị, desktop/mobile, loading/rỗng/lỗi, hiển thị số tiền do server tính.
- TV3-04/05/06: báo giá checkout, đặt đơn thật có snapshot/giữ tồn và lượt coupon/chống gửi trùng; lịch sử, chi tiết và hủy đơn của khách. COD/chuyển khoản tạo payment PENDING. Hủy đơn đã thu tạo refund PENDING để Task10 xử lý; chưa kết nối cổng thanh toán hoặc thực hiện hoàn tiền.

## Cấu trúc và phối hợp

- Maven main: `org.example.eshop.commerce.CommerceApplication`; bật profile commerce, chỉ scan commerce Entity/repository/component, tắt AI auto-configuration và validate schema mà không sửa bảng.
- Backend mới: `src/main/java/org/example/eshop/commerce/`; frontend: `frontend/`.
- Code legacy còn tại package/controller cũ và được tách khỏi commerce. Có thể gọi main class EshopApplication riêng khi làm regression với schema legacy; không dùng nó để chạy schema mới.
- Contract máy đọc: `docs/api/tv3-commerce.yaml`; thỏa thuận adapter: `docs/api/TV3_IMPLEMENTATION_CONTRACT.md`.
- TV1/TV2/TV4 chưa cung cấp module mới: adapter chỉ đọc tài khoản/địa chỉ/catalog; InventoryPort có implementation MariaDB giữ và trả phần reserved trong cùng transaction đặt/hủy đơn. Không thay đổi on_hand hoặc ghi movement cho thao tác giữ hàng. Khi ghép TV4 phải dùng cùng thứ tự khóa, kiểm tra trạng thái đơn và phần giữ còn lại; xem TV3_CHECKOUT_CONTRACT.md. Chưa có xác nhận của từng thành viên nhóm.
- Session dùng userId do DB cấp, roles/status được nạp lại theo request. Không có tài khoản mặc định hoặc lối đăng nhập giả trong app. Dev seed được chạy riêng, chỉ vào DB cô lập.

## Chuẩn bị

JDK 17+ (máy hiện tại chạy JDK21), Node phù hợp `frontend/package.json`, MariaDB 12.2 đã dùng để kiểm thử. Windows CLI MariaDB mặc định ở `C:\Program Files\MariaDB 12.2\bin\mariadb.exe`; script hỗ trợ `--client` nếu đường dẫn khác.

Mỗi terminal có environment riêng. Root workspace là thư mục chứa pom.xml. Không commit mật khẩu vào source hoặc biến VITE_*.

## Tạo dữ liệu demo riêng (một lần)

```powershell
$env:DB_PASSWORD = Read-Host 'Mật khẩu MariaDB'
python scripts/tv3_database.py create --database furniture_shop_tv3_dev
$env:TV3_DEMO_PASSWORD = Read-Host 'Đặt mật khẩu demo (ít nhất 10 ký tự)'
python scripts/tv3_database.py seed --database furniture_shop_tv3_dev
Remove-Item Env:TV3_DEMO_PASSWORD
```

`create` cố ý thất bại nếu DB đã tồn tại; không xóa/ghi đè DB. DB mới gồm 45 bảng V001 và các migration V002–V004. DB demo đã có từ Task01–03 cần chạy `python scripts/tv3_database.py migrate --database furniture_shop_tv3_dev` trước khi dùng các API mới. Seed tạo `tv3.admin` (ADMIN), `tv3.customer` (CUSTOMER), ba SKU và tồn MAIN; mật khẩu lấy từ environment và hash bằng BCrypt, không in ra. Không dùng chung mật khẩu tài khoản thật. Script từ chối database `furniture_shop` chính.

Khi DB demo trên máy đã được chuẩn bị, không cần tạo lại. Có thể đặt lại mật khẩu demo bằng cách chạy `seed` với environment do bạn chọn. Script chỉ chấp nhận `furniture_shop_tv3_dev` hoặc `furniture_shop_test_*`.

## Terminal backend

```powershell
$env:DB_URL = 'jdbc:mariadb://localhost:3306/furniture_shop_tv3_dev'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = Read-Host 'Mật khẩu MariaDB'
.\mvnw.cmd spring-boot:run
```

Backend chạy `http://localhost:8080`. Runtime commerce không cần JWT_SECRET hoặc GEMINI_API_KEY. `GET /api/commerce/runtime` chỉ trả tên ứng dụng và cờ DB cô lập, không trả tên DB/secret. Bằng chứng session/CSRF: `/api/session` và `/api/csrf`.

## Terminal frontend

```powershell
cd frontend
npm ci
npm run dev
```

Mở `http://127.0.0.1:5173/cart`, đăng nhập tại `/login`, quản trị tại `/admin/coupons`. Vite proxy API về backend, không cần CORS wildcard. Build deploy dùng `npm run build`; reverse proxy phục vụ SPA và chuyển `/api` về Java. Đây không phải một backend trả tên template HTML.

Luồng mới: thêm SKU → `/checkout` → chọn địa chỉ đã lưu hoặc nhập người nhận → chọn COD/chuyển khoản, mã giảm/lắp đặt → xem trước → xác nhận tổng tiền → đặt hàng → `/orders/{id}`. `/orders` có phân trang/lọc trạng thái. Khách chỉ xem/hủy đơn mình; đơn đã xuất giao không hủy trực tiếp được. Không có chức năng xóa lịch sử.

Phí mặc định là chính sách demo cấu hình ở server: giao50.000đ, miễn giao từ10triệu tiền hàng; lắp100.000đ/mỗi sản phẩm cần lắp khi khách chọn dịch vụ. Giá đã gồm thuế, không cộng thêm thuế. Có thể đổi CHECKOUT_SHIPPING_FEE, CHECKOUT_FREE_SHIPPING_THRESHOLD, CHECKOUT_ASSEMBLY_UNIT_FEE. Đây chưa phải kết nối báo phí hãng vận chuyển. Báo giá không giữ hàng; thay đổi khi đặt trả409 và yêu cầu khách xác nhận lại.

Giữ hàng/coupon chưa có tự động hết hạn (Task11): tồn giữ chỉ được trả qua hủy hoặc fulfillment TV4, không bỏ qua reservation khi chỉ hết timestamp. Khi mất kết nối lúc đặt, frontend lưu request/key theo user trong sessionStorage để gửi lại đúng nội dung, kể cả sau tải lại trang. Không sửa nội dung hoặc tạo key mới để retry một kết quả chưa rõ.

Triển khai với DB chính: chọn đúng database trong HeidiSQL rồi chạy file `docs/database/V002__orders_request_fingerprint.sql`; migration chỉ thêm cột nullable và không xóa lịch sử. Không chạy lại V001 trên database đã tồn tại.

Nếu máy Windows có CA doanh nghiệp và gặp lỗi chứng chỉ npm, có thể đặt `NODE_OPTIONS=--use-system-ca` tạm thời. Maven có thể dùng truststore Windows `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE` trong MAVEN_OPTS. Không tắt xác minh TLS; không cần thay truststore máy lâu dài.

## Kiểm thử

Unit/security không cần MariaDB:

```powershell
.\mvnw.cmd '-Dtest=CurrentActorTest,CommerceSecurityTest,CatalogBridgeTest,CommerceRuntimeTest,CouponCalculatorTest' test
cd frontend
npm test
npm run build
```

Integration API/MariaDB thật (database riêng, test sẽ xóa fixture bên trong nó; tuyệt đối không trỏ vào dữ liệu cần giữ):

```powershell
python scripts/tv3_database.py create --database furniture_shop_test_tv3_local
$env:TV3_TEST_DB_URL = 'jdbc:mariadb://localhost:3306/furniture_shop_test_tv3_local'
.\mvnw.cmd '-Dtest=CommerceDatabaseTest,CheckoutTransactionTest,CheckoutAdaptersTest,InventoryReservationTest' test
Remove-Item Env:TV3_TEST_DB_URL
```

`DB_PASSWORD` và `DB_USERNAME` vẫn cần trong terminal này. Integration kiểm tra tên DB trước khi dọn fixture. Ca tranh chấp gộp giỏ đọc INNODB_LOCK_WAITS/INNODB_TRX để đồng bộ chính xác theo khóa, cần quyền đọc metadata phù hợp (đã chạy bằng root trên DB test).

Smoke HTTP thật và Chrome E2E (backend/frontend đang chạy bằng DB demo):

```powershell
$env:TV3_DEMO_PASSWORD = Read-Host 'Mật khẩu tài khoản demo đã seed'
python scripts/tv3_http_smoke.py
cd frontend
npm run test:e2e
Remove-Item Env:TV3_DEMO_PASSWORD
```

Hai công cụ kiểm thử mutation xác minh runtime nối vào DB cô lập trước khi thao tác. Playwright dùng Chrome đã cài trên máy (`channel: chrome`), không điều khiển tab Chrome của người dùng. Screenshot desktop/mobile ở `frontend/test-results/`, được Git bỏ qua. Coupon do smoke/E2E tạo được xóa trong cleanup; E2E có thể để lại giỏ của tài khoản demo sau ca merge.

Full regression suite còn chứa legacy SpringBoot smoke test, cần schema legacy riêng. Trong phiên triển khai đã dùng schema trước migration từ commit 59c435b trong một DB test riêng; TV3 tests dùng DynamicPropertySource để trỏ tới schema mới. Không chạy legacy context trên `furniture_shop` chính hoặc tự tái tạo DB eshop đã xóa. Không xem legacy smoke pass là bằng chứng checkout đã migrate.

## Kịch bản demo

1. Khách mở giỏ, tìm/thêm SKU, tăng/giảm, refresh để thấy dữ liệu giữ lại, xóa dòng/xóa hết.
2. Thêm hàng rồi đăng nhập `tv3.customer`/`tv3.admin`: giỏ khách gộp vào tài khoản, refresh vẫn còn. Đăng xuất tạo lại ngữ cảnh giỏ khách riêng.
3. Với admin, tạo coupon PERCENT/FIXED, cap, minimum, limits và thời hạn; sửa trường nullable về trống, xem thử số tiền, tắt/bật mã.
4. Với khách, vào checkout, nhập/chọn địa chỉ, thêm mã giảm, chọn dịch vụ/phương thức rồi xem trước. Kiểm tra từng khoản tiền và xác nhận đặt. Xem sản phẩm/địa chỉ snapshot tại `/orders/{id}` và lọc lịch sử tại `/orders`.
5. Hủy đơn còn chờ xác nhận, xem payment chuyển CANCELLED và tồn/lượt mã được trả. Gửi hủy lần nữa không có hiệu ứng thứ hai. Không thể xem/hủy đơn của tài khoản khác. Đơn đã xuất giao cần hỗ trợ từ cửa hàng.
6. Quay lại admin: xóa coupon chưa dùng; coupon đã được đơn tham chiếu trả COUPON_IN_USE, có thể tắt mã. Tổng/snapshot của đơn cũ vẫn giữ nguyên.
7. Giới thiệu OpenAPI, test quyền/CSRF/đặt trùng/tranh lượt mã/đặt–hủy đồng thời và ảnh desktop/mobile. Bước tiếp theo là TV3-07 quản trị đơn, TV3-08 xác minh thu tiền.
