package org.example.eshop.commerce.vector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiEmbeddingClientTest {
    HttpServer server;
    ExecutorService executor;
    URI endpoint;
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger requests = new AtomicInteger();
    volatile int status = 200;
    volatile String response = vectorResponse(768, "3", "4");
    volatile String requestBody;
    volatile String header;
    volatile long delayMillis;
    volatile Runnable duringRequest = () -> {};

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/embed", exchange -> {
            requests.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            header = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            try {
                duringRequest.run();
                if (delayMillis > 0) Thread.sleep(delayMillis);
                if (status == 302) exchange.getResponseHeaders().add("Location", endpoint.toString());
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embed");
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    GeminiEmbeddingClient client(boolean enabled, String key, Duration timeout) {
        return new GeminiEmbeddingClient(new VectorSettings(enabled, key, 5, 60, 8), mapper, endpoint, timeout);
    }
    GeminiEmbeddingClient client() { return client(true, "fixture-key", Duration.ofSeconds(2)); }
    static String vectorResponse(int dimensions, String first, String second) {
        var values = new ArrayList<String>();
        for (int i = 0; i < dimensions; i++) values.add(i == 0 ? first : i == 1 ? second : "0");
        return "{\"embedding\":{\"values\":[" + String.join(",", values) + "]}}";
    }
    @Test void realHttpUsesHeaderFixedModelTaskAndNormalizesExactly768() throws Exception {
        var values = client().document("Sofa gỗ sồi");
        assertEquals(768, values.length); assertEquals(.6, values[0], .000001); assertEquals(.8, values[1], .000001);
        double norm = 0; for (float value : values) { assertTrue(Float.isFinite(value)); norm += value * value; }
        assertEquals(1, norm, .000001);
        var body = mapper.readTree(requestBody);
        assertEquals("fixture-key", header);
        assertEquals("models/gemini-embedding-001", body.path("model").asText());
        assertEquals("RETRIEVAL_DOCUMENT", body.path("taskType").asText());
        assertEquals(768, body.path("outputDimensionality").asInt());
        assertFalse(endpoint.toString().contains("key"));
        client().query("Chỗ ngồi êm");
        assertEquals("RETRIEVAL_QUERY", mapper.readTree(requestBody).path("taskType").asText());
    }
    @Test void rejectsWrongDimensionZeroAndNonFiniteValues() {
        for (String invalid : List.of(vectorResponse(767, "1", "0"), vectorResponse(769, "1", "0"),
                vectorResponse(768, "0", "0"), vectorResponse(768, "1e999", "0"), vectorResponse(768, "\"NaN\"", "0"),
                "{\"embedding\":{\"values\":null}}", "not json")) {
            response = invalid;
            assertEquals("VECTOR_RESPONSE_INVALID", assertThrows(EmbeddingFailure.class, () -> client().document("Sofa")).getMessage());
        }
    }
    @Test void providerErrorsAndRedirectsNeverExposeRawBodiesOrFollowLocation() {
        response = "PRIVATE_PROVIDER_ERROR fixture-key";
        for (int code : List.of(401, 429, 500, 302)) {
            status = code;
            assertEquals("VECTOR_PROVIDER_UNAVAILABLE", assertThrows(EmbeddingFailure.class, () -> client().query("Sofa")).getMessage());
        }
        assertEquals(4, requests.get());
    }
    @Test void boundsResponsesRequestsAndTheTotalDeadline() {
        response = "x".repeat(GeminiEmbeddingClient.MAX_RESPONSE_BYTES + 1);
        assertEquals("VECTOR_RESPONSE_INVALID", assertThrows(EmbeddingFailure.class, () -> client().query("Sofa")).getMessage());
        assertThrows(EmbeddingFailure.class, () -> client().document("a".repeat(GeminiEmbeddingClient.MAX_TEXT_CHARS + 1)));
        assertEquals(1, requests.get());
        response = vectorResponse(768, "1", "0"); delayMillis = 700;
        long started = System.nanoTime();
        assertThrows(EmbeddingFailure.class, () -> client(true, "fixture-key", Duration.ofMillis(100)).query("Sofa"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 600);
    }
    @Test void disabledOrMissingKeyAndDisabledJobsPerformNoIo() {
        assertThrows(EmbeddingFailure.class, () -> client(false, "fixture-key", Duration.ofSeconds(1)).query("Sofa"));
        assertThrows(EmbeddingFailure.class, () -> client(true, "", Duration.ofSeconds(1)).query("Sofa"));
        var runtime = mock(VectorRuntime.class);
        new VectorJob(runtime, new VectorSettings(false, "fixture-key", 5, 60, 8), true).run();
        new VectorJob(runtime, new VectorSettings(true, "fixture-key", 5, 60, 8), false).run();
        verifyNoInteractions(runtime); assertEquals(0, requests.get());
    }
    @Test void searchUnavailableReturnsEmptyWithoutTouchingTheCatalog() {
        var jdbc = mock(JdbcTemplate.class); var embeddings = mock(GeminiEmbeddingClient.class);
        var disabled = new CatalogVectorSearch(new VectorSettings(false, "fixture-key", 5, 60, 8), embeddings, jdbc);
        assertTrue(disabled.searchProducts("Sofa", 5).isEmpty()); verifyNoInteractions(embeddings, jdbc);
        var available = new CatalogVectorSearch(new VectorSettings(true, "fixture-key", 5, 60, 8), client(), jdbc);
        status = 503; assertTrue(available.searchProducts("Sofa", 5).isEmpty()); verifyNoInteractions(jdbc);
    }
    @Test void untrustedProductionEndpointCannotBeConfigured() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiEmbeddingClient(new VectorSettings(true,"key",5,60,8), mapper,
                URI.create("https://example.invalid/steal"), Duration.ofSeconds(1)));
    }
    @Test void oneSharedClientBoundsConcurrencyAndItsMinuteBudgetWithoutSleeping() throws Exception {
        var client=client(); var arrived=new CountDownLatch(2); var release=new CountDownLatch(1);
        duringRequest=() -> {
            arrived.countDown();
            try { assertTrue(release.await(1,TimeUnit.SECONDS)); }
            catch(InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> client.query("Sofa")); var second=pool.submit(() -> client.query("Sofa"));
            assertTrue(arrived.await(1,TimeUnit.SECONDS));
            assertEquals("VECTOR_PROVIDER_BUSY",assertThrows(EmbeddingFailure.class,() -> client.query("Sofa")).getMessage());
            assertEquals(2,requests.get()); release.countDown();
            first.get(2,TimeUnit.SECONDS); second.get(2,TimeUnit.SECONDS);
            duringRequest=() -> {};
            for(int i=2;i<60;i++) client.query("Sofa");
            assertEquals("VECTOR_PROVIDER_BUSY",assertThrows(EmbeddingFailure.class,() -> client.query("Sofa")).getMessage());
            assertEquals(60,requests.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
