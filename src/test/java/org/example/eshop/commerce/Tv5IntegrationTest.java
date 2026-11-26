package org.example.eshop.commerce;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.analytics.DashboardService;
import org.example.eshop.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

// Kiểm thử tích hợp MariaDB thật cho toàn bộ luồng TV5 (chạy trong CI hoặc local khi có TV3_TEST_DB_URL)
@TestPropertySource(properties = "commerce.jobs.enabled=false")
@SpringBootTest(classes = CommerceApplication.class)
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
class Tv5IntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("TV3_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "root"));
        r.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Tv5Cache cache;

    MockMvc mvc;
    final CommercePrincipal admin = new CommercePrincipal(201L, "tv5-admin", "Admin TV5", null, Set.of("ADMIN"));
    final CommercePrincipal customer = new CommercePrincipal(202L, "tv5-customer", "Khach TV5", null, Set.of("CUSTOMER"));
    final CommercePrincipal other = new CommercePrincipal(203L, "tv5-other", "Khach Khac", null, Set.of("CUSTOMER"));

    private static final List<String> CLEANUP_TABLES = List.of(
            "chat_messages", "chat_conversations", "review_images", "reviews", "wishlist_items",
            "tracking_events", "articles", "banners", "warranty_requests",
            "commerce_notifications", "commerce_notification_outbox", "audit_logs",
            "refunds", "return_items", "return_requests", "payment_events", "payments",
            "shipment_items", "shipments", "inventory_movements", "inventory_reservations",
            "coupon_redemptions", "order_status_history", "order_addresses", "order_items", "orders",
            "cart_items", "carts", "coupons", "user_addresses", "inventory_balances",
            "product_images", "variant_materials", "product_variants", "products", "categories",
            "user_roles", "users");

    @BeforeEach
    void reset() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("furniture_shop_test_"));
        cleanDatabase();
        String hash = new BCryptPasswordEncoder().encode("test-password");
        for (var a : List.of(admin, customer, other)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",
                    a.getUserId(), a.getUsername(), a.getUsername() + "@example.invalid", hash, a.fullName());
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",
                    a.getUserId(), a == admin ? "ADMIN" : "CUSTOMER");
        }
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(2001,'Sofa','sofa'),(2002,'Bàn trà','ban-tra')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(2001,2001,'Sofa Modena','sofa-modena','ACTIVE'),(2002,2002,'Bàn trà Oslo','ban-tra-oslo','ACTIVE')");
        jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price,status) VALUES(2001,2001,'SOFA-01','kem','Kem',12000000,'ACTIVE'),(2002,2002,'BT-01','soi','Gỗ sồi',3500000,'ACTIVE')");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,2001,4 FROM warehouses WHERE code='MAIN'");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,2002,10 FROM warehouses WHERE code='MAIN'");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    // Dọn sạch các bảng TV5 sau mỗi test để không gây lỗi khóa ngoại khi các test TV3 chạy tiếp theo
    @AfterEach
    void cleanupAfterTest() {
        if (jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("furniture_shop_test_")) {
            cleanDatabase();
        }
    }

    private void cleanDatabase() {
        cache.clearAll();
        for (String table : CLEANUP_TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void committedReceiptRefreshesLowStock() throws Exception {
        long warehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE code='MAIN'",Long.class);
        assertEquals(1,dashboard().path("lowStockVariants").size());
        mvc.perform(post("/api/admin/inventory/receipts").with(user(admin)).with(csrf()).header("Idempotency-Key","dashboard-receipt")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("warehouseId",warehouse,"variantId",2001,"quantity",3,"referenceCode","DASH-RECEIPT","reason","Nhập kho thực tế"))))
                .andExpect(status().isOk());
        assertEquals(0,dashboard().path("lowStockVariants").size());
    }

    @Test
    void committedAdjustmentRefreshesLowStock() throws Exception {
        long warehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE code='MAIN'",Long.class);
        assertEquals(1,dashboard().path("lowStockVariants").size());
        mvc.perform(post("/api/admin/inventory/adjustments").with(user(admin)).with(csrf()).header("Idempotency-Key","dashboard-adjust")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("warehouseId",warehouse,"variantId",2001,"quantityDelta",3,"referenceCode","DASH-ADJUST","reason","Kiểm kê thực tế"))))
                .andExpect(status().isOk());
        assertEquals(0,dashboard().path("lowStockVariants").size());
    }

    @Test
    void committedTransferRefreshesBothWarehousesLowStock() throws Exception {
        long warehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE code='MAIN'",Long.class);
        assertEquals(1,dashboard().path("lowStockVariants").size());
        jdbc.update("INSERT INTO warehouses(code,name) VALUES('DASH-DEST','Dashboard destination')");
        long destination=jdbc.queryForObject("SELECT id FROM warehouses WHERE code='DASH-DEST'",Long.class);
        try {
            mvc.perform(post("/api/admin/inventory/transfers").with(user(admin)).with(csrf()).header("Idempotency-Key","dashboard-transfer")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("sourceWarehouseId",warehouse,"destinationWarehouseId",destination,"variantId",2001,"quantity",2,"referenceCode","DASH-TRANSFER","reason","Chuyển kho thực tế"))))
                    .andExpect(status().isOk());
            assertEquals(2,dashboard().path("lowStockVariants").size());
            assertEquals(2,dashboard().path("lowStockVariants").get(0).path("availableQuantity").asInt());
        } finally {
            jdbc.update("DELETE FROM inventory_movements WHERE warehouse_id=?",destination);
            jdbc.update("DELETE FROM inventory_balances WHERE warehouse_id=?",destination);
            jdbc.update("DELETE FROM warehouses WHERE id=?",destination);
        }
    }

    private JsonNode dashboard() throws Exception {
        return json(mvc.perform(get("/api/admin/dashboard/summary?days=30").with(user(admin))).andExpect(status().isOk()).andReturn());
    }

    @Test
    void failedShipmentReturnedToWarehouseRefreshesLowStock() throws Exception {
        jdbc.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total) VALUES(3201,'NN-DASH-RETURN',202,'dash-return','PROCESSING',12000000,12000000)");
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(3201,3201,2001,2001,'Sofa Modena','Kem','SOFA-01',1,12000000)");
        jdbc.update("INSERT INTO shipments(id,order_id,warehouse_id,status,shipped_at) SELECT 3201,3201,id,'FAILED',UTC_TIMESTAMP(6) FROM warehouses WHERE code='MAIN'");
        jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(3201,3201,3201,1)");
        assertEquals(4,dashboard().path("lowStockVariants").get(0).path("availableQuantity").asInt());
        mvc.perform(post("/api/admin/inventory/shipments/3201/status").with(user(admin)).with(csrf())
                .header("Idempotency-Key","dashboard-return-shipment").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"RETURNED\",\"reason\":\"Hàng thực nhận lại kho\"}"))
                .andExpect(status().isOk());
        assertEquals(5,dashboard().path("lowStockVariants").get(0).path("availableQuantity").asInt());
    }

    @Test
    void contentAdminGuardsAccessAndEvictsCacheOnPublish() throws Exception {
        // Khách thường không được tạo bài viết
        mvc.perform(post("/api/admin/content/articles").with(user(customer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nháp\",\"slug\":\"nhap\",\"content\":\"Nội dung\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden());

        // Đọc public trước để nạp cache rỗng
        assertEquals(0, json(mvc.perform(get("/api/commerce/content/articles")).andExpect(status().isOk()).andReturn())
                .path("totalElements").asInt());

        // Admin tạo bài PUBLISHED -> cache tự xóa sau commit và public thấy ngay
        mvc.perform(post("/api/admin/content/articles").with(user(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Chọn sofa gỗ\",\"slug\":\"chon-sofa-go\",\"content\":\"Đoạn 1\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated());

        JsonNode list = json(mvc.perform(get("/api/commerce/content/articles")).andExpect(status().isOk()).andReturn());
        assertEquals(1, list.path("totalElements").asInt());
        assertEquals("chon-sofa-go", list.path("items").get(0).path("slug").asText());
    }

    @Test
    void reviewRequiresBuyerOwnershipDeliveryAndModeration() throws Exception {
        // Tạo 1 đơn hàng chưa hoàn tất (PENDING_CONFIRMATION) cho customer
        jdbc.update("""
                INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total)
                VALUES(3001,'NN-TV5-1',202,'idem-tv5-1','PENDING_CONFIRMATION',12000000,12000000)
                """);
        jdbc.update("""
                INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price)
                VALUES(3001,3001,2001,2001,'Sofa Modena','Kem','SOFA-01',1,12000000)
                """);

        // Chưa giao -> bị chặn 409
        mvc.perform(post("/api/reviews").with(user(customer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":3001,\"rating\":5,\"content\":\"Rất đẹp\"}"))
                .andExpect(status().isConflict());

        // Người khác đánh giá đơn của customer -> bị chặn 403
        mvc.perform(post("/api/reviews").with(user(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":3001,\"rating\":5,\"content\":\"Đánh giá hộ\"}"))
                .andExpect(status().isForbidden());

        // Có chứng từ giao thực tế cho chính dòng hàng -> customer đánh giá thành công.
        jdbc.update("UPDATE orders SET status='COMPLETED' WHERE id=3001");
        jdbc.update("INSERT INTO shipments(id,order_id,warehouse_id,status,delivered_at) SELECT 3001,3001,id,'DELIVERED',UTC_TIMESTAMP(6) FROM warehouses WHERE code='MAIN'");
        jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(3001,3001,3001,1)");
        long reviewId = json(mvc.perform(post("/api/reviews").with(user(customer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":3001,\"rating\":5,\"title\":\"Rất êm\",\"content\":\"Gỗ chắc chắn\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();

        // Đánh giá lại cùng dòng đơn -> 409
        mvc.perform(post("/api/reviews").with(user(customer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":3001,\"rating\":4,\"content\":\"Lần 2\"}"))
                .andExpect(status().isConflict());

        // Trước khi duyệt -> public chưa thấy
        assertEquals(0, json(mvc.perform(get("/api/commerce/products/2001/reviews")).andExpect(status().isOk()).andReturn())
                .path("totalReviews").asInt());

        // Admin duyệt + phản hồi -> public thấy 1 đánh giá kèm phản hồi
        mvc.perform(patch("/api/admin/reviews/" + reviewId + "/moderate").with(user(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"staffReply\":\"Cảm ơn bạn đã ủng hộ Nếp Nhà.\"}"))
                .andExpect(status().isOk());

        JsonNode pub = json(mvc.perform(get("/api/commerce/products/2001/reviews")).andExpect(status().isOk()).andReturn());
        assertEquals(1, pub.path("totalReviews").asInt());
        assertEquals("Cảm ơn bạn đã ủng hộ Nếp Nhà.", pub.path("items").get(0).path("staffReply").asText());
    }

    @Test
    void reviewNeedsDeliveredUnitsOfTheSelectedLineAndTheAuthenticatedOwner() throws Exception {
        jdbc.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total,completed_at) VALUES(3101,'NN-REVIEW-EVIDENCE',202,'review-evidence','COMPLETED',27500000,27500000,UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(3101,3101,2001,2001,'Sofa Modena','Kem','SOFA-01',2,12000000),(3102,3101,2002,2002,'Bàn trà Oslo','Gỗ sồi','BT-01',1,3500000)");
        // A completed header alone is insufficient, even with completed_at.
        review(3101, customer, 409);
        assertEquals(0, eligible(customer).size());
        jdbc.update("UPDATE orders SET status='PROCESSING',completed_at=NULL WHERE id=3101");
        jdbc.update("INSERT INTO shipments(id,order_id,warehouse_id,status,delivered_at) SELECT 3101,3101,id,'DELIVERED',UTC_TIMESTAMP(6) FROM warehouses WHERE code='MAIN'");
        jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(3101,3101,3102,1)");
        // A delivered different line does not grant access to the sofa line.
        review(3101, customer, 409);
        assertEquals(3102, eligible(customer).get(0).path("orderItemId").asLong());
        jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(3101,3101,3101,1)");
        jdbc.update("UPDATE shipments SET delivered_at=NULL WHERE id=3101");
        review(3101, customer, 409);
        assertEquals(0, eligible(customer).size());
        for (String state : List.of("PENDING", "IN_TRANSIT", "FAILED", "RETURNED")) {
            jdbc.update("UPDATE shipments SET status=?,delivered_at=UTC_TIMESTAMP(6) WHERE id=3101", state);
            review(3101, customer, 409);
        }
        jdbc.update("UPDATE shipments SET status='DELIVERED',delivered_at=UTC_TIMESTAMP(6)+INTERVAL 1 DAY WHERE id=3101");
        review(3101, customer, 409);
        assertEquals(0, eligible(customer).size());
        jdbc.update("UPDATE shipments SET status='DELIVERED',delivered_at=UTC_TIMESTAMP(6) WHERE id=3101");
        assertEquals(2, eligible(customer).size());
        assertEquals(0, eligible(other).size());
        review(3101, other, 403);
        // One of the two ordered units was delivered; the owner may review it now.
        review(3101, customer, 201);
        assertEquals(1, eligible(customer).size());
    }

    private JsonNode eligible(CommercePrincipal actor) throws Exception {
        return json(mvc.perform(get("/api/reviews/eligible").with(user(actor))).andExpect(status().isOk()).andReturn());
    }

    private void review(long item, CommercePrincipal actor, int expected) throws Exception {
        mvc.perform(post("/api/reviews").with(user(actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemId\":" + item + ",\"rating\":5,\"content\":\"Đã nhận hàng\"}"))
                .andExpect(status().is(expected));
    }

    @Test
    void dashboardUsesHalfOpenUtcWindowsAndRefundCompletionTime() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
        var dashboard = new DashboardService(jdbc, fixed, new Tv5Cache(fixed, true));
        jdbc.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total,placed_at) VALUES(4101,'NN-WINDOW-1',202,'window-1','COMPLETED',1000,1000,'2026-11-25 00:00:00'),(4102,'NN-WINDOW-2',202,'window-2','PENDING_CONFIRMATION',1000,1000,'2026-11-26 00:00:00')");
        jdbc.update("INSERT INTO order_items(order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(4101,2001,2001,'Sofa Modena','Kem','SOFA-01',1,1000),(4102,2002,2002,'Bàn trà Oslo','Gỗ sồi','BT-01',9,1000)");
        jdbc.update("INSERT INTO payments(id,order_id,method,provider,idempotency_key,amount,status,paid_at,updated_at) VALUES(4101,4101,'COD','COD','window-pay-1',1000,'SUCCEEDED','2026-11-25 00:00:00','2026-12-01 00:00:00'),(4102,4101,'COD','COD','window-pay-old',700,'SUCCEEDED','2026-11-24 23:59:59.999999','2026-11-25 12:00:00'),(4103,4102,'COD','COD','window-pay-end',900,'SUCCEEDED','2026-11-26 00:00:00','2026-11-26 00:00:00'),(4104,4102,'COD','COD','window-pay-pending',500,'PENDING',NULL,'2026-11-25 12:00:00'),(4105,4102,'COD','COD','window-pay-failed',600,'FAILED',NULL,'2026-11-25 12:00:00')");
        jdbc.update("INSERT INTO refunds(order_id,payment_id,provider,idempotency_key,amount,status,reason,completed_at,created_at,updated_at) VALUES(4101,4101,'MANUAL','window-ref-in',200,'SUCCEEDED','Window refund','2026-11-25 23:59:59.999999','2026-11-24 00:00:00','2026-12-01 00:00:00'),(4101,4101,'MANUAL','window-ref-end',300,'SUCCEEDED','Later refund','2026-11-26 00:00:00','2026-11-25 12:00:00','2026-11-26 00:00:00'),(4101,4101,'MANUAL','window-ref-pending',400,'PENDING','Held budget',NULL,'2026-11-25 00:00:00','2026-11-25 12:00:00')");
        jdbc.update("INSERT INTO tracking_events(event_type,session_id,user_id,occurred_at) VALUES('PAGE_VIEW','window-in',202,'2026-11-25 00:00:00'),('PAGE_VIEW','window-end',202,'2026-11-26 00:00:00')");
        var result = dashboard.summary(1);
        assertEquals("2026-11-25T00:00Z", result.fromUtc());
        assertEquals("2026-11-26T00:00Z", result.toUtc());
        assertEquals("1000.00", result.revenue().grossCollected());
        assertEquals("200.00", result.revenue().totalRefunded());
        assertEquals("800.00", result.revenue().netRevenue());
        assertEquals(1, result.totalOrders());
        assertEquals(2001, result.topProducts().get(0).productId());
        assertEquals(1, result.funnel().get("PAGE_VIEW"));
        Clock nextDay = Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC);
        var later = new DashboardService(jdbc, nextDay, new Tv5Cache(nextDay, true)).summary(1);
        assertEquals("900.00", later.revenue().grossCollected());
        assertEquals("300.00", later.revenue().totalRefunded());
        assertEquals("600.00", later.revenue().netRevenue());
    }

    @Test
    void dashboardComputesRealNetRevenueAndChatEnforcesOwnerIsolation() throws Exception {
        // Đơn 1: thu 12tr thành công, hoàn 2tr thành công -> ròng 10tr; Đơn 2: thu PENDING 3.5tr (không được cộng)
        jdbc.update("""
                INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total,placed_at)
                VALUES(4001,'NN-TV5-R1',202,'idem-r1','COMPLETED',12000000,12000000,UTC_TIMESTAMP(6)),
                      (4002,'NN-TV5-R2',202,'idem-r2','PENDING_CONFIRMATION',3500000,3500000,UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO payments(id,order_id,method,provider,idempotency_key,amount,status,paid_at)
                VALUES(4001,4001,'BANK_TRANSFER','MANUAL','pay-r1',12000000,'SUCCEEDED',UTC_TIMESTAMP(6)),
                      (4002,4002,'COD','COD','pay-r2',3500000,'PENDING',NULL)
                """);
        jdbc.update("""
                INSERT INTO refunds(id,order_id,payment_id,provider,idempotency_key,amount,status,reason,completed_at)
                VALUES(4001,4001,4001,'MANUAL','ref-r1',2000000,'SUCCEEDED','Hoàn một phần',UTC_TIMESTAMP(6))
                """);

        JsonNode dash = json(mvc.perform(get("/api/admin/dashboard/summary?days=30").with(user(admin)))
                .andExpect(status().isOk()).andReturn());
        assertEquals("12000000.00", dash.path("revenue").path("grossCollected").asText());
        assertEquals("2000000.00", dash.path("revenue").path("totalRefunded").asText());
        assertEquals("10000000.00", dash.path("revenue").path("netRevenue").asText());

        // Chat: customer tạo hội thoại, other không được đọc dù biết publicId
        String convId = json(mvc.perform(post("/api/commerce/chat").with(user(customer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tư vấn bàn trà dưới 5 triệu\"}"))
                .andExpect(status().isOk()).andReturn()).path("conversationId").asText();

        mvc.perform(get("/api/commerce/chat/" + convId).with(user(customer))).andExpect(status().isOk());
        mvc.perform(get("/api/commerce/chat/" + convId).with(user(other))).andExpect(status().isNotFound());
    }
}
