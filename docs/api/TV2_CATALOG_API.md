# API catalog — Thành viên 2

Base `/api`, cùng session/CSRF với ứng dụng hiện tại. Không gửi token/password qua URL. HTTP 400 validation, 401 chưa đăng nhập, 403 thiếu quyền/CSRF, 404 không có dữ liệu, 409 xung đột unique/version. Thông báo lỗi theo `ApiError` hiện có.

## Public GET

- `/catalog/categories?room=` → danh sách `{id,name,slug,parentId}` đang hoạt động (cây đầy đủ, lọc phòng tại sản phẩm).
- `/catalog/facets` → `{categories,rooms,brands,materials,collections,colors,sizes}`. Các nhóm phân loại có `id,name,slug,description`; màu/size là danh sách chuỗi.
- `/catalog/products` → `{items,page,size,totalElements,totalPages}`.
- `/catalog/products/{slug}` → giữ nguyên DTO chi tiết cũ, ảnh bổ sung `variantId` nullable. Chỉ sản phẩm/SKU ACTIVE và danh mục đang hiển thị.

Bộ lọc `/products`:

| Tham số | Ý nghĩa |
| --- | --- |
| query | Tên/mô tả ngắn/SKU, tối đa 200 ký tự; `%`/`_` tìm literal |
| category | Slug danh mục; bao gồm con/cháu đang hoạt động |
| room, brand, collection, material | Slug tương ứng; room/collection chấp nhận alias bỏ tiền tố `demo-` |
| color | Tên màu chính xác |
| sizeLabel | Nhãn kích thước chính xác; `size` dành riêng cho phân trang |
| minPrice, maxPrice | Khoảng tiền VND, không âm, tối đa 2 số lẻ |
| minWidth, maxWidth, minDepth, maxDepth, minHeight, maxHeight | Khoảng số đo mm |
| sort | `newest` (mặc định), `price_asc`, `price_desc`, `name` |
| page, size | Trang từ 0, size 1–100, mặc định 12 |

Các điều kiện giá/màu/chất liệu/size/số đo/từ khóa SKU được áp dụng trên **cùng một SKU ACTIVE**. Card dùng SKU rẻ nhất thỏa điều kiện, không dùng giá một SKU khác để quảng cáo kết quả. Giá trả chuỗi 2 số lẻ. `availableQuantity` batch đọc live từ DB. Trình duyệt truyền `?variant={variantId}` khi mở card đã lọc để chọn đúng SKU ở detail.

## Admin/Staff

Tất cả endpoint `/admin/catalog/**` yêu cầu ADMIN hoặc STAFF. Mutation cần CSRF; client dùng `createApiClient` hiện có.

- `GET /admin/catalog/options` → năm nhóm phân loại, bao gồm cả dữ liệu ẩn; tên field camelCase, `active` boolean.
- `POST /admin/catalog/taxonomies/{kind}` → 201, `PUT .../{kind}/{id}` → 200; kind allowlist `categories|rooms|brands|materials|collections`.
- Taxonomy: `{name,slug,description,active,parentId?,imageUrl?,sortOrder?,logoUrl?,countryCode?,careInstructions?}`. Field áp dụng tùy loại; danh mục chặn cycle trong transaction.
- `GET /admin/catalog/products?query=&page=0&size=12` → page quản trị, cả DRAFT/ARCHIVED.
- `GET /admin/catalog/products/{id}` → full aggregate có `version`, dùng để sửa.
- `POST /admin/catalog/products` → 201; `PUT .../products/{id}` → 200. Trả full aggregate sau lưu.

Payload sản phẩm:

```json
{
  "version": 0,
  "name": "Bàn An Nhiên",
  "slug": "ban-an-nhien",
  "categoryId": 1,
  "brandId": null,
  "status": "DRAFT",
  "shortDescription": "Bàn gỗ cho góc làm việc.",
  "description": "Nội dung văn bản thuần.",
  "careInstructions": "Lau bằng khăn mềm.",
  "warrantyMonths": 24,
  "assemblyRequired": true,
  "seoTitle": "Bàn An Nhiên | Nếp Nhà",
  "seoDescription": "Nội thất gỗ cho nhà bạn.",
  "roomIds": [],
  "collectionIds": [],
  "variants": [{
    "sku": "AN-NHIEN-120",
    "variantKey": "oak-120",
    "name": "Sồi 120 cm",
    "status": "ACTIVE",
    "colorName": "Gỗ tự nhiên",
    "colorHex": "#AA815C",
    "sizeLabel": "120 cm",
    "widthMm": 1200,
    "depthMm": 600,
    "heightMm": 750,
    "weightKg": 20,
    "price": "2500000.00",
    "compareAtPrice": null,
    "leadTimeDays": 3,
    "materialIds": []
  }],
  "images": [{"url": "/images/catalog/NN-DEMO-03-01.jpg", "altText": "Ảnh minh họa bàn", "variantId": null, "sortOrder": 0}],
  "specifications": [{"name": "Tải trọng", "value": "40", "unit": "kg", "sortOrder": 0}]
}
```

Tạo mới không gửi `id` SKU; cập nhật giữ `id` nhận từ GET. SKU status `ACTIVE|INACTIVE|DISCONTINUED`, sản phẩm `DRAFT|ACTIVE|DISCONTINUED|ARCHIVED`. Giá tham chiếu phải ≥ giá bán; số đo/khối lượng nếu có phải dương. Tối đa 50 SKU, 30 ảnh, 80 thông số mỗi sản phẩm. Các danh sách liên kết phải được gửi, danh sách rỗng nghĩa bỏ liên kết. SKU cũ vắng khỏi payload chuyển INACTIVE, không xóa.

Ảnh chấp nhận `/images/...` hoặc HTTP(S), chặn path traversal, ký tự điều khiển và các scheme thực thi. Server lưu URL, không tải URL do admin nhập. Gallery lọc ảnh thuộc SKU khác. Ảnh ngoài do trình duyệt tải; ảnh demo được đóng gói cục bộ. Nội dung hiển thị dạng văn bản, không render HTML tùy ý.

`version` bắt buộc khi PUT. Giao dịch thành công tăng version; version cũ trả `CATALOG_STALE`. Lỗi một SKU/ảnh/tham chiếu rollback cả aggregate. Unique/FK được giữ ở DB làm lớp bảo vệ đồng thời. API chủ động không hard-delete sản phẩm/SKU/taxonomy để giữ hợp đồng với đơn/kho.
