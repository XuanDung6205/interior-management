package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountPasswordService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AccountPasswordService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                  ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void change(long userId, AccountPasswordRequest request) {
        if (request == null) {
            throw CommerceException.badRequest("INVALID_REQUEST", "Vui lòng nhập thông tin đổi mật khẩu.");
        }

        String currentPassword = request.currentPassword();
        String newPassword = request.newPassword();
        var errors = new LinkedHashMap<String, String>();
        if (currentPassword == null || currentPassword.isEmpty()) {
            errors.put("currentPassword", "Vui lòng nhập mật khẩu hiện tại.");
        } else if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            errors.put("currentPassword", "Mật khẩu hiện tại không hợp lệ.");
        }
        if (newPassword == null || newPassword.length() < 8
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            errors.put("newPassword", "Mật khẩu mới cần ít nhất 8 ký tự và tối đa 72 byte.");
        }
        if (request.confirmPassword() == null || !request.confirmPassword().equals(newPassword)) {
            errors.put("confirmPassword", "Mật khẩu nhập lại chưa khớp.");
        }
        if (!errors.isEmpty()) {
            throw CommerceException.invalid("Vui lòng kiểm tra thông tin mật khẩu.", errors);
        }

        List<PasswordAccount> accounts = jdbc.query("""
                        SELECT username,full_name,password_hash FROM users WHERE id=:id FOR UPDATE
                        """, Map.of("id", userId), (rs, row) -> new PasswordAccount(
                rs.getString("username"), rs.getString("full_name"), rs.getString("password_hash")));
        if (accounts.isEmpty()) throw CommerceException.notFound("ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.");
        PasswordAccount account = accounts.get(0);

        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw CommerceException.invalid("Mật khẩu hiện tại không đúng.",
                    Map.of("currentPassword", "Mật khẩu hiện tại không đúng."));
        }
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw CommerceException.invalid("Mật khẩu mới phải khác mật khẩu hiện tại.",
                    Map.of("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại."));
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("UPDATE users SET password_hash=:passwordHash,updated_at=:updatedAt WHERE id=:id",
                Map.of("passwordHash", passwordEncoder.encode(newPassword), "updatedAt", now, "id", userId));
        jdbc.update("""
                        INSERT INTO audit_logs(actor_user_id,actor_name_snapshot,action,entity_type,entity_id,
                                               before_data,after_data,request_id,created_at)
                        VALUES(:actorId,:actorName,'USER_PASSWORD_CHANGED','USER',:entityId,
                               :beforeData,:afterData,:requestId,:createdAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("actorId", userId)
                        .addValue("actorName", account.fullName() == null ? account.username() : account.fullName())
                        .addValue("entityId", Long.toString(userId))
                        .addValue("beforeData", mapper.writeValueAsString(Map.of("credential", "unchanged")))
                        .addValue("afterData", mapper.writeValueAsString(Map.of("credential", "changed")))
                        .addValue("requestId", UUID.randomUUID().toString())
                        .addValue("createdAt", now));
    }

    private record PasswordAccount(String username, String fullName, String passwordHash) {}
}
