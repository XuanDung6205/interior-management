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
class ManualRefundTest {
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
    long funded()throws Exception{long id=order(customer,"COD");confirm(payment(id),admin,"COLLECT-"+id,"",200);return id;}
    long cancelled()throws Exception{long id=funded();cancel(id);return id;}
    long refund(long order){return db.queryForObject("SELECT id FROM refunds WHERE order_id=? ORDER BY id DESC LIMIT 1",Long.class,order);}
    JsonNode refunds(long order)throws Exception{return json(mvc.perform(get("/api/admin/orders/"+order+"/refunds").with(user(admin))).andExpect(status().isOk()).andReturn());}
    JsonNode requestRefund(long order,long payment,String amount,String reason,Long returned,String key,int status)throws Exception{var body=new LinkedHashMap<String,Object>();body.put("paymentId",payment);body.put("amount",amount);body.put("reason",reason);body.put("returnRequestId",returned);return json(mvc.perform(post("/api/admin/orders/"+order+"/refunds").with(user(admin)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().is(status)).andReturn());}
    JsonNode settle(long id,CommercePrincipal actor,String ref,String note,int status)throws Exception{return json(mvc.perform(post("/api/admin/refunds/"+id+"/confirm").with(user(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("reference",ref,"note",note,"acknowledged",true)))).andExpect(status().is(status)).andReturn());}
    JsonNode voidRefund(long id,String reason,int status)throws Exception{return json(mvc.perform(post("/api/admin/refunds/"+id+"/cancel").with(user(admin)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("reason",reason,"acknowledged",true)))).andExpect(status().is(status)).andReturn());}
    @Test void staffAuthenticationCsrfAndFilterValidation()throws Exception{
        mvc.perform(get("/api/admin/refunds")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/refunds").with(user(customer))).andExpect(status().isForbidden());long id=cancelled();mvc.perform(get("/api/admin/orders/"+id+"/refunds").with(user(customer))).andExpect(status().isForbidden());mvc.perform(post("/api/admin/refunds/"+refund(id)+"/confirm").with(user(admin)).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(get("/api/admin/refunds?size=101").with(user(admin))).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/refunds?status=BAD").with(user(admin))).andExpect(status().isBadRequest());
    }
    @Test void cancellationGeneratedRefundAppearsWithReservedBudgetAndLiteralQueue()throws Exception{
        long id=cancelled();var s=refunds(id);assertEquals("2050000.00",s.get("pendingRefundAmount").asText());assertEquals("0.00",s.get("availableRefundAmount").asText());assertFalse(s.get("canCreate").asBoolean());assertTrue(s.get("items").get(0).get("canConfirm").asBoolean());var rows=json(mvc.perform(get("/api/admin/refunds").with(user(staff)).param("status","PENDING")).andExpect(status().isOk()).andReturn());assertEquals(1,rows.get("totalElements").asInt());assertEquals(id,rows.get("items").get(0).get("orderId").asLong());var literal=json(mvc.perform(get("/api/admin/refunds?query=%25").with(user(admin))).andExpect(status().isOk()).andReturn());assertEquals(0,literal.get("totalElements").asInt());
    }
    @Test void manualProofReplayPreservesFirstVerifierAndOriginalCollectionWithoutLeakingNotes()throws Exception{
        long id=cancelled(),r=refund(id),p=payment(id);int audits=count("audit_logs");settle(r,staff,"REFUND-PROOF-001","Ghi chú riêng hoàn tiền",200);var first=db.queryForMap("SELECT processed_by,completed_at,provider_refund_id FROM refunds WHERE id=?",r);settle(r,admin,"REFUND-PROOF-001","Ghi chú riêng hoàn tiền",200);assertEquals(first,db.queryForMap("SELECT processed_by,completed_at,provider_refund_id FROM refunds WHERE id=?",r));assertEquals(104L,((Number)first.get("processed_by")).longValue());assertEquals(audits+1,count("audit_logs"));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals("2050000.00",refunds(id).get("refundedAmount").asText());assertEquals("REFUND_ALREADY_CONFIRMED",settle(r,admin,"OTHER-PROOF-001","",409).get("code").asText());assertEquals("REFUND_ALREADY_RESOLVED",voidRefund(r,"Không được hủy khoản đã hoàn",409).get("code").asText());
        var publicOrder=json(mvc.perform(get("/api/orders/"+id).with(user(customer))).andExpect(status().isOk()).andReturn());assertFalse(publicOrder.toString().contains("Ghi chú riêng hoàn tiền"));assertFalse(publicOrder.get("refunds").get(0).has("confirmationNote"));assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    @Test void cancellingUnsentRefundReleasesBudgetAndExactReplayIsStable()throws Exception{
        long id=cancelled(),r=refund(id);int audits=count("audit_logs");voidRefund(r,"Chưa chuyển tiền, lập lại từng phần",200);var before=db.queryForMap("SELECT status,updated_at,processed_by FROM refunds WHERE id=?",r);voidRefund(r,"Chưa chuyển tiền, lập lại từng phần",200);assertEquals(before,db.queryForMap("SELECT status,updated_at,processed_by FROM refunds WHERE id=?",r));assertEquals(audits+1,count("audit_logs"));assertEquals("2050000.00",refunds(id).get("availableRefundAmount").asText());assertEquals("REFUND_ALREADY_RESOLVED",voidRefund(r,"Lý do khác",409).get("code").asText());
    }
    @Test void partialRequestReservesBudgetAndReplaysBeforeStateChecks()throws Exception{
        long id=cancelled(),p=payment(id);voidRefund(refund(id),"Thay bằng yêu cầu từng phần",200);var s=requestRefund(id,p,"1000000","Hoàn một phần",null,"refund-create-key",201);long r=refund(id);assertEquals("1050000.00",s.get("availableRefundAmount").asText());requestRefund(id,p,"1000000.00","Hoàn một phần",null,"refund-create-key",201);assertEquals(2,count("refunds"));assertEquals("IDEMPOTENCY_CONFLICT",requestRefund(id,p,"1000000","Lý do khác",null,"refund-create-key",409).get("code").asText());settle(r,admin,"PARTIAL-REFUND-001","",200);requestRefund(id,p,"1000000","Hoàn một phần",null,"refund-create-key",201);assertEquals("REFUND_EXCEEDS_BALANCE",requestRefund(id,p,"1100000","Vượt tiền còn lại",null,"refund-too-much",409).get("code").asText());
    }
    @Test void twoConcurrentSixtyPercentRequestsCannotOverReserve()throws Exception{
        long id=cancelled(),p=payment(id);voidRefund(refund(id),"Tạo lại yêu cầu",200);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->requestStatus(start,id,p,"race-refund-one"));var b=pool.submit(()->requestStatus(start,id,p,"race-refund-two"));start.countDown();var statuses=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(201,409),statuses);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM refunds WHERE status='PENDING'",Integer.class));}finally{pool.shutdownNow();}
    }
    int requestStatus(CountDownLatch start,long id,long p,String key)throws Exception{start.await();return mvc.perform(post("/api/admin/orders/"+id+"/refunds").with(user(admin)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("paymentId",p,"amount","1230000","reason","Tranh chấp hoàn tiền")))).andReturn().getResponse().getStatus();}
    @Test void duplicateProofCannotSettleDifferentRefunds()throws Exception{
        long first=cancelled(),a=refund(first);long second=cancelled(),b=refund(second);settle(a,admin,"SAME-REFUND-PROOF","",200);assertEquals("REFUND_REFERENCE_IN_USE",settle(b,staff,"SAME-REFUND-PROOF","",409).get("code").asText());assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds WHERE id=?",String.class,b));
    }
    @Test void unpaidGatewayCrossOrderAndInvalidAmountsReject()throws Exception{
        long id=order(customer,"COD"),p=payment(id);assertEquals("PAYMENT_NOT_REFUNDABLE",requestRefund(id,p,"1","Chưa thu",null,"unpaid-refund-key",409).get("code").asText());confirm(p,admin,"UNPAID-NOW-COLLECTED","",200);cancel(id);voidRefund(refund(id),"Lập lại",200);long otherOrder=order(other,"COD");assertEquals("PAYMENT_NOT_REFUNDABLE",requestRefund(id,payment(otherOrder),"1","Sai đơn",null,"wrong-order-key",409).get("code").asText());for(String amount:List.of("0","-1","1.001","NaN","1e3","10000000000000"))requestRefund(id,p,amount,"Sai số tiền",null,"bad-amount-key",400);db.update("UPDATE payments SET method='GATEWAY',provider='FUTURE_GATEWAY' WHERE id=?",p);assertEquals("PAYMENT_NOT_REFUNDABLE",requestRefund(id,p,"1","Không thủ công gateway",null,"gateway-refund-key",409).get("code").asText());
    }
    long returned(long order,String status,String resolution,int received){long ret=insert("INSERT INTO return_requests(return_number,order_id,requested_by,status,reason,resolution,approved_by) VALUES(?,?,102,?,'test',?,101)","RT-"+UUID.randomUUID(),order,status,resolution);db.update("INSERT INTO return_items(return_request_id,order_id,order_item_id,quantity,received_quantity,reason) SELECT ?,order_id,id,1,?,'test' FROM order_items WHERE order_id=?",ret,received,order);return ret;}
    long insert(String sql,Object...args){var key=new org.springframework.jdbc.support.GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return key.getKey().longValue();}
    @Test void receivedSameOrderReturnHasDiscountedGoodsCapAndNoStockMutation()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('REF10','PERCENT',10,'2020-01-01','2099-01-01')");long id=order(customer,"COD",2,"REF10"),p=payment(id);confirm(p,admin,"RETURN-COLLECTION-001","",200);long ret=returned(id,"RECEIVED","REFUND",1);var stock=db.queryForList("SELECT on_hand_quantity,reserved_quantity FROM inventory_balances");var s=requestRefund(id,p,"900000","Hàng đã nhận lại",ret,"received-refund-key",201);assertEquals("900000.00",s.get("returns").get(0).get("refundableAmount").asText());assertEquals("RETURN_REFUND_EXCEEDS_BALANCE",requestRefund(id,p,"1","Vượt phần hàng",ret,"return-over-limit",409).get("code").asText());settle(refund(id),admin,"RECEIVED-REFUND-001","",200);assertEquals(stock,db.queryForList("SELECT on_hand_quantity,reserved_quantity FROM inventory_balances"));
    }
    @Test void returnDisplayBudgetNeverExceedsPartialActualCollection()throws Exception{
        long id=order(customer,"COD"),p=payment(id);db.update("UPDATE payments SET amount=500000 WHERE id=?",p);confirm(p,admin,"PARTIAL-COLLECTION-RETURN","",200);returned(id,"RECEIVED","REFUND",1);assertEquals("500000.00",refunds(id).get("returns").get(0).get("refundableAmount").asText());
    }
    @Test void unreceivedWrongResolutionAndOtherOrderReturnsReject()throws Exception{
        long id=funded(),p=payment(id);long approved=returned(id,"APPROVED","REFUND",0);assertEquals("RETURN_NOT_REFUNDABLE",requestRefund(id,p,"1","Chưa nhận hàng",approved,"unreceived-return",409).get("code").asText());long repair=returned(id,"RECEIVED","REPAIR",1);assertEquals("RETURN_NOT_REFUNDABLE",requestRefund(id,p,"1","Sai xử lý",repair,"repair-return-key",409).get("code").asText());long wrong=returned(order(other,"COD"),"RECEIVED","REFUND",1);assertEquals("RETURN_NOT_REFUNDABLE",requestRefund(id,p,"1","Sai đơn trả",wrong,"wrong-return-key",409).get("code").asText());assertEquals("RETURN_NOT_REFUNDABLE",requestRefund(id,p,"1","Không có yêu cầu trả",null,"missing-return-key",409).get("code").asText());
    }
    @Test void simultaneousConfirmCancelHasExactlyOneTerminalEffect()throws Exception{
        long id=cancelled(),r=refund(id);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();return mvc.perform(post("/api/admin/refunds/"+r+"/confirm").with(user(admin)).with(csrf()).contentType("application/json").content("{\"reference\":\"RACE-REFUND-PROOF\",\"acknowledged\":true}")).andReturn().getResponse().getStatus();});var b=pool.submit(()->{start.await();return mvc.perform(post("/api/admin/refunds/"+r+"/cancel").with(user(staff)).with(csrf()).contentType("application/json").content("{\"reason\":\"Chưa chuyển tiền\",\"acknowledged\":true}")).andReturn().getResponse().getStatus();});start.countDown();var codes=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);}finally{pool.shutdownNow();}assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action IN ('REFUND_CONFIRMED','REFUND_CANCELLED')",Integer.class));
    }
    @Test void proofAndExplicitAttestationRequiredAndAuditFailureRollsBack()throws Exception{
        long id=cancelled(),r=refund(id);mvc.perform(post("/api/admin/refunds/"+r+"/confirm").with(user(admin)).with(csrf()).contentType("application/json").content("{\"reference\":\"VALID-PROOF\"}")).andExpect(status().isBadRequest());db.execute("CREATE TRIGGER tv3_refund_audit_fail BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced refund audit failure'");try{assertThrows(Exception.class,()->settle(r,admin,"ROLLBACK-REFUND-PROOF","",200));}finally{db.execute("DROP TRIGGER tv3_refund_audit_fail");}assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds WHERE id=?",String.class,r));assertNull(db.queryForObject("SELECT provider_refund_id FROM refunds WHERE id=?",String.class,r));assertNull(db.queryForObject("SELECT processed_by FROM refunds WHERE id=?",Long.class,r));
    }
}
