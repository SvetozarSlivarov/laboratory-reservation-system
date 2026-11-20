
package com.labreservation.bookings;

import com.labreservation.catalog.CatalogDtos.WorkstationView;
import com.labreservation.catalog.LaboratoryRepository;
import com.labreservation.catalog.WorkstationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.labreservation.bookings.BookingDtos.AvailableSlot;
import static java.util.stream.Collectors.groupingBy;

@Service
public class AvailabilityService {

    private final WorkstationRepository workstations;
    private final LaboratoryRepository laboratories;
    private final BookingRepository bookings;
    private final BookingAuthorization authorization;
    private final Clock clock;

    public AvailabilityService(WorkstationRepository workstations, LaboratoryRepository laboratories,
                               BookingRepository bookings, BookingAuthorization authorization, Clock clock) {
        this.workstations = workstations;
        this.laboratories = laboratories;
        this.bookings = bookings;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AvailableSlot> search(Instant from, Instant to, Long laboratoryId) {
        authorization.currentBookerId();
        Instant now = clock.instant();
        BookingTimes.validateWindow(from, to, now);
        if (laboratoryId != null && laboratories.findById(laboratoryId).filter(lab -> lab.isEnabled()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Laboratory not found");
        }
        return findSlots(from, to, laboratoryId, now);
    }

    @Transactional(readOnly = true)
    public List<AvailableSlot> searchRemaining(Instant from, Instant to, Long laboratoryId) {
        authorization.currentBookerId();
        Instant now = clock.instant();
        Instant remainingFrom = from.isBefore(now) ? now : from;
        if (!remainingFrom.isBefore(to)) {
            return List.of();
        }
        BookingTimes.validateWindow(remainingFrom, to, now);
        return findSlots(remainingFrom, to, laboratoryId, now);
    }

    private List<AvailableSlot> findSlots(Instant from, Instant to, Long laboratoryId, Instant now) {
        var candidates = workstations.findEnabled(laboratoryId).stream().map(WorkstationView::from).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        var conflicts = bookings.findConflicts(candidates.stream().map(WorkstationView::id).toList(), from, to)
                .stream().collect(groupingBy(booking -> booking.getWorkstation().getId()));
        var slots = new ArrayList<AvailableSlot>();
        for (Instant start = BookingTimes.firstSlotAtOrAfter(from); !BookingTimes.endOf(start).isAfter(to);
             start = BookingTimes.endOf(start)) {
            if (!start.isAfter(now)) continue;
            Instant slotStart = start;
            Instant slotEnd = BookingTimes.endOf(start);
            for (var candidate : candidates) {
                boolean booked = conflicts.getOrDefault(candidate.id(), List.of()).stream()
                        .anyMatch(booking -> booking.getStartsAt().isBefore(slotEnd)
                                && booking.getEndsAt().isAfter(slotStart));
                if (!booked) {
                    slots.add(new AvailableSlot(candidate, slotStart, slotEnd));
                }
            }
        }
        return slots;
    }
}
