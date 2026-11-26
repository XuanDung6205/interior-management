package org.example.eshop.commerce.tracking;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.content.ContentValidation;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// Ghi nhận sự kiện hành vi người dùng vào bảng tracking_events (tách biệt sự kiện client với sự kiện server)
@Service
public class CommerceTrackingService {
    private static final Logger log = LoggerFactory.getLogger(CommerceTrackingService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Các loại sự kiện cho phép trình duyệt gửi lên; ADD_TO_CART / ORDER_PLACED chỉ do server ghi
    public static final Set<String> BROWSER_EVENTS = Set.of(
            "PAGE_VIEW", "PRODUCT_VIEW", "SEARCH", "FILTER",
            "ADD_TO_CART_CLICK", "CART_UPDATE_CLICK", "CART_REMOVE_CLICK",
            "CART_CLEAR_CLICK", "CHECKOUT_START");

    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public CommerceTrackingService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
    }

    // Dữ liệu JSON trình duyệt gửi lên (không có trường userId để tránh giả mạo danh tính)
    public record BrowserEventRequest(
            String eventType, String sessionId, Instant occurredAt,
            Long productId, Long variantId, Long categoryId,
            String keyword, Long resultCount, Integer quantity, String pagePath) {}

    // Xác thực và lưu tín hiệu hành vi do trình duyệt gửi
    @Transactional
    public void recordBrowserEvent(BrowserEventRequest req) {
        if (req == null) {
            throw CommerceException.badRequest("INVALID_REQUEST", "Thiếu dữ liệu sự kiện tracking.");
        }
        var errors = new LinkedHashMap<String, String>();

        String rawType = ContentValidation.trimToNull(req.eventType());
        String eventType = rawType == null ? null : rawType.toUpperCase(Locale.ROOT);
        if (eventType == null || !BROWSER_EVENTS.contains(eventType)) {
            errors.put("eventType", "Loại sự kiện không hợp lệ hoặc chỉ do máy chủ ghi nhận.");
        }

        String sessionId = ContentValidation.trimToNull(req.sessionId());
        if (sessionId == null || !UUID_PATTERN.matcher(sessionId).matches()) {
            errors.put("sessionId", "sessionId phải là UUID hợp lệ.");
        }

        if (req.quantity() != null && req.quantity() <= 0) {
            errors.put("quantity", "Số lượng phải lớn hơn 0.");
        }
        if (req.resultCount() != null && req.resultCount() < 0) {
            errors.put("resultCount", "Số kết quả không được âm.");
        }
        // Ràng buộc ck_tracking_variant_product trong DB: có variant_id thì bắt buộc phải có product_id
        if (req.variantId() != null && req.productId() == null) {
            errors.put("productId", "Cần truyền productId khi có variantId.");
        }

        String keyword = ContentValidation.optionalText(req.keyword(), "keyword", 200, errors);
        String pagePath = ContentValidation.optionalText(req.pagePath(), "pagePath", 500, errors);
        if (pagePath != null && (!pagePath.startsWith("/") || pagePath.startsWith("//"))) {
            errors.put("pagePath", "pagePath phải là đường dẫn nội bộ bắt đầu bằng /.");
        }

        // Kiểm tra các trường bắt buộc theo từng loại sự kiện cụ thể
        if (eventType != null) {
            if (("PRODUCT_VIEW".equals(eventType) || "ADD_TO_CART_CLICK".equals(eventType)
                    || "CART_UPDATE_CLICK".equals(eventType) || "CART_REMOVE_CLICK".equals(eventType))
                    && req.productId() == null) {
                errors.put("productId", "Sự kiện này yêu cầu productId.");
            }
            if ("SEARCH".equals(eventType) && keyword == null) {
                errors.put("keyword", "Sự kiện SEARCH yêu cầu từ khóa tìm kiếm.");
            }
            if ("FILTER".equals(eventType) && req.categoryId() == null) {
                errors.put("categoryId", "Sự kiện FILTER yêu cầu categoryId.");
            }
            if ("PAGE_VIEW".equals(eventType) && pagePath == null) {
                errors.put("pagePath", "Sự kiện PAGE_VIEW yêu cầu pagePath.");
            }
        }
        ContentValidation.throwIfErrors(errors);

        // Lấy user_id trực tiếp từ session đăng nhập (null nếu là khách vãng lai)
        Long userId = actors.currentActor().userId();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime occurredAt = req.occurredAt() == null
                ? now
                : LocalDateTime.ofInstant(req.occurredAt(), ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO tracking_events(
                    event_type, session_id, user_id, product_id, variant_id,
                    category_id, keyword, result_count, quantity, page_path,
                    occurred_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventType, sessionId, userId, req.productId(), req.variantId(),
                req.categoryId(), keyword, req.resultCount(), req.quantity(), pagePath,
                occurredAt, now);
    }

    // Ghi sự kiện xác nhận từ phía server (ví dụ ADD_TO_CART, ORDER_PLACED), không để lỗi tracking làm hỏng đơn
    public void recordServerEvent(
            String eventType, String sessionId, Long userId,
            Long productId, Long variantId, Long orderId, Integer quantity) {
        try {
            String safeSession = (sessionId != null && !sessionId.isBlank()) ? sessionId.trim() : "server";
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO tracking_events(
                        event_type, session_id, user_id, product_id, variant_id,
                        order_id, quantity, occurred_at, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventType, safeSession, userId, productId, variantId, orderId, quantity, now, now);
        } catch (RuntimeException ex) {
            log.warn("Không thể ghi sự kiện tracking server {}", eventType, ex);
        }
    }
}
