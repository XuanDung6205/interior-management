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
@SpringBootTest(classes=CommerceApplication.class,properties="commerce.payments.bank.mode=DEMO")
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class PaymentConfirmationTest {
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
    @Test void paymentEndpointsRequireAuthenticationOwnershipStaffAndCsrf()throws Exception{
        long id=order(customer,"COD"),p=payment(id);mvc.perform(get("/api/orders/"+id+"/payments")).andExpect(status().isUnauthorized());summary(id,other,404);mvc.perform(get("/api/admin/payments")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/payments").with(user(customer))).andExpect(status().isForbidden());
        confirm(p,customer,"RECEIPT-01","",403);mvc.perform(post("/api/admin/payments/"+p+"/confirm").with(user(admin)).contentType("application/json").content("{}")).andExpect(status().isForbidden());assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));
    }
    @Test void ownerSeesServerAmountsPendingHistoryAndClearlyFakeBank()throws Exception{
        long id=order(customer,"BANK_TRANSFER");var s=summary(id,customer,200);assertEquals("2050000.00",s.get("remainingAmount").asText());assertEquals("PENDING",s.get("items").get(0).get("status").asText());assertFalse(s.get("canCreate").asBoolean());assertTrue(s.get("bankTransfer").get("isDemo").asBoolean());assertEquals("DEMO-KHONG-CHUYEN-TIEN",s.get("bankTransfer").get("accountNumber").asText());assertFalse(s.get("items").get(0).has("confirmationNote"));
    }
    @Test void codConfirmationIsServerOwnedAuditedAndReplaySafe()throws Exception{
        long id=order(customer,"COD"),p=payment(id);var s=confirm(p,staff,"COD-RECEIPT-001","Đã đối chiếu biên nhận",200);var first=db.queryForMap("SELECT amount,paid_at,verified_by FROM payments WHERE id=?",p);confirm(p,admin,"COD-RECEIPT-001","Đã đối chiếu biên nhận",200);assertEquals(first,db.queryForMap("SELECT amount,paid_at,verified_by FROM payments WHERE id=?",p));assertEquals(104L,((Number)first.get("verified_by")).longValue());assertEquals("0.00",s.get("remainingAmount").asText());assertEquals(1,count("audit_logs"));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));
        var own=summary(id,customer,200);assertFalse(own.get("items").get(0).has("confirmationNote"));assertFalse(own.toString().contains("Đã đối chiếu biên nhận"));assertEquals("PAYMENT_ALREADY_CONFIRMED",confirm(p,admin,"OTHER-RECEIPT","",409).get("code").asText());
    }
    @Test void confirmationRequiresProofAndExplicitAttestation()throws Exception{
        long id=order(customer,"COD"),p=payment(id);for(String body:List.of("{\"reference\":\"SHORT\",\"acknowledged\":true}","{\"reference\":\"RECEIPT-123\",\"acknowledged\":false}","{\"reference\":\"RECEIPT-123\"}"))mvc.perform(post("/api/admin/payments/"+p+"/confirm").with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(0,count("audit_logs"));
    }
    @Test void bankConfirmationReferenceCannotBeAllocatedTwice()throws Exception{
        long first=order(customer,"BANK_TRANSFER"),second=order(other,"BANK_TRANSFER");confirm(payment(first),admin,"BANK-STATEMENT-001","",200);assertEquals("PAYMENT_REFERENCE_IN_USE",confirm(payment(second),staff,"BANK-STATEMENT-001","",409).get("code").asText());assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment(second)));
    }
    @Test void createRejectsExistingPendingThenRetryUsesBalanceAndStableKey()throws Exception{
        long id=order(customer,"COD");assertEquals("PAYMENT_PENDING_EXISTS",create(id,"BANK_TRANSFER","new-method-key",409).get("code").asText());db.update("UPDATE payments SET status='FAILED' WHERE order_id=?",id);
        var first=create(id,"BANK_TRANSFER","retry-payment-key",201);long p=payment(id);assertEquals("2050000.00",first.get("items").get(first.get("items").size()-1).get("amount").asText());create(id,"BANK_TRANSFER","retry-payment-key",201);assertEquals(2,count("payments"));assertEquals(p,payment(id));assertEquals("IDEMPOTENCY_CONFLICT",create(id,"COD","retry-payment-key",409).get("code").asText());
        confirm(p,admin,"BANK-RETRY-001","",200);create(id,"BANK_TRANSFER","retry-payment-key",201);assertEquals(2,count("payments"));
    }
    @Test void zeroTotalOrdersNeverCreateZeroPayments()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('FREE100','PERCENT',100,'2020-01-01','2099-01-01')");long id=order(customer,"COD",10,"FREE100");assertEquals(0,count("payments"));assertEquals("PAYMENT_NOT_REQUIRED",create(id,"COD","zero-payment-key",409).get("code").asText());assertEquals(0,count("payments"));
    }
    @Test void cancelledOrdersCannotCreateButLateReceiptQueuesRefundWithoutResurrection()throws Exception{
        long id=order(customer,"BANK_TRANSFER"),p=payment(id);cancel(id);assertEquals("ORDER_NOT_PAYABLE",create(id,"COD","cancelled-payment",409).get("code").asText());confirm(p,admin,"LATE-BANK-001","Khoản thu đến muộn",200);confirm(p,staff,"LATE-BANK-001","Khoản thu đến muộn",200);
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(1,count("refunds"));assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds",String.class));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals("0.00",summary(id,customer,200).get("remainingAmount").asText());
    }
    @Test void expiredAndGatewayPaymentsAreNotManualConfirmable()throws Exception{
        long id=order(customer,"COD"),p=payment(id);db.update("UPDATE payments SET expires_at='2020-01-01' WHERE id=?",p);assertTrue(summary(id,customer,200).get("items").get(0).get("isExpired").asBoolean());assertEquals("PAYMENT_EXPIRED",confirm(p,admin,"EXPIRED-PROOF","",409).get("code").asText());db.update("UPDATE payments SET expires_at=NULL,method='GATEWAY',provider='FUTURE_GATEWAY' WHERE id=?",p);assertEquals("PAYMENT_NOT_CONFIRMABLE",confirm(p,admin,"GATEWAY-PROOF","",409).get("code").asText());
    }
    @Test void twoStaffConfirmingOnePaymentRecordOneCollection()throws Exception{
        long id=order(customer,"COD"),p=payment(id);race(()->confirm(p,admin,"SAME-COLLECTION","",200),()->confirm(p,staff,"SAME-COLLECTION","",200));assertEquals(1,count("audit_logs"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payments WHERE status='SUCCEEDED'",Integer.class));
    }
    @Test void competingAttemptsCannotCollectAboveOriginalTotal()throws Exception{
        long id=order(customer,"COD"),first=payment(id);db.update("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,currency,status) SELECT id,'COD','COD','legacy-second-attempt',grand_total,currency,'PENDING' FROM orders WHERE id=?",id);long second=payment(id);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->confirmStatus(start,first,admin,"FIRST-RECEIPT"));var b=pool.submit(()->confirmStatus(start,second,staff,"SECOND-RECEIPT"));start.countDown();var statuses=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payments WHERE status='SUCCEEDED'",Integer.class));assertEquals(1,count("audit_logs"));}finally{pool.shutdownNow();}
    }
    int confirmStatus(CountDownLatch start,long id,CommercePrincipal actor,String ref)throws Exception{start.await();return mvc.perform(post("/api/admin/payments/"+id+"/confirm").with(user(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("reference",ref,"acknowledged",true)))).andReturn().getResponse().getStatus();}
    @Test void cancellationAndConfirmationRaceAlwaysKeepsMoneyAndOneRefund()throws Exception{
        long id=order(customer,"COD"),p=payment(id);race(()->confirm(p,admin,"RACING-COLLECTION","",200),()->{cancel(id);return null;});assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals(1,count("refunds"));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    void race(Callable<?> first,Callable<?> second)throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();return first.call();});var b=pool.submit(()->{start.await();return second.call();});start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
    @Test void failedAuditRollsBackReceiptVerifierCouponAndPaymentState()throws Exception{
        long id=order(customer,"COD"),p=payment(id);db.execute("CREATE TRIGGER tv3_payment_audit_fail BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced verification audit failure'");try{assertThrows(Exception.class,()->confirm(p,admin,"ROLLBACK-RECEIPT","",200));}finally{db.execute("DROP TRIGGER tv3_payment_audit_fail");}assertEquals("PENDING",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertNull(db.queryForObject("SELECT verified_by FROM payments WHERE id=?",Long.class,p));assertNull(db.queryForObject("SELECT provider_transaction_id FROM payments WHERE id=?",String.class,p));assertEquals(0,count("audit_logs"));
    }
    @Test void staffListSupportsLiteralSearchFiltersAndPagination()throws Exception{
        long id=order(customer,"COD");order(other,"BANK_TRANSFER");String number=db.queryForObject("SELECT order_number FROM orders WHERE id=?",String.class,id);var list=json(mvc.perform(get("/api/admin/payments").with(user(staff)).param("query",number).param("size","1").param("method","COD").param("status","PENDING")).andExpect(status().isOk()).andReturn());assertEquals(1,list.get("totalElements").asInt());var literal=json(mvc.perform(get("/api/admin/payments").with(user(admin)).param("query","%")).andExpect(status().isOk()).andReturn());assertEquals(0,literal.get("totalElements").asInt());mvc.perform(get("/api/admin/payments?size=101").with(user(admin))).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/payments?status=BAD").with(user(admin))).andExpect(status().isBadRequest());
    }
    @Test void actualDeliveredOrderCompletesOnlyAfterManualReceiptAndReplaysOnce()throws Exception{
        long id=order(customer,"COD"),p=payment(id);mvc.perform(post("/api/admin/orders/"+id+"/transitions").with(user(admin)).with(csrf()).contentType("application/json").content("{\"fromStatus\":\"PENDING_CONFIRMATION\",\"toStatus\":\"CONFIRMED\"}")).andExpect(status().isOk());long warehouse=db.queryForObject("SELECT id FROM warehouses WHERE code='MAIN'",Long.class);
        var dispatch=json(mvc.perform(post("/api/admin/inventory/orders/"+id+"/dispatch").with(user(admin)).with(csrf()).header("Idempotency-Key","payment-test-dispatch").contentType("application/json").content(mapper.writeValueAsString(Map.of("warehouseId",warehouse,"carrier","Test","trackingNumber",UUID.randomUUID().toString())))).andExpect(status().isOk()).andReturn());long shipment=dispatch.get("shipmentId").asLong();mvc.perform(post("/api/admin/inventory/shipments/"+shipment+"/status").with(user(admin)).with(csrf()).header("Idempotency-Key","payment-test-delivery").contentType("application/json").content("{\"targetStatus\":\"DELIVERED\",\"reason\":\"Đã giao\"}")).andExpect(status().isOk());assertEquals("PROCESSING",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));
        confirm(p,admin,"DELIVERED-COD-001","",200);confirm(p,staff,"DELIVERED-COD-001","",200);assertEquals("COMPLETED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND to_status='COMPLETED'",Integer.class,id));
    }
    @Test void failedBankHistoryDoesNotBlockVerifiedRetryDispatch()throws Exception{
        long id=order(customer,"BANK_TRANSFER");db.update("UPDATE payments SET status='FAILED' WHERE order_id=?",id);create(id,"BANK_TRANSFER","verified-retry-dispatch",201);confirm(payment(id),admin,"RETRY-DISPATCH-001","",200);dispatch(id,200);
    }
    @Test void failedBankCannotDispatchUntilReplacedWithLiveCodAttempt()throws Exception{
        long id=order(customer,"BANK_TRANSFER");db.update("UPDATE payments SET status='EXPIRED' WHERE order_id=?",id);dispatch(id,409);create(id,"COD","cod-retry-dispatch",201);dispatch(id,200);
    }
    void dispatch(long id,int status)throws Exception{
        mvc.perform(post("/api/admin/orders/"+id+"/transitions").with(user(admin)).with(csrf()).contentType("application/json").content("{\"fromStatus\":\"PENDING_CONFIRMATION\",\"toStatus\":\"CONFIRMED\"}")).andExpect(status().isOk());long warehouse=db.queryForObject("SELECT id FROM warehouses WHERE code='MAIN'",Long.class);
        mvc.perform(post("/api/admin/inventory/orders/"+id+"/dispatch").with(user(admin)).with(csrf()).header("Idempotency-Key","retry-dispatch-"+id).contentType("application/json").content(mapper.writeValueAsString(Map.of("warehouseId",warehouse,"carrier","Test","trackingNumber","PAY-"+id)))).andExpect(status().is(status));
    }
    @Test void fullReceiptAppliesCouponButRefundNeverReopensReceivable()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('PAY10','PERCENT',10,'2020-01-01','2099-01-01')");long id=order(customer,"COD",2,"PAY10"),p=payment(id);confirm(p,admin,"COUPON-RECEIPT-001","",200);assertEquals("APPLIED",db.queryForObject("SELECT status FROM coupon_redemptions WHERE order_id=?",String.class,id));
        db.update("INSERT INTO refunds(order_id,payment_id,provider,idempotency_key,amount,currency,status,reason) SELECT order_id,id,provider,'manual-refund-test',amount,currency,'SUCCEEDED','test' FROM payments WHERE id=?",p);var s=summary(id,customer,200);assertEquals("0.00",s.get("remainingAmount").asText());assertEquals("0.00",s.get("netCollectedAmount").asText());assertFalse(s.get("canCreate").asBoolean());assertEquals("REFUND_RECONCILIATION_REQUIRED",create(id,"COD","after-refund-attempt",409).get("code").asText());
    }
}
