# TV4-03 — Xuất kho theo hàng đã giữ của đơn

Áp dụng `docs/database/V003__shipments_idempotency.sql` sau V002 trên database đích trước khi bật API này. Migration thêm khóa chống gửi lặp cho bảng `shipments`; lệnh có thể chạy lại. Trên database test cô lập, `python scripts/tv3_database.py migrate --database <tên DB>` áp dụng cả V002/V003. Runtime `CommerceApplication`.

## API và giao diện

- `GET /api/admin/inventory/orders?page=0&size=20`: đơn còn hàng giữ để xuất; tối đa 100 dòng/trang.
- `GET /api/admin/inventory/orders/{orderId}/holds`: mã đơn, trạng thái và lượng giữ/còn lại theo SKU và kho.
- `POST /api/admin/inventory/orders/{orderId}/dispatch`: yêu cầu session `STAFF`/`ADMIN`, CSRF và `Idempotency-Key` 8–64 ký tự. Body `{ "warehouseId": 1, "carrier": "Giao hàng nội bộ", "trackingNumber": "VAN-001", "note": "Giao chiều nay" }`.

POST xuất **toàn bộ lượng đang giữ cho đơn tại kho đã chọn**. Một đơn có thể có nhiều vận đơn khi hàng nằm ở nhiều kho. Trả shipment ID, trạng thái `IN_TRANSIT`, mã vận đơn, các dòng SKU/số lượng và `replayed`. Gửi lại đúng key/body sau lỗi mạng; key trùng với nội dung khác trả `409 IDEMPOTENCY_CONFLICT`. `(carrier,trackingNumber)` cũng phải duy nhất. Màn hình `/admin/inventory/dispatch` hiển thị hàng đang giữ, tạo vận đơn và lưu yêu cầu chờ trong `sessionStorage` theo tài khoản để thử lại.

## Quy tắc giao dịch

- Khóa tài khoản nhân viên và chủ đơn theo ID, khóa đơn, coupon/thanh toán, danh sách kho, reservation, rồi balance theo SKU/kho. Đọc lại trạng thái đơn và lượng giữ sau khi khóa. Đơn hủy/hoàn tất, không còn hàng giữ, kho ngừng hoạt động hoặc thanh toán chuyển khoản chưa xác minh đều bị chặn.
- Với COD có thể xuất khi thanh toán còn `PENDING`; với chuyển khoản/cổng thanh toán yêu cầu `SUCCEEDED`. Quy tắc này dùng trạng thái tiền thật từ MariaDB.
- Cùng transaction tạo `shipments` và `shipment_items`, giảm `on_hand_quantity` và `reserved_quantity`, tăng `fulfilled_quantity`, ghi `SHIPMENT` movement âm, chuyển đơn sang `PROCESSING` và ghi lịch sử. Bất kỳ bước nào lỗi đều rollback.
- Chức năng này xử lý xuất toàn bộ số đã giữ của **một kho** trong một lần, chưa chia nhỏ lượng của cùng SKU tại cùng kho. Lịch giao, cập nhật giao thành công/thất bại, lắp đặt, đổi trả và bảo hành là các chức năng kế tiếp. `installation_required` chưa được suy ra từ phí lắp đặt vì checkout TV3 chưa lưu riêng lựa chọn dịch vụ lắp đặt.
