# TV3 React Commerce Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development when implementation is requested. This document is a proposed task backlog; it does not implement the application or authorize remote publishing.

**Goal:** Hoàn thiện phần TV3: giỏ DB, checkout, đơn, coupon, thanh toán và hoàn tiền bằng Spring Boot REST API + React trên schema đã chốt.

**Architecture:** TV3 sở hữu logic và 11 bảng thương mại, cung cấp DTO JSON cho React. Tích hợp tài khoản, SKU/giá và kho qua contract với TV1/2/4; transaction MariaDB quyết định tiền/tồn, trình duyệt và cache không quyết định chúng. Làm từng luồng backend–frontend–test, không viết toàn bộ backend rồi mới ghép giao diện.

**Tech Stack:** Spring Boot/JPA hiện có, MariaDB `furniture_shop`, React + Vite; Maven/JUnit, kiểm thử MariaDB thật/Testcontainers và React component/browser tests qua bộ công cụ chung do TV1 thiết lập.

**Spec:** `docs/PHAN_CONG_NHOM_5_THANH_VIEN.md`, `docs/TV3_BANG_VA_THUOC_TINH.md`, `docs/database/V001__create_furniture_shop.sql`.

## Global Constraints

- Thời hạn nhóm là 14 ngày; không phân lịch theo ngày trong tài liệu này.
- TV3 chỉ sở hữu `carts`, `cart_items`, `orders`, `order_items`, `order_addresses`, `order_status_history`, `coupons`, `coupon_redemptions`, `payments`, `payment_events`, `refunds`.
- Đây là đề xuất task/route/file, chưa phải chức năng đã triển khai. Không tạo/xóa database, scaffold frontend hoặc sửa Java trong bước viết backlog.
- Không tự viết lại User/Product/Inventory của thành viên khác; chỉ dùng service/DTO công khai đã thống nhất. Nếu dependency chưa có, dùng test double trong test; chức năng chỉ nghiệm thu sau khi tích hợp thật.
- Không tiếp tục làm Thymeleaf. React đặt ở `frontend/`; backend Maven giữ cấu trúc hiện có. Package đề xuất cho code TV3 là `org.example.eshop.commerce` với nhóm `cart`, `order`, `promotion`, `payment`, `port`.
- `BigDecimal`, snapshot đơn, UTC và các ràng buộc trong SQL là bắt buộc. Generated columns không ghi trực tiếp; không trả JPA Entity ra API.
- Dữ liệu tài chính/lịch sử không hard-delete. Không dùng trạng thái PAID cho orders; trạng thái thanh toán nằm ở payments.
- Sau mỗi task chạy test phù hợp, demo API + React và commit riêng; không công bố test pass chỉ từ thiết kế này.

## Review Focus

- Đơn của người khác/giỏ khách bị giả mạo: API từ chối và không lộ dữ liệu — Task 02/06/08/10.
- SKU/giá/coupon thay đổi giữa xem trước và đặt: tính lại từ DB, báo thay đổi để khách xác nhận — Task 04/05.
- Gửi lại cùng yêu cầu hoặc callback trùng/trái thứ tự: một hiệu ứng nghiệp vụ, cùng key khác nội dung bị từ chối — Task 05/09/10.
- Hủy/hết hạn đồng thời với thu tiền hoặc xuất hàng: không bán vượt tồn, không đánh dấu mất khoản thu thật — Task 06/09/11.
- Hoàn tiền hoặc dùng lượt coupon đồng thời vượt hạn mức: khóa/transaction và kiểm thử thực tế — Task 05/10/12.

## Quy tắc thực hiện mỗi task

1. Viết test nghiệp vụ theo mục nghiệm thu và chạy để thấy lỗi trước khi có implementation.
2. Làm Entity/Repository/Service/API và React của task, cập nhật contract OpenAPI/collection cùng thay đổi.
3. Chạy lại test Java/React và kiểm tra transaction MariaDB nếu có ghi dữ liệu; tích hợp dependency thật trước khi đánh dấu hoàn thành.
4. Demo một ca thành công và một ca lỗi, cập nhật hướng dẫn, commit Conventional Commits theo task.

