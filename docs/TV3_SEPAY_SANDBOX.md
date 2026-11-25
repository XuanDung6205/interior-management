# Chạy thanh toán SePay Test mode

Luồng này dùng SePay Webhooks Test mode và API v2. Không dùng VNPAY hay bộ Merchant
ID/Secret Key của Cổng thanh toán SePay. Không chuyển khoản bằng app ngân hàng thật.

## Chuẩn bị

1. Trong SePay bật Test mode, tạo tài khoản VietinBank Test; cấu trúc mã `DH` với
   đúng 8 chữ số. Webhook nhận qua CLI, tiền vào, JSON, chọn tài khoản Test, lọc DH,
   bật retry và HMAC-SHA256. Tạo API Access Test mode riêng.
2. Sao chép các biến SEPAY_* trong `.env.example` vào `.env.sepay.local`, điền đúng
   tài khoản Test, tên chủ tài khoản, HMAC secret, API Token và UUID Webhook.
   File này đã bị Git ignore; không gửi ảnh khóa hoặc commit file. Khóa từng chia sẻ
   qua chat cần thay sau đợt thử và cập nhật đồng thời SePay + file local.
3. Spring không tự đọc `.env`; dùng helper dưới đây hoặc export biến trong IDE.
   API token/HMAC secret chỉ ở backend, không dùng biến VITE_*.
4. Cài SePay CLI theo tài liệu chính thức. Bản local của lần triển khai này nằm ở
   `target/sepay-work/cli/sepay.exe`; target là thư mục tạm, nên không xóa nếu còn
   dùng CLI local. Máy khác có thể cài `sepay` vào PATH theo hướng dẫn chính thức.

```powershell
# Token không xuất ra màn hình; chỉ in trạng thái và tài khoản cấu hình có tồn tại.
python scripts/tv3_sepay.py check
# CLI dùng OAuth riêng; API Token không thay thế việc đăng nhập CLI.
python scripts/tv3_sepay.py login
```

## Migration và chạy

Dùng MariaDB12.2, Java21 (source target17), Node24. Cấp DB_PASSWORD trong môi trường
terminal/IDE. Không đặt mật khẩu trong dòng lệnh Git hoặc source. Lệnh migrate chỉ
chấp nhận DB demo/test, không ghi vào `furniture_shop`.

```powershell
# Sao lưu trước khi nâng DB demo hiện có; dùng output mới mỗi lần.
python scripts/tv3_backup.py backup --database furniture_shop_tv3_dev --output backups/truoc-sepay
python scripts/tv3_database.py migrate --database furniture_shop_tv3_dev
python scripts/tv3_release.py build --offline
python scripts/tv3_sepay.py run --database furniture_shop_tv3_dev --port 8086
```

Mở `http://127.0.0.1:8086`. Trong terminal thứ hai:

```powershell
python scripts/tv3_sepay.py listen --port 8086
```

Endpoint chính xác là POST `http://localhost:8086/api/payments/sepay/webhook`.
Không forward vào frontend Vite5173 hoặc cổng ví dụ3000. CLI giữ nguyên body và
header chữ ký; không dùng `--print`/`--headers` khi không cần lưu dữ liệu giao dịch.
Đóng CLI sẽ ngừng nhận mới; mở lại bằng lệnh listen. Thử lại một Webhook cũ quá
5 phút cần SePay gửi lại/chữ ký mới, không tắt kiểm tra timestamp để replay.

Trên laptop Windows này, Corretto mặc định chưa tin chuỗi chứng chỉ của kết nối
HTTPS trong khi kho chứng chỉ Windows đã tin cậy. Đã xác minh cùng API trả200 khi
dùng kho Windows. Chạy Java bằng helper với `--windows-trust` nếu gặp lỗi đối soát
do `SSLHandshakeException`/`SunCertPathBuilderException`:

```powershell
python scripts/tv3_sepay.py run --database furniture_shop_tv3_dev --port 8086 --windows-trust
```

Cờ này chỉ áp dụng cho tiến trình Java đó; không sửa JDK/máy, không bỏ kiểm tra
chứng chỉ hay hostname. Máy khác có truststore JDK phù hợp thì không cần cờ.

