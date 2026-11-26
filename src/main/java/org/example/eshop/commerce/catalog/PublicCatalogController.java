package org.example.eshop.commerce.catalog;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.InventoryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/catalog")
public class PublicCatalogController {
    private static final String ACTIVE_VARIANT = "v.id=(SELECT MIN(v2.id) FROM product_variants v2 WHERE v2.product_id=p.id AND v2.status='ACTIVE')";
    private static final String CARD_FROM = " FROM products p JOIN categories c ON c.id=p.category_id JOIN product_variants v ON " + ACTIVE_VARIANT;
    private static final String CARD_SELECT = "SELECT p.id,p.slug,p.name,p.short_description,c.name AS category_name,c.slug AS category_slug,v.id AS variant_id,v.sku,v.price,v.compare_at_price," +
            "(SELECT i.url FROM product_images i WHERE i.product_id=p.id AND (i.variant_id=v.id OR i.variant_id IS NULL) ORDER BY CASE WHEN i.variant_id=v.id THEN 0 ELSE 1 END,i.sort_order,i.id LIMIT 1) AS image_url";
    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryPort inventory;

    public PublicCatalogController(NamedParameterJdbcTemplate jdbc, InventoryPort inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    public record Category(Long id, String name, String slug) {}
    public record ProductCard(Long id, String slug, String name, String shortDescription, String categoryName,
                              String categorySlug, Long variantId, String sku, String price, String compareAtPrice,
                              String imageUrl, int availableQuantity) {}
    public record CatalogPage(List<ProductCard> items, int page, int size, long totalElements, int totalPages) {}
    public record ProductImage(String url, String altText) {}
    public record Specification(String name, String value, String unit) {}
    public record Variant(Long id, String sku, String name, String colorName, String colorHex, String sizeLabel,
                          BigDecimal widthMm, BigDecimal depthMm, BigDecimal heightMm, BigDecimal weightKg,
                          String price, String compareAtPrice, int leadTimeDays, int availableQuantity,
                          List<String> materials) {}
    public record ProductDetail(Long id, String slug, String name, String shortDescription, String description,
                                String careInstructions, String categoryName, String categorySlug, String brandName,
                                int warrantyMonths, boolean assemblyRequired, List<ProductImage> images,
                                List<Specification> specifications, List<Variant> variants) {}

    private static String money(BigDecimal amount) { return amount == null ? null : amount.setScale(2).toPlainString(); }

    @GetMapping("/categories")
    public List<Category> categories(@RequestParam(defaultValue = "") String room) {
        room = room == null ? "" : room.trim();
        if (!room.isEmpty() && !Set.of("phong-khach", "phong-an", "phong-ngu").contains(room))
            throw CommerceException.badRequest("INVALID_ROOM", "Unsupported room.");
        return jdbc.query("SELECT c.id,c.name,c.slug FROM categories c WHERE c.is_active=1 AND EXISTS(SELECT 1 FROM products p JOIN product_variants v ON v.product_id=p.id AND v.status='ACTIVE' WHERE p.category_id=c.id AND p.status='ACTIVE' AND (:room='' OR EXISTS(SELECT 1 FROM product_rooms pr JOIN rooms r ON r.id=pr.room_id WHERE pr.product_id=p.id AND r.is_active=1 AND (r.slug=:room OR r.slug=CONCAT('demo-',:room))))) ORDER BY c.sort_order,c.name", Map.of("room", room),
                (rs, row) -> new Category(rs.getLong("id"), rs.getString("name"), rs.getString("slug")));
    }

    @GetMapping("/products")
    public CatalogPage products(@RequestParam(defaultValue = "") String query,
                                @RequestParam(defaultValue = "") String category,
                                @RequestParam(defaultValue = "") String room,
                                @RequestParam(defaultValue = "newest") String sort,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "12") int size) {
        if (page < 0 || size < 1 || size > 100) throw CommerceException.badRequest("INVALID_PAGE", "Invalid catalog page.");
        query = query == null ? "" : query.trim();
        category = category == null ? "" : category.trim();
        room = room == null ? "" : room.trim();
        if (query.length() > 200 || category.length() > 180) throw CommerceException.badRequest("INVALID_FILTER", "Catalog filter is too long.");
        if (!room.isEmpty() && !Set.of("phong-khach", "phong-an", "phong-ngu").contains(room))
            throw CommerceException.badRequest("INVALID_ROOM", "Unsupported room.");
        String order = switch (sort) {
            case "newest" -> "p.id DESC";
            case "price_asc" -> "v.price ASC,p.id DESC";
            case "price_desc" -> "v.price DESC,p.id DESC";
            case "name" -> "p.name ASC,p.id DESC";
            default -> throw CommerceException.badRequest("INVALID_SORT", "Unsupported catalog sort.");
        };
        String where = " WHERE p.status='ACTIVE' AND c.is_active=1 AND (:category='' OR c.slug=:category)" +
                " AND (:room='' OR EXISTS(SELECT 1 FROM product_rooms pr JOIN rooms r ON r.id=pr.room_id WHERE pr.product_id=p.id AND r.is_active=1 AND (r.slug=:room OR r.slug=CONCAT('demo-',:room))))" +
                " AND (:query='' OR p.name LIKE :pattern ESCAPE '=' OR v.sku LIKE :pattern ESCAPE '=' OR p.short_description LIKE :pattern ESCAPE '=')";
        String pattern = "%" + query.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
        var args = new MapSqlParameterSource().addValue("query", query).addValue("pattern", pattern)
                .addValue("category", category).addValue("room", room).addValue("size", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + CARD_FROM + where, args, Long.class);
        var rows = jdbc.query(CARD_SELECT + CARD_FROM + where + " ORDER BY " + order + " LIMIT :size OFFSET :offset", args,
                (rs, row) -> new ProductCard(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                        rs.getString("short_description"), rs.getString("category_name"), rs.getString("category_slug"),
                        rs.getLong("variant_id"), rs.getString("sku"), money(rs.getBigDecimal("price")),
                        money(rs.getBigDecimal("compare_at_price")), rs.getString("image_url"), 0));
        Set<Long> variantIds = rows.stream().map(ProductCard::variantId).collect(Collectors.toSet());
        Map<Long, Integer> quantities = inventory.availableQuantities(variantIds);
        var items = rows.stream().map(item -> new ProductCard(item.id(), item.slug(), item.name(), item.shortDescription(),
                item.categoryName(), item.categorySlug(), item.variantId(), item.sku(), item.price(),
                item.compareAtPrice(), item.imageUrl(), quantities.getOrDefault(item.variantId(), 0))).toList();
        long count = total == null ? 0 : total;
        return new CatalogPage(items, page, size, count, (int) ((count + size - 1) / size));
    }

