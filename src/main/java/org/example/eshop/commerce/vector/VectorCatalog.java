package org.example.eshop.commerce.vector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A deliberately narrow allowlist; prices, orders, customers and tracking never enter a document. */
@Repository
class VectorCatalog {
    record Document(long productId, boolean active, String text, String fingerprint) {}
    private final JdbcTemplate jdbc;
    VectorCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<Long> ids(long afterId, int limit) {
        return jdbc.queryForList("SELECT id FROM products WHERE id>? ORDER BY id LIMIT ?", Long.class, afterId, limit);
    }

    Document load(long id, boolean lock) {
        var products = jdbc.query("""
                SELECT p.id,p.name,p.status,p.short_description,LEFT(p.description,8000) AS description,c.name AS category
                FROM products p JOIN categories c ON c.id=p.category_id WHERE p.id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> Map.of(
                "name", rs.getString("name"), "status", rs.getString("status"), "category", rs.getString("category"),
                "summary", Objects.toString(rs.getString("short_description"), ""),
                "description", Objects.toString(rs.getString("description"), "")), id);
        if (products.isEmpty()) return null;
        var row = products.get(0);
        boolean active = "ACTIVE".equals(row.get("status"));
        StringBuilder text = new StringBuilder();
        if (active) {
            text.append(row.get("name")).append('\n').append(row.get("category")).append('\n')
                    .append(row.get("summary")).append('\n').append(row.get("description"));
            // A fixed ID window makes the bounded document stable when later SKUs are edited.
            // Persist holds these rows briefly so a public SKU/material edit cannot race its fingerprint check.
            var variants = jdbc.query("""
                    SELECT id,sku,name,status FROM product_variants WHERE product_id=? ORDER BY id LIMIT 50
                    """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> Map.of("id",rs.getLong("id"),
                    "text",rs.getString("sku")+": "+rs.getString("name"),"active","ACTIVE".equals(rs.getString("status"))), id);
            var activeIds = new ArrayList<Long>();
            for (var variant : variants) if (Boolean.TRUE.equals(variant.get("active"))) {
                text.append('\n').append(variant.get("text")); activeIds.add((Long)variant.get("id"));
            }
            if (!activeIds.isEmpty()) {
                String placeholders=String.join(",",Collections.nCopies(activeIds.size(),"?"));
                for (String material : jdbc.query("""
                        SELECT DISTINCT m.id,m.name FROM materials m JOIN variant_materials vm ON vm.material_id=m.id
                        WHERE vm.variant_id IN (
                        """ + placeholders + ") ORDER BY m.id LIMIT 50" + (lock ? " FOR UPDATE" : ""),
                        (rs,n) -> rs.getString("name"),activeIds.toArray())) text.append('\n').append(material);
            }
        }
        String bounded = text.length() > GeminiEmbeddingClient.MAX_TEXT_CHARS ? text.substring(0, GeminiEmbeddingClient.MAX_TEXT_CHARS) : text.toString();
        return new Document(id, active, bounded, fingerprint(row.get("status") + "\n" + VectorSettings.MODEL + "\n" + bounded));
    }

    static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
