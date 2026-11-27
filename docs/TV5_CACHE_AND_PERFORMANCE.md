# TV5 — Nội dung, tương tác, AI tư vấn, thống kê và đo hiệu năng

## 1. Phạm vi API đã triển khai trong `org.example.eshop.commerce`

- **Nội dung (`content`)**:
  - Public: `GET /api/commerce/content/banners`, `GET /api/commerce/content/articles`, `GET /api/commerce/content/articles/{slug}`.
  - Admin (`ADMIN`/`STAFF`): `/api/admin/content/banners`, `/api/admin/content/articles`.
- **Tương tác (`engagement`)**:
  - Yêu thích (`authenticated`): `GET/POST/DELETE /api/wishlist`.
  - Đánh giá: `GET /api/commerce/products/{productId}/reviews`, `GET /api/reviews/eligible`, `GET /api/reviews/mine`, `POST /api/reviews`, và kiểm duyệt `GET /api/admin/reviews`, `PATCH /api/admin/reviews/{id}/moderate`.
- **Tracking (`tracking`)**:
  - `POST /api/tracking/events` trả `202 Accepted`; chặn sự kiện server-owned (`ADD_TO_CART`, `ORDER_PLACED`) gửi từ trình duyệt và luôn lấy `user_id` từ session trên server.
  - Đủ 8 bước phễu hành vi: `PAGE_VIEW`, `PRODUCT_VIEW`, `SEARCH`, `FILTER`, `ADD_TO_CART_CLICK`, `ADD_TO_CART` (server), `CHECKOUT_START`, `ORDER_PLACED` (server).
- **Dashboard (`analytics`)**:
  - `GET /api/admin/dashboard/summary?days=30` (`ADMIN`/`STAFF`): doanh thu ròng = `SUM(payments WHERE status='SUCCEEDED') - SUM(refunds WHERE status='SUCCEEDED')`.
  - `POST /api/admin/dashboard/cache/clear`: xóa sạch cache TV5 (cả Redis lẫn RAM dự phòng) và reset bộ đếm hit/miss.
- **AI & Gợi ý (`ai`)**:
  - `GET /api/commerce/recommendations?productId=&sessionId=&limit=6` (ưu tiên vector ngữ nghĩa khi bật `VECTOR_ENABLED=true`, dự phòng 3 tầng: cùng danh mục → hành vi 30 ngày → phối đồ nội thất bổ trợ).
  - `GET /api/commerce/chat`, `GET /api/commerce/chat/{publicId}`, `POST /api/commerce/chat` (kiểm tra chủ sở hữu theo `user_id` hoặc `guest_token_hash`, giới hạn tốc độ, lấy lại giá & tồn thật từ DB trước khi trả lời; hiển thị rõ trạng thái nếu chưa có `GEMINI_API_KEY`).

## 2. Migration Vector & Reindex bền vững

File [V013__tv5_vector_and_reindex_outbox.sql](database/V013__tv5_vector_and_reindex_outbox.sql) và [V014__commerce_vector_runtime.sql](database/V014__commerce_vector_runtime.sql) tạo bảng `vector_store` (768 chiều cosine tương thích MariaDB 11.7+/12.2) và bảng hàng đợi `commerce_vector_reindex_outbox` (`PENDING` / `RETRY` / `SENT` / `DEAD`) để tránh mất tác vụ reindex khi khởi động lại server.

## 3. Cache Redis (kèm tự động dự phòng RAM) & đo hiệu năng trước/sau cache

`Tv5Cache` ưu tiên lưu DTO dạng JSON vào **Redis** (cấu hình qua `REDIS_HOST`, `REDIS_PORT`, `REDIS_TIMEOUT=1500ms`, `TV5_REDIS_CACHE_ENABLED=true`) theo quy tắc khóa `tv5:<namespace>:g<generation>:<key>`.
- **Xóa cache nguyên tử (invalidation)**: sau khi transaction ghi DB commit (`evictAfterCommit`), chỉ cần `INCR tv5:gen:<namespace>` (O(1), không dùng `KEYS *`) nên dữ liệu cũ bị vô hiệu ngay mà không cần chờ hết TTL.
- **Chịu lỗi Redis (fail-safe fallback)**: nếu Redis tắt hoặc timeout, `Tv5Cache` tự kích hoạt cooldown 30 giây, chuyển sang cache RAM cục bộ (`backend = MEMORY_FALLBACK`) rồi tự thử nối lại sau 30 giây — không bao giờ làm hỏng giao dịch hay báo lỗi 500.
- **Không bao giờ cache dữ liệu thanh toán/tồn kho thật**: checkout và xác nhận đơn luôn đọc trực tiếp MariaDB.

