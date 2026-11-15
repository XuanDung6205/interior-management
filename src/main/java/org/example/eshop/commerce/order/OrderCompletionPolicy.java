package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only completion guidance from persisted payment and TV4 evidence.
 * Lifecycle callers must hold the order root lock; this method joins that same
 * transaction. Every payment/fulfillment writer must acquire and recheck the root
 * before changing evidence. Detail requests may show guidance without the lock,
 * so a transition must always evaluate again while holding it.
 */
@Service
@Transactional(readOnly=true)
public class OrderCompletionPolicy {
    private final JdbcTemplate jdbc;
    public OrderCompletionPolicy(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Evaluation(boolean eligible,List<String> reasons) {
        public Evaluation {reasons=List.copyOf(reasons);}
    }

    public Evaluation evaluate(long orderId) {
        var headers=jdbc.query("SELECT grand_total,assembly_requested FROM orders WHERE id=?",
            (rs,row) -> new Header(rs.getBigDecimal("grand_total"),rs.getBoolean("assembly_requested")),orderId);
        if(headers.isEmpty())throw CommerceException.notFound("Không tìm thấy đơn hàng.");
        Header header=headers.get(0);
        var lines=jdbc.query("SELECT id,sku,quantity,assembly_required FROM order_items WHERE order_id=? ORDER BY id",
            (rs,row) -> new Line(rs.getLong("id"),rs.getString("sku"),rs.getInt("quantity"),rs.getBoolean("assembly_required")),orderId);
        BigDecimal netCollected=jdbc.queryForObject("""
            SELECT COALESCE((SELECT SUM(amount) FROM payments WHERE order_id=? AND status='SUCCEEDED'),0)
                 - COALESCE((SELECT SUM(amount) FROM refunds WHERE order_id=? AND status IN ('PENDING','SUCCEEDED')),0)
            """,BigDecimal.class,orderId,orderId);
        var shipments=jdbc.query("""
            SELECT id,status,delivered_at,installation_required,installation_status,
                   installation_completed_at,installation_staff_id
            FROM shipments WHERE order_id=? AND status<>'CANCELLED' ORDER BY id
            """,(rs,row) -> new Shipment(rs.getLong("id"),rs.getString("status"),rs.getTimestamp("delivered_at")!=null,
                rs.getBoolean("installation_required"),rs.getString("installation_status"),
                rs.getTimestamp("installation_completed_at")!=null,rs.getObject("installation_staff_id")!=null),orderId);
        var allocations=jdbc.query("""
            SELECT shipment_id,order_item_id,quantity FROM shipment_items
            WHERE order_id=? ORDER BY shipment_id,order_item_id,id
            """,(rs,row) -> new Allocation(rs.getLong("shipment_id"),rs.getLong("order_item_id"),rs.getInt("quantity")),orderId);
        var reservations=new LinkedHashMap<Long,ReservationEvidence>();
        jdbc.query("""
            SELECT r.order_item_id,SUM(r.fulfilled_quantity) AS fulfilled,SUM(r.remaining_quantity) AS remaining
            FROM inventory_reservations r JOIN order_items i ON i.id=r.order_item_id
            WHERE i.order_id=? GROUP BY r.order_item_id
            """,rs -> {
                reservations.put(rs.getLong("order_item_id"),new ReservationEvidence(rs.getBigDecimal("fulfilled"),rs.getBigDecimal("remaining")));
            },orderId);
        var movements=new LinkedHashMap<Long,BigDecimal>();
        jdbc.query("""
            SELECT m.order_item_id,SUM(m.quantity_delta) AS quantity
            FROM inventory_movements m JOIN order_items i ON i.id=m.order_item_id
            WHERE i.order_id=? AND m.movement_type='SHIPMENT' GROUP BY m.order_item_id
            """,rs -> {movements.put(rs.getLong("order_item_id"),rs.getBigDecimal("quantity"));},orderId);

        var reasons=new ArrayList<String>();
        if(lines.isEmpty())reasons.add("Đơn hàng chưa có sản phẩm.");
        if(header.grandTotal().signum()>0 && netCollected.compareTo(header.grandTotal())<0) {
            reasons.add("Thanh toán thành công sau khi trừ hoàn tiền chưa đủ tổng giá trị đơn hàng.");
        }
        if(shipments.isEmpty())reasons.add("Đơn hàng chưa có vận đơn giao hàng chưa hủy.");
        Map<Long,Shipment> byShipment=new LinkedHashMap<>();
        for(var shipment:shipments) {
            byShipment.put(shipment.id(),shipment);
            if(!shipment.delivered())reasons.add("Vận đơn #"+shipment.id()+" chưa được giao thành công hoặc thiếu thời điểm giao hàng.");
        }
        var byLine=new LinkedHashMap<Long,Line>();
        lines.forEach(line -> byLine.put(line.id(),line));
        var delivered=new LinkedHashMap<Long,BigDecimal>();
        var assemblyShipments=new HashSet<Long>();
        for(var allocation:allocations) {
            Shipment shipment=byShipment.get(allocation.shipmentId());
            if(shipment==null || !shipment.delivered())continue;
            delivered.merge(allocation.lineId(),BigDecimal.valueOf(allocation.quantity()),BigDecimal::add);
            Line line=byLine.get(allocation.lineId());
            if(line!=null && line.assemblyRequired())assemblyShipments.add(shipment.id());
        }
        for(var line:lines) {
            BigDecimal expected=BigDecimal.valueOf(line.quantity());
            String item=" sản phẩm "+line.sku()+" (dòng #"+line.id()+")";
            BigDecimal received=delivered.getOrDefault(line.id(),BigDecimal.ZERO);
            if(received.compareTo(expected)!=0)reasons.add("Số lượng giao của"+item+" là "+received.toPlainString()+", cần "+line.quantity()+".");
            ReservationEvidence hold=reservations.getOrDefault(line.id(),new ReservationEvidence(BigDecimal.ZERO,BigDecimal.ZERO));
            if(hold.fulfilled().compareTo(expected)!=0)reasons.add("Số lượng xuất kho của"+item+" là "+hold.fulfilled().toPlainString()+", cần "+line.quantity()+".");
            if(hold.remaining().signum()!=0)reasons.add("Sản phẩm "+line.sku()+" (dòng #"+line.id()+") vẫn còn "+hold.remaining().toPlainString()+" sản phẩm giữ kho chưa xử lý.");
            BigDecimal movement=movements.getOrDefault(line.id(),BigDecimal.ZERO);
            if(movement.compareTo(expected.negate())!=0)reasons.add("Biến động xuất kho của"+item+" là "+movement.toPlainString()+", cần "+expected.negate().toPlainString()+".");
        }
        if(header.assemblyRequested()) {
            for(var shipment:shipments) {
                if(assemblyShipments.contains(shipment.id()) && !shipment.installed()) {
                    reasons.add("Vận đơn #"+shipment.id()+" chưa hoàn tất lắp đặt theo yêu cầu.");
                }
            }
        }
        return new Evaluation(reasons.isEmpty(),reasons);
    }

    private record Header(BigDecimal grandTotal,boolean assemblyRequested) {}
    private record Line(long id,String sku,int quantity,boolean assemblyRequired) {}
    private record Allocation(long shipmentId,long lineId,int quantity) {}
    private record ReservationEvidence(BigDecimal fulfilled,BigDecimal remaining) {}
    private record Shipment(long id,String status,boolean deliveredAt,boolean installationRequired,String installationStatus,boolean installationCompletedAt,boolean installer) {
        boolean delivered() {return "DELIVERED".equals(status) && deliveredAt;}
        boolean installed() {return installationRequired && "COMPLETED".equals(installationStatus) && installationCompletedAt && installer;}
    }
}
