package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.InventoryPort;
import org.example.eshop.commerce.notification.OrderNotifications;
import org.example.eshop.commerce.tracking.CommerceTrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class CheckoutService {
    private final OrderStore store;
    private final CheckoutQuoteService quotes;
    private final OrderQueryService orders;
    private final InventoryPort inventory;
    private final Clock clock;
    private final long confirmationTtl;
    private final org.example.eshop.commerce.payment.SePayPayments sepay;
    @Autowired(required = false) private CommerceTrackingService tracking;
    public CheckoutService(OrderStore store,CheckoutQuoteService quotes,OrderQueryService orders,InventoryPort inventory,Clock clock,org.example.eshop.commerce.payment.SePayPayments sepay,@Value("${commerce.order.confirmation-ttl-seconds:86400}")long confirmationTtl){this.sepay=sepay;this.store=store;this.quotes=quotes;this.orders=orders;this.inventory=inventory;this.clock=clock;if(confirmationTtl<60||confirmationTtl>604800)throw new IllegalArgumentException("COD confirmation TTL must be60..604800seconds");this.confirmationTtl=confirmationTtl;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> place(CheckoutRequest request,String key){
        long user=quotes.actorId();quotes.validate(request);
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw CommerceException.badRequest("INVALID_IDEMPOTENCY_KEY","Idempotency-Key phải có8–64 chữ, số, _ hoặc -.");
        if(request.quoteFingerprint()==null||!request.quoteFingerprint().matches("[a-f0-9]{64}"))throw CommerceException.badRequest("QUOTE_REQUIRED","Vui lòng xem trước đơn hàng trước khi đặt.");
        // Per-user lock serializes duplicate submissions even before an order row exists.
        store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);
        String scopedKey=quotes.hash(List.of(user,key));String requestHash=quotes.hash(request);
        var existing=store.jdbc().query("SELECT id,request_fingerprint FROM orders WHERE user_id=? AND idempotency_key=?",(rs,n)->Map.entry(rs.getLong(1),Objects.toString(rs.getString(2),"")),user,scopedKey);
        if(!existing.isEmpty()){
            if(!existing.get(0).getValue().equals(requestHash))throw CommerceException.conflict("IDEMPOTENCY_CONFLICT","Khóa đã dùng cho nội dung khác. Vui lòng tạo yêu cầu mới.");
            return orders.detailFor(existing.get(0).getKey(),user);
        }
        var cart=store.jdbc().query("SELECT id FROM carts WHERE id=? AND user_id=? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getLong(1),request.cartId(),user);
        if(cart.isEmpty())throw CommerceException.conflict("CART_UNAVAILABLE","Giỏ đã đặt hoặc không thuộc tài khoản hiện tại.");
        var calculation=quotes.calculate(request,user,true);var q=calculation.quote();
        if(!q.quoteFingerprint().equals(request.quoteFingerprint()))throw new QuoteChangedException(q);
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        String number="NN-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        long id=store.insert("""
            INSERT INTO orders(order_number,user_id,idempotency_key,request_fingerprint,status,currency,subtotal,discount_amount,shipping_fee,assembly_fee,tax_amount,grand_total,coupon_id,coupon_code_snapshot,customer_note,placed_at,updated_at,assembly_requested)
            VALUES(?,?,?,?,'PENDING_CONFIRMATION','VND',?,?,?,?,?,?,?,?,?,?,?,?)
            """,number,user,scopedKey,requestHash,q.subtotal(),q.discountAmount(),q.shippingFee(),q.assemblyFee(),q.taxAmount(),q.grandTotal(),calculation.couponId(),q.couponCode(),request.customerNote(),now,now,q.assemblyRequested());
        var holds=new ArrayList<InventoryPort.ReservationLine>();
        for(var line:q.items()){
            long itemId=store.insert("""
                INSERT INTO order_items(order_id,product_id,variant_id,product_name,variant_name,sku,image_url,color_name,dimensions_snapshot,materials_snapshot,warranty_months,assembly_required,quantity,unit_price,discount_amount)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """,id,line.productId(),line.variantId(),line.productName(),line.variantName(),line.sku(),line.imageUrl(),line.colorName(),line.dimensionsSnapshot(),line.materialsSnapshot(),line.warrantyMonths(),line.assemblyRequired(),line.quantity(),line.unitPrice());
            holds.add(new InventoryPort.ReservationLine(itemId,line.variantId(),line.quantity()));
        }
        inventory.reserve(holds);
        var a=q.shippingAddress();
        store.insert("""
            INSERT INTO order_addresses(order_id,address_type,recipient_name,phone,email,address_line1,address_line2,ward_name,district_name,province_name,postal_code,country_code,company_name,tax_code)
            VALUES(?,'SHIPPING',?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,a.recipientName(),a.phone(),a.email(),a.addressLine1(),a.addressLine2(),a.wardName(),a.districtName(),a.provinceName(),a.postalCode(),a.countryCode(),a.companyName(),a.taxCode());
        store.insert("INSERT INTO order_status_history(order_id,from_status,to_status,changed_by,note,changed_at) VALUES(?,NULL,'PENDING_CONFIRMATION',?,'Khách hàng đặt đơn',?)",id,user,now);
        if(calculation.couponId()!=null)store.insert("INSERT INTO coupon_redemptions(coupon_id,order_id,user_id,discount_amount,status,expires_at,created_at) VALUES(?,?,?,?,'RESERVED',NULL,?)",calculation.couponId(),id,user,q.discountAmount(),now);
        if(new BigDecimal(q.grandTotal()).signum()>0&&"SEPAY".equals(q.paymentMethod()))sepay.insert(id,new BigDecimal(q.grandTotal()),"checkout:"+id,now);
        else if(new BigDecimal(q.grandTotal()).signum()>0)store.insert("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,currency,status,created_at,updated_at) VALUES(?,?,?, ?,?,'VND','PENDING',?,?)",id,q.paymentMethod(),"COD".equals(q.paymentMethod())?"COD":"MANUAL_BANK","checkout:"+id,q.grandTotal(),now,now);
        if("COD".equals(q.paymentMethod())&&new BigDecimal(q.grandTotal()).signum()>0)store.jdbc().update("UPDATE orders SET confirmation_expires_at=? WHERE id=?",now.plusSeconds(confirmationTtl),id);
        OrderNotifications.placed(store.jdbc(),clock,id);
        int converted=store.jdbc().update("UPDATE carts SET status='CONVERTED',updated_at=? WHERE id=? AND user_id=? AND status='ACTIVE'",now,request.cartId(),user);
        if(converted!=1)throw CommerceException.conflict("CART_UNAVAILABLE","Giỏ hàng đã thay đổi.");
        // Ghi sự kiện ORDER_PLACED xác nhận từ server sau khi đơn đã tạo xong (fail-safe ko ảnh hưởng transaction đặt đơn)
        if (tracking != null) {
            tracking.recordServerEvent("ORDER_PLACED", "order-" + id, user, null, null, id, null);
        }
        return orders.detailFor(id,user);
    }
}
