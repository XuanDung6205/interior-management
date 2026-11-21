# Bàn giao và demo TV3–TV4 — vận hành commerce

Tài liệu vận hành cho runtime `org.example.eshop.commerce.CommerceApplication`, React/Vite và MariaDB. Phạm vi gồm giỏ/coupon/checkout/đơn, thu và hoàn tiền thủ công, kho/giao hàng, hết hạn COD 24 giờ, thông báo web bền vững, lắp đặt và trả hàng. Contract chính: [operations/aftercare](api/TV3_OPERATIONS_HANDOFF_CONTRACT.md), [OpenAPI](api/tv3-commerce.yaml). Những kết quả kiểm thử/đo tải ở cuối tài liệu phải đọc cùng điều kiện môi trường của chúng.

Cập nhật20/11: [gói React+Spring Boot một JAR](TV3_RELEASE.md), [backup/restore có đối chiếu dữ liệu](TV3_BACKUP_RESTORE.md), [workflow CI](TV3_CI.md). Các công cụ này hỗ trợ nhóm chạy và nghiệm thu bản demo mà không cần VNPAY. Snapshot/backups là dữ liệu riêng tư, không đưa lên GitHub hoặc artifact CI.

## 1. Chủ sở hữu tích hợp của nhóm 5 thành viên

Tên cá nhân chưa được cung cấp; dùng đúng mã thành viên trong [phân công nhóm](PHAN_CONG_NHOM_5_THANH_VIEN.md).

- **TV1 — kiến trúc, tài khoản, bảo mật, triển khai:** users/roles/địa chỉ/audit; session và CSRF; cấu hình/secret, backup và quan sát hạ tầng. Tài khoản, vai trò và trạng thái nạp từ DB; ID nhân viên lấy từ session. Mọi thay đổi migration cần phối hợp TV1, không tự tạo lại schema đang dùng.
- **TV2 — catalog:** sản phẩm/SKU/giá/ảnh/thuộc tính. TV3 đọc SKU và giá DB hiện hành khi quote/checkout, lưu snapshot bất biến; thay đổi catalog không sửa lịch sử đơn. API kho TV4 là nguồn tồn khả dụng.
- **TV3 — giỏ, coupon, checkout, đơn, tiền và điều phối vận hành:** reservation/coupon cùng transaction; xác minh thu/hoàn thủ công; job COD và outbox/inbox. Không xác nhận đã thu/hoàn từ browser, cache hay lời gọi provider chưa được đối soát.
- **TV4 — kho, giao/lắp, trả hàng:** nhập/điều chỉnh/chuyển/xuất kho, vận đơn, bằng chứng giao và lắp, duyệt/nhận/nhập lại hàng trả. TV3 chỉ cho hoàn đơn còn hoạt động khi phiếu trả cùng đơn đã duyệt và thực nhận đủ bằng chứng. Package bridge aftercare dùng chung order-root lock và completion policy, không thay thế toàn bộ module bảo hành/đặt lịch của TV4.
- **TV5 — nội dung, tương tác, AI và phân tích/đo tải:** nội dung/tracking/AI; số doanh thu lấy khoản thu thành công trừ hoàn thành công. Đo tải dùng dataset/điều kiện/số liệu thực; cache catalog/nội dung thuộc chủ module. Không cache quyết định tiền, quyền, coupon, tồn hoặc trạng thái đơn.

Thứ tự khóa dùng chung: user theo ID tăng → order root → các dòng nghiệp vụ theo thứ tự cố định → kho/số dư theo contract TV4. API tiền, expiry và fulfillment cùng khóa order root; không gọi mạng ngoài khi đang giữ khóa DB. Audit/outbox thất bại làm rollback nghiệp vụ.

## 2. Môi trường và migration

