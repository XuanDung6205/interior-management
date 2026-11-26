package org.example.eshop.commerce;

import org.example.eshop.commerce.cache.Tv5Cache;
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

        // Đơn hoàn tất -> customer đánh giá thành công, trạng thái PENDING
        jdbc.update("UPDATE orders SET status='COMPLETED' WHERE id=3001");
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
    void dashboardComputesRealNetRevenueAndChatEnforcesOwnerIsolation() throws Exception {
        // Đơn 1: thu 12tr thành công, hoàn 2tr thành công -> ròng 10tr; Đơn 2: thu PENDING 3.5tr (không được cộng)
        jdbc.update("""
                INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total)
                VALUES(4001,'NN-TV5-R1',202,'idem-r1','COMPLETED',12000000,12000000),
                      (4002,'NN-TV5-R2',202,'idem-r2','PENDING_CONFIRMATION',3500000,3500000)
                """);
        jdbc.update("""
                INSERT INTO payments(id,order_id,method,provider,idempotency_key,amount,status,paid_at)
                VALUES(4001,4001,'BANK_TRANSFER','MANUAL','pay-r1',12000000,'SUCCEEDED',CURRENT_TIMESTAMP(6)),
                      (4002,4002,'COD','COD','pay-r2',3500000,'PENDING',NULL)
                """);
        jdbc.update("""
                INSERT INTO refunds(id,order_id,payment_id,provider,idempotency_key,amount,status,reason,completed_at)
                VALUES(4001,4001,4001,'MANUAL','ref-r1',2000000,'SUCCEEDED','Hoàn một phần',CURRENT_TIMESTAMP(6))
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
