package org.example.eshop.commerce.content;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// API quản trị banner và bài viết (chỉ ADMIN/STAFF truy cập được theo CommerceSecurityConfiguration)
@RestController
@RequestMapping("/api/admin/content")
public class ContentAdminController {
    private final BannerService banners;
    private final ArticleService articles;

    public ContentAdminController(BannerService banners, ArticleService articles) {
        this.banners = banners;
        this.articles = articles;
    }

    // Lấy danh sách banner cho bảng quản trị, hỗ trợ lọc theo từ khóa và placement
    @GetMapping("/banners")
    public BannerService.BannerPage listBanners(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String placement,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return banners.listAdmin(query, placement, page, size);
    }

    // Tạo mới banner
    @PostMapping("/banners")
    @ResponseStatus(HttpStatus.CREATED)
    public BannerService.BannerView createBanner(@RequestBody BannerService.BannerSaveRequest req) {
        return banners.create(req);
    }

    // Cập nhật banner theo id
    @PutMapping("/banners/{id}")
    public BannerService.BannerView updateBanner(
            @PathVariable long id,
            @RequestBody BannerService.BannerSaveRequest req) {
        return banners.update(id, req);
    }

    // Xóa banner theo id
    @DeleteMapping("/banners/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBanner(@PathVariable long id) {
        banners.delete(id);
    }

    // Lấy danh sách bài viết cho quản trị (xem được cả DRAFT, PUBLISHED, ARCHIVED)
    @GetMapping("/articles")
    public ArticleService.ArticlePage listArticles(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return articles.listAdmin(query, status, page, size);
    }

    // Lấy toàn bộ nội dung bài viết theo id để đổ vào form sửa
    @GetMapping("/articles/{id}")
    public ArticleService.ArticleDetail getArticle(@PathVariable long id) {
        return articles.getAdmin(id);
    }

    // Tạo mới bài viết
    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleService.ArticleDetail createArticle(@RequestBody ArticleService.ArticleSaveRequest req) {
        return articles.create(req);
    }

    // Cập nhật bài viết theo id
    @PutMapping("/articles/{id}")
    public ArticleService.ArticleDetail updateArticle(
            @PathVariable long id,
            @RequestBody ArticleService.ArticleSaveRequest req) {
        return articles.update(id, req);
    }

    // Xóa bài viết theo id
    @DeleteMapping("/articles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArticle(@PathVariable long id) {
        articles.delete(id);
    }
}
