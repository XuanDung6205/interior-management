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
   hoàn PENDING. Khoản hoàn chưa gửi qua SePay vẫn có thể xác nhận thủ công bằng chứng từ. Để hoàn qua Sandbox, thực hiện quy trình dưới đây.

## Đối soát, hết hạn và duyệt sự kiện

Nhân viên có thể bấm **Tìm theo mã thanh toán** ngay tại khoản SePay. Server tìm mã
DH đã lưu qua API v2, đối chiếu toàn bộ dữ liệu nhận được rồi dùng chung bộ xử lý
với Webhook. Đây là lựa chọn thông thường khi CLI tắt hoặc thiếu callback.

Job kiểm tra theo lô, mặc định 5 khoản mỗi 60 giây; khóa nhận việc và retry/backoff
được lưu trong DB. Không gọi mạng trong lúc giữ khóa tiền. Đơn SePay mới có hạn
thanh toán 30 phút, khác với hạn xác nhận COD 24 giờ. Hết giờ trên màn hình chưa
đủ để hủy: server phải xác minh API trả kết quả đầy đủ và không có giao dịch, rồi
khóa và kiểm tra lại đơn chưa xác nhận, chưa thu/hoàn, chưa xuất/giao, chỉ có đúng một lần
thanh toán SePay mới. Đơn có nhiều lần thanh toán cần nhân viên xử lý. API lỗi, phân trang chưa hết, dữ liệu mơ hồ hoặc đã có bằng
chứng tiền/giao hàng thì hoãn và chuyển nhân viên xử lý. Khoản có sự kiện FAILED/PROCESSED đã liên kết không tự hết hạn; duyệt note không
xóa guard này. Các lần cũ có `expires_at=NULL` giữ nguyên. Thanh toán đến sau khi đơn bị hủy vẫn được ghi nhận
và tạo yêu cầu hoàn, không tự khôi phục đơn hay tồn giữ.

Trong **Giao dịch SePay cần kiểm tra**, lọc chưa duyệt/đã duyệt và ghi chú kết quả
xử lý. Note bắt buộc, lưu nhân viên và thời điểm, không sửa sau khi duyệt. Duyệt
sự kiện không chuyển khoản thu sang thành công, không xóa dữ liệu gốc và không
xác nhận tiền đã hoàn. Nếu sự kiện phản ánh thêm tiền thật ngoài khoản đã thu,
nhân viên phải đối chiếu và giải quyết riêng, không bấm duyệt để tăng ngân sách.

Nút **Chạy một lượt đối soát** chỉ chạy một lô hữu hạn theo cùng chính sách, không
bỏ qua xác thực/CSRF. Tắt scheduler không làm mất dữ liệu hoặc quyền gọi thao tác
staff rõ ràng.

Có thể đối soát trực tiếp bằng UUID khi đã biết giao dịch:

Nhân viên mở khoản SePay, nhập **UUID giao dịch API v2**, bấm Đối soát với SePay.
UUID khác ID số Webhook và mã tham chiếu ngân hàng. Backend GET từ host cố định
`userapi-sandbox.sepay.vn`, kiểm tra dữ liệu rồi dùng chung bộ xử lý giao dịch.
Không có nút tự khai đã trả tiền của khách. Lỗi API không đồng nghĩa thanh toán
thất bại; không tạo lần thu mới khi chưa rõ kết quả. Sự kiện FAILED nghĩa là tự
khớp không thành công, chưa phải tiền đã hoàn về ngân hàng.

API chi tiết: [TV3_SEPAY_SANDBOX_CONTRACT.md](api/TV3_SEPAY_SANDBOX_CONTRACT.md).
Không cần Redis hoặc cache trạng thái thu tiền; trạng thái luôn đọc từ MariaDB.

## Hoàn qua SePay Sandbox

1. Bật `SEPAY_REFUNDS_ENABLED=true` trong cấu hình backend để cho phép gửi; mặc định false. Đối soát khoản thu thành công để server có UUID giao dịch và UUID tài khoản từ
   API v2. API Access Test mode cần quyền ghi để gửi hoàn; token chỉ đọc có thể đối
   soát nhưng không chứng minh gửi hoàn hoạt động.
2. Tạo hoặc mở yêu cầu hoàn PENDING hợp lệ: đơn đã hủy hoặc phiếu trả đã nhận,
   cùng payment và còn ngân sách. Không tạo yêu cầu mới nếu lệnh trước chưa rõ.
3. Nhân viên xác nhận và bấm **Gửi hoàn SePay Sandbox**. Server lưu ý định gửi,
   số tiền và khóa chống trùng trước khi gọi provider. Scheduler chỉ xử lý tiếp
   những lệnh đã được nhân viên gửi; không tự gửi mọi yêu cầu hoàn PENDING.
4. `PENDING`/`UNKNOWN` vẫn giữ ngân sách; không được xác nhận/hủy thủ công. Bấm
   đồng bộ hoặc chờ retry, luôn dùng cùng giao dịch/số tiền/khóa. Timeout/5xx
   không chứng minh lệnh thất bại và không cho phép tạo khóa gửi mới.
5. Chỉ kết quả thành công đã xác minh mới chuyển refund sang SUCCEEDED. Thất bại
   chắc chắn được ghi riêng; không dùng ghi chú sự kiện để sửa số tiền. Mã hoàn
   SePay phục vụ tra cứu; payment gốc vẫn SUCCEEDED.

