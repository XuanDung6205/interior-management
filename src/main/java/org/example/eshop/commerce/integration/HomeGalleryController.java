package org.example.eshop.commerce.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HomeGalleryController {
    private final JdbcTemplate jdbc;

    public HomeGalleryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record GallerySlide(long id, String title, String imageUrl, String altText) {}
    public record GalleryResponse(List<GallerySlide> items) {}

    @GetMapping("/api/commerce/home/gallery")
    public GalleryResponse gallery() {
        var items = jdbc.query("""
                SELECT id, title, image_url, COALESCE(NULLIF(alt_text, ''), title) AS alt_text
                FROM banners
                WHERE placement = 'HOME_GALLERY' AND is_active = TRUE
                  AND (starts_at IS NULL OR starts_at <= CURRENT_TIMESTAMP(6))
                  AND (ends_at IS NULL OR ends_at > CURRENT_TIMESTAMP(6))
                ORDER BY sort_order, id
                LIMIT 12
                """, (rs, row) -> new GallerySlide(
                rs.getLong("id"), rs.getString("title"), rs.getString("image_url"), rs.getString("alt_text")));
        return new GalleryResponse(items);
    }
}
