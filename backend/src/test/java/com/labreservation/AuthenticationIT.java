package com.labreservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labreservation.accounts.AccountService;
import com.labreservation.accounts.UserRepository;
import com.labreservation.accounts.UserRole;
import com.labreservation.bookings.Booking;
import com.labreservation.bookings.BookingAuthorization;
import com.labreservation.bookings.BookingStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.google.enabled=true",
        "GOOGLE_CLIENT_ID=test-client",
        "GOOGLE_CLIENT_SECRET=test-secret"
})
@AutoConfigureMockMvc
@Import(AuthenticationIT.OidcFixture.class)
class AuthenticationIT {

    private static final String PASSWORD = "a strong test password";
    private static String callbackNonce;
    private static HttpServer tokenServer;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AccountService accounts;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingAuthorization bookingAuthorization;

    @Test
    void registersLogsInRotatesSessionAndLogsOut() throws Exception {
        String email = "local-auth@example.test";
        var csrf = csrf(null);
        mvc.perform(post("/api/auth/register").session(csrf.session()).header(csrf.header(), csrf.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"Local-Auth@example.test","password":"a strong test password","role":"ADMIN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();

        mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").session(csrf.session()).header(csrf.header(), csrf.value())
                        .param("email", email).param("password", "incorrect"))
                .andExpect(status().isUnauthorized());

        String originalId = csrf.session().getId();
        mvc.perform(post("/api/auth/login").session(csrf.session()).header(csrf.header(), csrf.value())
                        .param("email", "LOCAL-AUTH@example.test").param("password", PASSWORD))
                .andExpect(status().isNoContent());
        assertThat(csrf.session().getId()).isNotEqualTo(originalId);
        mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/admin/example").session(csrf.session())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(csrf.session()).header(csrf.header(), csrf.value()))
                .andExpect(status().isForbidden());

        var fresh = csrf(csrf.session());
        mvc.perform(post("/api/auth/logout").session(fresh.session()).header(fresh.header(), fresh.value()))
                .andExpect(status().isNoContent());
        assertThat(fresh.session().isInvalid()).isTrue();
    }

    @Test
    void rejectsMissingCsrfAndInvalidRegistration() throws Exception {
        mvc.perform(post("/api/auth/register")).andExpect(status().isForbidden());
        var csrf = csrf(null);
        mvc.perform(post("/api/auth/register").session(csrf.session()).header(csrf.header(), csrf.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invalid","password":"short"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsOnlyVerifiedGoogleIdentityAndRequiresExistingSessionForLinking() {
        var local = accounts.register("link@example.test", PASSWORD);
        var other = accounts.register("other-owner@example.test", PASSWORD);
        assertThatThrownBy(() -> accounts.googleSignIn("google-link", local.email(), false, local.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> accounts.googleSignIn("google-link", local.email(), true, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> accounts.googleSignIn("google-link", local.email(), true, other.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");

        assertThat(accounts.googleSignIn("google-link", "LINK@example.test", true, local.id())).isEqualTo(local);
        assertThat(accounts.googleSignIn("google-link", "changed@example.test", true, null)).isEqualTo(local);
        assertThatThrownBy(() -> accounts.googleSignIn("google-link", local.email(), true, other.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");

        var googleOnly = accounts.googleSignIn("google-new", "google-only@example.test", true, null);
        assertThat(googleOnly.role()).isEqualTo(UserRole.USER);
        assertThat(users.findById(googleOnly.id()).orElseThrow().getPasswordHash()).isNull();
    }

    @Test
    void googleAuthorizationStoresStateAndRejectsUnsolicitedCallback() throws Exception {
        var redirect = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith("https://accounts.google.com/").contains("state=", "nonce=");
        mvc.perform(get("/login/oauth2/code/google").param("code", "unexpected").param("state", "unexpected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void successfulGoogleCallbackCreatesTheSameApplicationSession() throws Exception {
        var authorization = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var params = UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl())
                .build().getQueryParams();
        callbackNonce = params.getFirst("nonce");
        var session = (MockHttpSession) authorization.getRequest().getSession(false);
        String anonymousId = session.getId();
        mvc.perform(get("/login/oauth2/code/google").session(session)
                        .param("code", "test-code")
                        .param("state", UriUtils.decode(params.getFirst("state"), StandardCharsets.UTF_8)))
                .andExpect(status().is3xxRedirection());
        assertThat(session.getId()).isNotEqualTo(anonymousId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("callback@example.test"))
                .andExpect(jsonPath("$.role").value("USER"));
        var csrf = csrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header(csrf.header(), csrf.value()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @AfterAll
    static void stopTokenServer() {
        if (tokenServer != null) tokenServer.stop(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OidcFixture {

        @Bean
        @Primary
        ClientRegistrationRepository fixtureClients() throws IOException {
            tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            tokenServer.createContext("/token", exchange -> {
                byte[] body = """
                        {"access_token":"fixture-access","token_type":"Bearer","expires_in":300,
                         "id_token":"fixture-id-token","scope":"openid email"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            tokenServer.start();
            var registration = ClientRegistration.withRegistrationId("google")
                    .clientId("test-client").clientSecret("test-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }

        @Bean
        JwtDecoderFactory<ClientRegistration> fixtureDecoder() {
            return registration -> token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .subject("callback-subject")
                    .claim("email", "callback@example.test")
                    .claim("email_verified", true)
                    .claim("nonce", callbackNonce)
                    .build();
        }
    }

    @Test
    void bookingOwnershipUsesAuthenticatedAccount() {
        var owner = users.findById(accounts.register("booking-owner@example.test", PASSWORD).id()).orElseThrow();
        var other = accounts.register("booking-other@example.test", PASSWORD);
        var booking = new Booking(owner, null, null, null, BookingStatus.CONFIRMED, null, null);
        assertThatThrownBy(bookingAuthorization::currentBookerId)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        try {
            authenticate(owner.getId(), "ROLE_USER");
            assertThat(bookingAuthorization.currentBookerId()).isEqualTo(owner.getId());
            bookingAuthorization.requireOwner(booking);
            authenticate(other.id(), "ROLE_USER");
            assertThatThrownBy(() -> bookingAuthorization.requireOwner(booking))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            authenticate(other.id(), "ROLE_ADMIN");
            assertThatThrownBy(bookingAuthorization::currentBookerId)
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(Long id, String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                id.toString(), null, AuthorityUtils.createAuthorityList(role)));
    }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new Csrf((MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName").asText(), body.get("token").asText());
    }

    private record Csrf(MockHttpSession session, String header, String value) {
    }
}
