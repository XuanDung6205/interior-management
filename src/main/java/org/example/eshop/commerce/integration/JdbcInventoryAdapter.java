package org.example.eshop.commerce.integration;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.port.InventoryPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Local inventory integration boundary until the TV4 service is delivered.
 * All writers share the caller's datasource and writable transaction. TV4 writers
 * must first lock and recheck the order root (including cancellation status), then
 * lock warehouses in ID order and balances by (variant ID, warehouse ID). The
 * warehouse shared locks keep activation stable during allocation. Reservation
 * children are current-read under the order lock; fulfillment/cancellation of the
 * same order therefore cannot overlap. Fulfillment must recheck remaining holds
 * after acquiring these locks. Conditional versions also guard balance updates.
 */
@Repository
@Transactional(readOnly=true)
public class JdbcInventoryAdapter implements InventoryPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final Tv5Cache cache;
    public JdbcInventoryAdapter(NamedParameterJdbcTemplate jdbc,Tv5Cache cache) { this.jdbc=jdbc;this.cache=cache; }

    @Override public Map<Long,Integer> availableQuantities(Set<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        var result=new LinkedHashMap<Long,Integer>();
        ids.forEach(id -> result.put(id,0));
        jdbc.query("""
            SELECT b.variant_id,SUM(b.on_hand_quantity-b.reserved_quantity) AS available
            FROM inventory_balances b JOIN warehouses w ON w.id=b.warehouse_id
            WHERE w.is_active=1 AND b.variant_id IN (:ids) GROUP BY b.variant_id
            """,Map.of("ids",ids),rs -> {
                result.put(rs.getLong("variant_id"),(int)Math.min(Integer.MAX_VALUE,Math.max(0L,rs.getLong("available"))));
            });
        return result;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(List<ReservationLine> lines) {
        requireWritableTransaction();
        if(lines==null) throw CommerceException.badRequest("INVALID_RESERVATION","Danh sách giữ hàng không hợp lệ.");
        if(lines.isEmpty()) return;
        var grouped=new TreeMap<Long,List<ReservationLine>>();
        var itemIds=new HashSet<Long>();
        for(var line:lines) {
            if(line==null || line.orderItemId()<=0 || line.variantId()<=0 || line.quantity()<=0 || !itemIds.add(line.orderItemId())) {
                throw CommerceException.badRequest("INVALID_RESERVATION","Thông tin dòng giữ hàng không hợp lệ hoặc bị trùng.");
            }
            grouped.computeIfAbsent(line.variantId(),id -> new ArrayList<>()).add(line);
        }
        var warehouses=lockWarehouses().stream().filter(Warehouse::active).toList();
        var balances=new TreeMap<Long,List<Balance>>();
        for(var entry:grouped.entrySet()) {
            var variantBalances=new ArrayList<Balance>();
            for(var warehouse:warehouses) {
                Balance balance=lockBalance(entry.getKey(),warehouse.id());
                if(balance!=null) variantBalances.add(balance);
            }
            long requested=entry.getValue().stream().mapToLong(ReservationLine::quantity).sum();
            long available=variantBalances.stream().mapToLong(Balance::available).sum();
            if(requested>available) throw unavailable();
            balances.put(entry.getKey(),variantBalances);
        }
        for(var entry:grouped.entrySet()) {
            entry.getValue().sort(Comparator.comparingLong(ReservationLine::orderItemId));
            for(var line:entry.getValue()) {
                int remaining=line.quantity();
                for(var balance:balances.get(entry.getKey())) {
                    int allocated=Math.min(remaining,balance.available());
                    if(allocated==0) continue;
                    int changed=jdbc.update("""
                        UPDATE inventory_balances SET reserved_quantity=reserved_quantity+:quantity,version=version+1
                        WHERE warehouse_id=:warehouse AND variant_id=:variant AND version=:version
                          AND on_hand_quantity-reserved_quantity>=:quantity
                        """,Map.of("quantity",allocated,"warehouse",balance.warehouse,"variant",balance.variant,"version",balance.version));
                    if(changed!=1) throw unavailable();
                    jdbc.update("""
                        INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity,expires_at)
                        VALUES(:item,:warehouse,:variant,:quantity,NULL)
                        """,Map.of("item",line.orderItemId(),"warehouse",balance.warehouse,"variant",balance.variant,"quantity",allocated));
                    balance.reserved+=allocated;
                    balance.version++;
                    remaining-=allocated;
                    if(remaining==0) break;
                }
                if(remaining!=0) throw unavailable();
            }
        }
        cache.evictAfterCommit(Tv5Cache.DASHBOARD);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void releaseOrder(long orderId) {
        requireWritableTransaction();
        // Current-read the root even when the caller already holds it. Every TV4
        // writer must acquire this same lock and recheck the order status.
        if(jdbc.queryForList("SELECT id FROM orders WHERE id=:order FOR UPDATE",Map.of("order",orderId),Long.class).isEmpty()) return;
        lockWarehouses();
        var reservations=jdbc.query("""
            SELECT r.id,r.warehouse_id,r.variant_id,r.quantity,r.fulfilled_quantity,r.released_quantity
            FROM inventory_reservations r JOIN order_items oi ON oi.id=r.order_item_id
            WHERE oi.order_id=:order ORDER BY r.variant_id,r.warehouse_id,r.id FOR UPDATE
            """,Map.of("order",orderId),(rs,row) -> new Hold(rs.getLong("id"),rs.getLong("warehouse_id"),rs.getLong("variant_id"),rs.getInt("quantity"),rs.getInt("fulfilled_quantity"),rs.getInt("released_quantity")));
        if(reservations.stream().anyMatch(hold -> hold.fulfilled()>0) || physicalShipment(orderId)) throw dispatched();
        var releases=new TreeMap<BalanceKey,Long>(Comparator.comparingLong(BalanceKey::variant).thenComparingLong(BalanceKey::warehouse));
        for(var hold:reservations) {
            if(hold.remaining()>0) releases.merge(new BalanceKey(hold.variant(),hold.warehouse()),(long)hold.remaining(),Long::sum);
        }
        var locked=new LinkedHashMap<BalanceKey,Balance>();
        for(var entry:releases.entrySet()) {
            Balance balance=lockBalance(entry.getKey().variant(),entry.getKey().warehouse());
            if(balance==null || balance.reserved<entry.getValue()) throw inconsistent();
            locked.put(entry.getKey(),balance);
        }
        for(var entry:releases.entrySet()) {
            Balance balance=locked.get(entry.getKey());
            int changed=jdbc.update("""
                UPDATE inventory_balances SET reserved_quantity=reserved_quantity-:quantity,version=version+1
                WHERE warehouse_id=:warehouse AND variant_id=:variant AND version=:version AND reserved_quantity>=:quantity
                """,Map.of("quantity",entry.getValue(),"warehouse",balance.warehouse,"variant",balance.variant,"version",balance.version));
            if(changed!=1) throw inconsistent();
        }
        for(var hold:reservations) {
            if(hold.remaining()==0) continue;
            int changed=jdbc.update("""
                UPDATE inventory_reservations SET released_quantity=released_quantity+:remaining
                WHERE id=:id AND fulfilled_quantity=0 AND released_quantity=:released AND quantity=:quantity
                """,Map.of("remaining",hold.remaining(),"id",hold.id(),"released",hold.released(),"quantity",hold.quantity()));
            if(changed!=1) throw dispatched();
        }
        cache.evictAfterCommit(Tv5Cache.DASHBOARD);
    }

    private List<Warehouse> lockWarehouses() {
        return jdbc.query("SELECT id,is_active FROM warehouses ORDER BY id LOCK IN SHARE MODE",Map.of(),
            (rs,row) -> new Warehouse(rs.getLong("id"),rs.getBoolean("is_active")));
    }
    private Balance lockBalance(long variant,long warehouse) {
        return jdbc.query("""
            SELECT on_hand_quantity,reserved_quantity,version FROM inventory_balances
            WHERE warehouse_id=:warehouse AND variant_id=:variant FOR UPDATE
            """,Map.of("warehouse",warehouse,"variant",variant),(rs,row) -> new Balance(variant,warehouse,rs.getInt("on_hand_quantity"),rs.getInt("reserved_quantity"),rs.getLong("version"))).stream().findFirst().orElse(null);
    }
    private boolean physicalShipment(long orderId) {
        // Lock every shipment, including a currently PENDING row, so dispatch
        // cannot slip between the evidence check and this transaction's release.
        var shipments=jdbc.query("SELECT status,shipped_at,delivered_at FROM shipments WHERE order_id=:order ORDER BY id FOR UPDATE",Map.of("order",orderId),
            (rs,row) -> rs.getTimestamp("shipped_at")!=null || rs.getTimestamp("delivered_at")!=null || Set.of("IN_TRANSIT","DELIVERED","FAILED","RETURNED").contains(rs.getString("status")));
        if(shipments.stream().anyMatch(Boolean::booleanValue)) return true;
        return !jdbc.queryForList("""
            SELECT m.id FROM inventory_movements m JOIN order_items oi ON oi.id=m.order_item_id
            WHERE oi.order_id=:order AND m.movement_type='SHIPMENT' ORDER BY m.id FOR UPDATE
            """,Map.of("order",orderId),Long.class).isEmpty();
    }
    private static void requireWritableTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("Inventory writes require the caller's existing writable transaction");
        }
    }
    private static CommerceException unavailable() { return CommerceException.conflict("STOCK_UNAVAILABLE","Số lượng tồn kho hiện tại không đủ. Vui lòng kiểm tra lại đơn hàng."); }
    private static CommerceException dispatched() { return CommerceException.conflict("ORDER_ALREADY_DISPATCHED","Đơn hàng đã được xuất kho hoặc giao vận, không thể giải phóng giữ hàng."); }
    private static CommerceException inconsistent() { return CommerceException.conflict("STOCK_RESERVATION_CONFLICT","Thông tin giữ hàng đã thay đổi. Vui lòng tải lại đơn hàng."); }
    private record Warehouse(long id,boolean active) {}
    private record BalanceKey(long variant,long warehouse) {}
    private record Hold(long id,long warehouse,long variant,int quantity,int fulfilled,int released) {
        int remaining() { return quantity-fulfilled-released; }
    }
    private static final class Balance {
        final long variant,warehouse;
        final int onHand;
        int reserved;
        long version;
        Balance(long variant,long warehouse,int onHand,int reserved,long version) { this.variant=variant;this.warehouse=warehouse;this.onHand=onHand;this.reserved=reserved;this.version=version; }
        int available() { return onHand-reserved; }
    }
}
