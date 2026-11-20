# Chạy TV3 — React + Spring Boot + MariaDB

Hướng dẫn bàn giao cập nhật ngày19/11/2026: [TV3_BAN_GIAO_VA_DEMO.md](TV3_BAN_GIAO_VA_DEMO.md). Runtime hiện dùng V001–V009,47bảng; gồm cả thu/hoàn thủ công, lắp đặt/đổi trả, thông báo và vận hành. VNPAY sandbox vẫn chờ thông tin đăng ký.

Từ20/11 có thể [build/chạy một JAR cùng origin](TV3_RELEASE.md); xem thêm [CI](TV3_CI.md) và [backup/restore](TV3_BACKUP_RESTORE.md). Các bước dưới đây vẫn dành cho phát triển bằng backend/Vite riêng.

## Phần đã có

- REST contract và lỗi JSON; session login/logout, CSRF, phân quyền STAFF/ADMIN, token giỏ khách HttpOnly lưu hash trong DB.
- Giỏ theo SKU lưu DB: xem/thêm/sửa/xóa/xóa hết, gộp sau đăng nhập, cảnh báo ngừng bán/hết hàng và giữ giỏ sau refresh. Giỏ không giữ tồn kho.
- Quản trị coupon: phân trang/tìm kiếm/tạo/sửa một phần/bật tắt/xóa mã chưa dùng, xung đột cho mã đã dùng; tính thử phần trăm/số tiền/cap/minimum/thời hạn.
- Giao diện React cho khách và quản trị, desktop/mobile, loading/rỗng/lỗi, hiển thị số tiền do server tính.
- TV3-04/05/06: báo giá checkout, đặt đơn thật có snapshot/giữ tồn và lượt coupon/chống gửi trùng; lịch sử, chi tiết và hủy đơn của khách. COD/chuyển khoản tạo payment PENDING. Hủy đơn đã thu tạo refund PENDING để Task10 xử lý; chưa kết nối cổng thanh toán hoặc thực hiện hoàn tiền.
- TV3-07: quản trị đơn cho STAFF/ADMIN tại `/admin/orders`: tìm/lọc/phân trang, chi tiết snapshot, ghi chú nội bộ có phát hiện sửa đồng thời, chuyển trạng thái có kiểm tra bằng chứng và audit trong cùng transaction.

## Cấu trúc và phối hợp

- Maven main: `org.example.eshop.commerce.CommerceApplication`; bật profile commerce, chỉ scan commerce Entity/repository/component, tắt AI auto-configuration và validate schema mà không sửa bảng.
- Backend mới: `src/main/java/org/example/eshop/commerce/`; frontend: `frontend/`.
- Code legacy còn tại package/controller cũ và được tách khỏi commerce. Có thể gọi main class EshopApplication riêng khi làm regression với schema legacy; không dùng nó để chạy schema mới.
- Contract máy đọc: `docs/api/tv3-commerce.yaml`; thỏa thuận adapter: `docs/api/TV3_IMPLEMENTATION_CONTRACT.md`.
- TV1/TV2/TV4 chưa cung cấp module mới: adapter chỉ đọc tài khoản/địa chỉ/catalog; InventoryPort có implementation MariaDB giữ và trả phần reserved trong cùng transaction đặt/hủy đơn. Không thay đổi on_hand hoặc ghi movement cho thao tác giữ hàng. Khi ghép TV4 phải dùng cùng thứ tự khóa, kiểm tra trạng thái đơn và phần giữ còn lại; xem TV3_CHECKOUT_CONTRACT.md. Chưa có xác nhận của từng thành viên nhóm.
- OrderAuditPort nối vào bảng audit_logs sẵn có của TV1, chỉ append cùng transaction và ghi UTC tường minh. Không tạo module quản lý người dùng/audit mới. Contract TV3-07: `docs/api/TV3_ADMIN_ORDERS_CONTRACT.md`.
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

