package org.example.eshop.commerce.security;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class RegistrationService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern PHONE = Pattern.compile("[+0-9 .()\\-]{0,30}");
    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public RegistrationService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public record Request(String username, String email, String fullName, String phone,
                          String password, String confirmPassword) {
        @Override public String toString() {
            return "RegistrationRequest[username=" + username + ", email=" + email + ", password=redacted]";
        }
    }
    public record Result(long id, String username, String email, String fullName) {}

    @Transactional
    public Result register(Request request) {
        if (request == null) throw CommerceException.badRequest("INVALID_REQUEST", "Please enter your account details.");
        String username = trim(request.username());
        String email = trim(request.email()).toLowerCase(Locale.ROOT);
        String fullName = trim(request.fullName());
        String phone = trim(request.phone());
        String password = request.password();
        var errors = new LinkedHashMap<String, String>();
        if (!USERNAME.matcher(username).matches()) errors.put("username", "Dùng 3–50 ký tự chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) errors.put("email", "Nhập email hợp lệ (tối đa 254 ký tự).");
        if (fullName.length() < 2 || fullName.length() > 150) errors.put("fullName", "Họ tên cần từ 2 đến 150 ký tự.");
        if (!PHONE.matcher(phone).matches()) errors.put("phone", "Số điện thoại không hợp lệ.");
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            errors.put("password", "Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte.");
        if (request.confirmPassword() == null || !request.confirmPassword().equals(password))
            errors.put("confirmPassword", "Mật khẩu nhập lại chưa khớp.");
        if (!errors.isEmpty()) throw CommerceException.invalid("Vui lòng kiểm tra thông tin đăng ký.", errors);

        var existing = existingFields(username, email);
        if (!existing.isEmpty()) throw CommerceException.conflict("ACCOUNT_EXISTS", "Tên đăng nhập hoặc email đã được sử dụng.", existing);
        try {
            jdbc.update("INSERT INTO users(username,email,password_hash,full_name,phone,status) VALUES(:username,:email,:password,:fullName,:phone,'ACTIVE')",
                    new MapSqlParameterSource().addValue("username", username).addValue("email", email)
                            .addValue("password", passwords.encode(password)).addValue("fullName", fullName)
                            .addValue("phone", phone.isEmpty() ? null : phone));
        } catch (DuplicateKeyException duplicate) {
            var raced = existingFields(username, email);
            if (raced.isEmpty()) throw duplicate;
            throw CommerceException.conflict("ACCOUNT_EXISTS", "Tên đăng nhập hoặc email đã được sử dụng.", raced);
        }
        int linked = jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u JOIN roles r ON r.code='CUSTOMER' WHERE u.username=:username",
                Map.of("username", username));
        if (linked != 1) throw new IllegalStateException("CUSTOMER role is unavailable.");
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username=:username", Map.of("username", username), Long.class);
        return new Result(id, username, email, fullName);
    }

    private Map<String, String> existingFields(String username, String email) {
        var errors = new LinkedHashMap<String, String>();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=:username", Map.of("username", username), Integer.class) > 0)
            errors.put("username", "Tên đăng nhập đã được sử dụng.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=:email", Map.of("email", email), Integer.class) > 0)
            errors.put("email", "Email đã được sử dụng.");
        return errors;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
