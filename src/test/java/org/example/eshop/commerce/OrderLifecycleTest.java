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

@SpringBootTest(classes=CommerceApplication.class)
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class OrderLifecycleTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));}
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    final CommercePrincipal admin=new CommercePrincipal(101L,"admin","Admin",null,Set.of("ADMIN"));
    final CommercePrincipal customer=new CommercePrincipal(102L,"customer","Khách_An",null,Set.of("CUSTOMER"));
    final CommercePrincipal other=new CommercePrincipal(103L,"other","Other",null,Set.of("CUSTOMER"));
    final CommercePrincipal staff=new CommercePrincipal(104L,"staff","Staff",null,Set.of("STAFF"));
    @BeforeEach void setup(){
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));
        db.execute("DROP TRIGGER IF EXISTS tv3_fail_audit");
        for(String table:List.of("audit_logs","refunds","payment_events","payments","shipment_items","shipments","inventory_movements","inventory_reservations","coupon_redemptions","order_status_history","order_addresses","order_items","orders","cart_items","carts","coupons","user_addresses","inventory_balances","product_images","variant_materials","product_variants","products","categories","user_roles","users"))db.update("DELETE FROM "+table);
        for(var actor:List.of(admin,customer,other,staff)){db.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",actor.id(),actor.getUsername(),actor.getUsername()+"@example.invalid","unused",actor.getUsername());db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",actor.id(),actor.roles().iterator().next());}
        db.update("UPDATE users SET full_name='Khách_An' WHERE id=102");
        db.update("INSERT INTO categories(id,name,slug) VALUES(1001,'Sofa','admin-sofa')");
        db.update("INSERT INTO products(id,category_id,name,slug,status,assembly_required,warranty_months) VALUES(1001,1001,'Sofa admin','admin-sofa','ACTIVE',1,24)");
        db.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price) VALUES(1001,1001,'ADMIN-KEM','cream','Kem',1000000)");
        db.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,1001,50 FROM warehouses WHERE code='MAIN'");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    JsonNode json(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    long order(CommercePrincipal buyer,boolean assembly)throws Exception{
        return order(buyer,assembly,null);
    }
    long order(CommercePrincipal buyer,boolean assembly,String coupon)throws Exception{
        var cart=json(mvc.perform(post("/api/cart/items").with(user(buyer)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":2}")).andExpect(status().isOk()).andReturn());
        var body=new LinkedHashMap<String,Object>();body.put("cartId",cart.get("id").asLong());body.put("paymentMethod","COD");body.put("assemblyRequested",assembly);body.put("shippingAddress",Map.of("recipientName",buyer.getUsername(),"phone","0901234567","addressLine1","12 Test","provinceName","Hồ Chí Minh","countryCode","VN"));
        if(coupon!=null)body.put("couponCode",coupon);
        var quote=json(mvc.perform(post("/api/checkout/quote").with(user(buyer)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn());body.put("quoteFingerprint",quote.get("quoteFingerprint").asText());
        return json(mvc.perform(post("/api/checkout/orders").header("Idempotency-Key",UUID.randomUUID().toString()).with(user(buyer)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }
    JsonNode transition(long id,String from,String to,String note,int expected)throws Exception{
        return json(mvc.perform(post("/api/admin/orders/"+id+"/transitions").with(user(admin)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("fromStatus",from,"toStatus",to,"note",note)))).andExpect(status().is(expected)).andReturn());
    }
    JsonNode detail(long id)throws Exception{return json(mvc.perform(get("/api/admin/orders/"+id).with(user(admin))).andExpect(status().isOk()).andReturn());}
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void adminEndpointsRequireCurrentRoleAndCsrf()throws Exception{
        mvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        for(var actor:List.of(customer,other)){mvc.perform(get("/api/admin/orders").with(user(actor))).andExpect(status().isForbidden());mvc.perform(get("/api/admin/orders/1").with(user(actor))).andExpect(status().isForbidden());mvc.perform(post("/api/admin/orders/1/transitions").with(user(actor)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(patch("/api/admin/orders/1/internal-note").with(user(actor)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
        mvc.perform(post("/api/admin/orders/1/transitions").with(user(admin)).contentType("application/json").content("{}")).andExpect(status().isForbidden());assertEquals(0,count("audit_logs"));
    }
    @Test void adminListIsPaginatedSearchableAndReadOnly()throws Exception{
        long id=order(customer,true);order(other,false);String number=detail(id).get("orderNumber").asText();
        var list=json(mvc.perform(get("/api/admin/orders").param("query",number).param("size","1").with(user(staff))).andExpect(status().isOk()).andReturn());assertEquals(1,list.get("totalElements").asInt());assertEquals(102,list.get("items").get(0).get("customer").get("id").asInt());
        var literal=json(mvc.perform(get("/api/admin/orders").param("query","%").with(user(admin))).andExpect(status().isOk()).andReturn());assertEquals(0,literal.get("totalElements").asInt());
        var byName=json(mvc.perform(get("/api/admin/orders").param("query","Khách_An").param("status","PENDING_CONFIRMATION").with(user(admin))).andExpect(status().isOk()).andReturn());assertEquals(1,byName.get("totalElements").asInt());
        mvc.perform(get("/api/admin/orders?size=101").with(user(admin))).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/orders?status=INVALID").with(user(admin))).andExpect(status().isBadRequest());assertEquals(0,count("audit_logs"));assertTrue(detail(id).get("assemblyRequested").asBoolean());
    }
    @Test void forwardTransitionsAreControlledAndReplayWritesHistoryOnce()throws Exception{
        long id=order(customer,false);assertEquals("INVALID_TRANSITION",transition(id,"PENDING_CONFIRMATION","PROCESSING","",409).get("code").asText());
        transition(id,"PENDING_CONFIRMATION","CONFIRMED","Đã tiếp nhận",200);transition(id,"PENDING_CONFIRMATION","CONFIRMED","Đã tiếp nhận",200);
        assertEquals(2,count("order_status_history"));assertEquals(1,count("audit_logs"));assertFalse(detail(id).get("confirmedAt").isNull());
        transition(id,"CONFIRMED","PROCESSING","Chuẩn bị hàng",200);assertEquals("ORDER_STATE_CHANGED",transition(id,"PENDING_CONFIRMATION","CONFIRMED","",409).get("code").asText());
        assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals("PENDING",db.queryForObject("SELECT status FROM payments",String.class));
    }
    @Test void completingAnUnpaidUndeliveredOrderIsBlockedWithReasons()throws Exception{
        long id=order(customer,true);transition(id,"PENDING_CONFIRMATION","CONFIRMED","",200);transition(id,"CONFIRMED","PROCESSING","",200);
        assertEquals("ORDER_NOT_READY",transition(id,"PROCESSING","COMPLETED","",409).get("code").asText());
        var d=detail(id);assertTrue(d.get("blockedTransitions").toString().contains("COMPLETED"));assertTrue(d.get("blockedTransitions").toString().contains("reasons"));assertEquals(3,count("order_status_history"));
    }
    @Test void processingRequiresUnreleasedCoverage()throws Exception{
        long id=order(customer,false);transition(id,"PENDING_CONFIRMATION","CONFIRMED","",200);db.update("UPDATE inventory_reservations SET released_quantity=quantity");db.update("UPDATE inventory_balances SET reserved_quantity=0");assertEquals("ORDER_STOCK_NOT_READY",transition(id,"CONFIRMED","PROCESSING","",409).get("code").asText());
    }
    @Test void staffCanCancelProcessingOnceButCannotCancelDispatchedOrders()throws Exception{
        long id=order(customer,false);transition(id,"PENDING_CONFIRMATION","CONFIRMED","",200);transition(id,"CONFIRMED","PROCESSING","",200);
        transition(id,"PROCESSING","CANCELLED","Khách đổi mẫu",200);transition(id,"PROCESSING","CANCELLED","Khách đổi mẫu",200);assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals(4,count("order_status_history"));assertEquals(3,count("audit_logs"));
        long second=order(customer,false);transition(second,"PENDING_CONFIRMATION","CONFIRMED","",200);transition(second,"CONFIRMED","PROCESSING","",200);db.update("INSERT INTO shipments(order_id,warehouse_id,status,shipped_at) SELECT ?,id,'IN_TRANSIT',UTC_TIMESTAMP(6) FROM warehouses WHERE code='MAIN'",second);transition(second,"PROCESSING","CANCELLED","Hủy",409);assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    @Test void noteIsPrivateOptimisticAndAuditedWithoutPublicHistory()throws Exception{
        long id=order(customer,false);String secret="Ghi chú bí mật cho nhân viên";
        var body=new LinkedHashMap<String,Object>();body.put("internalNote",secret);body.put("expectedInternalNote",null);
        for(int i=0;i<2;i++)mvc.perform(patch("/api/admin/orders/"+id+"/internal-note").with(user(staff)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.internalNote").value(secret));
        assertEquals(1,count("audit_logs"));assertEquals(1,count("order_status_history"));
        body.put("internalNote","Đè ghi chú cũ");mvc.perform(patch("/api/admin/orders/"+id+"/internal-note").with(user(admin)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTERNAL_NOTE_CHANGED"));
        var own=mvc.perform(get("/api/orders/"+id).with(user(customer))).andExpect(status().isOk()).andReturn();assertFalse(own.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(secret));assertFalse(json(own).has("internalNote"));
    }
    @Test void paidCancellationKeepsCollectionAndQueuesRefundOnce()throws Exception{
        long id=order(customer,false);db.update("UPDATE payments SET status='SUCCEEDED',paid_at=UTC_TIMESTAMP(6)");transition(id,"PENDING_CONFIRMATION","CANCELLED","Đổi ý",200);transition(id,"PENDING_CONFIRMATION","CANCELLED","Đổi ý",200);assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments",String.class));assertEquals(1,count("refunds"));assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds",String.class));
    }
    @Test void failedAuditRollsBackTheWholeCancellation()throws Exception{
        long id=order(customer,false);db.execute("CREATE TRIGGER tv3_fail_audit BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced audit failure'");
        try{assertThrows(Exception.class,()->transition(id,"PENDING_CONFIRMATION","CANCELLED","Lỗi audit",200));}finally{db.execute("DROP TRIGGER tv3_fail_audit");}
        assertEquals("PENDING_CONFIRMATION",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id));assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals("PENDING",db.queryForObject("SELECT status FROM payments",String.class));assertEquals(1,count("order_status_history"));assertEquals(0,count("audit_logs"));
    }
    @Test void concurrentSameTransitionHasOneEffect()throws Exception{
        long id=order(customer,false);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<JsonNode> job=()->{gate.await();return transition(id,"PENDING_CONFIRMATION","CONFIRMED","Đồng thời",200);};var a=pool.submit(job);var b=pool.submit(job);gate.countDown();assertEquals("CONFIRMED",a.get(20,TimeUnit.SECONDS).get("status").asText());assertEquals("CONFIRMED",b.get(20,TimeUnit.SECONDS).get("status").asText());assertEquals(2,count("order_status_history"));assertEquals(1,count("audit_logs"));}finally{pool.shutdownNow();}
    }
    @Test void importedNoteCanBeEditedUsingItsExactPreviousValue()throws Exception{
        long id=order(customer,false);db.update("UPDATE orders SET internal_note='  Ghi chú nhập từ hệ thống cũ  ' WHERE id=?",id);
        mvc.perform(patch("/api/admin/orders/"+id+"/internal-note").with(user(admin)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("internalNote","Ghi chú đã chỉnh sửa","expectedInternalNote","  Ghi chú nhập từ hệ thống cũ  ")))).andExpect(status().isOk()).andExpect(jsonPath("$.internalNote").value("Ghi chú đã chỉnh sửa"));
    }
    @Test void auditUsesUtcRatherThanDatabaseLocalTime()throws Exception{
        long id=order(customer,false);transition(id,"PENDING_CONFIRMATION","CONFIRMED","",200);
        assertTrue(db.queryForObject("SELECT ABS(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(6)))<5 FROM audit_logs WHERE entity_id=? ORDER BY id DESC LIMIT 1",Boolean.class,Long.toString(id)));
    }
    @Test void concurrentNotesCannotOverwriteAnotherStaffEdit()throws Exception{
        long id=order(customer,false);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->noteAfter(gate,id,admin,"Ghi chú A"));var b=pool.submit(()->noteAfter(gate,id,staff,"Ghi chú B"));gate.countDown();var statuses=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(1,count("audit_logs"));assertEquals(1,count("order_status_history"));}finally{pool.shutdownNow();}
    }
    int noteAfter(CountDownLatch gate,long id,CommercePrincipal actor,String text)throws Exception{
        gate.await();var body=new LinkedHashMap<String,Object>();body.put("internalNote",text);body.put("expectedInternalNote",null);
        return mvc.perform(patch("/api/admin/orders/"+id+"/internal-note").with(user(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
    }
    @Test void customerCancellationRacingStaffConfirmationDoesNotResurrectOrder()throws Exception{
        long id=order(customer,false);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return mvc.perform(post("/api/admin/orders/"+id+"/transitions").with(user(admin)).with(csrf()).contentType("application/json").content("{\"fromStatus\":\"PENDING_CONFIRMATION\",\"toStatus\":\"CONFIRMED\"}")).andReturn().getResponse().getStatus();});var b=pool.submit(()->{gate.await();return mvc.perform(post("/api/orders/"+id+"/cancel").with(user(customer)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đổi ý\"}")).andReturn().getResponse().getStatus();});gate.countDown();assertTrue(Set.of(200,409).contains(a.get(20,TimeUnit.SECONDS)));assertEquals(200,b.get(20,TimeUnit.SECONDS));assertEquals("CANCELLED",detail(id).get("status").asText());assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void completedOrderRequiresRealFulfillmentAndCannotRegress()throws Exception{
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('COMPLETE10','PERCENT',10,'2020-01-01','2099-01-01')");
        long id=order(customer,true,"COMPLETE10");transition(id,"PENDING_CONFIRMATION","CONFIRMED","",200);transition(id,"CONFIRMED","PROCESSING","",200);
        long warehouse=db.queryForObject("SELECT id FROM warehouses WHERE code='MAIN'",Long.class);long item=db.queryForObject("SELECT id FROM order_items WHERE order_id=?",Long.class,id);
        db.update("UPDATE payments SET status='SUCCEEDED',paid_at=UTC_TIMESTAMP(6) WHERE order_id=?",id);
        db.update("UPDATE inventory_balances SET on_hand_quantity=on_hand_quantity-2,reserved_quantity=reserved_quantity-2 WHERE warehouse_id=? AND variant_id=1001",warehouse);db.update("UPDATE inventory_reservations SET fulfilled_quantity=quantity WHERE order_item_id=?",item);
        db.update("INSERT INTO inventory_movements(warehouse_id,variant_id,order_item_id,movement_type,quantity_delta,idempotency_key) VALUES(?,1001,?,'SHIPMENT',-2,?)",warehouse,item,"test-shipment:"+id);
        db.update("INSERT INTO shipments(order_id,warehouse_id,status,shipped_at,delivered_at,installation_required,installation_status,installation_completed_at,installation_staff_id) VALUES(?,?,'DELIVERED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,'COMPLETED',UTC_TIMESTAMP(6),101)",id,warehouse);long shipment=db.queryForObject("SELECT id FROM shipments WHERE order_id=?",Long.class,id);db.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(?,?,?,2)",shipment,id,item);
        transition(id,"PROCESSING","COMPLETED","Đã giao và lắp",200);transition(id,"PROCESSING","COMPLETED","Đã giao và lắp",200);assertFalse(detail(id).get("completedAt").isNull());assertEquals("APPLIED",db.queryForObject("SELECT status FROM coupon_redemptions WHERE order_id=?",String.class,id));assertNotNull(db.queryForObject("SELECT redeemed_at FROM coupon_redemptions WHERE order_id=?",java.sql.Timestamp.class,id));assertEquals(4,count("order_status_history"));transition(id,"COMPLETED","CONFIRMED","Lùi trạng thái",409);
    }
}