## Kiểm tra nghiệp vụ

1. Đăng nhập khách trên DB demo, thêm hàng, chọn **SePay Sandbox · QR thử nghiệm**.
   Xem trước và đặt đơn; trang thanh toán hiện QR cùng số tiền/nội dung do server tạo.
2. Trong SePay Test mode chọn Mô phỏng giao dịch. Chọn cùng tài khoản, Tiền vào,
   đúng tổng tiền và nội dung `SEVQR DHxxxxxxxx` trên đơn, gửi giao dịch thử.
3. CLI phải báo chuyển tiếp HTTP200. Trang thanh toán cập nhật mỗi5giây khi đang
   mở, tối đa15phút; tab ẩn/ngắt trang sẽ ngừng đọc. Có nút làm mới thủ công.
4. Thử gửi lại cùng giao dịch: không tăng số tiền đã thu. Thử sai tiền, ngân hàng,
   tài khoản, mã hoặc thêm giao dịch khác cho khoản đã thu: không tự thu lần hai.
   Nhân viên xem **Quản lý thanh toán → Giao dịch SePay cần kiểm tra**.
5. Hủy đơn rồi nhận tiền đúng mã: đơn vẫn hủy, hàng không giữ lại, tạo một khoản
   hoàn PENDING. Hoàn thủ công vẫn cần chứng từ; chưa có API hoàn tiền tự động.

## Đối soát khi thiếu Webhook

Nhân viên mở khoản SePay, nhập **UUID giao dịch API v2**, bấm Đối soát với SePay.
UUID khác ID số Webhook và mã tham chiếu ngân hàng. Backend GET từ host cố định
`userapi-sandbox.sepay.vn`, kiểm tra dữ liệu rồi dùng chung bộ xử lý giao dịch.
Không có nút tự khai đã trả tiền của khách. Lỗi API không đồng nghĩa thanh toán
thất bại; không tạo lần thu mới khi chưa rõ kết quả. Sự kiện FAILED nghĩa là tự
khớp không thành công, chưa phải tiền đã hoàn về ngân hàng.

API chi tiết: [TV3_SEPAY_SANDBOX_CONTRACT.md](api/TV3_SEPAY_SANDBOX_CONTRACT.md).
Không cần Redis hoặc cache trạng thái thu tiền; trạng thái luôn đọc từ MariaDB.

## Kiểm thử và giới hạn

Java dùng DB `furniture_shop_test_*` riêng và V001-V010; các bài kiểm tra bao gồm
chữ ký/raw bytes, thời gian, gửi trùng, tranh chấp hủy/thu, rollback audit, hai
nguồn API/Webhook, sai dữ liệu và timeout phần body HTTP. React kiểm tra QR/đã thu,
thử lại yêu cầu và đối soát của nhân viên. Kiểm thử mô phỏng HTTP được ghi rõ là
stand-in, không thay cho bằng chứng kết nối SePay thật.

Phiên bản này hỗ trợ tài khoản chính VietinBank Test, không hỗ trợ nhận qua VA,
không gọi API Production, không tự hoàn qua mạng và không chia một khoản thanh
toán thành nhiều lần chuyển. Nhận tiền không tự thay việc xác nhận giao hàng/lắp
đặt hay điều kiện hoàn thành đơn. Đơn SePay chưa trả không có job tự hết hạn;
nhân viên/khách dùng nghiệp vụ hủy đơn hiện có.

Tài liệu nguồn: [Test mode](https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh),
[HMAC](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc),
[API v2](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/chi-tiet),
[CLI](https://developer.sepay.vn/vi/agents/cli/webhook-listen).

QR PNG được tạo offline bằng ZXing tại endpoint có kiểm tra chủ đơn/quyền nhân viên, `Cache-Control: no-store`. Không gửi tài khoản, số tiền hay nội dung đến dịch vụ ảnh ngoài. Mẫu payload/CRC được đối chiếu ví dụ công khai của VietQR và ảnh được giải mã lại trong kiểm thử.
