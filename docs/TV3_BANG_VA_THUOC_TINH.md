# TV3 — Các bảng và thuộc tính theo schema hiện tại

Nguồn bootstrap: [V001__create_furniture_shop.sql](database/V001__create_furniture_shop.sql). Runtime bổ sung hai cột orders qua [V002](database/V002__orders_request_fingerprint.sql) và [V005](database/V005__orders_assembly_requested.sql); các định nghĩa bootstrap bên dưới được giữ nguyên để đối chiếu.

Bạn sở hữu 11 bảng: 2 giỏ hàng, 4 đơn hàng, 2 khuyến mãi và 3 thanh toán/hoàn tiền. Trách nhiệm gồm persistence/Service/REST Controller/DTO, React page/component và API integration liên quan, kiểm thử và tài liệu. Bootstrap có139 thuộc tính; hiện có144 sau V002, V005, V006 và V007. Frontend dùng React thay cho Thymeleaf.

Các cột orders bổ sung sau bootstrap:

- `request_fingerprint CHAR(64) ascii_bin NULL` (V002): hash nội dung đặt hàng để phát hiện dùng lại idempotency key với nội dung khác; đơn lịch sử có thể NULL.
- `assembly_requested BOOLEAN NOT NULL DEFAULT FALSE` (V005): khách có chọn dịch vụ lắp đặt, độc lập với phí và `order_items.assembly_required` (yêu cầu kỹ thuật của sản phẩm). Đơn cũ có phí lắp>0 được backfill TRUE.

Cột payments bổ sung: `confirmation_note VARCHAR(1000) NULL` ([V006](database/V006__payments_confirmation_note.sql)): ghi chú đối soát chỉ STAFF/ADMIN đọc được; không trả trong API khách hàng. Mã biên nhận nằm ở `provider_transaction_id`, người xác minh ở `verified_by`, thời điểm server xác minh ở `paid_at`; xác nhận lại cùng bằng chứng không ghi đè các trường này.

Các cột refunds bổ sung ([V007](database/V007__refunds_verification_notes.sql)): `confirmation_note VARCHAR(1000) NULL` lưu ghi chú chứng từ đã hoàn; `resolution_note VARCHAR(1000) NULL` lưu lý do hủy yêu cầu chưa gửi tiền. Chỉ STAFF/ADMIN đọc được. Mã chứng từ nằm ở `provider_refund_id`; `processed_by` ghi nhân viên xử lý và `completed_at` chỉ ghi khi xác minh hoàn thành. PENDING giữ ngân sách, không có nghĩa đã hoàn; CANCELLED giải phóng ngân sách yêu cầu nhưng không xóa nghĩa vụ còn phải hoàn. Payment gốc giữ SUCCEEDED.

## Quy ước đọc

- PK: khóa chính. FK: khóa ngoại. UNIQUE: không được trùng theo cột/cặp cột được chỉ định.
- Dòng định nghĩa SQL dưới mỗi thuộc tính giữ nguyên kiểu, NOT NULL, DEFAULT và biểu thức generated từ file nguồn. Nếu không có NOT NULL thì cột có thể NULL, trừ PK.
- BIGINT → Java Long; INT → Integer; DECIMAL(15,2) → BigDecimal; BOOLEAN → Boolean/boolean; BINARY(32) → byte[]. DATETIME(6) được quy ước lưu UTC.
- Với generated column (`active_user_id`, `active_guest_hash`, `line_total`), mapping JPA phải không insert/update trực tiếp.
- id được DB sinh; cột có DEFAULT trong SQL không có nghĩa JPA tự áp dụng default nếu gửi NULL. Service/Entity cần khởi tạo giá trị phù hợp hoặc chủ động để DB cấp.

## 1. `carts` — Giỏ hàng

