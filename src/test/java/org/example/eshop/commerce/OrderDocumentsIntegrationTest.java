package org.example.eshop.commerce;

import org.example.eshop.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest(classes=CommerceApplication.class)
@ActiveProfiles("commerce")
@TestPropertySource(properties="commerce.jobs.enabled=false")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
@Transactional
class OrderDocumentsIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));
    }
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    final CommercePrincipal staff=new CommercePrincipal(881101L,"docs.staff","Staff",null,Set.of("STAFF"));
    final CommercePrincipal owner=new CommercePrincipal(881102L,"docs.owner","Owner",null,Set.of("CUSTOMER"));
    final CommercePrincipal other=new CommercePrincipal(881103L,"docs.other","Other",null,Set.of("CUSTOMER"));

    @BeforeEach void fixture() {
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));
        for(var actor:List.of(staff,owner,other)) {
            db.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",actor.getUserId(),actor.getUsername(),actor.getUsername()+"@example.invalid","unused",actor.getUsername());
            db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",actor.getUserId(),actor==staff?"STAFF":"CUSTOMER");
        }
        db.update("INSERT INTO categories(id,name,slug) VALUES(881100,'Docs','docs-test')");
        db.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(881100,881100,'Catalog original','docs-product','ACTIVE')");
        db.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price) VALUES(881100,881100,'DOC-SKU','one','Original',1000)");
        order(889001,"DOC-ONE",owner.getUserId(),"CANCELLED","2026-11-20 17:00:00","Khách gốc");
        db.update("UPDATE orders SET subtotal=1000,discount_amount=100,shipping_fee=50,assembly_fee=20,tax_amount=30,grand_total=1000,assembly_requested=1,customer_note='Gọi trước khi giao',internal_note='NEVER-SHOW-INTERNAL' WHERE id=889001");
        db.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price,discount_amount) VALUES(889001,889001,881100,881100,'Sofa snapshot','Kem snapshot','DOC-SKU',1,1000,100)");
        db.update("INSERT INTO payments(id,order_id,method,provider,status,amount,currency,idempotency_key,provider_transaction_id,confirmation_note) VALUES(889001,889001,'COD','COD','SUCCEEDED',1000,'VND','docs-paid','NEVER-SHOW-RECEIPT','NEVER-SHOW-PAYMENT-NOTE')");
        db.update("INSERT INTO refunds(id,order_id,payment_id,idempotency_key,status,amount,currency,reason,provider_refund_id,confirmation_note) VALUES(889001,889001,889001,'docs-refunded','SUCCEEDED',50,'VND','Hoàn một phần','NEVER-SHOW-REFUND-REFERENCE','NEVER-SHOW-REFUND-NOTE'),(889002,889001,889001,'docs-pending','PENDING',25,'VND','Đang xử lý',NULL,NULL)");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    void order(long id,String number,long user,String status,String time,String recipient) {
        db.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total,placed_at) VALUES(?,?,?,?,?,1000,1000,?)",id,number,user,"docs-order-"+id,status,time);
        db.update("INSERT INTO order_addresses(order_id,address_type,recipient_name,phone,address_line1,province_name,country_code) VALUES(?,'SHIPPING',?,'0900000000','12 Đường snapshot','Hồ Chí Minh','VN')",id,recipient);
    }
    MvcResult read(String path,CommercePrincipal actor,int status) throws Exception {
        var request=get(path);if(actor!=null)request.with(user(actor));
        return mvc.perform(request).andExpect(status().is(status)).andReturn();
    }
    JsonNode json(MvcResult result) throws Exception {return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    String body(MvcResult result) throws Exception {return result.getResponse().getContentAsString(StandardCharsets.UTF_8);}

    @Test void ownerAndStaffPrintSafeSnapshotsAndActualFinancialStatesWithoutWriting() throws Exception {
        db.update("UPDATE products SET name='Changed catalog' WHERE id=881100");
        db.update("UPDATE product_variants SET price=9000 WHERE id=881100");
        int audits=db.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class);
        for(var entry:List.of(Map.entry("/api/orders/889001/print",owner),Map.entry("/api/admin/orders/889001/print",staff))) {
            var response=read(entry.getKey(),entry.getValue(),200);
            assertEquals("no-store",response.getResponse().getHeader("Cache-Control"));
            var data=json(response);
            assertEquals("Sofa snapshot",data.get("items").get(0).get("productName").asString());
            assertEquals("1000.00",data.get("grandTotal").asString());
            assertEquals("1000.00",data.get("collectedAmount").asString());
            assertEquals("50.00",data.get("refundedAmount").asString());
            assertEquals("25.00",data.get("pendingRefundAmount").asString());
            assertEquals("Khách gốc",data.get("shippingAddress").get("recipientName").asString());
            assertFalse(body(response).contains("NEVER-SHOW"));
            assertFalse(data.has("internalNote"));assertFalse(data.has("customer"));assertFalse(data.has("history"));
        }
        assertEquals(audits,db.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=889001",String.class));
    }

    @Test void printAndExportRequireAuthenticationRoleAndOwnership() throws Exception {
        read("/api/orders/889001/print",null,401);
        read("/api/orders/889001/print",other,404);
        read("/api/orders/889001/print",staff,404);
        read("/api/admin/orders/889001/print",owner,403);
        read("/api/admin/orders/889001/print",null,401);
        read("/api/admin/orders/export.csv",owner,403);
        read("/api/admin/orders/export.csv",null,401);
        read("/api/admin/orders/99999999/print",staff,404);
    }

    @Test void csvMatchesAppliedListStatusSearchAndVietnamDateBoundaries() throws Exception {
        order(889002,"DOC-BEFORE",owner.getUserId(),"CANCELLED","2026-11-20 16:59:59.999999","Trước ngày");
        order(889003,"DOC-LAST",other.getUserId(),"CANCELLED","2026-11-21 16:59:59.999999","Cuối ngày");
        order(889004,"DOC-NEXT",owner.getUserId(),"CANCELLED","2026-11-21 17:00:00","Ngày tiếp");
        order(889005,"DOC-STATUS",owner.getUserId(),"CONFIRMED","2026-11-21 10:00:00","Sai trạng thái");
        String filters="?status=CANCELLED&query=DOC-&fromDate=2026-11-21&toDate=2026-11-21";
        var list=json(read("/api/admin/orders"+filters+"&page=0&size=1",staff,200));
        assertEquals(2,list.get("totalElements").asInt());assertEquals("DOC-LAST",list.get("items").get(0).get("orderNumber").asString());
        var csv=read("/api/admin/orders/export.csv"+filters,staff,200);
        assertTrue(csv.getResponse().getContentType().startsWith("text/csv"));
        assertTrue(csv.getResponse().getHeader("Content-Disposition").contains("attachment"));
        assertEquals("no-store",csv.getResponse().getHeader("Cache-Control"));
        assertEquals("nosniff",csv.getResponse().getHeader("X-Content-Type-Options"));
        String text=body(csv);assertTrue(text.startsWith("\uFEFF"));
        assertTrue(text.contains("DOC-ONE"));assertTrue(text.contains("DOC-LAST"));
        for(String hidden:List.of("DOC-BEFORE","DOC-NEXT","DOC-STATUS","NEVER-SHOW","12 Đường snapshot"))assertFalse(text.contains(hidden));
        assertTrue(text.indexOf("DOC-LAST")<text.indexOf("DOC-ONE"));
        assertTrue(text.contains("2026-11-21 00:00:00"));
        assertTrue(text.contains("\"1000.00\""));
    }

    @Test void invalidFiltersFailIdenticallyAndLiteralWildcardsStayLiteral() throws Exception {
        for(String query:List.of("?fromDate=2026-02-30","?fromDate=2026-11-22&toDate=2026-11-21","?toDate=21-11-2026")) {
            for(String endpoint:List.of("/api/admin/orders","/api/admin/orders/export.csv")) {
                assertEquals("INVALID_DATE_RANGE",json(read(endpoint+query,staff,400)).get("code").asString());
            }
        }
        order(889002,"DOC-%_",owner.getUserId(),"CONFIRMED","2026-11-21 10:00:00","Literal");
        var list=json(mvc.perform(get("/api/admin/orders").with(user(staff)).param("query","%_")).andExpect(status().isOk()).andReturn());
        assertEquals(1,list.get("totalElements").asInt());
        var csv=mvc.perform(get("/api/admin/orders/export.csv").with(user(staff)).param("query","%_")).andExpect(status().isOk()).andReturn();
        assertTrue(body(csv).contains("DOC-%_"));assertFalse(body(csv).contains("DOC-ONE"));
    }

    @Test void csvQuotesMultilineTextAndNeutralizesFormulaStartsIncludingFullWidth() throws Exception {
        String name="  =HYPERLINK(\"x\",\"y\")\r\nDòng hai";
        db.update("UPDATE order_addresses SET recipient_name=?,phone='+84900000000' WHERE order_id=889001",name);
        var csv=read("/api/admin/orders/export.csv?query=DOC-ONE",staff,200);
        assertTrue(body(csv).contains("\"\t  =HYPERLINK(\"\"x\"\",\"\"y\"\")\r\nDòng hai\""));
        assertTrue(body(csv).contains("\"\t+84900000000\""));
        db.update("UPDATE order_addresses SET recipient_name='＝1+2' WHERE order_id=889001");
        assertTrue(body(read("/api/admin/orders/export.csv?query=DOC-ONE",staff,200)).contains("\"\t＝1+2\""));
    }

    @Test void csvDownloadAcceptStillReceivesJsonBusinessErrors() throws Exception {
        mvc.perform(get("/api/admin/orders/export.csv").with(user(staff)).accept("text/csv").param("fromDate","bad-date"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test void emptyExportIsHeaderOnlyAndMoreThanFiveThousandCannotReturnPartialCsv() throws Exception {
        var empty=read("/api/admin/orders/export.csv?query=NO-SUCH-DOC",staff,200);
        assertEquals(1,body(empty).lines().count());
        var values=new ArrayList<Object[]>();
        for(int i=0;i<5001;i++)values.add(new Object[]{900000L+i,"DOC-LIMIT-"+i,owner.getUserId(),"docs-limit-"+i});
        db.batchUpdate("INSERT INTO orders(id,order_number,user_id,idempotency_key,subtotal,grand_total) VALUES(?,?,?,?,1000,1000)",values);
        var rejected=read("/api/admin/orders/export.csv?query=DOC-LIMIT-",staff,400);
        assertEquals("EXPORT_TOO_LARGE",json(rejected).get("code").asString());
        assertNull(rejected.getResponse().getHeader("Content-Disposition"));
        db.update("DELETE FROM orders WHERE id=905000");
        var allowed=read("/api/admin/orders/export.csv?query=DOC-LIMIT-",staff,200);
        assertEquals(5001,body(allowed).lines().count());
    }
}
