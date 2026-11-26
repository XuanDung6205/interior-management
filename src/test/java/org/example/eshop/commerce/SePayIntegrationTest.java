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
@SpringBootTest(classes=CommerceApplication.class,properties={"commerce.payments.bank.mode=DEMO", "commerce.payments.sepay.enabled=true", "commerce.payments.sepay.api-token=unit-test-token", "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED", "commerce.payments.sepay.webhook-secret=test-only-hmac-key-at-least-32-characters", "commerce.payments.sepay.bank=VietinBank", "commerce.payments.sepay.account-number=0000000001", "commerce.payments.sepay.account-holder=TEST ACCOUNT"})
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class SePayIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));}
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    @Autowired org.example.eshop.commerce.payment.SePayAutomationService automation;
    @Autowired org.example.eshop.commerce.analytics.DashboardService dashboard;
    @Autowired org.example.eshop.commerce.port.OrderAuditPort audit;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.example.eshop.commerce.cache.Tv5Cache dashboardCache;
    MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.example.eshop.commerce.order.OrderStore storeSpy;
    @org.springframework.test.context.bean.override.mockito.MockitoBean org.example.eshop.commerce.payment.SePayApiClient provider;
    final CommercePrincipal admin=new CommercePrincipal(101L,"admin","Admin",null,Set.of("ADMIN"));
    final CommercePrincipal customer=new CommercePrincipal(102L,"customer","Customer",null,Set.of("CUSTOMER"));
    final CommercePrincipal other=new CommercePrincipal(103L,"other","Other",null,Set.of("CUSTOMER"));
    final CommercePrincipal staff=new CommercePrincipal(104L,"staff","Staff",null,Set.of("STAFF"));
    @BeforeEach void setup(){
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));db.execute("DROP TRIGGER IF EXISTS tv3_payment_audit_fail");
        for(String t:List.of("audit_logs","refunds","payment_events","payments","shipment_items","shipments","inventory_movements","inventory_reservations","coupon_redemptions","order_status_history","order_addresses","order_items","orders","cart_items","carts","coupons","user_addresses","inventory_balances","product_images","variant_materials","product_variants","products","categories","user_roles","users"))db.update("DELETE FROM "+t);
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

    String code(long p){return db.queryForObject("SELECT sepay_code FROM payments WHERE id=?",String.class,p);}
    String event(long id,String code,int amount,String account,String bank){return "{\"id\":"+id+",\"gateway\":\""+bank+"\",\"transactionDate\":\"2026-11-25 10:00:00\",\"accountNumber\":\""+account+"\",\"subAccount\":null,\"code\":\""+code+"\",\"content\":\"SEVQR "+code+" thanh toan\",\"transferType\":\"in\",\"transferAmount\":"+amount+",\"referenceCode\":\"FT-TEST-"+id+"\"}";}
    String sign(String body,String ts)throws Exception{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("test-only-hmac-key-at-least-32-characters".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal((ts+"."+body).getBytes(StandardCharsets.UTF_8)));}
    JsonNode webhook(String body,int expected)throws Exception{String ts=Long.toString(java.time.Instant.now().getEpochSecond());return json(mvc.perform(post("/api/payments/sepay/webhook").contentType("application/json").header("X-SePay-Timestamp",ts).header("X-SePay-Signature",sign(body,ts)).content(body)).andExpect(status().is(expected)).andReturn());}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"COD","SEPAY"})
    void committedCollectionsAndPartialRefundsImmediatelyRefreshDashboard(String method) throws Exception {
        dashboardCache.clearAll();
        assertEquals(0, dashboard.summary(30).totalOrders());
        long id=order(customer,method),p=payment(id);
        assertEquals(1, dashboard.summary(30).totalOrders());
        assertRevenue("0.00","0.00","0.00");
        if("COD".equals(method)) {
            confirm(p,admin,"DASH-COD-"+p,"",200);
            confirm(p,admin,"DASH-COD-"+p,"",200);
        } else {
            String body=event(99001,code(p),2050000,"0000000001","VietinBank");
            webhook(body,200);
            webhook(body,200);
        }
        assertRevenue("2050000.00","0.00","2050000.00");
        cancel(id);
        assertRevenue("2050000.00","0.00","2050000.00");
        long automatic=db.queryForObject("SELECT id FROM refunds WHERE order_id=?",Long.class,id);
        JsonNode held=refundSummary(id);
        assertEquals("2050000.00",held.path("pendingRefundAmount").asText());
        assertEquals("0.00",held.path("availableRefundAmount").asText());
        mvc.perform(post("/api/admin/refunds/"+automatic+"/cancel").with(user(admin)).with(csrf()).contentType("application/json")
                .content("{\"reason\":\"Hoàn một phần theo thỏa thuận\",\"acknowledged\":true}")).andExpect(status().isOk());
        JsonNode created=json(mvc.perform(post("/api/admin/orders/"+id+"/refunds").with(user(admin)).with(csrf())
                .header("Idempotency-Key","dashboard-partial-refund").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("paymentId",p,"amount","500000","reason","Hoàn một phần"))))
                .andExpect(status().isCreated()).andReturn());
        long partial=created.path("selectedRefundId").asLong();
        assertTrue(partial>0);
        assertEquals("500000.00",refundSummary(id).path("pendingRefundAmount").asText());
        assertEquals("1550000.00",refundSummary(id).path("availableRefundAmount").asText());
        assertRevenue("2050000.00","0.00","2050000.00");
        String confirmation=mapper.writeValueAsString(Map.of("reference","DASH-REF-"+partial,"note","","acknowledged",true));
        for(int i=0;i<2;i++)mvc.perform(post("/api/admin/refunds/"+partial+"/confirm").with(user(admin)).with(csrf())
                .contentType("application/json").content(confirmation)).andExpect(status().isOk());
        assertRevenue("2050000.00","500000.00","1550000.00");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payments WHERE status='SUCCEEDED'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM refunds WHERE status='SUCCEEDED'",Integer.class));
    }

    @Test
    void auditInvalidatesDashboardOnlyAfterTheDatabaseCommit() throws Exception {
        dashboardCache.clearAll();
        long id=order(customer,"COD"),p=payment(id);
        assertRevenue("0.00","0.00","0.00");
        long misses=dashboardCache.stats().misses();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{
            db.update("UPDATE payments SET status='SUCCEEDED',paid_at=UTC_TIMESTAMP(6) WHERE id=?",p);
            audit.appendSystem(id,"PAYMENT_CONFIRMED",Map.of(),Map.of("paymentId",p));
            assertRevenue("0.00","0.00","0.00");
            status.setRollbackOnly();
        });
        assertRevenue("0.00","0.00","0.00");
        assertEquals(misses,dashboardCache.stats().misses());
        assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));
        confirm(p,admin,"DASH-COMMIT-"+p,"",200);
        assertRevenue("2050000.00","0.00","2050000.00");
        assertEquals(misses+1,dashboardCache.stats().misses());
    }

    @Test
    void cacheEvictionFailureCannotMakeACommittedCollectionLookUnsuccessful() throws Exception {
        dashboardCache.clearAll();
        long id=order(customer,"COD"),p=payment(id);
        org.mockito.Mockito.doThrow(new IllegalStateException("test cache unavailable"))
                .when(dashboardCache).clearNamespace(org.example.eshop.commerce.cache.Tv5Cache.DASHBOARD);
        confirm(p,admin,"DASH-CACHE-FAIL-"+p,"",200);
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_CONFIRMED'",Integer.class));
    }

    void assertRevenue(String gross,String refunds,String net) {
        var revenue=dashboard.summary(30).revenue();
        assertEquals(gross,revenue.grossCollected());
        assertEquals(refunds,revenue.totalRefunded());
        assertEquals(net,revenue.netRevenue());
    }

    JsonNode refundSummary(long id) throws Exception {
        return json(mvc.perform(get("/api/admin/orders/"+id+"/refunds").with(user(admin))).andExpect(status().isOk()).andReturn());
    }
    @Test void checkoutSnapshotsUniqueCodeAndQrWithoutSecrets()throws Exception{
        long a=order(customer,"SEPAY"),b=order(other,"SEPAY"),p=payment(a);var s=summary(a,customer,200);assertTrue(code(p).matches("DH[0-9]{8}"));assertNotEquals(code(p),code(payment(b)));var qr=s.get("items").get(0).get("sepay");assertTrue(qr.get("qrUrl").asText().startsWith("/api/orders/"));assertEquals("SEVQR "+code(p),qr.get("transferContent").asText());assertEquals("SEPAY_SANDBOX",s.get("items").get(0).get("provider").asText());assertFalse(s.toString().contains("test-only-hmac"));assertEquals("PAYMENT_NOT_CONFIRMABLE",confirm(p,admin,"NO-MANUAL-SEPAY","",409).get("code").asText());summary(a,other,404);
    }
    @Test void exactSignedTransferCollectsOnceAndAppliesCoupon()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('SP10','PERCENT',10,'2020-01-01','2099-01-01')");long a=order(customer,"SEPAY",2,"SP10"),p=payment(a);String body=event(7001,code(p),1850000,"0000000001","VietinBank");webhook(body,200);webhook(body,200);assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(1,count("payment_events"));assertEquals(1,count("audit_logs"));assertEquals("APPLIED",db.queryForObject("SELECT status FROM coupon_redemptions WHERE order_id=?",String.class,a));assertEquals("0.00",summary(a,customer,200).get("remainingAmount").asText());assertNull(db.queryForObject("SELECT verified_by FROM payments WHERE id=?",Long.class,p));
    }
    @Test void forgedExpiredAndModifiedRequestsHaveNoSideEffects()throws Exception{
        mvc.perform(post("/api/payments/sepay/webhook").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());String ts="1000";mvc.perform(post("/api/payments/sepay/webhook").contentType("application/json").header("X-SePay-Timestamp",ts).header("X-SePay-Signature",sign("{}",ts)).content("{}")).andExpect(status().isUnauthorized());ts=Long.toString(java.time.Instant.now().getEpochSecond());mvc.perform(post("/api/payments/sepay/webhook").contentType("application/json").header("X-SePay-Timestamp",ts).header("X-SePay-Signature",sign("{}",ts)).content("{ }")).andExpect(status().isUnauthorized());assertEquals(0,count("payment_events"));
    }
    @Test void authenticatedMismatchesAreRetainedButNeverCollect()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);webhook(event(8001,code(p),1,"0000000001","VietinBank"),200);webhook(event(8002,code(p),2050000,"9999999999","VietinBank"),200);webhook(event(8003,code(p),2050000,"0000000001","ACB"),200);webhook(event(8004,"DH99999999",2050000,"0000000001","VietinBank"),200);assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(4,count("payment_events"));assertEquals(0,count("audit_logs"));
    }
    @Test void conflictingEventIdentityCannotChangeTheOriginalEvidence()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);webhook(event(9001,code(p),2050000,"0000000001","VietinBank"),200);webhook(event(9001,code(p),100000,"0000000001","VietinBank"),409);assertEquals(1,count("payment_events"));assertEquals(1,count("audit_logs"));
    }
    @Test void lateTransferQueuesOneRefundWithoutRevivingOrder()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);cancel(a);webhook(event(10001,code(p),2050000,"0000000001","VietinBank"),200);webhook(event(10001,code(p),2050000,"0000000001","VietinBank"),200);assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(1,count("refunds"));assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds",String.class));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    @Test void differentTransfersToOneAttemptDoNotDoubleCollect()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);webhook(event(11001,code(p),2050000,"0000000001","VietinBank"),200);webhook(event(11002,code(p),2050000,"0000000001","VietinBank"),200);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payments WHERE status='SUCCEEDED'",Integer.class));assertEquals(1,count("audit_logs"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_events WHERE processing_status='FAILED'",Integer.class));
    }

    @Test void duplicateConcurrentWebhooksProduceOneCollection()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);String body=event(12001,code(p),2050000,"0000000001","VietinBank");race(()->webhook(body,200),()->webhook(body,200));assertEquals(1,count("payment_events"));assertEquals(1,count("audit_logs"));
    }
    void race(Callable<?> a,Callable<?> b)throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var x=pool.submit(()->{start.await();return a.call();});var y=pool.submit(()->{start.await();return b.call();});start.countDown();x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
    @Test void cancellationRacingWithWebhookAlwaysLeavesOneRefundAndNoStockHold()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);race(()->webhook(event(13001,code(p),2050000,"0000000001","VietinBank"),200),()->{cancel(a);return null;});assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));assertEquals(1,count("refunds"));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    @Test void auditFailureRollsBackEventAndCollectionSoDeliveryCanRetry()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);String body=event(14001,code(p),2050000,"0000000001","VietinBank");db.execute("CREATE TRIGGER tv3_payment_audit_fail BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced audit failure'");try{assertThrows(Exception.class,()->webhook(body,200));}finally{db.execute("DROP TRIGGER tv3_payment_audit_fail");}assertEquals(0,count("payment_events"));assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));webhook(body,200);assertEquals(1,count("audit_logs"));
    }
    @Test void authenticatedMalformedAndAmbiguousCodesNeverCollect()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);webhook("{broken",400);String body=event(15001,code(p),2050000,"0000000001","VietinBank").replace(" thanh toan"," DH87654321 thanh toan");webhook(body,200);assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(0,count("audit_logs"));
    }
    @Test void apiReconciliationAndWebhookUseOneBankReferenceAcrossDifferentProviderIds()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);String body=event(16001,code(p),2050000,"0000000001","VietinBank");var t=org.example.eshop.commerce.payment.SePayTransfer.webhook(mapper.readTree(body));String uuid="a1b2c3d4-e5f6-7890-abcd-ef1234567890";var api=new org.example.eshop.commerce.payment.SePayTransfer("api:"+uuid,t.bank(),t.account(),t.subAccount(),t.code(),t.content(),t.direction(),t.amount(),t.reference(),t.transactionDate());org.mockito.Mockito.when(provider.transaction(uuid)).thenReturn(api);
        mvc.perform(post("/api/admin/payments/"+p+"/sepay/reconcile").with(user(admin)).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/payments/"+p+"/sepay/reconcile").with(user(customer)).with(csrf()).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        String request=mapper.writeValueAsString(Map.of("transactionId",uuid));mvc.perform(post("/api/admin/payments/"+p+"/sepay/reconcile").with(user(admin)).with(csrf()).contentType("application/json").content(request)).andExpect(status().isOk());webhook(body,200);assertEquals(1,count("audit_logs"));assertEquals(2,count("payment_events"));assertEquals("0.00",summary(a,customer,200).get("remainingAmount").asText());
    }
    @Test void staffCanReviewUnknownCodesButCustomerCannotReadProviderEvents()throws Exception{
        webhook(event(17001,"DH99999999",100000,"0000000001","VietinBank"),200);var list=json(mvc.perform(get("/api/admin/payments/sepay/events?status=IGNORED").with(user(staff))).andExpect(status().isOk()).andReturn());assertEquals(1,list.get("items").size());mvc.perform(get("/api/admin/payments/sepay/events").with(user(customer))).andExpect(status().isForbidden());
    }

    @Test void missingReferenceIsAcknowledgedAndRetainedForReview()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);String body=event(18001,code(p),2050000,"0000000001","VietinBank").replace("FT-TEST-18001","");var ack=webhook(body,200);assertEquals(mapper.readTree("{\"success\":true}"),ack);assertEquals("FAILED",db.queryForObject("SELECT processing_status FROM payment_events",String.class));assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));
    }
    @Test void missingSnapshotPhraseAndReusedBankReferenceNeverSilentlySettle()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);webhook(event(19001,code(p),2050000,"0000000001","VietinBank").replace("SEVQR ",""),200);assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));webhook(event(19002,code(p),2050000,"0000000001","VietinBank"),200);webhook(event(19003,code(p),2050000,"0000000001","VietinBank").replace("FT-TEST-19003","FT-TEST-19002"),200);assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_events WHERE processing_status='FAILED'",Integer.class));assertEquals(1,count("audit_logs"));
    }
    @Test void paymentSummaryRemainsConsistentWhenWebhookCommitsBetweenQueries()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);var switched=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation->{if(switched.compareAndSet(false,true)){var pool=Executors.newSingleThreadExecutor();try{pool.submit(()->db.update("UPDATE payments SET status='SUCCEEDED' WHERE id=?",p)).get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}return invocation.callRealMethod();}).when(storeSpy).rows(org.mockito.ArgumentMatchers.startsWith("SELECT p.sepay_code"),org.mockito.ArgumentMatchers.eq(a));
        var s=summary(a,customer,200);assertEquals("PENDING",s.get("items").get(0).get("status").asText());assertEquals("2050000.00",s.get("remainingAmount").asText());
    }

    @Test void sameBankReferenceWithDifferentWebhookIdRequiresReview()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);webhook(event(20001,code(p),2050000,"0000000001","VietinBank"),200);webhook(event(20002,code(p),2050000,"0000000001","VietinBank").replace("FT-TEST-20002","FT-TEST-20001"),200);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_events WHERE processing_status='FAILED'",Integer.class));assertEquals(1,count("audit_logs"));
    }

    @Test void qrIsServedLocallyForOwnerOnlyAndDisappearsAfterPayment()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);String url="/api/orders/"+a+"/payments/"+p+"/sepay/qr";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());mvc.perform(get(url).with(user(other))).andExpect(status().isNotFound());
        byte[] png=mvc.perform(get(url).with(user(customer))).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsByteArray();assertTrue(png.length>500);assertEquals((byte)0x89,png[0]);
        webhook(event(21001,code(p),2050000,"0000000001","VietinBank"),200);mvc.perform(get(url).with(user(customer))).andExpect(status().isConflict());
    }
    @Test void newAttemptsHaveDeadlineButVerifiedApiAbsenceIsRequiredForExpiry()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);assertNotNull(db.queryForObject("SELECT expires_at FROM payments WHERE id=?",java.sql.Timestamp.class,p));
        db.update("UPDATE payments SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE id=?",p);
        org.mockito.Mockito.when(provider.searchCode(code(p))).thenReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),false));
        assertFalse((Boolean)automation.reconcile(p).get("expired"));assertEquals("PENDING_CONFIRMATION",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));
        org.mockito.Mockito.when(provider.searchCode(code(p))).thenReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true));
        assertTrue((Boolean)automation.reconcile(p).get("expired"));assertEquals("EXPIRED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
        webhook(event(22001,code(p),2050000,"0000000001","VietinBank"),200);assertEquals(1,count("refunds"));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));
    }
    @Test void apiFailureAndSuspiciousTransferLeaveStockAndOrderHeld()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);db.update("UPDATE payments SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE id=?",p);
        org.mockito.Mockito.when(provider.searchCode(code(p))).thenThrow(org.example.eshop.commerce.common.CommerceException.conflict("SEPAY_API_UNAVAILABLE","Unavailable"));assertThrows(Exception.class,()->automation.reconcile(p));
        var t=org.example.eshop.commerce.payment.SePayTransfer.webhook(mapper.readTree(event(23001,code(p),1,"0000000001","VietinBank")));
        org.mockito.Mockito.doReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(t),true)).when(provider).searchCode(code(p));assertFalse((Boolean)automation.reconcile(p).get("expired"));assertEquals("PENDING_CONFIRMATION",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));assertEquals(1,count("payment_events"));
    }
    @Test void verifiedEmptySearchCannotCancelConfirmedOrLegacyNullExpiryOrders()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);db.update("UPDATE payments SET expires_at=NULL WHERE id=?",p);org.mockito.Mockito.when(provider.searchCode(code(p))).thenReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true));assertFalse((Boolean)automation.reconcile(p).get("expired"));
        db.update("UPDATE payments SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE id=?",p);db.update("UPDATE orders SET status='CONFIRMED',confirmed_at=UTC_TIMESTAMP() WHERE id=?",a);assertFalse((Boolean)automation.reconcile(p).get("expired"));assertEquals("CONFIRMED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));
    }
    @Test void staffReviewIsImmutableIdempotentAndCannotChangeMoney()throws Exception{
        webhook(event(24001,"DH99999999",1000,"0000000001","VietinBank"),200);long e=db.queryForObject("SELECT id FROM payment_events",Long.class);String path="/api/admin/payments/sepay/events/"+e+"/review";String body="{\"note\":\"Đã kiểm tra, sai mã đơn\",\"expectedReviewStatus\":\"OPEN\"}";
        mvc.perform(post(path).with(user(customer)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        for(int n=0;n<2;n++)mvc.perform(post(path).with(user(staff)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post(path).with(user(staff)).with(csrf()).contentType("application/json").content(body.replace("sai mã đơn","khác"))).andExpect(status().isConflict());
        assertEquals("IGNORED",db.queryForObject("SELECT processing_status FROM payment_events",String.class));assertEquals(0,count("payments"));assertEquals(104L,db.queryForObject("SELECT reviewed_by FROM payment_events",Long.class));
        var reviewed=json(mvc.perform(get("/api/admin/payments/sepay/events?status=IGNORED&reviewStatus=REVIEWED").with(user(staff))).andExpect(status().isOk()).andReturn());assertEquals(1,reviewed.path("items").size());
    }
    @Test void expiredUnpaidOrderReleasesCouponButPaidCallbackRaceNeverLosesCollection()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('SPTTL','PERCENT',10,'2020-01-01','2099-01-01')");long a=order(customer,"SEPAY",2,"SPTTL"),p=payment(a);db.update("UPDATE payments SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE id=?",p);
        org.mockito.Mockito.when(provider.searchCode(code(p))).thenReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true));
        race(()->automation.reconcile(p),()->webhook(event(25001,code(p),1850000,"0000000001","VietinBank"),200));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));String state=db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a);
        assertEquals("CANCELLED".equals(state)?1:0,count("refunds"));assertEquals("CANCELLED".equals(state)?"RELEASED":"APPLIED",db.queryForObject("SELECT status FROM coupon_redemptions WHERE order_id=?",String.class,a));
    }
    @Test void reconciliationLeasePreventsConcurrentApiCallsAndFailuresPersistBackoff()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        org.mockito.Mockito.when(provider.searchCode(code(p))).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true);});
        var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->automation.reconcile(p));assertTrue(entered.await(5,TimeUnit.SECONDS));var error=assertThrows(org.example.eshop.commerce.common.CommerceException.class,()->automation.reconcile(p));assertEquals("SEPAY_CHECK_BUSY",error.getCode());release.countDown();first.get(10,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();}
        org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(1)).searchCode(code(p));
        org.mockito.Mockito.when(provider.searchCode(code(p))).thenThrow(org.example.eshop.commerce.common.CommerceException.conflict("SEPAY_API_UNAVAILABLE","Unavailable"));assertThrows(Exception.class,()->automation.reconcile(p));assertEquals(1,db.queryForObject("SELECT sepay_check_attempts FROM payments WHERE id=?",Integer.class,p));assertNull(db.queryForObject("SELECT sepay_lease_token FROM payments WHERE id=?",String.class,p));assertTrue(db.queryForObject("SELECT sepay_next_check_at>UTC_TIMESTAMP()+INTERVAL 60 SECOND FROM payments WHERE id=?",Boolean.class,p));
    }
    @Test void jobAndCodeRoutesRequireStaffAndCsrf()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);mvc.perform(get("/api/admin/payments/sepay/jobs").with(user(customer))).andExpect(status().isForbidden());mvc.perform(post("/api/admin/payments/sepay/jobs/run").with(user(staff))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/sepay/jobs").with(user(staff))).andExpect(status().isOk());org.mockito.Mockito.when(provider.searchCode(code(p))).thenReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true));mvc.perform(post("/api/admin/payments/"+p+"/sepay/reconcile-code").with(user(staff)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isOk());
    }
    @Test void exactApiEventReplayCanEnrichMissingRefundUuidsWithoutCollectingAgain()throws Exception{
        long a=order(customer,"SEPAY"),p=payment(a);String id="a1b2c3d4-e5f6-7890-abcd-ef1234567890",bank="b1b2c3d4-e5f6-7890-abcd-ef1234567890";var source=org.example.eshop.commerce.payment.SePayTransfer.webhook(mapper.readTree(event(26001,code(p),2050000,"0000000001","VietinBank")));
        var processor=context.getBean(org.example.eshop.commerce.payment.SePayEventService.class);
        processor.process(new org.example.eshop.commerce.payment.SePayTransfer("api:"+id,source.bank(),source.account(),source.subAccount(),source.code(),source.content(),source.direction(),source.amount(),source.reference(),source.transactionDate(),id,null),p);
        assertNull(db.queryForObject("SELECT sepay_bank_account_id FROM payments WHERE id=?",String.class,p));
        processor.process(new org.example.eshop.commerce.payment.SePayTransfer("api:"+id,source.bank(),source.account(),source.subAccount(),source.code(),source.content(),source.direction(),source.amount(),source.reference(),source.transactionDate(),id,bank),p);
        assertEquals(bank,db.queryForObject("SELECT sepay_bank_account_id FROM payments WHERE id=?",String.class,p));assertEquals(id,db.queryForObject("SELECT sepay_transaction_id FROM payments WHERE id=?",String.class,p));assertEquals(1,count("audit_logs"));assertEquals(1,count("payment_events"));
    }
    @Test void workerBoundsCallsAndRecoversRecentCancelledAndMissingMetadataOnly()throws Exception{
        var ids=new ArrayList<Long>();for(int n=0;n<10;n++)ids.add(order(customer,"SEPAY"));
        long recent=ids.get(5),old=ids.get(6),missing=ids.get(7),complete=ids.get(8),leased=ids.get(9);cancel(recent);cancel(old);db.update("UPDATE orders SET cancelled_at=UTC_TIMESTAMP()-INTERVAL 100 HOUR WHERE id=?",old);
        db.update("UPDATE payments SET status='SUCCEEDED' WHERE order_id IN (?,?)",missing,complete);db.update("UPDATE payments SET sepay_transaction_id='a1b2c3d4-e5f6-7890-abcd-ef1234567890',sepay_bank_account_id='b1b2c3d4-e5f6-7890-abcd-ef1234567890' WHERE order_id=?",complete);
        db.update("UPDATE payments SET sepay_next_check_at=UTC_TIMESTAMP()-INTERVAL 1 HOUR");db.update("UPDATE payments SET sepay_lease_until=UTC_TIMESTAMP()+INTERVAL 5 MINUTE WHERE order_id=?",leased);
        org.mockito.Mockito.when(provider.searchCode(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true);});
        assertEquals(5,automation.runBatch().get("scanned"));assertEquals(2,automation.runBatch().get("scanned"));org.mockito.Mockito.verify(provider).searchCode(code(payment(recent)));org.mockito.Mockito.verify(provider).searchCode(code(payment(missing)));
        for(long skipped:List.of(old,complete,leased))org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).searchCode(code(payment(skipped)));
        org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(7)).searchCode(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void dispatchMixedAndMultipleAttemptsAndReviewedMismatchEachPreventExpiry()throws Exception{
        for(String guard:List.of("dispatch","mixed","multiple","reviewed")){
            long a=order(customer,"SEPAY"),p=payment(a);db.update("UPDATE payments SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE id=?",p);
            if("dispatch".equals(guard))db.update("UPDATE inventory_reservations SET fulfilled_quantity=1 WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?)",a);
            if(Set.of("mixed","multiple").contains(guard))db.update("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,status,expires_at) VALUES(?, 'BANK_TRANSFER',?,?,1000,'PENDING',UTC_TIMESTAMP()-INTERVAL 1 MINUTE)",a,"mixed".equals(guard)?"MANUAL_BANK":"SEPAY_SANDBOX",UUID.randomUUID().toString());
            if("reviewed".equals(guard)){webhook(event(27001,code(p),1,"0000000001","VietinBank"),200);db.update("UPDATE payment_events SET reviewed_by=104,reviewed_at=UTC_TIMESTAMP(),review_note='Đã kiểm tra' WHERE payment_id=?",p);}
            org.mockito.Mockito.when(provider.searchCode(code(p))).thenReturn(new org.example.eshop.commerce.payment.SePayApiClient.SearchResult(List.of(),true));assertFalse((Boolean)automation.reconcile(p).get("expired"),guard);assertEquals("PENDING_CONFIRMATION",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,a));
        }
    }
}
