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
public class InventoryReceiptService {
    private static final String KEY_PATTERN = "[A-Za-z0-9_-]{8,64}";
    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final Clock clock;

    public InventoryReceiptService(JdbcTemplate jdbc, CurrentActorPort actors, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.clock = clock;
    }

    public record WarehouseView(long id, String code, String name) {}
    public record ReceiptEntry(long id, long warehouseId, String warehouseCode, long variantId,
                               String sku, int quantity, String referenceCode, String reason,
                               long performedBy, String createdAt) {}
    public record ReceiptPage(List<ReceiptEntry> items, int page, int size, long totalElements, int totalPages) {}
    public record ReceiptView(long movementId, long warehouseId, String warehouseCode, long variantId,
                              String sku, int receivedQuantity, int onHandQuantity, int reservedQuantity,
                              int availableQuantity, String referenceCode, String createdAt, boolean replayed) {}
    private record ExistingReceipt(long id, long warehouseId, long variantId, int quantity,
                                   String referenceCode, String reason, long performedBy) {}
    private record Balance(int onHand, int reserved, long version) {}

    public List<WarehouseView> activeWarehouses() {
        requireStaff();
        return jdbc.query("SELECT id,code,name FROM warehouses WHERE is_active=1 ORDER BY code,id",
                (rs, row) -> new WarehouseView(rs.getLong("id"), rs.getString("code"), rs.getString("name")));
    }

