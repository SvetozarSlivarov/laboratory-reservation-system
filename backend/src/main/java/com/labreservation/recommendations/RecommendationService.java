package com.labreservation.recommendations;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.labreservation.bookings.AvailabilityService;
import com.labreservation.bookings.BookingDtos.AvailableSlot;
import com.labreservation.catalog.CatalogDtos.LaboratoryView;
import com.labreservation.catalog.CatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toMap;

@Service
public class RecommendationService {

    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_EXPLANATION_LENGTH = 240;
    private static final Comparator<AvailableSlot> SLOT_ORDER = Comparator.comparing(AvailableSlot::startsAt)
            .thenComparing(slot -> slot.workstation().id());

    private final AvailabilityService availability;
    private final CatalogService catalog;
    private final RecommendationModel model;
    private final ObjectReader responseReader;
    private final Duration timeout;

    RecommendationService(AvailabilityService availability, CatalogService catalog, RecommendationModel model,
                          ObjectMapper json, @Value("${app.gemini.timeout}") Duration timeout) {
        this.availability = availability;
        this.catalog = catalog;
        this.model = model;
        this.timeout = timeout;
        this.responseReader = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public Recommendation recommend(Instant from, Instant to, Long laboratoryId, String preference) {
        List<AvailableSlot> initial = availability.search(from, to, laboratoryId);
        if (initial.isEmpty()) {
            return noAvailability();
        }

        List<AvailableSlot> submitted = initial.stream().sorted(SLOT_ORDER).limit(MAX_CANDIDATES).toList();
        Selection selection = null;
        if (model.configured()) {
            var names = catalog.listLaboratories().stream().collect(toMap(LaboratoryView::id, LaboratoryView::name));
            var candidates = IntStream.range(0, submitted.size())
                    .mapToObj(index -> candidate(index, submitted.get(index), names)).toList();
            selection = select(preference == null ? "" : preference.strip(), candidates);
        }

        List<AvailableSlot> current = availability.searchRemaining(from, to, laboratoryId);
        if (selection != null) {
            AvailableSlot selected = submitted.get(selection.index());
            var match = current.stream().filter(slot -> sameSlot(slot, selected)).findFirst();
            if (match.isPresent()) {
                return new Recommendation(Source.GEMINI, match.get(), selection.explanation());
            }
        }
        return current.stream().min(SLOT_ORDER)
                .map(slot -> new Recommendation(Source.FALLBACK, slot, "This is the earliest available slot."))
                .orElseGet(RecommendationService::noAvailability);
    }

    private Selection select(String preference, List<RecommendationModel.Candidate> candidates) {
        try {
            String response = Mono.fromCallable(() -> model.select(preference, candidates))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(timeout)
                    .block();
            if (response == null || response.length() > 4096) {
                return null;
            }
            JsonNode object = responseReader.readValue(response);
            if (!object.isObject() || object.size() != 2 || !object.path("slotId").isTextual()
                    || !object.path("explanation").isTextual()) {
                return null;
            }
            String id = object.get("slotId").textValue();
            String explanation = object.get("explanation").textValue().strip();
            if (explanation.isEmpty() || explanation.length() > MAX_EXPLANATION_LENGTH
                    || explanation.codePoints().anyMatch(Character::isISOControl)) {
                return null;
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).slotId().equals(id)) {
                    return new Selection(i, explanation);
                }
            }
        } catch (JsonProcessingException | RuntimeException exception) {
            // Provider failure, rate limiting, timeout and invalid output all use the same fallback.
        }
        return null;
    }

    private static RecommendationModel.Candidate candidate(int index, AvailableSlot slot, Map<Long, String> names) {
        return new RecommendationModel.Candidate("slot-" + (index + 1), slot.startsAt(), slot.endsAt(),
                names.get(slot.workstation().laboratoryId()), slot.workstation().capabilities());
    }

    private static boolean sameSlot(AvailableSlot first, AvailableSlot second) {
        return first.workstation().id().equals(second.workstation().id())
                && first.startsAt().equals(second.startsAt()) && first.endsAt().equals(second.endsAt());
    }

    private static Recommendation noAvailability() {
        return new Recommendation(Source.NO_AVAILABILITY, null, "No slots are available in this time range.");
    }

    private record Selection(int index, String explanation) {}

    public enum Source { GEMINI, FALLBACK, NO_AVAILABILITY }

    public record Recommendation(Source source, AvailableSlot slot, String explanation) {}
}
