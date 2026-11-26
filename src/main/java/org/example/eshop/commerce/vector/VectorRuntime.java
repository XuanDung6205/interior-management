package org.example.eshop.commerce.vector;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class VectorRuntime {
    public record EnqueueResult(int queued, int skipped, long nextAfterId, boolean hasMore) {}
    public record RunResult(int scanned, int indexed, int deleted, int retried, int failed, int skipped) {}
    private record Candidate(long id, long productId) {}
    private record Claim(long id, long productId, String token, int attempt, VectorCatalog.Document document) {}
    private enum Completion { INDEXED, DELETED, SKIPPED }
    private final JdbcTemplate jdbc;
    private final VectorCatalog catalog;
    private final Clock clock;
    private final VectorSettings settings;
    private final GeminiEmbeddingClient embeddings;
    private final TransactionTemplate transactions;
    private final AtomicLong discoveryCursor = new AtomicLong();

    public VectorRuntime(JdbcTemplate jdbc, VectorCatalog catalog, Clock clock, VectorSettings settings,
                         GeminiEmbeddingClient embeddings, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.catalog = catalog; this.clock = clock; this.settings = settings; this.embeddings = embeddings;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
    }

    public Map<String, Object> snapshot() {
        var counts = new LinkedHashMap<String, Object>();
        for (String state : List.of("pending", "retry", "sent", "dead", "leased")) counts.put(state, 0L);
        long indexed = 0;
        List<Map<String, Object>> dead = List.of();
        try {
            indexed = jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE catalog_product_id IS NOT NULL", Long.class);
            for (var row : jdbc.queryForList("SELECT state,COUNT(*) AS total FROM commerce_vector_reindex_outbox GROUP BY state"))
                counts.put(row.get("state").toString().toLowerCase(Locale.ROOT), row.get("total"));
            counts.put("leased", jdbc.queryForObject("""
                    SELECT COUNT(*) FROM commerce_vector_reindex_outbox
                    WHERE state IN ('PENDING','RETRY') AND lease_until>?
                    """, Long.class, now()));
            dead = jdbc.queryForList("""
                    SELECT id,product_id AS productId,attempts,
                    CASE WHEN last_error REGEXP '^VECTOR_[A-Z_]+$' THEN last_error ELSE 'VECTOR_FAILED' END AS lastError,
                    updated_at AS updatedAt FROM commerce_vector_reindex_outbox
                    WHERE state='DEAD' ORDER BY updated_at DESC,id DESC LIMIT 20
                    """);
        } catch (DataAccessException legacySchema) {
            // Opt-in deployments may still be running the historical schema while disabled.
            if (settings.enabled()) throw schemaUnavailable();
        }
        return Map.of("enabled", settings.enabled(), "configured", settings.configured(), "model", VectorSettings.MODEL,
                "dimensions", VectorSettings.DIMENSIONS, "batchSize", settings.batchSize(),
                "intervalSeconds", settings.intervalSeconds(), "indexedProducts", indexed, "outbox", counts, "deadEvents", dead);
    }

    /** Bounded manual enqueue is useful even before an API key is installed. No provider IO. */
    public EnqueueResult enqueuePage(Long afterId, Integer limit, Long actor) {
        long after = afterId == null ? 0 : afterId;
        if (after < 0 || limit == null || limit < 1 || limit > 100)
            throw CommerceException.invalid(Map.of("limit", "Số lượng phải từ 1 đến 100; mã bắt đầu không được âm."));
        try {
            var ids = catalog.ids(after, limit + 1);
            int queued = 0, skipped = 0;
            for (long id : ids.subList(0, Math.min(limit, ids.size()))) {
                boolean added = Boolean.TRUE.equals(transactions.execute(tx -> {
                    var document = catalog.load(id, true);
                    return document != null && ensureQueued(document, actor);
                }));
                if (added) queued++; else skipped++;
                after = id;
            }
            return new EnqueueResult(queued, skipped, after, ids.size() > limit);
        } catch (DataAccessException legacySchema) { throw schemaUnavailable(); }
    }

    /** Each claim/persist is its own short transaction. HTTP executes after every claim has committed. */
    public RunResult runBatch() {
        if (!settings.enabled()) throw CommerceException.conflict("VECTOR_DISABLED", "Lập chỉ mục vector đang tắt.");
        if (!settings.configured()) throw CommerceException.conflict("VECTOR_NOT_CONFIGURED", "Cần cấu hình GEMINI_API_KEY trước khi lập chỉ mục.");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Vector IO must run outside a database transaction");
        try {
            var discovery = enqueuePage(discoveryCursor.get(), settings.batchSize(), null);
            discoveryCursor.set(discovery.hasMore() ? discovery.nextAfterId() : 0);
            var candidates = jdbc.query("""
                    SELECT id,product_id FROM commerce_vector_reindex_outbox
                    WHERE state IN ('PENDING','RETRY') AND next_attempt_at<=?
                      AND (lease_until IS NULL OR lease_until<=?)
                    ORDER BY next_attempt_at,id LIMIT ?
                    """, (rs, n) -> new Candidate(rs.getLong("id"), rs.getLong("product_id")), now(), now(), settings.batchSize());
            int indexed = 0, deleted = 0, retried = 0, failed = 0, skipped = 0;
            for (var candidate : candidates) {
                Claim claim = transactions.execute(tx -> claim(candidate));
                if (claim == null) { skipped++; continue; }
                try {
                    float[] vector = claim.document().active() ? embeddings.document(claim.document().text()) : null;
                    Completion completion = transactions.execute(tx -> persist(claim, vector));
                    if (completion == Completion.INDEXED) indexed++;
                    else if (completion == Completion.DELETED) deleted++;
                    else skipped++;
                } catch (RuntimeException failure) {
                    String code = failure instanceof EmbeddingFailure ? failure.getMessage() : "VECTOR_INDEX_FAILED";
                    String state = transactions.execute(tx -> fail(claim, code));
                    if (state == null) skipped++;
                    else { failed++; if ("RETRY".equals(state)) retried++; }
                }
            }
            return new RunResult(candidates.size(), indexed, deleted, retried, failed, skipped);
        } catch (DataAccessException legacySchema) { throw schemaUnavailable(); }
    }

    public Map<String, Object> retry(long id, String reason, long actor) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 1000)
            throw CommerceException.invalid(Map.of("reason", "Nhập lý do thử lại từ 1 đến 1000 ký tự."));
        var products = jdbc.queryForList("SELECT product_id FROM commerce_vector_reindex_outbox WHERE id=?", Long.class, id);
        if (products.isEmpty()) throw CommerceException.notFound("Không tìm thấy tác vụ vector.");
        transactions.executeWithoutResult(tx -> {
            var document = catalog.load(products.get(0), true);
            if (document == null) throw CommerceException.notFound("Không tìm thấy sản phẩm.");
            var states = jdbc.queryForList("SELECT state FROM commerce_vector_reindex_outbox WHERE id=? FOR UPDATE", String.class, id);
            if (states.isEmpty()) throw CommerceException.notFound("Không tìm thấy tác vụ vector.");
            if (!"DEAD".equals(states.get(0))) throw CommerceException.conflict("VECTOR_NOT_DEAD", "Chỉ tác vụ DEAD mới được thử lại thủ công.");
            supersedePending(document.productId());
            jdbc.update("""
                    UPDATE commerce_vector_reindex_outbox SET state='RETRY',attempts=0,next_attempt_at=?,last_error=NULL,
                    processed_at=NULL,lease_token=NULL,lease_until=NULL,requested_fingerprint=?,action=?,
                    retry_reason=?,retried_by=?,retried_at=?,updated_at=? WHERE id=?
                    """, now(), document.fingerprint(), document.active() ? "UPSERT" : "DELETE", reason.trim(), actor, now(), now(), id);
        });
        return snapshot();
    }

    private boolean ensureQueued(VectorCatalog.Document document, Long actor) {
        var indexed = jdbc.queryForList("SELECT catalog_fingerprint,embedding_model FROM vector_store WHERE catalog_product_id=?", document.productId());
        if (document.active() && !indexed.isEmpty() && document.fingerprint().equals(indexed.get(0).get("catalog_fingerprint")) &&
                VectorSettings.MODEL.equals(indexed.get(0).get("embedding_model"))) return false;
        if (!document.active() && indexed.isEmpty()) return false;
        var latest = jdbc.queryForList("""
                SELECT state,requested_fingerprint FROM commerce_vector_reindex_outbox WHERE product_id=? ORDER BY id DESC LIMIT 1
                """, document.productId());
        if (!latest.isEmpty() && document.fingerprint().equals(latest.get(0).get("requested_fingerprint")) &&
                Set.of("PENDING", "RETRY", "DEAD").contains(latest.get(0).get("state"))) return false;
        supersedePending(document.productId());
        jdbc.update("""
                INSERT INTO commerce_vector_reindex_outbox(product_id,action,state,next_attempt_at,requested_fingerprint,requested_by,created_at,updated_at)
                VALUES(?,?,'PENDING',?,?,?,?,?)
                """, document.productId(), document.active() ? "UPSERT" : "DELETE", now(), document.fingerprint(), actor, now(), now());
        return true;
    }

    private void supersedePending(long productId) {
        jdbc.update("""
                UPDATE commerce_vector_reindex_outbox SET state='SENT',last_error='VECTOR_SUPERSEDED',processed_at=?,
                lease_token=NULL,lease_until=NULL,updated_at=? WHERE product_id=? AND state IN ('PENDING','RETRY')
                """, now(), now(), productId);
    }

    private Claim claim(Candidate candidate) {
        // All paths lock product before outbox: enqueue, retry, persist and claim have one lock order.
        var document = catalog.load(candidate.productId(), true);
        if (document == null) return null; // Product deletion cascades both owned vectors and tasks.
        var events = jdbc.queryForList("SELECT * FROM commerce_vector_reindex_outbox WHERE id=? FOR UPDATE", candidate.id());
        if (events.isEmpty()) return null;
        var event = events.get(0);
        if (!Set.of("PENDING", "RETRY").contains(event.get("state")) || afterNow(event.get("next_attempt_at")) || afterNow(event.get("lease_until"))) return null;
        if (event.get("requested_fingerprint") != null && !document.fingerprint().equals(event.get("requested_fingerprint"))) {
            sent(candidate.id(), "VECTOR_SUPERSEDED"); ensureQueued(document, null); return null;
        }
        int attempts = ((Number) event.get("attempts")).intValue();
        if (attempts >= settings.maxAttempts()) {
            jdbc.update("UPDATE commerce_vector_reindex_outbox SET state='DEAD',last_error='VECTOR_LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?", now(), candidate.id());
            return null;
        }
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE commerce_vector_reindex_outbox SET attempts=attempts+1,lease_token=?,lease_until=?,
                requested_fingerprint=?,action=?,updated_at=? WHERE id=?
                """, token, now().plusSeconds(60), document.fingerprint(), document.active() ? "UPSERT" : "DELETE", now(), candidate.id());
        return new Claim(candidate.id(), candidate.productId(), token, attempts + 1, document);
    }

    private Completion persist(Claim claim, float[] vector) {
        var current = catalog.load(claim.productId(), true);
        if (current == null || !ownsLease(claim)) return Completion.SKIPPED;
        if (!current.fingerprint().equals(claim.document().fingerprint())) {
            sent(claim.id(), "VECTOR_SUPERSEDED"); ensureQueued(current, null); return Completion.SKIPPED;
        }
        if (current.active()) {
            var existing = jdbc.queryForList("SELECT id FROM vector_store WHERE catalog_product_id=?", String.class, current.productId());
            String text = GeminiEmbeddingClient.vectorText(vector);
            if (existing.isEmpty()) {
                String stableId = UUID.nameUUIDFromBytes(("eshop:catalog-product:" + current.productId()).getBytes(StandardCharsets.UTF_8)).toString();
                jdbc.update("""
                        INSERT INTO vector_store(id,content,metadata,embedding,catalog_product_id,catalog_fingerprint,embedding_model,indexed_at)
                        VALUES(?,?,'{}',VEC_FromText(?),?,?,?,?)
                        """, stableId, current.text(), text, current.productId(), current.fingerprint(), VectorSettings.MODEL, now());
            } else jdbc.update("""
                    UPDATE vector_store SET content=?,embedding=VEC_FromText(?),catalog_fingerprint=?,embedding_model=?,indexed_at=?
                    WHERE catalog_product_id=?
                    """, current.text(), text, current.fingerprint(), VectorSettings.MODEL, now(), current.productId());
            sent(claim.id(), null);
            return Completion.INDEXED;
        }
        jdbc.update("DELETE FROM vector_store WHERE catalog_product_id=?", current.productId());
        sent(claim.id(), null);
        return Completion.DELETED;
    }

    private String fail(Claim claim, String code) {
        if (catalog.load(claim.productId(), true) == null || !ownsLease(claim)) return null;
        String state = claim.attempt() >= settings.maxAttempts() ? "DEAD" : "RETRY";
        long delay = Math.min(3600L, 30L * (1L << Math.min(10, claim.attempt() - 1)));
        jdbc.update("""
                UPDATE commerce_vector_reindex_outbox SET state=?,next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE id=? AND lease_token=?
                """, state, now().plusSeconds(delay), code, now(), claim.id(), claim.token());
        return state;
    }

    private boolean ownsLease(Claim claim) {
        return !jdbc.queryForList("""
                SELECT id FROM commerce_vector_reindex_outbox WHERE id=? AND lease_token=?
                AND state IN ('PENDING','RETRY') AND lease_until>? FOR UPDATE
                """, Long.class, claim.id(), claim.token(), now()).isEmpty();
    }

    private void sent(long id, String error) {
        jdbc.update("""
                UPDATE commerce_vector_reindex_outbox SET state='SENT',processed_at=?,updated_at=?,last_error=?,
                lease_token=NULL,lease_until=NULL WHERE id=?
                """, now(), now(), error, id);
    }
    private boolean afterNow(Object value) {
        return value instanceof Timestamp time ? time.toLocalDateTime().isAfter(now()) :
                value instanceof LocalDateTime localTime && localTime.isAfter(now());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static CommerceException schemaUnavailable() { return CommerceException.conflict("VECTOR_SCHEMA_UNAVAILABLE", "Cần cập nhật cấu trúc cơ sở dữ liệu vector trước khi lập chỉ mục."); }
}
