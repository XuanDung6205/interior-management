# TV4-01 — Nhập kho theo SKU

Runtime: `CommerceApplication`, MariaDB `furniture_shop` schema V001/V002. Chức năng này không thêm bảng. API thuộc nhóm `/api/admin/inventory/**`, yêu cầu session có quyền `STAFF` hoặc `ADMIN`; lệnh POST cần CSRF như các API commerce khác.

## API

- `GET /api/admin/inventory/warehouses`: danh sách kho đang hoạt động `{id,code,name}`.
- `GET /api/admin/inventory/receipts?warehouseId=&page=0&size=20`: lịch sử nhập kho mới nhất, tối đa 100 dòng/trang. `warehouseId` có thể bỏ qua để xem mọi kho. Trả `{items,page,size,totalElements,totalPages}`; mỗi dòng có mã SKU, kho, số lượng, mã chứng từ, lý do, người thực hiện và thời gian UTC.
- `POST /api/admin/inventory/receipts`, header `Idempotency-Key` gồm 8–64 ký tự chữ/số/`_`/`-`, body:

```json
{
  "warehouseId": 1,
  "variantId": 123,
  "quantity": 10,
  "referenceCode": "PN-2026-001",
  "reason": "Nhập từ nhà cung cấp"
}
```

Phản hồi chứa `movementId`, kho, SKU, `receivedQuantity`, `onHandQuantity`, `reservedQuantity`, `availableQuantity`, `referenceCode`, `createdAt` UTC và `replayed`. Khi không biết lệnh trước đã thành công hay chưa, gửi lại **đúng body và key cũ**. Cùng key với body khác trả `409 IDEMPOTENCY_CONFLICT`. Key được giới hạn theo tài khoản nhân viên; một key không thể tạo hai phiếu cho cùng tài khoản. Phản hồi gửi lại chứa tồn *hiện tại*, có thể đã thay đổi do giao dịch khác.

## Bất biến

- Chỉ nhận SKU/sản phẩm và kho còn hoạt động. Số lượng dương, tồn sau nhập không vượt giới hạn `INT`.
- Trong cùng transaction: khóa tài khoản thao tác, kho, số dư `(warehouse,variant)`; tăng `on_hand_quantity` và `version`, ghi đúng một `inventory_movements` loại `RECEIPT` với khóa chống lặp và người thực hiện. `reserved_quantity` giữ nguyên. Nếu bất kỳ bước nào lỗi, toàn bộ transaction rollback.
- SKU chưa có dòng tồn tại kho được tạo dòng số dư với giá trị 0 trước khi khóa và tăng. Các lệnh checkout/giữ hàng tiếp tục dùng `InventoryPort` hiện có; TV4-01 không thay đổi hợp đồng đó.
- Giao diện tại `/admin/inventory/receipts` lưu request/key đang chờ trong `sessionStorage` theo user ID để retry sau lỗi mạng hoặc reload. Chỉ xóa khi nhận thành công hoặc lỗi nghiệp vụ xác định chưa ghi phiếu.

## Phạm vi tiếp theo

Điều chỉnh, chuyển kho, xuất hàng, đối chiếu tồn, giao hàng, đổi trả và bảo hành thuộc các chức năng TV4 sau. Chúng cần dùng cùng thứ tự khóa đã thống nhất với TV3. Chưa chạy các luồng đó qua trang nhập kho.
