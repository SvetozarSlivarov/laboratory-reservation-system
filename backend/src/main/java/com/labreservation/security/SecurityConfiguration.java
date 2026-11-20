package com.labreservation.security;

import com.labreservation.accounts.User;
import com.labreservation.accounts.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    UserDetailsService localUsers(UserRepository users) {
        return email -> users.findByEmail(User.normalizeEmail(email))
                .filter(user -> user.getPasswordHash() != null)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getId().toString())
                        .password(user.getPasswordHash())
                        .roles(user.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
            ObjectProvider<ClientRegistrationRepository> clients, GoogleLogin google,
            @Value("${app.mcp.allowed-origin}") String mcpOrigin) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/mcp").access((authentication, context) ->
                                new AuthorizationDecision(isRegularUser(authentication.get())
                                        && permitsMcpTransport(context.getRequest(), mcpOrigin)))
                        .requestMatchers("/api/laboratories", "/api/laboratories/**", "/api/workstations/**",
                                "/api/availability", "/api/recommendations", "/api/bookings", "/api/bookings/**").authenticated()
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problem(json, response, HttpStatus.UNAUTHORIZED, "Sign in to continue"))
                        .accessDeniedHandler((request, response, exception) ->
                                problem(json, response, HttpStatus.FORBIDDEN, "Access denied or invalid CSRF token")))
                .formLogin(form -> form.loginPage("/")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) ->
                                problem(json, response, HttpStatus.UNAUTHORIZED, "Invalid email or password")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));

        var registration = clients.getIfAvailable();
        if (registration != null) {
            http.oauth2Login(oauth -> oauth.clientRegistrationRepository(registration)
                    .loginPage("/")
                    .userInfoEndpoint(info -> info.oidcUserService(google::loadUser))
                    .defaultSuccessUrl("/", true)
                    .failureHandler((request, response, exception) -> {
                        if (exception instanceof OAuth2AuthenticationException failure) {
                            switch (failure.getError().getErrorCode()) {
                                case "account_conflict" -> {
                                    problem(json, response, HttpStatus.CONFLICT, failure.getError().getDescription());
                                    return;
                                }
                                case "unverified_identity" -> {
                                    problem(json, response, HttpStatus.FORBIDDEN, failure.getError().getDescription());
                                    return;
                                }
                            }
                        }
                        problem(json, response, HttpStatus.UNAUTHORIZED, "Google sign-in failed");
                    }));
        }
        return http.build();
    }

    private static boolean isRegularUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        var authorities = authentication.getAuthorities();
        return authorities.stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"))
                && authorities.stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private static boolean permitsMcpTransport(HttpServletRequest request, String allowedOrigin) {
        var origins = Collections.list(request.getHeaders("Origin"));
        if (!origins.isEmpty() && (allowedOrigin.isBlank() || !origins.equals(java.util.List.of(allowedOrigin)))) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        var loopbackAddresses = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
        return loopbackAddresses.contains(request.getRemoteAddr())
                && (loopbackAddresses.contains(request.getServerName())
                    || "localhost".equalsIgnoreCase(request.getServerName())
                    || "[::1]".equals(request.getServerName()));
    }

    private static void problem(ObjectMapper json, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