## TV3-01 — Chốt contract tích hợp và môi trường chạy phần TV3

**Phụ thuộc:** phối hợp TV1/TV2/TV4; chưa cần viết toàn bộ 11 Entity.

**Files:** tạo `docs/api/tv3-commerce.yaml`; thống nhất với TV1 `frontend/src/lib/apiClient.js` và `frontend/src/features/`; contract backend dưới `src/main/java/org/example/eshop/commerce/port/`.

**Interfaces:** `CurrentActorPort` lấy user/guest đáng tin từ server; `CatalogPort` đọc SKU/giá/trạng thái theo batch; `InventoryPort` đọc/giữ/giải phóng hàng trong transaction chung; contract phí giao/lắp đặt, fulfillment state và yêu cầu hoàn tiền với TV4. Tên/phương thức cuối cùng được chốt trong OpenAPI/contract cùng chủ module trước khi code consumer.

- [ ] Chốt auth với TV1: cookie/session/CSRF hoặc JWT theo kiến trúc chung; CORS/proxy, JSON 401/403, quyền CUSTOMER/STAFF/ADMIN. SecurityConfig hiện tại không thể dùng nguyên trạng cho API mới.
- [ ] Chốt JSON request/response/lỗi, phân trang, mã lỗi 400/401/403/404/409; ID và principal không lấy tùy ý từ body. Chốt biểu diễn tiền trong JSON và không tính tiền bằng số thực JS.
- [ ] Chốt bản đầu checkout yêu cầu đăng nhập; giỏ khách vẫn được hỗ trợ và gộp khi đăng nhập. Đặt hàng guest là mở rộng riêng sau khi nhóm quyết định, không vô tình mở endpoint ẩn danh.
- [ ] Chuẩn bị DB test/seed hợp lệ với chủ bảng, môi trường React gọi được API và test lỗi xác thực. Không dùng password/API key thật trong fixture.
- [ ] Nghiệm thu: contract được các bên đọc thống nhất; xác định rõ cách chạy khi entity legacy chưa migrate. Không bật validate toàn app rồi coi lỗi do bảng cũ là lỗi của database mới.

## TV3-02 — Giỏ hàng DB + API + trang React

**Bảng:** `carts`, `cart_items`.

**Files:** `commerce/cart/CartEntity.java`, `CartItemEntity.java`, repository/service/DTO, `CartApiController.java`; `frontend/src/features/cart/CartPage.jsx`, `cartApi.js`.

**API đề xuất:** `GET /api/cart`, `POST /api/cart/items`, `PATCH /api/cart/items/{itemId}`, `DELETE /api/cart/items/{itemId}`, `DELETE /api/cart/items`, `POST /api/cart/merge`.

- [ ] Test `DbCartServiceTest`: giỏ tồn tại sau request mới; cùng SKU chỉ một dòng; quantity <= 0 bị từ chối; cập nhật đồng thời không làm mất lượng đã thêm; không sửa dòng giỏ của chủ khác.
- [ ] Implement giỏ tài khoản/guest, token guest ngẫu nhiên trong cookie an toàn và hash trong DB. Merge chỉ vào tài khoản hiện tại; giữ thông tin hàng vượt tồn và báo khách chỉnh, không âm thầm làm mất hàng.
- [ ] React: danh sách ảnh/tên/SKU/giá, tăng giảm, xóa/xóa hết, tổng tạm tính, trạng thái rỗng/lỗi/loading. Dữ liệu catalog/tồn do module khác cung cấp.
- [ ] Nghiệm thu: refresh vẫn giữ giỏ; thêm cùng SKU tăng số lượng; đăng nhập gộp đúng giỏ; giỏ chưa giữ tồn. Test controller gồm CSRF/auth theo contract TV1.

## TV3-03 — Quản trị coupon và tính giảm giá

**Bảng:** `coupons`.

**Files:** `commerce/promotion/CouponEntity.java`, repository, `CouponAdminService.java`, `CouponCalculator.java`, `CouponAdminController.java`; `frontend/src/features/promotions/CouponAdminPage.jsx`.

