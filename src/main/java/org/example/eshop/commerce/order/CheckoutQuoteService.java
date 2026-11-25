package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.*;
import org.example.eshop.commerce.port.CheckoutAddressPort.Address;
import org.example.eshop.commerce.promotion.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class CheckoutQuoteService {
    private static final BigDecimal MAX_MONEY=new BigDecimal("9999999999999.99");
    private final JdbcTemplate db;
    private final CurrentActorPort actors;
    private final CheckoutCatalogPort catalog;
    private final CheckoutAddressPort addresses;
    private final InventoryPort inventory;
    private final CouponRepository coupons;
    private final CouponCalculator calculator;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final BigDecimal shipping,freeThreshold,assembly;
    public CheckoutQuoteService(JdbcTemplate db,CurrentActorPort actors,CheckoutCatalogPort catalog,
            CheckoutAddressPort addresses,InventoryPort inventory,CouponRepository coupons,
            CouponCalculator calculator,ObjectMapper mapper,Clock clock,
            @Value("${commerce.checkout.shipping-fee:50000.00}") BigDecimal shipping,
            @Value("${commerce.checkout.free-shipping-threshold:10000000.00}") BigDecimal freeThreshold,
            @Value("${commerce.checkout.assembly-unit-fee:100000.00}") BigDecimal assembly) {
        this.db=db;this.actors=actors;this.catalog=catalog;this.addresses=addresses;this.inventory=inventory;
        this.coupons=coupons;this.calculator=calculator;this.mapper=mapper;this.clock=clock;
        this.shipping=moneyConfig(shipping);this.freeThreshold=moneyConfig(freeThreshold);this.assembly=moneyConfig(assembly);
    }
    private static BigDecimal moneyConfig(BigDecimal v){if(v.signum()<0||v.scale()>2||v.compareTo(MAX_MONEY)>0)throw new IllegalArgumentException("Checkout fees must fit nonnegative DECIMAL(15,2)");return v.setScale(2);}
    private static void storable(BigDecimal v){if(v.signum()<0||v.compareTo(MAX_MONEY)>0)throw CommerceException.conflict("ORDER_AMOUNT_TOO_LARGE","Khoản tiền vượt giới hạn, vui lòng chia nhỏ giỏ hàng.");}
    public long actorId(){var id=actors.currentActor().userId();if(id==null)throw CommerceException.forbidden("Bạn cần đăng nhập để đặt hàng.");return id;}
    public void validate(CheckoutRequest r) {
        var errors=new LinkedHashMap<String,String>();
        if(r.cartId()==null||r.cartId()<=0)errors.put("cartId","Giỏ hàng không hợp lệ.");
        if((r.addressId()==null)==(r.shippingAddress()==null))errors.put("shippingAddress","Chọn một địa chỉ đã lưu hoặc nhập địa chỉ nhận hàng.");
        if(r.addressId()!=null&&r.addressId()<=0)errors.put("addressId","Địa chỉ không hợp lệ.");
        if(!Set.of("COD","BANK_TRANSFER","SEPAY").contains(Objects.toString(r.paymentMethod(),"")))errors.put("paymentMethod","Chọn COD hoặc chuyển khoản.");
        if(r.customerNote()!=null&&r.customerNote().length()>2000)errors.put("customerNote","Ghi chú tối đa 2000 ký tự.");
        if(r.couponCode()!=null&&!r.couponCode().isBlank()&&!r.couponCode().trim().matches("[a-zA-Z0-9_-]{1,50}"))errors.put("couponCode","Mã giảm giá không hợp lệ.");
        if(!errors.isEmpty())throw CommerceException.invalid(errors);
    }
    private static String clean(String s){return s==null||s.isBlank()?null:s.trim();}
    private static void field(Map<String,String> errors,String key,String value,int length,boolean required){if((required&&(value==null||value.isBlank()))||(value!=null&&value.length()>length))errors.put("shippingAddress."+key,"Thông tin thiếu hoặc vượt độ dài cho phép ("+length+").");}
    private Address address(CheckoutRequest r,long userId){
        Address a=r.addressId()!=null?addresses.findOwned(userId,r.addressId()).orElseThrow(()->CommerceException.notFound("Không tìm thấy địa chỉ của bạn.")):r.shippingAddress();
        var errors=new LinkedHashMap<String,String>();
        field(errors,"recipientName",a.recipientName(),150,true);field(errors,"phone",a.phone(),30,true);
        if(a.phone()!=null&&!a.phone().trim().matches("[+0-9() .-]{8,30}"))errors.put("shippingAddress.phone","Số điện thoại không hợp lệ.");
        field(errors,"addressLine1",a.addressLine1(),255,true);field(errors,"addressLine2",a.addressLine2(),255,false);
        field(errors,"wardName",a.wardName(),120,false);field(errors,"districtName",a.districtName(),120,false);field(errors,"provinceName",a.provinceName(),120,true);
        field(errors,"postalCode",a.postalCode(),20,false);field(errors,"email",a.email(),254,false);field(errors,"companyName",a.companyName(),200,false);field(errors,"taxCode",a.taxCode(),40,false);
        if(clean(a.email())!=null&&!a.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))errors.put("shippingAddress.email","Email không hợp lệ.");
        if(a.countryCode()!=null&&!"VN".equals(a.countryCode()))errors.put("shippingAddress.countryCode","Hiện chỉ hỗ trợ giao hàng trong Việt Nam.");
        if(!errors.isEmpty())throw CommerceException.invalid(errors);
        return new Address(null,null,clean(a.recipientName()),clean(a.phone()),clean(a.email()),clean(a.addressLine1()),clean(a.addressLine2()),clean(a.wardName()),clean(a.districtName()),clean(a.provinceName()),clean(a.postalCode()),"VN",clean(a.companyName()),clean(a.taxCode()));
    }
    @Transactional(readOnly=true)
    public CheckoutQuote quote(CheckoutRequest request){return calculate(request,actorId(),false).quote();}
    public record Calculation(CheckoutQuote quote,Long couponId) {}
    public Calculation calculate(CheckoutRequest r,long userId,boolean lockCoupon) {
        validate(r);Address address=address(r,userId);
        var carts=db.query("SELECT id FROM carts WHERE id=? AND user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)",(rs,n)->rs.getLong(1),r.cartId(),userId,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        if(carts.isEmpty())throw CommerceException.conflict("CART_UNAVAILABLE","Giỏ hàng đã thay đổi hoặc không còn hoạt động.");
        record CartLine(long variantId,int quantity){}
        var cartLines=db.query("SELECT variant_id,quantity FROM cart_items WHERE cart_id=? ORDER BY variant_id",(rs,n)->new CartLine(rs.getLong(1),rs.getInt(2)),r.cartId());
        if(cartLines.isEmpty())throw CommerceException.conflict("EMPTY_CART","Giỏ hàng đang trống.");
        var ids=new TreeSet<Long>();cartLines.forEach(l->ids.add(l.variantId()));
        var products=catalog.findProducts(ids);var quantities=inventory.availableQuantities(ids);
        BigDecimal subtotal=BigDecimal.ZERO,assemblyTotal=BigDecimal.ZERO;
        var lines=new ArrayList<CheckoutQuote.Line>();var fingerprintItems=new ArrayList<Object>();
        for(var l:cartLines){
            var p=products.get(l.variantId());if(p==null||!p.active())throw CommerceException.conflict("SKU_UNAVAILABLE","Sản phẩm trong giỏ đã ngừng bán.");
            int available=quantities.getOrDefault(l.variantId(),0);if(l.quantity()>available)throw CommerceException.conflict("STOCK_UNAVAILABLE","Sản phẩm "+p.sku()+" không đủ tồn kho. Hãy cập nhật giỏ hàng.");
            var total=p.unitPrice().multiply(BigDecimal.valueOf(l.quantity())).setScale(2,RoundingMode.HALF_UP);storable(total);subtotal=subtotal.add(total);
            if(Boolean.TRUE.equals(r.assemblyRequested())&&p.assemblyRequired())assemblyTotal=assemblyTotal.add(assembly.multiply(BigDecimal.valueOf(l.quantity())));
            lines.add(new CheckoutQuote.Line(p.variantId(),p.productId(),p.sku(),p.productName(),p.variantName(),p.imageUrl(),p.colorName(),p.dimensionsSnapshot(),p.materialsSnapshot(),p.warrantyMonths(),p.assemblyRequired(),l.quantity(),amount(p.unitPrice()),amount(total),available));
            fingerprintItems.add(List.of(p,l.quantity()));
        }
        String code=clean(r.couponCode());BigDecimal discount=BigDecimal.ZERO;Long couponId=null;
        if(code!=null){code=code.toUpperCase(Locale.ROOT);final String lookup=code;
            var found=db.query("SELECT id FROM coupons WHERE code=?"+(lockCoupon?" FOR UPDATE":""),(rs,n)->rs.getLong(1),lookup);
            if(found.isEmpty())throw CommerceException.conflict("COUPON_UNAVAILABLE","Mã giảm giá không tồn tại.");
            couponId=found.get(0);
            // A new EntityManager per request and the coupon row lock serialize all reservations.
            var c=coupons.findById(couponId).orElseThrow(()->CommerceException.conflict("COUPON_UNAVAILABLE","Mã giảm giá không còn tồn tại."));
            discount=calculator.discount(c,subtotal);
            Long total=db.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id=? AND status IN ('RESERVED','APPLIED')",Long.class,couponId);
            Long own=db.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id=? AND user_id=? AND status IN ('RESERVED','APPLIED')",Long.class,couponId,userId);
            if((c.getTotalUsageLimit()!=null&&total>=c.getTotalUsageLimit())||(c.getPerUserLimit()!=null&&own>=c.getPerUserLimit()))throw CommerceException.conflict("COUPON_LIMIT_REACHED","Mã giảm giá đã hết lượt sử dụng cho đơn này.");
        }
        BigDecimal shippingTotal=subtotal.compareTo(freeThreshold)>=0?BigDecimal.ZERO:shipping;
        BigDecimal grand=subtotal.subtract(discount).add(shippingTotal).add(assemblyTotal);
        for(var amount:List.of(subtotal,discount,shippingTotal,assemblyTotal,grand))storable(amount);
        String fp=hash(List.of(userId,r.cartId(),address,fingerprintItems,amount(subtotal),amount(discount),amount(shippingTotal),amount(assemblyTotal),Objects.toString(code,""),r.paymentMethod(),Boolean.TRUE.equals(r.assemblyRequested()),Objects.toString(clean(r.customerNote()),"")));
        return new Calculation(new CheckoutQuote(r.cartId(),fp,"VND",List.copyOf(lines),address,amount(subtotal),amount(discount),amount(shippingTotal),amount(assemblyTotal),"0.00",amount(grand),code,r.paymentMethod(),Boolean.TRUE.equals(r.assemblyRequested()),List.of()),couponId);
    }
    public String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint checkout",e);}}
    static String amount(BigDecimal amount){return amount.setScale(2,RoundingMode.HALF_UP).toPlainString();}
}