Yêu cầu: JDK 17 trở lên; Node `^22.12.0 || >=24.0.0`; MariaDB tương thích schema (phiên đo dùng 12.2.2); Chrome cài sẵn cho Playwright. Python 3 dùng cho helper/smoke/load. Maven wrapper ở root. Không cần JWT/Gemini/Redis/SMTP để chạy commerce hiện tại.

V001–V007 giữ nguyên. Áp dụng tuần tự:

1. `V001__create_furniture_shop.sql`: bootstrap **45 bảng**, seed 3 vai trò và kho MAIN; chỉ chạy khi DB chưa tồn tại.
2. V002 request fingerprint; V003 idempotency vận đơn; V004 index audit vận đơn; V005 lựa chọn lắp đặt; V006 ghi chú xác minh khoản thu; V007 ghi chú xác minh/hủy hoàn tiền. Đây là migration cộng thêm, vẫn 45 bảng.
3. `V008__commerce_operations.sql`: deadline/retry COD và hai bảng `commerce_notification_outbox`, `commerce_notifications`; tổng **47 bảng**. Index phục vụ expiry/inbox/outbox; đọc báo cáo hiệu năng để biết index truy vấn danh sách thực tế đã được đánh giá.
4. `V009__commerce_read_indexes.sql`: ba index đọc `orders(placed_at,id)`, `payments(created_at,id)`, `payments(status,created_at,id)` có bằng chứng EXPLAIN, không thêm bảng; runtime đầy đủ dùng **V001–V009, vẫn 47 bảng**. Không sửa lại V008 đã áp dụng. HTTP p95 sau trial tăng 40,150 → 43,508 ms; đây không phải bằng chứng tăng tốc HTTP.

DB mới cho demo riêng:

```powershell
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = Read-Host 'Mật khẩu MariaDB cho terminal này'
python scripts/tv3_database.py create --database furniture_shop_tv3_dev
$env:TV3_DEMO_PASSWORD = Read-Host 'Đặt mật khẩu demo riêng, tối thiểu 10 ký tự'
python scripts/tv3_database.py seed --database furniture_shop_tv3_dev
Remove-Item Env:TV3_DEMO_PASSWORD
```

`create` từ chối ghi đè DB có sẵn. Với DB demo đã tồn tại, dùng `python scripts/tv3_database.py migrate --database furniture_shop_tv3_dev`, không chạy lại V001. Helper áp dụng toàn bộ migration đang được phiên bản repo hỗ trợ; xác nhận đã gồm V009 trước khi khởi động bản này. DB chính chỉ áp dụng SQL cộng thêm sau backup/kiểm tra đúng DB; helper fixture/load không được trỏ vào DB chính. Không có Flyway tự động baseline hay Hibernate tự sửa schema trong commerce.

Seed riêng tạo `tv3.admin` (ADMIN), `tv3.customer` (CUSTOMER), ba SKU nội thất và tồn MAIN; **Sofa Modena** có `assembly_required=true` để tái lập demo lắp đặt, **Bàn trà Oslo** giữ false để bảo toàn ca đã chọn dịch vụ nhưng sản phẩm không bắt buộc lắp. DB demo cũ chỉ cần cập nhật cờ catalog có chủ đích bởi owner; không seed/reset số dư hay lịch sử để bổ sung cờ này. Mật khẩu do người chạy cấp qua environment và được BCrypt; không có mật khẩu đăng nhập mặc định. Không chia sẻ secret thật qua tài liệu/Git. Dataset lớn cho đo tải chỉ nằm ở DB `furniture_shop_test_perf_ops_20261119`; không import nó vào DB demo lâu dài.

