package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** One filter contract for the paged list and the complete CSV result. */
final class AdminOrderSearch {
    static final String FROM = " FROM orders o LEFT JOIN users u ON u.id=o.user_id LEFT JOIN order_addresses a ON a.order_id=o.id AND a.address_type='SHIPPING'";
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    record Filter(String where, List<Object> parameters) {}

    static Filter parse(String status, String query, String fromDate, String toDate) {
        status = status == null ? "" : status;
        if (!status.isEmpty() && !OrderLifecyclePolicy.STATUSES.contains(status))
            throw CommerceException.badRequest("INVALID_STATUS", "Trạng thái đơn không hợp lệ.");
        query = query == null ? "" : query.trim();
        if (query.length() > 150) throw CommerceException.badRequest("INVALID_SEARCH", "Từ khóa tối đa150 ký tự.");
        LocalDate from = date(fromDate), to = date(toDate);
        if (from != null && to != null && from.isAfter(to)) throw invalidDates();
        var where = new StringBuilder(" WHERE 1=1");
        var params = new ArrayList<Object>();
        if (!status.isEmpty()) { where.append(" AND o.status=?"); params.add(status); }
        if (!query.isEmpty()) {
            String pattern = "%" + query.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
            where.append(" AND (o.order_number LIKE ? ESCAPE '=' OR u.username LIKE ? ESCAPE '=' OR u.full_name LIKE ? ESCAPE '=' OR u.email LIKE ? ESCAPE '=' OR a.recipient_name LIKE ? ESCAPE '=' OR a.phone LIKE ? ESCAPE '=')");
            for (int i=0; i<6; i++) params.add(pattern);
        }
        if (from != null) { where.append(" AND o.placed_at>=?"); params.add(utc(from)); }
        if (to != null) { where.append(" AND o.placed_at<?"); params.add(utc(to.plusDays(1))); }
        return new Filter(where.toString(), List.copyOf(params));
    }

    private static LocalDate date(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalidDates();
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeException error) { throw invalidDates(); }
    }
    private static LocalDateTime utc(LocalDate day) {
        var utc = LocalDateTime.ofInstant(day.atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
        if (utc.getYear() < 1000 || utc.getYear() > 9999) throw invalidDates();
        return utc;
    }
    private static CommerceException invalidDates() {
        return CommerceException.badRequest("INVALID_DATE_RANGE", "Chọn ngày hợp lệ theo YYYY-MM-DD; từ ngày không được sau đến ngày.");
    }
}
