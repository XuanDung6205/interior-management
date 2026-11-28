# Catalog demo Nếp Nhà

`scripts/seed_demo_catalog.py` thêm 50 sản phẩm và 62 SKU vào database demo `furniture_shop_tv3_dev`. Dữ liệu được viết riêng cho project: 8 danh mục con + 3 danh mục cha, 3 phòng, 4 chất liệu và 3 bộ sưu tập, thương hiệu demo, giá, mức giá so sánh cho 20 sản phẩm, kích thước, hướng dẫn bảo quản, bảo hành, thông số, ảnh và tồn kho ban đầu ở kho `MAIN`.

Mỗi SKU gốc trong nhóm 50 mẫu dùng một tệp ảnh khác nhau trong `frontend/public/images/catalog` (50 ảnh, 50 SHA-256 khác nhau). Nguồn gồm ảnh gốc trong project, ảnh StockSnap theo giấy phép CC0 và ảnh Unsplash theo Unsplash License; xem [bảng nguồn ảnh](demo-catalog-image-sources.md). Ảnh là minh họa, không phải hình chính xác của từng SKU; giá/kích thước chỉ là dữ liệu demo. Không dùng ảnh hoặc mô tả của Exclusivia.

Chạy sau khi tạo schema V001–V015 và kho `MAIN`:

```powershell
$env:DB_PASSWORD = '<mật khẩu MariaDB của bạn>'
python scripts/seed_demo_catalog.py
```

Có thể đặt thêm `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `MARIADB_CLIENT`. Mặc định script nạp vào `furniture_shop_tv3_dev`; CI đặt `DB_NAME=furniture_shop_test_ci_demo`. Chạy lại không tạo trùng sản phẩm, SKU hay ảnh và không đặt lại lượng tồn kho đã thay đổi.

Nếu nhóm dùng database mặc định `furniture_shop`, hãy sao lưu và kiểm tra bản sao lưu trước khi nạp dữ liệu mẫu. Sau đó chỉ định rõ cả tên DB và cờ cho phép:

```powershell
$env:DB_PASSWORD = Read-Host 'Mật khẩu MariaDB'
python scripts/seed_demo_catalog.py --database furniture_shop --allow-main-db
```

Lệnh này thêm 50 sản phẩm / 62 SKU demo vào chính `furniture_shop`; nó không tạo schema hay tài khoản. Khi chạy lại, script có thể bổ sung mô tả/ảnh/liên kết demo nhưng giữ giá, trạng thái, ID SKU, tồn kho và lịch sử đơn đã có. Mỗi máy dùng MariaDB riêng vẫn phải chạy lệnh riêng; clone Git chỉ mang script và 50 ảnh.

Trang `/sale` hiển thị tất cả sản phẩm đang bán, tìm kiếm, lọc danh mục, sắp xếp và phân trang. Trang `/products/{slug}` hiển thị ảnh, mô tả, SKU, thông số, giá, tồn kho và cho phép thêm vào giỏ. API công khai: `GET /api/catalog/categories`, `GET /api/catalog/products`, `GET /api/catalog/products/{slug}`.

Menu Phòng khách, Phòng ăn, Phòng ngủ mở `/sale?room=phong-khach`, `/sale?room=phong-an`, `/sale?room=phong-ngu` và có thể lọc tiếp theo danh mục. Lọc phòng dùng liên kết `product_rooms`; tủ lưu trữ demo được gắn cả Phòng khách và Phòng ngủ. Khu “Món đồ cho ngày thường đẹp hơn” trên Home ưu tiên ba SKU demo `NN-DEMO-01-04`, `NN-DEMO-03-05`, `NN-DEMO-04-04`, có ảnh khác ba ảnh cảm hứng phía trên và dẫn đến trang chi tiết tương ứng.

Khi đổi danh mục hoặc trang, danh sách hiện tại được giữ trong lúc tải để tránh giật bố cục. Kết quả vừa xem được hiện ngay khi quay lại và làm mới ở nền (cache giao diện 15 giây); tồn kho và giá khi mua vẫn do backend kiểm tra từ MariaDB.

TV2 bổ sung 12 SKU cho 6 mẫu đại diện, 3 bộ sưu tập và gallery ảnh bố trí được ghi nhãn tham khảo. Ảnh chung mô tả kiểu sản phẩm; màu SKU được mô phỏng theo lựa chọn, không tuyên bố ảnh là màu thực tế. Có 50 thumbnail trong `images/catalog/thumbs` dùng cho danh sách, giảm 86,18% tổng byte so với 50 ảnh gốc (1.925.215 / 13.928.382 byte). Seed lại giữ SKU ID, giá/trạng thái đã sửa và lượng tồn; không tạo lại đơn. [Hướng dẫn chạy toàn bộ qua Docker](TV2_CATALOG_HANDOFF.md).
