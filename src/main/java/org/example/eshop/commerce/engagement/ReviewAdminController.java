package org.example.eshop.commerce.engagement;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// API kiểm duyệt và phản hồi đánh giá cho ADMIN/STAFF
@RestController
@RequestMapping("/api/admin/reviews")
public class ReviewAdminController {
    private final ReviewService service;

    public ReviewAdminController(ReviewService service) {
        this.service = service;
    }

    // Lấy danh sách đánh giá theo trạng thái và từ khóa
    @GetMapping
    public ReviewService.ReviewPage list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listAdmin(status, query, page, size);
    }

    // Duyệt/ẩn/từ chối và phản hồi đánh giá của khách
    @PatchMapping("/{id}/moderate")
    public ReviewService.ReviewView moderate(
            @PathVariable long id,
            @RequestBody ReviewService.ModerateReviewRequest req) {
        return service.moderate(id, req);
    }
}