Một giỏ thuộc tài khoản hoặc khách vãng lai.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`user_id`** — Tài khoản sở hữu, liên kết users của TV1. Định nghĩa: `BIGINT`.
- **`guest_token_hash`** — SHA-256 token nhận diện giỏ khách; chỉ lưu hash, không lưu token gốc. Định nghĩa: `BINARY(32)`.
- **`status`** — ACTIVE / CONVERTED / ABANDONED. Định nghĩa: `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`.
- **`expires_at`** — Thời điểm giỏ hết hạn. Định nghĩa: `DATETIME(6)`.
- **`active_user_id`** — Cột DB tự tính để bảo đảm mỗi user tối đa một giỏ ACTIVE. Định nghĩa: `BIGINT AS (CASE WHEN status='ACTIVE' THEN user_id ELSE NULL END) PERSISTENT`.
- **`active_guest_hash`** — Cột DB tự tính để bảo đảm mỗi token khách tối đa một giỏ ACTIVE. Định nghĩa: `BINARY(32) AS (CASE WHEN status='ACTIVE' THEN guest_token_hash ELSE NULL END) PERSISTENT`.
- **`created_at`** — Thời điểm tạo; DB gán CURRENT_TIMESTAMP(6) nếu không truyền. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`updated_at`** — Thời điểm cập nhật; DB tự cập nhật CURRENT_TIMESTAMP(6). Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_carts_active_user UNIQUE(active_user_id)`
- `CONSTRAINT uq_carts_active_guest UNIQUE(active_guest_hash)`
- `CONSTRAINT ck_carts_owner CHECK((user_id IS NOT NULL AND guest_token_hash IS NULL) OR (user_id IS NULL AND guest_token_hash IS NOT NULL))`
- `CONSTRAINT ck_carts_status CHECK(status IN ('ACTIVE','CONVERTED','ABANDONED'))`
- `CONSTRAINT fk_carts_user FOREIGN KEY(user_id) REFERENCES users(id)`
- `KEY idx_carts_expiry(status,expires_at)`

## 2. `cart_items` — Dòng giỏ hàng

Mỗi dòng là một SKU và số lượng, không phải một mẫu sản phẩm.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`cart_id`** — Giỏ chứa dòng hàng, liên kết carts. Định nghĩa: `BIGINT NOT NULL`.
- **`variant_id`** — SKU được chọn, liên kết product_variants của TV2. Định nghĩa: `BIGINT NOT NULL`.
- **`quantity`** — Số lượng mua, phải > 0. Định nghĩa: `INT NOT NULL`.
- **`created_at`** — Thời điểm tạo; DB gán CURRENT_TIMESTAMP(6) nếu không truyền. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`updated_at`** — Thời điểm cập nhật; DB tự cập nhật CURRENT_TIMESTAMP(6). Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_cart_items UNIQUE(cart_id,variant_id)`
- `CONSTRAINT ck_cart_items_quantity CHECK(quantity>0)`
- `CONSTRAINT fk_cart_items_cart FOREIGN KEY(cart_id) REFERENCES carts(id) ON DELETE CASCADE`
- `CONSTRAINT fk_cart_items_variant FOREIGN KEY(variant_id) REFERENCES product_variants(id)`

## 3. `orders` — Đơn hàng

