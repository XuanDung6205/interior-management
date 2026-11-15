package org.example.eshop.commerce.inventory;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class StockOperationsService {
    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public StockOperationsService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
    }

    public record StockBalanceView(long warehouseId, String warehouseCode, long variantId, String sku,
                                   int onHandQuantity, int reservedQuantity, int availableQuantity) {}
    public record AdjustmentView(long movementId, int quantityDelta, StockBalanceView balance, boolean replayed) {}
    public record TransferView(long outMovementId, long inMovementId, int quantity,
                               StockBalanceView source, StockBalanceView destination, boolean replayed) {}
    public record MovementView(long id, String type, long warehouseId, String warehouseCode, long variantId,
                               String sku, int quantityDelta, String referenceCode, String reason,
                               long performedBy, String createdAt) {}
    public record MovementPage(List<MovementView> items, int page, int size, long totalElements, int totalPages) {}
    private record Warehouse(long id, boolean active) {}
    private record Balance(long warehouse, long variant, int onHand, int reserved, long version) {
        int available() { return onHand - reserved; }
    }
    private record Movement(long id, long warehouse, long variant, String type, int delta,
                            String reference, String reason, long actor) {}

    public StockBalanceView balance(long warehouseId, long variantId) {
        requireStaff();
        if (warehouseId <= 0 || variantId <= 0) {
            throw CommerceException.badRequest("INVALID_BALANCE", "Kho hoặc SKU không hợp lệ.");
        }
        return currentBalance(warehouseId, variantId);
    }

    public MovementPage movements(Long warehouseId, int page, int size) {
        requireStaff();
        if (page < 0 || size < 1 || size > 100 || (warehouseId != null && warehouseId <= 0)) {
            throw CommerceException.badRequest("INVALID_PAGE", "Trang, kích thước trang hoặc kho không hợp lệ.");
        }
        String filter = warehouseId == null ? "" : " WHERE m.warehouse_id=?";
        Object[] countArgs = warehouseId == null ? new Object[]{} : new Object[]{warehouseId};
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements m" + filter, Long.class, countArgs);
        Object[] args = warehouseId == null ? new Object[]{size, (long) page * size}
                : new Object[]{warehouseId, size, (long) page * size};
        var items = jdbc.query("""
                SELECT m.id,m.movement_type,m.warehouse_id,w.code AS warehouse_code,m.variant_id,v.sku,
                       m.quantity_delta,m.reference_code,m.reason,m.performed_by,m.created_at
                FROM inventory_movements m JOIN warehouses w ON w.id=m.warehouse_id
                JOIN product_variants v ON v.id=m.variant_id
                """ + filter + " ORDER BY m.id DESC LIMIT ? OFFSET ?", (rs, row) -> new MovementView(
                rs.getLong("id"), rs.getString("movement_type"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_code"), rs.getLong("variant_id"), rs.getString("sku"),
                rs.getInt("quantity_delta"), rs.getString("reference_code"), rs.getString("reason"),
                rs.getLong("performed_by"), rs.getTimestamp("created_at").toLocalDateTime()
                        .atOffset(ZoneOffset.UTC).toString()), args);
        long total = count == null ? 0 : count;
        return new MovementPage(items, page, size, total, (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdjustmentView adjust(StockAdjustmentRequest request, String rawKey) {
        long actor = requireStaff();
        validateAdjustment(request, rawKey);
        String key = "adjust:" + actor + ":" + rawKey;
        lockActor(actor);
        Movement previous = movement(key);
        if (previous != null) {
            if (!matches(previous, request.warehouseId(), request.variantId(), "ADJUSTMENT",
                    request.quantityDelta(), request.referenceCode(), request.reason(), actor)) throw keyConflict();
            return new AdjustmentView(previous.id(), previous.delta(),
                    currentBalance(previous.warehouse(), previous.variant()), true);
        }
        lockWarehouses(List.of(request.warehouseId()));
        requireVariant(request.variantId());
        ensureBalance(request.warehouseId(), request.variantId());
        Balance balance = lockBalance(request.warehouseId(), request.variantId());
        long next = (long) balance.onHand() + request.quantityDelta();
        if (next < balance.reserved()) throw CommerceException.conflict("STOCK_RESERVED",
                "Không thể giảm tồn dưới số lượng đang giữ cho đơn hàng.");
        if (next > Integer.MAX_VALUE) throw CommerceException.conflict("STOCK_LIMIT", "Tồn kho vượt giới hạn lưu trữ.");
        updateBalance(balance, request.quantityDelta());
        long movementId = insertMovement(request.warehouseId(), request.variantId(), "ADJUSTMENT",
                request.quantityDelta(), request.referenceCode(), key, request.reason(), actor);
        return new AdjustmentView(movementId, request.quantityDelta(),
                currentBalance(request.warehouseId(), request.variantId()), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferView transfer(StockTransferRequest request, String rawKey) {
        long actor = requireStaff();
        validateTransfer(request, rawKey);
        String base = "transfer:" + actor + ":" + rawKey;
        String outKey = base + ":out";
        String inKey = base + ":in";
        lockActor(actor);
        Movement previousOut = movement(outKey);
        Movement previousIn = movement(inKey);
        if (previousOut != null || previousIn != null) {
            if (previousOut == null || previousIn == null
                    || !matches(previousOut, request.sourceWarehouseId(), request.variantId(), "TRANSFER_OUT",
                            -request.quantity(), request.referenceCode(), request.reason(), actor)
                    || !matches(previousIn, request.destinationWarehouseId(), request.variantId(), "TRANSFER_IN",
                            request.quantity(), request.referenceCode(), request.reason(), actor)) throw keyConflict();
            return new TransferView(previousOut.id(), previousIn.id(), request.quantity(),
                    currentBalance(request.sourceWarehouseId(), request.variantId()),
                    currentBalance(request.destinationWarehouseId(), request.variantId()), true);
        }
        List<Long> warehouses = List.of(request.sourceWarehouseId(), request.destinationWarehouseId())
                .stream().sorted().toList();
        lockWarehouses(warehouses);
        requireVariant(request.variantId());
        for (long warehouse : warehouses) ensureBalance(warehouse, request.variantId());
        Balance first = lockBalance(warehouses.get(0), request.variantId());
        Balance second = lockBalance(warehouses.get(1), request.variantId());
        Balance source = first.warehouse() == request.sourceWarehouseId() ? first : second;
        Balance destination = first.warehouse() == request.destinationWarehouseId() ? first : second;
        if (source.available() < request.quantity()) throw CommerceException.conflict("STOCK_UNAVAILABLE",
                "Kho nguồn không đủ hàng khả dụng để chuyển.");
        if ((long) destination.onHand() + request.quantity() > Integer.MAX_VALUE) {
            throw CommerceException.conflict("STOCK_LIMIT", "Tồn kho đích vượt giới hạn lưu trữ.");
        }
        // Balance locks are acquired in warehouse ID order; both updates and movement
        // rows commit together, so an interrupted transfer never leaves a one-sided stock change.
        updateBalance(source, -request.quantity());
        updateBalance(destination, request.quantity());
        long outId = insertMovement(source.warehouse(), request.variantId(), "TRANSFER_OUT",
                -request.quantity(), request.referenceCode(), outKey, request.reason(), actor);
        long inId = insertMovement(destination.warehouse(), request.variantId(), "TRANSFER_IN",
                request.quantity(), request.referenceCode(), inKey, request.reason(), actor);
        return new TransferView(outId, inId, request.quantity(),
                currentBalance(source.warehouse(), request.variantId()),
                currentBalance(destination.warehouse(), request.variantId()), false);
    }

    private long requireStaff() {
        var actor = actors.currentActor();
        if (actor.userId() == null || Set.of("ADMIN", "STAFF").stream().noneMatch(actor.roles()::contains)) {
            throw CommerceException.forbidden("Chỉ nhân viên kho hoặc quản trị viên được thay đổi tồn kho.");
        }
        return actor.userId();
    }

    private void lockActor(long actor) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, actor).isEmpty()) {
            throw CommerceException.forbidden("Tài khoản không còn hoạt động.");
        }
    }

    private void lockWarehouses(List<Long> ids) {
        List<Warehouse> found;
        if (ids.size() == 1) {
            found = jdbc.query("SELECT id,is_active FROM warehouses WHERE id=? LOCK IN SHARE MODE",
                    (rs, row) -> new Warehouse(rs.getLong(1), rs.getBoolean(2)), ids.get(0));
        } else {
            found = jdbc.query("SELECT id,is_active FROM warehouses WHERE id IN (?,?) ORDER BY id LOCK IN SHARE MODE",
                    (rs, row) -> new Warehouse(rs.getLong(1), rs.getBoolean(2)), ids.get(0), ids.get(1));
        }
        if (found.size() != ids.size()) throw CommerceException.notFound("Không tìm thấy kho.");
        if (found.stream().anyMatch(warehouse -> !warehouse.active())) {
            throw CommerceException.conflict("WAREHOUSE_INACTIVE", "Kho đã ngừng hoạt động.");
        }
    }

    private void requireVariant(long variantId) {
        if (jdbc.queryForList("SELECT id FROM product_variants WHERE id=?", Long.class, variantId).isEmpty()) {
            throw CommerceException.notFound("Không tìm thấy SKU.");
        }
    }

    private void ensureBalance(long warehouseId, long variantId) {
        jdbc.update("INSERT IGNORE INTO inventory_balances(warehouse_id,variant_id) VALUES(?,?)", warehouseId, variantId);
    }

    private Balance lockBalance(long warehouseId, long variantId) {
        return jdbc.queryForObject("""
                SELECT warehouse_id,variant_id,on_hand_quantity,reserved_quantity,version
                FROM inventory_balances WHERE warehouse_id=? AND variant_id=? FOR UPDATE
                """, (rs, row) -> new Balance(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)),
                warehouseId, variantId);
    }

    private void updateBalance(Balance balance, int delta) {
        int changed = jdbc.update("""
                UPDATE inventory_balances SET on_hand_quantity=on_hand_quantity+?,version=version+1
                WHERE warehouse_id=? AND variant_id=? AND version=?
                  AND on_hand_quantity+?>=reserved_quantity AND on_hand_quantity+?<=2147483647
                """, delta, balance.warehouse(), balance.variant(), balance.version(), delta, delta);
        if (changed != 1) throw CommerceException.conflict("STOCK_CHANGED", "Tồn kho vừa thay đổi. Vui lòng thử lại.");
    }

    private long insertMovement(long warehouseId, long variantId, String type, int delta,
                                String reference, String key, String reason, long actor) {
        var keys = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO inventory_movements(warehouse_id,variant_id,movement_type,quantity_delta,
                                                    reference_code,idempotency_key,reason,performed_by,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, warehouseId);
            statement.setLong(2, variantId);
            statement.setString(3, type);
            statement.setInt(4, delta);
            statement.setString(5, reference.trim());
            statement.setString(6, key);
            statement.setString(7, reason.trim());
            statement.setLong(8, actor);
            statement.setObject(9, now);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private Movement movement(String key) {
        return jdbc.query("""
                SELECT id,warehouse_id,variant_id,movement_type,quantity_delta,reference_code,reason,performed_by
                FROM inventory_movements WHERE idempotency_key=?
                """, (rs, row) -> new Movement(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8)), key)
                .stream().findFirst().orElse(null);
    }

    private StockBalanceView currentBalance(long warehouseId, long variantId) {
        var rows = jdbc.query("""
                SELECT w.id AS warehouse_id,w.code,v.id AS variant_id,v.sku,
                       COALESCE(b.on_hand_quantity,0) AS on_hand_quantity,
                       COALESCE(b.reserved_quantity,0) AS reserved_quantity
                FROM warehouses w CROSS JOIN product_variants v
                LEFT JOIN inventory_balances b ON b.warehouse_id=w.id AND b.variant_id=v.id
                WHERE w.id=? AND v.id=?
                """, (rs, row) -> new StockBalanceView(rs.getLong("warehouse_id"), rs.getString("code"),
                rs.getLong("variant_id"), rs.getString("sku"), rs.getInt("on_hand_quantity"),
                rs.getInt("reserved_quantity"), rs.getInt("on_hand_quantity") - rs.getInt("reserved_quantity")),
                warehouseId, variantId);
        if (rows.isEmpty()) throw CommerceException.notFound("Không tìm thấy kho hoặc SKU.");
        return rows.get(0);
    }

    private static boolean matches(Movement movement, long warehouseId, long variantId, String type,
                                   int delta, String reference, String reason, long actor) {
        return movement.warehouse() == warehouseId && movement.variant() == variantId
                && movement.type().equals(type) && movement.delta() == delta && movement.actor() == actor
                && movement.reference().equals(reference.trim()) && movement.reason().equals(reason.trim());
    }

    private static void validateFields(String key, String reference, String reason) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
            throw CommerceException.invalid(Map.of("idempotencyKey", "Idempotency-Key cần 8–64 ký tự chữ, số, _ hoặc -."));
        }
        if (reference == null || reference.isBlank() || reference.trim().length() > 100
                || reason == null || reason.isBlank() || reason.trim().length() > 1000) {
            throw CommerceException.badRequest("INVALID_STOCK_OPERATION", "Mã chứng từ hoặc lý do không hợp lệ.");
        }
    }

    private static void validateAdjustment(StockAdjustmentRequest request, String key) {
        if (request == null || request.warehouseId() == null || request.warehouseId() <= 0
                || request.variantId() == null || request.variantId() <= 0
                || request.quantityDelta() == null || request.quantityDelta() == 0) {
            throw CommerceException.badRequest("INVALID_ADJUSTMENT", "Thông tin điều chỉnh tồn không hợp lệ.");
        }
        validateFields(key, request.referenceCode(), request.reason());
    }

    private static void validateTransfer(StockTransferRequest request, String key) {
        if (request == null || request.sourceWarehouseId() == null || request.sourceWarehouseId() <= 0
                || request.destinationWarehouseId() == null || request.destinationWarehouseId() <= 0
                || request.sourceWarehouseId().equals(request.destinationWarehouseId())
                || request.variantId() == null || request.variantId() <= 0
                || request.quantity() == null || request.quantity() <= 0) {
            throw CommerceException.badRequest("INVALID_TRANSFER", "Thông tin chuyển kho không hợp lệ.");
        }
        validateFields(key, request.referenceCode(), request.reason());
    }

    private static CommerceException keyConflict() {
        return CommerceException.conflict("IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã dùng cho thao tác kho khác.");
    }
}