**API đề xuất:** `GET/POST /api/admin/coupons`, `PATCH /api/admin/coupons/{id}`, `DELETE /api/admin/coupons/{id}` chỉ khi chưa được tham chiếu.

- [ ] Test `CouponCalculatorTest`: FIXED/PERCENT, giảm tối đa, đơn tối thiểu, thời hạn, mã bị tắt; dùng Clock để kiểm thử biên thời gian.
- [ ] Làm danh sách/phân trang/tạo/sửa/tắt mã; coupon đã dùng được ngừng sử dụng, xóa cứng phải trả xung đột phù hợp khi FK chặn.
- [ ] Chốt cơ sở tính: coupon áp trên tiền hàng còn lại sau giảm dòng; phí giao/lắp/thuế không nằm trong cơ sở. Tổng giảm không vượt tiền hàng; quy tắc làm tròn theo tiền tệ được dùng chung toàn checkout.
- [ ] Nghiệm thu: khách thường không có quyền quản trị; tắt/sửa coupon không đổi snapshot và số tiền đơn cũ.

## TV3-04 — Checkout xem trước và form React

**Bảng đọc:** giỏ, coupon; SKU từ TV2, địa chỉ từ TV1, phí/tồn từ TV4.

**Files:** `commerce/order/CheckoutQuoteService.java`, `CheckoutQuoteRequest.java`, `CheckoutQuoteResponse.java`, `CheckoutApiController.java`; `frontend/src/features/checkout/CheckoutPage.jsx`.

**API đề xuất:** `POST /api/checkout/quote`.

- [x] Test `CheckoutQuoteServiceTest`: giỏ rỗng, SKU ngừng bán, thiếu hàng, địa chỉ không thuộc người mua, coupon không hợp lệ, phí do server tính và làm tròn nhất quán.
- [x] API trả các dòng, subtotal/giảm/phí/thuế/grandTotal, cảnh báo và dấu vết phiên bản/băm dữ liệu xem trước. Client không gửi giá hoặc tổng tiền có quyền quyết định.
- [x] React: chọn địa chỉ/nhập thông tin nhận hàng hợp lệ, ghi chú, mã giảm và phương thức thanh toán; hiển thị từng khoản tiền và lỗi theo trường.
- [x] Nghiệm thu: xem trước chưa tạo đơn/chưa giữ coupon/chưa giữ hàng. Quote không phải cam kết giữ giá hoặc tồn; thay đổi phải được kiểm tra lại khi đặt.

## TV3-05 — Tạo đơn thật, snapshot và chống gửi trùng

**Bảng:** `orders`, `order_items`, `order_addresses`, `order_status_history`, `coupon_redemptions`; tạo lần `payments` PENDING cho phương thức đã chọn qua service chung với Task 08.

**Files:** `commerce/order/OrderEntity.java`, `OrderItemEntity.java`, `OrderAddressEntity.java`, `OrderStatusHistoryEntity.java`, repository tương ứng, `CheckoutService.java`; `commerce/promotion/CouponRedemptionEntity.java`; `commerce/payment/PaymentEntity.java` và service tạo lần thanh toán chờ.

**API đề xuất:** `POST /api/checkout/orders` với `Idempotency-Key`.

- [x] Test `CheckoutTransactionIT`: rollback cả đơn/lượt mã/giữ hàng khi một SKU thất bại; cùng khóa/cùng request trả cùng đơn; không lộ đơn của tài khoản khác; cùng khóa/khác nội dung trả 409.
- [x] Tính lại từ DB và so với dữ liệu xem trước; nếu thay đổi giá/phí/coupon thì 409 kèm dữ liệu mới để khách xác nhận. Lưu snapshot đầy đủ, tiền đúng công thức và lịch sử ban đầu.
- [x] Dùng khóa/cập nhật có điều kiện cho lượt coupon và kho; giữ hàng qua InventoryPort trong cùng transaction, không ghi bảng kho trực tiếp. Chốt TTL/điểm APPLIED của coupon và release với TV4, thống nhất COD và online.
- [x] Lưu dấu vết request để chống tái sử dụng key cho nội dung khác. Nếu cần thêm cột request fingerprint vào orders, viết migration mới phối hợp TV1; không sửa bootstrap đã áp dụng.
- [x] Chuyển đúng giỏ sang CONVERTED và tạo giỏ mới khi cần; không xóa giỏ mới phát sinh của khách khi retry đơn cũ. Không gọi mạng/provider khi đang giữ transaction khóa hàng.
- [x] React: đặt hàng một lần, retry giữ nguyên key, xử lý xung đột và điều hướng đến đơn/luồng thanh toán. Disable nút chỉ là UX.
- [x] Nghiệm thu: tạo đơn thực tế, coupon và tồn đối chiếu được; COD vẫn là payment PENDING cho tới lúc thu tiền, không tự ghi PAID.