Thông tin tổng của đơn; trạng thái đơn tách biệt trạng thái thanh toán/giao hàng.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`order_number`** — Mã đơn hiển thị cho khách, duy nhất. Định nghĩa: `VARCHAR(40) NOT NULL`.
- **`user_id`** — Người mua, liên kết users; có thể NULL nếu cho phép đặt với tư cách khách. Định nghĩa: `BIGINT`.
- **`idempotency_key`** — Khóa duy nhất cho một thao tác tạo đơn; gửi lại cùng yêu cầu phải dùng cùng khóa. Định nghĩa: `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL`.
- **`status`** — PENDING_CONFIRMATION / CONFIRMED / PROCESSING / COMPLETED / CANCELLED. Định nghĩa: `VARCHAR(30) NOT NULL DEFAULT 'PENDING_CONFIRMATION'`.
- **`currency`** — Mã tiền tệ, mặc định VND. Định nghĩa: `CHAR(3) NOT NULL DEFAULT 'VND'`.
- **`subtotal`** — Tổng quantity * unit_price của các dòng, trước toàn bộ giảm giá. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`discount_amount`** — Tổng giảm trên dòng cộng giảm coupon toàn đơn; không tính cùng một khoản hai lần. Định nghĩa: `DECIMAL(15,2) NOT NULL DEFAULT 0`.
- **`shipping_fee`** — Phí giao hàng. Định nghĩa: `DECIMAL(15,2) NOT NULL DEFAULT 0`.
- **`assembly_fee`** — Phí lắp đặt. Định nghĩa: `DECIMAL(15,2) NOT NULL DEFAULT 0`.
- **`tax_amount`** — Thuế được áp dụng theo nghiệp vụ đã thống nhất. Định nghĩa: `DECIMAL(15,2) NOT NULL DEFAULT 0`.
- **`grand_total`** — Tổng phải trả = subtotal - discount_amount + shipping_fee + assembly_fee + tax_amount. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`coupon_id`** — Mã giảm giá đã chọn, liên kết coupons. Định nghĩa: `BIGINT`.
- **`coupon_code_snapshot`** — Bản sao code coupon tại lúc đặt, để lịch sử không đổi khi sửa coupon. Định nghĩa: `VARCHAR(50)`.
- **`customer_note`** — Ghi chú khách hàng. Định nghĩa: `TEXT`.
- **`internal_note`** — Ghi chú nội bộ; không đưa ra giao diện/API khách. Định nghĩa: `TEXT`.
- **`placed_at`** — Thời điểm tạo/đặt đơn. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`confirmed_at`** — Thời điểm nhân viên xác nhận. Định nghĩa: `DATETIME(6)`.
- **`completed_at`** — Thời điểm hoàn tất. Định nghĩa: `DATETIME(6)`.
- **`cancelled_at`** — Thời điểm hủy. Định nghĩa: `DATETIME(6)`.
- **`cancel_reason`** — Lý do hủy. Định nghĩa: `VARCHAR(1000)`.
- **`updated_at`** — Thời điểm cập nhật; DB tự cập nhật CURRENT_TIMESTAMP(6). Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_orders_number UNIQUE(order_number)`
- `CONSTRAINT uq_orders_idempotency UNIQUE(idempotency_key)`
- `CONSTRAINT uq_orders_id_user UNIQUE(id,user_id)`
- `CONSTRAINT uq_orders_id_currency UNIQUE(id,currency)`
- `CONSTRAINT uq_orders_id_coupon UNIQUE(id,coupon_id)`
- `CONSTRAINT ck_orders_status CHECK(status IN ('PENDING_CONFIRMATION','CONFIRMED','PROCESSING','COMPLETED','CANCELLED'))`
- `CONSTRAINT ck_orders_amounts CHECK(subtotal>=0 AND discount_amount>=0 AND discount_amount<=subtotal AND shipping_fee>=0 AND assembly_fee>=0 AND tax_amount>=0 AND grand_total=subtotal-discount_amount+shipping_fee+assembly_fee+tax_amount)`
- `CONSTRAINT fk_orders_user FOREIGN KEY(user_id) REFERENCES users(id)`
- `CONSTRAINT fk_orders_coupon FOREIGN KEY(coupon_id) REFERENCES coupons(id)`
- `KEY idx_orders_user_time(user_id,placed_at)`
- `KEY idx_orders_status_time(status,placed_at)`

## 4. `order_items` — Chi tiết đơn

Lưu snapshot của SKU tại thời điểm mua; không đọc giá hiện hành để tính lại đơn cũ.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`order_id`** — Đơn chứa dòng hàng, liên kết orders. Định nghĩa: `BIGINT NOT NULL`.
- **`product_id`** — Mẫu sản phẩm đã mua; cùng variant_id được kiểm tra thuộc đúng sản phẩm. Định nghĩa: `BIGINT NOT NULL`.
- **`variant_id`** — SKU đã mua, liên kết product_variants của TV2. Định nghĩa: `BIGINT NOT NULL`.
- **`product_name`** — Bản sao tên mẫu sản phẩm. Định nghĩa: `VARCHAR(200) NOT NULL`.
- **`variant_name`** — Bản sao tên biến thể. Định nghĩa: `VARCHAR(200) NOT NULL`.
- **`sku`** — Bản sao mã SKU. Định nghĩa: `VARCHAR(80) NOT NULL`.
- **`image_url`** — Bản sao URL/đường dẫn ảnh. Định nghĩa: `VARCHAR(1000)`.
- **`color_name`** — Bản sao màu. Định nghĩa: `VARCHAR(80)`.
- **`dimensions_snapshot`** — Bản sao thông tin kích thước. Định nghĩa: `VARCHAR(200)`.
- **`materials_snapshot`** — Bản sao thông tin chất liệu. Định nghĩa: `TEXT`.
- **`warranty_months`** — Thời hạn bảo hành tại lúc mua, >= 0. Định nghĩa: `INT NOT NULL DEFAULT 0`.
- **`assembly_required`** — Hàng có yêu cầu lắp đặt tại lúc mua. Định nghĩa: `BOOLEAN NOT NULL DEFAULT FALSE`.
- **`quantity`** — Số lượng của dòng, > 0. Định nghĩa: `INT NOT NULL`.
- **`unit_price`** — Giá một đơn vị trước giảm trên dòng, >= 0. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`discount_amount`** — Giảm giá của toàn bộ dòng, không phải mỗi đơn vị; không vượt quantity * unit_price. Định nghĩa: `DECIMAL(15,2) NOT NULL DEFAULT 0`.
- **`line_total`** — DB tự tính = quantity * unit_price - discount_amount; không ghi trực tiếp từ JPA. Định nghĩa: `DECIMAL(15,2) AS (quantity*unit_price-discount_amount) PERSISTENT`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_order_items_id_order UNIQUE(id,order_id)`
- `CONSTRAINT uq_order_items_id_variant UNIQUE(id,variant_id)`
- `CONSTRAINT uq_order_items_id_order_product UNIQUE(id,order_id,product_id)`
- `CONSTRAINT ck_order_items_values CHECK(quantity>0 AND unit_price>=0 AND discount_amount>=0 AND discount_amount<=quantity*unit_price AND warranty_months>=0)`
- `CONSTRAINT fk_order_items_order FOREIGN KEY(order_id) REFERENCES orders(id)`
- `CONSTRAINT fk_order_items_variant FOREIGN KEY(variant_id,product_id) REFERENCES product_variants(id,product_id)`

