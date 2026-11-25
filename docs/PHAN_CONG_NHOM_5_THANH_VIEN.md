# Phân công đồ án website bán nội thất — nhóm 5 thành viên

## Phạm vi chung

- Thời hạn nhóm tự quản lý: 14 ngày. Tài liệu này phân công trách nhiệm, không chia lịch theo ngày.
- Frontend được người dùng chọn là **React**, thay cho Thymeleaf. Backend vẫn **Spring Boot REST API**, database MariaDB `furniture_shop`; khai thác đủ 45 bảng nghiệp vụ đã thống nhất.
- Đề xuất dùng React + Vite trong thư mục `frontend/`, giữ Maven/backend tại vị trí hiện có và tổ chức Java theo module nghiệp vụ. Mỗi người làm trọn Entity/Repository/Service/REST Controller, DTO/validation, React component/page và phần gọi API của mình, kiểm thử và tài liệu.
- React quản lý giao diện và route khách/admin; Spring Boot trả JSON, xử lý xác thực, phân quyền, validation, giá, transaction và dữ liệu. Trình duyệt không kết nối MariaDB hoặc Redis trực tiếp.
- Phần TV3-01/02/03 đã có React, REST API, giỏ DB và quản trị coupon trong runtime commerce riêng. Các luồng khác vẫn cần chuyển đổi; không coi việc chỉ xóa dependency Thymeleaf là hoàn thành frontend/API.
- Redis phục vụ cache dữ liệu đọc; MariaDB là nguồn dữ liệu quyết định về tiền, đơn và tồn kho. Không lấy giá/tồn từ cache để chốt đơn.
- Tái sử dụng có chọn lọc code hiện có. Java entity hiện tại chưa tương thích schema mới; việc chuyển đổi nằm trong từng phần việc dưới đây.
- Giao dịch trực tuyến tích hợp một cổng có sandbox thật. Demo cần phân biệt trạng thái sandbox với thu/hoàn tiền thật, không coi một nút giả lập là tích hợp thanh toán hoàn chỉnh.

## Thành viên 1 — Kiến trúc, tài khoản, bảo mật và triển khai

- Bảng sở hữu: `users`, `roles`, `user_roles`, `user_addresses`, `audit_logs`.

### Công việc

- Thiết lập cấu trúc package theo nghiệp vụ; các phần chung gồm cấu hình, JSON response/lỗi, DTO/validation, phân trang, thời gian UTC, logging. Dựng React/Vite, API client và layout dùng chung cùng TV2.
- Kiểm tra tương thích Java/Spring Boot/Spring Security/Spring AI hiện có; chuẩn hóa profile local/test/deploy và quản lý secret bằng environment.
- Tích hợp Flyway làm cơ chế migration duy nhất. Với database 45 bảng đã có, kiểm tra schema rồi baseline rõ ràng; với database trống, chạy migration tạo bảng. Không copy nguyên script có `CREATE DATABASE`/`USE` vào migration chạy trên datasource và không baseline tự động mọi database lạ.
- Tài khoản: đăng ký, đăng nhập/đăng xuất, xem/sửa hồ sơ, đổi/quên mật khẩu, sổ địa chỉ và địa chỉ mặc định.
- Quản trị tài khoản: tìm kiếm, khóa/mở, gán quyền ADMIN/STAFF/CUSTOMER; chặn tự leo thang quyền, truy cập dữ liệu của người khác.
- Chọn và ghi rõ một cơ chế xác thực cho React SPA; đề xuất Spring Security session qua cookie HttpOnly khi triển khai cùng origin. Nếu chọn JWT thì phải thống nhất lưu trữ, hết hạn, đăng xuất và làm mới; React không bắt buộc phải dùng JWT. Không trộn hai cơ chế không rõ phạm vi.
- Cấu hình React Router và API prefix `/api`, xử lý JSON 401/403 thay vì redirect HTML. Khi phát triển dùng Vite proxy cho API; khi triển khai dùng reverse proxy để phục vụ React và chuyển `/api` đến Spring Boot, có fallback route SPA. Nếu tách origin phải cấu hình CORS/credentials đúng; cookie authentication vẫn cần CSRF.
- Bảo mật: hash mật khẩu, CSRF cho web, cookie an toàn, kiểm tra quyền ở service/controller, giới hạn đăng nhập, giới hạn upload, không lộ secret/log nhạy cảm. Endpoint webhook chỉ miễn CSRF đúng phạm vi cần thiết và xác minh riêng.
- Audit: ghi người thao tác, đối tượng, hành động và thay đổi quan trọng; không log mật khẩu/token.
- Cấu hình Redis/Spring Cache dùng chung, timeout và đường xử lý khi cache lỗi. Chủ module quyết định dữ liệu cache, key, TTL và xóa cache tương ứng.
- Docker Compose cho backend/frontend/MariaDB/Redis và SMTP sandbox; cấu hình HTTPS/reverse proxy ở môi trường triển khai, health check, log, CI build/test cả Java và React, quản lý file upload bằng volume hoặc object storage, backup/khôi phục DB.
- Khởi tạo tài khoản quản trị qua cơ chế được kiểm soát, không hardcode mật khẩu mặc định.

