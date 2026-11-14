package org.example.eshop.commerce.security;

import org.example.eshop.commerce.common.ApiError;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class CommerceSecurityConfiguration {
    private static void json(ObjectMapper mapper,HttpServletResponse response,int status,Object body) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); mapper.writeValue(response.getOutputStream(),body);
    }
    @Bean UserDetailsService commerceUserDetailsService(UserDirectory users) {
        return username -> {
            var account=users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
            if(!account.enabled()) throw new org.springframework.security.authentication.DisabledException("Account unavailable");
            return account.principal();
        };
    }
    @Bean PasswordEncoder commercePasswordEncoder() {
        var encoder=(DelegatingPasswordEncoder)PasswordEncoderFactories.createDelegatingPasswordEncoder();
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
    @Bean SecurityFilterChain commerceSecurity(HttpSecurity http,UserDirectory users,UserDetailsService commerceUserDetailsService,PasswordEncoder commercePasswordEncoder,ObjectMapper mapper) throws Exception {
        var provider=new DaoAuthenticationProvider(commerceUserDetailsService);
        provider.setPasswordEncoder(commercePasswordEncoder);
        http.authenticationProvider(provider)
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/coupons","/api/admin/coupons/**","/api/admin/inventory","/api/admin/inventory/**").hasAnyRole("ADMIN","STAFF")
                .requestMatchers("/api/cart/merge","/api/checkout","/api/checkout/**","/api/orders","/api/orders/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> json(mapper,response,401,new ApiError("AUTHENTICATION_REQUIRED","Sign in to continue.")))
                .accessDeniedHandler((request,response,error) -> json(mapper,response,403,new ApiError(error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",error instanceof CsrfException ? "Refresh the security token and try again." : "You do not have permission to perform this action."))))
            .formLogin(login -> login.loginProcessingUrl("/api/session/login")
                .successHandler((request,response,authentication) -> json(mapper,response,200,SessionController.sessionView(authentication)))
                .failureHandler((request,response,error) -> json(mapper,response,401,new ApiError("INVALID_CREDENTIALS","Invalid username or password."))))
            .logout(logout -> logout.logoutUrl("/api/session/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request,response,authentication) -> json(mapper,response,200,SessionController.sessionView(null))))
            .requestCache(cache -> cache.disable())
            .addFilterBefore(new SessionRefreshFilter(users,mapper),AuthorizationFilter.class);
        return http.build();
    }
}