Các biến môi trường cần biết:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: datasource; DB_URL phải chọn rõ demo/test trong môi trường thử nghiệm.
- `PAYMENT_BANK_MODE`: `UNCONFIGURED` mặc định, chặn tạo lần BANK_TRANSFER mới; `DEMO` chỉ dùng DB cô lập và hiển thị tài khoản cố ý không hợp lệ; `MANUAL` cần `PAYMENT_BANK_NAME`, `PAYMENT_BANK_ACCOUNT_NUMBER`, `PAYMENT_BANK_ACCOUNT_HOLDER`. MANUAL cung cấp hướng dẫn, không kết nối ngân hàng.
- `ORDER_CONFIRMATION_TTL_SECONDS`: mặc định 86400 (24 giờ), phạm vi 60..604800. Đổi biến này không được backfill deadline đơn cũ.
- `COMMERCE_JOBS_ENABLED`: mặc định bật; đặt `false` cho integration test hoặc runtime đo tải cố định. Xem `/admin/operations` để biết trạng thái thực của từng job, không suy từ file env cũ.
- `ORDER_EXPIRY_ENABLED`, `ORDER_NOTIFICATIONS_ENABLED`: bật/tắt riêng từng worker, mặc định true; `COMMERCE_JOB_DELAY_MS` mặc định 10000. Khi cờ chung false thì scheduler không chạy cả hai; nút staff explicit vẫn thực thi đầy đủ chính sách.
- `CHECKOUT_SHIPPING_FEE=50000.00`, `CHECKOUT_FREE_SHIPPING_THRESHOLD=10000000.00`, `CHECKOUT_ASSEMBLY_UNIT_FEE=100000.00`: chính sách demo phía server, không phải báo giá hãng vận chuyển; giá đã gồm thuế.
- `TV3_TEST_DB_URL`: DB integration test dùng riêng. `TV3_DEMO_PASSWORD`: smoke/Chrome chạy demo, không phải biến cấu hình tài khoản app. Tên biến performance đọc trong [harness README](../tests/performance/tv3/README.md).

Spring không tự nạp `.env`; đặt environment trong đúng terminal/IDE. Không đặt secret trong `VITE_*`, query string, lệnh có mật khẩu literal hoặc file commit.

## 3. Khởi động và xác minh

Terminal backend tại root:

```powershell
$env:DB_URL = 'jdbc:mariadb://localhost:3306/furniture_shop_tv3_dev'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = Read-Host 'Mật khẩu MariaDB'
$env:PAYMENT_BANK_MODE = 'DEMO'
$env:COMMERCE_JOBS_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

Terminal frontend:

```powershell
cd frontend
npm ci
npm run dev
```

Backend mặc định `http://127.0.0.1:8080`; frontend `http://127.0.0.1:5173`. Vite proxy `/api` về backend; production cần reverse proxy cùng origin, fallback SPA, HTTPS/cookie phù hợp. Commerce scan package riêng, không khởi động legacy `EshopApplication` để kiểm thử schema mới.

Kiểm tra `GET /api/commerce/runtime` có `application=tv3-commerce`; chỉ demo/test có `isolatedData:true`. Đăng nhập thật, `GET /api/session` có vai trò đúng; `GET /api/csrf` cung cấp token cho mutation. Guest phải nhận JSON 401; customer gọi API admin nhận 403; tài nguyên người khác không đọc được. `/admin/operations` chỉ STAFF/ADMIN, hiển thị snapshot Hikari thật, trạng thái jobs, số outbox và hàng expiry cần xử lý. Không có actuator công khai chứa URL/secret.

Chạy kiểm thử khi không đang đo tải:

```powershell
python scripts/tv3_database.py create --database furniture_shop_test_tv3_handoff_20261119
$env:TV3_TEST_DB_URL = 'jdbc:mariadb://localhost:3306/furniture_shop_test_tv3_handoff_20261119'
$env:COMMERCE_JOBS_ENABLED = 'false'
.\mvnw.cmd '-Dtest=org.example.eshop.commerce.**.*Test' test
Remove-Item Env:TV3_TEST_DB_URL
python -m unittest discover -s tests/performance/tv3 -p 'test_*.py'
```