### Đầu ra nghiệm thu

Môi trường dựng được từ hướng dẫn; phân quyền đúng; hồ sơ/địa chỉ chạy trên DB mới; quên mật khẩu có token dùng một lần và hết hạn; có CI, cấu hình secret, backup và kiểm chứng khôi phục. Bảng token xác thực kỹ thuật có thể cần bổ sung bằng migration, không lưu token vào bảng nghiệp vụ không phù hợp.

## Thành viên 2 — Catalog nội thất và trải nghiệm duyệt sản phẩm

- Bảng sở hữu: `categories`, `rooms`, `product_rooms`, `materials`, `brands`, `products`, `product_variants`, `variant_materials`, `product_images`, `product_specifications`, `collections`, `collection_products`.

### Công việc

- Quản lý danh mục nhiều cấp, không gian/phòng, thương hiệu, chất liệu và bộ sưu tập; chặn danh mục tự làm cha hoặc tạo chu kỳ.
- Sản phẩm và SKU: màu sắc, kích thước, trọng lượng, giá, giá tham chiếu, chất liệu theo bộ phận, bảo hành, yêu cầu lắp đặt và trạng thái kinh doanh.
- Upload/quản lý nhiều ảnh, ảnh riêng cho SKU, ảnh đại diện theo thứ tự, thông số và hướng dẫn bảo quản. Bỏ cơ chế ảnh công nghệ gắn cứng theo ID.
- Trang khách: danh sách sản phẩm, trang danh mục/phòng/bộ sưu tập, chi tiết sản phẩm, chọn SKU và gallery.
- Tìm kiếm từ khóa và bộ lọc giá, danh mục, màu, kích thước, chất liệu, không gian; sắp xếp và phân trang. Giá/tồn hiển thị theo SKU, lấy tồn qua API đọc của TV4.
- Trang quản trị tương ứng, validation dữ liệu, trạng thái ngừng bán thay cho xóa SKU đã có lịch sử.
- Chủ trì bộ React component dùng chung với TV1: typography, màu, form, card sản phẩm, breadcrumb, layout khách/admin, trạng thái loading/rỗng/lỗi, responsive mobile và keyboard accessibility cơ bản. Các thành viên còn lại dùng cùng bộ này cho màn hình của mình.
- Cung cấp dữ liệu catalog/giá SKU cho TV3 và TV5 qua service/DTO đã thống nhất; phát thay đổi catalog để cache/vector được cập nhật sau commit.
- Cache danh mục, không gian, thương hiệu, chi tiết catalog và truy vấn phổ biến có giới hạn; xóa cache sau cập nhật. Không đưa tồn quyết định mua vào cache catalog.
- Tối ưu SQL bằng projection/fetch có chủ đích, index theo truy vấn thực, kiểm tra EXPLAIN, tránh N+1 và tải toàn bộ danh sách vào bộ nhớ.

### Đầu ra nghiệm thu

Khách duyệt/lọc/chọn đúng SKU trên mobile; admin quản lý được toàn bộ catalog; sửa dữ liệu cập nhật đúng cache và phát tín hiệu reindex; danh sách có phân trang và không phát sinh N+1 rõ rệt. Có bộ dữ liệu/ảnh nội thất hợp lệ để demo.

