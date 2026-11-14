package org.example.eshop.commerce.inventory;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class OrderDispatchService {
    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public OrderDispatchService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
    }

    public record HoldView(long reservationId, long orderItemId, long warehouseId, String warehouseCode,
                           String sku, int quantity, int fulfilledQuantity, int releasedQuantity, int remainingQuantity) {}
    public record DispatchPreview(long orderId, String orderNumber, String status, List<HoldView> holds) {}
    public record QueueItem(long orderId, String orderNumber, String status, int remainingQuantity) {}
    public record QueuePage(List<QueueItem> items, int page, int size, long totalElements, int totalPages) {}
    public record DispatchItem(long orderItemId, String sku, int quantity) {}
    public record DispatchResult(long shipmentId, long orderId, long warehouseId, String status,
                                 String carrier, String trackingNumber, List<DispatchItem> items, boolean replayed) {}
    private record Order(long id, Long owner, String number, String status, Long couponId) {}
    private record Hold(long id, long itemId, long warehouseId, long variantId, String sku, int remaining) {}
    private record Balance(long warehouseId, long variantId, int onHand, int reserved, long version) {}
    private record Prior(long id, long orderId, long warehouseId, String carrier, String tracking, String note) {}

    public QueuePage queue(int page, int size) {
        requireStaff();
        if (page < 0 || size < 1 || size > 100) throw CommerceException.badRequest("INVALID_PAGE", "Trang không hợp lệ.");
        String base = """
                FROM orders o JOIN order_items oi ON oi.order_id=o.id
                JOIN inventory_reservations r ON r.order_item_id=oi.id
                WHERE o.status IN ('PENDING_CONFIRMATION','CONFIRMED','PROCESSING')
                  AND r.remaining_quantity>0
                """;
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT o.id) " + base, Long.class);
        var items = jdbc.query("""
                SELECT o.id,o.order_number,o.status,SUM(r.remaining_quantity) AS remaining
                """ + base + " GROUP BY o.id,o.order_number,o.status ORDER BY o.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new QueueItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                size, (long) page * size);
        long total = count == null ? 0 : count;
        return new QueuePage(items, page, size, total, (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
    }

    public DispatchPreview preview(long orderId) {
        requireStaff();
        if (orderId <= 0) throw CommerceException.badRequest("INVALID_ORDER", "Mã đơn không hợp lệ.");
        Order order = findOrder(orderId, false);
        var holds = jdbc.query("""
                SELECT r.id,r.order_item_id,r.warehouse_id,w.code,oi.sku,r.quantity,
                       r.fulfilled_quantity,r.released_quantity,r.remaining_quantity
                FROM inventory_reservations r JOIN order_items oi ON oi.id=r.order_item_id
                JOIN warehouses w ON w.id=r.warehouse_id
                WHERE oi.order_id=? ORDER BY r.variant_id,r.warehouse_id,r.id
                """, (rs, row) -> new HoldView(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)), orderId);
        return new DispatchPreview(order.id(), order.number(), order.status(), holds);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DispatchResult dispatch(long orderId, OrderDispatchRequest request, String rawKey) {
        long actor = requireStaff();
        validate(orderId, request, rawKey);
        String key = "dispatch:" + actor + ":" + rawKey;
        // The owner row precedes the order root, matching checkout and cancellation.
        Order snapshot = findOrder(orderId, false);
        if (snapshot.owner() == null) throw CommerceException.conflict("ORDER_UNAVAILABLE", "Đơn không có tài khoản sở hữu.");
        for (long userId : List.of(actor, snapshot.owner()).stream().distinct().sorted().toList()) {
            jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
        }
        Order order = findOrder(orderId, true);
        if (!snapshot.owner().equals(order.owner())) throw CommerceException.conflict("ORDER_CHANGED", "Chủ đơn vừa thay đổi.");
        Prior previous = prior(key);
        if (previous != null) {
            if (previous.orderId() != orderId || previous.warehouseId() != request.warehouseId()
                    || !previous.carrier().equals(request.carrier().trim())
                    || !previous.tracking().equals(request.trackingNumber().trim())
                    || !previous.note().equals(cleanNote(request.note()))) {
                throw CommerceException.conflict("IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã dùng cho phiếu giao khác.");
            }
            return result(previous.id(), true);
        }
        if (!Set.of("PENDING_CONFIRMATION", "CONFIRMED", "PROCESSING").contains(order.status())) {
            throw CommerceException.conflict("ORDER_NOT_DISPATCHABLE", "Đơn không ở trạng thái có thể xuất kho.");
        }
        if (order.couponId() != null) jdbc.queryForList("SELECT id FROM coupons WHERE id=? FOR UPDATE", Long.class, order.couponId());
        var payments = jdbc.query("SELECT method,status FROM payments WHERE order_id=? ORDER BY id FOR UPDATE",
                (rs, row) -> Map.entry(rs.getString(1), rs.getString(2)), orderId);
        if (payments.stream().anyMatch(payment -> !"COD".equals(payment.getKey()) && !"SUCCEEDED".equals(payment.getValue()))) {
            throw CommerceException.conflict("PAYMENT_UNVERIFIED", "Chưa xác minh thanh toán để xuất kho.");
        }
        var warehouses = jdbc.query("SELECT id,is_active FROM warehouses ORDER BY id LOCK IN SHARE MODE",
                (rs, row) -> Map.entry(rs.getLong(1), rs.getBoolean(2)));
        if (warehouses.stream().noneMatch(entry -> entry.getKey().equals(request.warehouseId()) && entry.getValue())) {
            throw CommerceException.conflict("WAREHOUSE_INACTIVE", "Kho không tồn tại hoặc đã ngừng hoạt động.");
        }
        // Reservation children are current-read after locking the order; cancellation uses the same order lock.
        var holds = jdbc.query("""
                SELECT r.id,r.order_item_id,r.warehouse_id,r.variant_id,oi.sku,r.remaining_quantity
                FROM inventory_reservations r JOIN order_items oi ON oi.id=r.order_item_id
                WHERE oi.order_id=? ORDER BY r.variant_id,r.warehouse_id,r.id FOR UPDATE
                """, (rs, row) -> new Hold(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getString(5), rs.getInt(6)), orderId).stream()
                .filter(hold -> hold.warehouseId() == request.warehouseId() && hold.remaining() > 0).toList();
        if (holds.isEmpty()) throw CommerceException.conflict("NO_HELD_STOCK", "Kho này không còn hàng đã giữ cho đơn.");
        var balances = new LinkedHashMap<Long, Balance>();
        var quantities = new LinkedHashMap<Long, Long>();
        for (Hold hold : holds) {
            if (!balances.containsKey(hold.variantId())) {
                Balance balance = jdbc.query("""
                        SELECT warehouse_id,variant_id,on_hand_quantity,reserved_quantity,version
                        FROM inventory_balances WHERE warehouse_id=? AND variant_id=? FOR UPDATE
                        """, (rs, row) -> new Balance(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                        rs.getInt(4), rs.getLong(5)), hold.warehouseId(), hold.variantId())
                        .stream().findFirst().orElseThrow(OrderDispatchService::stockConflict);
                balances.put(hold.variantId(), balance);
            }
            quantities.merge(hold.variantId(), (long) hold.remaining(), Long::sum);
        }
        for (var entry : quantities.entrySet()) {
            Balance balance = balances.get(entry.getKey());
            if (balance.onHand() < entry.getValue() || balance.reserved() < entry.getValue()) throw stockConflict();
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        long shipmentId = insertShipment(orderId, request, key, now);
        for (var entry : quantities.entrySet()) {
            Balance balance = balances.get(entry.getKey());
            int quantity = entry.getValue().intValue();
            int changed = jdbc.update("""
                    UPDATE inventory_balances SET on_hand_quantity=on_hand_quantity-?,
                        reserved_quantity=reserved_quantity-?,version=version+1
                    WHERE warehouse_id=? AND variant_id=? AND version=?
                      AND on_hand_quantity>=? AND reserved_quantity>=?
                    """, quantity, quantity, balance.warehouseId(), balance.variantId(), balance.version(), quantity, quantity);
            if (changed != 1) throw stockConflict();
        }
        for (Hold hold : holds) {
            int quantity = hold.remaining();
            int changed = jdbc.update("""
                    UPDATE inventory_reservations SET fulfilled_quantity=fulfilled_quantity+?
                    WHERE id=? AND remaining_quantity=?
                    """, quantity, hold.id(), quantity);
            if (changed != 1) throw stockConflict();
            jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(?,?,?,?)",
                    shipmentId, orderId, hold.itemId(), quantity);
            jdbc.update("""
                    INSERT INTO inventory_movements(warehouse_id,variant_id,order_item_id,movement_type,
                        quantity_delta,reference_code,idempotency_key,reason,performed_by,created_at)
                    VALUES(?,?,?,'SHIPMENT',?,?,?,?,?,?)
                    """, hold.warehouseId(), hold.variantId(), hold.itemId(), -quantity,
                    "SHIP-" + shipmentId, "dispatch:" + shipmentId + ":" + hold.id(),
                    "Xuất kho đơn " + order.number(), actor, now);
        }
        if (!"PROCESSING".equals(order.status())) {
            jdbc.update("UPDATE orders SET status='PROCESSING',updated_at=? WHERE id=?", now, orderId);
            jdbc.update("""
                    INSERT INTO order_status_history(order_id,from_status,to_status,changed_by,note,changed_at)
                    VALUES(?,?,'PROCESSING',?,?,?)
                    """, orderId, order.status(), actor, "Xuất kho giao hàng", now);
        }
        return result(shipmentId, false);
    }

    private long insertShipment(long orderId, OrderDispatchRequest request, String key, LocalDateTime now) {
        if (!jdbc.queryForList("SELECT id FROM shipments WHERE carrier=? AND tracking_number=?",
                Long.class, request.carrier().trim(), request.trackingNumber().trim()).isEmpty()) {
            throw CommerceException.conflict("TRACKING_IN_USE", "Mã vận đơn đã được sử dụng.");
        }
        var holder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO shipments(order_id,warehouse_id,carrier,tracking_number,status,
                            shipped_at,note,idempotency_key,created_at,updated_at)
                        VALUES(?,?,?,?,'IN_TRANSIT',?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, orderId);
                statement.setLong(2, request.warehouseId());
                statement.setString(3, request.carrier().trim());
                statement.setString(4, request.trackingNumber().trim());
                statement.setObject(5, now);
                statement.setString(6, cleanNote(request.note()));
                statement.setString(7, key);
                statement.setObject(8, now);
                statement.setObject(9, now);
                return statement;
            }, holder);
        } catch (DataIntegrityViolationException duplicate) {
            throw CommerceException.conflict("TRACKING_IN_USE", "Mã vận đơn hoặc mã yêu cầu đã được sử dụng.");
        }
        return holder.getKey().longValue();
    }

    private DispatchResult result(long shipmentId, boolean replayed) {
        var rows = jdbc.query("""
                SELECT s.id,s.order_id,s.warehouse_id,s.status,s.carrier,s.tracking_number
                FROM shipments s WHERE s.id=?
                """, (rs, row) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getString(6)}, shipmentId);
        if (rows.isEmpty()) throw CommerceException.notFound("Không tìm thấy phiếu giao.");
        Object[] values = rows.get(0);
        var items = jdbc.query("""
                SELECT si.order_item_id,oi.sku,si.quantity FROM shipment_items si
                JOIN order_items oi ON oi.id=si.order_item_id WHERE si.shipment_id=? ORDER BY si.order_item_id
                """, (rs, row) -> new DispatchItem(rs.getLong(1), rs.getString(2), rs.getInt(3)), shipmentId);
        return new DispatchResult((long) values[0], (long) values[1], (long) values[2],
                (String) values[3], (String) values[4], (String) values[5], items, replayed);
    }

    private Prior prior(String key) {
        return jdbc.query("SELECT id,order_id,warehouse_id,carrier,tracking_number,COALESCE(note,'') FROM shipments WHERE idempotency_key=?",
                (rs, row) -> new Prior(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), key).stream().findFirst().orElse(null);
    }

    private Order findOrder(long id, boolean lock) {
        return jdbc.query("SELECT id,user_id,order_number,status,coupon_id FROM orders WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Order(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3),
                        rs.getString(4), (Long) rs.getObject(5)), id)
                .stream().findFirst().orElseThrow(() -> CommerceException.notFound("Không tìm thấy đơn hàng."));
    }

    private long requireStaff() {
        var actor = actors.currentActor();
        if (actor.userId() == null || Set.of("ADMIN", "STAFF").stream().noneMatch(actor.roles()::contains)) {
            throw CommerceException.forbidden("Chỉ nhân viên hoặc quản trị viên được xuất kho.");
        }
        return actor.userId();
    }

    private static void validate(long orderId, OrderDispatchRequest request, String key) {
        if (orderId <= 0 || request == null || request.warehouseId() == null || request.warehouseId() <= 0
                || request.carrier() == null || request.carrier().isBlank() || request.carrier().trim().length() > 100
                || request.trackingNumber() == null || request.trackingNumber().isBlank()
                || request.trackingNumber().trim().length() > 150
                || request.note() != null && request.note().length() > 1000) {
            throw CommerceException.badRequest("INVALID_DISPATCH", "Thông tin xuất kho không hợp lệ.");
        }
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
            throw CommerceException.invalid(Map.of("idempotencyKey", "Idempotency-Key cần 8–64 ký tự chữ, số, _ hoặc -."));
        }
    }

    private static String cleanNote(String note) { return note == null ? "" : note.trim(); }
    private static CommerceException stockConflict() {
        return CommerceException.conflict("STOCK_RESERVATION_CONFLICT", "Hàng đã giữ hoặc tồn kho vừa thay đổi.");
    }
}
