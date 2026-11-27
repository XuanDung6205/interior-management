package org.example.eshop.commerce.analytics;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.common.CommerceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Tổng hợp số liệu quản trị chỉ đọc (không sửa bảng đơn hàng/kho/thanh toán của các module khác)
@Service
@Transactional(readOnly = true)
public class DashboardService {
    private static final int LOW_STOCK_THRESHOLD = 5;
    private static final Duration SUMMARY_CACHE_TTL = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Tv5Cache cache;

    public DashboardService(JdbcTemplate jdbc, Clock clock, Tv5Cache cache) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cache = cache;
    }

    public record RevenueStats(String grossCollected, String totalRefunded, String netRevenue, String currency) {}

    public record TopProductRow(long productId, String productName, long unitsSold, String grossMerchandise) {}

    public record LowStockRow(
            long warehouseId, String warehouseCode, long variantId, String sku,
            String productName, String variantName, int availableQuantity) {}

    public record DashboardSummary(
            int days, String fromUtc, String toUtc,
            RevenueStats revenue,
            long totalOrders,
            Map<String, Long> ordersByStatus,
            List<TopProductRow> topProducts,
            List<LowStockRow> lowStockVariants,
            Map<String, Long> funnel,
            Tv5Cache.CacheStats cacheStats) {}

    // Trả toàn bộ số liệu tổng quan trong khoảng days ngày gần nhất (cache 2 phút, thống kê cache luôn lấy mới)
    public DashboardSummary summary(int days) {
        if (days < 1 || days > 365) {
            throw CommerceException.badRequest("INVALID_RANGE", "Khoảng thống kê phải từ 1 đến 365 ngày.");
        }
        DashboardSummary cached = cache.getOrLoad(Tv5Cache.DASHBOARD, "days:" + days, SUMMARY_CACHE_TTL,
                DashboardSummary.class, () -> loadSummary(days));
        return new DashboardSummary(
                cached.days(), cached.fromUtc(), cached.toUtc(), cached.revenue(),
                cached.totalOrders(), cached.ordersByStatus(), cached.topProducts(),
                cached.lowStockVariants(), cached.funnel(), cache.stats());
    }

    // Xóa toàn bộ cache TV5 theo yêu cầu của nhân viên/admin (hữu ích khi kiểm thử cold vs warm cache)
    public Tv5Cache.CacheStats clearCache() {
        cache.clearAll();
        return cache.stats();
    }

    private DashboardSummary loadSummary(int days) {
        LocalDateTime to = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime from = to.minusDays(days);

        // Doanh thu ròng chỉ tính trên khoản thu SUCCEEDED trừ khoản hoàn SUCCEEDED, không cộng đơn chưa thu
        BigDecimal grossCollected = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM payments
                WHERE status = 'SUCCEEDED' AND COALESCE(paid_at, updated_at) >= ?
                  AND COALESCE(paid_at, updated_at) < ?
                """, BigDecimal.class, from, to);
        BigDecimal totalRefunded = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM refunds
                WHERE status = 'SUCCEEDED' AND COALESCE(completed_at, updated_at) >= ?
                  AND COALESCE(completed_at, updated_at) < ?
                """, BigDecimal.class, from, to);

        BigDecimal gross = grossCollected == null ? BigDecimal.ZERO : grossCollected;
        BigDecimal refunded = totalRefunded == null ? BigDecimal.ZERO : totalRefunded;
        BigDecimal net = gross.subtract(refunded);
        var revenue = new RevenueStats(money(gross), money(refunded), money(net), "VND");

        // Đếm số đơn theo từng trạng thái trong khoảng thời gian
        var ordersByStatus = new LinkedHashMap<String, Long>();
        for (String st : List.of("PENDING_CONFIRMATION", "CONFIRMED", "PROCESSING", "COMPLETED", "CANCELLED")) {
            ordersByStatus.put(st, 0L);
        }
        long totalOrders = 0;
        var statusRows = jdbc.queryForList("""
                SELECT status, COUNT(*) AS cnt
                FROM orders
                WHERE placed_at >= ? AND placed_at < ?
                GROUP BY status
                """, from, to);
        for (var row : statusRows) {
            String st = String.valueOf(row.get("status"));
            long cnt = ((Number) row.get("cnt")).longValue();
            ordersByStatus.put(st, cnt);
            totalOrders += cnt;
        }

        // Top 5 sản phẩm bán chạy (bỏ qua đơn đã hủy)
        var topProducts = jdbc.query("""
                SELECT oi.product_id, oi.product_name,
                       SUM(oi.quantity) AS units_sold,
                       SUM(oi.line_total) AS gross_merchandise
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE o.placed_at >= ? AND o.placed_at < ? AND o.status <> 'CANCELLED'
                GROUP BY oi.product_id, oi.product_name
                ORDER BY units_sold DESC, gross_merchandise DESC
                LIMIT 5
                """, (rs, row) -> new TopProductRow(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("units_sold"),
                money(rs.getBigDecimal("gross_merchandise"))), from, to);

        // Danh sách biến thể đang kinh doanh có tồn khả dụng thấp để kho chủ động nhập thêm
        var lowStock = jdbc.query("""
                SELECT ib.warehouse_id, w.code AS warehouse_code, ib.variant_id, v.sku,
                       p.name AS product_name, v.name AS variant_name, ib.available_quantity
                FROM inventory_balances ib
                JOIN warehouses w ON w.id = ib.warehouse_id
                JOIN product_variants v ON v.id = ib.variant_id
                JOIN products p ON p.id = v.product_id
                WHERE w.is_active = TRUE AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                  AND ib.available_quantity <= ?
                ORDER BY ib.available_quantity ASC, ib.variant_id ASC
                LIMIT 10
                """, (rs, row) -> new LowStockRow(
                rs.getLong("warehouse_id"),
                rs.getString("warehouse_code"),
                rs.getLong("variant_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("variant_name"),
                rs.getInt("available_quantity")), LOW_STOCK_THRESHOLD);

        // Phễu hành vi từ tracking_events (tách rõ lượt bấm client và sự kiện xác nhận từ server)
        var funnel = new LinkedHashMap<String, Long>();
        for (String step : List.of("PAGE_VIEW", "PRODUCT_VIEW", "SEARCH", "FILTER", "ADD_TO_CART_CLICK",
                "ADD_TO_CART", "CHECKOUT_START", "ORDER_PLACED")) {
            funnel.put(step, 0L);
        }
        var funnelRows = jdbc.queryForList("""
                SELECT event_type, COUNT(*) AS cnt
                FROM tracking_events
                WHERE occurred_at >= ? AND occurred_at < ?
                  AND event_type IN ('PAGE_VIEW','PRODUCT_VIEW','SEARCH','FILTER','ADD_TO_CART_CLICK','ADD_TO_CART','CHECKOUT_START','ORDER_PLACED')
                GROUP BY event_type
                """, from, to);
        for (var row : funnelRows) {
            funnel.put(String.valueOf(row.get("event_type")), ((Number) row.get("cnt")).longValue());
        }

        return new DashboardSummary(
                days,
                from.atOffset(ZoneOffset.UTC).toString(),
                to.atOffset(ZoneOffset.UTC).toString(),
                revenue,
                totalOrders,
                ordersByStatus,
                topProducts,
                lowStock,
                funnel,
                cache.stats());
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
