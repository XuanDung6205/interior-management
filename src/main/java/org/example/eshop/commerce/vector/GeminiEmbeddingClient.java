package org.example.eshop.commerce.vector;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Fixed Google endpoint, no redirects, bounded bytes and an end-to-end deadline. */
@Component
public class GeminiEmbeddingClient {
    static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent");
    static final int MAX_TEXT_CHARS = 12000;
    static final int MAX_REQUEST_BYTES = 64000;
    static final int MAX_RESPONSE_BYTES = 65536;
    private final VectorSettings settings;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore inFlight = new Semaphore(2);
    private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();

    @Autowired
    public GeminiEmbeddingClient(VectorSettings settings, ObjectMapper mapper) {
        this(settings, mapper, ENDPOINT, Duration.ofSeconds(12));
    }

    // Package-private deterministic fixture seam. Production configuration cannot override the host.
    GeminiEmbeddingClient(VectorSettings settings, ObjectMapper mapper, URI endpoint, Duration timeout) {
        if (!ENDPOINT.equals(endpoint) && !("http".equals(endpoint.getScheme()) &&
                Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost()))) {
            throw new IllegalArgumentException("Only the official endpoint or a loopback fixture is allowed");
        }
        this.settings = settings;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public float[] document(String publicCatalogText) { return embed(publicCatalogText, "RETRIEVAL_DOCUMENT"); }
    public float[] query(String question) { return embed(question, "RETRIEVAL_QUERY"); }

    private float[] embed(String text, String task) {
        if (!settings.enabled() || !settings.configured()) throw new EmbeddingFailure("VECTOR_NOT_CONFIGURED");
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_CHARS) throw new EmbeddingFailure("VECTOR_INPUT_INVALID");
        if (!inFlight.tryAcquire()) throw new EmbeddingFailure("VECTOR_PROVIDER_BUSY");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            synchronized (requestTimes) {
                long now = System.nanoTime();
                while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= TimeUnit.MINUTES.toNanos(1)) requestTimes.removeFirst();
                if (requestTimes.size() >= 60) throw new EmbeddingFailure("VECTOR_PROVIDER_BUSY");
                requestTimes.addLast(now);
            }
            byte[] requestBody = mapper.writeValueAsBytes(Map.of(
                    "model", "models/" + VectorSettings.MODEL,
                    "content", Map.of("parts", List.of(Map.of("text", text))),
                    "taskType", task, "outputDimensionality", VectorSettings.DIMENSIONS));
            if (requestBody.length > MAX_REQUEST_BYTES) throw new EmbeddingFailure("VECTOR_INPUT_INVALID");
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("x-goog-api-key", settings.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            pending = http.sendAsync(request, info -> new BoundedBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new EmbeddingFailure("VECTOR_PROVIDER_UNAVAILABLE");
            var values = mapper.readTree(response.body()).path("embedding").path("values");
            if (!values.isArray() || values.size() != VectorSettings.DIMENSIONS) throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
            double norm = 0;
            double[] raw = new double[VectorSettings.DIMENSIONS];
            for (int i = 0; i < raw.length; i++) {
                if (!values.get(i).isNumber()) throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
                raw[i] = values.get(i).asDouble();
                if (!Double.isFinite(raw[i]) || Math.abs(raw[i]) > Float.MAX_VALUE) throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
                norm = Math.hypot(norm, raw[i]);
            }
            if (!Double.isFinite(norm) || norm == 0) throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
            float[] normalized = new float[raw.length];
            for (int i = 0; i < raw.length; i++) normalized[i] = (float) (raw[i] / norm);
            return normalized;
        } catch (EmbeddingFailure expected) {
            throw expected;
        } catch (TimeoutException timeoutFailure) {
            if (pending != null) pending.cancel(true);
            throw new EmbeddingFailure("VECTOR_PROVIDER_TIMEOUT");
        } catch (InterruptedException interrupted) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new EmbeddingFailure("VECTOR_PROVIDER_UNAVAILABLE");
        } catch (Exception failure) {
            throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
        } finally {
            inFlight.release();
        }
    }

    static String vectorText(float[] values) {
        if (values.length != VectorSettings.DIMENSIONS) throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) throw new EmbeddingFailure("VECTOR_RESPONSE_INVALID");
            if (i > 0) result.append(',');
            result.append(Float.toString(values[i]));
        }
        return result.append(']').toString();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new EmbeddingFailure("VECTOR_RESPONSE_INVALID")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
