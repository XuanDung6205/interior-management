package org.example.eshop.commerce.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// API gợi ý sản phẩm nội thất (dùng được cho cả khách vãng lai lẫn tài khoản đã đăng nhập)
@RestController
@RequestMapping("/api/commerce/recommendations")
public class RecommendationController {
    private final FurnitureRecommendationService service;

    public RecommendationController(FurnitureRecommendationService service) {
        this.service = service;
    }

    // Trả tối đa limit sản phẩm nội thất gợi ý, lọc bỏ sản phẩm ngừng bán và hết tồn
    @GetMapping
    public FurnitureRecommendationService.RecommendationResponse list(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "6") int limit) {
        return service.recommend(productId, sessionId, limit);
    }
}
