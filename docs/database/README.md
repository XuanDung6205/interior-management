# Database nội thất — furniture_shop

Schema bootstrap có 33 bảng nghiệp vụ chính và 12 bảng mở rộng, tổng cộng **45 bảng**. Runtime hiện tại có **49 bảng**: V008 thêm hai bảng thông báo, V013 thêm hai bảng vector/outbox; V014 chỉ thêm cột/index/FK. Registry dùng chung ở `scripts/tv3_schema.py` quy định thứ tự V001–V015 và tập tên bảng chính xác cho create/migrate, CI, release và recovery. Database được sử dụng là `furniture_shop`; database `eshop` cũ và các script khởi tạo/seed cũ đã được dọn theo yêu cầu.

## Kết nối bằng HeidiSQL

1. Mở phiên kết nối MariaDB cục bộ đang dùng: host `127.0.0.1`, port `3306`, user `root`; dùng mật khẩu đã nhập trong phiên của bạn.
2. Refresh danh sách database, chọn `furniture_shop` để xem bảng.
3. Không chạy lại bootstrap nếu database đã tồn tại. Câu `CREATE DATABASE` được cố ý viết không có `IF NOT EXISTS` để tránh áp dụng nhầm lên database cũ.

Mật khẩu không được ghi trong repository, SQL hay tài liệu này. Không tạo tài khoản admin mẫu với mật khẩu mặc định.

## Các file

- `V001__create_furniture_shop.sql`: bootstrap một lần, tạo database và 45 bảng, seed 3 quyền và 1 kho chính.
- `V002__orders_request_fingerprint.sql`, `V003__shipments_idempotency.sql`, `V004__shipment_audit_index.sql`, `V005__orders_assembly_requested.sql`: migration cộng thêm theo thứ tự sau V001; dùng các bảng đã có nên tổng số bảng vẫn là45. V005 lưu lựa chọn lắp đặt độc lập với phí.
- `V006__payments_confirmation_note.sql`: bổ sung ghi chú đối soát nội bộ cho khoản thu thủ công; không thay đổi dữ liệu tài chính cũ. Áp dụng sau V002–V005.
- `V007__refunds_verification_notes.sql`: hai cột ghi chú đối soát/hủy yêu cầu hoàn thủ công, chỉ nhân viên đọc; không ghi đè lịch sử khoản thu/hoàn.
- `V008__commerce_operations.sql`: ba cột hạn xác nhận/retry trên orders và hai bảng `commerce_notification_outbox`, `commerce_notifications`. Runtime có47bảng; không backfill hạn tự hủy cho đơn cũ.
- `V009__commerce_read_indexes.sql`: ba index phục vụ danh sách đơn/khoản thu đã đo bằng EXPLAIN; không đổi số bảng.
- V010/V011/V012: snapshot thanh toán SePay, đối soát/lease/retry và ý định hoàn; không thêm bảng hay lưu khóa provider.
- `V013__tv5_vector_and_reindex_outbox.sql`: thêm `vector_store` (`VECTOR(768)`, cosine index) và `commerce_vector_reindex_outbox`; tổng49bảng. V001/V013 đã áp dụng được giữ nguyên.
- `V014__commerce_vector_runtime.sql`: metadata catalog/fingerprint/model, FK cascade cho catalog vector và lease/retry cho outbox; không thêm bảng. Runtime cần đủ **V001–V015**, theo đúng registry.
- `V015__catalog_management.sql`: thêm version chống mất cập nhật sản phẩm và các index truy vấn catalog; không thêm bảng, giữ nguyên ID sản phẩm/SKU và dữ liệu đơn/kho.
- `verify_schema.py`: kiểm tra tích hợp bằng MariaDB CLI, chỉ chấp nhận tên `furniture_shop` hoặc `furniture_shop_test_*`.
- `verification-fixture.sql`: dữ liệu kiểm thử bên trong transaction; script kiểm tra sẽ rollback, không phải dữ liệu mẫu để import.

## MariaDB và Redis cục bộ

`docker-compose.yml` cung cấp MariaDB 12.2 và Redis 7 cho môi trường phát triển. Service `migrate` của Compose tạo database demo mặc định `furniture_shop_tv3_dev`; schema chuẩn vẫn tên `furniture_shop`. MariaDB lưu dữ liệu trong named volume; các cổng chỉ publish trên loopback. Đặt `DB_PASSWORD` trong environment hoặc file `.env` cục bộ trước khi chạy `docker compose up -d mariadb redis`; không commit file `.env`. Compose hiện có service `migrate`, `app` và profile `demo`/`test`; xem [hướng dẫn TV2](../TV2_CATALOG_HANDOFF.md) để chạy toàn bộ trong Docker. Migration cộng thêm được chạy trước app; seed là bước chủ động riêng trên DB demo cô lập.