## 5. `order_addresses` — Địa chỉ snapshot của đơn

Tách khỏi user_addresses để sửa sổ địa chỉ không làm đổi đơn đã đặt.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`order_id`** — Đơn hàng, liên kết orders. Định nghĩa: `BIGINT NOT NULL`.
- **`address_type`** — SHIPPING hoặc BILLING; mỗi đơn tối đa một địa chỉ cho từng loại. Định nghĩa: `VARCHAR(20) NOT NULL DEFAULT 'SHIPPING'`.
- **`recipient_name`** — Tên người nhận. Định nghĩa: `VARCHAR(150) NOT NULL`.
- **`phone`** — Điện thoại nhận hàng. Định nghĩa: `VARCHAR(30) NOT NULL`.
- **`email`** — Email liên hệ tại lúc đặt. Định nghĩa: `VARCHAR(254)`.
- **`address_line1`** — Địa chỉ chi tiết chính: số nhà, đường, địa điểm. Định nghĩa: `VARCHAR(255) NOT NULL`.
- **`address_line2`** — Địa chỉ bổ sung: căn hộ, tầng, tòa nhà... Định nghĩa: `VARCHAR(255)`.
- **`ward_name`** — Tên phường/xã. Định nghĩa: `VARCHAR(120)`.
- **`district_name`** — Thông tin quận/huyện nếu địa chỉ sử dụng. Định nghĩa: `VARCHAR(120)`.
- **`province_name`** — Tên tỉnh/thành phố. Định nghĩa: `VARCHAR(120)`.
- **`postal_code`** — Mã bưu chính nếu có. Định nghĩa: `VARCHAR(20)`.
- **`country_code`** — Mã quốc gia, mặc định VN. Định nghĩa: `CHAR(2) NOT NULL DEFAULT 'VN'`.
- **`company_name`** — Tên công ty nếu xuất thông tin thanh toán/hóa đơn. Định nghĩa: `VARCHAR(200)`.
- **`tax_code`** — Mã số thuế nếu có. Định nghĩa: `VARCHAR(40)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_order_addresses_type UNIQUE(order_id,address_type)`
- `CONSTRAINT ck_order_addresses_type CHECK(address_type IN ('SHIPPING','BILLING'))`
- `CONSTRAINT fk_order_addresses_order FOREIGN KEY(order_id) REFERENCES orders(id)`

## 6. `order_status_history` — Lịch sử trạng thái đơn

