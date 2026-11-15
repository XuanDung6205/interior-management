# Nếp Nhà — React commerce

Phạm vi TV3-01 đến TV3-07: giỏ hàng, đăng nhập/gộp giỏ khách, quản lý mã ưu đãi, xem trước/đặt đơn, theo dõi/hủy đơn của khách và xử lý đơn dành cho nhân viên. Dùng dữ liệu thật từ API; không có dữ liệu mẫu trong giao diện. Chọn COD hoặc chuyển khoản không tự xác nhận đã thu tiền; xử lý cổng thanh toán/hoàn tiền thực tế thuộc các task sau.

## Chạy local

Yêu cầu Node 22.12+ thuộc nhánh 22, hoặc Node 24+ (đã kiểm tra với Node 24.13.0), npm và backend commerce ở `http://localhost:8080`.

```powershell
cd frontend
npm ci
npm run dev
```

Mở `http://127.0.0.1:5173/cart`, `/login`, `/checkout`, `/orders`, `/orders/:id`, `/admin/orders`, `/admin/orders/:id`, `/admin/coupons`, `/admin/inventory/receipts`, `/admin/inventory/operations`, `/admin/inventory/dispatch` hoặc `/admin/inventory/shipments`. Vite chuyển tiếp `/api` đến backend; luôn gọi API với cookie phiên và CSRF. Không cấu hình token/password trong frontend. Dùng tài khoản thật hoặc dữ liệu test cô lập do backend cung cấp; frontend không tạo tài khoản, sản phẩm hay kho. Các trang kho dành cho STAFF/ADMIN; hợp đồng API ở `docs/api/TV4_INVENTORY_RECEIPTS.md`, `docs/api/TV4_STOCK_OPERATIONS.md`, `docs/api/TV4_ORDER_DISPATCH.md` và `docs/api/TV4_SHIPMENT_LIFECYCLE.md`.

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
- Quản lý đơn yêu cầu STAFF/ADMIN. Tìm kiếm nguyên văn tối đa 150 ký tự, lọc trạng thái và phân trang qua API. Chỉ có các thao tác trong `allowedTransitions`; lý do trong `blockedTransitions` được hiển thị để nhân viên kiểm tra. Gửi đúng `fromStatus` đã xem; xung đột cần chủ động tải lại trạng thái. Hủy cần lý do công khai và bước xác nhận riêng. Không có thao tác giả lập thu tiền, xuất kho hay giao hàng.
- Ghi chú chuyển trạng thái được đánh dấu là thông điệp khách hàng nhìn thấy. Ghi chú nội bộ dùng editor riêng tối đa 5.000 ký tự, gửi `expectedInternalNote` đúng nguyên văn từ server (giữ cả khoảng trắng). Khi xung đột, giữ bản nháp, tải riêng ghi chú mới nhất và để nhân viên chọn dùng bản mới hoặc tiếp tục với bản nháp trước khi lưu. Tải lại trạng thái đơn không tự xóa bản nháp nội bộ.
- `assemblyRequested` là dịch vụ khách đã chọn, kể cả khi không phát sinh phí; `assemblyRequired` trong từng sản phẩm là yêu cầu kỹ thuật. Hai thông tin hiển thị riêng, không suy diễn từ phí.

## Bằng chứng kiểm thử frontend

- RED trước implementation: 13 test fail (thiếu màn hình, session/CSRF client, định dạng tiền và UTC).
- GREEN sau khi ghép TV4: `npm test` chạy 67 test/10 file. Gồm 55 test TV3, 4 test kho/vận đơn được ghép và 8 test tích hợp staff guard/login return cho bốn trang kho. TV3-07 đã chạy RED 18 test trước implementation rồi GREEN; khi ghép, đã thấy RED cho bốn đường dẫn đăng nhập lại trước khi mở rộng whitelist. Các test giỏ/checkout/đơn/coupon và kho/vận đơn vẫn pass.
- `npm run build`: pass. `npm audit`: 0 lỗ hổng sau khi ghim phiên bản đã vá.
- Component test chỉ thay thế biên HTTP; không chứng minh transaction, quyền sở hữu hoặc MariaDB. Trên môi trường Windows Codex này Vitest cần chạy ngoài sandbox do sandbox chặn rename tệp tạm (`EPERM`).
- Smoke qua proxy Vite thật tới Java/MariaDB cô lập `furniture_shop_tv3_dev`: `/api/session` trả guest, `/api/cart` trả giỏ rỗng/VND và catalog trả 3 SKU đã seed, giá có kiểu chuỗi. Chỉ kiểm tra đọc qua HTTP, không thay thế E2E browser.
- E2E thực tế: `npm run test:e2e` qua Playwright dùng Chrome đã cài (`channel: chrome`), backend Java và MariaDB demo thật. Bốn test pass, gồm ba luồng giỏ/coupon/checkout đã có và TV3-07: khách tạo đơn → admin tìm/lọc/xem → sửa ghi chú riêng → xác nhận → xử lý → thấy lý do chưa thể hoàn thành → hủy có xác nhận. Kiểm tra thêm API/UI của khách không có ghi chú nội bộ. Đơn do test tạo được hủy để giải phóng giữ hàng; không hard-delete. Desktop 1440px và mobile 390px không tràn ngang, không có pageerror. Ảnh trong `test-results/` (Git bỏ qua) đã xem trực tiếp; quản trị dùng `tv3-admin-orders-{desktop,mobile}.png`, `tv3-admin-order-{desktop,mobile}.png`.
- E2E chỉ chạy mutation sau khi `/api/commerce/runtime` xác nhận DB cô lập. Cần đặt `TV3_DEMO_PASSWORD` qua environment và khởi chạy backend/Vite trước; checkout mặc định `tv3.customer` (`TV3_CHECKOUT_USER`), admin mặc định `tv3.admin` (`TV3_ADMIN_USER`). Test không tự sinh người dùng, không dùng DB production. Retry mạng/đổi quote/xung đột note được kiểm ở component HTTP boundary; E2E không mô phỏng mất phản hồi hay hai nhân viên sửa đồng thời. Gateway, xác minh khoản thu và thực thi hoàn tiền thuộc task sau.
- Sau khi ghép frontend TV4, kiểm tra bố cục Chrome riêng với HTTP read-only thay thế ở biên test: đủ tám link nhân viên tại 1440/1024/768/390/375px, không tràn ngang. Đã thấy lỗi tràn 1024px trước khi sửa grid. Ảnh header desktop/mobile `staff-navigation-1440.png`, `staff-navigation-390.png` trong `test-results/` đã xem trực tiếp. Đây là kiểm tra bố cục, chưa thay thế việc chạy lại E2E với backend đã ghép và migration mới.

Tài liệu thư viện đã đối chiếu: [Vite](https://vite.dev/guide/), [React Router](https://reactrouter.com/start/declarative/installation), [Vitest](https://vitest.dev/guide/) và metadata/audit từ npm registry chính thức. Các phiên bản đã khóa trong `package-lock.json`.

Review regressions: giữ nguyên request/key khi mất phản hồi rồi gặp401/403/429, đăng nhập lại vẫn replay cùng đơn; lỗi nghiệp vụ xác nhận chưa tạo đơn phục hồi form gốc để sửa và xem báo giá mới.
