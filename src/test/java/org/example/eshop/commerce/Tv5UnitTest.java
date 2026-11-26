package org.example.eshop.commerce;

import org.example.eshop.commerce.ai.FurnitureChatService;
import org.example.eshop.commerce.analytics.DashboardService;
import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.content.ContentValidation;
import org.example.eshop.commerce.port.CommerceActor;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.example.eshop.commerce.tracking.CommerceTrackingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

// Kiểm thử nhanh logic validate, phân tích ngân sách AI và ràng buộc tracking của TV5 mà không cần DB ngoài
class Tv5UnitTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void contentValidationRejectsUnsafeUrlsAndInvalidDates() {
        var errors = new LinkedHashMap<String, String>();
        ContentValidation.validateUrl("javascript:alert(1)", "imageUrl", true, errors);
        ContentValidation.validateUrl("//evil.example/a.png", "targetUrl", false, errors);
        ContentValidation.validateSlug("Bai Viet Khong Hop Le!", errors);
        ContentValidation.validateDateRange(
                Instant.parse("2026-11-26T10:00:00Z"),
                Instant.parse("2026-11-26T09:00:00Z"),
                errors);

        assertTrue(errors.containsKey("imageUrl"));
        assertTrue(errors.containsKey("targetUrl"));
        assertTrue(errors.containsKey("slug"));
        assertTrue(errors.containsKey("endsAt"));
    }

    @Test
    void contentValidationAcceptsSafeRelativeAndHttpsUrls() {
        var errors = new LinkedHashMap<String, String>();
        assertEquals("/images/home-hero.jpg", ContentValidation.validateUrl(" /images/home-hero.jpg ", "imageUrl", true, errors));
        assertEquals("https://cdn.example.com/a.jpg", ContentValidation.validateUrl("https://cdn.example.com/a.jpg", "targetUrl", false, errors));
        assertEquals("sofa-phong-khach-2026", ContentValidation.validateSlug("Sofa-Phong-Khach-2026", errors));
        assertEquals("HOME_HERO", ContentValidation.validatePlacement(null, errors));
        assertTrue(errors.isEmpty());
    }

    @Test
    void trackingRejectsServerOwnedEventsFromBrowser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentActorPort actors = new CurrentActorPort() {
            @Override public CommerceActor currentActor() { return new CommerceActor(null, null, Set.of()); }
            @Override public void clearGuestIdentity() {}
        };
        var service = new CommerceTrackingService(jdbc, actors, FIXED_CLOCK);

        var req = new CommerceTrackingService.BrowserEventRequest(
                "ORDER_PLACED",
                "dfaa68f0-ec69-41bf-a28d-b6532e498a35",
                Instant.now(),
                10L, null, null, null, null, 1, "/checkout");

        CommerceException ex = assertThrows(CommerceException.class, () -> service.recordBrowserEvent(req));
        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertTrue(ex.getFieldErrors().containsKey("eventType"));
    }

    @Test
    void trackingRequiresProductIdWhenVariantIdIsProvided() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentActorPort actors = new CurrentActorPort() {
            @Override public CommerceActor currentActor() { return new CommerceActor(1L, null, Set.of("CUSTOMER")); }
            @Override public void clearGuestIdentity() {}
        };
        var service = new CommerceTrackingService(jdbc, actors, FIXED_CLOCK);

        var req = new CommerceTrackingService.BrowserEventRequest(
                "PRODUCT_VIEW",
                "dfaa68f0-ec69-41bf-a28d-b6532e498a35",
                Instant.now(),
                null, 99L, null, null, null, null, "/products/1");

        CommerceException ex = assertThrows(CommerceException.class, () -> service.recordBrowserEvent(req));
        assertTrue(ex.getFieldErrors().containsKey("productId"));
    }

    @Test
    void dashboardRejectsOutOfRangeDays() {
        var service = new DashboardService(mock(JdbcTemplate.class), FIXED_CLOCK, new Tv5Cache(FIXED_CLOCK, true));
        assertThrows(CommerceException.class, () -> service.summary(0));
        assertThrows(CommerceException.class, () -> service.summary(400));
    }

    @Test
    void cacheTracksHitsMissesAndEviction() {
        var cache = new Tv5Cache(FIXED_CLOCK, true);
        int[] loads = {0};
        String first = cache.getOrLoad(Tv5Cache.BANNERS, "HOME_HERO", java.time.Duration.ofMinutes(5), () -> {
            loads[0]++;
            return "v" + loads[0];
        });
        String second = cache.getOrLoad(Tv5Cache.BANNERS, "HOME_HERO", java.time.Duration.ofMinutes(5), () -> {
            loads[0]++;
            return "v" + loads[0];
        });
        assertEquals("v1", first);
        assertEquals("v1", second);
        assertEquals(1, loads[0]);
        assertEquals(1, cache.stats().hits());
        assertEquals(1, cache.stats().misses());

        cache.clearNamespace(Tv5Cache.BANNERS);
        String third = cache.getOrLoad(Tv5Cache.BANNERS, "HOME_HERO", java.time.Duration.ofMinutes(5), () -> {
            loads[0]++;
            return "v" + loads[0];
        });
        assertEquals("v2", third);
        assertEquals(2, cache.stats().misses());
    }

    @Test
    void chatParsesBudgetAndKeywordsAndReportsUnconfiguredKeyHonestly() {
        assertEquals(0, new BigDecimal("12000000").compareTo(FurnitureChatService.extractBudgetVnd("Tư vấn sofa dưới 12 triệu")));
        assertEquals(0, new BigDecimal("5500000").compareTo(FurnitureChatService.extractBudgetVnd("Bàn trà tầm 5.5tr")));
        assertNull(FurnitureChatService.extractBudgetVnd("Cho xem mẫu bàn ăn gỗ sồi"));

        List<String> tokens = FurnitureChatService.extractSearchTokens("Tôi cần tìm sofa gỗ sồi dưới 15 triệu");
        assertTrue(tokens.contains("sofa"));
        assertTrue(tokens.contains("gỗ"));

        CurrentActorPort actors = new CurrentActorPort() {
            @Override public CommerceActor currentActor() { return new CommerceActor(10L, null, Set.of("CUSTOMER")); }
            @Override public void clearGuestIdentity() {}
        };
        var mapper = new ObjectMapper();
        var chat = new FurnitureChatService(mock(JdbcTemplate.class), actors, mapper, FIXED_CLOCK, "", "gemini-2.0-flash");
        assertFalse(chat.isAiConfigured());

        // Đảm bảo bỏ qua part thought nội bộ và ghép đủ các part văn bản trả lời
        var sample = mapper.readTree("""
                {"candidates":[{"content":{"parts":[
                  {"thought":true,"text":"internal reasoning"},
                  {"text":"Chào bạn, mẫu Bàn trà Oslo gỗ sồi hiện có giá 2.490.000đ."},
                  {"text":"Bạn cần tư vấn thêm kích thước không?"}
                ]}}]}
                """);
        assertEquals(
                "Chào bạn, mẫu Bàn trà Oslo gỗ sồi hiện có giá 2.490.000đ.\nBạn cần tư vấn thêm kích thước không?",
                FurnitureChatService.extractReplyText(sample));
    }
}
