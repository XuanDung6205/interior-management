package org.example.eshop.commerce.cart;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.*;
import org.example.eshop.commerce.tracking.CommerceTrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DbCartService {
    private static final int MAX_QUANTITY=1_000_000;
    private final CartRepository carts;
    private final CartItemRepository items;
    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final CatalogPort catalog;
    private final InventoryPort inventory;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;
    @Autowired(required = false) private CommerceTrackingService tracking;
    public DbCartService(CartRepository carts,CartItemRepository items,JdbcTemplate jdbc,CurrentActorPort actors,CatalogPort catalog,InventoryPort inventory,Clock clock){this.carts=carts;this.items=items;this.jdbc=jdbc;this.actors=actors;this.catalog=catalog;this.inventory=inventory;this.clock=clock;}

    @Transactional(readOnly=true) public CartView get(){
        CommerceActor actor=actors.currentActor();
        CartEntity cart=active(actor).filter(this::usable).orElse(null);
        return view(cart);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CartView add(Long variantId,int quantity){
        validateQuantity(quantity);if(variantId==null || variantId<=0)throw CommerceException.badRequest("INVALID_VARIANT","SKU không hợp lệ.");
        CartEntity cart=lockedActive(actors.currentActor());
        var existing=items.findByCartIdAndVariantId(cart.getId(),variantId);
        int wanted=checkedSum(existing.map(CartItemEntity::getQuantity).orElse(0),quantity);
        checkIncrease(variantId,wanted);
        CartItemEntity item=existing.orElseGet(()->{var line=new CartItemEntity();line.setCartId(cart.getId());line.setVariantId(variantId);line.setCreatedAt(now());return line;});
        item.setQuantity(wanted);item.setUpdatedAt(now());items.saveAndFlush(item);touch(cart);
        // Ghi sự kiện ADD_TO_CART xác nhận từ server sau khi lưu giỏ thành công (fail-safe ko ảnh hưởng luồng giỏ)
        if (tracking != null) {
            VariantSnapshot v = catalog.findVariants(Set.of(variantId)).get(variantId);
            tracking.recordServerEvent("ADD_TO_CART", "cart-" + cart.getId(), actors.currentActor().userId(),
                    v == null ? null : v.productId(), v == null ? null : variantId, null, quantity);
        }
        return view(cart);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CartView update(Long itemId,int quantity){
        validateQuantity(quantity);CartEntity cart=existingLocked(actors.currentActor());
        CartItemEntity item=ownedItem(cart,itemId);
        if(quantity>item.getQuantity())checkIncrease(item.getVariantId(),quantity);
        item.setQuantity(quantity);item.setUpdatedAt(now());items.saveAndFlush(item);touch(cart);return view(cart);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CartView remove(Long itemId){
        CartEntity cart=existingLocked(actors.currentActor());CartItemEntity item=ownedItem(cart,itemId);
        items.delete(item);items.flush();touch(cart);return view(cart);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CartView clear(){
        var current=active(actors.currentActor());if(current.isEmpty())return view(null);
        CartEntity cart=refreshLocked(current.get().getId());
        if(!usable(cart))cart=null;
        if(cart==null)return view(null);items.deleteCartItems(cart.getId());touch(cart);return view(cart);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CartView merge(){
        CommerceActor actor=actors.currentActor();
        if(actor.userId()==null)throw CommerceException.forbidden("Bạn cần đăng nhập để gộp giỏ.");
        CartEntity guest=actor.guestHash()==null?null:carts.findByActiveGuestHash(actor.guestHash()).orElse(null);
        if(guest==null || !usable(guest))return view(active(actor).filter(this::usable).orElse(null));
        CartEntity userCart=active(actor).orElseGet(()->insertActive(actor));
        Map<Long,CartEntity> locked=new HashMap<>();
        for(Long id:List.of(guest.getId(),userCart.getId()).stream().sorted().toList())locked.put(id,refreshLocked(id));
        guest=locked.get(guest.getId());userCart=locked.get(userCart.getId());
        if(!usable(guest))return view(userCart);
        for(CartItemEntity incoming:items.lockedCartItems(guest.getId())){
            CartItemEntity target=items.findByCartIdAndVariantId(userCart.getId(),incoming.getVariantId()).orElse(null);
            if(target==null){target=new CartItemEntity();target.setCartId(userCart.getId());target.setVariantId(incoming.getVariantId());target.setQuantity(0);target.setCreatedAt(now());}
            target.setQuantity(checkedSum(target.getQuantity(),incoming.getQuantity()));target.setUpdatedAt(now());items.saveAndFlush(target);
        }
        items.deleteCartItems(guest.getId());guest.setStatus("CONVERTED");guest.setUpdatedAt(now());carts.saveAndFlush(guest);touch(userCart);
        return view(userCart);
    }
    private CartEntity ownedCart(CommerceActor actor){return active(actor).orElseThrow(()->CommerceException.notFound("Không tìm thấy dòng hàng trong giỏ của bạn."));}
    private CartEntity existingLocked(CommerceActor actor){
        CartEntity found=ownedCart(actor);
        CartEntity current=refreshLocked(found.getId());
        if(!usable(current))throw CommerceException.notFound("Giỏ hàng không còn hoạt động.");
        return current;
    }
    private CartItemEntity ownedItem(CartEntity cart,Long itemId){return items.lockOwnedItem(itemId,cart.getId()).orElseThrow(()->CommerceException.notFound("Không tìm thấy dòng hàng trong giỏ của bạn."));}
    private CartEntity refreshLocked(Long id){
        CartEntity cart=carts.lockById(id).orElseThrow(()->CommerceException.notFound("Giỏ không còn tồn tại."));
        entityManager.refresh(cart,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return cart;
    }
    private Optional<CartEntity> active(CommerceActor actor){
        if(actor.userId()!=null)return carts.findByActiveUserId(actor.userId());
        if(actor.guestHash()==null)throw CommerceException.forbidden("Không xác định được chủ giỏ hàng.");
        return carts.findByActiveGuestHash(actor.guestHash());
    }
    private CartEntity insertActive(CommerceActor actor){
        LocalDateTime time=now();
        jdbc.update("INSERT INTO carts(user_id,guest_token_hash,status,expires_at,created_at,updated_at) VALUES(?,?,'ACTIVE',?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",actor.userId(),actor.userId()==null?actor.guestHash():null,actor.userId()==null?time.plusDays(30):null,time,time);
        return active(actor).orElseThrow(()->CommerceException.conflict("CART_BUSY","Giỏ đang thay đổi. Vui lòng thử lại."));
    }
    private CartEntity lockedActive(CommerceActor actor){
        // The upsert obtains the unique owner lock before any item is read. This
        // handles both first-cart creation races and concurrent increments.
        CartEntity candidate=insertActive(actor);
        CartEntity locked=refreshLocked(candidate.getId());
        if(!usable(locked)){
            locked.setStatus("ABANDONED");locked.setUpdatedAt(now());carts.saveAndFlush(locked);
            candidate=insertActive(actor);locked=refreshLocked(candidate.getId());
        }
        return locked;
    }
    private boolean usable(CartEntity cart){return "ACTIVE".equals(cart.getStatus()) && (cart.getExpiresAt()==null || cart.getExpiresAt().isAfter(now()));}
    private void touch(CartEntity cart){cart.setUpdatedAt(now());carts.saveAndFlush(cart);}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private void validateQuantity(int quantity){if(quantity<1 || quantity>MAX_QUANTITY)throw CommerceException.badRequest("INVALID_QUANTITY","Số lượng phải từ 1 đến "+MAX_QUANTITY+".");}
    private int checkedSum(int first,int second){long sum=(long)first+second;if(sum>MAX_QUANTITY)throw CommerceException.conflict("QUANTITY_LIMIT","Số lượng sau khi gộp quá lớn. Hãy giảm số lượng trước.");return (int)sum;}
    private void checkIncrease(Long variantId,int quantity){
        VariantSnapshot variant=catalog.findVariants(Set.of(variantId)).get(variantId);
        if(variant==null || !variant.active())throw CommerceException.conflict("UNAVAILABLE_VARIANT","SKU không còn được bán.");
        int available=inventory.availableQuantities(Set.of(variantId)).getOrDefault(variantId,0);
        if(quantity>available)throw CommerceException.conflict("INSUFFICIENT_STOCK","SKU hiện chỉ còn "+available+" sản phẩm. Giỏ hàng chưa giữ tồn kho.");
    }
    private String money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP).toPlainString();}
    private CartView view(CartEntity cart){
        if(cart==null)return new CartView(null,0,"0.00","VND",List.of(),List.of());
        List<CartItemEntity> rows=TransactionSynchronizationManager.isCurrentTransactionReadOnly()?items.findByCartIdOrderById(cart.getId()):items.lockedCartItems(cart.getId());
        Set<Long> ids=rows.stream().map(CartItemEntity::getVariantId).collect(Collectors.toSet());
        Map<Long,VariantSnapshot> variants=ids.isEmpty()?Map.of():catalog.findVariants(ids);
        Map<Long,Integer> stocks=ids.isEmpty()?Map.of():inventory.availableQuantities(ids);
        List<CartView.Line> lines=new ArrayList<>();List<CartView.Warning> warnings=new ArrayList<>();
        BigDecimal subtotal=BigDecimal.ZERO;long quantity=0;
        for(CartItemEntity row:rows){
            VariantSnapshot v=variants.get(row.getVariantId());int available=Math.max(0,stocks.getOrDefault(row.getVariantId(),0));
            boolean active=v!=null && v.active();List<String> issues=new ArrayList<>();
            if(!active){issues.add("UNAVAILABLE_VARIANT");warnings.add(new CartView.Warning("UNAVAILABLE_VARIANT","Một SKU trong giỏ đã ngừng bán.",row.getId()));}
            if(available==0){issues.add("OUT_OF_STOCK");warnings.add(new CartView.Warning("OUT_OF_STOCK","SKU đã hết hàng.",row.getId()));}
            else if(row.getQuantity()>available){issues.add("INSUFFICIENT_STOCK");warnings.add(new CartView.Warning("INSUFFICIENT_STOCK","Số lượng trong giỏ vượt tồn hiện tại.",row.getId()));}
            BigDecimal price=v==null?BigDecimal.ZERO:v.price();BigDecimal total=price.multiply(BigDecimal.valueOf(row.getQuantity()));
            subtotal=subtotal.add(total);quantity+=row.getQuantity();
            lines.add(new CartView.Line(row.getId(),row.getVariantId(),v==null?null:v.productId(),v==null?"":v.sku(),v==null?"SKU không còn khả dụng":v.productName(),v==null?"":v.variantName(),v==null?null:v.imageUrl(),row.getQuantity(),money(price),money(total),available,active,List.copyOf(issues)));
        }
        return new CartView(cart.getId(),quantity,money(subtotal),"VND",List.copyOf(lines),List.copyOf(warnings));
    }
}