Thêm một dòng cùng transaction khi thay đổi trạng thái đơn.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`order_id`** — Đơn được thay đổi, liên kết orders. Định nghĩa: `BIGINT NOT NULL`.
- **`from_status`** — Trạng thái trước; NULL khi ghi sự kiện tạo đơn. Định nghĩa: `VARCHAR(30)`.
- **`to_status`** — Trạng thái sau; dùng cùng tập trạng thái của orders. Định nghĩa: `VARCHAR(30) NOT NULL`.
- **`changed_by`** — Người thay đổi, liên kết users của TV1; NULL cho hành động hệ thống. Định nghĩa: `BIGINT`.
- **`note`** — Lý do/ghi chú chuyển trạng thái. Định nghĩa: `VARCHAR(1000)`.
- **`changed_at`** — Thời điểm thay đổi. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT ck_order_history_from CHECK(from_status IS NULL OR from_status IN ('PENDING_CONFIRMATION','CONFIRMED','PROCESSING','COMPLETED','CANCELLED'))`
- `CONSTRAINT ck_order_history_to CHECK(to_status IN ('PENDING_CONFIRMATION','CONFIRMED','PROCESSING','COMPLETED','CANCELLED'))`
- `CONSTRAINT fk_order_history_order FOREIGN KEY(order_id) REFERENCES orders(id)`
- `CONSTRAINT fk_order_history_actor FOREIGN KEY(changed_by) REFERENCES users(id)`
- `KEY idx_order_history_time(order_id,changed_at,id)`

## 7. `coupons` — Định nghĩa mã giảm giá

Bản đầu áp dụng một mã toàn đơn, không có bảng phạm vi sản phẩm/danh mục.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`code`** — Code khách nhập, duy nhất. Định nghĩa: `VARCHAR(50) NOT NULL`.
- **`name`** — Tên chương trình nếu có. Định nghĩa: `VARCHAR(150)`.
- **`discount_type`** — PERCENT (phần trăm) hoặc FIXED (số tiền). Định nghĩa: `VARCHAR(20) NOT NULL`.
- **`discount_value`** — Giá trị giảm > 0; PERCENT không vượt 100. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`max_discount_amount`** — Số tiền giảm tối đa nếu có. Định nghĩa: `DECIMAL(15,2)`.
- **`minimum_order_amount`** — Giá trị đơn tối thiểu theo cơ sở tính nhóm thống nhất. Định nghĩa: `DECIMAL(15,2) NOT NULL DEFAULT 0`.
- **`total_usage_limit`** — Giới hạn tổng lượt; NULL là không giới hạn theo tiêu chí này. Định nghĩa: `INT`.
- **`per_user_limit`** — Giới hạn lượt mỗi tài khoản; NULL là không giới hạn theo tiêu chí này. Định nghĩa: `INT`.
- **`starts_at`** — Bắt đầu hiệu lực. Định nghĩa: `DATETIME(6) NOT NULL`.
- **`ends_at`** — Kết thúc hiệu lực, phải sau starts_at. Định nghĩa: `DATETIME(6) NOT NULL`.
- **`is_active`** — Được bật/tắt bởi người quản trị. Định nghĩa: `BOOLEAN NOT NULL DEFAULT TRUE`.
- **`created_at`** — Thời điểm tạo; DB gán CURRENT_TIMESTAMP(6) nếu không truyền. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`updated_at`** — Thời điểm cập nhật; DB tự cập nhật CURRENT_TIMESTAMP(6). Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_coupons_code UNIQUE(code)`
- `CONSTRAINT ck_coupons_discount CHECK(discount_value>0 AND ((discount_type='PERCENT' AND discount_value<=100) OR discount_type='FIXED'))`
- `CONSTRAINT ck_coupons_amounts CHECK(minimum_order_amount>=0 AND (max_discount_amount IS NULL OR max_discount_amount>0))`
- `CONSTRAINT ck_coupons_limits CHECK((total_usage_limit IS NULL OR total_usage_limit>0) AND (per_user_limit IS NULL OR per_user_limit>0))`
- `CONSTRAINT ck_coupons_dates CHECK(ends_at>starts_at)`

## 8. `coupon_redemptions` — Lượt giữ/sử dụng mã

