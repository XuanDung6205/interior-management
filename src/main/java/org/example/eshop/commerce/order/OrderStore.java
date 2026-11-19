package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Scalar snapshot persistence; uses the same datasource transaction as cart JPA and InventoryPort. */
@Repository
public class OrderStore {
    private final JdbcTemplate db;
    public OrderStore(JdbcTemplate db){this.db=db;}
    public JdbcTemplate jdbc(){return db;}
    public long insert(String sql,Object... values){
        var keys=new GeneratedKeyHolder();
        db.update(connection->{var s=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;},keys);
        if(keys.getKey()==null)throw new IllegalStateException("Insert did not return an id");return keys.getKey().longValue();
    }
    public List<Map<String,Object>> rows(String sql,Object... args){return db.query(sql,(rs,n)->{
        var row=new LinkedHashMap<String,Object>();var meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++){
            Object value=rs.getObject(i);
            if(value instanceof BigDecimal decimal)value=CheckoutQuoteService.amount(decimal);
            if(value instanceof Timestamp time)value=time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
            row.put(meta.getColumnLabel(i),value);
        }return row;
    },args);}
    public Map<String,Object> owned(long id,long user,boolean lock){
        return selected("id=? AND user_id=?",lock,id,user);
    }
    public Map<String,Object> find(long id,boolean lock){return selected("id=?",lock,id);}
    private Map<String,Object> selected(String predicate,boolean lock,Object... parameters){
        var rows=rows("""
            SELECT id,order_number AS orderNumber,status,currency,subtotal,discount_amount AS discountAmount,
              shipping_fee AS shippingFee,assembly_fee AS assemblyFee,tax_amount AS taxAmount,grand_total AS grandTotal,
              coupon_code_snapshot AS couponCode,customer_note AS customerNote,placed_at AS placedAt,
              cancelled_at AS cancelledAt,cancel_reason AS cancelReason,assembly_requested AS assemblyRequested,confirmation_expires_at AS confirmationExpiresAt
            FROM orders WHERE
            """+predicate+(lock?" FOR UPDATE":""),parameters);
        if(rows.isEmpty())throw CommerceException.notFound("Không tìm thấy đơn hàng của bạn.");return rows.get(0);
    }
    public boolean dispatched(long id){return Boolean.TRUE.equals(db.queryForObject("""
        SELECT (EXISTS(SELECT 1 FROM shipments WHERE order_id=? AND
             (shipped_at IS NOT NULL OR delivered_at IS NOT NULL OR status IN ('IN_TRANSIT','DELIVERED','FAILED','RETURNED')))
          OR EXISTS(SELECT 1 FROM inventory_reservations r JOIN order_items i ON i.id=r.order_item_id WHERE i.order_id=? AND r.fulfilled_quantity>0)
          OR EXISTS(SELECT 1 FROM inventory_movements m JOIN order_items i ON i.id=m.order_item_id WHERE i.order_id=? AND m.movement_type='SHIPMENT'))
        """,Boolean.class,id,id,id));}
}
