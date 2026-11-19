# TV3 — số đo đọc và Hikari trên 1.000 đơn, 19/11/2026

Hai lần đo cùng workload đã thực hiện **3.200 GET thành công, 0 lỗi (0%)** trên DB test dùng riêng. Sau khi thêm ba index đọc, **p95 tổng tăng từ 40,150 lên 43,508 ms** (khoảng +8,36%); throughput giảm 83,915 → 80,923 request/giây. Không có bằng chứng cải thiện độ trễ HTTP từ lần đo này. EXPLAIN xác nhận index loại filesort ở ba dạng truy vấn trang đầu; trang sâu OFFSET 900 vẫn full scan/filesort. Không chạy lại để chọn số đẹp hơn.

## Bằng chứng gốc

- [Dataset SQL có guard](tv3-large-fixture-2026-11-19.json).
- [HTTP trước index](tv3-read-load-2026-11-19-before.json), [HTTP sau index](tv3-read-load-2026-11-19-after.json).
- [11 EXPLAIN trước](tv3-explain-2026-11-19-before.json), [11 EXPLAIN sau](tv3-explain-2026-11-19-after.json).
- [Harness, giới hạn và lệnh tái lập](../../tests/performance/tv3/README.md); [bàn giao/demo](../TV3_BAN_GIAO_VA_DEMO.md).

## Dataset và cô lập

Root tạo mới/migrate V001–V008/seed DB có tên chính xác `furniture_shop_test_perf_ops_20261119`. Helper từ chối mọi tên khác, cả `furniture_shop` và DB demo lâu dài `furniture_shop_tv3_dev`; kiểm tra `DATABASE()` thật, 47 base table, hai tài khoản seed, lịch sử đơn rỗng và chưa có prefix fixture trước khi ghi. Không xóa/reset DB, không tắt constraint; fixture DML nằm trong transaction. App đo ở `http://127.0.0.1:8081` được root khởi động với datasource DB này, xác nhận runtime cô lập trước mọi login. Helper EXPLAIN kiểm tra lại DB identity và MariaDB **12.2.2**.

Đây là **SQL test data được phép dùng riêng cho tải đọc**, không chứng minh checkout, thu tiền hoặc giao hàng thật. Dữ liệu gồm:

- 250 mẫu nội thất mới × 4 màu/SKU = 1.000 SKU thêm; cùng 3 SKU seed, tổng **1.003 variants**.
- **1.000 orders, 3.000 order_items**, 3 dòng snapshot/đơn, số lượng 1–3 mỗi dòng; nhiều mức giá, policy miễn giao mặc định và **333 đơn giảm coupon 10%** ở header, không tính giảm lặp vào dòng.
- Bốn nhóm trạng thái, mỗi nhóm 250: PENDING_CONFIRMATION, CONFIRMED, PROCESSING, CANCELLED. Trong mỗi nhóm có 200 COD và 50 MANUAL_BANK. PENDING_CONFIRMATION/CONFIRMED có tổng 500 payment PENDING; PROCESSING có 250 payment SUCCEEDED do fixture; CANCELLED có 250 payment CANCELLED. Không có gateway/network call.
- **3.000 reservations**, balance giữ khớp phần chưa release, **333 coupon_redemptions**. Cart thật của khách có **3 dòng / 6 đơn vị**, không còn baseline giỏ rỗng.
- 2 users, 1 cart, 0 refunds, 0 outbox và 0 inbox. Không tạo deadline, backdate hoặc scheduler delivery bằng fixture. Phần notification storage failure/retry được kiểm tra riêng bằng MariaDB integration, không suy từ dataset outbox rỗng này.