Theo dõi coupon đã dùng cho đơn, thay vì chỉ lưu coupon_id trong orders.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`coupon_id`** — Coupon được sử dụng. Định nghĩa: `BIGINT NOT NULL`.
- **`order_id`** — Đơn áp mã; duy nhất, mỗi đơn tối đa một bản ghi sử dụng. Định nghĩa: `BIGINT NOT NULL`.
- **`user_id`** — Người mua; nếu có phải khớp chủ đơn. Định nghĩa: `BIGINT`.
- **`discount_amount`** — Số tiền giảm coupon thực tế cho đơn này, >= 0. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`status`** — RESERVED / APPLIED / RELEASED. Định nghĩa: `VARCHAR(20) NOT NULL DEFAULT 'RESERVED'`.
- **`expires_at`** — Hạn giữ lượt coupon. Định nghĩa: `DATETIME(6)`.
- **`redeemed_at`** — Thời điểm chính thức sử dụng. Định nghĩa: `DATETIME(6)`.
- **`created_at`** — Thời điểm tạo; DB gán CURRENT_TIMESTAMP(6) nếu không truyền. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_coupon_redemptions_order UNIQUE(order_id)`
- `CONSTRAINT ck_coupon_redemptions_amount CHECK(discount_amount>=0)`
- `CONSTRAINT ck_coupon_redemptions_status CHECK(status IN ('RESERVED','APPLIED','RELEASED'))`
- `CONSTRAINT fk_redemptions_coupon FOREIGN KEY(coupon_id) REFERENCES coupons(id)`
- `CONSTRAINT fk_redemptions_order_coupon FOREIGN KEY(order_id,coupon_id) REFERENCES orders(id,coupon_id)`
- `CONSTRAINT fk_redemptions_owner FOREIGN KEY(order_id,user_id) REFERENCES orders(id,user_id)`
- `KEY idx_redemptions_coupon_user(coupon_id,user_id,status)`
- `KEY idx_redemptions_expiry(status,expires_at)`

## 9. `payments` — Lần thanh toán

Một đơn có thể có nhiều lần thử thanh toán hoặc nhiều khoản thu.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`order_id`** — Đơn được thanh toán. Định nghĩa: `BIGINT NOT NULL`.
- **`method`** — COD / BANK_TRANSFER / GATEWAY; không dùng enum BANKING cũ. Định nghĩa: `VARCHAR(20) NOT NULL`.
- **`provider`** — Nhà cung cấp hoặc kênh xử lý được backend cấu hình. Định nghĩa: `VARCHAR(50) NOT NULL`.
- **`provider_transaction_id`** — Mã giao dịch bên nhà cung cấp; duy nhất trong mỗi provider khi có giá trị. Định nghĩa: `VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin`.
- **`idempotency_key`** — Khóa duy nhất ổn định cho một thao tác tạo lần thanh toán. Định nghĩa: `VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL`.
- **`amount`** — Số tiền của lần thu, phải > 0. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`currency`** — Tiền tệ, mặc định VND; phải khớp đơn. Định nghĩa: `CHAR(3) NOT NULL DEFAULT 'VND'`.
- **`status`** — PENDING / SUCCEEDED / FAILED / CANCELLED / EXPIRED. Định nghĩa: `VARCHAR(30) NOT NULL DEFAULT 'PENDING'`.
- **`paid_at`** — Thời điểm xác nhận thu thành công. Định nghĩa: `DATETIME(6)`.
- **`expires_at`** — Thời điểm giao dịch hết hạn. Định nghĩa: `DATETIME(6)`.
- **`verified_by`** — Nhân viên xác minh, liên kết users; có thể NULL khi cổng thanh toán xác nhận. Định nghĩa: `BIGINT`.
- **`failure_reason`** — Nguyên nhân thất bại nếu có. Định nghĩa: `VARCHAR(1000)`.
- **`created_at`** — Thời điểm tạo; DB gán CURRENT_TIMESTAMP(6) nếu không truyền. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`updated_at`** — Thời điểm cập nhật; DB tự cập nhật CURRENT_TIMESTAMP(6). Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_payments_provider_tx UNIQUE(provider,provider_transaction_id)`
- `CONSTRAINT uq_payments_key UNIQUE(idempotency_key)`
- `CONSTRAINT uq_payments_id_provider UNIQUE(id,provider)`
- `CONSTRAINT uq_payments_id_order_currency UNIQUE(id,order_id,currency)`
- `CONSTRAINT ck_payments_amount CHECK(amount>0)`
- `CONSTRAINT ck_payments_method CHECK(method IN ('COD','BANK_TRANSFER','GATEWAY'))`
- `CONSTRAINT ck_payments_status CHECK(status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED','EXPIRED'))`
- `CONSTRAINT fk_payments_order FOREIGN KEY(order_id,currency) REFERENCES orders(id,currency)`
- `CONSTRAINT fk_payments_verifier FOREIGN KEY(verified_by) REFERENCES users(id)`
- `KEY idx_payments_order_status(order_id,status)`
- `KEY idx_payments_expiry(status,expires_at)`

## 10. `payment_events` — Sự kiện/webhook thanh toán

Lưu sự kiện đến và trạng thái xử lý để chống trùng, theo dõi lỗi và retry.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`payment_id`** — Lần thanh toán được gắn; có thể NULL khi mới nhận/chưa ghép được giao dịch. Định nghĩa: `BIGINT`.
- **`provider`** — Nhà cung cấp gửi sự kiện; nếu payment_id có giá trị phải khớp provider của payment. Định nghĩa: `VARCHAR(50) NOT NULL`.
- **`provider_event_id`** — Mã sự kiện, duy nhất theo cặp (provider, provider_event_id). Định nghĩa: `VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin NOT NULL`.
- **`event_type`** — Loại sự kiện theo provider; không phải status đơn. Định nghĩa: `VARCHAR(80) NOT NULL`.
- **`payload`** — Payload JSON cần thiết cho xử lý/đối soát; không lưu secret hoặc dữ liệu thanh toán nhạy cảm. Định nghĩa: `JSON`.
- **`processing_status`** — RECEIVED / PROCESSED / FAILED / IGNORED. Định nghĩa: `VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'`.
- **`error_message`** — Lỗi xử lý nếu có. Định nghĩa: `VARCHAR(1000)`.
- **`received_at`** — Thời điểm backend nhận. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`processed_at`** — Thời điểm xử lý hoàn tất. Định nghĩa: `DATETIME(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_payment_events_provider_id UNIQUE(provider,provider_event_id)`
- `CONSTRAINT ck_payment_events_status CHECK(processing_status IN ('RECEIVED','PROCESSED','FAILED','IGNORED'))`
- `CONSTRAINT fk_payment_events_payment FOREIGN KEY(payment_id,provider) REFERENCES payments(id,provider)`
- `KEY idx_payment_events_processing(processing_status,received_at)`

