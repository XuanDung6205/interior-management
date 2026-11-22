# TV4 — Đặt lịch giao và lắp đặt

`POST /api/admin/inventory/shipments/{id}/schedule` chỉ dành cho STAFF/ADMIN có session và CSRF hợp lệ. Header `Idempotency-Key` gồm 8–64 ký tự chữ/số/`_`/`-`. Thời gian gửi theo UTC ISO 8601 (`Z`); giao diện chuyển từ giờ địa phương của thiết bị trước khi gửi.

Đặt hoặc dời lịch giao:

```json
{"kind":"DELIVERY","scheduledFrom":"2026-11-25T09:00:00Z","scheduledTo":"2026-11-25T12:00:00Z"}
```

Chỉ vận đơn `IN_TRANSIT` hoặc `FAILED` của đơn `PROCESSING` được đặt lịch giao. Bắt đầu phải ở tương lai, không quá 90 ngày; kết thúc phải sau bắt đầu và khung giao không quá 24 giờ.

Đặt hoặc dời lịch lắp:

```json
{"kind":"INSTALLATION","installationAt":"2026-11-26T09:00:00Z"}
```

Đơn phải chọn dịch vụ lắp, vận đơn yêu cầu lắp, có trạng thái `IN_TRANSIT` hoặc `DELIVERED`, lịch lắp đang `PENDING` hoặc `SCHEDULED`; đơn còn `PROCESSING`. Thời gian lắp phải ở tương lai và trong 90 ngày. Đặt lịch chuyển `installation_status` thành `SCHEDULED`; bước xác nhận lắp thực tế vẫn do API aftercare thực hiện.

Cả hai thao tác khóa tài khoản liên quan, đơn và vận đơn trong cùng transaction, ghi `SHIPMENT_SCHEDULE` vào audit. Gửi lại đúng key/nội dung trả trạng thái hiện tại với `replayed=true`; dùng key cho nội dung khác trả `409 IDEMPOTENCY_CONFLICT`. Phản hồi vận đơn và `GET /api/admin/inventory/shipments` bổ sung `scheduledFrom`, `scheduledTo`, `installationRequired`, `installationStatus`, `installationScheduledAt`. Khách thấy lịch trong `GET /api/orders/{id}` của chính mình và trang chi tiết đơn; không có quyền sửa lịch.
