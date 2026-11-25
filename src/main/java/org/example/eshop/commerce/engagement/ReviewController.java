package org.example.eshop.commerce.engagement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// API đánh giá dành cho khách (xem đánh giá công khai theo sản phẩm và gửi đánh giá sau mua)
@RestController
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    // Xem danh sách đánh giá đã duyệt của 1 sản phẩm (công khai)
    @GetMapping("/api/commerce/products/{productId}/reviews")
    public ReviewService.ProductReviewsResponse byProduct(
            @PathVariable long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.listForProduct(productId, page, size);
    }

    // Lấy danh sách dòng đơn hàng đã giao mà khách chưa đánh giá
    @GetMapping("/api/reviews/eligible")
    public List<ReviewService.EligibleItemView> eligible() {
        return service.listEligibleForCurrentUser();
    }

    // Lấy các đánh giá mà khách hiện tại đã gửi
    @GetMapping("/api/reviews/mine")
    public List<ReviewService.ReviewView> mine() {
        return service.listMine();
    }

    // Gửi đánh giá mới cho một sản phẩm đã mua
    @PostMapping("/api/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewService.ReviewView create(@RequestBody ReviewService.CreateReviewRequest req) {
        return service.create(req);
    }
}
