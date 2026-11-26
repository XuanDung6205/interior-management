package org.example.eshop.commerce.engagement;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.content.ContentValidation;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Nghiệp vụ đánh giá sản phẩm sau mua: xác minh đúng chủ đơn đã giao, lưu ảnh và duyệt/phản hồi từ cửa hàng
@Service
@Transactional(readOnly = true)
public class ReviewService {
    private static final Set<String> MODERATION_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "HIDDEN");
    private static final int MAX_IMAGES = 5;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public ReviewService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.actors = actors;
        this.clock = clock;
    }

    public record ReviewImageView(long id, String url, String altText, int sortOrder) {}

    public record ReviewView(
            long id, long orderId, long orderItemId, long productId, String productName,
            String authorName, int rating, String title, String content, String status,
            String staffReply, String repliedByName, String repliedAt, String createdAt,
            List<ReviewImageView> images) {}

    public record ProductReviewsResponse(
            long productId, double averageRating, long totalReviews,
            List<ReviewView> items, int page, int size, int totalPages) {}

    public record ReviewPage(List<ReviewView> items, int page, int size, long totalElements, int totalPages) {}

    // Các dòng sản phẩm đã giao nhưng khách chưa đánh giá để hiển thị nút viết đánh giá
    public record EligibleItemView(
            long orderId, String orderNumber, long orderItemId, long productId,
            String productName, String variantName, String imageUrl, String placedAt) {}

    public record CreateReviewRequest(
            Long orderItemId, Integer rating, String title, String content, List<String> imageUrls) {}

    public record ModerateReviewRequest(String status, String staffReply) {}

    // Lấy danh sách đánh giá đã duyệt (APPROVED) của 1 sản phẩm kèm điểm trung bình
    public ProductReviewsResponse listForProduct(long productId, int page, int size) {
        ContentValidation.checkPage(page, size, null);
        var stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS cnt, COALESCE(AVG(rating), 0) AS avg_rating
                FROM reviews
                WHERE product_id = ? AND status = 'APPROVED'
                """, productId);
        long total = ((Number) stats.get("cnt")).longValue();
        double avg = Math.round(((Number) stats.get("avg_rating")).doubleValue() * 10.0) / 10.0;

        var rows = jdbc.query("""
                SELECT r.id, r.order_id, r.order_item_id, r.product_id, p.name AS product_name,
                       COALESCE(u.full_name, u.username) AS author_name, r.rating, r.title, r.content,
                       r.status, r.staff_reply, COALESCE(rep.full_name, rep.username) AS replied_by_name,
                       r.replied_at, r.created_at
                FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN users u ON u.id = r.user_id
                LEFT JOIN users rep ON rep.id = r.replied_by
                WHERE r.product_id = ? AND r.status = 'APPROVED'
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT ? OFFSET ?
                """, ReviewService::mapRaw, productId, size, (long) page * size);

        int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new ProductReviewsResponse(productId, avg, total, attachImages(rows), page, size, totalPages);
    }

    // Danh sách sản phẩm khách đã nhận hàng thành công và chưa viết đánh giá
    public List<EligibleItemView> listEligibleForCurrentUser() {
        long userId = requireUser();
        return jdbc.query("""
                SELECT o.id AS order_id, o.order_number, oi.id AS order_item_id, oi.product_id,
                       oi.product_name, oi.variant_name, oi.image_url, o.placed_at
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE o.user_id = ?
                  AND EXISTS (SELECT 1 FROM shipment_items si
                              JOIN shipments s ON s.id = si.shipment_id AND s.order_id = si.order_id
                              WHERE si.order_item_id = oi.id AND si.order_id = o.id AND si.quantity > 0
                                AND s.status = 'DELIVERED' AND s.delivered_at IS NOT NULL
                                AND s.delivered_at <= ?)
                  AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.order_item_id = oi.id)
                ORDER BY o.placed_at DESC, oi.id DESC
                LIMIT 50
                """, (rs, row) -> new EligibleItemView(
                rs.getLong("order_id"),
                rs.getString("order_number"),
                rs.getLong("order_item_id"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("variant_name"),
                rs.getString("image_url"),
                toIso(rs.getTimestamp("placed_at"))), userId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    // Danh sách đánh giá mà user hiện tại đã gửi (thấy được cả trạng thái PENDING/APPROVED/HIDDEN)
    public List<ReviewView> listMine() {
        long userId = requireUser();
        var rows = jdbc.query("""
                SELECT r.id, r.order_id, r.order_item_id, r.product_id, p.name AS product_name,
                       COALESCE(u.full_name, u.username) AS author_name, r.rating, r.title, r.content,
                       r.status, r.staff_reply, COALESCE(rep.full_name, rep.username) AS replied_by_name,
                       r.replied_at, r.created_at
                FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN users u ON u.id = r.user_id
                LEFT JOIN users rep ON rep.id = r.replied_by
                WHERE r.user_id = ?
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT 50
                """, ReviewService::mapRaw, userId);
        return attachImages(rows);
    }

    // Khách tạo đánh giá cho 1 dòng sản phẩm đã mua và đã giao
    @Transactional
    public ReviewView create(CreateReviewRequest req) {
        long userId = requireUser();
        if (req == null) {
            throw CommerceException.badRequest("INVALID_REQUEST", "Thiếu nội dung đánh giá.");
        }

        var errors = new LinkedHashMap<String, String>();
        if (req.orderItemId() == null || req.orderItemId() <= 0) {
            errors.put("orderItemId", "Cần chọn dòng sản phẩm đã mua.");
        }
        if (req.rating() == null || req.rating() < 1 || req.rating() > 5) {
            errors.put("rating", "Số sao phải từ 1 đến 5.");
        }
        String title = ContentValidation.optionalText(req.title(), "title", 200, errors);
        String content = ContentValidation.requiredText(req.content(), "content", 3000, errors);

        var safeImages = new ArrayList<String>();
        if (req.imageUrls() != null) {
            if (req.imageUrls().size() > MAX_IMAGES) {
                errors.put("imageUrls", "Tối đa " + MAX_IMAGES + " ảnh cho mỗi đánh giá.");
            } else {
                for (int i = 0; i < req.imageUrls().size(); i++) {
                    String url = ContentValidation.validateUrl(req.imageUrls().get(i), "imageUrls[" + i + "]", false, errors);
                    if (url != null) {
                        safeImages.add(url);
                    }
                }
            }
        }
        ContentValidation.throwIfErrors(errors);

        // Kiểm tra dòng đơn có tồn tại, đúng chủ đơn và đã giao hàng hay chưa
        var items = jdbc.query("""
                SELECT oi.id, oi.order_id, oi.product_id, o.user_id,
                       EXISTS (SELECT 1 FROM shipment_items si
                               JOIN shipments s ON s.id = si.shipment_id AND s.order_id = si.order_id
                               WHERE si.order_item_id = oi.id AND si.order_id = o.id AND si.quantity > 0
                                 AND s.status = 'DELIVERED' AND s.delivered_at IS NOT NULL
                                 AND s.delivered_at <= ?) AS delivered
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE oi.id = ?
                """, (rs, row) -> new OrderItemCheck(
                rs.getLong("id"), rs.getLong("order_id"), rs.getLong("product_id"),
                rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                rs.getBoolean("delivered")), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), req.orderItemId());

        if (items.isEmpty()) {
            throw CommerceException.notFound("Không tìm thấy dòng đơn hàng.");
        }
        OrderItemCheck item = items.get(0);
        if (!Long.valueOf(userId).equals(item.userId())) {
            throw CommerceException.forbidden("Bạn không có quyền đánh giá đơn hàng của người khác.");
        }
        if (!item.delivered()) {
            throw CommerceException.conflict("REVIEW_NOT_ELIGIBLE", "Chỉ được đánh giá sau khi sản phẩm đã giao thành công.");
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(conn -> {
                var ps = conn.prepareStatement("""
                        INSERT INTO reviews(user_id, order_item_id, order_id, product_id,
                                            rating, title, content, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setLong(2, item.orderItemId());
                ps.setLong(3, item.orderId());
                ps.setLong(4, item.productId());
                ps.setInt(5, req.rating());
                ps.setString(6, title);
                ps.setString(7, content);
                ps.setObject(8, now);
                ps.setObject(9, now);
                return ps;
            }, keyHolder);
        } catch (DataIntegrityViolationException ex) {
            // Ràng buộc uq_reviews_order_item đảm bảo mỗi dòng đơn chỉ được đánh giá 1 lần
            throw CommerceException.conflict("REVIEW_ALREADY_EXISTS", "Sản phẩm trong đơn này đã được bạn đánh giá.");
        }

        long reviewId = keyHolder.getKey().longValue();
        for (int i = 0; i < safeImages.size(); i++) {
            jdbc.update(
                    "INSERT INTO review_images(review_id, url, alt_text, sort_order) VALUES (?, ?, ?, ?)",
                    reviewId, safeImages.get(i), title, i);
        }
        return getById(reviewId);
    }

    // Danh sách đánh giá cho nhân viên kiểm duyệt (lọc theo status và từ khóa)
    public ReviewPage listAdmin(String status, String query, int page, int size) {
        ContentValidation.checkPage(page, size, query);
        String st = ContentValidation.trimToNull(status);
        String q = ContentValidation.trimToNull(query);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (st != null) {
            where.append(" AND r.status = ?");
            args.add(st.toUpperCase(Locale.ROOT));
        }
        if (q != null) {
            String like = "%" + q.toLowerCase(Locale.ROOT)
                    .replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
            where.append(" AND (LOWER(p.name) LIKE ? ESCAPE '=' OR LOWER(r.content) LIKE ? ESCAPE '=' OR LOWER(COALESCE(u.full_name, u.username)) LIKE ? ESCAPE '=')");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        Long totalObj = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN users u ON u.id = r.user_id
                """ + where, Long.class, args.toArray());
        long total = totalObj == null ? 0 : totalObj;

        args.add(size);
        args.add((long) page * size);
        var rows = jdbc.query("""
                SELECT r.id, r.order_id, r.order_item_id, r.product_id, p.name AS product_name,
                       COALESCE(u.full_name, u.username) AS author_name, r.rating, r.title, r.content,
                       r.status, r.staff_reply, COALESCE(rep.full_name, rep.username) AS replied_by_name,
                       r.replied_at, r.created_at
                FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN users u ON u.id = r.user_id
                LEFT JOIN users rep ON rep.id = r.replied_by
                """ + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
                ReviewService::mapRaw, args.toArray());

        int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new ReviewPage(attachImages(rows), page, size, total, totalPages);
    }

    // Nhân viên duyệt/ẩn/từ chối đánh giá và gửi phản hồi chính thức từ cửa hàng
    @Transactional
    public ReviewView moderate(long reviewId, ModerateReviewRequest req) {
        long staffId = requireUser();
        if (req == null) {
            throw CommerceException.badRequest("INVALID_REQUEST", "Thiếu thông tin kiểm duyệt.");
        }
        ReviewView current = getById(reviewId);
        var errors = new LinkedHashMap<String, String>();

        String rawStatus = ContentValidation.trimToNull(req.status());
        String status = rawStatus == null ? current.status() : rawStatus.toUpperCase(Locale.ROOT);
        if (!MODERATION_STATUSES.contains(status)) {
            errors.put("status", "Trạng thái phải là PENDING, APPROVED, REJECTED hoặc HIDDEN.");
        }
        String reply = ContentValidation.optionalText(req.staffReply(), "staffReply", 2000, errors);
        ContentValidation.throwIfErrors(errors);

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // Nếu có nhập phản hồi mới thì ghi nhận người trả lời và thời điểm phản hồi
        if (reply != null) {
            jdbc.update("""
                    UPDATE reviews
                    SET status = ?, staff_reply = ?, replied_by = ?, replied_at = ?, updated_at = ?
                    WHERE id = ?
                    """, status, reply, staffId, now, now, reviewId);
        } else {
            jdbc.update("UPDATE reviews SET status = ?, updated_at = ? WHERE id = ?", status, now, reviewId);
        }
        return getById(reviewId);
    }

    private ReviewView getById(long id) {
        var rows = jdbc.query("""
                SELECT r.id, r.order_id, r.order_item_id, r.product_id, p.name AS product_name,
                       COALESCE(u.full_name, u.username) AS author_name, r.rating, r.title, r.content,
                       r.status, r.staff_reply, COALESCE(rep.full_name, rep.username) AS replied_by_name,
                       r.replied_at, r.created_at
                FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN users u ON u.id = r.user_id
                LEFT JOIN users rep ON rep.id = r.replied_by
                WHERE r.id = ?
                """, ReviewService::mapRaw, id);
        if (rows.isEmpty()) {
            throw CommerceException.notFound("Không tìm thấy đánh giá.");
        }
        return attachImages(rows).get(0);
    }

    // Tải ảnh đánh giá theo batch (IN) để không phát sinh N+1 query
    private List<ReviewView> attachImages(List<ReviewView> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var ids = rows.stream().map(ReviewView::id).toList();
        var grouped = new LinkedHashMap<Long, List<ReviewImageView>>();
        namedJdbc.query("""
                SELECT id, review_id, url, alt_text, sort_order
                FROM review_images
                WHERE review_id IN (:ids)
                ORDER BY sort_order ASC, id ASC
                """, Map.of("ids", ids), rs -> {
            long revId = rs.getLong("review_id");
            grouped.computeIfAbsent(revId, k -> new ArrayList<>())
                    .add(new ReviewImageView(rs.getLong("id"), rs.getString("url"),
                            rs.getString("alt_text"), rs.getInt("sort_order")));
        });

        var result = new ArrayList<ReviewView>(rows.size());
        for (var r : rows) {
            result.add(new ReviewView(
                    r.id(), r.orderId(), r.orderItemId(), r.productId(), r.productName(),
                    r.authorName(), r.rating(), r.title(), r.content(), r.status(),
                    r.staffReply(), r.repliedByName(), r.repliedAt(), r.createdAt(),
                    List.copyOf(grouped.getOrDefault(r.id(), List.of()))));
        }
        return result;
    }

    private long requireUser() {
        Long userId = actors.currentActor().userId();
        if (userId == null) {
            throw CommerceException.forbidden("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        return userId;
    }

    private record OrderItemCheck(
            long orderItemId, long orderId, long productId, Long userId, boolean delivered) {}

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
    }

    private static ReviewView mapRaw(ResultSet rs, int row) throws SQLException {
        return new ReviewView(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("order_item_id"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("author_name"),
                rs.getInt("rating"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getString("staff_reply"),
                rs.getString("replied_by_name"),
                toIso(rs.getTimestamp("replied_at")),
                toIso(rs.getTimestamp("created_at")),
                List.of());
    }
}
