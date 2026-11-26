# Catalog demo Nếp Nhà

`scripts/seed_demo_catalog.py` thêm 50 sản phẩm và 50 SKU vào database demo `furniture_shop_tv3_dev`. Dữ liệu được viết riêng cho project: 8 danh mục, 3 phòng, 2 chất liệu, thương hiệu demo, giá, mức giá so sánh cho 20 sản phẩm, kích thước, hướng dẫn bảo quản, bảo hành, thông số, ảnh và tồn kho ban đầu ở kho `MAIN`.

Mỗi SKU dùng một tệp ảnh khác nhau trong `frontend/public/images/catalog` (50 ảnh, 50 SHA-256 khác nhau). Nguồn gồm ảnh gốc trong project, ảnh StockSnap theo giấy phép CC0 và ảnh Unsplash theo Unsplash License; xem [bảng nguồn ảnh](demo-catalog-image-sources.md). Ảnh là minh họa, không phải hình chính xác của từng SKU; giá/kích thước chỉ là dữ liệu demo. Không dùng ảnh hoặc mô tả của Exclusivia.

Chạy sau khi tạo schema V001–V012 và kho `MAIN`:

```powershell
$env:DB_PASSWORD = '<mật khẩu MariaDB của bạn>'
python scripts/seed_demo_catalog.py
```

Có thể đặt thêm `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `MARIADB_CLIENT`. Script chỉ chấp nhận `furniture_shop_tv3_dev` hoặc `furniture_shop_test_ci_demo`; để chọn database CI, đặt `DB_NAME`. Chạy lại không tạo trùng sản phẩm, SKU hay ảnh và không đặt lại lượng tồn kho đã thay đổi.

Trang `/sale` hiển thị tất cả sản phẩm đang bán, tìm kiếm, lọc danh mục, sắp xếp và phân trang. Trang `/products/{slug}` hiển thị ảnh, mô tả, SKU, thông số, giá, tồn kho và cho phép thêm vào giỏ. API công khai: `GET /api/catalog/categories`, `GET /api/catalog/products`, `GET /api/catalog/products/{slug}`.

Menu Phòng khách, Phòng ăn, Phòng ngủ mở `/sale?room=phong-khach`, `/sale?room=phong-an`, `/sale?room=phong-ngu` và có thể lọc tiếp theo danh mục. Lọc phòng dùng liên kết `product_rooms`; tủ lưu trữ demo được gắn cả Phòng khách và Phòng ngủ. Khu “Món đồ cho ngày thường đẹp hơn” trên Home ưu tiên ba SKU demo `NN-DEMO-01-04`, `NN-DEMO-03-05`, `NN-DEMO-04-04`, có ảnh khác ba ảnh cảm hứng phía trên và dẫn đến trang chi tiết tương ứng.

Khi đổi danh mục hoặc trang, danh sách hiện tại được giữ trong lúc tải để tránh giật bố cục. Kết quả vừa xem được hiện ngay khi quay lại và làm mới ở nền (cache giao diện 15 giây); tồn kho và giá khi mua vẫn do backend kiểm tra từ MariaDB.