## Thành viên 3 — Giỏ hàng, đơn hàng, khuyến mãi và thanh toán

- Bảng sở hữu: `carts`, `cart_items`, `orders`, `order_items`, `order_addresses`, `order_status_history`, `coupons`, `coupon_redemptions`, `payments`, `payment_events`, `refunds`.

### Công việc

- Giỏ hàng lưu DB cho khách/tài khoản, thêm/sửa/xóa SKU, gộp giỏ khi đăng nhập qua hợp đồng phối hợp với TV1.
- Checkout: chọn địa chỉ, ghi chú, phí giao/lắp đặt, áp mã giảm, tính tiền và tạo đơn bằng giá SKU hiện hành trong DB.
- Lưu snapshot tên/giá/SKU/chất liệu/kích thước/bảo hành và địa chỉ; mã đơn, khóa chống gửi đơn lặp, lịch sử trạng thái.
- Gọi dịch vụ giữ hàng của TV4 trong transaction tạo đơn; điều phối giải phóng giữ hàng khi hủy/hết hạn theo chính sách thống nhất. Không tự sửa các bảng kho.
- Khách xem lịch sử/chi tiết đơn, yêu cầu hủy theo trạng thái; admin tra cứu/lọc/xác nhận/xử lý đơn. Cập nhật trạng thái phải kiểm tra chuyển trạng thái hợp lệ.
- Coupon: thời gian hiệu lực, điều kiện giá trị đơn, phần trăm/số tiền, mức giảm tối đa, giới hạn sử dụng; giữ/sử dụng/giải phóng lượt mã trong transaction.
- COD, chuyển khoản có nhân viên xác minh và SePay Webhooks Test mode + API v2: khởi tạo giao dịch, trang kết quả, thất bại/hủy/hết hạn, webhook kiểm tra chữ ký/số tiền/tiền tệ và chống xử lý trùng.
- Hoàn tiền toàn phần/một phần từ yêu cầu hợp lệ của TV4 hoặc đơn hủy: khóa chống lặp ổn định, không hoàn vượt tổng đã thu, lưu kết quả thành công/thất bại.
- Phối hợp gửi email xác nhận đơn/thanh toán bằng cơ chế retry có lưu trạng thái; không giữ transaction khóa hàng trong lúc chờ mạng/cổng thanh toán.
- Tối ưu truy vấn lịch sử đơn/admin và xử lý concurrency; kiểm tra quyền sở hữu đơn. Không dùng cache để quyết định giá, quyền truy cập, số tiền, coupon hay trạng thái thu/hoàn tiền.

### Đầu ra nghiệm thu

Luồng mua từ SKU đến đơn/thu tiền chạy xuyên suốt; đơn không trùng khi gửi lại; không dùng lại coupon quá giới hạn; webhook lặp không thu/xử lý hai lần; hỗ trợ hoàn tiền một phần và chặn hoàn quá số đã thu. Thanh toán giả lập cũ được thay bằng luồng sandbox có xác minh.

## Thành viên 4 — Kho, giao/lắp đặt, đổi trả và bảo hành

- Bảng sở hữu: `warehouses`, `inventory_balances`, `inventory_reservations`, `inventory_movements`, `shipments`, `shipment_items`, `return_requests`, `return_items`, `warranty_requests`.

### Công việc