    @GetMapping("/products/{slug}")
    public ProductDetail product(@PathVariable String slug) {
        var products = jdbc.query("SELECT p.id,p.slug,p.name,p.short_description,p.description,p.care_instructions,p.warranty_months,p.assembly_required,c.name AS category_name,c.slug AS category_slug,b.name AS brand_name FROM products p JOIN categories c ON c.id=p.category_id LEFT JOIN brands b ON b.id=p.brand_id WHERE p.slug=:slug AND p.status='ACTIVE' AND c.is_active=1", Map.of("slug", slug),
                (rs, row) -> new ProductDetail(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                        rs.getString("short_description"), rs.getString("description"), rs.getString("care_instructions"),
                        rs.getString("category_name"), rs.getString("category_slug"), rs.getString("brand_name"),
                        rs.getInt("warranty_months"), rs.getBoolean("assembly_required"), List.of(), List.of(), List.of()));
        if (products.isEmpty()) throw CommerceException.notFound("PRODUCT_NOT_FOUND", "Product not found.");
        var p = products.get(0);
        var args = Map.of("id", p.id());
        var images = jdbc.query("SELECT url,alt_text FROM product_images WHERE product_id=:id ORDER BY sort_order,id", args,
                (rs, row) -> new ProductImage(rs.getString("url"), rs.getString("alt_text")));
        var specs = jdbc.query("SELECT name,value,unit FROM product_specifications WHERE product_id=:id ORDER BY sort_order,id", args,
                (rs, row) -> new Specification(rs.getString("name"), rs.getString("value"), rs.getString("unit")));
        var rows = jdbc.query("SELECT id,sku,name,color_name,color_hex,size_label,width_mm,depth_mm,height_mm,weight_kg,price,compare_at_price,lead_time_days FROM product_variants WHERE product_id=:id AND status='ACTIVE' ORDER BY id", args,
                (rs, row) -> new Variant(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                        rs.getString("color_name"), rs.getString("color_hex"), rs.getString("size_label"),
                        rs.getBigDecimal("width_mm"), rs.getBigDecimal("depth_mm"), rs.getBigDecimal("height_mm"),
                        rs.getBigDecimal("weight_kg"), money(rs.getBigDecimal("price")),
                        money(rs.getBigDecimal("compare_at_price")), rs.getInt("lead_time_days"), 0, List.of()));
        var quantities = inventory.availableQuantities(rows.stream().map(Variant::id).collect(Collectors.toSet()));
        Map<Long, List<String>> materials = new HashMap<>();
        var materialRows = jdbc.query("SELECT vm.variant_id,m.name FROM variant_materials vm JOIN materials m ON m.id=vm.material_id JOIN product_variants v ON v.id=vm.variant_id WHERE v.product_id=:id AND m.is_active=1 ORDER BY m.name", args,
                (rs, row) -> Map.entry(rs.getLong("variant_id"), rs.getString("name")));
        materialRows.forEach(entry -> materials.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        var variants = rows.stream().map(v -> new Variant(v.id(), v.sku(), v.name(), v.colorName(), v.colorHex(),
                v.sizeLabel(), v.widthMm(), v.depthMm(), v.heightMm(), v.weightKg(), v.price(), v.compareAtPrice(),
                v.leadTimeDays(), quantities.getOrDefault(v.id(), 0), materials.getOrDefault(v.id(), List.of()))).toList();
        return new ProductDetail(p.id(), p.slug(), p.name(), p.shortDescription(), p.description(), p.careInstructions(),
                p.categoryName(), p.categorySlug(), p.brandName(), p.warrantyMonths(), p.assemblyRequired(), images,
                specs, variants);
    }
}