## 11. `refunds` — Lần hoàn tiền

TV3 ghi và xử lý hoàn tiền; TV4 chỉ cung cấp yêu cầu hậu mãi hợp lệ.

- **`id`** — Khóa chính BIGINT tự tăng. Định nghĩa: `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`.
- **`order_id`** — Đơn được hoàn; phải khớp với payment. Định nghĩa: `BIGINT NOT NULL`.
- **`payment_id`** — Khoản thu gốc được hoàn. Định nghĩa: `BIGINT NOT NULL`.
- **`return_request_id`** — Yêu cầu đổi trả của TV4 nếu có; phải cùng đơn. Có thể NULL khi hoàn do hủy đơn. Định nghĩa: `BIGINT`.
- **`provider`** — Kênh hoàn tiền; mặc định MANUAL. Định nghĩa: `VARCHAR(50) NOT NULL DEFAULT 'MANUAL'`.
- **`provider_refund_id`** — Mã hoàn tiền bên provider; duy nhất trong mỗi provider khi có giá trị. Định nghĩa: `VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin`.
- **`idempotency_key`** — Khóa bắt buộc và duy nhất do backend cấp ổn định cho cùng thao tác hoàn; không sinh khóa mới mỗi lần retry. Định nghĩa: `VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL`.
- **`amount`** — Số tiền hoàn lần này, phải > 0. Định nghĩa: `DECIMAL(15,2) NOT NULL`.
- **`currency`** — Tiền tệ, mặc định VND; phải khớp khoản thu gốc. Định nghĩa: `CHAR(3) NOT NULL DEFAULT 'VND'`.
- **`status`** — PENDING / SUCCEEDED / FAILED / CANCELLED. Định nghĩa: `VARCHAR(20) NOT NULL DEFAULT 'PENDING'`.
- **`reason`** — Lý do hoàn tiền, bắt buộc. Định nghĩa: `VARCHAR(1000) NOT NULL`.
- **`processed_by`** — Nhân viên xử lý, liên kết users; có thể NULL cho hệ thống. Định nghĩa: `BIGINT`.
- **`completed_at`** — Thời điểm hoàn thành công. Định nghĩa: `DATETIME(6)`.
- **`created_at`** — Thời điểm tạo; DB gán CURRENT_TIMESTAMP(6) nếu không truyền. Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.
- **`updated_at`** — Thời điểm cập nhật; DB tự cập nhật CURRENT_TIMESTAMP(6). Định nghĩa: `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`.

**Ràng buộc và index trong SQL:**

- `CONSTRAINT uq_refunds_key UNIQUE(idempotency_key)`
- `CONSTRAINT uq_refunds_provider_tx UNIQUE(provider,provider_refund_id)`
- `CONSTRAINT ck_refunds_amount CHECK(amount>0)`
- `CONSTRAINT ck_refunds_status CHECK(status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED'))`
- `CONSTRAINT fk_refunds_payment FOREIGN KEY(payment_id,order_id,currency) REFERENCES payments(id,order_id,currency)`
- `CONSTRAINT fk_refunds_return FOREIGN KEY(return_request_id,order_id) REFERENCES return_requests(id,order_id)`
- `CONSTRAINT fk_refunds_actor FOREIGN KEY(processed_by) REFERENCES users(id)`

## Ranh giới với thành viên khác

- TV1: đọc thông tin users/địa chỉ và dùng cơ chế xác thực/phân quyền; không tự làm hệ thống tài khoản riêng.
- TV2: đọc products/product_variants và giá hiện hành qua service/DTO công khai; không ghi ngược giá catalog khi tạo đơn.
- TV4: gọi dịch vụ giữ/giải phóng/xuất hàng; không trực tiếp sửa inventory_balances/reservations/movements. Nhận yêu cầu hoàn tiền từ return_requests đã hợp lệ.
- TV5: cung cấp dữ liệu đơn/thu/hoàn thành công cho thống kê và xác minh người mua khi đánh giá; không trao quyền ghi trực tiếp bảng thanh toán.