Giữ jobs tắt trong toàn bộ JVM kiểm thử, kể cả các Spring context được cache; không để scheduler nền giao thông báo khi suite khác đang dọn fixture. Trong terminal frontend: `npm test`, `npm run build`; khi cả server chạy DB demo riêng, đặt `TV3_DEMO_PASSWORD` rồi `npm run test:e2e`. `python scripts/tv3_http_smoke.py` là smoke mutation bổ sung có guard cô lập. Playwright dùng Chrome headless, một worker, không retry, không điều khiển tab người dùng. Tests integration có thể dọn fixture trong DB test: không dùng DB chứa lịch sử cần giữ. Bộ legacy/AI cần môi trường riêng; không tính một ca AI live skip thành pass tích hợp.

## 4. Demo COD → giao → thu → lắp → trả → hoàn

Chỉ demo chứng từ minh họa trong DB cô lập, không chuyển tiền thật. Hai browser context hoặc đăng xuất/đăng nhập lần lượt để thấy đúng ranh giới quyền.

1. **Khách:** `/cart` thêm SKU có tồn và cần lắp (seed mới dùng Sofa Modena); `/checkout` nhập địa chỉ, chọn COD và lắp đặt, xem quote rồi xác nhận tổng. Ghi lại mã đơn. Đơn lưu snapshot, giữ tồn/coupon và có deadline COD 24 giờ. Nếu phản hồi chưa rõ, retry đúng key/body đang lưu theo tài khoản; không tạo đơn mới.
2. **Nhân viên:** `/admin/orders` tìm mã, đọc đơn/ghi chú nội bộ; xác nhận rồi chuyển PROCESSING. Xác nhận hợp lệ xóa deadline. Khách không đọc được ghi chú nội bộ. Thiếu bằng chứng giao/thu/lắp thì server từ chối COMPLETED.
3. **TV4:** `/admin/inventory/dispatch` xuất từ kho/dòng reservation thật. `/admin/inventory/shipments` đánh dấu DELIVERED sau khi có xác nhận giao thực tế (demo minh họa). Xuất giảm on-hand và reserved, tăng fulfilled và ghi movement. Giao đủ COD chưa thu vẫn PROCESSING.
4. **Nhân viên thu:** `/admin/payments` đối chiếu khoản COD PENDING; xác nhận hai bước với mã chứng từ minh họa. Server giữ số tiền, nhân viên và UTC. Gửi lại đúng bằng chứng không thu lần hai. Không có nút khách tự đánh dấu đã thanh toán.
5. **Nhân viên lắp:** từ đơn mở `/admin/orders/{id}/aftercare`; chỉ shipment DELIVERED có chọn dịch vụ mới hoàn tất lắp. Nhập reference và xác nhận đã lắp; note/proof riêng không lộ khách. Khi đủ tiền/giao/xuất/lắp, completion policy chuyển COMPLETED cùng transaction. Retry đúng proof giữ người và thời điểm đầu.
6. **Khách trả hàng:** `/orders/{id}/returns` chọn lượng đã giao còn được trả, nhập lý do. REQUESTED chỉ giữ lượng yêu cầu, chưa cộng tồn hay hoàn tiền. Retry giữ key/body. Không thể trả vượt lượng, lặp claim hoặc đọc đơn khác.
7. **Nhân viên duyệt/nhận:** trên aftercare chọn APPROVE (hoặc REJECT với lý do riêng). Sau khi thực nhận, chọn kho đang hoạt động từ API thật, nhập từng dòng received và phần đạt điều kiện restock, xác nhận. Chỉ restock làm tăng on-hand và ghi RETURN movement; reserved không tăng. Retry đúng receipt không nhập lại lần hai. Không dùng chuyển DELIVERED → RETURNED của vận đơn để giả phiếu trả.
8. **Nhân viên hoàn từng phần:** mở liên kết hoàn tiền `/admin/orders/{id}/refunds`; chọn payment và phiếu đã RECEIVED, nhập số tiền trong ngân sách hàng thực nhận sau giảm. Phí giao/lắp không tự cộng. PENDING là yêu cầu, chưa trả tiền. Sau khi thực sự hoàn/đối chiếu (demo chứng từ minh họa), xác nhận reference hai bước. Payment gốc vẫn SUCCEEDED; không đổi đơn thành CANCELLED hay hồi sinh reservation.
9. **Khách thông báo/lịch sử:** `/notifications` đọc thông báo web đã deliver; mark-read chỉ tài khoản sở hữu, lặp giữ thời điểm đầu. Chi tiết đơn hiển thị trạng thái/tiền/phiếu trả công khai, không thấy proof, staff note, condition note hay người đối soát riêng.

