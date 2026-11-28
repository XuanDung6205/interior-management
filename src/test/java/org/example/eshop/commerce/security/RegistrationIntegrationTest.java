package org.example.eshop.commerce.security;

import org.example.eshop.commerce.CommerceApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(classes = CommerceApplication.class)
@ActiveProfiles("commerce")
@TestPropertySource(properties = "commerce.jobs.enabled=false")
@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
class RegistrationIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TV3_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    final List<String> created = new ArrayList<>();

    @BeforeEach void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("furniture_shop_test_"));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup() {
        for (String username : created) jdbc.update("DELETE FROM users WHERE username=?", username);
    }

    String username() {
        String value = "regtest-" + UUID.randomUUID().toString().substring(0, 12);
        created.add(value);
        return value;
    }
    String body(String username, String email, String password, String confirmation) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"fullName\":\"Nguyễn An\",\"phone\":\"+84901234567\",\"password\":\"" + password + "\",\"confirmPassword\":\"" + confirmation + "\"}";
    }

    @Test void createsOnlyCustomerAndAllowsPasswordLogin() throws Exception {
        String username = username();
        String email = username + "@example.invalid";
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body(username, email, "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username=?", String.class, username);
        assertNotEquals("secret-pass-123", hash);
        assertTrue(hash.startsWith("{bcrypt}"));
        List<String> roles = jdbc.queryForList("SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id JOIN users u ON u.id=ur.user_id WHERE u.username=?", String.class, username);
        assertEquals(List.of("CUSTOMER"), roles);
        var login = mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", username).param("password", "secret-pass-123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.roles[0]").value("CUSTOMER")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/api/account/profile").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Nguyễn An"));
        String updatedEmail = ("updated-" + email).toUpperCase();
        mvc.perform(put("/api/account/profile").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"Nguyễn Bình\",\"email\":\"" + updatedEmail + "\",\"phone\":\"+84901234567\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Nguyễn Bình"))
                .andExpect(jsonPath("$.email").value(updatedEmail.toLowerCase()))
                .andExpect(jsonPath("$.phone").value("+84901234567"));
        assertEquals(updatedEmail.toLowerCase(), jdbc.queryForObject("SELECT email FROM users WHERE username=?", String.class, username));
    }

    @Test void signedInCustomerCanChangePasswordAndTheOldPasswordStopsAuthenticating() throws Exception {
        String username = username();
        String email = username + "@example.invalid";
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body(username, email, "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isCreated());
        var login = mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", username).param("password", "secret-pass-123"))
                .andExpect(status().isOk()).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession();

        mvc.perform(post("/api/account/password").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"wrong-pass-123\",\"newPassword\":\"new-secret-123\",\"confirmPassword\":\"new-secret-123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.currentPassword").exists());
        mvc.perform(post("/api/account/password").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"secret-pass-123\",\"newPassword\":\"secret-pass-123\",\"confirmPassword\":\"secret-pass-123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        mvc.perform(post("/api/account/password").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"secret-pass-123\",\"newPassword\":\"new-secret-123\",\"confirmPassword\":\"different-pass-123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.confirmPassword").exists());
        mvc.perform(post("/api/account/password").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"secret-pass-123\",\"newPassword\":\"new-secret-123\",\"confirmPassword\":\"new-secret-123\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", username).param("password", "secret-pass-123"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", username).param("password", "new-secret-123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type='USER'
                  AND entity_id=(SELECT CAST(id AS CHAR) FROM users WHERE username=?)
                  AND action='USER_PASSWORD_CHANGED'
                """, Integer.class, username));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type='USER'
                  AND entity_id=(SELECT CAST(id AS CHAR) FROM users WHERE username=?)
                  AND (before_data LIKE '%secret-pass-123%' OR after_data LIKE '%new-secret-123%')
                """, Integer.class, username));
    }

    @Test void rejectsInvalidAndDuplicateAccountsWithFieldErrors() throws Exception {
        String username = username();
        String email = username + "@example.invalid";
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body("bad name", email, "short", "different")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.confirmPassword").exists());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email));
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body(username, email, "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body(username.toUpperCase(), "another-" + email, "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.fieldErrors.username").exists());
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body(username(), email.toUpperCase(), "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test void requiresCsrfAndDoesNotLetSignedInUsersSelfCreateMoreAccounts() throws Exception {
        String username = username();
        String email = username + "@example.invalid";
        mvc.perform(post("/api/session/register").contentType("application/json")
                        .content(body(username, email, "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isForbidden());
        String existing = username();
        mvc.perform(post("/api/session/register").with(csrf()).contentType("application/json")
                        .content(body(existing, existing + "@example.invalid", "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isCreated());
        var login = mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", existing).param("password", "secret-pass-123"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/session/register").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession())
                        .with(csrf()).contentType("application/json")
                        .content(body(username, email, "secret-pass-123", "secret-pass-123")))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, username));
    }
}
