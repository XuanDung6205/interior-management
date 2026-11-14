# Nếp Nhà — React commerce

Phạm vi TV3-01 đến TV3-06: giỏ hàng, đăng nhập/gộp giỏ khách, quản lý mã ưu đãi, xem trước/đặt đơn và theo dõi/hủy đơn của khách. Dùng dữ liệu thật từ API; không có dữ liệu mẫu trong giao diện. Chọn COD hoặc chuyển khoản không tự xác nhận đã thu tiền; xử lý cổng thanh toán/hoàn tiền thực tế thuộc các task sau.

## Chạy local

Yêu cầu Node 22.12+ thuộc nhánh 22, hoặc Node 24+ (đã kiểm tra với Node 24.13.0), npm và backend commerce ở `http://localhost:8080`.

```powershell
cd frontend
npm ci
npm run dev
```

Mở `http://127.0.0.1:5173/cart`, `/login`, `/checkout`, `/orders`, `/orders/:id`, `/admin/coupons` hoặc `/admin/inventory/receipts`. Vite chuyển tiếp `/api` đến backend; luôn gọi API với cookie phiên và CSRF. Không cấu hình token/password trong frontend. Dùng tài khoản thật hoặc dữ liệu test cô lập do backend cung cấp; frontend không tạo tài khoản, sản phẩm hay kho. Trang nhập kho dành cho STAFF/ADMIN; hợp đồng API ở `docs/api/TV4_INVENTORY_RECEIPTS.md`.

Nếu npm báo lỗi chứng chỉ trên Windows có chứng chỉ doanh nghiệp, có thể dùng kho chứng chỉ hệ điều hành của Node:

```powershell
$env:NODE_OPTIONS='--use-system-ca'
npm ci
```

Không tắt kiểm tra TLS. Tránh mở dev server ra mạng công cộng.

```powershell
npm test
npm run build
npm run preview
```

Build nằm trong `dist/`. Khi triển khai cần cùng origin, reverse proxy `/api` đến Java, và SPA fallback về `index.html` cho các route React. Không đưa biến bí mật vào các biến `VITE_*` vì chúng nằm trong bundle công khai.

## Hành vi tích hợp

- Tiền do server trả ở dạng chuỗi thập phân; frontend chỉ định dạng, không tính giá/tổng/giảm giá.
- Đăng nhập gửi form URL-encoded, lấy CSRF mới, sau đó gọi `POST /api/cart/merge`. Lỗi gộp không hủy phiên đăng nhập; có nút thử lại và đường dẫn điều chỉnh giỏ tài khoản. Sau đăng xuất cũng lấy CSRF mới.
- Coupon yêu cầu STAFF/ADMIN. Form giữ tất cả trường chỉnh sửa; ô trống ở giới hạn/cap gửi `null`. Thời gian được hiển thị theo múi giờ trình duyệt và chuyển sang UTC, giữ cả giây/mili giây khi sửa. Trạng thái “Đang bật” chỉ phản ánh `isActive`; thời hạn được hiển thị riêng.
- `COUPON_IN_USE` khi xóa hiển thị thông báo server và giữ thao tác tắt mã. Nút xem thử dùng API tính giảm; không phải giữ lượt hay xác nhận checkout.
- Checkout/đơn hàng yêu cầu đăng nhập. Có thể chọn địa chỉ đã lưu hoặc nhập địa chỉ chỉ dùng cho đơn này; các khoản tiền và ưu đãi đều do server tính. Sau bước xem trước cần chủ động xác nhận thông tin rồi mới đặt đơn.
- Trước khi đặt, frontend lưu đúng body, báo giá và `Idempotency-Key` trong `sessionStorage`, theo ID tài khoản. Nếu mất phản hồi hoặc lỗi server, giữ nguyên yêu cầu và hiển thị thông tin đã gửi để thử lại, kể cả sau refresh khi giỏ cũ đã chuyển đổi. Chưa cho sửa thông tin khi kết quả còn chưa rõ. Thành công xóa yêu cầu chờ; sửa trước khi gửi hoặc `QUOTE_CHANGED` cần xem lại báo giá và dùng khóa mới. Đăng xuất không xóa yêu cầu chưa rõ kết quả của tài khoản đó; tài khoản khác không đọc hoặc gửi lại nó qua giao diện.
- Chi tiết đơn dùng snapshot đã lưu, tách rõ trạng thái đơn/thanh toán/giao hàng/hoàn tiền và lịch sử. Chỉ hiển thị hủy khi server trả `canCancel`; cần lý do, không xóa lịch sử. Hoàn tiền `PENDING` chỉ là yêu cầu chờ xử lý.

