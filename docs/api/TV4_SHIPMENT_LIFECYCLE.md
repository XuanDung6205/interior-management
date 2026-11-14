# TV4-04 — Kết quả giao hàng và hàng hoàn về kho

Áp dụng `docs/database/V004__shipment_audit_index.sql` sau V003 trước khi chạy backend. Migration thêm index vào `audit_logs` hiện có, giữ nguyên 45 bảng đã chốt và có thể chạy lại. Script `python scripts/tv3_database.py migrate --database <DB demo/test>` áp dụng V002–V004 cho database cô lập.

## API

- `GET /api/admin/inventory/shipments?page=0&size=20&status=`: danh sách vận đơn, tối đa 100 dòng/trang. Lọc `IN_TRANSIT`, `FAILED`, `DELIVERED`, `RETURNED` hoặc bỏ trống.
- `POST /api/admin/inventory/shipments/{id}/status`: session `STAFF`/`ADMIN`, CSRF, header `Idempotency-Key` 8–64 ký tự chữ/số/`_`/`-`; body `{ "targetStatus": "DELIVERED", "reason": "Khách đã nhận hàng" }`. Lý do bắt buộc, tối đa 1000 ký tự và hiển thị cho khách.

Chuyển trạng thái hợp lệ: `IN_TRANSIT → DELIVERED | FAILED`, `FAILED → IN_TRANSIT | RETURNED`. Không thể chuyển tiếp từ `DELIVERED` hoặc `RETURNED`. Gửi lại đúng key/body nếu phản hồi chưa rõ; key trùng với nội dung khác trả `409 IDEMPOTENCY_CONFLICT`. Màn hình nhân viên `/admin/inventory/shipments` lưu yêu cầu chưa rõ kết quả theo ID tài khoản trong `sessionStorage`. Khách xem trạng thái và lịch sử sự kiện trong chi tiết đơn của chính mình.

## Quy tắc giao dịch

- Khóa tài khoản nhân viên/chủ đơn, đơn, vận đơn trước khi đổi trạng thái; sự kiện lưu vào `audit_logs` trong cùng transaction. Khóa yêu cầu gắn với tài khoản và đọc lại dưới khóa tài khoản để chống lặp. Đơn phải còn `PROCESSING`.
- `DELIVERED` ghi thời gian giao. Đơn chỉ sang `COMPLETED` khi **mọi** vận đơn đã giao, không còn hàng đang giữ chưa xuất và không có lịch lắp đặt bắt buộc chưa hoàn tất. Lịch sử đơn chỉ ghi hoàn tất một lần.
- `FAILED` ghi lý do; `IN_TRANSIT` sau thất bại là một lần giao lại trên cùng vận đơn.
- `RETURNED` nghĩa là nhân viên đã nhận **hàng vật lý** tại kho xuất. Cùng transaction cộng lại `on_hand_quantity`, ghi movement `RETURN` dương cho từng dòng vận đơn và sự kiện; `reserved_quantity` không đổi vì lượng giữ đã được xuất. Giao diện yêu cầu nhân viên xác nhận hàng thực sự về kho. Retry không cộng tồn lần hai. Đơn giữ `PROCESSING` để xử lý hậu mãi/hoàn tiền ở chức năng sau; thao tác này không tự hoàn tiền hoặc tạo giữ hàng mới.
- Chức năng này chưa đặt lịch giao/lắp đặt; các trường lịch trong `shipments` chưa được sử dụng. Các vận đơn cũ không có sự kiện lịch sử, nhưng trạng thái hiện tại vẫn hiển thị.