## TV3-06 — Đơn của khách và yêu cầu hủy

**Bảng:** `orders`, `order_items`, `order_addresses`, `order_status_history`; liên quan coupon/payment qua service.

**Files:** `commerce/order/CustomerOrderApiController.java`, `OrderQueryService.java`, `OrderCancellationService.java`; `frontend/src/features/orders/OrdersPage.jsx`, `OrderDetailPage.jsx`.

**API đề xuất:** `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders/{id}/cancel`.

- [x] Test `OrderAccessTest`/`OrderCancellationIT`: người A không đọc/hủy đơn B; hủy lặp không hoàn kho/lượt mã hai lần; chặn hủy khi đã xuất giao theo contract TV4.
- [x] Phân trang/lọc danh sách; detail dùng snapshot và trạng thái đơn/thanh toán/giao riêng. Không trả internal_note hoặc dữ liệu của người khác.
- [x] Hủy phần chưa thực hiện trong transaction, giải phóng giữ hàng và lượt mã; đơn đã thu tiền chuyển yêu cầu hoàn đến Task 10, không chỉ đổi status rồi bỏ quên khoản thu.
- [x] Nghiệm thu: React hiển thị lịch sử, chi tiết, trạng thái và quyền hủy đúng nghiệp vụ; không có nút xóa lịch sử đơn.

## TV3-07 — Quản trị đơn và chuyển trạng thái

**Files:** `commerce/order/AdminOrderApiController.java`, `OrderLifecycleService.java`; `frontend/src/features/orders/OrderAdminPage.jsx`, `OrderAdminDetailPage.jsx`.

**API đề xuất:** `GET /api/admin/orders`, `GET /api/admin/orders/{id}`, `POST /api/admin/orders/{id}/transitions`.

- [x] Test `OrderLifecycleTest`: trạng thái sai bị từ chối; người không có quyền không cập nhật được; mỗi chuyển trạng thái hợp lệ ghi lịch sử đúng một lần.
- [x] Viết ma trận chuyển trạng thái thay vì nhận tùy ý mọi enum. COMPLETED yêu cầu điều kiện giao/lắp/thu tiền phù hợp; sau khi xuất hàng, hoàn hàng đi qua hậu mãi thay vì hủy đơn như chưa giao.
- [x] Làm lọc/tìm kiếm/phân trang, ghi chú nội bộ, xác nhận/xử lý đơn; phối hợp audit TV1 và fulfillment TV4.
- [x] Nghiệm thu: admin quản lý được; không tái sử dụng delete() cũ vì đang hoàn kho/xóa lịch sử theo mô hình cũ.

## TV3-08 — COD và chuyển khoản có xác minh

**Bảng:** `payments` (Entity/khởi tạo PENDING đã có ở Task 05).

**Files:** `commerce/payment/PaymentService.java`, `PaymentApiController.java`, `PaymentAdminController.java`; `frontend/src/features/payments/PaymentPage.jsx`, `PaymentAdminPage.jsx`.

**API đề xuất:** `POST /api/orders/{id}/payments`, `GET /api/orders/{id}/payments`, `POST /api/admin/payments/{id}/confirm`.

