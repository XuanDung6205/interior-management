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
    private static final String CARD_SELECT = "SELECT p.id,p.slug,p.name,p.short_description,c.name AS category_name,c.slug AS category_slug,v.id AS variant_id,v.sku,v.price,v.compare_at_price," +
            "(SELECT i.url FROM product_images i WHERE i.product_id=p.id AND (i.variant_id=v.id OR i.variant_id IS NULL) ORDER BY CASE WHEN i.variant_id=v.id THEN 0 ELSE 1 END,i.sort_order,i.id LIMIT 1) AS image_url";
    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryPort inventory;
    private final CatalogCache cache;

    public PublicCatalogController(NamedParameterJdbcTemplate jdbc, InventoryPort inventory, CatalogCache cache) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.cache = cache;
    }

    public record Category(Long id, String name, String slug, Long parentId) {}
    public record ProductCard(Long id, String slug, String name, String shortDescription, String categoryName,
                              String categorySlug, Long variantId, String sku, String price, String compareAtPrice,
                              String imageUrl, int availableQuantity) {}
    public record CatalogPage(List<ProductCard> items, int page, int size, long totalElements, int totalPages) {}
    public record ProductImage(String url, String altText, Long variantId) {}
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
        return jdbc.query("SELECT id,name,slug,parent_id FROM categories WHERE is_active=1 ORDER BY sort_order,name", Map.of(),
            (rs,n)->new Category(rs.getLong("id"),rs.getString("name"),rs.getString("slug"),rs.getObject("parent_id",Long.class)));
    }

    public record Facets(List<Category> categories, List<Map<String,Object>> rooms, List<Map<String,Object>> brands,
                         List<Map<String,Object>> materials, List<Map<String,Object>> collections, List<String> colors, List<String> sizes) {}
    @GetMapping("/facets")
    public Facets facets() {
        return cache.read("facets",Facets.class,()->new Facets(categories(""),
            options("rooms"),options("brands"),options("materials"),options("collections"),
            jdbc.queryForList("SELECT DISTINCT v.color_name FROM product_variants v JOIN products p ON p.id=v.product_id JOIN categories c ON c.id=p.category_id WHERE v.status='ACTIVE' AND p.status='ACTIVE' AND c.is_active=1 AND v.color_name IS NOT NULL AND v.color_name<>'' ORDER BY v.color_name",Map.of(),String.class),
            jdbc.queryForList("SELECT DISTINCT v.size_label FROM product_variants v JOIN products p ON p.id=v.product_id JOIN categories c ON c.id=p.category_id WHERE v.status='ACTIVE' AND p.status='ACTIVE' AND c.is_active=1 AND v.size_label IS NOT NULL AND v.size_label<>'' ORDER BY v.size_label",Map.of(),String.class)));
    }
    private List<Map<String,Object>> options(String table) {
        return jdbc.queryForList("SELECT id,name,slug,description FROM "+table+" WHERE is_active=1 ORDER BY name",Map.of());
    }
    @GetMapping("/products")
    public CatalogPage products(@RequestParam Map<String,String> filters) {
        // Canonical key includes every filter and pagination value; availability is always read live below.
        var normalized=new java.util.TreeMap<String,String>();
        filters.forEach((key,value)->normalized.put(key,value.trim()));
        validateFilters(normalized);
        CatalogPage raw=cache.read("list:"+normalized,CatalogPage.class,()->loadProducts(normalized));
        var quantities=inventory.availableQuantities(raw.items().stream().map(ProductCard::variantId).collect(Collectors.toSet()));
        return new CatalogPage(raw.items().stream().map(i->new ProductCard(i.id(),i.slug(),i.name(),i.shortDescription(),i.categoryName(),i.categorySlug(),i.variantId(),i.sku(),i.price(),i.compareAtPrice(),i.imageUrl(),quantities.getOrDefault(i.variantId(),0))).toList(),raw.page(),raw.size(),raw.totalElements(),raw.totalPages());
    }
    private static int integer(Map<String,String> f,String key,int fallback) {
        try { return Integer.parseInt(f.getOrDefault(key,String.valueOf(fallback))); }
        catch (NumberFormatException e) { throw CommerceException.badRequest("INVALID_FILTER","Trang và kích thước trang phải là số nguyên."); }
    }
    private static void validateFilters(Map<String,String> f) {
        var allowed=Set.of("query","category","room","brand","collection","material","color","sizeLabel","sort","page","size","minPrice","maxPrice","minWidth","maxWidth","minDepth","maxDepth","minHeight","maxHeight");
        if(!allowed.containsAll(f.keySet()) || f.getOrDefault("query","").length()>200) throw CommerceException.badRequest("INVALID_FILTER","Bộ lọc không được hỗ trợ hoặc từ khóa quá dài.");
        int page=integer(f,"page",0),size=integer(f,"size",12);
        if(page<0 || size<1 || size>100) throw CommerceException.badRequest("INVALID_PAGE","Trang không hợp lệ; tối đa 100 sản phẩm/trang.");
        for(var e:f.entrySet()) if(e.getValue().length()>220) throw CommerceException.badRequest("INVALID_FILTER","Bộ lọc quá dài.");
        for(String dimension:List.of("Price","Width","Depth","Height")) {
            BigDecimal min=decimal(f,"min"+dimension),max=decimal(f,"max"+dimension);
            if(min!=null && max!=null && min.compareTo(max)>0) throw CommerceException.badRequest("INVALID_FILTER","Khoảng lọc từ phải nhỏ hơn hoặc bằng đến.");
        }
        if(!Set.of("newest","price_asc","price_desc","name").contains(f.getOrDefault("sort","newest"))) throw CommerceException.badRequest("INVALID_SORT","Cách sắp xếp không hợp lệ.");
    }
    private static BigDecimal decimal(Map<String,String> f,String key) {
        if(f.getOrDefault(key,"").isEmpty()) return null;
        try {
            var n=new BigDecimal(f.get(key));
            if(n.signum()<0 || n.scale()>2 || n.compareTo(new BigDecimal("9999999999999.99"))>0) throw new NumberFormatException();
            return n;
        } catch(NumberFormatException ex) { throw CommerceException.badRequest("INVALID_FILTER","Giá và số đo phải là số không âm, tối đa 2 chữ số thập phân."); }
    }
    private CatalogPage loadProducts(Map<String,String> f) {
        int page=integer(f,"page",0),size=integer(f,"size",12);
        var args=new MapSqlParameterSource().addValue("size",size).addValue("offset",(long)page*size);
        StringBuilder sku=new StringBuilder("v2.product_id=p.id AND v2.status='ACTIVE'");
        String query=f.getOrDefault("query","");
        args.addValue("query",query).addValue("pattern","%"+query.replace("=","==").replace("%","=%").replace("_","=_")+"%");
        sku.append(" AND (:query='' OR p.name LIKE :pattern ESCAPE '=' OR p.short_description LIKE :pattern ESCAPE '=' OR v2.sku LIKE :pattern ESCAPE '=')");
        for(String dimension:List.of("Price","Width","Depth","Height")) {
            String col=dimension.equals("Price")?"price":dimension.toLowerCase()+"_mm";
            for(String bound:List.of("min","max")) {
                var n=decimal(f,bound+dimension);
                if(n!=null) { sku.append(" AND v2.").append(col).append(bound.equals("min")?">=:":"<=:").append(bound+dimension); args.addValue(bound+dimension,n); }
            }
        }
        for(String key:List.of("color","sizeLabel")) if(!f.getOrDefault(key,"").isEmpty()) {
            sku.append(" AND v2.").append(key.equals("color")?"color_name":"size_label").append("=:").append(key); args.addValue(key,f.get(key));
        }
        if(!f.getOrDefault("material","").isEmpty()) {
            sku.append(" AND EXISTS(SELECT 1 FROM variant_materials vm JOIN materials m ON m.id=vm.material_id WHERE vm.variant_id=v2.id AND m.is_active=1 AND m.slug=:material)"); args.addValue("material",f.get("material"));
        }
        StringBuilder where=new StringBuilder(" WHERE p.status='ACTIVE' AND c.is_active=1");
        String cte="WITH RECURSIVE allowed_categories AS (SELECT id FROM categories WHERE slug=:category AND is_active=1 UNION ALL SELECT c.id FROM categories c JOIN allowed_categories ac ON c.parent_id=ac.id WHERE c.is_active=1) ";
        String category=f.getOrDefault("category","");
        if(!category.isEmpty()) { where.append(" AND p.category_id IN(SELECT id FROM allowed_categories)"); args.addValue("category",category); } else cte="";
        for(String key:List.of("room","collection")) if(!f.getOrDefault(key,"").isEmpty()) {
            String table=key.equals("room")?"rooms":"collections",link=key.equals("room")?"product_rooms":"collection_products",fk=key.equals("room")?"room_id":"collection_id";
            where.append(" AND EXISTS(SELECT 1 FROM "+link+" l JOIN "+table+" t ON t.id=l."+fk+" WHERE l.product_id=p.id AND t.is_active=1 AND (t.slug=:"+key+" OR t.slug=CONCAT('demo-',:"+key+")))"); args.addValue(key,f.get(key));
        }
        if(!f.getOrDefault("brand","").isEmpty()) { where.append(" AND EXISTS(SELECT 1 FROM brands b WHERE b.id=p.brand_id AND b.is_active=1 AND b.slug=:brand)"); args.addValue("brand",f.get("brand")); }
        String order=switch(f.getOrDefault("sort","newest")) { case "price_asc"->"v.price,p.id DESC";case "price_desc"->"v.price DESC,p.id DESC";case "name"->"p.name,p.id DESC";default->"p.id DESC"; };
        // Select the cheapest matching SKU, so displayed price and all filters describe the same SKU.
        String from=" FROM products p JOIN categories c ON c.id=p.category_id JOIN product_variants v ON v.id=(SELECT v2.id FROM product_variants v2 WHERE "+sku+" ORDER BY v2.price,v2.id LIMIT 1)";
        long count=jdbc.queryForObject(cte+"SELECT COUNT(*)"+from+where,args,Long.class);
        var rows=jdbc.query(cte+CARD_SELECT+from+where+" ORDER BY "+order+" LIMIT :size OFFSET :offset",args,
            (rs,n)->new ProductCard(rs.getLong("id"),rs.getString("slug"),rs.getString("name"),rs.getString("short_description"),rs.getString("category_name"),rs.getString("category_slug"),rs.getLong("variant_id"),rs.getString("sku"),money(rs.getBigDecimal("price")),money(rs.getBigDecimal("compare_at_price")),rs.getString("image_url"),0));
        return new CatalogPage(rows,page,size,count,(int)((count+size-1)/size));
    }

    @GetMapping("/products/{slug}")
    public ProductDetail product(@PathVariable String slug) {
        if(slug.length()>220) throw CommerceException.notFound("PRODUCT_NOT_FOUND","Không tìm thấy sản phẩm.");
        var p=cache.read("detail:"+slug,ProductDetail.class,()->loadProduct(slug));
        if(p.variants().isEmpty()) throw CommerceException.notFound("PRODUCT_NOT_FOUND","Sản phẩm chưa có SKU đang bán.");
        var quantities=inventory.availableQuantities(p.variants().stream().map(Variant::id).collect(Collectors.toSet()));
        var variants=p.variants().stream().map(v->new Variant(v.id(),v.sku(),v.name(),v.colorName(),v.colorHex(),v.sizeLabel(),v.widthMm(),v.depthMm(),v.heightMm(),v.weightKg(),v.price(),v.compareAtPrice(),v.leadTimeDays(),quantities.getOrDefault(v.id(),0),v.materials())).toList();
        return new ProductDetail(p.id(),p.slug(),p.name(),p.shortDescription(),p.description(),p.careInstructions(),p.categoryName(),p.categorySlug(),p.brandName(),p.warrantyMonths(),p.assemblyRequired(),p.images(),p.specifications(),variants);
    }
    private ProductDetail loadProduct(String slug) {
        var products = jdbc.query("SELECT p.id,p.slug,p.name,p.short_description,p.description,p.care_instructions,p.warranty_months,p.assembly_required,c.name AS category_name,c.slug AS category_slug,b.name AS brand_name FROM products p JOIN categories c ON c.id=p.category_id LEFT JOIN brands b ON b.id=p.brand_id WHERE p.slug=:slug AND p.status='ACTIVE' AND c.is_active=1", Map.of("slug", slug),
                (rs, row) -> new ProductDetail(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                        rs.getString("short_description"), rs.getString("description"), rs.getString("care_instructions"),
                        rs.getString("category_name"), rs.getString("category_slug"), rs.getString("brand_name"),
                        rs.getInt("warranty_months"), rs.getBoolean("assembly_required"), List.of(), List.of(), List.of()));
        if (products.isEmpty()) throw CommerceException.notFound("PRODUCT_NOT_FOUND", "Product not found.");
        var p = products.get(0);
        var args = Map.of("id", p.id());
        var images = jdbc.query("SELECT i.url,i.alt_text,i.variant_id FROM product_images i WHERE i.product_id=:id AND (i.variant_id IS NULL OR EXISTS(SELECT 1 FROM product_variants v WHERE v.id=i.variant_id AND v.status='ACTIVE')) ORDER BY i.sort_order,i.id", args,
                (rs, row) -> new ProductImage(rs.getString("url"), rs.getString("alt_text"), rs.getObject("variant_id",Long.class)));
        var specs = jdbc.query("SELECT name,value,unit FROM product_specifications WHERE product_id=:id ORDER BY sort_order,id", args,
                (rs, row) -> new Specification(rs.getString("name"), rs.getString("value"), rs.getString("unit")));
        var rows = jdbc.query("SELECT id,sku,name,color_name,color_hex,size_label,width_mm,depth_mm,height_mm,weight_kg,price,compare_at_price,lead_time_days FROM product_variants WHERE product_id=:id AND status='ACTIVE' ORDER BY id", args,
                (rs, row) -> new Variant(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                        rs.getString("color_name"), rs.getString("color_hex"), rs.getString("size_label"),
                        rs.getBigDecimal("width_mm"), rs.getBigDecimal("depth_mm"), rs.getBigDecimal("height_mm"),
                        rs.getBigDecimal("weight_kg"), money(rs.getBigDecimal("price")),
                        money(rs.getBigDecimal("compare_at_price")), rs.getInt("lead_time_days"), 0, List.of()));
        Map<Long,Integer> quantities = Map.of();
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
