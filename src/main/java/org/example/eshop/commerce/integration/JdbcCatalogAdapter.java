package org.example.eshop.commerce.integration;

import org.example.eshop.commerce.port.CatalogPort;
import org.example.eshop.commerce.port.VariantSnapshot;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashMap;

@Repository
@Transactional(readOnly=true)
public class JdbcCatalogAdapter implements CatalogPort {
    private static final String SELECT="""
        SELECT v.id,v.product_id,v.sku,p.name AS product_name,v.name AS variant_name,v.price,
               (SELECT i.url FROM product_images i WHERE i.product_id=p.id AND (i.variant_id=v.id OR i.variant_id IS NULL)
                ORDER BY CASE WHEN i.variant_id=v.id THEN 0 ELSE 1 END,i.sort_order,i.id LIMIT 1) AS image_url,
               (v.status='ACTIVE' AND p.status='ACTIVE' AND c.is_active=1) AS active
        FROM product_variants v JOIN products p ON p.id=v.product_id JOIN categories c ON c.id=p.category_id
        """;
    private static final RowMapper<VariantSnapshot> MAPPER=(rs,row) -> new VariantSnapshot(rs.getLong("id"),rs.getLong("product_id"),rs.getString("sku"),rs.getString("product_name"),rs.getString("variant_name"),rs.getBigDecimal("price"),rs.getString("image_url"),rs.getBoolean("active"));
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcCatalogAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Map<Long,VariantSnapshot> findVariants(Set<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        var result=new LinkedHashMap<Long,VariantSnapshot>();
        jdbc.query(SELECT+" WHERE v.id IN (:ids)",Map.of("ids",ids),MAPPER).forEach(variant -> result.put(variant.id(),variant));
        return result;
    }
    public record VariantPage(List<VariantSnapshot> items,long totalElements) {}
    public VariantPage search(String query,int page,int size) {
        if(page<0 || size<1 || size>100) throw org.example.eshop.commerce.common.CommerceException.badRequest("INVALID_PAGE","Page must be nonnegative and size must be between 1 and 100.");
        String pattern="%"+query.replace("=","==").replace("%","=%").replace("_","=_")+"%";
        String where=" WHERE v.status='ACTIVE' AND p.status='ACTIVE' AND c.is_active=1 AND (v.sku LIKE :pattern ESCAPE '=' OR p.name LIKE :pattern ESCAPE '=' OR v.name LIKE :pattern ESCAPE '=')";
        var parameters=Map.of("pattern",(Object)pattern,"size",size,"offset",(long)page*size);
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM product_variants v JOIN products p ON p.id=v.product_id JOIN categories c ON c.id=p.category_id"+where,parameters,Long.class);
        return new VariantPage(jdbc.query(SELECT+where+" ORDER BY v.id LIMIT :size OFFSET :offset",parameters,MAPPER),total==null ? 0 : total);
    }
}
