package com.labreservation.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labreservation.accounts.User;
import com.labreservation.accounts.UserRepository;
import com.labreservation.accounts.UserRole;
import com.labreservation.bookings.AvailabilityService;
import com.labreservation.bookings.BookingDtos.NewBooking;
import com.labreservation.bookings.BookingService;
import com.labreservation.catalog.CatalogService;
import com.labreservation.catalog.Laboratory;
import com.labreservation.catalog.LaboratoryRepository;
import com.labreservation.catalog.Workstation;
import com.labreservation.catalog.WorkstationRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.mcp.server.enabled=true", "server.servlet.session.cookie.secure=false", "app.gemini.api-key="
})
@Import(McpServerIT.FixedClock.class)
class McpServerIT {

    private static final String PASSWORD = "a long integration test password";
    private static final Instant START = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private UserRepository users;
    @Autowired private LaboratoryRepository laboratories;
    @Autowired private WorkstationRepository workstations;
    @MockitoSpyBean private CatalogService catalog;
    @MockitoSpyBean private AvailabilityService availability;
    @MockitoSpyBean private BookingService bookings;

    private final HttpClient http = HttpClient.newHttpClient();
    private Laboratory lab;
    private Workstation station;
    private Laboratory disabled;

    @BeforeEach
    void catalogFixture() {
        lab = laboratories.save(new Laboratory("MCP laboratory", "Room A", true));
        disabled = laboratories.save(new Laboratory("Private laboratory", "Room B", false));
        station = workstations.save(new Workstation(lab, "PC-01", null, true));
    }

    @Test
    void initializesListsAndInvokesEveryToolUsingTheSameServicesAsRest() throws Exception {
        Session owner = login(UserRole.USER);
        Session other = login(UserRole.USER);
        try (McpSyncClient client = client(owner); McpSyncClient otherClient = client(other)) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("laboratory-reservation");
            otherClient.initialize();
            var tools = client.listTools().tools();
            assertThat(tools).extracting(tool -> tool.name()).containsExactlyInAnyOrder("list_laboratories",
                    "list_available_workstations", "my_bookings", "create_booking", "cancel_booking");
            assertThat(tools).allSatisfy(tool -> {
                assertThat(tool.description()).hasSizeGreaterThanOrEqualTo(20);
                assertThat(tool.inputSchema()).isNotNull();
                assertThat(tool.outputSchema()).isNotEmpty();
            });

            JsonNode visible = success(call(client, "list_laboratories", Map.of())).get("laboratories");
            assertThat(visible).isEqualTo(json.readTree(rest(owner, "GET", "/api/laboratories", null).body()));
            assertThat(visible.findValues("id")).noneMatch(id -> id.asLong() == disabled.getId());
            verify(catalog, times(2)).listLaboratories();

            var range = Map.<String, Object>of("from", START.toString(), "to", END.toString(), "laboratoryId", lab.getId());
            JsonNode slots = success(call(client, "list_available_workstations", range)).get("slots");
            String query = "/api/availability?from=" + START + "&to=" + END + "&laboratoryId=" + lab.getId();
            assertThat(slots).isEqualTo(json.readTree(rest(owner, "GET", query, null).body()));
            assertThat(slots.get(0).path("workstation").path("id").asLong()).isEqualTo(station.getId());
            verify(availability, times(2)).search(START, END, lab.getId());

            var args = Map.<String, Object>of("workstationId", station.getId(), "startsAt", START.toString());
            var created = success(call(client, "create_booking", args));
            long bookingId = created.path("id").asLong();
            assertThat(bookingId).isPositive();
            assertThat(created.path("status").asText()).isEqualTo("CONFIRMED");
            verify(bookings).create(new NewBooking(station.getId(), START, null));
            var conflict = rest(other, "POST", "/api/bookings", args);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertError(call(otherClient, "create_booking", args), "409");
            verify(bookings, times(3)).create(new NewBooking(station.getId(), START, null));

            var own = success(call(client, "my_bookings", Map.of())).get("bookings");
            assertThat(own).isEqualTo(json.readTree(rest(owner, "GET", "/api/bookings/me", null).body()));
            assertThat(own.size()).isEqualTo(1);
            verify(bookings, times(2)).listOwn();
            assertThat(success(call(otherClient, "my_bookings", Map.of())).get("bookings").isEmpty()).isTrue();
            assertError(call(otherClient, "cancel_booking", Map.of("bookingId", bookingId)), "404");
            var cancelled = success(call(client, "cancel_booking", Map.of("bookingId", bookingId)));
            assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled).isEqualTo(json.readTree(
                    rest(owner, "POST", "/api/bookings/" + bookingId + "/cancel", null).body()));
            verify(bookings, times(3)).cancel(bookingId);

