package org.example.eshop.commerce.ai;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.content.ContentValidation;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.example.eshop.commerce.vector.CatalogVectorSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Gợi ý sản phẩm nội thất 3 tầng: cùng danh mục, cá nhân hóa theo hành vi 30 ngày, và phối đồ bổ trợ theo không gian
@Service
@Transactional(readOnly = true)
public class FurnitureRecommendationService {
    private static final int MAX_LIMIT = 16;

    // Bảng ánh xạ danh mục bổ trợ cho đồ nội thất (dò theo từ khóa trên slug/tên danh mục)
    private static final Map<String, List<String>> COMPLEMENTARY_KEYWORDS = Map.ofEntries(
            Map.entry("sofa", List.of("ban-tra", "bàn trà", "tham", "thảm", "den", "đèn", "ke-tivi", "kệ", "ghe", "ghế")),
            Map.entry("ban-tra", List.of("sofa", "ghe", "ghế", "tham", "thảm", "ke-tivi", "kệ")),
            Map.entry("giuong", List.of("tu-dau-giuong", "tủ đầu giường", "tu-quan-ao", "tủ quần áo", "ban-trang-diem", "đèn")),
            Map.entry("ban-an", List.of("ghe-an", "ghế ăn", "ghe", "ghế", "tu-bep", "đèn")),
            Map.entry("ghe", List.of("ban", "bàn", "sofa", "den", "đèn")),
            Map.entry("tu", List.of("giuong", "giường", "ban", "bàn", "ke", "kệ")),
            Map.entry("ban-lam-viec", List.of("ghe", "ghế", "ke-sach", "kệ", "den", "đèn")));

    // SQL gốc dùng chung: luôn lọc sản phẩm ACTIVE, biến thể ACTIVE và còn tồn khả dụng > 0
    private static final String PRODUCT_SELECT = """
            SELECT p.id AS product_id, p.name AS product_name, p.slug AS product_slug,
                   c.id AS category_id, c.name AS category_name, c.slug AS category_slug,
                   (SELECT pi.url FROM product_images pi
                    WHERE pi.product_id = p.id
                    ORDER BY pi.sort_order ASC, pi.id ASC LIMIT 1) AS image_url,
                   (SELECT pv.id FROM product_variants pv
                    JOIN inventory_balances ib ON ib.variant_id = pv.id
                    JOIN warehouses w ON w.id = ib.warehouse_id AND w.is_active = 1
                    WHERE pv.product_id = p.id AND pv.status = 'ACTIVE' AND ib.available_quantity > 0
                    ORDER BY pv.price ASC, pv.id ASC LIMIT 1) AS default_variant_id,
                   (SELECT MIN(pv.price) FROM product_variants pv
                    WHERE pv.product_id = p.id AND pv.status = 'ACTIVE') AS min_price,
                   COALESCE((SELECT SUM(ib.available_quantity)
                             FROM inventory_balances ib
                             JOIN warehouses w ON w.id = ib.warehouse_id AND w.is_active = 1
                             JOIN product_variants pv ON pv.id = ib.variant_id
                             WHERE pv.product_id = p.id AND pv.status = 'ACTIVE'), 0) AS total_available
            FROM products p
            JOIN categories c ON c.id = p.category_id
            WHERE p.status = 'ACTIVE'
              AND EXISTS (SELECT 1 FROM product_variants pv
                          JOIN inventory_balances ib ON ib.variant_id = pv.id
                          JOIN warehouses w ON w.id = ib.warehouse_id AND w.is_active = 1
                          WHERE pv.product_id = p.id AND pv.status = 'ACTIVE' AND ib.available_quantity > 0)
            """;

    private static final Duration REC_CACHE_TTL = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;
    private final Tv5Cache cache;
    private CatalogVectorSearch vectorSearch;

    @Autowired
    public void setCatalogVectorSearch(CatalogVectorSearch vectorSearch) { this.vectorSearch = vectorSearch; }