## Các quy tắc bạn phải thực hiện trong service

1. Checkout tính lại giá DB, tạo snapshot, ghi đơn/dòng/địa chỉ, giữ coupon và gọi giữ hàng trong transaction thống nhất. Không tin tổng tiền hoặc user_id/verified_by từ client.
2. Không tính giảm hai lần: orders.subtotal là giá gốc của các dòng; orders.discount_amount gồm giảm dòng + giảm coupon. order_items.line_total chỉ trừ giảm của dòng. Tổng cuối = subtotal - tổng giảm + phí giao + phí lắp + thuế.
3. Đơn, payment, webhook và refund có trạng thái riêng. Database chỉ kiểm tra tập giá trị, không tự chặn mọi chuyển trạng thái trái nghiệp vụ.
4. Idempotency key phải giữ nguyên khi gửi lại cùng thao tác. Webhook trùng đã xử lý không tạo hiệu ứng lần hai; webhook FAILED vẫn cần cơ chế retry an toàn.
5. Kiểm tra chữ ký callback, mã đơn, provider, số tiền và tiền tệ trước khi xác nhận thu tiền; không đánh dấu PAID từ nút trên trình duyệt.
6. Coupon cần khóa/transaction khi kiểm tra và sử dụng giới hạn; RESERVED/APPLIED được tính theo chính sách đã thống nhất, RELEASED phải giải phóng lượt.
7. Khóa/transaction chống tổng hoàn vượt khoản đã thu thành công khi nhiều request đồng thời; CHECK(amount>0) không bảo đảm bất biến này.
8. Snapshot đơn cũ phải giữ nguyên khi sửa catalog/sổ địa chỉ. Các bảng tiền/đơn/lịch sử không triển khai xóa tùy tiện như CRUD danh mục.
9. Không dùng cache làm nguồn quyết định giá, số tiền, coupon hoặc quyền truy cập đơn. Lịch sử đơn cần phân trang và tránh N+1.

## Frontend React của TV3

- Làm giỏ hàng, checkout, lịch sử/chi tiết đơn, kết quả/trạng thái thanh toán; màn hình quản trị đơn, coupon, xác minh chuyển khoản và hoàn tiền theo quyền.
- Gọi REST API JSON của Spring Boot qua API client chung của TV1. React chỉ hiển thị/tương tác; giá, giảm giá, phí, quyền truy cập và kết quả thanh toán được backend kiểm tra và quyết định.
- Tách DTO request/response; không trả trực tiếp JPA Entity hoặc internal_note ra API khách. Hiển thị lỗi theo trường, lỗi hết hàng/coupon, trạng thái đang gửi và khả năng thử lại an toàn.
- Dùng lại cùng idempotency key cho một thao tác đang retry. Không coi nút bị disable là cơ chế chống trùng và không coi trang redirect thành công là chứng cứ đã trả tiền.
- Chốt với TV1 cơ chế auth cookie/CSRF hoặc JWT; route guard phía React không thay thế kiểm tra quyền sở hữu đơn ở backend.

## Phần Java cũ cần thay đổi

TV3-01/02/03 hiện đã có implementation riêng trong `commerce`: giỏ DB và coupon chạy bằng REST/React. Danh sách dưới đây giải thích sự khác biệt với legacy; các mục liên quan đơn/thanh toán vẫn thuộc Task 04 trở đi. Xem `TV3_HUONG_DAN_CHAY_VA_DEMO.md` để chạy, không dùng lại controller session/template cũ cho phần đã chuyển.

- Cart/CartItem hiện theo session cần chuyển sang carts/cart_items trong DB.
- Order hiện có dữ liệu địa chỉ và payment_method trực tiếp; schema mới tách order_addresses và payments.
- OrderDetail cũ dùng product_id; OrderItem mới lưu cả product_id, variant_id và các snapshot.
- Enum PaymentMethod cũ BANKING phải được ánh xạ sang BANK_TRANSFER; trạng thái đơn/payment phải tách riêng.
- Controller MVC trả tên template cần chuyển thành REST endpoint trả DTO JSON; thay form/redirect Thymeleaf bằng request và điều hướng ở React.
- Không chỉ đổi tên bảng để chạy: cần thay mapping, DTO, service, validation, giao diện và kiểm thử theo toàn bộ quy tắc trên.

Đã đối chiếu đủ **11 bảng / 139 thuộc tính**, bao gồm cột generated, với file SQL nguồn.