- Kho và số dư theo SKU/kho: nhập kho, điều chỉnh có lý do, chuyển kho, tra cứu lịch sử biến động. Hàng hóa được quản lý theo SKU, không theo mẫu sản phẩm.
- API nội bộ cho TV2/3: đọc tồn khả dụng, giữ hàng, giải phóng, xuất và nhập lại; khóa/cập nhật có điều kiện trong transaction, có idempotency.
- Giữ hàng: on-hand/reserved, xuất từng phần, hủy phần còn lại, job giải phóng hết hạn; định kỳ đối chiếu số dư với reservation/movement và báo sai lệch.
- Khi phối hợp checkout dùng transaction chung theo hợp đồng; tránh `REQUIRES_NEW` làm giữ hàng vẫn commit khi đơn rollback.
- Giao hàng: tạo nhiều đợt, chia dòng và số lượng theo kho, vận đơn, lịch/trạng thái giao, giao thất bại/hoàn về; không giao vượt đơn hoặc dùng giữ hàng của sai kho.
- Lắp đặt: lịch hẹn, nhân viên phụ trách, trạng thái và xác nhận hoàn tất.
- Đổi trả: khách gửi yêu cầu, nhân viên duyệt/từ chối, nhận/kiểm tra hàng; nhập lại phần đủ điều kiện; trao đổi với TV3 về hoàn tiền hoặc dòng đơn thay thế/chênh lệch.
- Bảo hành: kiểm tra người yêu cầu, số lượng, thời hạn theo snapshot và thời điểm giao; tiếp nhận, phân công, đặt lịch, xử lý, kết quả.
- Màn hình khách theo dõi giao/đổi trả/bảo hành và màn hình quản trị vận hành tương ứng.
- Tối ưu truy vấn tồn kho và các job theo batch; kiểm thử tranh chấp đồng thời, job chạy lặp, xuất từng phần, hủy/hoàn hàng. MariaDB quyết định tồn thật, không khóa tồn bằng Redis thay cho DB.

### Đầu ra nghiệm thu

Không bán vượt tồn; hủy/giữ/xuất/nhập lại đối chiếu được; một đơn giao nhiều lần đúng số lượng; hoàn trả/bảo hành có quyền và điều kiện rõ ràng. Yêu cầu hoàn tiền chỉ được xử lý bởi module thanh toán của TV3.

## Thành viên 5 — Tương tác, nội dung, AI, phân tích và đo hiệu năng

- Bảng sở hữu: `wishlist_items`, `reviews`, `review_images`, `tracking_events`, `banners`, `articles`, `chat_conversations`, `chat_messages`.

### Công việc

- Yêu thích, đánh giá của người đã mua, ảnh đánh giá, duyệt/ẩn và phản hồi của cửa hàng; xác minh chủ đơn và điều kiện đã giao.
- Banner và bài viết: quản trị, lịch hiển thị/xuất bản, ảnh, slug, nội dung an toàn; trang chủ dùng banner/bộ sưu tập/catalog của TV2, trang bài viết và trang yêu thích/đánh giá.
- Tracking: xem, tìm kiếm, lọc, giỏ, đơn; phân biệt tín hiệu client với sự kiện thành công do server xác nhận; không ghi thông tin nhạy cảm.
- Dashboard: doanh thu dựa trên thu thành công trừ hoàn tiền thành công, số đơn, bán chạy, tồn thấp qua API module, funnel hành vi. Không cộng tiền đơn chưa thu thành doanh thu đã nhận.
- Chat tư vấn nội thất: RAG từ catalog mới, lưu hội thoại/tin nhắn, xác thực chủ hội thoại, tư vấn theo ngân sách/kích thước/chất liệu/phòng. Giá và tồn lấy lại từ nguồn hiện hành trước khi trả lời.
- Di chuyển quy tắc gợi ý công nghệ sang nội thất; gợi ý cùng loại/mua kèm, cá nhân hóa theo hành vi, lọc hàng ngừng bán/hết hàng, phương án dự phòng khi AI lỗi.
- Chủ trì schema/index vector tương thích Spring AI và reindex. Indexing/email cần retry bền vững qua cơ chế TV1 cung cấp, không chỉ dùng event trong RAM rồi mất việc khi restart.
- Chặn lạm dụng AI bằng giới hạn request, độ dài hội thoại/context, timeout, ngân sách và retry có giới hạn; không gọi LLM đồng bộ trong transaction đặt hàng. Chưa có khóa AI phải hiển thị trạng thái đúng, không giả vờ tư vấn thật.
- Cache banner/bài viết public, số liệu tổng hợp và gợi ý; key cá nhân phải tách user/session, không dùng cache trả lời chứa giá/tồn cũ làm nguồn sự thật.
- Chủ trì kịch bản load test, báo cáo trước/sau cache và dashboard theo dõi: p95, error rate, DB pool, query count, cache hit/miss, AI latency. TV1 cung cấp Actuator/Micrometer và hạ tầng; từng chủ module sửa nút thắt phần mình.

