package org.example.eshop.commerce.analytics;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// API đọc bảng điều khiển thống kê dành cho ADMIN/STAFF
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    // Tổng hợp doanh thu thực, đơn hàng, top bán chạy, tồn thấp, funnel hành vi và chỉ số cache
    @GetMapping("/summary")
    public DashboardService.DashboardSummary summary(@RequestParam(defaultValue = "30") int days) {
        return service.summary(days);
    }

    // Xóa toàn bộ cache TV5 để làm mới số liệu ngay hoặc chuẩn bị lượt đo cold cache
    @PostMapping("/cache/clear")
    public Tv5Cache.CacheStats clearCache() {
        return service.clearCache();
    }
}
