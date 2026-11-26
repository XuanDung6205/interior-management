package org.example.eshop.commerce.vector;

import com.sun.net.httpserver.HttpServer;
import org.example.eshop.commerce.CommerceApplication;
import org.example.eshop.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real MariaDB native VECTOR and real loopback HTTP fixtures; this does not call live Google AI. */
@SpringBootTest(classes=CommerceApplication.class, properties={"commerce.jobs.enabled=false", "spring.ai.gemini.api-key="})
@Import(VectorRuntimeIntegrationTest.FixtureConfiguration.class)
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL", matches=".*furniture_shop_test_.*")
class VectorRuntimeIntegrationTest {
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-11-26T08:00:00Z"));
    static final AtomicInteger REQUESTS = new AtomicInteger();
    static volatile Consumer<JsonNode> DURING_REQUEST = body -> {};
    static volatile boolean PROVIDER_FAIL;
    static HttpServer fixture;
    static ExecutorService fixtureThreads;
    static URI endpoint;
    static synchronized URI startFixture() {
        if (endpoint != null) return endpoint;
        try {
            fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fixtureThreads = Executors.newCachedThreadPool(); fixture.setExecutor(fixtureThreads);
            fixture.createContext("/embed", exchange -> {
                try {
                    var body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                    REQUESTS.incrementAndGet(); DURING_REQUEST.accept(body);
                    String text = body.path("content").path("parts").get(0).path("text").asText();
                    boolean query = "RETRIEVAL_QUERY".equals(body.path("taskType").asText());
                    // Geometry is a declared semantic fixture: seats near query; desks orthogonal.
                    String raw = query ? GeminiEmbeddingClientTest.vectorResponse(768,"1","0") :
                            text.contains("Steel desk") ? GeminiEmbeddingClientTest.vectorResponse(768,"0","1") :
                            text.contains("Oak lounge") ? GeminiEmbeddingClientTest.vectorResponse(768,"1","0") :
                            GeminiEmbeddingClientTest.vectorResponse(768,"0.98","0.2");
                    byte[] response = (PROVIDER_FAIL ? "PRIVATE_FIXTURE_PROVIDER_ERROR" : raw).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(PROVIDER_FAIL ? 503 : 200,response.length);
                    exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            fixture.start(); endpoint=URI.create("http://127.0.0.1:" + fixture.getAddress().getPort() + "/embed"); return endpoint;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary VectorSettings fixtureSettings() { return new VectorSettings(true,"fixture-key",5,60,2); }
        @Bean @Primary GeminiEmbeddingClient fixtureClient(VectorSettings settings,ObjectMapper mapper) {
            return new GeminiEmbeddingClient(settings,mapper,startFixture(),Duration.ofSeconds(3));
        }
        @Bean @Primary Clock vectorClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return NOW.get(); }
            };
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("TV3_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME","root"));
        r.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired VectorRuntime runtime;
    @Autowired CatalogVectorSearch search;
    @Autowired VectorCatalog catalog;
    @Autowired VectorSettings settings;
    @Autowired GeminiEmbeddingClient embeddings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    Long cascadeProbeId;
    final CommercePrincipal admin = new CommercePrincipal(48001L,"vector-admin","Vector admin",null,Set.of("ADMIN"));
    final CommercePrincipal customer = new CommercePrincipal(48002L,"vector-customer","Vector customer",null,Set.of("CUSTOMER"));
    static final String GENERIC_ID="00000000-0000-0000-0000-000000048001";

    @BeforeEach void seed() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));
        cleanup();
        // Discovery and SQL fallback query the whole catalog. Other integration classes
        // leave fixtures behind, so start from an empty guarded test catalog regardless
        // of Surefire's platform-dependent class order. Delete vectors explicitly before
        // parent products to avoid native VECTOR key reuse after a cascading deletion.
        for (String table : List.of(
                "commerce_vector_reindex_outbox", "vector_store",
                "chat_messages", "chat_conversations", "review_images", "reviews", "wishlist_items",
                "tracking_events", "articles", "banners", "warranty_requests",
                "commerce_notifications", "commerce_notification_outbox", "audit_logs",
                "refunds", "return_items", "return_requests", "payment_events", "payments",
                "shipment_items", "shipments", "inventory_movements", "inventory_reservations",
                "coupon_redemptions", "order_status_history", "order_addresses", "order_items", "orders",
                "cart_items", "carts", "coupons", "user_addresses", "inventory_balances",
                "collection_products", "product_rooms", "product_specifications", "product_images",
                "variant_materials", "product_variants", "products", "categories", "user_roles", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        NOW.set(Instant.parse("2026-11-26T08:00:00Z")); PROVIDER_FAIL=false; REQUESTS.set(0); DURING_REQUEST=body -> {};
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(48001,'Vector furniture','vector-furniture')");
        jdbc.update("""
                INSERT INTO products(id,category_id,name,slug,short_description,status) VALUES
                (48001,48001,'Cloud sofa','vector-cloud','Public soft seat','ACTIVE'),
                (48002,48001,'Oak lounge','vector-oak','Public reading seat','DRAFT'),
                (48003,48001,'Steel desk','vector-desk','Public work table','DRAFT')
                """);
        for (long id : List.of(48001L,48002L,48003L)) {
            jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price,status) VALUES(?,?,?,'one','Public SKU description',1234567,'ACTIVE')",id,id,"VECTOR-"+id);
            jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,?,7 FROM warehouses WHERE code='MAIN'",id);
        }
        for (var actor : List.of(admin,customer)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",actor.id(),actor.getUsername(),actor.getUsername()+"@example.invalid","unused",actor.fullName());
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",actor.id(),actor==admin?"ADMIN":"CUSTOMER");
        }
        float[] generic = new float[768]; generic[1]=1;
        jdbc.update("INSERT INTO vector_store(id,content,embedding) VALUES(?,'Generic non-catalog record',VEC_FromText(?))",GENERIC_ID,GeminiEmbeddingClient.vectorText(generic));
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup() {
        if (jdbc==null) return;
        DURING_REQUEST=body -> {}; PROVIDER_FAIL=false;
        jdbc.update("DELETE FROM chat_conversations WHERE user_id IN (48001,48002)");
        jdbc.update("DELETE FROM commerce_vector_reindex_outbox WHERE product_id BETWEEN 48001 AND 48003");
        jdbc.update("DELETE FROM vector_store WHERE catalog_product_id BETWEEN 48001 AND 48003 OR id=?",GENERIC_ID);
        if(cascadeProbeId!=null) {
            jdbc.update("DELETE FROM vector_store WHERE catalog_product_id=?",cascadeProbeId);
            jdbc.update("DELETE FROM products WHERE id=?",cascadeProbeId);
            cascadeProbeId=null;
        }
        jdbc.update("DELETE FROM inventory_balances WHERE variant_id BETWEEN 48001 AND 48003");
        jdbc.update("DELETE FROM warehouses WHERE code='VECTOR-INACTIVE'");
        jdbc.update("DELETE FROM product_variants WHERE id BETWEEN 48001 AND 48003");
        jdbc.update("DELETE FROM products WHERE id BETWEEN 48001 AND 48003");
        jdbc.update("DELETE FROM categories WHERE id=48001");
        jdbc.update("DELETE FROM users WHERE id IN (48001,48002)");
    }
    @AfterAll static void closeFixture() { if(fixture!=null) { fixture.stop(0); fixtureThreads.shutdownNow(); } }
    JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    long eventId() { return jdbc.queryForObject("SELECT id FROM commerce_vector_reindex_outbox WHERE product_id=48001 ORDER BY id DESC LIMIT 1",Long.class); }
    VectorRuntime restarted() { return new VectorRuntime(jdbc,catalog,clock,settings,embeddings,transactions); }
    void activateAll() { jdbc.update("UPDATE products SET status='ACTIVE' WHERE id BETWEEN 48001 AND 48003"); }
    void advance(long seconds) { NOW.updateAndGet(time -> time.plusSeconds(seconds)); }

    @Test void realHttpNativeRankingAndRealServiceBranchesHydrateFreshPricesAndStock() throws Exception {
        activateAll();
        var queued=json(mvc.perform(post("/api/admin/operations/vector/reindex").with(user(admin)).with(csrf()).contentType("application/json").content("{\"afterId\":0,\"limit\":100}")).andExpect(status().isOk()).andReturn());
        assertEquals(3,queued.path("queued").asInt()); assertEquals(0,REQUESTS.get());
        var run=json(mvc.perform(post("/api/admin/operations/vector/run").with(user(admin)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn());
        assertEquals(3,run.path("indexed").asInt());
        assertEquals(List.of(48002L,48001L),search.searchProducts("comfortable reading seat",2));
        assertEquals(768,jdbc.queryForObject("SELECT JSON_LENGTH(VEC_ToText(embedding)) FROM vector_store WHERE catalog_product_id=48002",Integer.class));
        String content=jdbc.queryForObject("SELECT content FROM vector_store WHERE catalog_product_id=48002",String.class);
        assertFalse(content.contains("1234567")); assertFalse(content.contains("example.invalid"));
        int before=REQUESTS.get();
        var recommendations=json(mvc.perform(get("/api/commerce/recommendations?productId=48001&limit=1")).andExpect(status().isOk()).andReturn());
        assertTrue(REQUESTS.get()>before,"The proxied recommendation service must actually take the vector branch");
        assertEquals(48002,recommendations.path("items").get(0).path("productId").asLong());
        jdbc.update("UPDATE product_variants SET price=7654321 WHERE id=48002");
        jdbc.update("UPDATE inventory_balances SET on_hand_quantity=11 WHERE variant_id=48002");
        jdbc.update("INSERT INTO warehouses(code,name,is_active) VALUES('VECTOR-INACTIVE','Disabled vector fixture warehouse',0)");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,48002,99 FROM warehouses WHERE code='VECTOR-INACTIVE'");
        jdbc.update("UPDATE inventory_balances SET on_hand_quantity=0 WHERE variant_id=48003");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) SELECT id,48003,99 FROM warehouses WHERE code='VECTOR-INACTIVE'");
        var fresh=json(mvc.perform(get("/api/commerce/recommendations?productId=48001&limit=1")).andExpect(status().isOk()).andReturn()).path("items").get(0);
        assertEquals("7654321.00",fresh.path("minPrice").asText()); assertEquals(11,fresh.path("totalAvailable").asInt());
        var onlyActiveStock=json(mvc.perform(get("/api/commerce/recommendations?productId=48001&limit=3")).andExpect(status().isOk()).andReturn()).path("items");
        assertEquals(1,onlyActiveStock.size()); assertEquals(48002,onlyActiveStock.get(0).path("productId").asLong());
        var chat=json(mvc.perform(post("/api/commerce/chat").with(user(customer)).with(csrf()).contentType("application/json").content("{\"message\":\"comfortable reading seat\"}")).andExpect(status().isOk()).andReturn());
        assertEquals("UNCONFIGURED",chat.path("model").asText());
        boolean liveOak=false;
        for(var source:chat.path("sources")) if(source.path("productId").asLong()==48002) {
            assertEquals("7654321.00",source.path("price").asText()); assertEquals(11,source.path("availableQuantity").asInt()); liveOak=true;
        }
        assertTrue(liveOak,"Semantic IDs must hydrate into live chat sources even when lexical words do not match");
        for(var source:chat.path("sources")) if(source.path("productId").asLong()==48003) assertEquals(0,source.path("availableQuantity").asInt());
        // The same warehouse policy also applies when embedding retrieval is unavailable.
        PROVIDER_FAIL=true;
        var sqlRecommendations=json(mvc.perform(get("/api/commerce/recommendations?productId=48001&limit=3")).andExpect(status().isOk()).andReturn()).path("items");
        assertEquals(1,sqlRecommendations.size()); assertEquals(11,sqlRecommendations.get(0).path("totalAvailable").asInt());
        for(String question:List.of("Oak lounge","unmatchedkeyword")) {
            var sqlChat=json(mvc.perform(post("/api/commerce/chat").with(user(customer)).with(csrf()).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("message",question)))).andExpect(status().isOk()).andReturn());
            boolean foundOak=false;
            for(var source:sqlChat.path("sources")) {
                if(source.path("productId").asLong()==48002) { assertEquals(11,source.path("availableQuantity").asInt()); foundOak=true; }
                if(source.path("productId").asLong()==48003) assertEquals(0,source.path("availableQuantity").asInt());
            }
            assertTrue(foundOak,"Both lexical SQL and its empty-keyword fallback must hydrate active-warehouse stock");
        }
    }

