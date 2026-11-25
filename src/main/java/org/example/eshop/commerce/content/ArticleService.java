package org.example.eshop.commerce.content;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.dao.DataIntegrityViolationException;
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
@Transactional(readOnly = true)
public class ArticleService {
    private static final Duration PUBLIC_CACHE_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors; // Dùng để lấy userId của nhân viên đang đăng nhập làm author_id
    private final Clock clock;
    private final Tv5Cache cache;

    public ArticleService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock, Tv5Cache cache) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
        this.cache = cache;
    }

    // Bản rút gọn dùng cho danh sách (không kéo cột content LONGTEXT cho nhẹ query)
    public record ArticleSummary(
            long id, String title, String slug, String summary, String coverImageUrl,
            String status, String authorName, String publishedAt, String updatedAt) {
    }

    // Bản đầy đủ dùng cho trang đọc chi tiết hoặc màn sửa của admin
    public record ArticleDetail(
            long id, String title, String slug, String summary, String content,
            String coverImageUrl, String status, String seoTitle, String seoDescription,
            String authorName, String publishedAt, String updatedAt) {
    }

    public record ArticlePage(List<ArticleSummary> items, int page, int size, long totalElements, int totalPages) {
    }

    public record ArticleSaveRequest(
            String title, String slug, String summary, String content, String coverImageUrl,
            String status, String seoTitle, String seoDescription, Instant publishedAt) {
    }

    // Lấy danh sách bài đã xuất bản (chỉ cache trang đầu không từ khóa để tránh phình key tìm kiếm tùy ý)
    public ArticlePage listPublic(String query, int page, int size) {
        ContentValidation.checkPage(page, size, query);
        String q = ContentValidation.trimToNull(query);
        if (q == null && page <= 2) {
            return cache.getOrLoad(Tv5Cache.ARTICLES, "list:" + page + ":" + size, PUBLIC_CACHE_TTL,
                    () -> queryPublicList(null, page, size));
        }
        return queryPublicList(q, page, size);
    }

    private ArticlePage queryPublicList(String q, int page, int size) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        StringBuilder where = new StringBuilder(
                " WHERE a.status = 'PUBLISHED' AND (a.published_at IS NULL OR a.published_at <= ?)");
        var args = new ArrayList<Object>();
        args.add(now);
        if (q != null) {
            String like = "%" + q.toLowerCase(Locale.ROOT)
                    .replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
            where.append(" AND (LOWER(a.title) LIKE ? ESCAPE '=' OR LOWER(COALESCE(a.summary, '')) LIKE ? ESCAPE '=')");
            args.add(like);
            args.add(like);
        }

        Long totalObj = jdbc.queryForObject("SELECT COUNT(*) FROM articles a" + where, Long.class, args.toArray());
        long total = totalObj == null ? 0 : totalObj;

        args.add(size);
        args.add((long) page * size);
        var items = jdbc.query("""
                SELECT a.id, a.title, a.slug, a.summary, a.cover_image_url, a.status,
                       COALESCE(u.full_name, u.username) AS author_name, a.published_at, a.updated_at
                FROM articles a
                JOIN users u ON u.id = a.author_id
                """ + where + " ORDER BY COALESCE(a.published_at, a.created_at) DESC, a.id DESC LIMIT ? OFFSET ?",
                ArticleService::mapSummary, args.toArray());

        int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new ArticlePage(items, page, size, total, totalPages);
    }

    // Xem chi tiết 1 bài viết public theo slug (có cache 10 phút, xóa ngay khi admin cập nhật)
    public ArticleDetail getPublicBySlug(String slug) {
        String normalized = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        return cache.getOrLoad(Tv5Cache.ARTICLES, "slug:" + normalized, PUBLIC_CACHE_TTL, () -> {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            var rows = jdbc.query("""
                    SELECT a.id, a.title, a.slug, a.summary, a.content, a.cover_image_url, a.status,
                           a.seo_title, a.seo_description, COALESCE(u.full_name, u.username) AS author_name,
                           a.published_at, a.updated_at
                    FROM articles a
                    JOIN users u ON u.id = a.author_id
                    WHERE a.slug = ? AND a.status = 'PUBLISHED' AND (a.published_at IS NULL OR a.published_at <= ?)
                    """, ArticleService::mapDetail, normalized, now);
            if (rows.isEmpty()) {
                throw CommerceException.notFound("Không tìm thấy bài viết.");
            }
            return rows.get(0);
        });
    }

    // Danh sách bài viết trong trang quản trị (xem được cả DRAFT, PUBLISHED,
    // ARCHIVED)
    public ArticlePage listAdmin(String query, String status, int page, int size) {
        ContentValidation.checkPage(page, size, query);
        String q = ContentValidation.trimToNull(query);
        String st = ContentValidation.trimToNull(status);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (q != null) {
            String like = "%" + q.toLowerCase(Locale.ROOT)
                    .replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
            where.append(" AND (LOWER(a.title) LIKE ? ESCAPE '=' OR LOWER(a.slug) LIKE ? ESCAPE '=')");
            args.add(like);
            args.add(like);
        }
        if (st != null) {
            where.append(" AND a.status = ?");
            args.add(st.toUpperCase(Locale.ROOT));
        }

        Long totalObj = jdbc.queryForObject("SELECT COUNT(*) FROM articles a" + where, Long.class, args.toArray());
        long total = totalObj == null ? 0 : totalObj;

        args.add(size);
        args.add((long) page * size);
        var items = jdbc.query("""
                SELECT a.id, a.title, a.slug, a.summary, a.cover_image_url, a.status,
                       COALESCE(u.full_name, u.username) AS author_name, a.published_at, a.updated_at
                FROM articles a
                JOIN users u ON u.id = a.author_id
                """ + where + " ORDER BY a.updated_at DESC, a.id DESC LIMIT ? OFFSET ?",
                ArticleService::mapSummary, args.toArray());

        int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new ArticlePage(items, page, size, total, totalPages);
    }

    // Lấy chi tiết bài viết theo id để admin mở form chỉnh sửa
    public ArticleDetail getAdmin(long id) {
        var rows = jdbc.query("""
                SELECT a.id, a.title, a.slug, a.summary, a.content, a.cover_image_url, a.status,
                       a.seo_title, a.seo_description, COALESCE(u.full_name, u.username) AS author_name,
                       a.published_at, a.updated_at
                FROM articles a
                JOIN users u ON u.id = a.author_id
                WHERE a.id = ?
                """, ArticleService::mapDetail, id);
        if (rows.isEmpty()) {
            throw CommerceException.notFound("Không tìm thấy bài viết.");
        }
        return rows.get(0);
    }

    // Tạo bài viết mới; author_id lấy từ session thay vì tin dữ liệu client gửi lên
    @Transactional
    public ArticleDetail create(ArticleSaveRequest req) {
        Long authorId = actors.currentActor().userId();
        if (authorId == null) {
            throw CommerceException.forbidden("Yêu cầu đăng nhập nhân viên.");
        }
        var valid = validate(req);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime publishedAt = resolvePublishedAt(valid.status, req.publishedAt(), now);

        var keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(conn -> {
                var ps = conn.prepareStatement("""
                        INSERT INTO articles(author_id, title, slug, summary, content, cover_image_url,
                                             status, seo_title, seo_description, published_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, authorId);
                ps.setString(2, valid.title);
                ps.setString(3, valid.slug);
                ps.setString(4, valid.summary);
                ps.setString(5, valid.content);
                ps.setString(6, valid.coverImageUrl);
                ps.setString(7, valid.status);
                ps.setString(8, valid.seoTitle);
                ps.setString(9, valid.seoDescription);
                ps.setObject(10, publishedAt);
                ps.setObject(11, now);
                ps.setObject(12, now);
                return ps;
            }, keyHolder);
        } catch (DataIntegrityViolationException ex) {
            // Bắt lỗi unique uq_articles_slug ở DB để trả 409 Conflict dễ hiểu cho UI
            throw CommerceException.conflict("ARTICLE_SLUG_EXISTS", "Slug bài viết đã tồn tại.");
        }
        cache.evictAfterCommit(Tv5Cache.ARTICLES);
        return getAdmin(keyHolder.getKey().longValue());
    }

    // Cập nhật bài viết; nếu giữ nguyên không nhập lại publishedAt thì giữ ngày
    // xuất bản cũ
    @Transactional
    public ArticleDetail update(long id, ArticleSaveRequest req) {
        ArticleDetail current = getAdmin(id);
        var valid = validate(req);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Instant fallback = current.publishedAt() == null ? null : Instant.parse(current.publishedAt());
        LocalDateTime publishedAt = resolvePublishedAt(valid.status,
                req.publishedAt() != null ? req.publishedAt() : fallback, now);

        try {
            jdbc.update("""
                    UPDATE articles
                    SET title = ?, slug = ?, summary = ?, content = ?, cover_image_url = ?,
                        status = ?, seo_title = ?, seo_description = ?, published_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    valid.title, valid.slug, valid.summary, valid.content, valid.coverImageUrl,
                    valid.status, valid.seoTitle, valid.seoDescription, publishedAt, now, id);
        } catch (DataIntegrityViolationException ex) {
            throw CommerceException.conflict("ARTICLE_SLUG_EXISTS", "Slug bài viết đã tồn tại.");
        }
        cache.evictAfterCommit(Tv5Cache.ARTICLES);
        return getAdmin(id);
    }

    @Transactional
    public void delete(long id) {
        int affected = jdbc.update("DELETE FROM articles WHERE id = ?", id);
        if (affected == 0) {
            throw CommerceException.notFound("Không tìm thấy bài viết.");
        }
        cache.evictAfterCommit(Tv5Cache.ARTICLES);
    }

    // Nếu admin chọn PUBLISHED mà chưa chọn giờ đăng thì lấy luôn thời điểm hiện
    // tại
    private LocalDateTime resolvePublishedAt(String status, Instant requested, LocalDateTime now) {
        if (requested != null) {
            return LocalDateTime.ofInstant(requested, ZoneOffset.UTC);
        }
        return "PUBLISHED".equals(status) ? now : null;
    }

    private ValidatedArticle validate(ArticleSaveRequest req) {
        if (req == null) {
            throw CommerceException.badRequest("INVALID_REQUEST", "Thiếu thông tin bài viết.");
        }
        var errors = new LinkedHashMap<String, String>();
        String title = ContentValidation.requiredText(req.title(), "title", 250, errors);
        String slug = ContentValidation.validateSlug(req.slug(), errors);
        String summary = ContentValidation.optionalText(req.summary(), "summary", 1000, errors);
        String content = ContentValidation.requiredText(req.content(), "content", 100_000, errors);
        String cover = ContentValidation.validateUrl(req.coverImageUrl(), "coverImageUrl", false, errors);
        String seoTitle = ContentValidation.optionalText(req.seoTitle(), "seoTitle", 200, errors);
        String seoDesc = ContentValidation.optionalText(req.seoDescription(), "seoDescription", 320, errors);

        String rawStatus = ContentValidation.trimToNull(req.status());
        String status = rawStatus == null ? "DRAFT" : rawStatus.toUpperCase(Locale.ROOT);
        if (!ContentValidation.ARTICLE_STATUSES.contains(status)) {
            errors.put("status", "Trạng thái phải là DRAFT, PUBLISHED hoặc ARCHIVED.");
        }
        ContentValidation.throwIfErrors(errors);
        return new ValidatedArticle(title, slug, summary, content, cover, status, seoTitle, seoDesc);
    }

    private record ValidatedArticle(
            String title, String slug, String summary, String content,
            String coverImageUrl, String status, String seoTitle, String seoDescription) {
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
    }

    private static ArticleSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new ArticleSummary(
                rs.getLong("id"), rs.getString("title"), rs.getString("slug"),
                rs.getString("summary"), rs.getString("cover_image_url"), rs.getString("status"),
                rs.getString("author_name"), toIso(rs.getTimestamp("published_at")),
                toIso(rs.getTimestamp("updated_at")));
    }

    private static ArticleDetail mapDetail(ResultSet rs, int row) throws SQLException {
        return new ArticleDetail(
                rs.getLong("id"), rs.getString("title"), rs.getString("slug"),
                rs.getString("summary"), rs.getString("content"), rs.getString("cover_image_url"),
                rs.getString("status"), rs.getString("seo_title"), rs.getString("seo_description"),
                rs.getString("author_name"), toIso(rs.getTimestamp("published_at")),
                toIso(rs.getTimestamp("updated_at")));
    }
}