`create` cố ý thất bại nếu DB đã tồn tại; không xóa/ghi đè DB. DB mới áp dụng V001–V009 và có47bảng. DB demo đã có cần chạy `python scripts/tv3_database.py migrate --database furniture_shop_tv3_dev` trước khi chạy bản mới. Seed tạo `tv3.admin` (ADMIN), `tv3.customer` (CUSTOMER), ba SKU và tồn MAIN; mật khẩu lấy từ environment và hash bằng BCrypt, không in ra. Seed lại không reset tồn hiện có. Modena yêu cầu kỹ thuật lắp đặt, Oslo không yêu cầu. Không dùng chung mật khẩu tài khoản thật. Script từ chối database `furniture_shop` chính.

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

Triển khai với DB chính: chọn đúng database trong HeidiSQL rồi chạy lần lượt `docs/database/V002__orders_request_fingerprint.sql`, `V003__shipments_idempotency.sql`, `V004__shipment_audit_index.sql`, `V005__orders_assembly_requested.sql`, `V006__payments_confirmation_note.sql`, `V007__refunds_verification_notes.sql`. V003/V004 hỗ trợ xuất kho/vận đơn TV4; V005 backfill đơn cũ đã thu phí lắp thành có chọn dịch vụ và lưu lựa chọn mới kể cả phí0; V006/V007 bổ sung ghi chú đối soát riêng cho nhân viên. Không xóa lịch sử và không chạy lại V001 trên database đã tồn tại.

## Quản trị đơn hàng TV3-07

Đăng nhập tài khoản STAFF/ADMIN rồi mở `/admin/orders`. Tìm mã đơn, tên/email tài khoản hoặc tên/số điện thoại người nhận; lọc trạng thái và phân trang. Màn hình chi tiết hiển thị thanh toán, vận chuyển, lắp đặt, hoàn tiền và lịch sử riêng nhau.

Luồng trạng thái: Chờ xác nhận → Đã xác nhận → Đang xử lý → Hoàn thành. Có thể hủy ở ba trạng thái đầu nếu chưa xuất giao; hủy khi Đang xử lý chỉ dành cho nhân viên. Bấm lặp một chuyển trạng thái đã áp dụng không ghi lịch sử/audit thêm. Nếu người khác đổi trạng thái, tải lại trước khi thao tác tiếp.

Hoàn thành cần đủ khoản thu thực sau khi trừ khoản hoàn đang chờ/đã hoàn, giao đủ số lượng, reservation đã xuất hết, movement xuất kho đúng và hoàn tất lắp đặt đã chọn. Nếu thiếu, UI hiển thị lý do và server từ chối chuyển. TV3-08 xác minh khoản thu; TV4 ghi nhận giao hàng. Lần chuyển khoản thất bại cũ không chặn xuất kho sau khi lần mới đã thu đủ hoặc có khoản COD đang chờ hợp lệ phủ đủ công nợ.

Ghi chú nội bộ chỉ nhân viên đọc được, không xuất hiện trong lịch sử khách. Thông báo chuyển trạng thái/lý do hủy là nội dung khách nhìn thấy. Khi ghi chú bị sửa đồng thời, bản nháp được giữ; đọc bản mới rồi quyết định lưu lại, không ghi đè im lặng.

Phần kho/giao hàng TV4 đã được ghép cùng: `/admin/inventory/receipts` (nhập kho), `/admin/inventory/operations` (điều chỉnh/chuyển kho), `/admin/inventory/dispatch` (xuất hàng đã giữ), `/admin/inventory/shipments` (kết quả giao/hoàn về kho). Xem các contract TV4 trong `docs/api/`. Giao đủ hàng chỉ tự hoàn tất đơn khi cùng chính sách TV3-07 xác nhận đủ thu tiền, số lượng và lắp đặt; COD chưa thu vẫn ở Đang xử lý.