## Bằng chứng kiểm thử frontend

- RED trước implementation: 13 test fail (thiếu màn hình, session/CSRF client, định dạng tiền và UTC).
- GREEN: `npm test` chạy 37 test/4 file. Ngoài 19 test TV3-01/02/03, đã chạy RED 10 test checkout/đơn trước implementation rồi GREEN: auth guard, địa chỉ đã lưu/nhập mới, báo giá server, xác nhận đặt, retry cùng body/khóa sau refresh, đổi báo giá, lỗi theo trường, phân trang/lọc đơn, snapshot/trạng thái độc lập và lý do hủy. Bổ sung hồi quy cho thông tin yêu cầu chờ sau reload và nhãn hoàn tiền khác thanh toán. Các hồi quy cũng đã thấy RED trước khi sửa.
- `npm run build`: pass. `npm audit`: 0 lỗ hổng sau khi ghim phiên bản đã vá.
- Component test chỉ thay thế biên HTTP; không chứng minh transaction, quyền sở hữu hoặc MariaDB. Trên môi trường Windows Codex này Vitest cần chạy ngoài sandbox do sandbox chặn rename tệp tạm (`EPERM`).
- Smoke qua proxy Vite thật tới Java/MariaDB cô lập `furniture_shop_tv3_dev`: `/api/session` trả guest, `/api/cart` trả giỏ rỗng/VND và catalog trả 3 SKU đã seed, giá có kiểu chuỗi. Chỉ kiểm tra đọc qua HTTP, không thay thế E2E browser.
- E2E thực tế: `npm run test:e2e` qua Playwright dùng Chrome đã cài (`channel: chrome`), backend Java và MariaDB demo thật. Ba test pass: giỏ khách/thêm/sửa/refresh/xóa; đăng nhập/gộp giỏ và quản trị coupon; khách đăng nhập → quote → xác nhận → đặt → xem snapshot → hủy → lọc danh sách đơn. Đơn do test tạo được hủy để giải phóng giữ hàng; không hard-delete. Kiểm tra desktop 1440px và mobile 390px không tràn ngang, không có pageerror. Ảnh trong `test-results/` (Git bỏ qua), đã được xem trực tiếp; checkout/order dùng tên `tv3-checkout-{desktop,mobile}.png`, `tv3-order-{desktop,mobile}.png`.
- E2E chỉ chạy mutation sau khi `/api/commerce/runtime` xác nhận DB cô lập. Cần đặt `TV3_DEMO_PASSWORD` qua environment và khởi chạy backend/Vite trước; tài khoản checkout mặc định `tv3.customer`, đổi qua `TV3_CHECKOUT_USER` nếu cần. Test không tự sinh người dùng, không dùng DB production. Retry mạng/đổi quote được kiểm ở component HTTP boundary; E2E không mô phỏng mất phản hồi. Gateway, xác minh khoản thu và thực thi hoàn tiền thuộc task sau.

Tài liệu thư viện đã đối chiếu: [Vite](https://vite.dev/guide/), [React Router](https://reactrouter.com/start/declarative/installation), [Vitest](https://vitest.dev/guide/) và metadata/audit từ npm registry chính thức. Các phiên bản đã khóa trong `package-lock.json`.

Review regressions: giữ nguyên request/key khi mất phản hồi rồi gặp401/403/429, đăng nhập lại vẫn replay cùng đơn; lỗi nghiệp vụ xác nhận chưa tạo đơn phục hồi form gốc để sửa và xem báo giá mới.
