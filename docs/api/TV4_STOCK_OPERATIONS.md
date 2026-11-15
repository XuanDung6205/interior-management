# TV4-02 — Điều chỉnh tồn và chuyển kho

Runtime `CommerceApplication`, schema MariaDB V001/V002. Chức năng dùng bảng `inventory_balances` và `inventory_movements` hiện có. Mọi API dưới `/api/admin/inventory/**` cần session `STAFF` hoặc `ADMIN`; POST cần CSRF và header `Idempotency-Key` gồm 8–64 ký tự chữ/số/`_`/`-`.

## API

- `GET /api/admin/inventory/balances?warehouseId=1&variantId=123`: tồn hiện tại theo kho và SKU, gồm `onHandQuantity`, `reservedQuantity`, `availableQuantity`. Nếu chưa có dòng tồn, trả 0.
- `GET /api/admin/inventory/movements?warehouseId=&page=0&size=20`: nhật ký biến động, mới nhất trước; có thể bỏ `warehouseId`, tối đa 100 dòng/trang.
- `POST /api/admin/inventory/adjustments`: body `{ "warehouseId": 1, "variantId": 123, "quantityDelta": -2, "referenceCode": "KK-001", "reason": "Kiểm kê" }`. Delta âm giảm tồn, dương tăng tồn; không chấp nhận 0. Trả movement ID, delta, tồn hiện tại và `replayed`.
- `POST /api/admin/inventory/transfers`: body `{ "sourceWarehouseId": 1, "destinationWarehouseId": 2, "variantId": 123, "quantity": 2, "referenceCode": "CK-001", "reason": "Bổ sung kho" }`. Trả ID của hai biến động, tồn nguồn/đích hiện tại và `replayed`.

Khi phản hồi chưa rõ kết quả, gửi lại **đúng body và key cũ**. Key gắn với tài khoản nhân viên và loại thao tác. Cùng key nhưng nội dung khác trả `409 IDEMPOTENCY_CONFLICT`. Giao diện lưu thao tác chờ theo ID tài khoản trong `sessionStorage` và cho phép thử lại sau mất kết nối hoặc tải lại trang.

## Bất biến

- Điều chỉnh giảm không đưa `on_hand_quantity` xuống dưới `reserved_quantity` của checkout. Chuyển kho chỉ dùng số lượng khả dụng ở kho nguồn; hàng đã giữ cho đơn không được chuyển. Tồn đích không vượt giới hạn `INT`.
- Chuyển kho ghi `TRANSFER_OUT` âm ở kho nguồn và `TRANSFER_IN` dương ở kho đích trong **một transaction**. Nếu thiếu hàng hoặc ghi thất bại, cả hai số dư và hai movement đều rollback.
- Lệnh khóa tài khoản, kho theo ID tăng dần, rồi số dư theo ID kho tăng dần để giảm xung đột với checkout. Mỗi lệnh tăng version của dòng tồn và ghi người thực hiện, mã chứng từ, lý do.
- Đây là biến động tồn vật lý theo SKU. Việc xuất hàng cho đơn phải cập nhật reservation/fulfillment riêng; không dùng API điều chỉnh để thay thế xuất hàng.
