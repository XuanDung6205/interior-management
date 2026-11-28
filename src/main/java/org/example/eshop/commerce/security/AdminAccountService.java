package org.example.eshop.commerce.security;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdminAccountService {
    private static final Set<String> SWITCHABLE_ROLES = Set.of("STAFF", "CUSTOMER");
    private static final String USER_SELECT = """
            SELECT u.id,u.username,u.full_name,u.email,u.phone,u.status,
                   GROUP_CONCAT(DISTINCT r.code ORDER BY r.code SEPARATOR ',') AS roles
            FROM users u
            LEFT JOIN user_roles ur ON ur.user_id=u.id
            LEFT JOIN roles r ON r.id=ur.role_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AdminAccountService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Account(long id, String username, String fullName, String email,
                          String phone, String status, List<String> roles) {}
    public record AccountPage(List<Account> items, int page, int size, long totalElements, int totalPages) {}

    @Transactional(readOnly = true)
    public AccountPage list(String query, String role, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 150)) {
            throw CommerceException.badRequest("INVALID_PAGINATION", "Trang, số dòng hoặc từ khóa không hợp lệ.");
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalizedRole.isEmpty() && !Set.of("ADMIN", "STAFF", "CUSTOMER").contains(normalizedRole)) {
            throw CommerceException.badRequest("INVALID_ROLE", "Vai trò không hợp lệ.");
        }
        if (!normalizedStatus.isEmpty() && !Set.of("ACTIVE", "LOCKED", "DISABLED").contains(normalizedStatus)) {
            throw CommerceException.badRequest("INVALID_STATUS", "Trạng thái không hợp lệ.");
        }

        String where = """
                WHERE (:query='' OR LOWER(u.username) LIKE :pattern
                    OR LOWER(u.full_name) LIKE :pattern OR LOWER(u.email) LIKE :pattern)
                  AND (:status='' OR u.status=:status)
                  AND (:role='' OR EXISTS (
                      SELECT 1 FROM user_roles filter_ur
                      JOIN roles filter_r ON filter_r.id=filter_ur.role_id
                      WHERE filter_ur.user_id=u.id AND filter_r.code=:role))
                """;
        var params = new MapSqlParameterSource()
                .addValue("query", normalizedQuery)
                .addValue("pattern", "%" + normalizedQuery + "%")
                .addValue("role", normalizedRole)
                .addValue("status", normalizedStatus);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u " + where, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<Account> items = jdbc.query(USER_SELECT + where
                        + " GROUP BY u.id ORDER BY u.id DESC LIMIT :limit OFFSET :offset",
                params, (rs, row) -> account(
                        rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getString("status"),
                        rs.getString("roles")));
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new AccountPage(items, page, size, total, totalPages);
    }

    @Transactional
    public Account changeStatus(long targetId, String desiredStatus, long actorId) {
        if (!Set.of("ACTIVE", "LOCKED").contains(desiredStatus == null ? "" : desiredStatus)) {
            throw CommerceException.badRequest("INVALID_STATUS", "Chỉ có thể mở hoặc khóa tài khoản.");
        }

        Long adminRoleId = jdbc.query("SELECT id FROM roles WHERE code='ADMIN' FOR UPDATE",
                        (rs, row) -> rs.getLong(1))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("ADMIN role is unavailable."));

        var rows = jdbc.query("SELECT id,username,full_name,status FROM users WHERE id=:id FOR UPDATE",
                Map.of("id", targetId),
                (rs, row) -> new LockedAccount(rs.getLong("id"), rs.getString("username"),
                        rs.getString("full_name"), rs.getString("status")));
        if (rows.isEmpty()) throw CommerceException.notFound("Không tìm thấy tài khoản.");
        LockedAccount target = rows.get(0);

        if (desiredStatus.equals(target.status())) return find(targetId);
        if (!Set.of("ACTIVE", "LOCKED").contains(target.status())) {
            throw CommerceException.conflict("ACCOUNT_STATUS_UNSUPPORTED",
                    "Tài khoản đang ở trạng thái không thể mở hoặc khóa từ màn hình này.");
        }
        if ("LOCKED".equals(desiredStatus) && targetId == actorId) {
            throw CommerceException.conflict("CANNOT_LOCK_SELF", "Bạn không thể khóa tài khoản đang đăng nhập.");
        }

        List<String> roles = jdbc.queryForList("""
                        SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                        WHERE ur.user_id=:id
                        """, Map.of("id", targetId), String.class);
        if ("LOCKED".equals(desiredStatus) && roles.contains("ADMIN")) {
            Long activeAdmins = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM users u
                            JOIN user_roles ur ON ur.user_id=u.id
                            WHERE ur.role_id=:roleId AND u.status='ACTIVE'
                            """, Map.of("roleId", adminRoleId), Long.class);
            if (activeAdmins <= 1) {
                throw CommerceException.conflict("LAST_ACTIVE_ADMIN",
                        "Không thể khóa ADMIN hoạt động cuối cùng.");
            }
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("UPDATE users SET status=:status,updated_at=:updatedAt WHERE id=:id",
                Map.of("status", desiredStatus, "updatedAt", now, "id", targetId));
        String actorName = jdbc.queryForObject(
                "SELECT COALESCE(full_name,username) FROM users WHERE id=:id",
                Map.of("id", actorId), String.class);
        String action = "LOCKED".equals(desiredStatus) ? "USER_ACCOUNT_LOCKED" : "USER_ACCOUNT_UNLOCKED";
        jdbc.update("""
                        INSERT INTO audit_logs(actor_user_id,actor_name_snapshot,action,entity_type,entity_id,
                                               before_data,after_data,request_id,created_at)
                        VALUES(:actorId,:actorName,:action,'USER',:entityId,:beforeData,:afterData,:requestId,:createdAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("actorId", actorId)
                        .addValue("actorName", actorName)
                        .addValue("action", action)
                        .addValue("entityId", Long.toString(targetId))
                        .addValue("beforeData", mapper.writeValueAsString(Map.of(
                                "username", target.username(), "status", target.status())))
                        .addValue("afterData", mapper.writeValueAsString(Map.of(
                                "username", target.username(), "status", desiredStatus)))
                        .addValue("requestId", UUID.randomUUID().toString())
                        .addValue("createdAt", now));
        return find(targetId);
    }

    @Transactional
    public Account changeRole(long targetId, String desiredRole, long actorId) {
        String roleCode = desiredRole == null ? "" : desiredRole.trim().toUpperCase(Locale.ROOT);
        if (!SWITCHABLE_ROLES.contains(roleCode)) {
            throw CommerceException.badRequest("INVALID_ROLE", "Chỉ có thể chuyển vai trò STAFF hoặc CUSTOMER.");
        }

        List<LockedAccount> accounts = jdbc.query(
                "SELECT id,username,full_name,status FROM users WHERE id=:id FOR UPDATE",
                Map.of("id", targetId),
                (rs, row) -> new LockedAccount(rs.getLong("id"), rs.getString("username"),
                        rs.getString("full_name"), rs.getString("status")));
        if (accounts.isEmpty()) throw CommerceException.notFound("Không tìm thấy tài khoản.");
        LockedAccount target = accounts.get(0);

        List<String> currentRoles = jdbc.queryForList("""
                        SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                        WHERE ur.user_id=:id ORDER BY r.code
                        """, Map.of("id", targetId), String.class);
        if (currentRoles.contains("ADMIN")) {
            throw CommerceException.conflict("ADMIN_ROLE_IMMUTABLE",
                    "Không thể đổi vai trò của tài khoản ADMIN bằng chức năng này.");
        }
        if (currentRoles.stream().noneMatch(SWITCHABLE_ROLES::contains)) {
            throw CommerceException.conflict("ACCOUNT_ROLE_UNSUPPORTED",
                    "Chỉ có thể chuyển tài khoản STAFF hoặc CUSTOMER.");
        }
        long switchableRoleCount = currentRoles.stream()
                .filter(SWITCHABLE_ROLES::contains)
                .count();
        if (currentRoles.contains(roleCode) && switchableRoleCount == 1) return find(targetId);

        Long roleId = jdbc.query("SELECT id FROM roles WHERE code=:code",
                        Map.of("code", roleCode), (rs, row) -> rs.getLong("id"))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(roleCode + " role is unavailable."));
        jdbc.update("""
                DELETE FROM user_roles
                WHERE user_id=:id AND role_id IN (
                    SELECT id FROM roles WHERE code IN ('STAFF','CUSTOMER')
                )
                """, Map.of("id", targetId));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES(:userId,:roleId)",
                Map.of("userId", targetId, "roleId", roleId));

        List<String> updatedRoles = currentRoles.stream()
                .filter(role -> !SWITCHABLE_ROLES.contains(role))
                .collect(Collectors.toCollection(ArrayList::new));
        updatedRoles.add(roleCode);
        updatedRoles.sort(String::compareTo);

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String actorName = jdbc.queryForObject(
                "SELECT COALESCE(full_name,username) FROM users WHERE id=:id",
                Map.of("id", actorId), String.class);
        jdbc.update("""
                        INSERT INTO audit_logs(actor_user_id,actor_name_snapshot,action,entity_type,entity_id,
                                               before_data,after_data,request_id,created_at)
                        VALUES(:actorId,:actorName,'USER_ROLE_CHANGED','USER',:entityId,
                               :beforeData,:afterData,:requestId,:createdAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("actorId", actorId)
                        .addValue("actorName", actorName)
                        .addValue("entityId", Long.toString(targetId))
                        .addValue("beforeData", mapper.writeValueAsString(Map.of(
                                "username", target.username(), "roles", currentRoles)))
                        .addValue("afterData", mapper.writeValueAsString(Map.of(
                                "username", target.username(), "roles", updatedRoles)))
                        .addValue("requestId", UUID.randomUUID().toString())
                        .addValue("createdAt", now));
        return find(targetId);
    }

    private Account find(long id) {
        List<Account> accounts = jdbc.query(USER_SELECT + " WHERE u.id=:id GROUP BY u.id",
                Map.of("id", id), (rs, row) -> account(
                        rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getString("status"),
                        rs.getString("roles")));
        if (accounts.isEmpty()) throw CommerceException.notFound("Không tìm thấy tài khoản.");
        return accounts.get(0);
    }

    private static Account account(long id, String username, String fullName, String email,
                                   String phone, String status, String roles) {
        List<String> roleList = roles == null || roles.isBlank()
                ? List.of() : Arrays.asList(roles.split(","));
        return new Account(id, username, fullName, email, phone, status, roleList);
    }

    private record LockedAccount(long id, String username, String fullName, String status) {}
}
