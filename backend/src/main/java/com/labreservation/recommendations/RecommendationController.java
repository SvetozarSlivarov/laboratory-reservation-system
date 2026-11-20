package com.labreservation.recommendations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
class RecommendationController {

    private final RecommendationService recommendations;

    RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @PostMapping("/api/recommendations")
    RecommendationService.Recommendation recommend(@Valid @RequestBody Request request) {
        return recommendations.recommend(request.from(), request.to(), request.laboratoryId(), request.preference());
    }

    record Request(@NotNull Instant from, @NotNull Instant to, @Positive Long laboratoryId,
                   @Size(max = 250) String preference) {}
}
