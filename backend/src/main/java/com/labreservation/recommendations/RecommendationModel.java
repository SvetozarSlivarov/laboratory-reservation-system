package com.labreservation.recommendations;

import java.time.Instant;
import java.util.List;

interface RecommendationModel {

    boolean configured();

    String select(String preference, List<Candidate> candidates);

    record Candidate(String slotId, Instant startsAt, Instant endsAt, String laboratory, String capabilities) {}
}
