package org.example.eshop.commerce;

import org.example.eshop.commerce.ai.FurnitureChatService;
import org.example.eshop.commerce.analytics.DashboardService;
import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.content.BannerService;
import org.example.eshop.commerce.content.ContentValidation;
import org.example.eshop.commerce.port.CommerceActor;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.example.eshop.commerce.tracking.CommerceTrackingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    @SuppressWarnings("unchecked")
    void cacheUsesRedisJsonAndInvalidatesByGeneration() {
        var redisStore = new HashMap<String, String>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            long next = Long.parseLong(redisStore.getOrDefault(key, "0")) + 1;
            redisStore.put(key, Long.toString(next));
            return next;
        });

        var cache = new Tv5Cache(FIXED_CLOCK, true, true, redis, new ObjectMapper());
        var listType = new TypeReference<List<BannerService.BannerView>>() {};
        int[] loads = {0};
        var first = cache.getOrLoad(Tv5Cache.BANNERS, "HOME_HERO", Duration.ofMinutes(10), listType, () -> {
            loads[0]++;
            return List.of(new BannerService.BannerView(1, "Banner " + loads[0], null, "/images/home-hero.jpg",
                    null, null, "/sale", "HOME_HERO", 0, true, null, null, null));
        });
        var second = cache.getOrLoad(Tv5Cache.BANNERS, "HOME_HERO", Duration.ofMinutes(10), listType, () -> {
            loads[0]++;
            return List.of();
        });
        assertEquals("Banner 1", first.get(0).title());
        assertEquals("Banner 1", second.get(0).title());
        assertEquals(1, loads[0]);
        assertEquals("REDIS", cache.stats().backend());
        assertEquals(1, cache.stats().hits());
        assertEquals(1, cache.stats().misses());

        // Khi admin sửa banner -> tăng generation trong Redis -> lần đọc tiếp theo tự tải bản mới từ DB
        cache.clearNamespace(Tv5Cache.BANNERS);
        var third = cache.getOrLoad(Tv5Cache.BANNERS, "HOME_HERO", Duration.ofMinutes(10), listType, () -> {
            loads[0]++;
            return List.of(new BannerService.BannerView(1, "Banner " + loads[0], null, "/images/home-hero.jpg",
                    null, null, "/sale", "HOME_HERO", 0, true, null, null, null));
        });
        assertEquals("Banner 2", third.get(0).title());
        assertEquals(2, loads[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheFallsBackToMemoryWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("Connection refused"));

        var cache = new Tv5Cache(FIXED_CLOCK, true, true, redis, new ObjectMapper());
        int[] loads = {0};
        String first = cache.getOrLoad(Tv5Cache.ARTICLES, "slug:demo", Duration.ofMinutes(5), String.class, () -> {
            loads[0]++;
            return "bai-viet-" + loads[0];
        });
        String second = cache.getOrLoad(Tv5Cache.ARTICLES, "slug:demo", Duration.ofMinutes(5), String.class, () -> {
            loads[0]++;
            return "bai-viet-" + loads[0];
        });
        assertEquals("bai-viet-1", first);
        assertEquals("bai-viet-1", second);
        assertEquals(1, loads[0]);
        assertEquals("MEMORY_FALLBACK", cache.stats().backend());
        assertEquals(1, cache.stats().hits());
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