Ảnh không tải từ mạng. Mọi tiền/stock evidence trong fixture chỉ phục vụ dữ liệu đọc. Sau cả hai lượt, API tổng đơn/payment/filter/cart không đổi; helper SQL xác nhận 12 table counts. Đây là các summary quan sát được, không phải checksum toàn bộ lịch sử DB. Harness không sửa nghiệp vụ; login thật có tạo HTTP session. Không lưu mật khẩu/cookie/token/raw response vào report. DB chỉ dùng một lần; owner có thể xóa nguyên DB test sau khi giữ báo cáo, không có teardown phá dữ liệu tự động trong harness.

## Máy, scheduling và quiet window

Máy báo Windows 11 build 26200, Python **3.14.3**, **16 logical CPUs**, `Intel64 Family 6 Model 154 Stepping 3, GenuineIntel`. Log startup runtime 8081 ghi **Java 21.0.3**; source target Java 17. Không đo RAM vật lý, CPU utilization, disk I/O, nhiệt độ, GC hoặc tải nền của hệ điều hành; không suy tên CPU thương mại từ processor string.

Root xác nhận runtime 8081 READY và QUIETWINDOW sau khi Java/React đã chạy xong, không Maven, Playwright, npm test/build hay mutation browser trong hai lượt. Các agent chỉ đọc trong cửa sổ đo. Hai Spring runtimes, Vite và IDE vẫn ở cùng laptop. Runtime demo 8080 có scheduler polling nhưng không browser writes; vì vậy còn tải nền được công bố, không phải máy benchmark trống hoàn toàn.

Operations 8081 trả thực tế: `jobs.enabled=false`, `expiryEnabled=true`, `notificationEnabled=true`, TTL **86400 giây**. Hai cờ riêng vẫn true nhưng cờ chung false làm scheduler benchmark không chạy. Không dùng dòng ghi chú CLI thay cho giá trị thực endpoint.

Lượt trước: **03:38:32–03:38:52 UTC**, tức **10:38:32–10:38:52 Asia/Bangkok**; thời gian measured **19,066871 giây**. Lượt sau: **03:40:14–03:40:34 UTC** (**10:40:14–10:40:34 địa phương**); measured **19,771969 giây**. DDL trial index nằm giữa hai lượt. Không có build/E2E/migration khác chạy trong thời gian measured.

## Workload và kết quả HTTP

Mỗi lượt: **4 workers**, mỗi worker giữ session customer và staff riêng (8 cookie jars), **50 rounds**, 8 GET cố định theo thứ tự, **25 ms think time** sau mỗi request, timeout 10 giây, không retry. Mỗi endpoint có **200 attempts / 200 successes / 0 errors** mỗi lượt; tổng **1.600/lượt**. Hai vòng warmup/worker tạo **64 GET bị loại khỏi số đo** mỗi lượt. Runtime guard, login/CSRF, warmup và snapshot dataset không tính vào latency/throughput đo.

P95 trước → sau theo đúng route:

- `/api/cart`: **37,426 → 39,897 ms**.
- `/api/orders?page=0&size=10`: **33,998 → 38,857 ms**.
- `/api/admin/orders?page=0&size=10`: **39,832 → 38,897 ms**.
- `/api/admin/payments?page=0&size=10`: **39,696 → 40,339 ms**.
- `/api/admin/orders?page=9&size=100`: **45,208 → 50,710 ms**.
- `/api/admin/payments?page=0&size=100&status=PENDING`: **43,050 → 46,225 ms**.
- `/api/admin/orders?page=0&size=100&status=CANCELLED`: **38,502 → 43,044 ms**.
- `/api/admin/operations`: **33,943 → 37,106 ms**.

Tổng p50 **18,699 → 20,583 ms**, p95 **40,150 → 43,508 ms**, maximum **63,944 → 76,832 ms**, attempted RPS **83,915 → 80,923**. Mỗi route đóng góp **10,489 → 10,115 RPS**, chia theo cùng measured wall time, không phải tốc độ riêng khi route chạy độc lập.