| Dữ liệu | Namespace / Cache key | TTL | Xóa cache (invalidation) |
| --- | --- | --- | --- |
| Banner public | `tv5:content:banners:g<gen>:{placement}` | 10 phút | Sau khi admin tạo/sửa/xóa banner |
| Bài viết public | `tv5:content:articles:g<gen>:list:*` / `slug:{slug}` | 10 phút | Sau khi admin tạo/sửa/xóa bài viết |
| Dashboard tổng hợp | `tv5:analytics:dashboard:g<gen>:days:{days}` | 2 phút | Sau khi thu/hoàn tiền, nhập/xuất kho, đổi trạng thái đơn hoặc admin bấm xóa cache |
| Gợi ý nội thất | `tv5:catalog:recommendations:g<gen>:{productId}:{limit}:{scope}` | 3 phút | Tách key theo `userId`/`sessionId`, không dùng làm giá/tồn lúc checkout |

Chạy đo tải cục bộ (khi ứng dụng đang chạy tại `http://127.0.0.1:8080` và Docker MariaDB + Redis đang chạy):

```powershell
# Lượt 1: Cold cache (khởi động backend với $env:TV5_CACHE_ENABLED='false')
python tests/performance/tv5/tv5_load.py --base-url http://127.0.0.1:8080 --vus 10 --iterations 30 --label cold --staff-user tv3.admin --staff-password <mat-khau>

# Lượt 2: Warm cache Redis (khởi động backend với $env:TV5_CACHE_ENABLED='true', đo kèm AI chat riêng)
python tests/performance/tv5/tv5_load.py --base-url http://127.0.0.1:8080 --vus 10 --iterations 30 --label warm --staff-user tv3.admin --staff-password <mat-khau> --include-ai --ai-requests 5
```

### Bảng số đo thực tế

- **Môi trường đo**: `Windows-11-10.0.26300-SP0`, Docker `mariadb:12.2` + `redis:7-alpine`, dataset demo 50 sản phẩm + 6 ảnh gallery (`furniture_shop_tv3_dev`), tải `10 VUs × 30 iterations = 300 requests/endpoint` (riêng AI chat đo 5 câu hỏi tuần tự).
- **Thời điểm đo**: Cold (`TV5_CACHE_ENABLED=false`, `backend=DISABLED`, `0/1201 hits` — `2026-11-27T16:40:06Z`) và Warm (`TV5_CACHE_ENABLED=true`, `backend=REDIS`, `1201/1201 hits = 100%` — `2026-11-27T16:33:32Z`).