Nếu máy Windows có CA doanh nghiệp và gặp lỗi chứng chỉ npm, có thể đặt `NODE_OPTIONS=--use-system-ca` tạm thời. Maven có thể dùng truststore Windows `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE` trong MAVEN_OPTS. Không tắt xác minh TLS; không cần thay truststore máy lâu dài.

## Kiểm thử

Unit/security không cần MariaDB:

```powershell
.\mvnw.cmd '-Dtest=CurrentActorTest,CommerceSecurityTest,CatalogBridgeTest,CommerceRuntimeTest,CouponCalculatorTest,BankTransferSettingsTest' test
cd frontend
npm test
npm run build
```

Integration API/MariaDB thật (database riêng, test sẽ xóa fixture bên trong nó; tuyệt đối không trỏ vào dữ liệu cần giữ):

```powershell
python scripts/tv3_database.py create --database furniture_shop_test_tv3_policy_local
$env:TV3_TEST_DB_URL = 'jdbc:mariadb://localhost:3306/furniture_shop_test_tv3_policy_local'
.\mvnw.cmd '-Dtest=CommerceDatabaseTest,CheckoutTransactionTest,CheckoutAdaptersTest,InventoryReservationTest,OrderLifecycleTest,OrderCompletionPolicyTest,PaymentConfirmationTest,ManualRefundTest,OrderDispatchTest' test
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
7. Với admin, tìm đơn mới, thêm ghi chú nội bộ, xác nhận/đưa vào xử lý; xem lý do chưa thể hoàn thành rồi hủy trước xuất giao. Đăng nhập khách kiểm tra không thấy ghi chú nội bộ.
8. Khách bấm **Thanh toán** từ đơn để xem khoản thu và hướng dẫn. Nhân viên mở `/admin/payments`, lọc/tìm đơn, đối chiếu khoản thu, nhập mã biên nhận và xác nhận hai bước. Khách tải lại thấy đã thu; xác nhận lại cùng bằng chứng giữ nguyên người/thời điểm ghi nhận.
9. Trên đơn đã thu còn được phép hủy, khách hủy và kiểm tra **Hoàn tiền chờ xử lý**. Đây là yêu cầu hoàn, chưa chuyển trả tiền. Nếu thực tế khoản thu đến sau khi hủy, nhân viên xác minh với cảnh báo khoản đến muộn; đơn giữ CANCELLED và tạo đúng một yêu cầu hoàn.

## Thanh toán thủ công TV3-08

Để demo trên `furniture_shop_tv3_dev`, đặt `$env:PAYMENT_BANK_MODE='DEMO'` trong terminal khởi động Spring Boot. Hướng dẫn dùng số tài khoản cố ý không hợp lệ `DEMO-KHONG-CHUYEN-TIEN` và hiển thị cảnh báo. Đây là mô phỏng cục bộ, không phải sandbox ngân hàng; không chuyển tiền thật. Cấu hình DEMO bị từ chối trên database chính.

Mặc định `PAYMENT_BANK_MODE=UNCONFIGURED`, không công khai số tài khoản và không cho tạo lần BANK_TRANSFER mới. COD vẫn dùng được. Đơn đã tạo bằng BANK_TRANSFER vẫn xem được lịch sử và thông báo chưa cấu hình. Khi cửa hàng có nhu cầu nhận chuyển khoản thủ công thật, dùng MANUAL và đặt đủ PAYMENT_BANK_NAME, PAYMENT_BANK_ACCOUNT_NUMBER, PAYMENT_BANK_ACCOUNT_HOLDER trong environment riêng; ứng dụng chỉ kiểm tra định dạng, không xác minh tài khoản và không gọi ngân hàng. Không ghi thông tin nhạy cảm vào Git.

Đặt hàng đã tạo sẵn một khoản PENDING; khách không cần tạo lần thứ hai. Chỉ tạo lại khi lần trước đã FAILED/EXPIRED. Job chỉ hết hạn COD mới chưa xác nhận sau24giờ và đủ điều kiện an toàn; không tự hết hạn khoản chuyển khoản chưa đối soát hoặc đơn cũ không có hạn. Tạo lại dùng số tiền server tính từ tổng đơn trừ khoản SUCCEEDED. Hoàn tiền không mở lại công nợ đơn cũ. Không có nút khách tự đánh dấu đã trả tiền.

Nhân viên chỉ xác minh COD/MANUAL_BANK sau khi đối chiếu biên nhận/sao kê (trong demo dùng chứng từ minh họa). Số tiền, tài khoản nhân viên và thời gian UTC do server quyết định. Ghi chú đối soát không xuất hiện trong API khách. Nếu mất phản hồi, React giữ nguyên nội dung và gửi lại cùng bằng chứng; không đổi mã để thử thu lại. Mã biên nhận đã gắn khoản khác bị từ chối, khoản vượt công nợ cần đối soát thủ công. Cổng thanh toán/webhook là TV3-09, thực hiện hoàn tiền là TV3-10; TV3-08 không giả lập các giao dịch đó thành đã hoàn tất.

## Hoàn tiền thủ công TV3-10 — không cần sandbox

STAFF/ADMIN mở `/admin/refunds` để tìm/lọc yêu cầu. Có liên kết từ chi tiết đơn và khoản thu tới `/admin/orders/{id}/refunds`; chọn một yêu cầu để đối chiếu tại `/admin/refunds/{id}`. Nhân viên xem riêng số đã thu, đang giữ chờ hoàn, đã hoàn và ngân sách có thể đề nghị thêm. Khách chỉ xem lịch sử công khai, không được gọi API ghi hoàn tiền.

Hủy đơn đã thu tạo sẵn PENDING, giữ ngân sách bằng số cần hoàn. Ứng dụng không chuyển tiền. Chỉ bấm xác nhận sau khi đã hoàn thực tế và đối chiếu mã chứng từ; trong demo dùng chứng từ giả trên DB cô lập, không chuyển tiền thật. Xác nhận hai bước giữ nguyên toàn bộ số tiền yêu cầu; tài khoản nhân viên và thời gian UTC do server ghi. Cùng chứng từ gửi lại giữ nguyên kết quả/người/thời điểm, không thêm audit. Thanh toán gốc vẫn SUCCEEDED và đơn đã hủy vẫn CANCELLED.

Nếu chưa chuyển tiền và yêu cầu cũ cần thay bằng các khoản từng phần, dùng **hủy yêu cầu chưa gửi tiền** với lý do/xác nhận rõ ràng, rồi lập yêu cầu thay thế theo ngân sách server trả. Đây không phải xóa nghĩa vụ hoàn; khoản chưa được trả lại vẫn cần xử lý. Không hủy yêu cầu có tiền đã gửi hoặc trạng thái chưa rõ. Số PENDING và SUCCEEDED đều trừ vào hạn mức; hai yêu cầu đồng thời không thể giữ vượt khoản đã thu.

Đơn chưa hủy chỉ được đề nghị theo phiếu TV4 cùng đơn có resolution REFUND, đã duyệt và thực nhận hàng (RECEIVED/COMPLETED). Giới hạn tính theo số lượng đã nhận và giá snapshot sau giảm giá; không tự cộng phí giao/lắp. Tại `/orders/{id}/returns`, khách tạo yêu cầu theo lượng đã giao. Nhân viên dùng `/admin/orders/{id}/aftercare` để duyệt/từ chối, xác nhận thực nhận/kiểm định và quyết định nhập lại đúng kho. Service ghi movement RETURN một lần; service hoàn tiền đọc bằng chứng này. Chrome đã kiểm chứng luồng thực qua API/MariaDB, có mất phản hồi và replay.

Khi mất mạng, lỗi đọc JSON,401/403/429 hoặc lỗi chưa rõ, React giữ nguyên key/body/chứng từ theo nhân viên và tài nguyên, kể cả tải lại trang. Đăng nhập lại bằng đúng tài khoản rồi thử lại yêu cầu đã gửi. Lỗi nghiệp vụ chắc chắn giữ nội dung thành bản nháp để đối chiếu và sửa; không tự tạo lệnh hoàn mới khi chưa biết kết quả cũ.

V007 thêm hai cột ghi chú riêng; đã áp dụng cộng thêm trên DB chính và demo trong phiên này. Hoàn tự động qua VNPAY, xử lý timeout/đối soát của provider và callback hoàn tiền vẫn chờ TV3-09/cấu hình sandbox, chưa được đánh dấu hoàn tất.

## Hiệu năng đọc độc lập TV3-11

Xem [báo cáo19/11](performance/TV3_READ_PERFORMANCE_2026-11-19.md) và `tests/performance/tv3/README.md`. Dataset cô lập có1000đơn/3000dòng/1003SKU; hai lượt tổng3200GET/0lỗi. Ba index cải thiện plan trang đầu, nhưng p95 thực tế40.150→43.508ms nên chưa chứng minh tăng tốc HTTP. Pool quan sát tối đa3active/0awaiting; deep offset vẫn phải scan. Không cache quyết định tiền, tồn, coupon hoặc quyền; chưa dùng Redis/provider reconciliation.

## Vận hành và thông báo TV3-11/12

- COD mới có hạn xác nhận24giờ (`ORDER_CONFIRMATION_TTL_SECONDS=86400`). Worker chỉ hủy đơn còn PENDING_CONFIRMATION chưa thu/chưa xuất, trả giữ hàng/lượt mã một lần, payment chờ chuyển EXPIRED, lịch sử/audit ghi tác nhân hệ thống. Đơn cũ có hạn NULL, chuyển khoản, đã thu/đã xác nhận/đã xuất và đơn0đồng không bị tự hủy.
- Outbox ghi cùng transaction nghiệp vụ; worker giao vào `/notifications`, có retry bền và chống trùng. Khách chỉ đọc hộp thư của mình; ghi chú/chứng từ riêng không đi vào thông báo. Đây là hộp thư web, chưa gửi email.
- STAFF/ADMIN mở `/admin/operations` để xem hàng đợi, lỗi DEAD, hạn đơn cần xử lý và pool Hikari thực; chạy batch hoặc retry DEAD có lý do/audit. `COMMERCE_JOBS_ENABLED=false` tắt lịch nền; thao tác thủ công vẫn dùng cùng kiểm tra điều kiện. Test Maven/commerce mặc định tắt lịch để tránh job tác động fixture.
- Lắp đặt tại aftercare chỉ ghi sau DELIVERED thật, có nhân viên/thời điểm/bằng chứng; đủ giao/lắp/thu mới hoàn tất đơn. Kịch bản bàn giao đầy đủ và cấu hình từng job nằm trong [hướng dẫn mới](TV3_BAN_GIAO_VA_DEMO.md).

Kiểm thử19/11: Java180ca (179đạt,1Gemini live bỏ qua), React142đạt/13file, build63module, Chrome7/7đạt; schema47bảng và22kiểm tra rollback đạt; harness tải15/15đạt. OpenAPI1.5.0 có52operation, Postman syntax lint không có lỗi. Đây là bằng chứng cục bộ, chưa phải nghiệm thu VNPAY hay triển khai production.

Kịch bản Chrome mới: thu DEMO→khách hủy→xem PENDING refund→nhân viên hủy yêu cầu chưa gửi→tạo thay thế→cố ý mất phản hồi/tải lại/gửi cùng key→xác minh hoàn với chứng từ giả→mất phản hồi/tải lại/gửi cùng chứng từ→khách xem lịch sử đã hoàn, không thấy ghi chú riêng. Đây là transaction/API/MariaDB thật trên dữ liệu demo; không phải giao dịch ngân hàng hay sandbox provider.
