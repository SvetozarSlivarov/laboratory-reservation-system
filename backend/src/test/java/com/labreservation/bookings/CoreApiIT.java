package com.labreservation.bookings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labreservation.accounts.User;
import com.labreservation.accounts.UserRepository;
import com.labreservation.accounts.UserRole;
import com.labreservation.catalog.Laboratory;
import com.labreservation.catalog.LaboratoryRepository;
import com.labreservation.catalog.Workstation;
import com.labreservation.catalog.WorkstationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.gemini.api-key=")
@AutoConfigureMockMvc
@Import(CoreApiIT.FixedClock.class)
class CoreApiIT {

    private static final Instant NOW = Instant.parse("2026-11-21T08:15:00Z");
    private static final Instant START = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private LaboratoryRepository laboratories;
    @Autowired private WorkstationRepository workstations;
    @Autowired private BookingRepository bookings;

    private User owner;
    private User other;
    private Laboratory laboratory;
    private Workstation workstation;

    @BeforeEach
    void createFixture() {
        owner = users.save(new User(UUID.randomUUID() + "@example.test", null, UserRole.USER));
        other = users.save(new User(UUID.randomUUID() + "@example.test", null, UserRole.USER));
        laboratory = laboratories.save(new Laboratory("Test lab", "Room", true));
        workstation = workstations.save(new Workstation(laboratory, "PC-01", null, true));
    }

    @Test
    void createsListsAndCancelsOnlyOwnBookingsThenReleasesTheSlot() throws Exception {
        var result = create(owner, workstation.getId(), START, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.endsAt").value(END.toString()))
                .andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/bookings/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/{id}", id).with(actor(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/{id}/cancel", id).with(actor(other)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/me").with(actor(other))).andExpect(content().json("[]"));
        mvc.perform(get("/api/bookings/me").with(actor(owner))).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/bookings/{id}", id).with(actor(owner))).andExpect(jsonPath("$.id").value(id));
        availability().andExpect(content().json("[]"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/bookings/{id}/cancel", id).with(actor(owner)).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancelledAt").value(NOW.toString()));
        }
        availability().andExpect(jsonPath("$[0].workstation.id").value(workstation.getId()));
        create(other, workstation.getId(), START, END).andExpect(status().isCreated());
    }

    @Test
    void rejectsOverlapAllowsAdjacencyAndReportsInvalidRangesAndMissingStation() throws Exception {
        create(owner, workstation.getId(), START, END).andExpect(status().isCreated());
        create(other, workstation.getId(), START, END).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Workstation already booked for this time"));
        create(other, workstation.getId(), END, END.plusSeconds(3600)).andExpect(status().isCreated());
        create(owner, workstation.getId(), START, START).andExpect(status().isBadRequest());
        create(owner, workstation.getId(), START.plusSeconds(60), null).andExpect(status().isBadRequest());
        create(owner, workstation.getId(), NOW.plusSeconds(8 * 86400), null).andExpect(status().isBadRequest());
        create(owner, Long.MAX_VALUE, START, END).andExpect(status().isNotFound());
        mvc.perform(get("/api/availability").with(actor(owner))
                        .param("from", END.toString()).param("to", START.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentAttemptsCommitOnlyOneBooking() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return create(owner, workstation.getId(), START, END).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return create(other, workstation.getId(), START, END).andReturn().getResponse().getStatus();
            });
            assertThat(first.get(20, TimeUnit.SECONDS)).isIn(201, 409);
            assertThat(java.util.List.of(first.get(), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(bookings.findAllByUserIdOrderByStartsAtDescIdDesc(owner.getId()).size()
                + bookings.findAllByUserIdOrderByStartsAtDescIdDesc(other.getId()).size()).isEqualTo(1);
    }

    @Test
    void recommendationUsesActualAvailabilityAndCreatesNoBooking() throws Exception {
        String request = json.writeValueAsString(java.util.Map.of(
                "from", START.toString(), "to", END.toString(), "laboratoryId", laboratory.getId(),
                "preference", "I need Java"));
        mvc.perform(post("/api/recommendations").with(actor(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.slot.workstation.id").value(workstation.getId()));
        assertThat(bookings.findAllByUserIdOrderByStartsAtDescIdDesc(owner.getId())).isEmpty();

        create(other, workstation.getId(), START, END).andExpect(status().isCreated());
        mvc.perform(post("/api/recommendations").with(actor(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("NO_AVAILABILITY"));
    }

    @Test
    void catalogWritesRequireAdminAndDisabledItemsAreHidden() throws Exception {
        var admin = users.save(new User(UUID.randomUUID() + "@example.test", null, UserRole.ADMIN));
        String request = """
                {"name":"New lab","location":"Room 2","enabled":true}
                """;
        mvc.perform(post("/api/laboratories").with(actor(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        var result = mvc.perform(post("/api/laboratories").with(actor(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        long labId = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        String workstationRequest = """
                {"code":"PC-10","capabilities":"Java","enabled":true}
                """;
        var created = mvc.perform(post("/api/laboratories/{id}/workstations", labId).with(actor(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(workstationRequest))
                .andExpect(status().isCreated()).andReturn();
        long stationId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/laboratories/{id}/workstations", labId).with(actor(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(workstationRequest))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/laboratories/{id}/workstations", labId).with(actor(owner)))
                .andExpect(jsonPath("$[0].id").value(stationId));
        mvc.perform(patch("/api/workstations/{id}", stationId).with(actor(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        create(owner, stationId, START, END).andExpect(status().isConflict());
        mvc.perform(get("/api/laboratories/{id}/workstations", labId).with(actor(owner)))
                .andExpect(content().json("[]"));
        mvc.perform(patch("/api/laboratories/{id}", labId).with(actor(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/laboratories/{id}", labId).with(actor(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/laboratories/{id}", labId).with(actor(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/laboratories").with(actor(owner))).andExpect(status().isOk());
    }

    private ResultActions availability() throws Exception {
        return mvc.perform(get("/api/availability").with(actor(owner))
                .param("from", START.toString()).param("to", END.toString())
                .param("laboratoryId", laboratory.getId().toString()));
    }

    private ResultActions create(User user, long workstationId, Instant start, Instant end) throws Exception {
        var body = new BookingDtos.NewBooking(workstationId, start, end);
        return mvc.perform(post("/api/bookings").with(actor(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private RequestPostProcessor actor(User account) {
        return user(account.getId().toString()).roles(account.getRole().name());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
