package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AccountProfileService {
    private final NamedParameterJdbcTemplate jdbc;

    public AccountProfileService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AccountProfileResponse get(long userId) {
        return find(userId);
    }

    @Transactional
    public AccountProfileResponse update(long userId, AccountProfileRequest request) {
        if (request == null) {
            throw CommerceException.badRequest("VALIDATION_ERROR", "Thông tin hồ sơ không hợp lệ.");
        }
        String fullName = request.fullName().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email)=:email AND id<>:id",
                Map.of("email", email, "id", userId), Integer.class) > 0) {
            throw CommerceException.conflict("ACCOUNT_EMAIL_EXISTS", "Email đã được sử dụng.",
                    Map.of("email", "Email đã được sử dụng."));
        }
        try {
            jdbc.update("""
                    UPDATE users SET full_name=:fullName,email=:email,phone=:phone
                    WHERE id=:id
                    """, new MapSqlParameterSource()
                    .addValue("fullName", fullName)
                    .addValue("email", email)
                    .addValue("phone", phone)
                    .addValue("id", userId));
        } catch (DataIntegrityViolationException exception) {
            throw CommerceException.conflict("ACCOUNT_EMAIL_EXISTS", "Email đã được sử dụng.",
                    Map.of("email", "Email đã được sử dụng."));
        }
        return find(userId);
    }

    private AccountProfileResponse find(long userId) {
        List<AccountProfileResponse> profiles = jdbc.query("""
                SELECT id,username,full_name,email,phone FROM users WHERE id=:id
                """, Map.of("id", userId), (rs, row) -> new AccountProfileResponse(
                rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("phone")));
        if (profiles.isEmpty()) throw CommerceException.notFound("ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.");
        return profiles.get(0);
    }
}
