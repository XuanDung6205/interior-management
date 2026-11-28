package org.example.eshop.commerce.catalog;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import static org.example.eshop.commerce.catalog.CatalogRequests.*;

@Service
public class CatalogAdminService {
    private static final Set<String> KINDS=Set.of("categories","rooms","brands","materials","collections");
    private final JdbcTemplate jdbc;
    private final CatalogCache cache;
    private final Tv5Cache tv5;
    private final CurrentActorPort actor;
    private final ObjectMapper mapper;
    public CatalogAdminService(JdbcTemplate jdbc,CatalogCache cache,Tv5Cache tv5,CurrentActorPort actor,ObjectMapper mapper) {
        this.jdbc=jdbc; this.cache=cache; this.tv5=tv5; this.actor=actor; this.mapper=mapper;
    }
    public Map<String,Object> options() {
        var result=new LinkedHashMap<String,Object>();
        for(String kind:List.of("categories","rooms","brands","materials","collections")) {
            var rows=jdbc.queryForList("SELECT * FROM "+kind+" ORDER BY name,id").stream().map(this::camel).toList();
            result.put(kind,rows);
        }
        return result;
    }
    public Object products(String query,int page,int size) {
        if(page<0 || size<1 || size>100 || query.length()>200) throw CommerceException.badRequest("INVALID_PAGE","Bộ lọc hoặc trang không hợp lệ.");
        String pattern="%"+query.trim().replace("=","==").replace("%","=%").replace("_","=_")+"%";
        String where=" WHERE p.name LIKE ? ESCAPE '=' OR p.slug LIKE ? ESCAPE '=' OR EXISTS(SELECT 1 FROM product_variants v WHERE v.product_id=p.id AND v.sku LIKE ? ESCAPE '=')";
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM products p"+where,Long.class,pattern,pattern,pattern);
        return Map.of("items",jdbc.queryForList("SELECT p.id,p.name,p.slug,p.status,p.version,c.name AS categoryName FROM products p JOIN categories c ON c.id=p.category_id"+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",pattern,pattern,pattern,size,(long)page*size),"page",page,"size",size,"totalElements",count,"totalPages",(count+size-1)/size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> product(long id) {
        var rows=jdbc.queryForList("SELECT * FROM products WHERE id=?",id);
        if(rows.isEmpty()) throw CommerceException.notFound("Không tìm thấy sản phẩm.");
        var p=camel(rows.get(0));
        p.put("roomIds",jdbc.queryForList("SELECT room_id FROM product_rooms WHERE product_id=? ORDER BY room_id",Long.class,id));
        p.put("collectionIds",jdbc.queryForList("SELECT collection_id FROM collection_products WHERE product_id=? ORDER BY collection_id",Long.class,id));
        var materials=new HashMap<Long,List<Long>>();
        jdbc.query("SELECT vm.variant_id,vm.material_id FROM variant_materials vm JOIN product_variants v ON v.id=vm.variant_id WHERE v.product_id=?",rs->{ materials.computeIfAbsent(rs.getLong(1),k->new ArrayList<>()).add(rs.getLong(2)); },id);
        p.put("variants",jdbc.queryForList("SELECT * FROM product_variants WHERE product_id=? ORDER BY id",id).stream().map(row->{ var v=camel(row); v.put("materialIds",materials.getOrDefault(((Number)row.get("id")).longValue(),List.of()).stream().distinct().toList()); return v; }).toList());
        p.put("images",jdbc.queryForList("SELECT * FROM product_images WHERE product_id=? ORDER BY sort_order,id",id).stream().map(this::camel).toList());
        p.put("specifications",jdbc.queryForList("SELECT * FROM product_specifications WHERE product_id=? ORDER BY sort_order,id",id).stream().map(this::camel).toList());
        return p;
    }
    private Map<String,Object> camel(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        row.forEach((key,value)->{
            StringBuilder name=new StringBuilder(); boolean upper=false;
            for(char c:key.toCharArray()) if(c=='_') upper=true; else { name.append(upper?Character.toUpperCase(c):c); upper=false; }
            String k=name.toString();
            if(k.equals("isActive")) { k="active"; value=value instanceof Boolean?value:((Number)value).intValue()!=0; }
            if(k.equals("assemblyRequired") && value instanceof Number n) value=n.intValue()!=0;
            if((k.equals("price") || k.equals("compareAtPrice")) && value!=null) value=value.toString();
            // Technical timestamps are not editable and may have driver-specific classes.
            if(!k.endsWith("At")) result.put(k,value);
        });
        return result;
    }
    static void imageUrl(String value) {
        if(value==null || value.isBlank()) return;
        try {
            if(value.chars().anyMatch(c->c<=32 || c==127) || value.contains("\\")) throw new IllegalArgumentException();
            if(value.startsWith("/images/") && !value.contains("..") && !value.contains("%")) return;
            URI url=URI.create(value);
            if(!Set.of("https","http").contains(url.getScheme()) || url.getHost()==null || url.getUserInfo()!=null) throw new IllegalArgumentException();
        } catch(RuntimeException ex) { throw CommerceException.invalid(Map.of("imageUrl","Ảnh phải dùng /images/... hoặc URL HTTP(S) hợp lệ.")); }
    }
    private long insert(String table,Map<String,Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingColumns(values.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
    }
    private void update(String table,long id,Map<String,Object> values) {
        var assignments=new ArrayList<String>(); var args=new ArrayList<Object>();
        values.forEach((key,value)->{assignments.add(key+"=?");args.add(value);}); args.add(id);
        jdbc.update("UPDATE "+table+" SET "+String.join(",",assignments)+" WHERE id=?",args.toArray());
    }
    private void reference(String table,Long id,boolean active) {
        if(id==null) return;
        var rows=jdbc.queryForList("SELECT is_active FROM "+table+" WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty() || (active && !Boolean.TRUE.equals(rows.get(0).get("is_active")) && !Objects.equals(rows.get(0).get("is_active"),1)))
            throw CommerceException.invalid(Map.of(table,"Tham chiếu không tồn tại hoặc đã ngừng hiển thị."));
    }
    private void audit(long id,String type,String action) {
        jdbc.update("INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,after_data) VALUES(?,?,?,?,?)",
            actor.currentActor().userId(),action,type,String.valueOf(id),mapper.writeValueAsString(Map.of("id",id)));
    }
    @Transactional
    public Map<String,Object> saveTaxonomy(String kind,Long id,Taxonomy t) {
        if(!KINDS.contains(kind)) throw CommerceException.notFound("Loại danh mục không hợp lệ.");
        // Serialize tree edits before examining ancestry: concurrent parent swaps cannot form a cycle.
        var ancestry=kind.equals("categories")?jdbc.queryForList("SELECT id,parent_id,is_active FROM categories ORDER BY id FOR UPDATE"):List.<Map<String,Object>>of();
        if(id!=null && jdbc.queryForObject("SELECT COUNT(*) FROM "+kind+" WHERE id=?",Integer.class,id)==0) throw CommerceException.notFound("Không tìm thấy dữ liệu.");
        imageUrl(t.imageUrl()); imageUrl(t.logoUrl());
        if(kind.equals("categories") && t.parentId()!=null) {
            var parents=new HashMap<Long,Long>();
            ancestry.forEach(r->parents.put(((Number)r.get("id")).longValue(),r.get("parent_id")==null?null:((Number)r.get("parent_id")).longValue()));
            if(!parents.containsKey(t.parentId())) throw CommerceException.invalid(Map.of("parentId","Danh mục cha không tồn tại."));
            var activeParents=new HashMap<Long,Boolean>();
            ancestry.forEach(r->activeParents.put(((Number)r.get("id")).longValue(),Boolean.TRUE.equals(r.get("is_active")) || (r.get("is_active") instanceof Number n && n.intValue()!=0)));
            var visited=new HashSet<Long>(); Long cursor=t.parentId();
            while(cursor!=null) { if(Objects.equals(id,cursor) || !visited.add(cursor)) throw CommerceException.invalid(Map.of("parentId","Không được tạo chu kỳ danh mục.")); if(t.active() && !Boolean.TRUE.equals(activeParents.get(cursor))) throw CommerceException.invalid(Map.of("parentId","Bật hiển thị danh mục cha trước khi bật danh mục con.")); cursor=parents.get(cursor); }
        }
        var values=new LinkedHashMap<String,Object>();
        values.put("name",t.name().trim()); values.put("slug",t.slug()); values.put("description",t.description()); values.put("is_active",t.active());
        if(kind.equals("categories")) values.put("parent_id",t.parentId());
        if(Set.of("categories","rooms","collections").contains(kind)) { values.put("image_url",t.imageUrl()); values.put("sort_order",t.sortOrder()); }
        if(kind.equals("brands")) { values.put("logo_url",t.logoUrl()); values.put("country_code",t.countryCode()); }
        if(kind.equals("materials")) values.put("care_instructions",t.careInstructions());
        if(id==null) id=insert(kind,values); else update(kind,id,values);
        if(kind.equals("categories") && !t.active()) {
            var children=new HashMap<Long,List<Long>>();
            ancestry.forEach(r->{ if(r.get("parent_id")!=null) children.computeIfAbsent(((Number)r.get("parent_id")).longValue(),k->new ArrayList<>()).add(((Number)r.get("id")).longValue()); });
            var queue=new ArrayDeque<Long>(); queue.add(id); var hidden=new HashSet<Long>();
            while(!queue.isEmpty()) { long category=queue.removeFirst(); if(hidden.add(category)) queue.addAll(children.getOrDefault(category,List.of())); }
            jdbc.batchUpdate("UPDATE categories SET is_active=0 WHERE id=?",hidden.stream().map(i->new Object[]{i}).toList());
        }
        audit(id,kind,"CATALOG_TAXONOMY_SAVE");
        changed();
        return camel(jdbc.queryForMap("SELECT * FROM "+kind+" WHERE id=?",id));
    }
    @Transactional
    public Map<String,Object> save(Long id,Product p) {
        boolean published=p.status().equals("ACTIVE");
        if(published && p.variants().stream().noneMatch(v->v.status().equals("ACTIVE"))) throw CommerceException.invalid(Map.of("variants","Sản phẩm đang bán cần ít nhất một SKU hoạt động."));
        if(published && p.images().isEmpty()) throw CommerceException.invalid(Map.of("images","Sản phẩm đang bán cần ít nhất một ảnh."));
        if(id!=null) {
            var versions=jdbc.queryForList("SELECT version FROM products WHERE id=? FOR UPDATE",Long.class,id);
            if(versions.isEmpty()) throw CommerceException.notFound("Không tìm thấy sản phẩm.");
            if(p.version()==null || !versions.get(0).equals(p.version())) throw CommerceException.conflict("CATALOG_STALE","Sản phẩm vừa được người khác sửa. Tải lại trước khi lưu.");
        }
        reference("categories",p.categoryId(),published); reference("brands",p.brandId(),published);
        p.roomIds().stream().distinct().sorted().forEach(i->reference("rooms",i,published));
        p.collectionIds().stream().distinct().sorted().forEach(i->reference("collections",i,published));
        var oldIds=id==null?List.<Long>of():jdbc.queryForList("SELECT id FROM product_variants WHERE product_id=? ORDER BY id FOR UPDATE",Long.class,id);
        var skuSet=new HashSet<String>(); var keySet=new HashSet<String>(); var retained=new HashSet<Long>();
        for(var v:p.variants()) {
            if(!skuSet.add(v.sku().trim().toLowerCase(Locale.ROOT)) || !keySet.add(v.variantKey().trim().toLowerCase(Locale.ROOT))) throw CommerceException.invalid(Map.of("variants","SKU và khóa phiên bản không được trùng nhau."));
            if(v.id()!=null && (!oldIds.contains(v.id()) || !retained.add(v.id()))) throw CommerceException.invalid(Map.of("variants","SKU phải thuộc chính sản phẩm đang sửa và không được lặp."));
            if(v.compareAtPrice()!=null && v.compareAtPrice().compareTo(v.price())<0) throw CommerceException.invalid(Map.of("compareAtPrice","Giá tham chiếu phải lớn hơn hoặc bằng giá bán."));
            v.materialIds().stream().distinct().sorted().forEach(i->reference("materials",i,published && v.status().equals("ACTIVE")));
        }
        for(var image:p.images()) {
            imageUrl(image.url());
            if(image.variantId()!=null && !retained.contains(image.variantId())) throw CommerceException.invalid(Map.of("images","Ảnh SKU phải thuộc sản phẩm và có trong danh sách SKU lưu."));
        }
        var specNames=new HashSet<String>();
        for(var spec:p.specifications()) if(!specNames.add(spec.name().trim().toLowerCase(Locale.ROOT))) throw CommerceException.invalid(Map.of("specifications","Tên thông số không được trùng."));
        var values=new LinkedHashMap<String,Object>();
        values.put("name",p.name().trim()); values.put("slug",p.slug()); values.put("category_id",p.categoryId()); values.put("brand_id",p.brandId()); values.put("status",p.status());
        values.put("short_description",p.shortDescription()); values.put("description",p.description()); values.put("care_instructions",p.careInstructions());
        values.put("warranty_months",p.warrantyMonths()); values.put("assembly_required",p.assemblyRequired()); values.put("seo_title",p.seoTitle()); values.put("seo_description",p.seoDescription());
        if(id==null) id=insert("products",values); else { values.put("version",p.version()+1); update("products",id,values); }
        for(var v:p.variants()) {
            var data=new LinkedHashMap<String,Object>();
            data.put("product_id",id);data.put("sku",v.sku().trim());data.put("variant_key",v.variantKey().trim());data.put("name",v.name().trim());data.put("status",v.status());
            data.put("color_name",v.colorName());data.put("color_hex",v.colorHex());data.put("size_label",v.sizeLabel());data.put("width_mm",v.widthMm());data.put("depth_mm",v.depthMm());data.put("height_mm",v.heightMm());data.put("weight_kg",v.weightKg());data.put("price",v.price());data.put("compare_at_price",v.compareAtPrice());data.put("lead_time_days",v.leadTimeDays());
            long variant=v.id()==null?insert("product_variants",data):v.id();
            if(v.id()!=null) update("product_variants",variant,data);
            // Retain material component names/notes for unchanged associations.
            var materialIds=new HashSet<>(v.materialIds());
            for(long existing:jdbc.queryForList("SELECT DISTINCT material_id FROM variant_materials WHERE variant_id=?",Long.class,variant)) if(!materialIds.contains(existing)) jdbc.update("DELETE FROM variant_materials WHERE variant_id=? AND material_id=?",variant,existing);
            for(long material:materialIds) jdbc.update("INSERT IGNORE INTO variant_materials(variant_id,material_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM variant_materials WHERE variant_id=? AND material_id=?)",variant,material,variant,material);
        }
        for(long old:oldIds) if(!retained.contains(old)) jdbc.update("UPDATE product_variants SET status='INACTIVE' WHERE id=?",old);
        jdbc.update("DELETE FROM product_rooms WHERE product_id=?",id);
        for(long room:new HashSet<>(p.roomIds())) jdbc.update("INSERT INTO product_rooms(product_id,room_id) VALUES(?,?)",id,room);
        jdbc.update("DELETE FROM collection_products WHERE product_id=?",id);
        for(long collection:new HashSet<>(p.collectionIds())) jdbc.update("INSERT INTO collection_products(collection_id,product_id) VALUES(?,?)",collection,id);
        jdbc.update("DELETE FROM product_images WHERE product_id=?",id);
        for(var image:p.images()) jdbc.update("INSERT INTO product_images(product_id,variant_id,url,alt_text,sort_order) VALUES(?,?,?,?,?)",id,image.variantId(),image.url(),image.altText(),image.sortOrder());
        jdbc.update("DELETE FROM product_specifications WHERE product_id=?",id);
        for(var spec:p.specifications()) jdbc.update("INSERT INTO product_specifications(product_id,name,value,unit,sort_order) VALUES(?,?,?,?,?)",id,spec.name().trim(),spec.value(),spec.unit(),spec.sortOrder());
        audit(id,"products","CATALOG_PRODUCT_SAVE");
        // Durable catalog-change event in the same transaction; the existing TV5 worker computes fingerprints.
        jdbc.update("INSERT INTO commerce_vector_reindex_outbox(product_id,action,state,next_attempt_at,requested_by) VALUES(?,'UPSERT','PENDING',UTC_TIMESTAMP(6),?)",id,actor.currentActor().userId());
        changed(); return product(id);
    }
    private void changed() { cache.evictAfterCommit(); tv5.evictAfterCommit(Tv5Cache.RECOMMENDATIONS); }
}
