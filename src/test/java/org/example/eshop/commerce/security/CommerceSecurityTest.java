package org.example.eshop.commerce.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Set;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringJUnitConfig(CommerceSecurityTest.Config.class)
@WebAppConfiguration
class CommerceSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({CommerceSecurityConfiguration.class,SessionController.class,ProbeController.class})
    static class Config {
        @Bean UserDirectory users() { return mock(UserDirectory.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @RestController static class ProbeController {
        @GetMapping("/api/admin/coupons") Object admin() { return java.util.Map.of("ok",true); }
        @GetMapping("/api/admin/inventory/warehouses") Object inventory() { return java.util.Map.of("ok",true); }
        @PostMapping("/api/admin/inventory/receipts") Object receive() { return java.util.Map.of("ok",true); }
        @GetMapping("/api/admin/inventory/orders") Object dispatchQueue() { return java.util.Map.of("ok",true); }
        @PostMapping("/api/admin/inventory/orders/42/dispatch") Object dispatch() { return java.util.Map.of("ok",true); }
        @PostMapping("/api/cart/items") Object cart() { return java.util.Map.of("ok",true); }
        @PostMapping("/api/cart/merge") Object merge() { return java.util.Map.of("ok",true); }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserDirectory users;
    MockMvc mvc;
    CommerceUserAccount account;
    @BeforeEach void setup() {
        reset(users);
        account = new CommerceUserAccount(77L,"alice","Alice",new BCryptPasswordEncoder().encode("correct-password"),"ACTIVE",Set.of("STAFF"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        when(users.findById(77L)).thenAnswer(invocation -> Optional.of(account));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    record Token(MockHttpSession session, String value, String header) {}
    Token csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        return new Token((MockHttpSession)result.getRequest().getSession(),json.get("token").asString(),json.get("headerName").asString());
    }
    @Test void registeredUserDetailsServiceUsesDirectoryAndRejectsUnknownAccounts() throws Exception {
        var services = context.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class);
        org.assertj.core.api.Assertions.assertThat(services).hasSize(1);
        var service = services.values().iterator().next();
        when(users.findByUsername("unknown-account")).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.loadUserByUsername("unknown-account"))
            .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        var token = csrf(new MockHttpSession());
        mvc.perform(post("/api/session/login").session(token.session()).header(token.header(),token.value())
                .param("username","alice").param("password","correct-password"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(77))
            .andExpect(jsonPath("$.user.roles[0]").value("STAFF"));
    }
    @Test void unsafeGuestWritesAndLoginRequireCsrfAndMergeRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/cart/items")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/session/login").param("username","alice").param("password","correct-password")).andExpect(status().isForbidden());
        var token = csrf(new MockHttpSession());
        mvc.perform(post("/api/cart/items").session(token.session()).header(token.header(),token.value())).andExpect(status().isOk());
        mvc.perform(post("/api/cart/merge").session(token.session()).header(token.header(),token.value())).andExpect(status().isUnauthorized());
    }
    @Test void loginReturnsSafeSessionAndRolesAndStatusAreRevalidated() throws Exception {
        var token = csrf(new MockHttpSession());
        var login = mvc.perform(post("/api/session/login").session(token.session()).header(token.header(),token.value()).param("username","alice").param("password","correct-password"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(77)).andExpect(jsonPath("$.user.roles[0]").value("STAFF"))
            .andExpect(jsonPath("$.user.password").doesNotExist()).andExpect(jsonPath("$.user.passwordHash").doesNotExist()).andReturn();
        var session = (MockHttpSession)login.getRequest().getSession();
        mvc.perform(get("/api/admin/coupons").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/inventory/warehouses").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/inventory/orders").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/inventory/receipts").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/orders/42/dispatch").session(session)).andExpect(status().isForbidden());
        var staffToken = csrf(session);
        mvc.perform(post("/api/admin/inventory/receipts").session(staffToken.session()).header(staffToken.header(),staffToken.value())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/inventory/orders/42/dispatch").session(staffToken.session()).header(staffToken.header(),staffToken.value())).andExpect(status().isOk());
        account = new CommerceUserAccount(77L,"alice","Alice",null,"ACTIVE",Set.of("CUSTOMER"));
        mvc.perform(get("/api/admin/coupons").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/inventory/warehouses").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/inventory/orders").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/receipts").session(staffToken.session()).header(staffToken.header(),staffToken.value())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/orders/42/dispatch").session(staffToken.session()).header(staffToken.header(),staffToken.value())).andExpect(status().isForbidden());
        account = new CommerceUserAccount(77L,"alice","Alice",null,"DISABLED",Set.of("ADMIN"));
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void wrongPasswordReturnsJsonAndLogoutReturnsAnonymousSession() throws Exception {
        var token = csrf(new MockHttpSession());
        mvc.perform(post("/api/session/login").session(token.session()).header(token.header(),token.value()).param("username","alice").param("password","wrong"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/session/login").session(token.session()).header(token.header(),token.value()).param("username","alice").param("password","correct-password")).andExpect(status().isOk());
        var fresh = csrf(token.session());
        mvc.perform(post("/api/session/logout").session(fresh.session()).header(fresh.header(),fresh.value()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.user").isEmpty());
    }
}
