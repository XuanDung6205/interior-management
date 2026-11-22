package org.example.eshop.commerce.inventory;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.OrderAdminAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ShipmentScheduleService {
    private final JdbcTemplate jdbc;
    private final OrderAdminAccess access;
    private final Clock clock;
    private final ShipmentLifecycleService shipments;

    public ShipmentScheduleService(JdbcTemplate jdbc, OrderAdminAccess access, Clock clock,
            ShipmentLifecycleService shipments) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
        this.shipments = shipments;
    }

    private record Shipment(long orderId, String status, boolean installationRequired,
            String installationStatus) {}
    private record Previous(long shipmentId, String kind, String from, String to, String installationAt) {}
    private record Intent(String kind, Instant from, Instant to, Instant installationAt) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShipmentLifecycleService.ShipmentView schedule(long id, ShipmentScheduleRequest request, String rawKey) {
        long actor = access.requireStaff();
        if (id <= 0 || rawKey == null || !rawKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw CommerceException.badRequest("INVALID_SHIPMENT_SCHEDULE", "Mã vận đơn hoặc mã yêu cầu không hợp lệ.");
        }
        Intent intent = parse(request);
        String key = "shipschedule:" + actor + ":" + rawKey;
        Shipment initial = find(id, false);
        Map<String, Object> order = access.lock(initial.orderId(), actor);
        Shipment current = find(id, true);
        if (current.orderId() != initial.orderId()) {
            throw CommerceException.conflict("SHIPMENT_CHANGED", "Vận đơn vừa thay đổi.");
        }
        Previous previous = previous(key);
        if (previous != null) {
            if (previous.shipmentId() != id || !previous.kind().equals(intent.kind())
                    || !previous.from().equals(value(intent.from())) || !previous.to().equals(value(intent.to()))
                    || !previous.installationAt().equals(value(intent.installationAt()))) {
                throw CommerceException.conflict("IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã dùng cho lịch khác.");
            }
            return shipments.view(id, true);
        }
        if (!"PROCESSING".equals(order.get("status"))) {
            throw CommerceException.conflict("ORDER_STATUS_CONFLICT", "Đơn không còn trong giai đoạn giao/lắp đặt.");
        }
        Instant now = clock.instant();
        if ("DELIVERY".equals(intent.kind())) {
            if (!Set.of("IN_TRANSIT", "FAILED").contains(current.status())) {
                throw CommerceException.conflict("SHIPMENT_STATUS_CONFLICT", "Vận đơn không thể đặt lịch giao.");
            }
            validateFuture(intent.from(), now);
            if (!intent.to().isAfter(intent.from()) || intent.to().isAfter(intent.from().plusSeconds(24 * 3600))) {
                throw CommerceException.badRequest("INVALID_SHIPMENT_SCHEDULE", "Khung giao phải dài hơn 0 và không quá 24 giờ.");
            }
            jdbc.update("UPDATE shipments SET scheduled_from=?,scheduled_to=?,updated_at=? WHERE id=?",
                    utc(intent.from()), utc(intent.to()), utc(now), id);
        } else {
            Object selected = order.get("assemblyRequested");
            if (!(Boolean.TRUE.equals(selected) || selected instanceof Number number && number.intValue() != 0)
                    || !current.installationRequired()
                    || !Set.of("PENDING", "SCHEDULED").contains(current.installationStatus())
                    || !Set.of("IN_TRANSIT", "DELIVERED").contains(current.status())) {
                throw CommerceException.conflict("INSTALLATION_NOT_READY", "Vận đơn không thể đặt lịch lắp đặt.");
            }
            validateFuture(intent.installationAt(), now);
            jdbc.update("UPDATE shipments SET installation_scheduled_at=?,installation_status='SCHEDULED',updated_at=? WHERE id=?",
                    utc(intent.installationAt()), utc(now), id);
        }
        jdbc.update("""
                INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,after_data,request_id,created_at)
                VALUES(?,'SHIPMENT_SCHEDULE','SHIPMENT',?,JSON_OBJECT('kind',?,'from',?,'to',?,'installationAt',?),?,?)
                """, actor, Long.toString(id), intent.kind(), value(intent.from()), value(intent.to()),
                value(intent.installationAt()), key, utc(now));
        return shipments.view(id, false);
    }

    private static Intent parse(ShipmentScheduleRequest request) {
        if (request == null || request.kind() == null) throw invalid();
        if ("DELIVERY".equals(request.kind())) {
            if (request.installationAt() != null) throw invalid();
            return new Intent("DELIVERY", instant(request.scheduledFrom()), instant(request.scheduledTo()), null);
        }
        if ("INSTALLATION".equals(request.kind())) {
            if (request.scheduledFrom() != null || request.scheduledTo() != null) throw invalid();
            return new Intent("INSTALLATION", null, null, instant(request.installationAt()));
        }
        throw invalid();
    }

    private static Instant instant(String value) {
        try {
            if (value == null || value.length() > 40) throw invalid();
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static void validateFuture(Instant time, Instant now) {
        if (!time.isAfter(now) || time.isAfter(now.plusSeconds(90L * 24 * 3600))) {
            throw CommerceException.badRequest("INVALID_SHIPMENT_SCHEDULE", "Lịch phải ở tương lai và trong vòng 90 ngày.");
        }
    }

    private Shipment find(long id, boolean lock) {
        List<Shipment> rows = jdbc.query("SELECT order_id,status,installation_required,installation_status FROM shipments WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Shipment(rs.getLong(1), rs.getString(2),
                rs.getBoolean(3), rs.getString(4)), id);
        if (rows.isEmpty()) throw CommerceException.notFound("Không tìm thấy vận đơn.");
        return rows.get(0);
    }

    private Previous previous(String key) {
        return jdbc.query("""
                SELECT CAST(entity_id AS UNSIGNED),
                  JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.kind')),
                  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.from')),''),
                  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.to')),''),
                  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.installationAt')),'')
                FROM audit_logs WHERE entity_type='SHIPMENT' AND action='SHIPMENT_SCHEDULE' AND request_id=?
                """, (rs, row) -> new Previous(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), key).stream().findFirst().orElse(null);
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static String value(Instant value) { return value == null ? "" : value.toString(); }
    private static CommerceException invalid() {
        return CommerceException.badRequest("INVALID_SHIPMENT_SCHEDULE", "Thông tin lịch giao/lắp đặt không hợp lệ.");
    }
}
