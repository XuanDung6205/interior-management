# Nếp Nhà — React commerce

Phạm vi TV3-01 đến TV3-08 và phần thủ công TV3-10: giỏ hàng, đăng nhập/gộp giỏ khách, quản lý mã ưu đãi, xem trước/đặt đơn, theo dõi/hủy/xử lý đơn, xác minh khoản thu COD/chuyển khoản và đối chiếu hoàn thủ công theo chứng từ. Dùng dữ liệu thật từ API; không có dữ liệu mẫu trong giao diện. Chọn phương thức hoặc tạo yêu cầu chưa xác nhận đã thu/hoàn tiền; nhân viên cần đối chiếu chứng từ và xác nhận riêng. Ứng dụng không khởi tạo chuyển tiền. Cổng thanh toán sandbox TV3-09 và hoàn qua gateway vẫn chờ tích hợp riêng.

## Chạy local

Yêu cầu Node 22.12+ thuộc nhánh 22, hoặc Node 24+ (đã kiểm tra với Node 24.13.0), npm và backend commerce ở `http://localhost:8080`.

```powershell
cd frontend
npm ci
npm run dev
```

Mở `http://127.0.0.1:5173/cart`, `/login`, `/checkout`, `/orders`, `/orders/:id`, `/orders/:id/payments`, `/admin/orders`, `/admin/orders/:id`, `/admin/payments`, `/admin/payments/:id`, `/admin/refunds`, `/admin/orders/:id/refunds`, `/admin/refunds/:id`, `/admin/coupons`, `/admin/inventory/receipts`, `/admin/inventory/operations`, `/admin/inventory/dispatch` hoặc `/admin/inventory/shipments`. Vite chuyển tiếp `/api` đến backend; luôn gọi API với cookie phiên và CSRF. Không cấu hình token/password trong frontend. Dùng tài khoản thật hoặc dữ liệu test cô lập do backend cung cấp; frontend không tạo tài khoản, sản phẩm hay kho. Các trang kho dành cho STAFF/ADMIN; hợp đồng API ở `docs/api/TV4_INVENTORY_RECEIPTS.md`, `docs/api/TV4_STOCK_OPERATIONS.md`, `docs/api/TV4_ORDER_DISPATCH.md` và `docs/api/TV4_SHIPMENT_LIFECYCLE.md`.

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
- Thanh toán khách hiển thị tổng hợp, lịch sử và hướng dẫn ngân hàng từ server; checkout đã tạo khoản PENDING nên trang không tự tạo thêm. Chỉ cho tạo yêu cầu mới nếu `canCreate`; BANK_TRANSFER bị tắt khi chưa cấu hình người nhận. PENDING đã hết hạn vẫn chờ server chuyển sang trạng thái kết thúc trước khi có yêu cầu mới.
- Trong môi trường học tập, khởi động backend với `PAYMENT_BANK_MODE=DEMO` trên DB cô lập. Account `DEMO-KHONG-CHUYEN-TIEN` là thông tin giả, không chuyển tiền thật và không phải sandbox ngân hàng/cổng thanh toán. Trang không gọi ngân hàng, không tạo QR qua dịch vụ bên ngoài. Cấu hình UNCONFIGURED không hiển thị tài khoản để chuyển tiền.
- Yêu cầu tạo thanh toán giữ đúng body/Idempotency-Key trong sessionStorage theo user+order. Chứng từ xác minh giữ đúng reference/note/acknowledged theo staff+payment. Mất mạng,401/403/429,5xx hoặc phản hồi thành công không đọc được đều giữ nguyên yêu cầu để kiểm tra lại; chỉ lỗi nghiệp vụ xác nhận rõ chưa tạo mới giải phóng trạng thái chờ. Chứng từ bị từ chối vẫn giữ bản nháp qua reload.
- Nhân viên lọc/tìm/phân trang khoản thu, đối chiếu số tiền cố định từ server, nhập tham chiếu và ghi chú riêng rồi xác nhận qua hai bước. Không có input sửa amount/status/verifier. Replay cùng chứng từ không ghi thêm khoản thu. Ghi chú/verifier không xuất hiện trong trang/API khách. Khoản thu khi đơn đã hủy chỉ tạo yêu cầu hoàn PENDING, chưa có nghĩa là đã trả lại tiền.
- Hoàn thủ công yêu cầu STAFF/ADMIN. Hàng đợi hỗ trợ trạng thái, tìm nguyên văn tối đa 150 ký tự và phân trang. Trang theo đơn hiển thị tổng đã thu, phần giữ PENDING, phần đã hoàn, ngân sách khả dụng và từng khoản thu. Tạo yêu cầu chọn khoản thu gốc, số tiền thập phân, lý do và phiếu trả hàng thực nhận đủ điều kiện do server trả về; đơn đang hoạt động bắt buộc chọn phiếu, đơn đã hủy có thể không gắn phiếu. Giao diện không tạo, duyệt hoặc nhận hàng thay TV4. Mọi hạn mức do server kiểm tra, không tính lại ngân sách trên client.
- Tạo yêu cầu hoàn cần bước xem lại trước khi giữ ngân sách. Xác nhận chứng từ hoặc hủy yêu cầu chưa gửi tiền đều cần lời xác nhận riêng và bước kiểm tra cuối. Số tiền hoàn cố định, không có input sửa amount/status/verifier. Hủy chỉ giải phóng phần giữ để tạo yêu cầu thay thế; nghĩa vụ hoàn vẫn cần được xử lý. Gateway không có thao tác hoàn thủ công. Ứng dụng chỉ ghi nhận chứng từ, không gọi ngân hàng hoặc khởi tạo chuyển tiền.
- Hoàn tiền giữ đúng khóa/nội dung tạo theo staff+order và proof/lý do hủy theo staff+refund trong sessionStorage trước khi gửi. Reload, lỗi mạng,401/403/429/5xx, phản hồi thành công hỏng JSON hoặc sai cấu trúc và conflict chưa biết giữ nguyên nội dung, chặn sửa/thao tác khác, chỉ cho replay đúng yêu cầu. Lỗi409 nghiệp vụ đã biết hoặc 400 có fieldErrors chuyển về nháp sửa được; nháp vẫn giữ qua reload. Mã nghiệp vụ hoàn tiền được diễn giải bằng tiếng Việt. Ghi chú chứng từ, lý do xử lý hủy và người đối chiếu chỉ hiển thị trong khu vực nhân viên; lý do đề nghị hoàn có thể xuất hiện trong lịch sử khách.