Percentile nearest rank trên tất cả attempt, kể cả failure nếu có; latency client gồm HTTP connection/read/JSON parse và không gồm think time. Throughput gồm think/scheduling theo cửa sổ chung. urllib/connection behavior và Python là một phần máy sinh tải; không mô phỏng browser keepalive hay open arrival rate. Không có threshold capacity/SLO được tuyên bố trước để biến con số này thành “pass năng lực production”. Profile ngắn 4 workers khác mục tiêu lớn hơn 50 users/10 phút; không chứng minh tải bền/saturation.

## Hikari thật và giới hạn phép đo

Mỗi lượt có **202 snapshot** từ `GET /api/admin/operations` sau xác thực STAFF/ADMIN: trước + 200 request operations trong workload + sau. Chỉ giữ field số/boolean được allowlist, không giữ đơn expiry/dead-event/customer text. Trạng thái Hikari `available=true`; cấu hình **max=10, minIdle=10**, total luôn **10**. Hai đầu mỗi lượt active **0**, idle **10**, awaiting **0**. Trong mẫu, peak active **3**, peak total **10**, peak awaiting **0** ở cả hai lượt; không có dấu hiệu chờ pool trong mẫu đã quan sát. Đây không phải max liên tục hoặc chứng minh tuyệt đối không từng có một lượt chờ ngắn giữa mẫu. Operations request cũng là tải đo và có truy vấn DB.

Pending/retry/dead/sent đều **0** theo dataset; phép đo này không stress outbox đầy. Chưa đo thời gian acquire connection, server SQL execution time, query-count/request, network thật, provider latency hay cache-hit/miss. Redis không dùng trong commerce nên kiểm thử outage/hit-rate Redis **N/A**; không thêm cache trên quyết định tiền, tồn, coupon, quyền hoặc trạng thái.

## EXPLAIN trước/sau và ba index có cơ sở

Hai snapshot giữ toàn bộ SQL cố định, metadata index/cột/cardinality và **11 plans**. Projection/filter/order/limit/offset tương ứng OrderQueryService, AdminOrderQueryService, PaymentQueryService; chỉ EXPLAIN, không trả dữ liệu người nhận. Ước lượng `rows` không phải actual rows read hay query runtime. Không `ANALYZE TABLE`, hint, cache flush hoặc optimizer setting.

Trial chỉ thêm:

```sql
CREATE INDEX idx_orders_time ON orders(placed_at,id);
CREATE INDEX idx_payments_time ON payments(created_at,id);
CREATE INDEX idx_payments_status_time ON payments(status,created_at,id);
```

- **Admin order page 0/size 10:** trước `ALL`, estimated rows **1000**, `Using filesort`; sau `type=index`, `idx_orders_time`, estimated rows **10**, không filesort. Index khớp global `ORDER BY placed_at DESC,id DESC`; các index user/status đang có không có global-time leading column.
- **Admin payment page 0/size 10:** trước `ALL` **1000** + filesort; sau `idx_payments_time`, `type=index`, estimated rows **10**, không filesort. Khớp global `created_at,id`.
- **Payment PENDING page 0 size 10 và size 100:** trước `idx_payments_expiry(status,expires_at)`, estimated **500** + filesort; sau `idx_payments_status_time(status,created_at,id)`, vẫn estimated **500**, không filesort. Equality status và thời gian sắp xếp khớp index; index expiry cũ không cung cấp thứ tự created_at.
- **Order page 9/size 100/OFFSET 900:** trước và sau đều **ALL 1000 + filesort**. MariaDB không chọn index mới khi offset gần cuối dataset; không ép hint và không nói index đã chữa trang sâu. Nếu dataset tăng và trở thành bottleneck, cần benchmark pagination/keyset theo nhu cầu sản phẩm riêng; chưa thay API trong phiên này.
- **Customer order page/count** giữ `idx_orders_user_time`; **cancelled admin page** size 10/100 giữ `idx_orders_status_time`, không filesort trước/sau. Không thêm index trùng cho chúng. Count toàn bộ vẫn đọc index khoảng 1000 rows, không biến thành constant-time do index mới. Correlated item/payment/shipment reads vẫn dùng index theo order và join address dùng unique `(order_id,address_type)`.

