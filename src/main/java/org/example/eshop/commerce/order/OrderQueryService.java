package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class OrderQueryService {
    private final OrderStore store;
    private final CheckoutQuoteService quote;
    private static final Set<String> STATUSES=Set.of("PENDING_CONFIRMATION","CONFIRMED","PROCESSING","COMPLETED","CANCELLED");
    public OrderQueryService(OrderStore store,CheckoutQuoteService quote){this.store=store;this.quote=quote;}
    public Map<String,Object> list(int page,int size,String status){
        long user=quote.actorId();
        if(page<0||size<1||size>100)throw CommerceException.badRequest("INVALID_PAGE","Trang phải >=0 và kích thước từ1 đến100.");
        status=status==null?"":status;
        if(!status.isEmpty()&&!STATUSES.contains(status))throw CommerceException.badRequest("INVALID_STATUS","Trạng thái đơn không hợp lệ.");
        String where=" WHERE o.user_id=?"+(status.isEmpty()?"":" AND o.status=?");
        var params=new ArrayList<Object>();params.add(user);if(!status.isEmpty())params.add(status);
        long total=store.jdbc().queryForObject("SELECT COUNT(*) FROM orders o"+where,Long.class,params.toArray());
        params.add(size);params.add((long)page*size);
        var items=store.rows("""
            SELECT o.id,o.order_number AS orderNumber,o.status,o.currency,o.grand_total AS grandTotal,o.placed_at AS placedAt,
             (SELECT CAST(COALESCE(SUM(i.quantity),0) AS SIGNED) FROM order_items i WHERE i.order_id=o.id) AS itemCount,
             CASE WHEN o.grand_total=0 THEN 'NOT_REQUIRED'
                  WHEN (SELECT COALESCE(SUM(p.amount),0) FROM payments p WHERE p.order_id=o.id AND p.status='SUCCEEDED')>=o.grand_total THEN 'SUCCEEDED'
                  WHEN EXISTS(SELECT 1 FROM payments p WHERE p.order_id=o.id AND p.status='SUCCEEDED') THEN 'PARTIALLY_PAID'
                  WHEN o.status='CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END AS paymentStatus,
             CASE WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status='IN_TRANSIT') THEN 'IN_TRANSIT'
                  WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status='DELIVERED') THEN 'DELIVERED'
                  WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status IN ('FAILED','RETURNED')) THEN 'EXCEPTION'
                  WHEN EXISTS(SELECT 1 FROM shipments s WHERE s.order_id=o.id AND s.status<>'CANCELLED') THEN 'PENDING'
                  ELSE 'NOT_CREATED' END AS shipmentStatus
            FROM orders o
            """+where+" ORDER BY o.placed_at DESC,o.id DESC LIMIT ? OFFSET ?",params.toArray());
        return Map.of("items",items,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);
    }
    public Map<String,Object> detail(long id){return detailFor(id,quote.actorId());}
    public Map<String,Object> detailFor(long id,long user){
        var order=store.owned(id,user,false);
        order.put("items",store.rows("""
            SELECT id,variant_id AS variantId,product_id AS productId,sku,product_name AS productName,variant_name AS variantName,
             image_url AS imageUrl,color_name AS colorName,dimensions_snapshot AS dimensionsSnapshot,materials_snapshot AS materialsSnapshot,
             warranty_months AS warrantyMonths,assembly_required AS assemblyRequired,quantity,unit_price AS unitPrice,discount_amount AS discountAmount,line_total AS lineTotal
            FROM order_items WHERE order_id=? ORDER BY id
            """,id));
        var addresses=store.rows("""
            SELECT recipient_name AS recipientName,phone,email,address_line1 AS addressLine1,address_line2 AS addressLine2,
             ward_name AS wardName,district_name AS districtName,province_name AS provinceName,postal_code AS postalCode,
             country_code AS countryCode,company_name AS companyName,tax_code AS taxCode FROM order_addresses WHERE order_id=? AND address_type='SHIPPING'
            """,id);
        order.put("shippingAddress",addresses.isEmpty()?null:addresses.get(0));
        order.put("payments",store.rows("SELECT id,method,status,amount,currency,paid_at AS paidAt FROM payments WHERE order_id=? ORDER BY id",id));
        order.put("shipments",store.rows("""
            SELECT id,status,tracking_number AS trackingNumber,carrier,
              shipped_at AS shippedAt,delivered_at AS deliveredAt
            FROM shipments WHERE order_id=? ORDER BY id
            """,id));
        order.put("shipmentEvents",store.rows("""
            SELECT s.id AS shipmentId,
              JSON_UNQUOTE(JSON_EXTRACT(a.before_data,'$.status')) AS fromStatus,
              JSON_UNQUOTE(JSON_EXTRACT(a.after_data,'$.status')) AS toStatus,
              JSON_UNQUOTE(JSON_EXTRACT(a.after_data,'$.reason')) AS reason,
              a.created_at AS changedAt
            FROM audit_logs a JOIN shipments s ON a.entity_id=CAST(s.id AS CHAR)
            WHERE a.entity_type='SHIPMENT' AND a.action='SHIPMENT_STATUS' AND s.order_id=?
            ORDER BY a.created_at,a.id
            """,id));
        order.put("history",store.rows("SELECT from_status AS fromStatus,to_status AS toStatus,note,changed_at AS changedAt FROM order_status_history WHERE order_id=? ORDER BY changed_at,id",id));
        order.put("refunds",store.rows("SELECT id,payment_id AS paymentId,status,amount,currency,reason FROM refunds WHERE order_id=? ORDER BY id",id));
        boolean canCancel=Set.of("PENDING_CONFIRMATION","CONFIRMED").contains(order.get("status"))&&!store.dispatched(id);
        order.put("canCancel",canCancel);
        order.put("cancellationMessage",canCancel?"Bạn có thể hủy trước khi đơn được xử lý/xuất giao. Khoản đã thu sẽ được đưa vào yêu cầu hoàn tiền.":"CANCELLED".equals(order.get("status"))?"Đơn đã được hủy.":"Đơn đã được xử lý hoặc xuất giao. Vui lòng liên hệ cửa hàng để được hỗ trợ.");
        return order;
    }
}