### Đầu ra nghiệm thu

Toàn bộ nội dung/tương tác chạy được; AI tư vấn đúng dữ liệu và có đường dự phòng; gợi ý phù hợp nội thất; dashboard đối chiếu được doanh thu; có báo cáo hiệu năng đo thật, script/dataset/cấu hình máy tái lập được.

## Hợp đồng tích hợp và phần dùng chung

- TV1 chủ trì cấu trúc chung, `pom.xml`, security, cấu hình, CI và điều phối migration. Mỗi người viết Entity/migration/test cho bảng mình sở hữu; không sửa Entity/service của người khác ngoài PR phối hợp.
- TV2 chủ trì giao diện dùng chung và catalog API. TV3 điều phối checkout; TV4 là bên duy nhất ghi kho; TV3 là bên duy nhất xác nhận thu/hoàn tiền. TV5 không tự sửa số dư/đơn để làm dashboard.
- Phần khác gọi service/DTO công khai, không truy cập repository nội bộ. Các facade đọc có thể batch để tránh tạo N+1 giữa module.
- Cần chốt contract cho catalog-price, inventory reserve/release/ship, order-read, payment-result, return-refund và cache/catalog-change. Những bước liên quan đơn/tiền/tồn có transaction và khóa rõ ràng.
- Tác vụ nền quan trọng (email/reindex/yêu cầu hoàn tiền) cần trạng thái lưu bền, retry và chống lặp. Nếu chọn outbox, TV1 phụ trách nền và migration bảng kỹ thuật; chủ module phụ trách handler. Bảng token, outbox, Flyway history và vector là bảng kỹ thuật bổ sung, không nằm trong 45 bảng nghiệp vụ hiện có; tài liệu này chưa tạo chúng.
- TV1 + TV2 thiết kế React layout/component; ai làm chức năng thì làm luôn React page, API integration, validation hiển thị và loading/rỗng/lỗi. Route guard React phục vụ trải nghiệm; Spring Boot vẫn phải kiểm tra quyền độc lập. Không dồn toàn bộ frontend hoặc toàn bộ test cho một người.
- Mỗi PR có kiểm thử nghiệp vụ phù hợp và được một người khác review. Tách branch theo chức năng; tích hợp thường xuyên, không chờ hoàn thành riêng rồi mới ghép.

## Cache và hiệu năng: trách nhiệm cụ thể

- TV1: cấu hình Redis/Spring Cache, namespace, serializer, timeout, metrics, cấu hình lỗi; Actuator chỉ mở endpoint cần thiết và có kiểm soát truy cập.
- TV2: cache dữ liệu catalog public, pagination/projection/index, tối ưu ảnh và lazy-load; cập nhật catalog làm mất hiệu lực cache liên quan sau commit.
- TV3: transaction/locking và query đơn/coupon/payment; kiểm tra hành vi khi callback đồng thời, gửi lại và trạng thái không theo thứ tự.
- TV4: transaction/locking tồn, index hàng đợi hết hạn, batch job, retry/deadlock có giới hạn; job lặp không làm trừ/cộng kho hai lần.
- TV5: cache nội dung/gợi ý/tổng hợp, kiểm soát chi phí AI, load-test tổng thể và báo cáo. Mỗi thành viên tự sửa vấn đề hiệu năng thuộc module mình.
- TTL khởi điểm đề xuất: danh mục/phòng 15 phút, catalog public 2–5 phút, nội dung 5–15 phút, tổng hợp/gợi ý 1–5 phút; chỉnh bằng số đo thực. TTL không thay cho invalidation, và không phải cam kết dữ liệu luôn mới.
- Giới hạn số tổ hợp key tìm kiếm/phân trang, không cache mọi từ khóa tùy ý. Cache DTO thay vì Entity JPA. Cache lỗi có thể đi DB với timeout/giới hạn tải phù hợp; không trả thành công giả cho bước ghi tiền/tồn.
- Khi dữ liệu cache có thể cũ (ví dụ Redis tạm ngừng đúng lúc invalidation), checkout vẫn kiểm tra DB; dùng TTL giới hạn độ cũ hiển thị và có cơ chế phục hồi/xóa key khi cần.

