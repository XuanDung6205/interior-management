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
- **Dashboard (`analytics`)**:
  - `GET /api/admin/dashboard/summary?days=30` (`ADMIN`/`STAFF`): doanh thu ròng = `SUM(payments WHERE status='SUCCEEDED') - SUM(refunds WHERE status='SUCCEEDED')`.
- **AI & Gợi ý (`ai`)**:
  - `GET /api/commerce/recommendations?productId=&sessionId=&limit=6` (3 tầng: cùng danh mục -> hành vi 30 ngày -> phối đồ nội thất bổ trợ).
  - `GET /api/commerce/chat`, `GET /api/commerce/chat/{publicId}`, `POST /api/commerce/chat` (kiểm tra chủ sở hữu theo `user_id` hoặc `guest_token_hash`, giới hạn tốc độ, lấy lại giá & tồn thật từ DB trước khi trả lời; hiển thị rõ trạng thái nếu chưa có `GEMINI_API_KEY`).

## 2. Migration Vector & Reindex bền vững

File [V013__tv5_vector_and_reindex_outbox.sql](database/V013__tv5_vector_and_reindex_outbox.sql) tạo bảng `vector_store` (768 chiều cosine tương thích MariaDB 11.7+/12.2) và bảng hàng đợi `commerce_vector_reindex_outbox` (`PENDING` / `RETRY` / `SENT` / `DEAD`) để tránh mất tác vụ reindex khi khởi động lại server.

## 3. Kế hoạch Cache Redis & đo hiệu năng trước/sau cache

TTL khởi điểm khi nhóm bật Redis (`spring-boot-starter-cache`, `spring-boot-starter-data-redis`, `spring-boot-starter-actuator` do TV1 cấu hình nền chung):

| Dữ liệu | Cache key / phạm vi | TTL đề xuất | Xóa cache (invalidation) |
| --- | --- | --- | --- |
| Banner public | `content:banners:{placement}` | 10 phút | Sau khi admin tạo/sửa/xóa banner |
| Bài viết public | `content:articles:{slug}` | 10 phút | Sau khi admin cập nhật/xóa bài viết |
| Dashboard tổng hợp | `analytics:dashboard:{days}` | 2 phút | Hết TTL hoặc khi admin bấm làm mới |
| Gợi ý nội thất | `catalog:rec:{productId}:{userOrSession}` | 3 phút | Không dùng để chốt giá/tồn lúc checkout |

Chạy đo tải cục bộ (khi ứng dụng đang chạy tại `http://127.0.0.1:8080`):

```powershell
# Lượt 1: Cold cache (khởi động backend với $env:TV5_CACHE_ENABLED='false')
python tests/performance/tv5/tv5_load.py --base-url http://127.0.0.1:8080 --vus 10 --iterations 30 --label cold

# Lượt 2: Warm cache (bật $env:TV5_CACHE_ENABLED='true', đo kèm dashboard + AI chat riêng)
python tests/performance/tv5/tv5_load.py --base-url http://127.0.0.1:8080 --vus 10 --iterations 30 --label warm --staff-user tv3.admin --staff-password <mat-khau> --include-ai --ai-requests 5
```

### Mẫu bảng ghi số đo thực tế trước khi nộp

| Endpoint | Cold p50 (ms) | Cold p95 (ms) | Warm p50 (ms) | Warm p95 (ms) | Error rate (%) | Ghi chú / Cache hit |
| --- | --- | --- | --- | --- | --- | --- |
| `GET /api/commerce/content/banners` | _điền số đo thật_ | _điền_ | _điền_ | _điền_ | _điền_ | TTL 10 phút |
| `GET /api/commerce/content/articles` | _điền số đo thật_ | _điền_ | _điền_ | _điền_ | _điền_ | TTL 10 phút |
| `GET /api/commerce/recommendations` | _điền số đo thật_ | _điền_ | _điền_ | _điền_ | _điền_ | TTL 3 phút |
| `GET /api/admin/dashboard/summary` | _điền số đo thật_ | _điền_ | _điền_ | _điền_ | _điền_ | TTL 2 phút |
| `POST /api/commerce/chat` (AI riêng) | _điền số đo thật_ | _điền_ | — | — | _điền_ | Đo riêng không cache giá/tồn |

Ghi kèm cấu hình máy (`machine` từ JSON xuất ra) và số liệu Hikari pool đọc tại `/admin/operations`.