Demo mất phản hồi: chặn/mất response ở trình duyệt sau request, tải lại, đăng nhập đúng actor rồi gửi lại exact attempt được lưu. Không tự đổi key/reference để thử lần nữa. Chỉ lỗi nghiệp vụ chắc chắn mới sửa bản nháp; 401/403/429/5xx/network/JSON không rõ phải giữ attempt để đối soát.

## 5. Demo expiry và vận hành thông báo

COD mới tổng >0, chưa xác nhận và chưa có bằng chứng giao/thu/hoàn mới đủ điều kiện hết hạn. Đơn cũ deadline NULL, đơn 0đ, BANK_TRANSFER/gateway/mixed payment không bị job tự hủy. Chuyển BANK_TRANSFER retry xóa deadline COD cũ. Browser chỉ hiển thị deadline, không quyết định giải phóng hàng.

Trong demo thường ngày dùng TTL 24 giờ đã chọn. Không sửa timestamp bằng SQL hoặc cung cấp endpoint force/backdate. Để minh họa trực tiếp trên **DB demo mới** có thể khởi động runtime riêng với TTL hợp lệ tối thiểu 60 giây trước khi tạo đơn COD mới; công bố rõ đây là cấu hình demo khác 24 giờ, sau đó khôi phục 86400. Đợi deadline rồi bấm chạy expiry trong `/admin/operations`; job vẫn recheck đủ chính sách. Integration dùng Clock injection cho expiry/race/rollback, không dùng sleep 24 giờ hay seed để giả E2E.

Hết hạn hợp lệ cùng transaction: order CANCELLED + lịch sử lý do công khai, payment PENDING → EXPIRED, trả reservation chưa xuất/lượt mã, audit hệ thống không giả user ID 0 và enqueue thông báo bền. Đơn poison dùng attempts/retry-after, không chặn toàn batch. Confirmation/dispatch/payment tranh chấp cùng order root; khoản thu thực sự đến muộn sau hủy giữ đơn CANCELLED và tạo yêu cầu hoàn đúng một lần.

Outbox là chuyển giao **nội bộ MariaDB → hộp thư web**, không gửi email/SMS. Một row lock/transaction insert inbox và đánh dấu SENT; unique outbox ID chống deliver trùng. Lỗi retry có backoff/attempts lưu DB, DEAD sau 5 lần. Nhân viên xem lỗi có kiểm soát rồi requeue DEAD với lý do; requeue có audit, không tạo thông báo đệ quy. Nút chạy thủ công trả scanned/delivered/expired/skipped/failed và không bypass guard/CSRF/quyền. Khi jobs tắt, nút explicit vẫn là thao tác staff được kiểm tra an toàn.

Không phá bảng, tắt DB hay sửa dữ liệu demo để biểu diễn lỗi. Dùng integration test failure injection cho insert inbox/audit rollback, nhiều worker, retry/restart và poison event. Redis không nằm trên đường commerce này: cache outage/hit-rate là **N/A**, không ghi “đã kiểm thử Redis fallback”.

## 6. Đo hiệu năng và bằng chứng bàn giao