    public FurnitureRecommendationService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock, Tv5Cache cache) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
        this.cache = cache;
    }

    public record RecommendationItem(
            long productId, String productName, String productSlug, String categoryName,
            Long defaultVariantId, String minPrice, String imageUrl, int totalAvailable, String reason) {}

    public record RecommendationResponse(List<RecommendationItem> items) {}

    // Điều phối 3 tầng gợi ý (key cache tách riêng user/session để không rò rỉ sở thích giữa các khách)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RecommendationResponse recommend(Long productId, String sessionId, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw CommerceException.badRequest("INVALID_LIMIT", "Số lượng gợi ý phải từ 1 đến " + MAX_LIMIT + ".");
        }
        // Provider IO runs without a read transaction. Hydrate these IDs from current catalog/stock,
        // bypassing the recommendation cache so indexed documents never supply stale prices.
        if (vectorSearch != null && productId != null && productId > 0) {
            var descriptions = jdbc.queryForList("SELECT LEFT(CONCAT_WS(' ',name,short_description),1000) FROM products WHERE id=? AND status='ACTIVE'", String.class, productId);
            if (!descriptions.isEmpty()) {
                var ids = vectorSearch.searchProducts(descriptions.get(0), limit + 1);
                var semantic = new LinkedHashMap<Long, RecommendationItem>();
                for (long id : ids) {
                    if (id == productId || semantic.size() >= limit) continue;
                    var live = jdbc.query(PRODUCT_SELECT + " AND p.id=?", (rs, n) -> mapItem(rs, "Sản phẩm tương đồng trong danh mục"), id);
                    for (var item : live) semantic.putIfAbsent(item.productId(), item);
                }
                if (!semantic.isEmpty()) return new RecommendationResponse(List.copyOf(semantic.values()));
            }
        }
        Long userId = actors.currentActor().userId();
        String safeSession = ContentValidation.trimToNull(sessionId);
        String scope = userId != null ? "u:" + userId : (safeSession != null ? "s:" + safeSession : "anon");
        String cacheKey = (productId == null ? "none" : productId) + ":" + limit + ":" + scope;
        return cache.getOrLoad(Tv5Cache.RECOMMENDATIONS, cacheKey, REC_CACHE_TTL,
                RecommendationResponse.class, () -> computeRecommendations(productId, safeSession, limit));
    }

    private RecommendationResponse computeRecommendations(Long productId, String sessionId, int limit) {
        var picked = new LinkedHashMap<Long, RecommendationItem>();
        var excluded = new LinkedHashSet<Long>();
        if (productId != null && productId > 0) {
            excluded.add(productId);
        }

        // Tầng 1: lấy sản phẩm thay thế cùng danh mục với món khách đang xem
        CategoryRef currentCat = productId == null ? null : findProductCategory(productId);
        if (currentCat != null) {
            var sameCategory = jdbc.query(
                    PRODUCT_SELECT + " AND p.category_id = ? AND p.id <> ? ORDER BY p.id DESC LIMIT ?",
                    (rs, row) -> mapItem(rs, "Cùng danh mục " + currentCat.name()),
                    currentCat.id(), productId, limit);
            addUnique(picked, sameCategory, excluded, limit);
        }

        // Tầng 2: cá nhân hóa dựa trên lịch sử tracking 30 ngày gần nhất (user_id hoặc sessionId khách vãng lai)
        if (picked.size() < limit) {
            var personalized = loadPersonalized(sessionId, excluded, limit - picked.size());
            addUnique(picked, personalized, excluded, limit);
        }

        // Tầng 3: gợi ý nội thất bổ trợ / mua kèm theo nhóm không gian sống
        if (picked.size() < limit && currentCat != null) {
            var complementary = loadComplementary(currentCat, excluded, limit - picked.size());
            addUnique(picked, complementary, excluded, limit);
        }

        // Fallback cuối: nếu catalog ít hoặc khách mới hoàn toàn thì bổ sung sản phẩm đang bán còn hàng
        if (picked.size() < limit) {
            var fallback = jdbc.query(
                    PRODUCT_SELECT + " ORDER BY p.id DESC LIMIT ?",
                    (rs, row) -> mapItem(rs, "Gợi ý nổi bật tại Nếp Nhà"),
                    limit + excluded.size());
            addUnique(picked, fallback, excluded, limit);
        }

        return new RecommendationResponse(List.copyOf(picked.values()));
    }

    // Phân tích hành vi 30 ngày gần nhất theo ma trận trọng số (xem=1, click=2, giỏ=5, đặt đơn=7)
    private List<RecommendationItem> loadPersonalized(String sessionId, Set<Long> excluded, int needed) {
        Long userId = actors.currentActor().userId();
        String safeSession = ContentValidation.trimToNull(sessionId);
        if (userId == null && safeSession == null) {
            return List.of();
        }

        LocalDateTime since = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(30);
        StringBuilder sql = new StringBuilder("""
                SELECT event_type, product_id, category_id, keyword
                FROM tracking_events
                WHERE occurred_at >= ? AND (
                """);
        var args = new ArrayList<Object>();
        args.add(since);
        if (userId != null && safeSession != null) {
            sql.append("user_id = ? OR session_id = ?");
            args.add(userId);
            args.add(safeSession);
        } else if (userId != null) {
            sql.append("user_id = ?");
            args.add(userId);
        } else {
            sql.append("session_id = ?");
            args.add(safeSession);
        }
        sql.append(") ORDER BY occurred_at DESC LIMIT 100");

        var events = jdbc.queryForList(sql.toString(), args.toArray());
        if (events.isEmpty()) {
            return List.of();
        }

        var categoryScores = new LinkedHashMap<Long, Integer>();
        var keywords = new LinkedHashSet<String>();
        for (var e : events) {
            String type = String.valueOf(e.get("event_type"));
            int weight = eventWeight(type);
            Object catObj = e.get("category_id");
            if (catObj instanceof Number num) {
                categoryScores.merge(num.longValue(), weight, Integer::sum);
            }
            Object kwObj = e.get("keyword");
            if (kwObj != null && keywords.size() < 2) {
                String kw = kwObj.toString().trim();
                if (!kw.isEmpty()) {
                    keywords.add(kw.toLowerCase(Locale.ROOT));
                }
            }
        }

        var result = new ArrayList<RecommendationItem>();
        // Ưu tiên tìm theo từ khóa khách vừa tìm kiếm gần đây
        for (String kw : keywords) {
            if (result.size() >= needed) break;
            String like = "%" + kw.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
            var rows = jdbc.query(
                    PRODUCT_SELECT + " AND (LOWER(p.name) LIKE ? ESCAPE '=' OR LOWER(COALESCE(p.short_description, '')) LIKE ? ESCAPE '=') ORDER BY p.id DESC LIMIT ?",
                    (rs, row) -> mapItem(rs, "Phù hợp với tìm kiếm gần đây"),
                    like, like, needed + excluded.size());
            result.addAll(rows);
        }

        // Tiếp theo lấy theo danh mục có điểm quan tâm cao nhất
        if (result.size() < needed && !categoryScores.isEmpty()) {
            Long topCat = categoryScores.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (topCat != null) {
                var rows = jdbc.query(
                        PRODUCT_SELECT + " AND p.category_id = ? ORDER BY p.id DESC LIMIT ?",
                        (rs, row) -> mapItem(rs, "Gợi ý theo sở thích của bạn"),
                        topCat, needed + excluded.size());
                result.addAll(rows);
            }
        }
        return result;
    }

    // Tìm sản phẩm ở các danh mục bổ trợ nội thất (ví dụ xem Sofa -> gợi ý Bàn trà, Thảm, Đèn)
    private List<RecommendationItem> loadComplementary(CategoryRef currentCat, Set<Long> excluded, int needed) {
        String keySource = (currentCat.slug() + " " + currentCat.name()).toLowerCase(Locale.ROOT);
        var relatedKeywords = new LinkedHashSet<String>();
        for (var entry : COMPLEMENTARY_KEYWORDS.entrySet()) {
            if (keySource.contains(entry.getKey())) {
                relatedKeywords.addAll(entry.getValue());
            }
        }
        if (relatedKeywords.isEmpty()) {
            return List.of();
        }

        var results = new ArrayList<RecommendationItem>();
        for (String kw : relatedKeywords) {
            if (results.size() >= needed) break;
            String like = "%" + kw + "%";
            var rows = jdbc.query(
                    PRODUCT_SELECT + " AND p.category_id <> ? AND (LOWER(c.slug) LIKE ? OR LOWER(c.name) LIKE ?) ORDER BY p.id DESC LIMIT 2",
                    (rs, row) -> mapItem(rs, "Phối hợp đẹp cùng " + currentCat.name()),
                    currentCat.id(), like, like);
            results.addAll(rows);
        }
        return results;
    }

    private CategoryRef findProductCategory(long productId) {
        var rows = jdbc.query("""
                SELECT c.id, c.name, c.slug
                FROM products p
                JOIN categories c ON c.id = p.category_id
                WHERE p.id = ?
                """, (rs, row) -> new CategoryRef(rs.getLong("id"), rs.getString("name"), rs.getString("slug")), productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int eventWeight(String eventType) {
        return switch (eventType) {
            case "ORDER_PLACED" -> 7;
            case "ADD_TO_CART" -> 5;
            case "CART_UPDATE" -> 3;
            case "ADD_TO_CART_CLICK", "CART_UPDATE_CLICK" -> 2;
            default -> 1;
        };
    }

    private static void addUnique(
            Map<Long, RecommendationItem> picked,
            List<RecommendationItem> candidates,
            Set<Long> excluded,
            int limit) {
        for (var item : candidates) {
            if (picked.size() >= limit) return;
            if (excluded.contains(item.productId())) continue;
            if (!picked.containsKey(item.productId())) {
                picked.put(item.productId(), item);
                excluded.add(item.productId());
            }
        }
    }

    private static RecommendationItem mapItem(ResultSet rs, String reason) throws SQLException {
        BigDecimal price = rs.getBigDecimal("min_price");
        long defVar = rs.getLong("default_variant_id");
        Long variantId = rs.wasNull() ? null : defVar;
        return new RecommendationItem(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("product_slug"),
                rs.getString("category_name"),
                variantId,
                price == null ? null : price.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                rs.getString("image_url"),
                Math.max(0, rs.getInt("total_available")),
                reason);
    }

    private record CategoryRef(long id, String name, String slug) {}
}