- [x] Test `PaymentConfirmationTest`: customer không tự xác nhận; admin xác nhận lặp không thu hai lần; không vượt số còn phải trả; BANK_TRANSFER thay enum BANKING cũ. Có thêm tranh chấp hai nhân viên, hủy/xác nhận đồng thời và retry qua xuất kho thật.
- [x] Ghi phương thức/provider/số tiền/tiền tệ/trạng thái; xác minh chuyển khoản bằng người có quyền và thông tin đối soát. COD chỉ SUCCEEDED khi có xác nhận đã thu từ người/hệ thống được phép. DEMO giới hạn trong DB cô lập; không giả lập giao dịch ngân hàng thành công.
- [x] React hiển thị hướng dẫn chuyển khoản/trạng thái chờ/thất bại/hết hạn; màn hình nhân viên xác minh hai bước và lọc/tìm/phân trang. Không dùng endpoint /pay giả lập cũ. Giữ đúng request/proof qua mất phản hồi, lỗi JSON, đăng nhập lại và tải trang.
- [x] Nghiệm thu: chứng từ được ghi đúng một lần; số tiền/verified_by không do client tùy chọn; đơn 0 đồng không tạo payment amount=0. Chrome chạy xác nhận DEMO thật qua API/MariaDB cô lập, mất phản hồi rồi replay cùng bằng chứng, khách xem khoản thu và hủy tạo yêu cầu hoàn PENDING. Đây chưa phải kết nối ngân hàng hoặc thực hiện hoàn tiền.

## TV3-09 — Cổng thanh toán sandbox và webhook

**Bảng:** `payments`, `payment_events`.

**Files:** `commerce/payment/PaymentGateway.java`, adapter cho một provider đã chọn, `PaymentEventEntity.java`, `PaymentWebhookController.java`, `PaymentEventProcessor.java`; React trang kết quả trong feature payments.

**API đề xuất:** dùng endpoint tạo payment ở Task 08; webhook `POST /api/payments/webhooks/{provider}`. Path/method/thông số cuối cùng phải tuân theo contract chính thức của provider.

- [ ] Chọn một provider có credential sandbox, URL callback hợp lệ và khả năng hoàn tiền theo yêu cầu; đọc tài liệu chính thức/contract trước khi viết adapter. Không chốt thuật toán chữ ký hoặc URL từ trí nhớ.
- [ ] Test `PaymentWebhookTest`: sai chữ ký/sai amount/currency/order; event lặp; event FAILED retry; event đến trái thứ tự; provider timeout. Mô phỏng lỗi ở lớp HTTP và có ca chạy sandbox thật khi được cấp thông tin.
- [ ] Khởi tạo giao dịch qua mạng ngoài transaction giữ khóa; lưu/retry có trạng thái và idempotency. Xác thực sự kiện rồi xử lý payment/order có transaction.
- [ ] Thanh toán đến muộn sau hủy/hết giữ hàng vẫn phải ghi nhận khoản thu thật và đưa vào quy trình bồi hoàn/đối soát; không tự khôi phục đơn/tồn nếu chưa tái kiểm tra.
- [ ] Nghiệm thu: React đọc trạng thái từ backend, redirect thành công không tự chứng minh đã trả tiền; callback gửi nhiều lần chỉ một hiệu ứng. Giới hạn CSRF exception theo đúng webhook với TV1.

## TV3-10 — Hoàn tiền một phần/toàn phần

**Bảng:** `refunds`; đọc khoản thu và yêu cầu đổi trả hợp lệ qua TV4.

**Files:** `commerce/payment/RefundEntity.java`, `RefundService.java`, `RefundGateway.java`, `RefundAdminController.java`; `frontend/src/features/payments/RefundAdminPage.jsx`.

**API đề xuất:** `POST /api/admin/orders/{id}/refunds`, `GET /api/admin/orders/{id}/refunds`; luồng nội bộ nhận yêu cầu hợp lệ từ TV4 dùng cùng service, không đưa quyền hoàn tiền vào API customer.

