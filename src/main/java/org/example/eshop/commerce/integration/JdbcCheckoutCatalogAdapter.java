package org.example.eshop.commerce.integration;

import org.example.eshop.commerce.port.CheckoutCatalogPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@Repository
@Transactional(readOnly=true)
public class JdbcCheckoutCatalogAdapter implements CheckoutCatalogPort {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcCheckoutCatalogAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Map<Long,Product> findProducts(Set<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        var materials=new LinkedHashMap<Long,StringJoiner>();
        jdbc.query("""
            SELECT vm.variant_id,vm.component_name,m.name
            FROM variant_materials vm JOIN materials m ON m.id=vm.material_id
            WHERE vm.variant_id IN (:ids) ORDER BY vm.variant_id,vm.component_name,m.name,m.id
            """,Map.of("ids",ids),rs -> {
                materials.computeIfAbsent(rs.getLong("variant_id"),id -> new StringJoiner("; "))
                    .add(rs.getString("component_name")+": "+rs.getString("name"));
            });
        var products=new LinkedHashMap<Long,Product>();
        jdbc.query("""
            SELECT v.id,v.product_id,v.sku,p.name AS product_name,v.name AS variant_name,v.color_name,
                   v.width_mm,v.depth_mm,v.height_mm,v.price,p.warranty_months,p.assembly_required,
                   (v.status='ACTIVE' AND p.status='ACTIVE' AND c.is_active=1) AS active,
                   (SELECT i.url FROM product_images i
                    WHERE i.product_id=p.id AND (i.variant_id=v.id OR i.variant_id IS NULL)
                    ORDER BY CASE WHEN i.variant_id=v.id THEN 0 ELSE 1 END,i.sort_order,i.id LIMIT 1) AS image_url
            FROM product_variants v JOIN products p ON p.id=v.product_id JOIN categories c ON c.id=p.category_id WHERE v.id IN (:ids) ORDER BY v.id
            """,Map.of("ids",ids),rs -> {
                long id=rs.getLong("id");
                var dimensions=new StringJoiner(" × ");
                dimension(dimensions,"W",rs.getBigDecimal("width_mm"));
                dimension(dimensions,"D",rs.getBigDecimal("depth_mm"));
                dimension(dimensions,"H",rs.getBigDecimal("height_mm"));
                products.put(id,new Product(id,rs.getLong("product_id"),rs.getString("sku"),rs.getString("product_name"),rs.getString("variant_name"),rs.getString("image_url"),rs.getString("color_name"),dimensions.length()==0 ? null : dimensions.toString(),materials.containsKey(id) ? materials.get(id).toString() : null,rs.getInt("warranty_months"),rs.getBoolean("assembly_required"),rs.getBigDecimal("price"),rs.getBoolean("active")));
            });
        return products;
    }
    private static void dimension(StringJoiner target,String label,BigDecimal value) {
        if(value!=null) target.add(label+" "+value.setScale(2).toPlainString()+" mm");
    }
}
