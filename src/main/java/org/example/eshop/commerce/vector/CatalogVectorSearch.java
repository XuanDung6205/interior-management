package org.example.eshop.commerce.vector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

@Service
public class CatalogVectorSearch {
    private final VectorSettings settings;
    private final GeminiEmbeddingClient embeddings;
    private final JdbcTemplate jdbc;
    public CatalogVectorSearch(VectorSettings settings, GeminiEmbeddingClient embeddings, JdbcTemplate jdbc) {
        this.settings = settings; this.embeddings = embeddings; this.jdbc = jdbc;
    }

    /** An empty result is the caller's signal to use its existing SQL catalog fallback. */
    public List<Long> searchProducts(String question, int limit) {
        if (!settings.enabled() || !settings.configured() || question == null || question.isBlank() || limit < 1 ||
                question.length() > 1000 || TransactionSynchronizationManager.isActualTransactionActive()) return List.of();
        int boundedLimit = Math.min(20, limit);
        try {
            String vector = GeminiEmbeddingClient.vectorText(embeddings.query(question));
            return jdbc.queryForList("""
                    SELECT v.catalog_product_id FROM vector_store v JOIN products p ON p.id=v.catalog_product_id
                    WHERE p.status='ACTIVE' AND v.embedding_model=?
                    ORDER BY VEC_DISTANCE_COSINE(v.embedding,VEC_FromText(?)),v.catalog_product_id LIMIT ?
                    """, Long.class, VectorSettings.MODEL, vector, boundedLimit);
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }
}