            assertError(call(client, "cancel_booking", Map.of("bookingId", -1)), "400");
            assertError(call(client, "cancel_booking", Map.of("bookingId", "1")), "400");
            assertError(call(client, "create_booking", Map.of("workstationId", station.getId())), "400");
            assertError(call(client, "create_booking", Map.of("workstationId", station.getId(), "startsAt", "tomorrow")), "400");
            assertError(call(client, "list_available_workstations", Map.of("from", END.toString(), "to", START.toString())), "400");
            assertError(call(client, "my_bookings", Map.of("userId", 123)), "400");
        }
    }

    @Test
    void rejectsAdminAnonymousCrossOriginAndExpiredSessions() throws Exception {
        Session admin = login(UserRole.ADMIN);
        assertThat(initializeRequest(admin, null, true)).isEqualTo(403);
        assertThat(initializeRequest(null, null, false)).isIn(401, 403);
        var initial = rest(null, "GET", "/api/auth/csrf", null);
        var csrf = json.readTree(initial.body());
        var anonymous = new Session(sessionCookie(initial), csrf.path("headerName").asText(), csrf.path("token").asText());
        assertThat(initializeRequest(anonymous, null, true)).isEqualTo(401);
        Session user = login(UserRole.USER);
        assertThat(initializeRequest(user, "https://untrusted.example.test", true)).isEqualTo(403);
        assertThat(initializeRequest(user, null, false)).isEqualTo(403);
        assertThat(rest(user, "POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(initializeRequest(user, null, true)).isIn(401, 403);
    }

    private int initializeRequest(Session session, String origin, boolean withCsrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (session != null) {
            request.header("Cookie", session.cookie());
            if (withCsrf) request.header(session.header(), session.csrf());
        }
        if (origin != null) request.header("Origin", origin);
        request.POST(HttpRequest.BodyPublishers.ofString("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
                """));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private McpSyncClient client(Session session) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint("/mcp")
                .openConnectionOnStartup(false)
                .customizeRequest(request -> request.header("Cookie", session.cookie())
                        .header(session.header(), session.csrf()))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    private CallToolResult call(McpSyncClient client, String name, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(name, arguments));
    }

    private JsonNode success(CallToolResult result) {
        assertThat(result.isError()).as("MCP tool result: %s", result.content()).isNotEqualTo(true);
        return json.valueToTree(result.structuredContent());
    }

    private static void assertError(CallToolResult result, String status) {
        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).startsWith(status);
    }

    private Session login(UserRole role) throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        users.save(new User(email, encoder.encode(PASSWORD), role));
        var initial = rest(null, "GET", "/api/auth/csrf", null);
        var csrf = json.readTree(initial.body());
        String cookie = sessionCookie(initial);
        String fields = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        var auth = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                .header("Cookie", cookie)
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(fields)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(auth.statusCode()).isEqualTo(204);
        String rotatingCookie = sessionCookie(auth);
        var renewed = rest(new Session(rotatingCookie, "", ""), "GET", "/api/auth/csrf", null);
        var token = json.readTree(renewed.body());
        return new Session(rotatingCookie, token.path("headerName").asText(), token.path("token").asText());
    }

    private static String sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("set-cookie").stream().map(HttpCookie::parse)
                .flatMap(java.util.Collection::stream).filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().map(cookie -> cookie.getName() + "=" + cookie.getValue()).orElseThrow();
    }

    private HttpResponse<String> rest(Session session, String method, String path, Object body)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        if (session != null) {
            request.header("Cookie", session.cookie());
            if (!session.header().isEmpty()) request.header(session.header(), session.csrf());
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private record Session(String cookie, String header, String csrf) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock mcpClock() {
            return Clock.fixed(START.minusSeconds(1800), ZoneOffset.UTC);
        }
    }
}
