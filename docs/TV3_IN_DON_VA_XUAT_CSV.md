# In đơn hàng và xuất CSV TV3

Phạm vi ngày 21/11/2026: phiếu đơn hàng cho khách/chủ đơn và nhân viên, cùng CSV danh sách đơn dành cho STAFF/ADMIN. Dùng React + Spring Boot + MariaDB hiện tại, vẫn **47 bảng**, không thêm migration hay cấu hình VNPAY. [Contract](api/TV3_ORDER_DOCUMENTS_CONTRACT.md), [OpenAPI](api/tv3-commerce.yaml), [bàn giao/demo](TV3_BAN_GIAO_VA_DEMO.md).

## In hoặc lưu PDF

1. Đăng nhập, mở chi tiết đơn tại `/orders/{id}` hoặc `/admin/orders/{id}` rồi chọn liên kết in đơn.
2. Trang `/orders/{id}/print` hoặc `/admin/orders/{id}/print` tải snapshot mới từ server. Chờ tải xong; nếu lỗi, kiểm tra phiên đăng nhập/quyền và dùng nút thử lại.
3. Chọn **In / Lưu PDF** để mở hộp thoại in của trình duyệt. Chọn máy in hoặc **Save as PDF / Lưu thành PDF**, khổ A4 và kiểm tra bản xem trước trước khi lưu/in. Trình duyệt quyết định tùy chọn máy in, lề và ngắt trang; ứng dụng không tự mở hộp thoại.

Phiếu có dòng hàng, địa chỉ giao tại thời điểm đặt, lựa chọn lắp đặt, chi tiết giá và số tiền đã thu/đã hoàn/đang chờ hoàn do server tính. Tổng đơn giữ nguyên; **phiếu đơn hàng không chứng minh đã thu tiền**. Khoản đang chờ hoàn chưa phải tiền đã hoàn. Thanh điều hướng và nút thao tác được ẩn khi in; bảng có tiêu đề lặp và nội dung dài được xuống dòng.

Khách chỉ in đơn của mình; đơn người khác trả 404. Nhân viên cần vai trò STAFF/ADMIN; bản in dùng cùng tập dữ liệu công khai với khách, không có danh bạ tài khoản, ghi chú nội bộ, nhân viên đối soát, bằng chứng hay thông tin ngân hàng. Các trang tải lại dữ liệu khi đổi đơn/tài khoản, không lưu nội dung chứng từ trong localStorage. Giữ file PDF và giấy in trong phạm vi người được phép xem dữ liệu đơn.

## Xuất danh sách đơn CSV

1. STAFF/ADMIN mở `/admin/orders`, chọn trạng thái, tìm kiếm và ngày đặt từ/đến cần dùng. **Áp dụng tìm kiếm** để cập nhật bộ lọc trên URL trước khi xuất; nội dung đang gõ trong ô tìm kiếm chưa phải bộ lọc đã áp dụng.
2. Chọn **Xuất CSV**. File lấy **tất cả đơn khớp bộ lọc đã áp dụng**, theo ngày đặt giảm dần rồi ID giảm dần; không chỉ các dòng ở trang hiện tại và không phụ thuộc kích thước trang.
3. Nếu báo vượt **5.000 đơn**, thu hẹp ngày, trạng thái hoặc tìm kiếm rồi xuất lại. Server từ chối toàn bộ lần xuất quá giới hạn (`EXPORT_TOO_LARGE`), không gửi file thiếu dòng. Không có kết quả thì file chỉ chứa hàng tiêu đề.

Ngày dùng định dạng `YYYY-MM-DD` và múi giờ Việt Nam **UTC+07**. Ngày từ và ngày đến đều được tính trọn ngày; có thể bỏ một đầu. Ví dụ từ/đến cùng `2026-11-21` gồm các đơn từ 00:00 đến trước 00:00 ngày 22/11 theo giờ Việt Nam. Ngày sai hoặc từ sau đến trả `INVALID_DATE_RANGE`. Tìm kiếm coi `%` và `_` là ký tự thông thường. API danh sách của khách không nhận thêm bộ lọc ngày này.