Ba index có chứng cứ thay đổi plan cho những truy vấn chỉ rõ ở trên; root chấp nhận đưa vào migration cộng thêm **V009__commerce_read_indexes.sql**, không sửa V008 đã áp dụng. Đây là cải thiện **access plan**, chưa phải cải thiện HTTP latency. Không đổi policy tài chính hoặc cache. Chi phí ghi/lưu trữ index chưa benchmark vì workload là GET. Không suy hiệu quả production từ 1.000 orders.

Một route admin order trang đầu giảm p95 khoảng 0,935 ms, trong khi phần lớn route (kể cả cart/customer/operations không đổi SQL) tăng. Với đúng một cặp run, JIT/cache/OS/concurrency/network-client variance chưa được kiểm soát để quy nguyên nhân cho index. Do đó giữ cả số xấu hơn, không gán quan hệ nhân quả hoặc lặp thử để chọn kết quả tốt. Baseline 17/11 có 23 đơn, 2 workers và giỏ rỗng/khả năng trùng Vitest; không dùng nó làm phép so before/after cho dataset hôm nay.

## Công cụ, kiểm tra và tái lập

Đã đọc skill `postman:performance-testing` phiên bản plugin 1.5.0, kiểm tra CLI thực **Postman 1.73.0** bằng `postman performance run -h`. CLI có local runner nhưng yêu cầu collection ID/environment ID; không có collection cloud đã chuẩn bị cho fixture loopback/session/CSRF này. Dùng lại harness stdlib có giới hạn volume/route, session isolation, redaction và operations telemetry sẵn có; không init workspace, signup, upload credential hay gọi cloud load chỉ để đổi tên công cụ. Không gọi kết quả này là Postman hosted test.

Chuẩn bị fixture một lần bằng lệnh README trước login; root dùng credentials transient environment, không lưu file. Sau khi runtime 8081 đúng DB đã READY, jobs tắt và quiet window được xác nhận:

```powershell
python tests/performance/tv3/read_load.py `
  --base-url http://127.0.0.1:8081 --workers 4 --rounds 50 `
  --warmup-rounds 2 --think-ms 25 --timeout 10 `
  --scheduler-note 'Record actual runtime/background job state; endpoint values retained' `
  --db-pool-note 'Actual protected Hikari snapshots before/during/after' `
  --dataset-note 'Same explicitly disposable 1000-order SQL load fixture' `
  --window-note 'Record newly confirmed quiet window and remaining background processes' `
  --output docs/performance/tv3-read-load-reproduction.json
python tests/performance/tv3/explain_read.py `
  --database furniture_shop_test_perf_ops_20261119 --customer-id 2 `
  --output docs/performance/tv3-explain-reproduction.json
```

Customer ID **2** đã được root xác nhận cho fixture này; DB khác phải tra ID thật, không đoán. Các biến credential chỉ ở environment như README; không đưa giá trị vào command/file. Lặp trên repo đã có index sẽ cho trạng thái sau, không tự bỏ index để giả baseline mới. Nếu cần một baseline chưa index khác, owner phải chuẩn bị DB test mới qua quy trình/allowlist đã được duyệt, không sửa DB demo lâu dài.

Harness **15/15 tests pass**, gồm pure guard/statistics và temporary loopback HTTP CSRF/session/redirect/redaction/report checks; syntax compile và scoped `git diff --check` pass. Loopback protocol tests cần network permission, đã chạy qua shell được phép ngoài network sandbox; không gọi app/DB thật. Kiểm thử chức năng job/aftercare và Chrome flow thuộc bằng chứng bàn giao riêng, không được thay bằng fixtures GET ở đây.