- [ ] Test `RefundConcurrencyIT`: cùng key một refund; key khác nội dung bị từ chối; khoản thu 100, hai yêu cầu hoàn 60 đồng thời không cùng được chấp nhận; request sai đơn/return bị chặn.
- [ ] Khi kiểm tra hạn mức, tính cả hoàn SUCCEEDED và số tiền đang giữ cho refund PENDING để không đẩy hai lệnh vượt mức đến provider. FAILED/CANCELLED giải phóng phần giữ khi trạng thái đã được xác minh.
- [ ] Lưu provider reference/idempotency và xử lý timeout chưa rõ kết quả bằng đối soát/retry cùng key; không tạo lệnh mới để thử lại một khoản hoàn có thể đã thành công.
- [ ] React quản trị: số đã thu/đã hoàn/đang chờ/còn được hoàn, chọn số tiền và lý do, lịch sử trạng thái. Có đường MANUAL được ghi nhận/xác minh đúng; không ghi SUCCEEDED chỉ vì nhân viên bấm gửi yêu cầu online.
- [ ] Nghiệm thu: cả hoàn toàn phần/một phần có đối soát; payment gốc giữ lịch sử thu thành công; không tự cập nhật kho khi hoàn tiền.

## TV3-11 — Job hết hạn, thông báo và hiệu năng

**Files:** `commerce/order/OrderExpiryJob.java`, `commerce/payment/PaymentReconciliationJob.java`, handler thông báo; cấu hình/scheduler/outbox nền phối hợp TV1. Kịch bản tải đặt dưới `tests/performance/tv3/` theo công cụ nhóm thống nhất.

- [ ] Test `OrderExpiryRaceIT`: job chạy lại, job nhiều instance, webhook cùng lúc hết hạn/hủy; không release hàng đã xuất hoặc bỏ quên khoản đã thu.
- [ ] Chốt khóa/idempotency/retry cho job; email/reindex/hậu mãi cần trạng thái bền qua hạ tầng TV1. Không coi event chỉ nằm trong RAM là giao việc đảm bảo.
- [ ] Phân trang/projection/fetch có chủ đích cho đơn/admin; kiểm tra EXPLAIN theo truy vấn đo được. Không cache quyết định tiền, tồn, coupon hoặc quyền; cache frontend không phải nguồn dữ liệu để đặt đơn.
- [ ] Nghiệm thu: có số đo p95, lỗi, truy vấn và DB pool với cấu hình tải/máy/dataset ghi rõ; test Redis/provider lỗi. Không bịa số đo hoặc ghi tối ưu thành công chỉ vì thêm annotation/index.

## TV3-12 — Kiểm thử xuyên suốt và bàn giao

**Files:** test Java dưới `src/test/java/org/example/eshop/commerce/`, test React theo bộ công cụ TV1; E2E `frontend/e2e/tv3-commerce.spec.js`; hướng dẫn `docs/TV3_HUONG_DAN_CHAY_VA_DEMO.md`; contract/collection local không chứa secret.

- [ ] Luồng chuẩn: chọn SKU → giỏ → quote/coupon → đặt đơn → thanh toán → theo dõi; ghép với TV4 giao/lắp/đổi trả và kiểm tra hoàn tiền khi phù hợp.
- [ ] Luồng lỗi: hết hàng, giá đổi, coupon hết lượt, gửi lại, truy cập đơn người khác, thu/hoàn thất bại hoặc timeout, callback lặp/trái thứ tự, hủy khi đã xuất giao.
- [ ] Chạy backend/component/E2E/schema integration phù hợp; ghi chính xác test thực tế, test mock, test sandbox và phần chưa xác minh. Smoke test phải dùng schema mới với các module thực đã tích hợp, không chỉ legacy DB.
- [ ] Bàn giao biến môi trường mẫu, dữ liệu demo, API contract/collection, ảnh/kịch bản demo và danh sách dependency còn cần nhóm xử lý. Không commit password, JWT secret hoặc khóa provider.

## Điểm bắt đầu

Làm **TV3-01**, sau đó **TV3-02**. Thành quả đầu tiên là giỏ theo SKU lưu DB, React thêm/sửa/xóa được và test quyền sở hữu đạt. Tiếp theo là coupon → quote → đặt đơn; thanh toán và hoàn tiền được xây trên nền đó. Đây là thứ tự phụ thuộc, không phải lịch theo ngày.