[Harness](../tests/performance/tv3/README.md) chỉ loopback, session/CSRF thật, không lưu raw body/cookie/token/password. Dataset SQL lớn chỉ cho DB được tạo mới có tên chính xác đã cho phép; có kiểm tra `DATABASE()`, schema và lịch sử rỗng. Dữ liệu này phục vụ tải GET, không được gọi là bằng chứng đặt/thu/giao/hoàn E2E.

Đã đo trong quiet window root xác nhận sau khi mọi build/E2E dừng; [báo cáo 19/11](performance/TV3_READ_PERFORMANCE_2026-11-19.md) giữ dataset/hardware/worker/think-time/jobs/pool thực. Hai lượt cùng 1.000 đơn/3.000 dòng/1.003 SKU, mỗi lượt 4 workers × 50 rounds × 8 GET = 1.600 requests, 0 lỗi. P95 tổng **40,150 → 43,508 ms**, RPS **83,915 → 80,923** sau ba index; **không cải thiện HTTP latency**. EXPLAIN bỏ filesort ở các trang đầu được chỉ rõ, trang sâu OFFSET 900 vẫn full scan/filesort. Hikari 202 mẫu/lượt: max cấu hình 10, observed active cao nhất 3/awaiting 0, không suy max liên tục hay năng lực production. Kết quả 17/11 trên 23 đơn có thể trùng Vitest, không dùng làm phép so index/cache với dataset/profile hôm nay.

Kết quả đã xác nhận trong phiên bàn giao:

- **Java:** 180 tests, **179 pass / 1 skip** (live Gemini chưa cấu hình), không lỗi; [log cuối](../target/tv3-ops-full-final.log) báo BUILD SUCCESS lúc 10:48:46 ngày 19/11/2026. Surefire tắt workers tập trung; các Spring Boot context cũ có TestPropertySource tắt jobs để không tranh chấp fixture. Ca guard mặc định đã thấy RED trước khi sửa và GREEN sau sửa. Integration commerce dùng MariaDB test thật; tổng regression có môi trường legacy riêng, không nạp legacy lên schema commerce.
- **React:** **142/142 tests** trong **13 files**; build production **63 modules** thành công.
- **Schema:** **47 bảng**, **22 kiểm tra hành vi** với V008 đã pass, các fixture đều rollback; V009 chỉ thêm index, không thêm bảng. EXPLAIN trước/sau ghi metadata ba index đọc thực tế trên DB performance.
- **Performance harness:** **15/15 tests**, gồm loopback session/CSRF/redirect và guard/redaction/statistics; Python syntax và scoped whitespace checks pass. Hai lượt HTTP load **3.200/3.200 thành công**, 0 lỗi, báo cáo giữ cả p95 xấu hơn sau index như trên.
- **Chrome:** **7/7 pass trong 53,5 giây**, gồm 6 kịch bản cũ + kịch bản mới COD → xác nhận/xuất/giao/thu → lắp → khách trả một phần → duyệt/nhận/restock → hoàn thủ công → thông báo khách; [log cuối](../target/tv3-ops-e2e-final.log). Kịch bản mới chạy 10,3 giây, gọi API nghiệp vụ thật và UI trên Java/MariaDB demo, có mất phản hồi/reload/replay và kiểm tra privacy; không SQL để giả bằng chứng thu/giao/trả. Rerun dùng Modena cần lắp, Oslo không bắt buộc lắp, không reset tồn. Smoke HTTP riêng không cộng vào số test đã xác nhận này.

Các log trong `target/` là artifact cục bộ được Git bỏ qua; khi tái lập trên máy mới phải tạo log mới. Báo cáo performance JSON được giữ trong repository. Một ca live Gemini skip được ghi tách biệt, không tính là pass tích hợp AI/provider.

## 7. Xử lý sự cố và ranh giới còn lại

