package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class AdminOrderQueryService {
    private final OrderStore store;private final OrderQueryService snapshots;private final OrderAdminAccess access;private final OrderLifecyclePolicy policy;
    public AdminOrderQueryService(OrderStore store,OrderQueryService snapshots,OrderAdminAccess access,OrderLifecyclePolicy policy){this.store=store;this.snapshots=snapshots;this.access=access;this.policy=policy;}
    public Map<String,Object> list(int page,int size,String status,String query){
        return list(page,size,status,query,"","");
    }
    public Map<String,Object> list(int page,int size,String status,String query,String fromDate,String toDate){
        access.requireStaff();
        if(page<0||size<1||size>100)throw CommerceException.badRequest("INVALID_PAGE","Trang phải >=0 và kích thước từ1 đến100.");
        var filter=AdminOrderSearch.parse(status,query,fromDate,toDate);
        String joins=AdminOrderSearch.FROM,where=filter.where();var params=new ArrayList<Object>(filter.parameters());
        long total=store.jdbc().queryForObject("SELECT COUNT(*)"+joins+where,Long.class,params.toArray());params.add(size);params.add((long)page*size);
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
                  ELSE 'NOT_CREATED' END AS shipmentStatus,
             u.id AS customerId,u.username AS customerUsername,u.full_name AS customerFullName,u.email AS customerEmail,
             a.recipient_name AS recipientName,a.phone AS recipientPhone
            """+joins+where+" ORDER BY o.placed_at DESC,o.id DESC LIMIT ? OFFSET ?",params.toArray());
        items.forEach(this::nestCustomer);
        return Map.of("items",items,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);
    }
    public Map<String,Object> detail(long id){
        access.requireStaff();var order=snapshots.detailForAdmin(id);
        var meta=store.rows("""
            SELECT o.internal_note AS internalNote,o.confirmed_at AS confirmedAt,o.completed_at AS completedAt,o.updated_at AS updatedAt,
              u.id AS customerId,u.username AS customerUsername,u.full_name AS customerFullName,u.email AS customerEmail
            FROM orders o LEFT JOIN users u ON u.id=o.user_id WHERE o.id=?
            """,id).get(0);
        nestCustomer(meta);order.putAll(meta);
        var options=policy.options(id,order.get("status").toString());order.put("allowedTransitions",options.allowedTransitions());order.put("blockedTransitions",options.blockedTransitions());
        return order;
    }
    private void nestCustomer(Map<String,Object> row){
        var customer=new LinkedHashMap<String,Object>();
        for(var pair:Map.of("id","customerId","username","customerUsername","fullName","customerFullName","email","customerEmail").entrySet())customer.put(pair.getKey(),row.remove(pair.getValue()));
        row.put("customer",customer);
    }
}
