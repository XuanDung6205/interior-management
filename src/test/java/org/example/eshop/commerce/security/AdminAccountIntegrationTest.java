package org.example.eshop.commerce.security;

import org.example.eshop.commerce.CommerceApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(classes = CommerceApplication.class)
@ActiveProfiles("commerce")
@TestPropertySource(properties = "commerce.jobs.enabled=false")
@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
class AdminAccountIntegrationTest {
    private static final String PASSWORD = "account-test-password";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TV3_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired WebApplicationContext context;
    private final List<Long> createdIds = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("furniture_shop_test_"));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdIds) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_type='USER' AND entity_id=?", Long.toString(id));
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
    }

    @Test
    void onlyAdminCanLockAndUnlockAndLockedSessionsAreRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        String admin = createAccount("account-admin-" + suffix, "ADMIN");
        String staff = createAccount("account-staff-" + suffix, "STAFF");
        MockHttpSession adminSession = login(admin);
        MockHttpSession staffSession = login(staff);

        mvc.perform(get("/api/admin/users").session(staffSession))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/" + id(staff) + "/status").session(staffSession).with(csrf())
                        .contentType("application/json").content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/users?query=" + staff).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value(staff))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
        mvc.perform(patch("/api/admin/users/" + id(staff) + "/status").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"));
        assertEquals("LOCKED", jdbc.queryForObject("SELECT status FROM users WHERE username=?", String.class, staff));
        mvc.perform(get("/api/session").session(staffSession)).andExpect(status().isUnauthorized());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='USER' AND action='USER_ACCOUNT_LOCKED' AND entity_id=?",
                Integer.class, Long.toString(id(staff))));

        mvc.perform(patch("/api/admin/users/" + id(staff) + "/status").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM users WHERE username=?", String.class, staff));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='USER' AND action='USER_ACCOUNT_UNLOCKED' AND entity_id=?",
                Integer.class, Long.toString(id(staff))));
        mvc.perform(post("/api/session/login").with(csrf()).param("username", staff).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));

        mvc.perform(patch("/api/admin/users/" + id(admin) + "/status").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_LOCK_SELF"));
    }

    @Test
    void onlyAdminCanSwitchStaffAndCustomerRolesAndChangesAreAudited() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        String admin = createAccount("role-admin-" + suffix, "ADMIN");
        String staff = createAccount("role-staff-" + suffix, "STAFF");
        MockHttpSession adminSession = login(admin);
        MockHttpSession staffSession = login(staff);
        long adminId = id(admin);
        long staffId = id(staff);

        mvc.perform(patch("/api/admin/users/" + staffId + "/role").session(staffSession).with(csrf())
                        .contentType("application/json").content("{\"roleCode\":\"CUSTOMER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/" + staffId + "/role").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"roleCode\":\"CUSTOMER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        assertEquals("CUSTOMER", jdbc.queryForObject("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?
                """, String.class, staffId));

        mvc.perform(patch("/api/admin/users/" + staffId + "/role").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"roleCode\":\"STAFF\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("STAFF"));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type='USER' AND entity_id=? AND action='USER_ROLE_CHANGED'
                """, Integer.class, Long.toString(staffId)));

        mvc.perform(patch("/api/admin/users/" + staffId + "/role").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"roleCode\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ROLE"));
        mvc.perform(patch("/api/admin/users/" + adminId + "/role").session(adminSession).with(csrf())
                        .contentType("application/json").content("{\"roleCode\":\"CUSTOMER\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_ROLE_IMMUTABLE"));
        assertEquals("ADMIN", jdbc.queryForObject("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?
                """, String.class, adminId));
    }

    private String createAccount(String username, String role) {
        jdbc.update("INSERT INTO users(username,email,password_hash,full_name,status) VALUES(?,?,?,?, 'ACTIVE')",
                username, username + "@example.invalid", passwords.encode(PASSWORD), username);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        createdIds.add(id);
        jdbc.update("""
                INSERT INTO user_roles(user_id,role_id)
                SELECT ?,id FROM roles WHERE code=?
                """, id, role);
        return username;
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", username).param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    private Long id(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }
}
