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
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class CheckoutTransactionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));
    }
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    CommercePrincipal alice=new CommercePrincipal(102L,"alice","Alice",null,Set.of("CUSTOMER"));
    CommercePrincipal bob=new CommercePrincipal(103L,"bob","Bob",null,Set.of("CUSTOMER"));
    @BeforeEach void setup() {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));
        for(String t:List.of("refunds","payment_events","payments","shipment_items","shipments","inventory_movements","inventory_reservations","coupon_redemptions","order_status_history","order_addresses","order_items","orders","cart_items","carts","coupons","user_addresses","inventory_balances","product_images","variant_materials","product_variants","products","categories","user_roles","users")) db.update("DELETE FROM "+t);
        for(var u:List.of(alice,bob)) {
            db.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",u.getUserId(),u.getUsername(),u.getUsername()+"@example.invalid","unused",u.getUsername());
            db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='CUSTOMER'",u.getUserId());
        }
        db.update("INSERT INTO categories(id,name,slug) VALUES(1001,'Sofa','checkout-sofa')");
        db.update("INSERT INTO products(id,category_id,name,slug,status,warranty_months,assembly_required) VALUES(1001,1001,'Sofa gốc','checkout-sofa','ACTIVE',24,1)");
        db.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price,color_name) VALUES(1001,1001,'CHECK-KEM','cream','Kem',100.05,'Kem'),(1002,1001,'CHECK-XAM','grey','Xám',200,'Xám')");
        db.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,1001,10 FROM warehouses WHERE code='MAIN'");
        db.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,1002,10 FROM warehouses WHERE code='MAIN'");
        db.update("INSERT INTO user_addresses(id,user_id,recipient_name,phone,address_line1,province_name) VALUES(1,102,'Alice','0901234567','12 Đường A','Hồ Chí Minh'),(2,103,'Bob','0901234568','13 Đường B','Hà Nội')");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    JsonNode json(MvcResult r) throws Exception {return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    long cart(CommercePrincipal user,int quantity) throws Exception {
        return json(mvc.perform(post("/api/cart/items").with(user(user)).with(csrf()).contentType("application/json").content("{\"variantId\":1001,\"quantity\":"+quantity+"}")).andDo(r->{if(r.getResponse().getStatus()!=200)System.out.println(r.getResponse().getContentAsString());}).andExpect(status().isOk()).andReturn()).get("id").asLong();
    }
    Map<String,Object> request(long cart) {var r=new LinkedHashMap<String,Object>();r.put("cartId",cart);r.put("addressId",1);r.put("paymentMethod","COD");r.put("assemblyRequested",true);r.put("customerNote","Gọi trước khi giao");return r;}
    JsonNode quote(Map<String,Object> r,CommercePrincipal actor,int expected) throws Exception {
        return json(mvc.perform(post("/api/checkout/quote").with(user(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(r))).andExpect(status().is(expected)).andReturn());
    }
    JsonNode place(Map<String,Object> r,CommercePrincipal actor,String key,int expected) throws Exception {
        return json(mvc.perform(post("/api/checkout/orders").header("Idempotency-Key",key).with(user(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(r))).andExpect(status().is(expected)).andReturn());
    }
    Map<String,Object> ready(CommercePrincipal actor) throws Exception {
        var r=request(cart(actor,2));r.put("addressId",actor==bob?2:1);r.put("quoteFingerprint",quote(r,actor,200).get("quoteFingerprint").asText());return r;
    }
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void quoteIsReadOnlyAndServerPricesFeesAndRounding() throws Exception {
        var r=request(cart(alice,3));
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('TEN','PERCENT',10,'2020-01-01','2099-01-01')");r.put("couponCode","ten");
        var q=quote(r,alice,200);
        assertEquals("300.15",q.get("subtotal").asText());assertEquals("30.02",q.get("discountAmount").asText());
        assertEquals("300000.00",q.get("assemblyFee").asText());assertEquals("350270.13",q.get("grandTotal").asText());
        assertEquals(0,count("orders"));assertEquals(0,count("coupon_redemptions"));assertEquals(0,count("inventory_reservations"));
    }
    @Test void quoteRejectsWrongOwnerEmptyInactiveShortageAndInvalidCoupon() throws Exception {
        var r=request(cart(alice,1));r.put("addressId",2);quote(r,alice,404);r.put("addressId",1);
        db.update("UPDATE product_variants SET status='INACTIVE' WHERE id=1001");quote(r,alice,409);
        db.update("UPDATE product_variants SET status='ACTIVE' WHERE id=1001");db.update("UPDATE inventory_balances SET on_hand_quantity=0");quote(r,alice,409);
        db.update("UPDATE inventory_balances SET on_hand_quantity=10");r.put("couponCode","MISSING");quote(r,alice,409);r.remove("couponCode");
        db.update("DELETE FROM cart_items");quote(r,alice,409);
    }
    @Test void discountedTotalCannotHideOverflowInStoredSubtotalAndLineAmount() throws Exception {
        long id=cart(alice,2);
        db.update("UPDATE product_variants SET price=6000000000000.00 WHERE id=1001");
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('QUARTER','PERCENT',25,'2020-01-01','2099-01-01')");
        var r=request(id);r.put("couponCode","QUARTER");r.put("assemblyRequested",false);
        assertEquals("ORDER_AMOUNT_TOO_LARGE",quote(r,alice,409).get("code").asText());assertEquals(0,count("orders"));
    }
    @Test void placeSnapshotsReservesAndReplaysWithoutConvertingNewCart() throws Exception {
        var r=ready(alice);var o=place(r,alice,"snapshot-key-01",201);long id=o.get("id").asLong();
        assertEquals("PENDING_CONFIRMATION",o.get("status").asText());assertEquals("PENDING",o.get("payments").get(0).get("status").asText());
        assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
        assertEquals("CONVERTED",db.queryForObject("SELECT status FROM carts WHERE id=?",String.class,r.get("cartId")));
        long newCart=cart(alice,1);assertNotEquals(r.get("cartId"),newCart);
        assertEquals(id,place(r,alice,"snapshot-key-01",201).get("id").asLong());
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM carts WHERE id=?",String.class,newCart));
        r.put("customerNote","different");assertEquals("IDEMPOTENCY_CONFLICT",place(r,alice,"snapshot-key-01",409).get("code").asText());
        db.update("UPDATE products SET name='Đổi tên' WHERE id=1001");db.update("UPDATE user_addresses SET address_line1='Địa chỉ mới' WHERE id=1");
        var detail=json(mvc.perform(get("/api/orders/"+id).with(user(alice))).andExpect(status().isOk()).andReturn());
        assertEquals("Sofa gốc",detail.get("items").get(0).get("productName").asText());assertEquals("12 Đường A",detail.get("shippingAddress").get("addressLine1").asText());assertFalse(detail.has("internalNote"));
    }
    @Test void staleQuoteReturnsNewQuoteWithoutAnyWrites() throws Exception {
        var r=ready(alice);db.update("UPDATE product_variants SET price=999 WHERE id=1001");
        var e=place(r,alice,"price-change-key",409);assertEquals("QUOTE_CHANGED",e.get("code").asText());assertEquals("1998.00",e.get("quote").get("subtotal").asText());assertEquals(0,count("orders"));
    }
    @Test void ownerIsolationAndRepeatedCancellationReleaseExactlyOnce() throws Exception {
        var r=ready(alice);db.update("INSERT INTO coupons(code,discount_type,discount_value,total_usage_limit,starts_at,ends_at) VALUES('ONE','FIXED',10,1,'2020-01-01','2099-01-01')");r.put("couponCode","ONE");r.put("quoteFingerprint",quote(r,alice,200).get("quoteFingerprint").asText());
        var o=place(r,alice,"cancel-order-key",201);long id=o.get("id").asLong();
        mvc.perform(get("/api/orders/"+id).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/"+id+"/cancel").with(user(bob)).with(csrf()).contentType("application/json").content("{\"reason\":\"Hủy\"}")).andExpect(status().isNotFound());
        for(int i=0;i<2;i++)mvc.perform(post("/api/orders/"+id+"/cancel").with(user(alice)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đổi ý\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals("RELEASED",db.queryForObject("SELECT status FROM coupon_redemptions",String.class));assertEquals(2,count("order_status_history"));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM payments",String.class));
    }
    @Test void paidCancellationQueuesRefundAndDispatchBlocksCancellation() throws Exception {
        long id=place(ready(alice),alice,"paid-cancel-key",201).get("id").asLong();db.update("UPDATE payments SET status='SUCCEEDED',paid_at=UTC_TIMESTAMP(6) WHERE order_id=?",id);
        for(int i=0;i<2;i++)mvc.perform(post("/api/orders/"+id+"/cancel").with(user(alice)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đổi ý\"}")).andExpect(status().isOk());
        assertEquals(1,count("refunds"));assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds",String.class));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments",String.class));
        long second=place(ready(alice),alice,"dispatch-order-key",201).get("id").asLong();
        db.update("INSERT INTO shipments(order_id,warehouse_id,status,shipped_at) SELECT ?,id,'IN_TRANSIT',UTC_TIMESTAMP(6) FROM warehouses WHERE code='MAIN'",second);
        mvc.perform(post("/api/orders/"+second+"/cancel").with(user(alice)).with(csrf()).contentType("application/json").content("{\"reason\":\"Hủy\"}")).andExpect(status().isConflict());
        assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
    }
    @Test void checkoutAndOrdersRequireAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());mvc.perform(get("/api/checkout/addresses")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/checkout/quote").with(user(alice)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void concurrentSameKeyCreatesOneOrder() throws Exception {
        var r=ready(alice);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<Long> call=()->{gate.await();return place(r,alice,"concurrent-key-01",201).get("id").asLong();};var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,count("orders"));assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void competingOrdersCannotOversellOrOverspendCoupon() throws Exception {
        db.update("INSERT INTO coupons(code,discount_type,discount_value,total_usage_limit,starts_at,ends_at) VALUES('LAST','FIXED',10,1,'2020-01-01','2099-01-01')");
        var a=ready(alice);var b=ready(bob);for(var pair:List.of(Map.entry(a,alice),Map.entry(b,bob))){pair.getKey().put("couponCode","LAST");pair.getKey().put("quoteFingerprint",quote(pair.getKey(),pair.getValue(),200).get("quoteFingerprint").asText());}
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {Callable<Integer> first=()->{gate.await();return mvc.perform(post("/api/checkout/orders").header("Idempotency-Key","last-coupon-alice").with(user(alice)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(a))).andReturn().getResponse().getStatus();};Callable<Integer> second=()->{gate.await();return mvc.perform(post("/api/checkout/orders").header("Idempotency-Key","last-coupon-bob").with(user(bob)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(b))).andReturn().getResponse().getStatus();};var x=pool.submit(first);var y=pool.submit(second);gate.countDown();var results=new ArrayList<>(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS)));Collections.sort(results);assertEquals(List.of(201,409),results);assertEquals(1,count("orders"));assertEquals(1,count("coupon_redemptions"));}finally{pool.shutdownNow();}
    }
    @Test void cancelAndCheckoutSameUserDoNotInvertUserAndCouponLocks() throws Exception {
        db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('SHARED','FIXED',10,'2020-01-01','2099-01-01')");
        var first=ready(alice);first.put("couponCode","SHARED");first.put("quoteFingerprint",quote(first,alice,200).get("quoteFingerprint").asText());
        long id=place(first,alice,"before-race-order",201).get("id").asLong();
        var next=ready(alice);next.put("couponCode","SHARED");next.put("quoteFingerprint",quote(next,alice,200).get("quoteFingerprint").asText());
        var pool=Executors.newFixedThreadPool(2);
        try(Connection blocker=Objects.requireNonNull(db.getDataSource()).getConnection()){
            blocker.setAutoCommit(false);
            try(var s=blocker.prepareStatement("SELECT id FROM payments WHERE order_id=? FOR UPDATE")){s.setLong(1,id);s.executeQuery().close();}
            var cancel=pool.submit(()->mvc.perform(post("/api/orders/"+id+"/cancel").with(user(alice)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đổi đơn\"}")).andExpect(status().isOk()).andReturn());
            waitForRunningSql("SELECT id,status,amount,currency,provider FROM payments WHERE order_id="+id+"%","SELECT id,status,amount,currency,provider FROM payments WHERE order_id="+id+"%");
            var checkout=pool.submit(()->place(next,alice,"while-cancel-order",201));
            waitForRunningSql("SELECT id FROM coupons WHERE code='SHARED'%","SELECT id FROM users WHERE id=102 FOR UPDATE%");
            blocker.commit();
            cancel.get(20,TimeUnit.SECONDS);checkout.get(20,TimeUnit.SECONDS);
            assertEquals(2,count("orders"));assertEquals(2,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));
        }finally{pool.shutdownNow();}
    }
    void waitForRunningSql(String first,String second) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            int count=db.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=DATABASE() AND (INFO LIKE ? OR INFO LIKE ?)",Integer.class,first,second);
            if(count>0)return;Thread.sleep(30);
        }
        throw new AssertionError("Expected blocked SQL stage: "+first+" or "+second);
    }
    @Test void failedSnapshotInsertRollsBackOrderCouponPaymentAndInventory() throws Exception {
        var r=ready(alice);db.update("INSERT INTO coupons(code,discount_type,discount_value,starts_at,ends_at) VALUES('ROLLBACK','FIXED',10,'2020-01-01','2099-01-01')");r.put("couponCode","ROLLBACK");r.put("quoteFingerprint",quote(r,alice,200).get("quoteFingerprint").asText());
        // Guarded disposable DB only: fail AFTER real reserve writes to verify the outer transaction.
        db.execute("CREATE TRIGGER tv3_test_fail_address BEFORE INSERT ON order_addresses FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced snapshot failure'");
        try {assertThrows(Exception.class,()->place(r,alice,"rollback-order-key",201));}finally{db.execute("DROP TRIGGER tv3_test_fail_address");}
        for(String table:List.of("orders","order_items","order_addresses","order_status_history","coupon_redemptions","payments","inventory_reservations"))assertEquals(0,count(table),table);
        assertEquals(0,db.queryForObject("SELECT SUM(reserved_quantity) FROM inventory_balances",Integer.class));assertEquals("ACTIVE",db.queryForObject("SELECT status FROM carts WHERE id=?",String.class,r.get("cartId")));
    }
    @Test void orderListIsPaginatedFilteredAndPrivateWithIntegerQuantity() throws Exception {
        place(ready(alice),alice,"list-alice-key",201);place(ready(bob),bob,"list-bob-key-01",201);
        var list=json(mvc.perform(get("/api/orders?page=0&size=1&status=PENDING_CONFIRMATION").with(user(alice))).andExpect(status().isOk()).andReturn());
        assertEquals(1,list.get("totalElements").asInt());assertEquals(1,list.get("items").size());assertTrue(list.get("items").get(0).get("itemCount").isIntegralNumber());assertEquals(2,list.get("items").get(0).get("itemCount").asInt());
        mvc.perform(get("/api/orders?status=UNKNOWN").with(user(alice))).andExpect(status().isBadRequest());
    }
}