- **Schema validation/unknown column/table:** kiểm tra đúng DB_URL và V002–V009. Không chạy `ddl-auto=update`, V001 hoặc seed reset để chữa DB có lịch sử. Startup không tự migrate.
- **401/403/CSRF:** lấy session/CSRF mới cùng cookie jar; kiểm tra actor/role trong DB. Không tắt CSRF hay cho customer vào admin. Giữ nguyên thao tác chưa rõ kết quả qua đăng nhập lại.
- **409 checkout/state/stock:** tải state/quote server và đối chiếu trước khi đổi nội dung. Dòng đã xuất/giao không được hủy như chưa xuất. Không cập nhật trực tiếp orders/payments/inventory để qua điều kiện.
- **Không COMPLETED:** xem thiếu khoản thu ròng, giao từng dòng, movement xuất kho, reservation còn lại hoặc lắp đặt. DELIVERED không chứng minh đã thu COD; phí lắp bằng 0 không chứng minh không chọn dịch vụ.
- **Không có thông báo:** xem jobs enabled và outbox pending/retry/dead ở operations; chạy explicit nếu jobs tắt, kiểm tra lỗi/next attempt rồi retry DEAD có lý do. Không lộ body lỗi DB/ghi chú nội bộ cho khách.
- **Expiry không hủy:** kiểm tra deadline UTC, trạng thái, method/provider và bằng chứng giao/thu/hoàn; NULL không tự backfill. Không ép hủy khi có tiền/stock evidence; xử lý qua quy trình nhân viên.
- **Pool awaiting tăng:** giữ report và EXPLAIN đúng dataset/route/filter; kiểm tra transaction khóa dài và background load. Snapshot là mẫu thời điểm, không phải max liên tục; không tăng pool/index tùy tiện.
- **Chứng chỉ doanh nghiệp:** có thể dùng `NODE_OPTIONS=--use-system-ca` hoặc truststore Windows cho Maven trong terminal phù hợp. Không tắt TLS verification.
- **Legacy/AI:** chạy context/schema riêng; commerce không yêu cầu JWT/Gemini. Full Java regression dùng `LEGACY_TEST_DB_URL` riêng cho test legacy có guard và tạo/xóa entity schema; không đặt create-drop toàn cục. Module TV1/TV2/TV5 đầy đủ, đặt lịch/bảo hành TV4 và triển khai production vẫn cần nghiệm thu riêng; adapter/bridge không chứng minh các module đó đã hoàn thành. Workflow CI và công cụ recovery/demo nằm ở các tài liệu20/11 phía trên; không coi kiểm tra workflow cục bộ là một hosted run đã đạt.

**Đã hỗ trợ trong phạm vi không cần sandbox:** nghiệp vụ DB thật và UI quyền thật; chứng từ thu/hoàn do staff đối chiếu; COD expiry; web notifications; giao/lắp/nhận trả/restock; retry/idempotency và ngân sách hoàn. Demo trên DB cô lập có thể dùng chứng từ minh họa, phải nói rõ không có tiền thật.

**Chờ tích hợp có cấu hình riêng:** VNPAY/gateway sandbox thực, chữ ký callback/webhook, đối soát timeout/thu/hoàn tự động; SMTP/email có provider thật. Không có khóa ngân hàng/SMTP thì không tuyên bố gửi email, chuyển khoản hay hoàn online thành công. Không có Redis dependency trong commerce hiện tại; không cần cài Redis để demo phần này.

Cập nhật21/11: [hướng dẫn in đơn/lưu PDF và xuất CSV](TV3_IN_DON_VA_XUAT_CSV.md), [contract tính năng](api/TV3_ORDER_DOCUMENTS_CONTRACT.md), [OpenAPI 1.6.0 — 55 operations](api/tv3-commerce.yaml). Bản in dùng hộp thoại trình duyệt; CSV lấy toàn bộ bộ lọc staff đã áp dụng, giới hạn 5.000 đơn, ngày đặt tính theo UTC+07. Đợt này không thêm bảng/migration; kết quả kiểm thử mới chờ tích hợp xác nhận, không dùng số liệu19/11 ở trên thay thế.
