package com.labreservation.recommendations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labreservation.bookings.AvailabilityService;
import com.labreservation.bookings.BookingDtos.AvailableSlot;
import com.labreservation.catalog.CatalogDtos.LaboratoryView;
import com.labreservation.catalog.CatalogDtos.WorkstationView;
import com.labreservation.catalog.CatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.LongStream;

import static com.labreservation.recommendations.RecommendationService.Source.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecommendationServiceTest {

    private static final Instant FROM = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant TO = FROM.plusSeconds(10_800);
    private static final Long LAB = 10L;
    private final AvailabilityService availability = mock(AvailabilityService.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final AvailableSlot first = slot(4L, FROM);
    private final AvailableSlot second = slot(5L, FROM);

    @BeforeEach
    void inventory() {
        when(availability.search(FROM, TO, LAB)).thenReturn(List.of(second, first));
        when(availability.searchRemaining(FROM, TO, LAB)).thenReturn(List.of(second, first));
        when(catalog.listLaboratories()).thenReturn(List.of(new LaboratoryView(LAB, "Computing", "Private room", true)));
    }

    @Test
    void returnsValidatedModelSelectionAndOnlySendsBoundedCandidateData() {
        var candidates = LongStream.rangeClosed(4, 30).mapToObj(id -> slot(id, FROM)).toList();
        when(availability.search(FROM, TO, LAB)).thenReturn(candidates.reversed());
        var result = service(fake((preference, options) -> {
            assertThat(preference).isEqualTo("Needs Java");
            assertThat(options).hasSize(20);
            assertThat(options.getFirst())
                    .isEqualTo(new RecommendationModel.Candidate("slot-1", FROM, FROM.plusSeconds(3600),
                            "Computing", "Java"));
            return """
                    {"slotId":"slot-2","explanation":"This station has Java."}
                    """;
        })).recommend(FROM, TO, LAB, " Needs Java ");

        assertThat(result.source()).isEqualTo(GEMINI);
        assertThat(result.slot()).isEqualTo(second);
        assertThat(result.explanation()).isEqualTo("This station has Java.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not JSON",
            "{\"slotId\":\"slot-3\",\"explanation\":\"Invented\"}",
            "{\"slotId\":\"slot-1\"}",
            "{\"slotId\":\"slot-1\",\"explanation\":\"OK\",\"workstationId\":4}",
            "{\"slotId\":\"slot-1\",\"explanation\":\"OK\"} {}",
            "{\"slotId\":\"slot-1\",\"slotId\":\"slot-2\",\"explanation\":\"OK\"}"
    })
    void invalidOrNonexistentSelectionFallsBackDeterministically(String response) {
        var result = service(fake((preference, options) -> response)).recommend(FROM, TO, LAB, null);
        assertThat(result.source()).isEqualTo(FALLBACK);
        assertThat(result.slot()).isEqualTo(first);
    }

    @Test
    void unavailableOrRateLimitedProviderFallsBack() {
        var result = service(fake((preference, options) -> {
            throw new IllegalStateException("Provider returned 429");
        })).recommend(FROM, TO, LAB, null);
        assertThat(result.source()).isEqualTo(FALLBACK);
        assertThat(result.slot()).isEqualTo(first);
    }

    @Test
    void timeoutFallsBackWhenTheFakeClientRespondsTooLate() {
        var result = new RecommendationService(availability, catalog, fake((preference, options) -> {
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "{\"slotId\":\"slot-2\",\"explanation\":\"Late\"}";
        }), new ObjectMapper(), Duration.ofMillis(30)).recommend(FROM, TO, LAB, null);
        assertThat(result.source()).isEqualTo(FALLBACK);
        assertThat(result.slot()).isEqualTo(first);
    }

    @Test
    void missingKeyUsesFallbackFromFreshAvailability() {
        var disabled = new GeminiRecommendationModel("", "unused", Duration.ofSeconds(3), new ObjectMapper());
        when(availability.searchRemaining(FROM, TO, LAB)).thenReturn(List.of(second));
        var result = service(disabled).recommend(FROM, TO, LAB, null);
        assertThat(result.source()).isEqualTo(FALLBACK);
        assertThat(result.slot()).isEqualTo(second);
        verifyNoInteractions(catalog);
    }

    @Test
    void fallsBackWhenSelectedSlotWasTakenDuringInference() {
        when(availability.searchRemaining(FROM, TO, LAB)).thenReturn(List.of(second));
        var result = service(fake((preference, options) ->
                "{\"slotId\":\"slot-1\",\"explanation\":\"Matches\"}")).recommend(FROM, TO, LAB, null);
        assertThat(result.source()).isEqualTo(FALLBACK);
        assertThat(result.slot()).isEqualTo(second);
    }

    @Test
    void skipsTheModelIfThereAreNoSlotsAndHandlesAllSlotsDisappearing() {
        var model = mock(RecommendationModel.class);
        when(availability.search(FROM, TO, LAB)).thenReturn(List.of());
        var empty = service(model).recommend(FROM, TO, LAB, null);
        assertThat(empty.source()).isEqualTo(NO_AVAILABILITY);
        assertThat(empty.slot()).isNull();
        verifyNoInteractions(model);

        when(availability.search(FROM, TO, LAB)).thenReturn(List.of(first));
        when(availability.searchRemaining(FROM, TO, LAB)).thenReturn(List.of());
        var gone = service(fake((preference, options) ->
                "{\"slotId\":\"slot-1\",\"explanation\":\"Matches\"}")).recommend(FROM, TO, LAB, null);
        assertThat(gone.source()).isEqualTo(NO_AVAILABILITY);
        assertThat(gone.slot()).isNull();
    }

    private RecommendationService service(RecommendationModel model) {
        return new RecommendationService(availability, catalog, model, new ObjectMapper(), Duration.ofSeconds(3));
    }

    private static RecommendationModel fake(BiFunction<String, List<RecommendationModel.Candidate>, String> reply) {
        return new RecommendationModel() {
            @Override public boolean configured() { return true; }
            @Override public String select(String preference, List<Candidate> candidates) {
                return reply.apply(preference, candidates);
            }
        };
    }

    private static AvailableSlot slot(long workstationId, Instant start) {
        return new AvailableSlot(new WorkstationView(workstationId, LAB, "PC-" + workstationId, "Java", true),
                start, start.plusSeconds(3600));
    }
}