| Endpoint | Cold p50 (ms) | Cold p95 (ms) | Cold RPS | Warm p50 (ms) | Warm p95 (ms) | Warm RPS | Error rate (%) | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /api/commerce/content/banners` | 15.93 | 29.66 | 407.32 | 19.63 | 36.49 | 204.39 | 0.0% | Redis TTL 10p (lượt đầu sau restart có spike JIT max 854ms) |
| `GET /api/commerce/content/articles` | 16.99 | 28.25 | 526.44 | 16.22 | 37.39 | 507.74 | 0.0% | Redis TTL 10p |
| `GET /api/commerce/recommendations` | 19.06 | 50.23 | 393.59 | 12.28 | 28.71 | 664.68 | 0.0% | **p50 −35.6%, p95 −42.8%, RPS +68.9%** (Redis TTL 3p) |
| `GET /api/admin/dashboard/summary` | 28.88 | 42.08 | 335.95 | 24.93 | 47.71 | 350.11 | 0.0% | **p50 −13.7%**, chặn 100% truy vấn tổng hợp lặp vào DB (Redis TTL 2p) |
| `POST /api/commerce/chat` (AI riêng) | — | — | — | 1634.56 | 2290.91 | — | 0.0% | `gemini-3.5-flash-lite` (5 req), đo riêng không cache giá/tồn |

**Nhận xét số liệu**:
- Toàn bộ 4 API đọc TV5 đều đạt `0.0%` lỗi và `p95 < 55 ms` (vượt xa ngưỡng mục tiêu `< 500 ms` trong tài liệu phân công).
- Redis phát huy hiệu quả rõ nhất ở API truy vấn nhiều tầng JOIN (`recommendations`: p95 giảm 42.8%, RPS tăng gần 69%) và API tổng hợp nhiều bảng (`dashboard`: p50 giảm 13.7%, hit rate 100% giúp giảm áp lực truy vấn trực tiếp vào MariaDB).
- Với bảng rất ít dòng như `banners`/`articles` chạy cục bộ trên cùng máy, MariaDB InnoDB buffer pool đã rất nhanh nên độ trễ gần tương đương Redis; giá trị chính của Redis là giữ tải DB ổn định khi dữ liệu và lưu lượng tăng.
- AI Chat (`POST /api/commerce/chat`) có độ trễ p50 ~1.63s / p95 ~2.29s do gọi LLM bên ngoài (`gemini-3.5-flash-lite`), khẳng định thiết kế tách AI ra khỏi transaction đặt hàng và áp dụng giới hạn tốc độ là cần thiết.

## 4. Tóm tắt đóng góp TV5 & Kịch bản demo bảo vệ

### Danh mục bàn giao của TV5
- **8 bảng nghiệp vụ + 2 bảng kỹ thuật vector**: `wishlist_items`, `reviews`, `review_images`, `tracking_events`, `banners`, `articles`, `chat_conversations`, `chat_messages`, cùng `vector_store` & `commerce_vector_reindex_outbox` (V013–V014).
- **Màn hình giao diện React**:
  - Khách hàng: banner động & bài viết mới trên `/`, Tạp chí `/articles` & `/articles/:slug`, Yêu thích `/wishlist`, Đánh giá sau mua `/reviews`, đánh giá + yêu thích + gợi ý ngay trên `/products/:slug`, khung chat **Tư vấn nội thất** có lịch sử hội thoại.
  - Quản trị (`ADMIN`/`STAFF`): `/admin/dashboard`, `/admin/content`, `/admin/reviews`.
- **Kiểm thử tự động**: `Tv5UnitTest` (unit + Redis hit/evict/fallback), `Tv5IntegrationTest` (MariaDB thật), `Tv5Features.test.jsx` (Vitest UI), `tv3-tv5-integration.spec.js` (Playwright E2E tích hợp TV3–TV5).

### Kịch bản demo nhanh 5 phút khi bảo vệ
1. **Nội dung động**: Vào `/admin/content` tạo 1 banner `HOME_HERO` + 1 bài viết `PUBLISHED` → về `/` và `/articles` thấy hiển thị ngay (cache Redis tự invalidation).
2. **Tương tác khách hàng**: Mở 1 sản phẩm ở `/sale` → lọc danh mục → xem chi tiết → bấm **♡ Yêu thích** → vào `/wishlist` thấy sản phẩm và dải **Gợi ý phối hợp**.
3. **Đánh giá xác minh đã mua**: Khách đánh giá đơn đã giao ở `/reviews` → Admin duyệt & phản hồi ở `/admin/reviews` → mở lại trang sản phẩm thấy hiển thị công khai.
4. **Trợ lý AI nội thất**: Mở nút **Tư vấn nội thất** → hỏi *"Tư vấn bàn trà dưới 5 triệu"* → AI trả lời theo dữ liệu thật trong DB kèm thẻ bấm thẳng sang `/products/:slug` và lưu trong mục **Lịch sử**.
5. **Dashboard + Redis + chịu lỗi**: Mở `/admin/dashboard` xem doanh thu ròng, phễu 8 bước `tracking_events`, bộ đệm **Redis** + tỷ lệ hit; bấm **Xóa cache TV5** hoặc thử tắt container `redis` để chứng minh web tự chuyển sang **RAM cục bộ (dự phòng)** không bị lỗi.
