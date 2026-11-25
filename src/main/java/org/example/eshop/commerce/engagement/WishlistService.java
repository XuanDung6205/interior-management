package org.example.eshop.commerce.engagement;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

// Xử lý danh sách sản phẩm yêu thích của từng khách hàng trên bảng wishlist_items
@Service
@Transactional(readOnly = true)
public class WishlistService {
    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public WishlistService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
    }

    // Dữ liệu hiển thị từng sản phẩm yêu thích kèm giá SKU thấp nhất và tổng tồn khả dụng
    public record WishlistItemView(
            long productId, String productName, String productSlug, String categoryName,
            String imageUrl, Long defaultVariantId, String minPrice, int totalAvailable,
            boolean active, String addedAt) {}

    // Lấy toàn bộ danh sách yêu thích của tài khoản đang đăng nhập
    public List<WishlistItemView> listMine() {
        long userId = requireUser();
        return jdbc.query("""
                SELECT w.product_id, p.name AS product_name, p.slug AS product_slug,
                       c.name AS category_name, (p.status = 'ACTIVE') AS active, w.created_at,
                       (SELECT pi.url FROM product_images pi
                        WHERE pi.product_id = p.id
                        ORDER BY pi.sort_order ASC, pi.id ASC LIMIT 1) AS image_url,
                       (SELECT pv.id FROM product_variants pv
                        WHERE pv.product_id = p.id AND pv.status = 'ACTIVE'
                        ORDER BY pv.price ASC, pv.id ASC LIMIT 1) AS default_variant_id,
                       (SELECT MIN(pv.price) FROM product_variants pv
                        WHERE pv.product_id = p.id AND pv.status = 'ACTIVE') AS min_price,
                       COALESCE((SELECT SUM(ib.available_quantity)
                                 FROM inventory_balances ib
                                 JOIN product_variants pv ON pv.id = ib.variant_id
                                 WHERE pv.product_id = p.id AND pv.status = 'ACTIVE'), 0) AS total_available
                FROM wishlist_items w
                JOIN products p ON p.id = w.product_id
                JOIN categories c ON c.id = p.category_id
                WHERE w.user_id = ?
                ORDER BY w.created_at DESC, w.product_id DESC
                """, WishlistService::mapRow, userId);
    }

    // Thêm sản phẩm vào danh sách yêu thích (bấm trùng vẫn an toàn nhờ INSERT IGNORE)
    @Transactional
    public List<WishlistItemView> add(long productId) {
        long userId = requireUser();
        if (productId <= 0) {
            throw CommerceException.badRequest("INVALID_PRODUCT", "Mã sản phẩm không hợp lệ.");
        }
        // Chỉ cho lưu sản phẩm đang kinh doanh để tránh thêm sản phẩm nháp hoặc ngừng bán
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE id = ? AND status = 'ACTIVE'",
                Integer.class, productId);
        if (found == null || found == 0) {
            throw CommerceException.notFound("Không tìm thấy sản phẩm đang bán.");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update(
                "INSERT IGNORE INTO wishlist_items(user_id, product_id, created_at) VALUES (?, ?, ?)",
                userId, productId, now);
        return listMine();
    }

    // Bỏ sản phẩm khỏi danh sách yêu thích của chính user đang đăng nhập
    @Transactional
    public void remove(long productId) {
        long userId = requireUser();
        jdbc.update("DELETE FROM wishlist_items WHERE user_id = ? AND product_id = ?", userId, productId);
    }

    // Lấy userId từ session máy chủ, tuyệt đối không tin userId client gửi lên
    private long requireUser() {
        Long userId = actors.currentActor().userId();
        if (userId == null) {
            throw CommerceException.forbidden("Bạn cần đăng nhập để sử dụng danh sách yêu thích.");
        }
        return userId;
    }

    private static WishlistItemView mapRow(ResultSet rs, int row) throws SQLException {
        BigDecimal price = rs.getBigDecimal("min_price");
        long defaultVariant = rs.getLong("default_variant_id");
        Long variantId = rs.wasNull() ? null : defaultVariant;
        Timestamp ts = rs.getTimestamp("created_at");
        String addedAt = ts == null ? null : ts.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
        return new WishlistItemView(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("product_slug"),
                rs.getString("category_name"),
                rs.getString("image_url"),
                variantId,
                price == null ? null : price.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                Math.max(0, rs.getInt("total_available")),
                rs.getBoolean("active"),
                addedAt);
    }
}
