package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class OrderDocumentService {
    private static final int EXPORT_LIMIT=5000;
    private static final List<String> ORDER_FIELDS=List.of("id","orderNumber","status","currency","placedAt","subtotal","discountAmount","shippingFee","assemblyFee","taxAmount","grandTotal","couponCode","assemblyRequested","customerNote");
    private static final List<String> ADDRESS_FIELDS=List.of("recipientName","phone","email","addressLine1","addressLine2","wardName","districtName","provinceName","postalCode","countryCode","companyName","taxCode");
    private static final List<String> ITEM_FIELDS=List.of("id","variantId","productId","sku","productName","variantName","imageUrl","colorName","dimensionsSnapshot","materialsSnapshot","warrantyMonths","assemblyRequired","quantity","unitPrice","discountAmount","lineTotal");
    private static final List<String> PAYMENT_FIELDS=List.of("id","method","status","amount","currency","paidAt");
    private static final List<String> REFUND_FIELDS=List.of("id","paymentId","status","amount","currency","reason");
    private final OrderStore store;
    private final OrderQueryService queries;
    private final OrderAdminAccess access;
    public OrderDocumentService(OrderStore store,OrderQueryService queries,OrderAdminAccess access) {this.store=store;this.queries=queries;this.access=access;}

    public Map<String,Object> customer(long id) {return document(queries.detail(id));}
    public Map<String,Object> staff(long id) {access.requireStaff();return document(queries.detailForAdmin(id));}

    @SuppressWarnings("unchecked")
    private Map<String,Object> document(Map<String,Object> order) {
        var result=select(order,ORDER_FIELDS);
        Object address=order.get("shippingAddress");
        result.put("shippingAddress",address==null?null:select((Map<String,Object>)address,ADDRESS_FIELDS));
        var payments=selectedRows(order.get("payments"),PAYMENT_FIELDS);
        var refunds=selectedRows(order.get("refunds"),REFUND_FIELDS);
        result.put("items",selectedRows(order.get("items"),ITEM_FIELDS));
        result.put("payments",payments);result.put("refunds",refunds);
        result.put("collectedAmount",sum(payments,Set.of("SUCCEEDED")));
        result.put("refundedAmount",sum(refunds,Set.of("SUCCEEDED")));
        result.put("pendingRefundAmount",sum(refunds,Set.of("PENDING","PROCESSING")));
        return result;
    }
    private static LinkedHashMap<String,Object> select(Map<String,Object> source,List<String> fields) {
        var selected=new LinkedHashMap<String,Object>();
        for(String field:fields)selected.put(field,source.get(field));
        return selected;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> selectedRows(Object value,List<String> fields) {
        return ((List<Map<String,Object>>)value).stream().<Map<String,Object>>map(row->select(row,fields)).toList();
    }
    private static String sum(List<Map<String,Object>> rows,Set<String> statuses) {
        return rows.stream().filter(row->statuses.contains(row.get("status")))
                .map(row->new BigDecimal(row.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2).toPlainString();
    }

    public byte[] csv(String status,String query,String fromDate,String toDate) {
        access.requireStaff();
        var filter=AdminOrderSearch.parse(status,query,fromDate,toDate);
        var parameters=new ArrayList<Object>(filter.parameters());parameters.add(EXPORT_LIMIT+1);
        var rows=store.rows("""
            SELECT o.order_number AS orderNumber,o.placed_at AS placedAt,o.status,a.recipient_name AS recipientName,a.phone AS recipientPhone,
              o.subtotal,o.discount_amount AS discountAmount,o.shipping_fee AS shippingFee,o.assembly_fee AS assemblyFee,
              o.tax_amount AS taxAmount,o.grand_total AS grandTotal,o.currency,
              (SELECT COALESCE(SUM(p.amount),0) FROM payments p WHERE p.order_id=o.id AND p.status='SUCCEEDED') AS collectedAmount,
              (SELECT COALESCE(SUM(r.amount),0) FROM refunds r WHERE r.order_id=o.id AND r.status='SUCCEEDED') AS refundedAmount
            """+AdminOrderSearch.FROM+filter.where()+" ORDER BY o.placed_at DESC,o.id DESC LIMIT ?",parameters.toArray());
        if(rows.size()>EXPORT_LIMIT)throw CommerceException.badRequest("EXPORT_TOO_LARGE","Có hơn5.000đơn phù hợp. Hãy thu hẹp bộ lọc trước khi xuất CSV.");
        var vietnam=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(AdminOrderSearch.ZONE);
        rows.forEach(row->row.put("placedAt",vietnam.format(Instant.parse(row.get("placedAt").toString()))));
        return OrderCsvWriter.write(rows);
    }
}