Sandbox có thể trả `original_reference=null` trong kết quả hoàn. Backend chỉ chấp
nhận thiếu tham chiếu này khi response khớp chính xác UUID giao dịch đã gửi, cùng
số tiền, VND và mã hoàn đã xác minh. Nếu provider có trả tham chiếu nhưng sai thì
vẫn từ chối; không bỏ kiểm tra hoặc tự xác nhận bằng note.

Luồng này chỉ gọi `userapi-sandbox.sepay.vn`. Khả năng hoàn trên Sandbox không
chứng minh tài khoản thật đã được SePay mở hoàn online. Không quét QR này bằng
ứng dụng ngân hàng để chuyển tiền thật. Yêu cầu chưa được provider execution nhận
vẫn theo quy trình [hoàn thủ công](api/TV3_MANUAL_REFUNDS_CONTRACT.md).

## Cấu hình worker

Các biến sau nằm dưới prefix Spring `commerce.payments.sepay`; helper nạp từ file
local được Git bỏ qua. `COMMERCE_JOBS_ENABLED=false` tắt scheduler nền.

- `SEPAY_AUTOMATION_ENABLED=true`: bật đối soát/hết hạn SePay tự động khi có token.
- `SEPAY_PAYMENT_TTL_SECONDS=1800`: hạn cho lần mới, phạm vi 60..86400; không backfill.
- `SEPAY_RECONCILIATION_INTERVAL_SECONDS=60`: chu kỳ kiểm tra, phạm vi 30..3600 giây.
- `SEPAY_BATCH_SIZE=5`: giới hạn mỗi lượt (1..20), tránh quét/gọi API không giới hạn.
- `SEPAY_REFUNDS_ENABLED=false`: chỉ bật khi sẵn sàng thử API hoàn; không tự gửi yêu cầu mới.
- `SEPAY_LATE_RECONCILIATION_HOURS=72`: cửa sổ tìm tiền muộn sau hủy/hết hạn (1..720 giờ).

Không có API token hoặc API không xác minh được thì không tự hủy vì hết hạn.
Không đưa token, HMAC hoặc khóa gửi hoàn vào frontend, log hay OpenAPI example.

## Nghiệm thu cục bộ ngày25/11/2026

- Java:252ca,251đạt và1ca AI live tùy chọn bỏ qua; đủ18lớp integration MariaDB.
- React:186/186đạt trong17file; kiểm thử công cụ vận hành 42/42đạt. Gói JAR cuối build thành công.
- SePay Sandbox thật: gửi lại đúng intent/khóa đã lưu sau kết quả chưa rõ, provider trả SUCCEEDED và app cập nhật hoàn thành. Tra cứu chi tiết riêng xác minh đúng mã hoàn, UUID giao dịch, số tiền và VND. Không có chuyển tiền thật.
- Response Sandbox có thể để `original_reference` null; đã tương thích bằng UUID giao dịch khớp chính xác, không chấp nhận tham chiếu có giá trị sai. Timeout vẫn giữ ngân sách và cùng khóa retry.

Chrome cục bộ trên JAR cuối, sau hai sửa lỗi khôi phục UI, đã đạt 10/10 (exit 0, khoảng 1,1 phút). Kết quả hosted CI được theo dõi trên PR tương ứng; kiểm thử cục bộ không thay thế GitHub Actions. Các log/khóa/UUID và dữ liệu giao dịch riêng không đưa vào tài liệu hoặc artifact công khai.

## Kiểm thử và giới hạn

Java dùng DB `furniture_shop_test_*` riêng và V001–V012; các bài kiểm tra bao gồm
chữ ký/raw bytes, thời gian, gửi trùng, tranh chấp hủy/thu, rollback audit, hai
nguồn API/Webhook, sai dữ liệu và timeout phần body HTTP. React kiểm tra QR/đã thu,
thử lại yêu cầu và đối soát của nhân viên. Kiểm thử mô phỏng HTTP được ghi rõ là
stand-in, không thay cho bằng chứng kết nối SePay thật.

Phiên bản này hỗ trợ tài khoản chính VietinBank Test, không hỗ trợ nhận qua VA,
không gọi API Production và không chia một khoản thanh toán thành nhiều lần
chuyển. Hoàn Sandbox chỉ áp dụng giao dịch/provider đã xác minh và được hỗ trợ. Nhận tiền không tự thay việc xác nhận giao hàng/lắp
đặt hay điều kiện hoàn thành đơn. Job SePay chỉ hủy khi đủ các điều kiện an toàn nêu trên; đơn ngoài chính sách
vẫn cần nhân viên/khách xử lý qua nghiệp vụ hiện có.

Tài liệu nguồn: [Test mode](https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh),
[HMAC](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc),
[API v2](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/chi-tiet),
[CLI](https://developer.sepay.vn/vi/agents/cli/webhook-listen),
[danh sách giao dịch](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach),
[hoàn theo giao dịch](https://developer.sepay.vn/vi/sepay-api/v2/hoan-tien/hoan-tien-giao-dich),
[tra cứu hoàn](https://developer.sepay.vn/vi/sepay-api/v2/hoan-tien/chi-tiet).

QR PNG được tạo offline bằng ZXing tại endpoint có kiểm tra chủ đơn/quyền nhân viên, `Cache-Control: no-store`. Không gửi tài khoản, số tiền hay nội dung đến dịch vụ ảnh ngoài. Mẫu payload/CRC được đối chiếu ví dụ công khai của VietQR và ảnh được giải mã lại trong kiểm thử.