Commerce đọc Redis host/port từ `REDIS_HOST`/`REDIS_PORT` (mặc định `localhost:6379`, timeout `REDIS_TIMEOUT=1500ms`). `CommerceRedisCacheConfiguration` bật Spring Cache với `RedisCacheManager` và TTL mặc định 10 phút. `Tv5Cache` ưu tiên lưu JSON vào Redis (có invalidation theo generation key `tv5:gen:*`) và tự động chuyển sang bộ đệm RAM cục bộ trong 30 giây nếu Redis tạm ngừng kết nối.

Để import trên một máy khác, mở **File → Load SQL file** trong HeidiSQL, chọn file V001 và chạy toàn bộ khi `furniture_shop` chưa tồn tại. Đây là script bootstrap được giữ lại; các script khởi tạo database và cập nhật ảnh công nghệ cũ đã bị xóa.

Kiểm tra bằng PowerShell sau khi đặt `MYSQL_PWD` trong environment tạm thời:

```powershell
python docs/database/verify_schema.py --database furniture_shop
# Sau toàn bộ V001–V015, kiểm tra đúng49bảng:
python docs/database/verify_schema.py --database furniture_shop --operations
# Snapshot lịch sử V008–V012 dùng riêng --historical (đúng47bảng).
```

Script mặc định dùng client `C:\Program Files\MariaDB 12.2\bin\mariadb.exe`; có thể đổi bằng `--client`, `--host`, `--port`. Hủy environment chứa mật khẩu sau khi dùng. Chỉ chạy verification trên database development, lúc chưa có ứng dụng ghi đồng thời. Test dùng transaction rollback nhưng bộ đếm AUTO_INCREMENT vẫn có thể tăng; ID bị khuyết là bình thường.

## Danh sách 45 bảng

**Tài khoản (4):** `users`, `roles`, `user_roles`, `user_addresses`.

**Catalog (12):** `categories`, `rooms`, `product_rooms`, `materials`, `brands`, `products`, `product_variants`, `variant_materials`, `product_images`, `product_specifications`, `collections`, `collection_products`.

**Giỏ hàng/yêu thích (3):** `carts`, `cart_items`, `wishlist_items`.

**Kho (4):** `warehouses`, `inventory_balances`, `inventory_reservations`, `inventory_movements`.

**Đơn hàng (4):** `orders`, `order_items`, `order_addresses`, `order_status_history`.

**Khuyến mãi (2):** `coupons`, `coupon_redemptions`.

**Thanh toán/giao hàng (4):** `payments`, `payment_events`, `shipments`, `shipment_items`.

**Đổi trả/hoàn tiền/bảo hành (4):** `return_requests`, `return_items`, `refunds`, `warranty_requests`.

**Đánh giá (2):** `reviews`, `review_images`.

**Nội dung (2):** `banners`, `articles`.

**Hành vi/kiểm toán/chat (4):** `tracking_events`, `audit_logs`, `chat_conversations`, `chat_messages`.

**Hạ tầng thông báo bổ sung V008 (2):** `commerce_notification_outbox`, `commerce_notifications`. Outbox ghi cùng transaction nghiệp vụ; worker chuyển đến hộp thư web riêng của khách, có retry và chống gửi trùng. Chưa có gửi email/SMTP.

**Hạ tầng vector bổ sung V013 (2):** `vector_store`, `commerce_vector_reindex_outbox`. Chỉ index dữ liệu catalog công khai. `VECTOR_ENABLED=false` mặc định; khi tắt hoặc lỗi provider, chat/catalog tiếp tục dùng SQL. Key Gemini chỉ truyền bằng environment; không lưu trong schema. Xem [bàn giao/demo](../TV3_BAN_GIAO_VA_DEMO.md) và [backup/restore](../TV3_BACKUP_RESTORE.md).

## Quy ước và quyết định thiết kế

