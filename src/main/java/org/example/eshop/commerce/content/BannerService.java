package org.example.eshop.commerce.content;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.common.CommerceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true) // Mặc định chỉ đọc để DB không mở lock ghi không cần thiết
public class BannerService {
    private static final Duration PUBLIC_CACHE_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Tv5Cache cache;

    public BannerService(JdbcTemplate jdbc, Clock clock, Tv5Cache cache) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cache = cache;
    }

    // Dữ liệu trả về cho cả public và admin
    public record BannerView(
            long id, String title, String subtitle, String imageUrl, String mobileImageUrl,
            String altText, String targetUrl, String placement, int sortOrder,
            boolean isActive, String startsAt, String endsAt, String updatedAt) {
    }

    // Gói kết quả phân trang chuẩn giống các module khác
    public record BannerPage(List<BannerView> items, int page, int size, long totalElements, int totalPages) {
    }

    // Dữ liệu JSON nhận từ form tạo/sửa banner của admin
    public record BannerSaveRequest(
            String title, String subtitle, String imageUrl, String mobileImageUrl,
            String altText, String targetUrl, String placement, Integer sortOrder,
            Boolean isActive, Instant startsAt, Instant endsAt) {
    }

    // Lấy danh sách banner đang bật và còn hạn hiển thị cho trang khách (có cache 10 phút)
    public List<BannerView> listPublic(String placement) {
        String target = ContentValidation.trimToNull(placement);
        String normalized = target == null ? "HOME_HERO" : target.toUpperCase(Locale.ROOT);
        return cache.getOrLoad(Tv5Cache.BANNERS, normalized, PUBLIC_CACHE_TTL, () -> {
            // Quy ước dự án: cột DATETIME(6) trong MariaDB lưu theo giờ UTC
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            return jdbc.query("""
                    SELECT id, title, subtitle, image_url, mobile_image_url, alt_text, target_url,
                           placement, sort_order, is_active, starts_at, ends_at, updated_at
                    FROM banners
                    WHERE placement = ? AND is_active = TRUE
                      AND (starts_at IS NULL OR starts_at <= ?)
                      AND (ends_at IS NULL OR ends_at > ?)
                    ORDER BY sort_order ASC, id ASC
                    LIMIT 20
                    """, BannerService::mapRow, normalized, now, now);
        });
    }

    // Lấy danh sách banner có lọc từ khóa, vị trí và phân trang cho màn admin
    public BannerPage listAdmin(String query, String placement, int page, int size) {
        ContentValidation.checkPage(page, size, query);
        String q = ContentValidation.trimToNull(query);
        String p = ContentValidation.trimToNull(placement);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (q != null) {
            // Escape ký tự đặc biệt của LIKE để người dùng gõ % hoặc _ không bị tìm sai
            String like = "%" + q.toLowerCase(Locale.ROOT)
                    .replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
            where.append(" AND (LOWER(title) LIKE ? ESCAPE '=' OR LOWER(COALESCE(subtitle, '')) LIKE ? ESCAPE '=')");
            args.add(like);
            args.add(like);
        }
        if (p != null) {
            where.append(" AND placement = ?");
            args.add(p.toUpperCase(Locale.ROOT));
        }

        Long totalObj = jdbc.queryForObject("SELECT COUNT(*) FROM banners" + where, Long.class, args.toArray());
        long total = totalObj == null ? 0 : totalObj;

        args.add(size);
        args.add((long) page * size); // OFFSET = page * size
        var items = jdbc.query("""
                SELECT id, title, subtitle, image_url, mobile_image_url, alt_text, target_url,
                       placement, sort_order, is_active, starts_at, ends_at, updated_at
                FROM banners
                """ + where + " ORDER BY placement ASC, sort_order ASC, id DESC LIMIT ? OFFSET ?",
                BannerService::mapRow, args.toArray());

        int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new BannerPage(items, page, size, total, totalPages);
    }

    // Lấy chi tiết 1 banner theo id, không có thì báo 404
    public BannerView getAdmin(long id) {
        var rows = jdbc.query("""
                SELECT id, title, subtitle, image_url, mobile_image_url, alt_text, target_url,
                       placement, sort_order, is_active, starts_at, ends_at, updated_at
                FROM banners WHERE id = ?
                """, BannerService::mapRow, id);
        if (rows.isEmpty()) {
            throw CommerceException.notFound("Không tìm thấy banner.");
        }
        return rows.get(0);
    }

    // Tạo mới banner và trả về bản ghi vừa lưu kèm id tự tăng
    @Transactional
    public BannerView create(BannerSaveRequest req) {
        var data = validate(req);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var keyHolder = new GeneratedKeyHolder(); // Hứng khóa chính AUTO_INCREMENT sau khi INSERT

        jdbc.update(conn -> {
            var ps = conn.prepareStatement("""
                    INSERT INTO banners(title, subtitle, image_url, mobile_image_url, alt_text, target_url,
                                        placement, sort_order, is_active, starts_at, ends_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, data.title);
            ps.setString(2, data.subtitle);
            ps.setString(3, data.imageUrl);
            ps.setString(4, data.mobileImageUrl);
            ps.setString(5, data.altText);
            ps.setString(6, data.targetUrl);
            ps.setString(7, data.placement);
            ps.setInt(8, data.sortOrder);
            ps.setBoolean(9, data.active);
            ps.setObject(10, toUtcDateTime(req.startsAt()));
            ps.setObject(11, toUtcDateTime(req.endsAt()));
            ps.setObject(12, now);
            ps.setObject(13, now);
            return ps;
        }, keyHolder);

        cache.evictAfterCommit(Tv5Cache.BANNERS);
        return getAdmin(keyHolder.getKey().longValue());
    }

    // Cập nhật banner theo id
    @Transactional
    public BannerView update(long id, BannerSaveRequest req) {
        getAdmin(id); // Kiểm tra tồn tại trước để trả 404 thay vì update 0 dòng
        var data = validate(req);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        jdbc.update("""
                UPDATE banners
                SET title = ?, subtitle = ?, image_url = ?, mobile_image_url = ?, alt_text = ?,
                    target_url = ?, placement = ?, sort_order = ?, is_active = ?,
                    starts_at = ?, ends_at = ?, updated_at = ?
                WHERE id = ?
                """,
                data.title, data.subtitle, data.imageUrl, data.mobileImageUrl, data.altText,
                data.targetUrl, data.placement, data.sortOrder, data.active,
                toUtcDateTime(req.startsAt()), toUtcDateTime(req.endsAt()), now, id);
        cache.evictAfterCommit(Tv5Cache.BANNERS);
        return getAdmin(id);
    }

    // Xóa hẳn banner (bảng banners không có FK tham chiếu từ đơn hàng)
    @Transactional
    public void delete(long id) {
        int affected = jdbc.update("DELETE FROM banners WHERE id = ?", id);
        if (affected == 0) {
            throw CommerceException.notFound("Không tìm thấy banner.");
        }
        cache.evictAfterCommit(Tv5Cache.BANNERS);
    }

    // Gom toàn bộ lỗi từng field vào Map để frontend highlight đúng ô nhập sai
    private ValidatedBanner validate(BannerSaveRequest req) {
        if (req == null) {
            throw CommerceException.badRequest("INVALID_REQUEST", "Thiếu thông tin banner.");
        }
        var errors = new LinkedHashMap<String, String>();
        String title = ContentValidation.requiredText(req.title(), "title", 200, errors);
        String subtitle = ContentValidation.optionalText(req.subtitle(), "subtitle", 500, errors);
        String imageUrl = ContentValidation.validateUrl(req.imageUrl(), "imageUrl", true, errors);
        String mobileImageUrl = ContentValidation.validateUrl(req.mobileImageUrl(), "mobileImageUrl", false, errors);
        String altText = ContentValidation.optionalText(req.altText(), "altText", 255, errors);
        String targetUrl = ContentValidation.validateUrl(req.targetUrl(), "targetUrl", false, errors);
        String placement = ContentValidation.validatePlacement(req.placement(), errors);
        ContentValidation.validateDateRange(req.startsAt(), req.endsAt(), errors);

        int sortOrder = req.sortOrder() == null ? 0 : req.sortOrder();
        if (sortOrder < 0) {
            errors.put("sortOrder", "Thứ tự hiển thị không được âm.");
        }
        ContentValidation.throwIfErrors(errors);

        boolean active = req.isActive() == null || req.isActive();
        return new ValidatedBanner(title, subtitle, imageUrl, mobileImageUrl, altText, targetUrl, placement, sortOrder,
                active);
    }

    private record ValidatedBanner(
            String title, String subtitle, String imageUrl, String mobileImageUrl,
            String altText, String targetUrl, String placement, int sortOrder, boolean active) {
    }

    private static LocalDateTime toUtcDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // Chuyển Timestamp trong DB sang chuỗi ISO-8601 có hậu tố Z để JS parse chuẩn
    // múi giờ
    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
    }

    // Ánh xạ 1 dòng ResultSet từ SQL sang BannerView
    private static BannerView mapRow(ResultSet rs, int row) throws SQLException {
        return new BannerView(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("image_url"),
                rs.getString("mobile_image_url"),
                rs.getString("alt_text"),
                rs.getString("target_url"),
                rs.getString("placement"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_active"),
                toIso(rs.getTimestamp("starts_at")),
                toIso(rs.getTimestamp("ends_at")),
                toIso(rs.getTimestamp("updated_at")));
    }
}