## Bằng chứng kiểm thử frontend

- RED trước implementation: 13 test fail (thiếu màn hình, session/CSRF client, định dạng tiền và UTC).
- Lịch sử trước TV3-08: bộ test sau khi ghép TV4 có 67 test/10 file. TV3-07 đã chạy RED 18 test trước implementation rồi GREEN; khi ghép, đã thấy RED cho bốn đường dẫn đăng nhập lại trước khi mở rộng whitelist.
- GREEN trước TV3-10: `npm test` chạy 97 test/11 file. TV3-08 đã thấy RED 16 test trước implementation; bổ sung auth/rate-limit replay, scope user/order, nháp chứng từ qua reload, phản hồi JSON hỏng, bỏ qua lỗi read cũ sau đổi filter và lý do chặn bằng tiếng Việt. Các hồi quy liên quan đã chạy RED trước khi sửa; toàn bộ test TV3/TV4 trước đó vẫn pass.
- `npm run build`: pass. `npm audit`: 0 lỗ hổng sau khi ghim phiên bản đã vá.
- Component test chỉ thay thế biên HTTP; không chứng minh transaction, quyền sở hữu hoặc MariaDB. Trên môi trường Windows Codex này Vitest cần chạy ngoài sandbox do sandbox chặn rename tệp tạm (`EPERM`).
- Smoke qua proxy Vite thật tới Java/MariaDB cô lập `furniture_shop_tv3_dev`: `/api/session` trả guest, `/api/cart` trả giỏ rỗng/VND và catalog trả 3 SKU đã seed, giá có kiểu chuỗi. Chỉ kiểm tra đọc qua HTTP, không thay thế E2E browser.
- E2E thực tế: `npm run test:e2e` qua Playwright dùng Chrome đã cài (`channel: chrome`), backend Java và MariaDB demo thật. Bốn test pass, gồm ba luồng giỏ/coupon/checkout đã có và TV3-07: khách tạo đơn → admin tìm/lọc/xem → sửa ghi chú riêng → xác nhận → xử lý → thấy lý do chưa thể hoàn thành → hủy có xác nhận. Kiểm tra thêm API/UI của khách không có ghi chú nội bộ. Đơn do test tạo được hủy để giải phóng giữ hàng; không hard-delete. Desktop 1440px và mobile 390px không tràn ngang, không có pageerror. Ảnh trong `test-results/` (Git bỏ qua) đã xem trực tiếp; quản trị dùng `tv3-admin-orders-{desktop,mobile}.png`, `tv3-admin-order-{desktop,mobile}.png`.
- E2E TV3-08 hiện tại: toàn bộ 5/5 scenario pass (4 luồng cũ và luồng mới). Luồng mới dùng BANK_TRANSFER DEMO: khách xem PENDING có sẵn → admin xác nhận chứng từ giả → cố ý làm mất phản hồi sau transaction thật → reload → replay đúng proof, giữ nguyên paidAt/verifier → khách thấy đã thu, không lộ ghi chú riêng → hủy đơn đã thu và xác minh PENDING refund, chưa hoàn tiền. Chỉ hủy đơn test tạo; giữ lịch sử khoản thu. Không có tiền thật hoặc thao tác kho vật lý bị ép để hoàn thành đơn.
- E2E chỉ chạy mutation sau khi `/api/commerce/runtime` xác nhận DB cô lập; test thanh toán còn kiểm tra bankTransfer.isDemo trước khi xác nhận. Đặt `TV3_DEMO_PASSWORD` qua environment; checkout mặc định `tv3.customer` (`TV3_CHECKOUT_USER`), admin `tv3.admin` (`TV3_ADMIN_USER`). Các E2E cũ không mô phỏng mất phản hồi; riêng TV3-08 và TV3-10 đã kiểm tra lost-response/reload/replay với transaction thật. Hai nhân viên sửa note đồng thời được kiểm ở component HTTP boundary, không phải browser E2E. Sandbox gateway và hoàn qua gateway vẫn thuộc task sau.
- Sau khi ghép frontend TV4, kiểm tra bố cục Chrome riêng với HTTP read-only thay thế ở biên test: đủ tám link nhân viên tại 1440/1024/768/390/375px, không tràn ngang. Đã thấy lỗi tràn 1024px trước khi sửa grid. Ảnh header desktop/mobile `staff-navigation-1440.png`, `staff-navigation-390.png` trong `test-results/` đã xem trực tiếp. Đây là kiểm tra bố cục, chưa thay thế việc chạy lại E2E với backend đã ghép và migration mới.
- TV3-08 đã kiểm tra live read-only admin list/detail với đủ chín link tại 1440/1024/768/390/375px, không tràn ngang; customer desktop/mobile được E2E kiểm tra. Sáu ảnh payment customer/staff/list đã xem trực tiếp: `tv3-payment-{customer,staff}-{desktop,mobile}.png`, `tv3-payment-list-{1440,390}.png`.
- Log cuối nằm trong `test-results/`: `tv3-08-unit-build-verification.log` (97 test + build), `tv3-08-e2e-verification.log` (5 scenario), `tv3-08-layout-verification.log` (10 phép kiểm tra viewport). Các log/ảnh là artifact local, Git bỏ qua.
- TV3-10 hiện tại: đã quan sát RED 17 test do thiếu route/màn hình trước implementation, sau đó GREEN 17/17; mở rộng ma trận replay/validation và hai hồi quy nhãn khoản chưa thu thành 30 test hoàn. Hồi quy FAILED/PENDING đã thấy RED 2/2 trước khi thêm trạng thái và đổi nhãn số tiền chưa thu thành “Giá trị yêu cầu”; chỉ SUCCEEDED được gọi “Đã thu”. Lần cuối chạy toàn bộ `npm test -- --maxWorkers=2` pass 127/127 trong 12 file, giữ đầy đủ các test TV3/TV4, 48,55 giây; `npm run build` pass 60 module. Component test thay thế biên HTTP, không chứng minh khóa đồng thời hoặc tính nguyên tử MariaDB.
- Chrome E2E thực tế ngày 2026-11-17: `npm run test:e2e` pass 6/6 scenario, 51,1 giây qua Vite→Java→MariaDB demo cô lập; scenario hoàn mới 23,9 giây. Luồng mới: BANK_TRANSFER DEMO có sẵn→xác nhận khoản thu giả→khách hủy đơn→PENDING refund→nhân viên hủy yêu cầu chưa gửi tiền→tạo yêu cầu thay thế, cố ý làm mất phản hồi sau transaction và replay đúng khóa/body qua reload→xác nhận chứng từ hoàn giả, mất phản hồi rồi replay đúng proof, giữ nguyên completedAt/processedBy→khách thấy SUCCEEDED trong lịch sử và tổng hoàn, khoản thu gốc vẫn SUCCEEDED. Kiểm tra API/UI khách không có confirmationNote/resolutionNote/processedBy hoặc nội dung ghi chú riêng. Không có chuyển tiền thật, gọi ngân hàng hay sandbox gateway.
- Ba trang hoàn có 15 phép kiểm tra bố cục thực tế tại 1440/1024/768/390/375px: đủ 10 link điều hướng nhân viên và không tràn ngang; không có pageerror. Đã xem trực tiếp 6 ảnh `test-results/tv3-refund-{queue,order,detail}-{1440,390}.png`; trạng thái đã hoàn/đã hủy, số tiền, ghi chú riêng và ngân sách có thể đọc trên desktop/mobile. Ảnh chụp sau xử lý hoàn, còn form tạo/xác nhận/hủy được browser thao tác trong cùng scenario. Log E2E đầy đủ ở `../target/tv3-refund-e2e.log`; Playwright reset `test-results/` mỗi lần chạy nên log/ảnh lần trước không được đảm bảo còn tồn tại.
- Log unit/build đầy đủ lần cuối: `../target/tv3-refund-frontend-final.log`. Lần chạy không giới hạn worker trước đó có một timeout 1 giây ở bước tải danh sách ban đầu của test cũ `ignores an obsolete payment-list request failure after newer filters have loaded` (126 pass/1 fail); test đó pass nguyên trạng khi chạy riêng và toàn bộ 127 test pass với hai worker. Log lỗi được giữ ở `../target/tv3-refund-frontend-final-attempt1.log`. Một lần chạy trước nữa gặp race đọc document.title trong test điều hướng; đã sửa assertion dùng waitFor để chờ effect React, không thay routing. Chrome 6/6 và sáu ảnh ở trên được ghi trước thay đổi nhãn/trạng thái khoản chưa thu; thay đổi hiển thị cuối cùng được kiểm bởi hai hồi quy component và build, không thay mutation hoặc luồng hoàn.

Tài liệu thư viện đã đối chiếu: [Vite](https://vite.dev/guide/), [React Router](https://reactrouter.com/start/declarative/installation), [Vitest](https://vitest.dev/guide/) và metadata/audit từ npm registry chính thức. Các phiên bản đã khóa trong `package-lock.json`.

Review regressions: giữ nguyên request/key khi mất phản hồi rồi gặp401/403/429, đăng nhập lại vẫn replay cùng đơn; lỗi nghiệp vụ xác nhận chưa tạo đơn phục hồi form gốc để sửa và xem báo giá mới.

Sau sửa nhãn giá trị của lần thanh toán FAILED/PENDING, root đã chạy lại riêng Chrome E2E hoàn tiền:1/1pass13.5s, gồm15kiểm tra viewport. Log thật `target/tv3-refund-e2e-final.log`; bộ6kịch bản trước đó vẫn lưu ở `target/tv3-refund-e2e.log`.