- MariaDB đã kiểm tra trên máy: **12.2.2**. Mọi bảng InnoDB, utf8mb4; PK `BIGINT` có dấu tương thích Java `Long`. Cột FK dùng cùng kiểu.
- Tiền `DECIMAL(15,2)` / Java `BigDecimal`, mặc định VND. Kích thước mm, cân nặng kg; không lưu chuỗi `200 x 80` vào các cột kích thước để lọc.
- `DATETIME(6)` lưu UTC theo quy ước ứng dụng. Bootstrap đặt timezone session UTC; JDBC và mọi job sau này cũng phải dùng UTC. Hiển thị giờ địa phương ở tầng giao diện.
- `products` là mẫu; mọi mẫu có ít nhất một SKU khi xuất bản, kể cả hàng không có tùy chọn. `product_variants` giữ giá bán, kích thước, màu và thời gian chuẩn bị hàng. Không nhân đôi giá/tồn trên `products`.
- `variant_key` là khóa tổ hợp do backend chuẩn hóa, ví dụ `cream-200-oak`; duy nhất trong một sản phẩm. Khóa này không tự sinh từ màu/kích thước/chất liệu.
- `compare_at_price` là giá tham chiếu để hiển thị giảm giá, không phải nguồn giá để tính đơn. Nó phải >= giá bán nếu có.
- Chất liệu hỗn hợp được biểu diễn bằng `variant_materials` theo bộ phận: khung, mặt, đệm. Thuộc tính cần lọc như kích thước nằm trong SKU; `product_specifications` dành cho thông tin bổ sung, không phải EAV tổng quát.
- `product_images` dùng đường dẫn/URL, không lưu bytes ảnh. Ảnh có SKU phải thuộc đúng sản phẩm nhờ FK ghép; ảnh đầu tiên theo `(sort_order,id)` làm ảnh đại diện. Không có ánh xạ cứng ảnh theo ID.
- Danh mục là cây, sản phẩm có một danh mục chính và nhiều không gian/bộ sưu tập. Backend phải chặn tự làm cha và chu kỳ tổ tiên. MariaDB không cho CHECK tham chiếu cột AUTO_INCREMENT để làm việc này.
- Mỗi user có tối đa một địa chỉ mặc định và một giỏ ACTIVE; khách có giỏ riêng bằng SHA-256 token, lưu dạng BINARY(32). Token gốc ngẫu nhiên gửi qua cookie; không dùng hash làm bearer token. Khi đăng nhập phải gộp giỏ trong transaction.
- Người dùng có nhiều quyền. Mã quyền DB `ADMIN/STAFF/CUSTOMER` cần ánh xạ sang Spring Security `ROLE_*`; không tương thích trực tiếp enum `ROLE_SELLER` cũ.
- `orders.user_id` nullable để cho phép đặt với tư cách khách nếu triển khai sau. Bản web đầu có thể tiếp tục yêu cầu đăng nhập. Snapshot người nhận/địa chỉ không phụ thuộc `user_addresses`.
- Dữ liệu đơn, giao dịch, biến động kho và lịch sử phải giữ lại. Catalog dùng trạng thái; không hard-delete SKU đã bán. Không có cascade xóa lịch sử tài chính.
- `order_items` giữ snapshot tên, SKU, ảnh, màu, kích thước, chất liệu, giá và bảo hành. Catalog thay đổi không làm đổi lịch sử; backend không cập nhật snapshot sau khi chốt đơn.
- `orders.subtotal` là tổng `quantity*unit_price` trước giảm; `discount_amount` gồm giảm trên dòng và giảm coupon toàn đơn. Tổng thanh toán = subtotal - tổng giảm + phí giao + phí lắp + thuế. `order_items.discount_amount` là giảm trên cả dòng, không phải trên một đơn vị. Coupon không tính lặp vào giảm trên dòng.
- `line_total` và tồn có thể bán là cột generated: JPA mapping cần `insertable=false, updatable=false`. Các cột generated để hỗ trợ unique cũng không được ghi từ ứng dụng.
- Một đơn dùng tối đa một mã giảm toàn đơn. Có thể có nhiều lần thanh toán, nhiều đợt giao, nhiều yêu cầu đổi trả/bảo hành. Hoàn tiền là bảng riêng, không đổi payment SUCCEEDED về FAILED.
- Mỗi lần gọi tạo đơn/thanh toán/biến động kho/hoàn tiền phải có idempotency key ổn định cho cùng một thao tác. Event thanh toán unique theo provider và mã event, không chỉ theo mã giao dịch.
- Một người mua chỉ đánh giá một lần cho mỗi dòng đơn. FK ghép kiểm tra người đánh giá là chủ đơn và sản phẩm thuộc dòng đó; backend kiểm tra đã giao/hoàn tất.
- `tracking_events` chỉ FK user; ID sản phẩm/SKU/đơn/danh mục là tham chiếu phân tích, không FK để giữ sự kiện quá khứ và tránh buộc pipeline tracking vào ghi catalog. Backend xác thực chúng; dữ liệu client không phải bằng chứng thanh toán.
- Chat có chủ sở hữu bằng user hoặc token khách, UUID công khai và thứ tự tin nhắn unique. Backend phải xác thực chủ hội thoại, không coi UUID là quyền truy cập.
- Trả hàng đổi sản phẩm có thể liên kết dòng đơn thay thế qua `replacement_order_item_id`. Backend kiểm tra cùng khách, chính sách đổi, chênh lệch tiền và tồn kho.

