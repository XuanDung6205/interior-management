package org.example.eshop.commerce.inventory;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ShipmentLifecycleService {
    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public ShipmentLifecycleService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
    }

    public record ShipmentView(long id, long orderId, String orderNumber, long warehouseId,
            String warehouseCode, String carrier, String trackingNumber, String status,
            String shippedAt, String deliveredAt, boolean replayed) {}
    public record ShipmentPage(List<ShipmentView> items, int page, int size, long totalElements, int totalPages) {}
    private record Shipment(long id, long orderId, long warehouseId, String status) {}
    private record Owner(long id, Long userId, String status) {}
    private record Previous(long shipmentId, String targetStatus, String reason) {}
    private record Line(long itemId, long variantId, int quantity) {}
    private record Balance(long variantId, int onHand, long version) {}
    private static final Set<String> FILTERS = Set.of("IN_TRANSIT", "FAILED", "DELIVERED", "RETURNED");

    public ShipmentPage list(int page, int size, String status) {
        requireStaff();
        if (page < 0 || size < 1 || size > 100 || status != null && !status.isBlank() && !FILTERS.contains(status)) {
            throw CommerceException.badRequest("INVALID_PAGE", "Trang hoặc trạng thái vận đơn không hợp lệ.");
        }
        String where = status == null || status.isBlank() ? " WHERE s.status IN ('IN_TRANSIT','FAILED','DELIVERED','RETURNED')" : " WHERE s.status=?";
        Long total = status == null || status.isBlank()
                ? jdbc.queryForObject("SELECT COUNT(*) FROM shipments s" + where, Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM shipments s" + where, Long.class, status);
        Object[] args = status == null || status.isBlank() ? new Object[]{size, (long) page * size}
                : new Object[]{status, size, (long) page * size};
        var items = jdbc.query("""
                SELECT s.id,s.order_id,o.order_number,s.warehouse_id,w.code,s.carrier,s.tracking_number,
                       s.status,s.shipped_at,s.delivered_at FROM shipments s
                JOIN orders o ON o.id=s.order_id JOIN warehouses w ON w.id=s.warehouse_id
                """ + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new ShipmentView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        utc(rs.getTimestamp(9)), utc(rs.getTimestamp(10)), false), args);
        long count = total == null ? 0 : total;
        return new ShipmentPage(items, page, size, count, (int) Math.min(Integer.MAX_VALUE, (count + size - 1) / size));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShipmentView changeStatus(long shipmentId, ShipmentStatusRequest request, String rawKey) {
        long actor = requireStaff();
        validate(shipmentId, request, rawKey);
        String key = "shipstatus:" + actor + ":" + rawKey;
        Shipment snapshot = findShipment(shipmentId, false);
        Owner owner = findOwner(snapshot.orderId(), false);
        if (owner.userId() == null) throw CommerceException.conflict("ORDER_UNAVAILABLE", "Đơn không có tài khoản sở hữu.");
        for (long userId : List.of(actor, owner.userId()).stream().distinct().sorted().toList()) {
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId).isEmpty()) {
                throw CommerceException.conflict("USER_UNAVAILABLE", "Tài khoản không còn tồn tại.");
            }
        }
        Owner lockedOrder = findOwner(snapshot.orderId(), true);
        if (!owner.userId().equals(lockedOrder.userId())) throw CommerceException.conflict("ORDER_CHANGED", "Chủ đơn vừa thay đổi.");
        Shipment shipment = findShipment(shipmentId, true);
        if (shipment.orderId() != lockedOrder.id()) throw CommerceException.conflict("SHIPMENT_CHANGED", "Vận đơn vừa thay đổi.");
        Previous previous = previous(key);
        if (previous != null) {
            if (previous.shipmentId() != shipmentId || !previous.targetStatus().equals(request.targetStatus())
                    || !previous.reason().equals(request.reason().trim())) {
                throw CommerceException.conflict("IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã dùng cho cập nhật giao hàng khác.");
            }
            return current(shipmentId, true);
        }
        if (!allowed(shipment.status(), request.targetStatus())) {
            throw CommerceException.conflict("SHIPMENT_STATUS_CONFLICT", "Không thể chuyển vận đơn sang trạng thái này.");
        }
        if (!"PROCESSING".equals(lockedOrder.status())) {
            throw CommerceException.conflict("ORDER_STATUS_CONFLICT", "Đơn không còn ở trạng thái giao hàng.");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if ("RETURNED".equals(request.targetStatus())) restock(shipment, actor, now);
        int changed = jdbc.update("""
                UPDATE shipments SET status=?,delivered_at=?,updated_at=? WHERE id=? AND status=?
                """, request.targetStatus(), "DELIVERED".equals(request.targetStatus()) ? now : null,
                now, shipmentId, shipment.status());
        if (changed != 1) throw CommerceException.conflict("SHIPMENT_CHANGED", "Vận đơn vừa thay đổi.");
        jdbc.update("""
                INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,before_data,after_data,request_id,created_at)
                VALUES(?,'SHIPMENT_STATUS','SHIPMENT',?,JSON_OBJECT('status',?),
                    JSON_OBJECT('status',?,'reason',?),?,?)
                """, actor, Long.toString(shipmentId), shipment.status(), request.targetStatus(),
                request.reason().trim(), key, now);
        if ("DELIVERED".equals(request.targetStatus()) && allDelivered(shipment.orderId())) {
            jdbc.update("UPDATE orders SET status='COMPLETED',completed_at=?,updated_at=? WHERE id=? AND status='PROCESSING'",
                    now, now, shipment.orderId());
            jdbc.update("""
                    INSERT INTO order_status_history(order_id,from_status,to_status,changed_by,note,changed_at)
                    VALUES(?,'PROCESSING','COMPLETED',?,?,?)
                    """, shipment.orderId(), actor, "Đã giao đủ các vận đơn", now);
        }
        return current(shipmentId, false);
    }

    private void restock(Shipment shipment, long actor, LocalDateTime now) {
        jdbc.query("SELECT id FROM warehouses ORDER BY id LOCK IN SHARE MODE", (rs, row) -> rs.getLong(1));
        var lines = jdbc.query("""
                SELECT si.id,oi.variant_id,si.quantity FROM shipment_items si
                JOIN order_items oi ON oi.id=si.order_item_id
                WHERE si.shipment_id=? ORDER BY oi.variant_id,si.id FOR UPDATE
                """, (rs, row) -> new Line(rs.getLong(1), rs.getLong(2), rs.getInt(3)), shipment.id());
        if (lines.isEmpty()) throw CommerceException.conflict("SHIPMENT_EMPTY", "Vận đơn không có hàng để nhập lại.");
        var totals = new LinkedHashMap<Long, Long>();
        for (Line line : lines) totals.merge(line.variantId(), (long) line.quantity(), Long::sum);
        for (var entry : totals.entrySet()) {
            Balance balance = jdbc.query("""
                    SELECT variant_id,on_hand_quantity,version FROM inventory_balances
                    WHERE warehouse_id=? AND variant_id=? FOR UPDATE
                    """, (rs, row) -> new Balance(rs.getLong(1), rs.getInt(2), rs.getLong(3)),
                    shipment.warehouseId(), entry.getKey()).stream().findFirst()
                    .orElseThrow(() -> CommerceException.conflict("STOCK_CHANGED", "Không tìm thấy tồn kho của vận đơn."));
            if ((long) balance.onHand() + entry.getValue() > Integer.MAX_VALUE) {
                throw CommerceException.conflict("STOCK_LIMIT", "Tồn kho sau hoàn về vượt giới hạn.");
            }
            int quantity = entry.getValue().intValue();
            if (jdbc.update("""
                    UPDATE inventory_balances SET on_hand_quantity=on_hand_quantity+?,version=version+1
                    WHERE warehouse_id=? AND variant_id=? AND version=?
                    """, quantity, shipment.warehouseId(), balance.variantId(), balance.version()) != 1) {
                throw CommerceException.conflict("STOCK_CHANGED", "Tồn kho vừa thay đổi.");
            }
        }
        for (Line line : lines) {
            jdbc.update("""
                    INSERT INTO inventory_movements(warehouse_id,variant_id,order_item_id,movement_type,
                        quantity_delta,reference_code,idempotency_key,reason,performed_by,created_at)
                    SELECT ?,oi.variant_id,si.order_item_id,'RETURN',?,?,?,?,?,?
                    FROM shipment_items si JOIN order_items oi ON oi.id=si.order_item_id WHERE si.id=?
                    """, shipment.warehouseId(), line.quantity(), "SHIP-" + shipment.id(),
                    "shipreturn:" + shipment.id() + ":" + line.itemId(), "Hàng giao thất bại hoàn về kho",
                    actor, now, line.itemId());
        }
    }

    private boolean allDelivered(long orderId) {
        Long incomplete = jdbc.queryForObject("""
                SELECT COUNT(*) FROM shipments WHERE order_id=? AND status<>'DELIVERED'
                """, Long.class, orderId);
        Long remaining = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.remaining_quantity),0) FROM inventory_reservations r
                JOIN order_items oi ON oi.id=r.order_item_id WHERE oi.order_id=?
                """, Long.class, orderId);
        Long pendingAssembly = jdbc.queryForObject("""
                SELECT COUNT(*) FROM shipments WHERE order_id=? AND installation_required=1
                  AND installation_status<>'COMPLETED'
                """, Long.class, orderId);
        return incomplete != null && incomplete == 0 && remaining != null && remaining == 0
                && pendingAssembly != null && pendingAssembly == 0;
    }

    private ShipmentView current(long id, boolean replayed) {
        return jdbc.query("""
                SELECT s.id,s.order_id,o.order_number,s.warehouse_id,w.code,s.carrier,s.tracking_number,
                       s.status,s.shipped_at,s.delivered_at FROM shipments s
                JOIN orders o ON o.id=s.order_id JOIN warehouses w ON w.id=s.warehouse_id WHERE s.id=?
                """, (rs, row) -> new ShipmentView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                utc(rs.getTimestamp(9)), utc(rs.getTimestamp(10)), replayed), id)
                .stream().findFirst().orElseThrow(() -> CommerceException.notFound("Không tìm thấy vận đơn."));
    }

    private Shipment findShipment(long id, boolean lock) {
        return jdbc.query("SELECT id,order_id,warehouse_id,status FROM shipments WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Shipment(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)), id)
                .stream().findFirst().orElseThrow(() -> CommerceException.notFound("Không tìm thấy vận đơn."));
    }

    private Owner findOwner(long orderId, boolean lock) {
        return jdbc.query("SELECT id,user_id,status FROM orders WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Owner(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3)), orderId)
                .stream().findFirst().orElseThrow(() -> CommerceException.notFound("Không tìm thấy đơn."));
    }

    private Previous previous(String key) {
        return jdbc.query("""
                SELECT CAST(entity_id AS UNSIGNED),
                    JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.status')),
                    JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.reason'))
                FROM audit_logs WHERE entity_type='SHIPMENT' AND action='SHIPMENT_STATUS' AND request_id=?
                """, (rs, row) -> new Previous(rs.getLong(1), rs.getString(2), rs.getString(3)), key)
                .stream().findFirst().orElse(null);
    }

    private static boolean allowed(String from, String to) {
        return "IN_TRANSIT".equals(from) && Set.of("DELIVERED", "FAILED").contains(to)
                || "FAILED".equals(from) && Set.of("IN_TRANSIT", "RETURNED").contains(to);
    }

    private long requireStaff() {
        var actor = actors.currentActor();
        if (actor.userId() == null || Set.of("ADMIN", "STAFF").stream().noneMatch(actor.roles()::contains)) {
            throw CommerceException.forbidden("Chỉ nhân viên hoặc quản trị viên được cập nhật vận đơn.");
        }
        return actor.userId();
    }

    private static void validate(long shipmentId, ShipmentStatusRequest request, String key) {
        if (shipmentId <= 0 || request == null || request.targetStatus() == null
                || !Set.of("DELIVERED", "FAILED", "IN_TRANSIT", "RETURNED")
                .contains(request.targetStatus()) || request.reason() == null || request.reason().isBlank()
                || request.reason().trim().length() > 1000) {
            throw CommerceException.badRequest("INVALID_SHIPMENT_STATUS", "Thông tin trạng thái giao hàng không hợp lệ.");
        }
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
            throw CommerceException.invalid(Map.of("idempotencyKey", "Idempotency-Key cần 8–64 ký tự chữ, số, _ hoặc -."));
        }
    }

    private static String utc(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
    }
}