File tên `orders-<UTCtimestamp>.csv`, mã hóa UTF-8 có BOM, dấu phẩy phân cột, CRLF phân dòng, mọi ô nằm trong dấu nháy kép và dấu nháy kép trong nội dung được nhân đôi. Có **14 cột**, theo thứ tự: Mã đơn; Ngày đặt (UTC+07:00); Trạng thái; Người nhận; Điện thoại; Tiền hàng; Giảm giá; Phí giao hàng; Phí lắp đặt; Thuế bổ sung; Tổng đơn; Tiền tệ; Đã thu; Đã hoàn. Số tiền có hai chữ số thập phân, dấu chấm và không kèm ký hiệu tiền; trạng thái giữ mã của server. Mỗi dòng là một đơn; **không coi tổng đơn hoặc tổng cột là doanh thu đã ghi nhận**.

CSV dành cho người xem trong phần mềm bảng tính. Server thêm tiền tố tab vào văn bản có nguy cơ thành công thức: dấu `=`, `+`, `-`, `@` ASCII hoặc dạng full-width sau khoảng trắng/ký tự điều khiển/định dạng; tab, CR hoặc LF ở đầu cũng được xử lý. Đây là biến đổi văn bản hiển thị, có thể khác dữ liệu gốc, không bảo đảm nhập lại nguyên trạng hoặc an toàn trong mọi phần mềm sau khi file bị biến đổi. Khi mở file, kiểm tra mã hóa và định dạng cột theo phần mềm đang dùng, đặc biệt số điện thoại có số 0 đầu.

CSV không chứa địa chỉ đầy đủ, ghi chú nội bộ, proof, staff ID, hash hay thông tin tài khoản ngân hàng. Tải xuống cần phiên đăng nhập hợp lệ; lỗi 401/403 không tạo chứng từ. Ứng dụng kiểm tra kiểu phản hồi CSV và giải phóng URL tải tạm. Server dùng `Cache-Control: no-store`; CSV có `Content-Disposition: attachment` và `X-Content-Type-Options: nosniff`. Ba API GET mới chỉ đọc, không yêu cầu CSRF token và không ghi nghiệp vụ.

## Kiểm chứng bàn giao

Tại đợt triển khai21/11, OpenAPI1.6.0 có55operation, gồm hai API JSON `/api/orders/{id}/print`, `/api/admin/orders/{id}/print` và GET `/api/admin/orders/export.csv`. Postman syntax lint không có lỗi; schema vẫn 47 bảng, không thêm migration.

Đã kiểm chứng cục bộ ngày 21/11/2026:

- Java: 189 ca, 188 đạt và 1 Gemini live bỏ qua; gate CI xác nhận16 nhóm integration DB đã thực thi. Có 7 ca integration mới cho quyền/snapshot/bộ lọc/date-edge/CSV/giới hạn, cùng regression deep-link và security hiện có.
- React: 157/157 đạt trong 14 file; bản production build 64 module và đóng gói JAR thành công.
- Chrome: 8/8 đạt trên JAR/API/MariaDB test thật trong 46,3 giây, gồm 7 luồng cũ; luồng mới chạy trong 4,2 giây. Sau chỉnh riêng nền print CSS, chạy lại luồng in/CSV trên gói cuối đạt 1/1 trong 6,6 giây; HTTP smoke 6 kiểm tra đạt.
- Phiếu 50 SKU được tạo qua giỏ/quote/checkout API thật trên DB test riêng, in thành 6 trang A4. Trích xuất xác nhận đủ 50 mã SKU duy nhất, không mất/lặp; đã xem các trang render để kiểm tra đầu bảng lặp, dòng không bị cắt, tổng tiền và nền trắng. Catalog/tồn ban đầu là fixture test; không thêm dòng đơn giả bằng SQL. Đơn QA được hủy qua API sau khi in để giải phóng giữ hàng.
- Công cụ vận hành: 39/39 test đạt; review độc lập không phát hiện lỗi quan trọng. Đây là kiểm chứng cục bộ của nhánh hiện tại, không tự gọi là hosted CI đã chạy cho đợt này.

Log và ảnh cục bộ ở `target/tv3-documents-*`, `target/tv3-print-pagination*` và `frontend/test-results/tv3-order-*`, đều được Git bỏ qua. Hướng dẫn tái lập backend/browser dùng [bàn giao](TV3_BAN_GIAO_VA_DEMO.md) và [gói JAR](TV3_RELEASE.md); cần DB demo/test cô lập và phiên đăng nhập thật.