## Các bất biến bắt buộc ở tầng dịch vụ

Database đã chặn nhiều lỗi theo dòng, unique và FK ghép. Những điều sau cần code Spring Boot, chưa được tự động hóa bởi schema:

1. **Tồn kho:** giữ hàng trong transaction với khóa/cập nhật có điều kiện; cập nhật reservation và `reserved_quantity` cùng transaction. Tổng reservation còn hiệu lực phải khớp tồn đang giữ. Không giữ quá số lượng dòng đơn, không vượt tồn khả dụng.
2. **Xuất và hủy:** xuất bao nhiêu thì giảm cả on-hand và reserved bấy nhiêu, tăng fulfilled, ghi movement. Hủy chỉ giải phóng phần chưa xuất. Hàng trả chỉ tăng tồn sau khi nhận/kiểm định và quyết định nhập lại. Reservation hết hạn cần job giải phóng.
3. **Giao nhiều đợt:** tổng số lượng giao hợp lệ không vượt số đã đặt; tồn/giữ hàng phải thuộc kho của shipment. Khi hủy đợt giao phải phân biệt chưa xuất, đã xuất và hoàn về.
4. **Tiền:** tính tổng từ giá DB, phân bổ giảm/thuế với quy tắc làm tròn thống nhất; đối chiếu tổng dòng với header. Chặn tổng thu vượt tiền phải trả và tổng hoàn vượt số tiền đã thu thành công. Không tạo payment 0 đồng; đơn tổng 0 không cần bản ghi thu tiền.
5. **Callback:** kiểm tra chữ ký, provider, số tiền, tiền tệ, mã đơn; khóa payment/order; xử lý event theo idempotency và trạng thái hiện tại. Lưu event trùng không được làm mất khả năng retry event FAILED. Không xác nhận trả tiền chỉ từ trình duyệt.
6. **Coupon:** khóa coupon khi đếm lượt reserved/applied, kiểm tra thời gian/giới hạn/người dùng và giải phóng khi hủy/hết hạn. Coupon chỉ hợp lệ với số tiền áp dụng và code được snapshot; đơn khách không được dùng giới hạn mỗi user nếu chưa xác minh danh tính.
7. **Đổi trả/bảo hành:** giới hạn số lượng yêu cầu theo lượng đã giao và chưa trả; tính thời hạn theo thời điểm giao và bảo hành snapshot. Backend phân quyền customer/nhân viên, kiểm tra transitions và ghi lịch sử trong cùng transaction.
8. **Audit/nội dung:** log không chứa password, token hay khóa API; nội dung HTML và URL phải được kiểm tra ở backend. Audit là append-only theo quyền ứng dụng, không có trigger tự ghi hoặc quyền append-only cho tài khoản root.

## Chuyển project sang database mới

Cấu hình kết nối đã chuyển sang `furniture_shop`, có thể ghi đè bằng `DB_URL`; username lấy từ `DB_USERNAME` (mặc định `root`), mật khẩu lấy từ biến môi trường bắt buộc `DB_PASSWORD`. Không ghi mật khẩu trực tiếp vào source. SQL tự khởi tạo và vector schema tự khởi tạo đều đã tắt; Hibernate vẫn dùng `ddl-auto=none`.

Ứng dụng legacy dùng `JWT_SECRET` (Base64 của ít nhất 32 byte ngẫu nhiên). Runtime commerce mới dùng session/CSRF, không yêu cầu JWT/Gemini cho giỏ/coupon. `.env.example` liệt kê tên biến nhưng không chứa secret; Spring Boot không tự nạp file này, cần cấu hình environment trong terminal/IDE hoặc Docker Compose. File `.env` và `.env.*` cục bộ được Git bỏ qua.