## Tiêu chí nghiệm thu chung để nộp đồ án

- Dựng môi trường từ repository và hướng dẫn, migration đúng cả database trống lẫn database đã baseline; seed nội thất và tài khoản demo được quản lý riêng.
- Chạy được khách duyệt SKU → giỏ → coupon → đặt đơn → thanh toán → giao/lắp đặt → đánh giá; nhánh hủy/đổi trả/hoàn tiền/bảo hành có kiểm tra điều kiện.
- Bảo mật quyền sở hữu: người A không đọc/sửa đơn, địa chỉ, hội thoại hoặc yêu cầu hậu mãi của người B; STAFF không tự gán ADMIN.
- Kiểm thử tranh chấp SKU cuối, callback gửi lặp, hoàn tiền đồng thời vượt hạn mức, coupon hết lượt, job giữ hàng chạy lại, Redis/AI/provider lỗi. Không mất đối chiếu tiền và tồn.
- Test tích hợp MariaDB thật/Testcontainers cho transaction và ràng buộc; test trình duyệt các luồng khách/admin quan trọng. Mỗi chủ module chịu trách nhiệm test của mình.
- Báo cáo hiệu năng có tải/dataset/máy và kịch bản cụ thể, đo cold/warm cache, p50/p95, lỗi, SQL và hit rate. Mục tiêu thử ban đầu có thể là 50 người dùng ảo, 10 phút, p95 API duyệt catalog <500 ms và lỗi ngoài dự kiến <1%; đây là mục tiêu đề xuất, chưa phải kết quả đạt được, cần điều chỉnh theo máy demo. Đo AI riêng.
- Có backup + thao tác khôi phục đã thử, `.env.example` không có secret, tài liệu kiến trúc/ERD/API/chức năng/test, đóng góp từng thành viên và kịch bản demo rõ ràng.
- Phạm vi này rộng cho 14 ngày. Phân công bao phủ đầy đủ, nhưng không tự chứng minh nhóm sẽ đạt mọi tiêu chí: nếu thiếu thời gian phải báo sớm để thống nhất phạm vi với cô; không ghi chức năng chưa chạy hoặc số đo chưa có là đã hoàn thành.

## Tài liệu kỹ thuật tham khảo

- Module và API nội bộ: [Spring Modulith fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html). Có thể áp dụng cách tổ chức module mà chưa cần thêm toàn bộ thư viện.
- Migration: [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
- Cache: [Spring Data Redis Cache](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html).
- Quan sát hiệu năng: [Spring Boot Actuator metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html).
- React/Vite: [Build a React app from scratch](https://react.dev/learn/build-a-react-app-from-scratch).
- Bảo vệ cookie authentication trong SPA: [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

## Cập nhật TV3 ngày25/11/2026

Nhóm chọn **SePay Test mode**, React + Spring Boot + MariaDB; không tiếp tục VNPAY. TV3 chịu trách nhiệm snapshot mã/người nhận, HMAC/raw-body, chống nhận trùng API/Webhook, hàng sự kiện cần kiểm tra có note bất biến, đối soát theo mã/UUID, worker lease/retry và hết hạn SePay có xác minh. COD24giờ vẫn độc lập. Hoàn Sandbox dùng intent/key bền vững, giữ ngân sách khi chưa rõ kết quả và chỉ gửi khi staff chủ động xác nhận; không được dùng nút thủ công để vượt lệnh provider đang chạy.

TV1 phối hợp quản lý secret/role/CI/backup và migration V010–V012; TV4 giữ bằng chứng xuất/giao/nhận trả trước khi TV3 hoàn. TV2 không sửa snapshot tiền của đơn cũ; TV5 tính thu ròng từ khoản thành công, không suy từ note duyệt sự kiện. Xem [hướng dẫn chạy](TV3_SEPAY_SANDBOX.md), [contract](api/TV3_SEPAY_SANDBOX_CONTRACT.md) và [bàn giao](TV3_BAN_GIAO_VA_DEMO.md). Kết quả Sandbox không thay cho nghiệm thu Production.
