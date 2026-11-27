package org.example.eshop.commerce.account;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcAddressBookRepository {
    private static final String SELECT = """
            SELECT id,label,recipient_name,phone,address_line1,address_line2,ward_name,district_name,
                   province_name,postal_code,country_code,is_default
            FROM user_addresses
            """;

    private static final RowMapper<AddressResponse> MAPPER = (rs, row) -> new AddressResponse(
            rs.getLong("id"),
            rs.getString("label"),
            rs.getString("recipient_name"),
            rs.getString("phone"),
            rs.getString("address_line1"),
            rs.getString("address_line2"),
            rs.getString("ward_name"),
            rs.getString("district_name"),
            rs.getString("province_name"),
            rs.getString("postal_code"),
            rs.getString("country_code"),
            rs.getBoolean("is_default")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAddressBookRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockUser(long userId) {
        return !jdbc.query(
                "SELECT id FROM users WHERE id=:userId FOR UPDATE",
                Map.of("userId", userId),
                (rs, row) -> rs.getLong("id")
        ).isEmpty();
    }

    public List<AddressResponse> findByUserId(long userId) {
        return jdbc.query(SELECT + " WHERE user_id=:userId ORDER BY is_default DESC,id",
                Map.of("userId", userId), MAPPER);
    }

    public Optional<AddressResponse> findOwned(long userId, long addressId) {
        return jdbc.query(SELECT + " WHERE user_id=:userId AND id=:id",
                        Map.of("userId", userId, "id", addressId), MAPPER)
                .stream()
                .findFirst();
    }

    public int countByUserId(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_addresses WHERE user_id=:userId",
                Map.of("userId", userId),
                Integer.class
        );
    }

    public AddressResponse insert(long userId, AddressRequest request, boolean isDefault) {
        var parameters = addressParameters(userId, request)
                .addValue("isDefault", isDefault);
        var keys = new GeneratedKeyHolder();
        int inserted = jdbc.update("""
                        INSERT INTO user_addresses(
                            user_id,label,recipient_name,phone,address_line1,address_line2,ward_name,
                            district_name,province_name,postal_code,country_code,is_default
                        ) VALUES (
                            :userId,:label,:recipientName,:phone,:addressLine1,:addressLine2,:wardName,
                            :districtName,:provinceName,:postalCode,:countryCode,:isDefault
                        )
                        """,
                parameters,
                keys,
                new String[]{"id"}
        );
        if (inserted != 1 || keys.getKey() == null) {
            throw new IllegalStateException("Could not create the address book entry.");
        }
        return findOwned(userId, keys.getKey().longValue())
                .orElseThrow(() -> new IllegalStateException("Created address book entry could not be loaded."));
    }

    public int updateOwned(long userId, long addressId, AddressRequest request) {
        var parameters = addressParameters(userId, request)
                .addValue("id", addressId);
        return jdbc.update("""
                UPDATE user_addresses
                SET label=:label,recipient_name=:recipientName,phone=:phone,address_line1=:addressLine1,
                    address_line2=:addressLine2,ward_name=:wardName,district_name=:districtName,
                    province_name=:provinceName,postal_code=:postalCode,country_code=:countryCode
                WHERE user_id=:userId AND id=:id
                """, parameters);
    }

    public int clearDefaultForUser(long userId) {
        return jdbc.update("UPDATE user_addresses SET is_default=FALSE WHERE user_id=:userId AND is_default=TRUE",
                Map.of("userId", userId));
    }

    public int setDefaultOwned(long userId, long addressId) {
        return jdbc.update("UPDATE user_addresses SET is_default=TRUE WHERE user_id=:userId AND id=:id",
                Map.of("userId", userId, "id", addressId));
    }

    public int deleteOwned(long userId, long addressId) {
        return jdbc.update("DELETE FROM user_addresses WHERE user_id=:userId AND id=:id",
                Map.of("userId", userId, "id", addressId));
    }

    private static MapSqlParameterSource addressParameters(long userId, AddressRequest request) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("label", nullableTrimmed(request.label()))
                .addValue("recipientName", request.recipientName().trim())
                .addValue("phone", request.phone().trim())
                .addValue("addressLine1", request.addressLine1().trim())
                .addValue("addressLine2", nullableTrimmed(request.addressLine2()))
                .addValue("wardName", nullableTrimmed(request.wardName()))
                .addValue("districtName", nullableTrimmed(request.districtName()))
                .addValue("provinceName", nullableTrimmed(request.provinceName()))
                .addValue("postalCode", nullableTrimmed(request.postalCode()))
                .addValue("countryCode", request.countryCode() == null || request.countryCode().isBlank()
                        ? "VN"
                        : request.countryCode().trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static String nullableTrimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