Runtime `org.example.eshop.commerce.CommerceApplication` hiện có giỏ/coupon/checkout/đơn, thu và hoàn thủ công, kho/giao hàng, lắp đặt/đổi trả, hết hạn COD và hộp thư web. Maven mặc định chạy runtime này, chỉ scan commerce và validate các Entity của nó. Adapter đọc users/roles/SKU/tồn từ bảng thật. SePay Test mode có HMAC Webhook, đối soát API và thực thi hoàn Sandbox; Production chưa triển khai; code legacy vẫn giữ riêng ngoài commerce.

Hướng frontend là **React**, backend **Spring Boot REST API**. Source vẫn chứa Thymeleaf/MVC legacy ngoài runtime commerce. Xem `docs/TV3_HUONG_DAN_CHAY_VA_DEMO.md` để chạy phần TV3 và database demo cô lập. Không cần đổi schema chỉ vì đổi frontend.

V001 chỉ seed ADMIN, STAFF, CUSTOMER và kho MAIN. Chưa nhập dữ liệu từ `eshop`. Script demo riêng `scripts/tv3_database.py` tạo tài khoản và catalog trong DB cô lập; seed lại không reset tồn hiện có. Các lần verification rollback dữ liệu nghiệp vụ. Xem [bàn giao và demo](../TV3_BAN_GIAO_VA_DEMO.md) cho cấu hình và bằng chứng kiểm thử mới nhất.

Bảng vector được quản lý bằng migration đã phiên bản hóa, không dùng Spring AI tự tạo schema: V013 tạo `vector_store` native `VECTOR(768)` và `commerce_vector_reindex_outbox`; V014 thêm metadata catalog cùng lease/retry. Helper kiểm tra đúng49bảng, dimension768 và các cột V014. Runtime commerce mặc định tắt vector; [setup vector](../TV3_VECTOR_SETUP.md) hướng dẫn bật có chủ đích, private-config helper và giới hạn kiểm chứng provider. Query embedding chỉ dùng tạm thời; index bền vững chỉ chứa catalog công khai.

V001 là bootstrap ban đầu, chưa cấu hình Flyway/Liquibase trong project. Các thay đổi sau lần cài đầu phải dùng script phiên bản mới; không chạy lại V001, không dùng `ddl-auto=update` để thay migration có kiểm soát.

## Tài liệu MariaDB đã đối chiếu

- [Constraints](https://mariadb.com/docs/server/reference/sql-statements/data-definition/constraint)
- [Foreign keys](https://mariadb.com/docs/server/architecture/server-constraints/foreign-key-constraints)
- [DECIMAL](https://mariadb.com/docs/server/reference/data-types/numeric-data-types/decimal)
- [FOR UPDATE](https://mariadb.com/docs/server/reference/sql-statements/data-manipulation/selecting-data/for-update)
- [Vector overview](https://mariadb.com/docs/server/reference/sql-structure/vectors/vector-overview)

### V010 — SePay

`V010__sepay_payment_snapshots.sql` thêm 5 cột snapshot nullable và unique index cho mã DH trong `payments`, giữ nguyên 47 bảng tại thời điểm V010 và các khoản COD/chuyển khoản cũ. Không lưu token/secret. Script TV3 create/migrate hiện áp dụng registry V001–V015 cho DB demo/test. Sao lưu DB đang dùng trước khi áp dụng migration.

### V011 — đối soát, hết hạn và duyệt sự kiện

[V011__sepay_automation_review.sql](V011__sepay_automation_review.sql) thêm UUID giao dịch/tài khoản đã xác minh, lịch kiểm tra, lease/retry trên `payments`; note/actor/time duyệt bất biến trên `payment_events`. `expires_at` đã có từ bootstrap, chỉ lần SePay mới được đặt hạn. Không backfill đơn/lần thanh toán cũ. Kết quả tìm kiếm chưa đầy đủ hoặc API lỗi không được coi là không có tiền.

### V012 — ý định hoàn Sandbox

[V012__sepay_refund_execution.sql](V012__sepay_refund_execution.sql) thêm trạng thái thực thi, unique idempotency key/refund ID, snapshot giao dịch gốc, staff gửi, lease/retry/kết quả trên `refunds`. Lưu intent trước HTTP; UNKNOWN giữ ngân sách và khóa đường thủ công. Chỉ poll/retry intent đã được staff duyệt gửi. Tổng bảng vẫn47; không có khóa SePay trong DB. Xem [hướng dẫn](../TV3_SEPAY_SANDBOX.md) và [thuộc tính TV3](../TV3_BANG_VA_THUOC_TINH.md).
