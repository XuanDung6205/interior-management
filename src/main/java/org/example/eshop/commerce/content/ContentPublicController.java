package org.example.eshop.commerce.content;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// API đọc banner và bài viết công khai cho khách (không cần đăng nhập)
@RestController
@RequestMapping("/api/commerce/content")
public class ContentPublicController {
    private final BannerService banners;
    private final ArticleService articles;

    public ContentPublicController(BannerService banners, ArticleService articles) {
        this.banners = banners;
        this.articles = articles;
    }

    // Trả danh sách banner đang bật và còn hạn theo vị trí hiển thị (mặc định HOME_HERO)
    @GetMapping("/banners")
    public List<BannerService.BannerView> banners(@RequestParam(defaultValue = "HOME_HERO") String placement) {
        return banners.listPublic(placement);
    }

    // Trả danh sách bài viết đã xuất bản có phân trang và tìm theo từ khóa
    @GetMapping("/articles")
    public ArticleService.ArticlePage articles(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "9") int size) {
        return articles.listPublic(query, page, size);
    }

    // Xem chi tiết 1 bài viết public theo slug
    @GetMapping("/articles/{slug}")
    public ArticleService.ArticleDetail article(@PathVariable String slug) {
        return articles.getPublicBySlug(slug);
    }
}
