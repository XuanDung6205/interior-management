package org.example.eshop.commerce.catalog;

import org.example.eshop.commerce.CommerceApplication;
import org.example.eshop.commerce.security.CommercePrincipal;
import org.example.eshop.commerce.integration.JdbcCheckoutCatalogAdapter;
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
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(classes=CommerceApplication.class)
@ActiveProfiles("commerce")
@TestPropertySource(properties={"commerce.jobs.enabled=false","commerce.catalog.cache.enabled=false","commerce.tv5.cache.redis-enabled=false"})
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
@Transactional
class CatalogIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcCheckoutCatalogAdapter checkout;
    MockMvc mvc;
    final CommercePrincipal admin=new CommercePrincipal(870001L,"catalog-admin","Catalog admin",null,Set.of("ADMIN"));
    final CommercePrincipal staff=new CommercePrincipal(870002L,"catalog-staff","Catalog staff",null,Set.of("STAFF"));
    final CommercePrincipal customer=new CommercePrincipal(870003L,"catalog-customer","Catalog customer",null,Set.of("CUSTOMER"));
    @BeforeEach void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));
        for(var principal:new CommercePrincipal[]{admin,staff,customer}) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",principal.getUserId(),principal.getUsername(),principal.getUsername()+"@example.invalid","unused",principal.fullName());
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",principal.getUserId(),principal==admin?"ADMIN":principal==staff?"STAFF":"CUSTOMER");
        }
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(870001,'Catalog root','catalog-root')");
        jdbc.update("INSERT INTO categories(id,parent_id,name,slug) VALUES(870002,870001,'Catalog child','catalog-child')");
        jdbc.update("INSERT INTO materials(id,name,slug) VALUES(870001,'Oak catalog','catalog-oak')");
        jdbc.update("INSERT INTO rooms(id,name,slug) VALUES(870001,'Office catalog','catalog-office')");
        jdbc.update("INSERT INTO collections(id,name,slug) VALUES(870001,'Calm catalog','catalog-calm')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(870001,870002,'Catalog table','catalog-table','ACTIVE')");
        jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,color_name,width_mm,price) VALUES(870001,870001,'CAT-RED','red','Small red','Red',100,100),(870002,870001,'CAT-BLUE','blue','Large blue','Blue',300,200)");
        jdbc.update("INSERT INTO variant_materials(variant_id,material_id) VALUES(870001,870001),(870002,870001)");
        jdbc.update("INSERT INTO product_rooms(product_id,room_id) VALUES(870001,870001)");
        jdbc.update("INSERT INTO collection_products(collection_id,product_id) VALUES(870001,870001)");
        jdbc.update("INSERT INTO product_images(product_id,url,alt_text) VALUES(870001,'/images/catalog.jpg','Catalog table')");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,870001,9 FROM warehouses WHERE code='MAIN'");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    ObjectNode draft() throws Exception { return (ObjectNode)json(mvc.perform(get("/api/admin/catalog/products/870001").with(user(admin))).andExpect(status().isOk()).andReturn()); }
    MvcResult save(ObjectNode data,int expected) throws Exception {
        return mvc.perform(put("/api/admin/catalog/products/870001").with(user(admin)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(data))).andExpect(status().is(expected)).andReturn();
    }
    @Test void filtersMatchOneSkuAndIncludeDescendantsWithoutDuplicateProducts() throws Exception {
        var noMatch=json(mvc.perform(get("/api/catalog/products?category=catalog-root&color=Red&minWidth=200&maxPrice=150")).andExpect(status().isOk()).andReturn());
        assertEquals(0,noMatch.path("totalElements").asInt());
        var match=json(mvc.perform(get("/api/catalog/products?category=catalog-root&color=Blue&minWidth=200&material=catalog-oak&room=catalog-office&collection=catalog-calm")).andExpect(status().isOk()).andReturn());
        assertEquals(1,match.path("totalElements").asInt());
        assertEquals(870002,match.path("items").get(0).path("variantId").asLong());
        assertEquals("200.00",match.path("items").get(0).path("price").asText());
        assertEquals(1,match.path("items").size());
    }
    @Test void invalidRangesAndPagingAreRejected() throws Exception {
        for(String query:new String[]{"minPrice=100&maxPrice=1","minHeight=-1","page=-1","size=101","minWidth=oops","sort=drop-table"})
            mvc.perform(get("/api/catalog/products?"+query)).andExpect(status().isBadRequest());
    }
    @Test void maintainsSkuIdentityStockAndCheckoutReadsCurrentPrice() throws Exception {
        var data=draft(); var first=(ObjectNode)data.path("variants").get(0); first.put("price","123.45");
        var saved=json(save(data,200));
        assertEquals(1,saved.path("version").asInt());
        assertEquals(870001,saved.path("variants").get(0).path("id").asLong());
        assertEquals(9,jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE variant_id=870001",Integer.class));
        assertEquals("123.45",checkout.findProducts(Set.of(870001L)).get(870001L).unitPrice().toPlainString());
        save(data,409); // stale version cannot silently overwrite another edit
        ((ObjectNode)saved).put("status","ARCHIVED"); save((ObjectNode)saved,200);
        assertFalse(checkout.findProducts(Set.of(870001L)).get(870001L).active());
        mvc.perform(get("/api/catalog/products/catalog-table")).andExpect(status().isNotFound());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM product_variants WHERE product_id=870001",Integer.class));
    }
    @Test void validatesOwnershipAndRollsBackAggregate() throws Exception {
        var data=draft(); data.put("name","Should not save");
        ((ObjectNode)data.path("variants").get(0)).put("id",999999);
        save(data,400);
        assertEquals("Catalog table",jdbc.queryForObject("SELECT name FROM products WHERE id=870001",String.class));
        data=draft(); ((ObjectNode)data.path("images").get(0)).put("variantId",999999); save(data,400);
        data=draft(); ((ObjectNode)data.path("images").get(0)).put("url","javascript:alert(1)"); save(data,400);
        data=draft(); ((ObjectNode)data.path("variants").get(0)).put("price","-1"); save(data,400);
    }
    @Test void createsCompleteAggregateAndEmitsAuditAndVectorEvent() throws Exception {
        var data=draft(); data.remove("id"); data.remove("version"); data.put("name","New catalog chair"); data.put("slug","catalog-new-chair");
        for(var v:data.path("variants")) { ((ObjectNode)v).remove("id"); ((ObjectNode)v).put("sku",v.path("sku").asText()+"-NEW"); }
        var saved=json(mvc.perform(post("/api/admin/catalog/products").with(user(staff)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(data))).andExpect(status().isCreated()).andReturn());
        long id=saved.path("id").asLong();
        assertTrue(id>0); assertEquals(2,saved.path("variants").size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_vector_reindex_outbox WHERE product_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='products' AND entity_id=?",Integer.class,String.valueOf(id)));
        assertEquals(1,saved.path("roomIds").size()); assertEquals(1,saved.path("collectionIds").size());
    }
    @Test void preventsCategoryCyclesAndKeepsArchivedTaxonomy() throws Exception {
        String invalid="{\"name\":\"Catalog root\",\"slug\":\"catalog-root\",\"parentId\":870002,\"active\":true}";
        mvc.perform(put("/api/admin/catalog/taxonomies/categories/870001").with(user(admin)).with(csrf()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        String hide="{\"name\":\"Catalog child\",\"slug\":\"catalog-child\",\"active\":false}";
        mvc.perform(put("/api/admin/catalog/taxonomies/categories/870002").with(user(admin)).with(csrf()).contentType("application/json").content(hide)).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/products/catalog-table")).andExpect(status().isNotFound());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE id=870002",Integer.class));
    }
    @Test void hidingParentCascadesToDescendantsAndCheckout() throws Exception {
        String hide="{\"name\":\"Catalog root\",\"slug\":\"catalog-root\",\"active\":false}";
        mvc.perform(put("/api/admin/catalog/taxonomies/categories/870001").with(user(admin)).with(csrf()).contentType("application/json").content(hide)).andExpect(status().isOk());
        assertFalse(jdbc.queryForObject("SELECT is_active FROM categories WHERE id=870002",Boolean.class));
        assertFalse(checkout.findProducts(Set.of(870001L)).get(870001L).active());
        mvc.perform(get("/api/catalog/products/catalog-table")).andExpect(status().isNotFound());
        String reopenChild="{\"name\":\"Catalog child\",\"slug\":\"catalog-child\",\"parentId\":870001,\"active\":true}";
        mvc.perform(put("/api/admin/catalog/taxonomies/categories/870002").with(user(admin)).with(csrf()).contentType("application/json").content(reopenChild)).andExpect(status().isBadRequest());
    }
    @Test void enforcesRolesAndCsrf() throws Exception {
        mvc.perform(get("/api/admin/catalog/options")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/catalog/options").with(user(customer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/catalog/options").with(user(staff))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/catalog/products").with(user(admin)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
}
