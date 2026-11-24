package org.example.eshop.commerce;

import jakarta.servlet.http.Cookie;
import org.example.eshop.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@org.springframework.test.context.TestPropertySource(properties="commerce.jobs.enabled=false")
@SpringBootTest(classes=CommerceApplication.class)
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL", matches=".*furniture_shop_test_.*")
class CommerceDatabaseTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    final CommercePrincipal admin=new CommercePrincipal(101L,"tv3-admin","Admin",null,Set.of("ADMIN"));
    final CommercePrincipal customer=new CommercePrincipal(102L,"tv3-customer","Customer",null,Set.of("CUSTOMER"));
    final CommercePrincipal other=new CommercePrincipal(103L,"tv3-other","Other",null,Set.of("CUSTOMER"));

    @BeforeEach void reset() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));
        jdbc.update("DELETE FROM banners");
        for(String table:List.of("refunds","payment_events","payments","shipment_items","shipments","inventory_movements","inventory_reservations","coupon_redemptions","order_status_history","order_addresses","order_items","orders","cart_items","carts","coupons","user_addresses","inventory_balances","product_images","variant_materials","product_variants","products","categories","user_roles","users")) jdbc.update("DELETE FROM "+table);
        String hash=new BCryptPasswordEncoder().encode("test-password");
        for(var a:List.of(admin,customer,other)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",a.getUserId(),a.getUsername(),a.getUsername()+"@example.invalid",hash,a.getUsername());
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",a.getUserId(),a==admin?"ADMIN":"CUSTOMER");
        }
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(1001,'Sofa','test-sofa')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(1001,1001,'Sofa Modena','test-modena','ACTIVE')");
        jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price) VALUES(1001,1001,'TEST-KEM','cream','Kem',100.05),(1002,1001,'TEST-XAM','grey','Xám',200)");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,1001,100 FROM warehouses WHERE code='MAIN'");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,1002,100 FROM warehouses WHERE code='MAIN'");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    Cookie guest() throws Exception {
        var result=mvc.perform(get("/api/cart")).andExpect(status().isOk()).andReturn();
        for(String header:result.getResponse().getHeaders("Set-Cookie")) if(header.startsWith("FURNITURE_CART=")) return new Cookie("FURNITURE_CART",header.split(";",2)[0].split("=",2)[1]);
        throw new AssertionError("Missing guest cookie");
    }
    String couponBody(String code) {
        return "{\"code\":\""+code+"\",\"discountType\":\"PERCENT\",\"discountValue\":\"10\",\"minimumOrderAmount\":\"0\",\"startsAt\":\""+Instant.now().minusSeconds(60)+"\",\"endsAt\":\""+Instant.now().plusSeconds(3600)+"\"}";
    }
    @Test void publicHomeGalleryShowsOnlyActiveCurrentImagesInOrder() throws Exception {
        jdbc.update("INSERT INTO banners(title,image_url,placement,sort_order,is_active) VALUES('Second','/images/second.jpg','HOME_GALLERY',20,TRUE),('First','/images/first.jpg','HOME_GALLERY',10,TRUE),('Other','/images/other.jpg','HOME_HERO',0,TRUE),('Hidden','/images/hidden.jpg','HOME_GALLERY',0,FALSE)");
        jdbc.update("INSERT INTO banners(title,image_url,placement,sort_order,starts_at) VALUES('Future','/images/future.jpg','HOME_GALLERY',0,DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO banners(title,image_url,placement,sort_order,ends_at) VALUES('Expired','/images/expired.jpg','HOME_GALLERY',0,DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))");
        var response=json(mvc.perform(get("/api/commerce/home/gallery")).andExpect(status().isOk()).andReturn());
        assertEquals(2,response.get("items").size());
        assertEquals("First",response.get("items").get(0).get("title").asText());
        assertEquals("/images/second.jpg",response.get("items").get(1).get("imageUrl").asText());
        assertEquals("Second",response.get("items").get(1).get("altText").asText());
    }
    long createCoupon(String code) throws Exception {
        return json(mvc.perform(post("/api/admin/coupons").with(user(admin)).with(csrf()).contentType("application/json").content(couponBody(code))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }
    @Test void guestCartPersistsAndUsesCurrentSkuPrice() throws Exception {
        Cookie cookie=guest();
        var added=json(mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":2}")).andExpect(status().isOk()).andReturn());
        assertEquals("200.10",added.get("subtotal").asText());
        assertEquals("100.05",added.get("items").get(0).get("unitPrice").asText());
        var later=json(mvc.perform(get("/api/cart").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(2,later.get("itemCount").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM carts",Integer.class));
    }
    @Test void concurrentAddsCreateOneCartAndLoseNoQuantity() throws Exception {
        Cookie cookie=guest(); ExecutorService executor=Executors.newFixedThreadPool(8); CountDownLatch ready=new CountDownLatch(1);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<12;i++) results.add(executor.submit(()->{ready.await();return mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":1}")).andReturn().getResponse().getStatus();}));
            ready.countDown(); for(var result:results) assertEquals(200,result.get(30,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM carts",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cart_items",Integer.class));
        assertEquals(12,jdbc.queryForObject("SELECT quantity FROM cart_items",Integer.class));
    }
    @Test void rejectsBadQuantitiesStockAndForeignLineMutations() throws Exception {
        var added=json(mvc.perform(post("/api/cart/items").with(user(customer)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":2}")).andExpect(status().isOk()).andReturn());
        long item=added.get("items").get(0).get("id").asLong();
        mvc.perform(patch("/api/cart/items/"+item).with(user(other)).with(csrf()).contentType("application/json").content("{\"quantity\":1}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/cart/items/"+item).with(user(other)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/cart/items/"+item).with(user(customer)).with(csrf()).contentType("application/json").content("{\"quantity\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/cart/items").with(user(customer)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":101}")).andExpect(status().isConflict());
        mvc.perform(post("/api/cart/items").with(user(customer)).contentType("application/json").content("{\"variantId\":1001,\"quantity\":1}")).andExpect(status().isForbidden());
    }
    @Test void mergePreservesExcessStockAndCannotConsumeGuestTwice() throws Exception {
        Cookie cookie=guest();
        mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":3}")).andExpect(status().isOk());
        mvc.perform(post("/api/cart/items").with(user(customer)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":4}")).andExpect(status().isOk());
        jdbc.update("UPDATE inventory_balances SET on_hand_quantity=5 WHERE variant_id=1001");
        var merged=json(mvc.perform(post("/api/cart/merge").with(user(customer)).cookie(cookie).with(csrf())).andExpect(status().isOk()).andReturn());
        assertEquals(7,merged.get("itemCount").asInt()); assertFalse(merged.get("warnings").isEmpty());
        var retry=json(mvc.perform(post("/api/cart/merge").with(user(customer)).cookie(cookie).with(csrf())).andExpect(status().isOk()).andReturn());
        assertEquals(7,retry.get("itemCount").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM carts WHERE status='CONVERTED'",Integer.class));
    }
    @Test void simultaneousMergesUseCurrentQuantitiesAfterWaitingForCartLock() throws Exception {
        mvc.perform(post("/api/cart/items").with(user(customer)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":1}")).andExpect(status().isOk());
        Cookie a=guest(),b=guest();
        for(var cookie:List.of(a,b)) mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":3}")).andExpect(status().isOk());
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try(Connection blocker=jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false); long connectionId;
            try(var statement=blocker.createStatement();var result=statement.executeQuery("SELECT CONNECTION_ID()")){result.next();connectionId=result.getLong(1);}
            try(var statement=blocker.createStatement();var result=statement.executeQuery("SELECT id FROM carts WHERE active_user_id=102 FOR UPDATE")){assertTrue(result.next());}
            List<Future<Integer>> jobs=new ArrayList<>();
            for(var cookie:List.of(a,b)) jobs.add(executor.submit(()->mvc.perform(post("/api/cart/merge").with(user(customer)).cookie(cookie).with(csrf())).andReturn().getResponse().getStatus()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);long waiting=0;
            while(System.nanoTime()<deadline && waiting<2 && jobs.stream().noneMatch(Future::isDone)){
                waiting=jdbc.queryForObject("SELECT COUNT(DISTINCT w.requesting_trx_id) FROM information_schema.INNODB_LOCK_WAITS w JOIN information_schema.INNODB_TRX t ON w.requesting_trx_id=t.trx_id WHERE t.trx_query LIKE '%carts%'",Long.class);
                if(waiting<2)Thread.sleep(20);
            }
            blocker.commit();
            for(var job:jobs)assertEquals(200,job.get(20,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
        assertEquals(7,jdbc.queryForObject("SELECT i.quantity FROM cart_items i JOIN carts c ON c.id=i.cart_id WHERE c.active_user_id=102",Integer.class));
    }
    @Test void expiredGuestCartDoesNotResurrectItems() throws Exception {
        Cookie cookie=guest();
        mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":2}")).andExpect(status().isOk());
        jdbc.update("UPDATE carts SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY)");
        assertEquals(0,json(mvc.perform(get("/api/cart").cookie(cookie)).andReturn()).get("itemCount").asInt());
        mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1002,\"quantity\":1}")).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM carts WHERE status='ABANDONED'",Integer.class));
    }
    @Test void inactiveSkuRemainsVisibleAndCanBeRemoved() throws Exception {
        Cookie cookie=guest();
        var added=json(mvc.perform(post("/api/cart/items").cookie(cookie).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":2}")).andReturn());
        long item=added.get("items").get(0).get("id").asLong();
        jdbc.update("UPDATE product_variants SET status='INACTIVE' WHERE id=1001");
        var cart=json(mvc.perform(get("/api/cart").cookie(cookie)).andReturn());
        assertFalse(cart.get("items").get(0).get("active").asBoolean()); assertFalse(cart.get("warnings").isEmpty());
        mvc.perform(delete("/api/cart/items/"+item).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(0));
    }
    @Test void couponCrudPreviewAndPartialPatchEnforceRolesAndValidation() throws Exception {
        mvc.perform(get("/api/admin/coupons").with(user(customer))).andExpect(status().isForbidden());
        long id=createCoupon(" home10 ");
        mvc.perform(post("/api/admin/coupons/"+id+"/preview").with(user(admin)).with(csrf()).contentType("application/json").content("{\"merchandiseAmount\":\"100.00\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.discountAmount").value("10.00"));
        mvc.perform(patch("/api/admin/coupons/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"isActive\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("HOME10")).andExpect(jsonPath("$.isActive").value(false));
        mvc.perform(patch("/api/admin/coupons/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"createdAt\":\"fake\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/coupons").with(user(admin)).with(csrf()).contentType("application/json").content(couponBody("TOOBIG").replace("\"10\"","\"10.001\""))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/coupons").with(user(admin)).with(csrf()).contentType("application/json").content(couponBody("home10"))).andExpect(status().isConflict());
        mvc.perform(get("/api/admin/coupons?size=101").with(user(admin))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/coupons/"+id).with(user(admin)).with(csrf())).andExpect(status().isNoContent());
    }
    @Test void usedCouponCannotBeDeletedAndSnapshotsSurviveDeactivation() throws Exception {
        long id=createCoupon("USED10");
        jdbc.update("INSERT INTO orders(order_number,user_id,idempotency_key,subtotal,discount_amount,grand_total,coupon_id,coupon_code_snapshot) VALUES('TV3-ORDER',102,'tv3-order-key',100,10,90,?,'USED10')",id);
        mvc.perform(delete("/api/admin/coupons/"+id).with(user(admin)).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON_IN_USE"));
        mvc.perform(patch("/api/admin/coupons/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"code\":\"RENAMED\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/coupons/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"isActive\":false}")).andExpect(status().isOk());
        assertEquals("USED10",jdbc.queryForObject("SELECT coupon_code_snapshot FROM orders WHERE order_number='TV3-ORDER'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT grand_total FROM orders WHERE order_number='TV3-ORDER'",java.math.BigDecimal.class).compareTo(new java.math.BigDecimal("90")));
    }
}