    @Test void changedCatalogWhileHttpIsInFlightRejectsStaleVectorAndQueuesFreshWork() {
        runtime.enqueuePage(0L,100,admin.id());
        DURING_REQUEST=body -> { jdbc.update("UPDATE products SET short_description='Changed during HTTP' WHERE id=48001"); DURING_REQUEST=ignored -> {}; };
        var first=runtime.runBatch(); assertEquals(0,first.indexed()); assertEquals(1,first.skipped());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE catalog_product_id=48001",Integer.class));
        assertEquals(1,restarted().runBatch().indexed());
        assertTrue(jdbc.queryForObject("SELECT content FROM vector_store WHERE catalog_product_id=48001",String.class).contains("Changed during HTTP"));
    }

    @Test void expiredLeaseAndRestartFenceTheOldWorkerWithoutHoldingLocksDuringHttp() throws Exception {
        runtime.enqueuePage(0L,100,admin.id());
        var arrived=new CountDownLatch(1); var release=new CountDownLatch(1); var seen=new AtomicInteger();
        DURING_REQUEST=body -> {
            if(seen.incrementAndGet()==1) { arrived.countDown(); try { assertTrue(release.await(2,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
        };
        var pool=Executors.newSingleThreadExecutor();
        try {
            var old=pool.submit(runtime::runBatch); assertTrue(arrived.await(2,TimeUnit.SECONDS)); advance(61);
            var recovered=restarted().runBatch(); assertEquals(1,recovered.indexed());
            release.countDown(); assertEquals(0,old.get(3,TimeUnit.SECONDS).indexed());
            assertEquals(2,jdbc.queryForObject("SELECT attempts FROM commerce_vector_reindex_outbox WHERE id=?",Integer.class,eventId()));
            assertEquals("SENT",jdbc.queryForObject("SELECT state FROM commerce_vector_reindex_outbox WHERE id=?",String.class,eventId()));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void outageBackoffDeadLetterAndManualRetrySurviveRestart() throws Exception {
        PROVIDER_FAIL=true;
        assertEquals(1,runtime.runBatch().retried()); long id=eventId();
        assertEquals(0,restarted().runBatch().scanned()); advance(31);
        assertEquals(1,restarted().runBatch().failed());
        assertEquals("DEAD",jdbc.queryForObject("SELECT state FROM commerce_vector_reindex_outbox WHERE id=?",String.class,id));
        assertEquals(0,restarted().runBatch().scanned());
        var status=json(mvc.perform(get("/api/admin/operations/vector").with(user(admin))).andExpect(status().isOk()).andReturn());
        assertEquals(1,status.path("outbox").path("dead").asInt()); assertFalse(status.toString().contains("PRIVATE_FIXTURE"));
        PROVIDER_FAIL=false;
        var retry=json(mvc.perform(post("/api/admin/operations/vector/events/"+id+"/retry").with(user(admin)).with(csrf()).contentType("application/json").content("{\"reason\":\"Đã sửa kết nối\"}")).andExpect(status().isOk()).andReturn());
        assertEquals(0,retry.path("outbox").path("dead").asInt()); assertEquals(1,restarted().runBatch().indexed());
        assertEquals(admin.id(),jdbc.queryForObject("SELECT retried_by FROM commerce_vector_reindex_outbox WHERE id=?",Long.class,id));
    }

    @Test void stableIdsInactiveDeletionAndForeignKeyCascadePreserveGenericVectors() {
        assertEquals(1,runtime.runBatch().indexed());
        String stable=jdbc.queryForObject("SELECT id FROM vector_store WHERE catalog_product_id=48001",String.class);
        jdbc.update("UPDATE product_variants SET name='Changed public SKU description' WHERE id=48001");
        assertEquals(1,restarted().runBatch().indexed());
        assertEquals(stable,jdbc.queryForObject("SELECT id FROM vector_store WHERE catalog_product_id=48001",String.class));
        int before=REQUESTS.get(); jdbc.update("UPDATE products SET status='DRAFT' WHERE id=48001");
        assertEquals(1,restarted().runBatch().deleted()); assertEquals(before,REQUESTS.get());
        jdbc.update("UPDATE products SET status='ACTIVE' WHERE id=48001"); assertEquals(1,restarted().runBatch().indexed());
        // MariaDB12.2 native VECTOR currently leaves a hidden PK tombstone after FK cascade.
        // Use a one-off auto-generated parent/UUID for this constraint assertion; fixed fixture IDs
        // are explicitly deleted before parent cleanup and remain reusable across methods/runs.
        String slug="vector-cascade-probe-"+UUID.randomUUID();
        jdbc.update("INSERT INTO products(category_id,name,slug,status) VALUES(48001,'Cascade probe',?,'ACTIVE')",slug);
        cascadeProbeId=jdbc.queryForObject("SELECT id FROM products WHERE slug=?",Long.class,slug);
        float[] probe=new float[768]; probe[0]=1;
        jdbc.update("INSERT INTO vector_store(id,content,embedding,catalog_product_id) VALUES(?,'Public cascade probe',VEC_FromText(?),?)",UUID.randomUUID().toString(),GeminiEmbeddingClient.vectorText(probe),cascadeProbeId);
        jdbc.update("INSERT INTO commerce_vector_reindex_outbox(product_id,next_attempt_at) VALUES(?,CURRENT_TIMESTAMP(6))",cascadeProbeId);
        jdbc.update("DELETE FROM products WHERE id=?",cascadeProbeId);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE catalog_product_id=?",Integer.class,cascadeProbeId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_vector_reindex_outbox WHERE product_id=?",Integer.class,cascadeProbeId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE id=?",Integer.class,GENERIC_ID));
    }

    @Test void parallelEnqueueIsBoundedAndDeduplicatesByCurrentFingerprint() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> runtime.enqueuePage(0L,100,admin.id()));
            var second=pool.submit(() -> restarted().enqueuePage(0L,100,admin.id()));
            assertEquals(1,first.get(5,TimeUnit.SECONDS).queued()+second.get(5,TimeUnit.SECONDS).queued());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_vector_reindex_outbox WHERE product_id=48001",Integer.class));
        } finally { pool.shutdownNow(); }
        assertEquals(0,REQUESTS.get());
    }

    @Test void staffCsrfAndBoundedInputProtectEveryOperationsMutation() throws Exception {
        mvc.perform(get("/api/admin/operations/vector").with(user(customer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/operations/vector/run").with(user(admin)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/operations/vector/reindex").with(user(admin)).with(csrf()).contentType("application/json").content("{\"afterId\":0,\"limit\":101}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/operations/vector/events/1/retry").with(user(customer)).with(csrf()).contentType("application/json").content("{\"reason\":\"retry\"}")).andExpect(status().isForbidden());
        var disabled=new VectorRuntime(jdbc,catalog,clock,new VectorSettings(false,"",5,60,8),embeddings,transactions);
        assertEquals("VECTOR_DISABLED",assertThrows(org.example.eshop.commerce.common.CommerceException.class,disabled::runBatch).getCode());
        var missingKey=new VectorRuntime(jdbc,catalog,clock,new VectorSettings(true,"",5,60,8),embeddings,transactions);
        assertEquals("VECTOR_NOT_CONFIGURED",assertThrows(org.example.eshop.commerce.common.CommerceException.class,missingKey::runBatch).getCode());
        assertEquals(0,REQUESTS.get());
    }
}
