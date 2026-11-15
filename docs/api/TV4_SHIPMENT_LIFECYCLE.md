# TV4-04 — Kết quả giao hàng và hàng hoàn về kho

Áp dụng `docs/database/V004__shipment_audit_index.sql` sau V003, sau đó V005 lưu lựa chọn lắp đặt để dùng cùng TV3-07. Migrations dùng các bảng hiện có, giữ nguyên45bảng và có thể chạy lại. Script `python scripts/tv3_database.py migrate --database <DB demo/test>` áp dụng V002–V005 cho database cô lập.

## API

- `GET /api/admin/inventory/shipments?page=0&size=20&status=`: danh sách vận đơn, tối đa 100 dòng/trang. Lọc `IN_TRANSIT`, `FAILED`, `DELIVERED`, `RETURNED` hoặc bỏ trống.
- `POST /api/admin/inventory/shipments/{id}/status`: session `STAFF`/`ADMIN`, CSRF, header `Idempotency-Key` 8–64 ký tự chữ/số/`_`/`-`; body `{ "targetStatus": "DELIVERED", "reason": "Khách đã nhận hàng" }`. Lý do bắt buộc, tối đa 1000 ký tự và hiển thị cho khách.

Chuyển trạng thái hợp lệ: `IN_TRANSIT → DELIVERED | FAILED`, `FAILED → IN_TRANSIT | RETURNED`. Không thể chuyển tiếp từ `DELIVERED` hoặc `RETURNED`. Gửi lại đúng key/body nếu phản hồi chưa rõ; key trùng với nội dung khác trả `409 IDEMPOTENCY_CONFLICT`. Màn hình nhân viên `/admin/inventory/shipments` lưu yêu cầu chưa rõ kết quả theo ID tài khoản trong `sessionStorage`. Khách xem trạng thái và lịch sử sự kiện trong chi tiết đơn của chính mình.

## Quy tắc giao dịch

- Khóa tài khoản nhân viên/chủ đơn theoID, đơn, coupon rồi vận đơn trước khi đổi trạng thái; sự kiện lưu vào `audit_logs` trong cùng transaction. Khóa yêu cầu gắn với tài khoản và đọc lại dưới khóa tài khoản để chống lặp. Đơn phải còn `PROCESSING`.
- `DELIVERED` ghi thời gian giao. Tự hoàn tất dùng chung OrderCompletionPolicy và OrderLifecycleService của TV3-07: đủ khoản thu thực sau hoàn tiền, giao/xuất đúng số lượng từng dòng, hết giữ kho và hoàn tất lắp đã chọn. COD chưa thu vẫn giao thành công nhưng đơn ở PROCESSING; không coi đã giao là đã thu tiền. Khi đủ điều kiện, lịch sử và audit hoàn tất chỉ ghi một lần, coupon chuyển APPLIED.
- `FAILED` ghi lý do; `IN_TRANSIT` sau thất bại là một lần giao lại trên cùng vận đơn.
- `RETURNED` nghĩa là nhân viên đã nhận **hàng vật lý** tại kho xuất. Cùng transaction cộng lại `on_hand_quantity`, ghi movement `RETURN` dương cho từng dòng vận đơn và sự kiện; `reserved_quantity` không đổi vì lượng giữ đã được xuất. Giao diện yêu cầu nhân viên xác nhận hàng thực sự về kho. Retry không cộng tồn lần hai. Đơn giữ `PROCESSING` để xử lý hậu mãi/hoàn tiền ở chức năng sau; thao tác này không tự hoàn tiền hoặc tạo giữ hàng mới.
- Chức năng này chưa đặt lịch giao/lắp đặt; các trường lịch trong `shipments` chưa được sử dụng. Các vận đơn cũ không có sự kiện lịch sử, nhưng trạng thái hiện tại vẫn hiển thị.
