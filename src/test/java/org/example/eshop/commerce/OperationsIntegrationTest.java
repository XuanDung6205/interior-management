package org.example.eshop.commerce;

import org.example.eshop.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@org.springframework.test.context.TestPropertySource(properties="commerce.jobs.enabled=false")
@SpringBootTest(classes=CommerceApplication.class,properties={"commerce.payments.bank.mode=DEMO","commerce.jobs.enabled=false"})
@org.springframework.context.annotation.Import(OperationsIntegrationTest.ClockConfig.class)
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class OperationsIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));}
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    final CommercePrincipal admin=new CommercePrincipal(101L,"admin","Admin",null,Set.of("ADMIN"));
    final CommercePrincipal customer=new CommercePrincipal(102L,"customer","Customer",null,Set.of("CUSTOMER"));
    final CommercePrincipal other=new CommercePrincipal(103L,"other","Other",null,Set.of("CUSTOMER"));
    final CommercePrincipal staff=new CommercePrincipal(104L,"staff","Staff",null,Set.of("STAFF"));
    @BeforeEach void setup(){
        clockNow.set(java.time.Instant.parse("2026-11-19T08:00:00Z")); assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_")); db.execute("DROP TRIGGER IF EXISTS ops_fail_enqueue");db.execute("DROP TRIGGER IF EXISTS ops_fail_delivery");db.execute("DROP TRIGGER IF EXISTS ops_fail_audit");db.execute("DROP TRIGGER IF EXISTS tv3_payment_audit_fail");
        for(String t:List.of("audit_logs","refunds","return_items","return_requests","payment_events","payments","shipment_items","shipments","inventory_movements","inventory_reservations","coupon_redemptions","order_status_history","order_addresses","order_items","orders","cart_items","carts","coupons","user_addresses","inventory_balances","product_images","variant_materials","product_variants","products","categories","user_roles","users"))db.update("DELETE FROM "+t);
        for(var actor:List.of(admin,customer,other,staff)){db.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",actor.id(),actor.getUsername(),actor.getUsername()+"@example.invalid","unused",actor.getUsername());db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",actor.id(),actor.roles().iterator().next());}
        db.update("INSERT INTO categories(id,name,slug) VALUES(1001,'Sofa','payment-sofa')");db.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(1001,1001,'Sofa payment','payment-sofa','ACTIVE')");db.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price) VALUES(1001,1001,'PAY-KEM','cream','Kem',1000000)");db.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,1001,50 FROM warehouses WHERE code='MAIN'");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    JsonNode json(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    long order(CommercePrincipal buyer,String method)throws Exception{return order(buyer,method,2,null);}
    long order(CommercePrincipal buyer,String method,int quantity,String coupon)throws Exception{
        var cart=json(mvc.perform(post("/api/cart/items").with(user(buyer)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":"+quantity+"}")).andExpect(status().isOk()).andReturn());
        var body=new LinkedHashMap<String,Object>();body.put("cartId",cart.get("id").asLong());body.put("paymentMethod",method);body.put("couponCode",coupon);body.put("shippingAddress",Map.of("recipientName",buyer.getUsername(),"phone","0901234567","addressLine1","12 Test","provinceName","Hồ Chí Minh","countryCode","VN"));
        var q=json(mvc.perform(post("/api/checkout/quote").with(user(buyer)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn());body.put("quoteFingerprint",q.get("quoteFingerprint").asText());
        return json(mvc.perform(post("/api/checkout/orders").with(user(buyer)).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }
    long payment(long order){return db.queryForObject("SELECT id FROM payments WHERE order_id=? ORDER BY id DESC LIMIT 1",Long.class,order);}
    JsonNode summary(long order,CommercePrincipal actor,int expected)throws Exception{return json(mvc.perform(get("/api/orders/"+order+"/payments").with(user(actor))).andExpect(status().is(expected)).andReturn());}
    JsonNode confirm(long id,CommercePrincipal actor,String ref,String note,int expected)throws Exception{return json(mvc.perform(post("/api/admin/payments/"+id+"/confirm").with(user(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("reference",ref,"note",note,"acknowledged",true)))).andExpect(status().is(expected)).andReturn());}
    JsonNode create(long order,String method,String key,int expected)throws Exception{return json(mvc.perform(post("/api/orders/"+order+"/payments").with(user(customer)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("method",method)))).andExpect(status().is(expected)).andReturn());}
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void cancel(long order)throws Exception{mvc.perform(post("/api/orders/"+order+"/cancel").with(user(customer)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đổi ý\"}")).andExpect(status().isOk());}
    @AfterEach void removeReturnFixtures(){db.execute("DROP TRIGGER IF EXISTS tv3_refund_audit_fail");db.update("DELETE FROM refunds WHERE return_request_id IS NOT NULL");db.update("DELETE FROM return_items");db.update("DELETE FROM return_requests");}
    static final java.util.concurrent.atomic.AtomicReference<java.time.Instant> clockNow=new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-19T08:00:00Z"));
    @org.springframework.boot.test.context.TestConfiguration static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary java.time.Clock testClock(){return new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return clockNow.get();}};}
    }
    void advance(long seconds){clockNow.updateAndGet(i->i.plusSeconds(seconds));}
    JsonNode job(String kind)throws Exception{return json(mvc.perform(post("/api/admin/operations/"+kind+"/run").with(user(admin)).with(csrf())).andExpect(status().isOk()).andReturn());}
    JsonNode inbox(CommercePrincipal actor)throws Exception{return json(mvc.perform(get("/api/notifications").with(user(actor))).andExpect(status().isOk()).andReturn());}
    JsonNode ops()throws Exception{return json(mvc.perform(get("/api/admin/operations").with(user(admin))).andExpect(status().isOk()).andReturn());}
    @Test void newOperationsAndInboxEnforceOwnerStaffAndCsrf()throws Exception{
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/operations")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/operations").with(user(customer))).andExpect(status().isForbidden());mvc.perform(post("/api/admin/operations/expiry/run").with(user(admin))).andExpect(status().isForbidden());mvc.perform(get("/api/notifications?size=101").with(user(customer))).andExpect(status().isBadRequest());
    }
    @Test void onlyNewPositiveCodHasServerOwnedTwentyFourHourDeadline()throws Exception{
        long cod=order(customer,"COD"),bank=order(other,"BANK_TRANSFER");assertEquals("2026-11-20T08:00:00Z",db.queryForObject("SELECT confirmation_expires_at FROM orders WHERE id=?",java.sql.Timestamp.class,cod).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toString());assertNull(db.queryForObject("SELECT confirmation_expires_at FROM orders WHERE id=?",java.sql.Timestamp.class,bank));var own=json(mvc.perform(get("/api/orders/"+cod).with(user(customer))).andExpect(status().isOk()).andReturn());assertEquals("2026-11-20T08:00:00Z",own.get("confirmationExpiresAt").asText());
    }
    @Test void expiryReleasesOnlyUnfulfilledCodAndCouponExactlyOnceWithSystemAudit()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('EXP10','PERCENT',10,'2020-01-01','2099-01-01')");long id=order(customer,"COD",2,"EXP10");assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));advance(86401);assertEquals(1,job("expiry").get("expired").asInt());assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals("EXPIRED",db.queryForObject("SELECT status FROM payments WHERE order_id=?",String.class,id));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals("RELEASED",db.queryForObject("SELECT status FROM coupon_redemptions WHERE order_id=?",String.class,id));assertNull(db.queryForObject("SELECT actor_user_id FROM audit_logs WHERE action='ORDER_EXPIRED'",Long.class));assertNull(db.queryForObject("SELECT changed_by FROM order_status_history WHERE order_id=? AND to_status='CANCELLED'",Long.class,id));int audits=count("audit_logs"),events=count("commerce_notification_outbox");assertEquals(0,job("expiry").get("expired").asInt());assertEquals(audits,count("audit_logs"));assertEquals(events,count("commerce_notification_outbox"));
    }
    @Test void paidBankLegacyFreeConfirmedAndDispatchedOrdersNeverAutoExpire()throws Exception{
        long paid=order(customer,"COD");confirm(payment(paid),admin,"OPS-PAID-RECEIPT","",200);long bank=order(customer,"BANK_TRANSFER");long legacy=order(customer,"COD");db.update("UPDATE orders SET confirmation_expires_at=NULL WHERE id=?",legacy);long confirmed=order(customer,"COD");mvc.perform(post("/api/admin/orders/"+confirmed+"/transitions").with(user(admin)).with(csrf()).contentType("application/json").content("{\"fromStatus\":\"PENDING_CONFIRMATION\",\"toStatus\":\"CONFIRMED\"}")).andExpect(status().isOk());long dispatched=order(customer,"COD");db.update("INSERT INTO shipments(order_id,warehouse_id,status,shipped_at) SELECT ?,id,'IN_TRANSIT','2026-11-19' FROM warehouses WHERE code='MAIN'",dispatched);db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('FREEOPS','PERCENT',100,'2020-01-01','2099-01-01')");long free=order(customer,"COD",10,"FREEOPS");advance(86401);assertEquals(0,job("expiry").get("expired").asInt());for(long id:List.of(paid,bank,legacy,dispatched,free))assertEquals("PENDING_CONFIRMATION",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals("CONFIRMED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,confirmed));
    }
    @Test void expiryAgainstActualCollectionNeverLosesMoney()throws Exception{
        long id=order(customer,"COD"),p=payment(id);advance(86401);race(()->job("expiry"),()->confirm(p,staff,"OPS-RACE-COLLECTION","",200));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));String state=db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id);if("CANCELLED".equals(state)){assertEquals(1,count("refunds"));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));}else{assertEquals("PENDING_CONFIRMATION",state);assertEquals(0,count("refunds"));assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));}
    }
    @Test void expiryFailureRollsBackAndRetriesAfterDurableBackoff()throws Exception{
        long id=order(customer,"COD");advance(86401);db.execute("CREATE TRIGGER ops_fail_audit BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops audit failure'");try{assertEquals(1,job("expiry").get("failed").asInt());assertEquals("PENDING_CONFIRMATION",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals(1,db.queryForObject("SELECT expiry_attempts FROM orders WHERE id=?",Integer.class,id));assertNotNull(db.queryForObject("SELECT expiry_retry_after FROM orders WHERE id=?",java.sql.Timestamp.class,id));assertEquals(0,job("expiry").get("scanned").asInt());}finally{db.execute("DROP TRIGGER ops_fail_audit");}advance(3601);assertEquals(1,job("expiry").get("expired").asInt());
    }
    @Test void outboxEnqueueFailureRollsBackBusinessOrderAndHolds()throws Exception{
        db.execute("CREATE TRIGGER ops_fail_enqueue BEFORE INSERT ON commerce_notification_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops enqueue failure'");try{assertThrows(Exception.class,()->order(customer,"COD"));}finally{db.execute("DROP TRIGGER ops_fail_enqueue");}assertEquals(0,count("orders"));assertEquals(0,count("payments"));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    @Test void concurrentOutboxWorkersDeliverOnceAndReadIsOwnerScopedIdempotent()throws Exception{
        long id=order(customer,"COD");race(()->job("notifications"),()->job("notifications"));assertEquals(1,count("commerce_notifications"));assertEquals("SENT",db.queryForObject("SELECT state FROM commerce_notification_outbox WHERE order_id=?",String.class,id));var own=inbox(customer);assertEquals(1,own.get("unreadCount").asInt());assertEquals(0,inbox(other).get("totalElements").asInt());long notification=own.get("items").get(0).get("id").asLong();mvc.perform(post("/api/notifications/"+notification+"/read").with(user(other)).with(csrf())).andExpect(status().isNotFound());mvc.perform(post("/api/notifications/"+notification+"/read").with(user(customer)).with(csrf())).andExpect(status().isOk());var before=db.queryForObject("SELECT read_at FROM commerce_notifications WHERE id=?",java.sql.Timestamp.class,notification);advance(3600);mvc.perform(post("/api/notifications/"+notification+"/read").with(user(customer)).with(csrf())).andExpect(status().isOk());assertEquals(before,db.queryForObject("SELECT read_at FROM commerce_notifications WHERE id=?",java.sql.Timestamp.class,notification));assertEquals(0,inbox(customer).get("unreadCount").asInt());
    }
    @Test void privateStaffNotesNeverEnterOwnerNotifications()throws Exception{
        long id=order(customer,"COD");confirm(payment(id),admin,"OPS-PRIVATE-PROOF","OPS_PRIVATE_STAFF_NOTE",200);mvc.perform(patch("/api/admin/orders/"+id+"/internal-note").with(user(admin)).with(csrf()).contentType("application/json").content("{\"internalNote\":\"OPS_PRIVATE_INTERNAL\",\"expectedInternalNote\":null}")).andExpect(status().isOk());job("notifications");String notifications=inbox(customer).toString();assertFalse(notifications.contains("OPS_PRIVATE"));assertFalse(notifications.contains("OPS-PRIVATE-PROOF"));assertEquals(2,count("commerce_notifications"));
    }
    @Test void deliveryFailureBackoffDeadLetterAndExplicitRetryAreDurable()throws Exception{
        order(customer,"COD");db.execute("CREATE TRIGGER ops_fail_delivery BEFORE INSERT ON commerce_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ops delivery failure'");try{for(int i=0;i<5;i++){assertEquals(1,job("notifications").get("failed").asInt());assertEquals(0,count("commerce_notifications"));if(i<4){assertEquals(0,job("notifications").get("scanned").asInt());advance(3601);}}assertEquals("DEAD",db.queryForObject("SELECT state FROM commerce_notification_outbox",String.class));assertEquals(5,db.queryForObject("SELECT attempts FROM commerce_notification_outbox",Integer.class));assertEquals(1,ops().get("deadEvents").size());}finally{db.execute("DROP TRIGGER ops_fail_delivery");}long outbox=db.queryForObject("SELECT id FROM commerce_notification_outbox",Long.class);mvc.perform(post("/api/admin/operations/notifications/"+outbox+"/retry").with(user(admin)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đã sửa lỗi lưu thông báo\"}")).andExpect(status().isOk());assertEquals(1,job("notifications").get("delivered").asInt());assertEquals(1,count("commerce_notifications"));assertEquals(0,ops().get("outbox").get("dead").asInt());
    }
    @Test void poolSnapshotIsActualAndContainsNoConnectionCredentials()throws Exception{
        var snapshot=ops();assertTrue(snapshot.get("pool").get("available").asBoolean());assertTrue(snapshot.get("pool").get("max").asInt()>0);assertTrue(snapshot.get("pool").get("total").asInt()>=snapshot.get("pool").get("active").asInt());assertEquals(86400,snapshot.get("jobs").get("confirmationTtlSeconds").asInt());assertFalse(snapshot.toString().contains("jdbc:mariadb"));assertFalse(snapshot.toString().toLowerCase().contains("password"));
    }
    void race(Callable<?> a,Callable<?> b)throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var first=pool.submit(()->{start.await();return a.call();});var second=pool.submit(()->{start.await();return b.call();});start.countDown();first.get(25,TimeUnit.SECONDS);second.get(25,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
}