    public ReceiptPage receipts(Long warehouseId, int page, int size) {
        requireStaff();
        if (page < 0 || size < 1 || size > 100 || (warehouseId != null && warehouseId <= 0)) {
            throw CommerceException.badRequest("INVALID_PAGE", "Trang, kích thước trang hoặc kho không hợp lệ.");
        }
        String where = " WHERE m.movement_type='RECEIPT'" + (warehouseId == null ? "" : " AND m.warehouse_id=?");
        Object[] filter = warehouseId == null ? new Object[]{} : new Object[]{warehouseId};
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements m" + where, Long.class, filter);
        Object[] args = warehouseId == null ? new Object[]{size, (long) page * size}
                : new Object[]{warehouseId, size, (long) page * size};
        List<ReceiptEntry> items = jdbc.query("""
                SELECT m.id,m.warehouse_id,w.code AS warehouse_code,m.variant_id,v.sku,
                       m.quantity_delta,m.reference_code,m.reason,m.performed_by,m.created_at
                FROM inventory_movements m JOIN warehouses w ON w.id=m.warehouse_id
                JOIN product_variants v ON v.id=m.variant_id
                """ + where + " ORDER BY m.id DESC LIMIT ? OFFSET ?", (rs, row) -> new ReceiptEntry(
                rs.getLong("id"), rs.getLong("warehouse_id"), rs.getString("warehouse_code"),
                rs.getLong("variant_id"), rs.getString("sku"), rs.getInt("quantity_delta"),
                rs.getString("reference_code"), rs.getString("reason"), rs.getLong("performed_by"),
                rs.getTimestamp("created_at").toLocalDateTime().atOffset(ZoneOffset.UTC).toString()), args);
        long total = count == null ? 0 : count;
        return new ReceiptPage(items, page, size, total, (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReceiptView receive(InventoryReceiptRequest request, String idempotencyKey) {
        long actorId = requireStaff();
        validate(request, idempotencyKey);
        String key = "receipt:" + actorId + ":" + idempotencyKey;
        // Serializing a staff member's receipts makes a repeated key safe even when
        // the first request is still committing. This also follows checkout's user-first lock order.
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, actorId).isEmpty()) {
            throw CommerceException.forbidden("Tài khoản không còn hoạt động.");
        }
        ExistingReceipt existing = existing(key);
        if (existing != null) return replay(existing, request, actorId);

        var warehouses = jdbc.query("SELECT id,code,is_active FROM warehouses WHERE id=? LOCK IN SHARE MODE",
                (rs, row) -> Map.of("code", rs.getString("code"), "active", rs.getBoolean("is_active")), request.warehouseId());
        if (warehouses.isEmpty()) throw CommerceException.notFound("Không tìm thấy kho.");
        if (!Boolean.TRUE.equals(warehouses.get(0).get("active"))) {
            throw CommerceException.conflict("WAREHOUSE_INACTIVE", "Kho đã ngừng hoạt động.");
        }
        var variants = jdbc.query("""
                SELECT v.sku,v.status AS variant_status,p.status AS product_status
                FROM product_variants v JOIN products p ON p.id=v.product_id WHERE v.id=?
                """, (rs, row) -> Map.of("sku", rs.getString("sku"), "variant", rs.getString("variant_status"),
                "product", rs.getString("product_status")), request.variantId());
        if (variants.isEmpty()) throw CommerceException.notFound("Không tìm thấy SKU.");
        if (!"ACTIVE".equals(variants.get(0).get("variant")) || !"ACTIVE".equals(variants.get(0).get("product"))) {
            throw CommerceException.conflict("SKU_INACTIVE", "SKU đã ngừng bán; không thể nhập hàng mới.");
        }

        jdbc.update("INSERT IGNORE INTO inventory_balances(warehouse_id,variant_id) VALUES(?,?)",
                request.warehouseId(), request.variantId());
        Balance balance = jdbc.queryForObject("""
                SELECT on_hand_quantity,reserved_quantity,version FROM inventory_balances
                WHERE warehouse_id=? AND variant_id=? FOR UPDATE
                """, (rs, row) -> new Balance(rs.getInt(1), rs.getInt(2), rs.getLong(3)),
                request.warehouseId(), request.variantId());
        if (balance == null || (long) balance.onHand() + request.quantity() > Integer.MAX_VALUE) {
            throw CommerceException.conflict("STOCK_LIMIT", "Số lượng sau nhập vượt giới hạn lưu trữ.");
        }
        int changed = jdbc.update("""
                UPDATE inventory_balances SET on_hand_quantity=on_hand_quantity+?,version=version+1
                WHERE warehouse_id=? AND variant_id=? AND version=? AND on_hand_quantity<=?
                """, request.quantity(), request.warehouseId(), request.variantId(), balance.version(),
                Integer.MAX_VALUE - request.quantity());
        if (changed != 1) throw CommerceException.conflict("STOCK_CHANGED", "Tồn kho vừa thay đổi. Vui lòng thử lại.");

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO inventory_movements(warehouse_id,variant_id,movement_type,quantity_delta,
                                                    reference_code,idempotency_key,reason,performed_by,created_at)
                    VALUES(?,?,'RECEIPT',?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.warehouseId());
            statement.setLong(2, request.variantId());
            statement.setInt(3, request.quantity());
            statement.setString(4, request.referenceCode().trim());
            statement.setString(5, key);
            statement.setString(6, request.reason().trim());
            statement.setLong(7, actorId);
            statement.setObject(8, now);
            return statement;
        }, keys);
        return view(keys.getKey().longValue(), false);
    }

    private ReceiptView replay(ExistingReceipt existing, InventoryReceiptRequest request, long actorId) {
        if (existing.performedBy() != actorId || existing.warehouseId() != request.warehouseId()
                || existing.variantId() != request.variantId() || existing.quantity() != request.quantity()
                || !existing.referenceCode().equals(request.referenceCode().trim())
                || !existing.reason().equals(request.reason().trim())) {
            throw CommerceException.conflict("IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã dùng cho một phiếu nhập khác.");
        }
        return view(existing.id(), true);
    }

    private ExistingReceipt existing(String key) {
        return jdbc.query("""
                SELECT id,warehouse_id,variant_id,quantity_delta,reference_code,reason,performed_by
                FROM inventory_movements WHERE idempotency_key=? AND movement_type='RECEIPT'
                """, (rs, row) -> new ExistingReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getInt(4), rs.getString(5), rs.getString(6), rs.getLong(7)), key)
                .stream().findFirst().orElse(null);
    }

    private ReceiptView view(long movementId, boolean replayed) {
        return jdbc.queryForObject("""
                SELECT m.id,m.warehouse_id,w.code AS warehouse_code,m.variant_id,v.sku,
                       m.quantity_delta,b.on_hand_quantity,b.reserved_quantity,m.reference_code,m.created_at
                FROM inventory_movements m JOIN warehouses w ON w.id=m.warehouse_id
                JOIN product_variants v ON v.id=m.variant_id
                JOIN inventory_balances b ON b.warehouse_id=m.warehouse_id AND b.variant_id=m.variant_id
                WHERE m.id=?
                """, (rs, row) -> new ReceiptView(rs.getLong("id"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_code"), rs.getLong("variant_id"), rs.getString("sku"),
                rs.getInt("quantity_delta"), rs.getInt("on_hand_quantity"), rs.getInt("reserved_quantity"),
                rs.getInt("on_hand_quantity") - rs.getInt("reserved_quantity"), rs.getString("reference_code"),
                rs.getTimestamp("created_at").toLocalDateTime().atOffset(ZoneOffset.UTC).toString(), replayed), movementId);
    }

    private long requireStaff() {
        var actor = actors.currentActor();
        if (actor.userId() == null || Set.of("ADMIN", "STAFF").stream().noneMatch(actor.roles()::contains)) {
            throw CommerceException.forbidden("Chỉ nhân viên kho hoặc quản trị viên được nhập hàng.");
        }
        return actor.userId();
    }

    private static void validate(InventoryReceiptRequest request, String key) {
        if (request == null || request.warehouseId() == null || request.warehouseId() <= 0
                || request.variantId() == null || request.variantId() <= 0
                || request.quantity() == null || request.quantity() <= 0
                || request.referenceCode() == null || request.referenceCode().isBlank()
                || request.referenceCode().trim().length() > 100
                || request.reason() == null || request.reason().isBlank()
                || request.reason().trim().length() > 1000) {
            throw CommerceException.badRequest("INVALID_RECEIPT", "Thông tin phiếu nhập không hợp lệ.");
        }
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw CommerceException.invalid(Map.of("idempotencyKey", "Idempotency-Key cần 8–64 ký tự chữ, số, _ hoặc -."));
        }
    }
}
