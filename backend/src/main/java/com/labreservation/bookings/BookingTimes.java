package com.labreservation.bookings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

final class BookingTimes {

    private static final ZoneId ZONE = ZoneId.of("Europe/Sofia");
    private static final Duration HORIZON = Duration.ofDays(7);
    private static final Duration SLOT = Duration.ofHours(1);

    private BookingTimes() {}

    static Instant endOf(Instant start) {
        return start.plus(SLOT);
    }

    static Instant firstSlotAtOrAfter(Instant from) {
        var localHour = from.atZone(ZONE).truncatedTo(ChronoUnit.HOURS);
        return localHour.toInstant().isBefore(from) ? localHour.plusHours(1).toInstant() : localHour.toInstant();
    }

    static void validateWindow(Instant from, Instant to, Instant now) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw invalid("The start must precede the end");
        }
        if (from.isBefore(now) || to.isAfter(now.plus(HORIZON))) {
            throw invalid("The range must be within the next seven days");
        }
    }

    static Instant validateSlot(Instant start, Instant suppliedEnd, Instant now) {
        if (start == null || !start.isAfter(now) || start.isAfter(now.plus(HORIZON))) {
            throw invalid("The slot must start in the future within the next seven days");
        }
        Instant end = endOf(start);
        validateWindow(start, suppliedEnd == null ? end : suppliedEnd, now);
        if (!start.equals(firstSlotAtOrAfter(start)) || (suppliedEnd != null && !end.equals(suppliedEnd))) {
            throw invalid("Bookings must start on the hour in Europe/Sofia and last 60 minutes");
        }
        return end;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
