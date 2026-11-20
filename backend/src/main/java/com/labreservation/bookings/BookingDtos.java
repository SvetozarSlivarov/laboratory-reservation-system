package com.labreservation.bookings;

import com.labreservation.catalog.CatalogDtos.WorkstationView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public final class BookingDtos {

    private BookingDtos() {}

    public record NewBooking(@NotNull @Positive Long workstationId, @NotNull Instant startsAt, Instant endsAt) {}

    public record BookingView(Long id, Long workstationId, Instant startsAt, Instant endsAt,
                              BookingStatus status, Instant createdAt, Instant cancelledAt) {
        static BookingView from(Booking booking) {
            return new BookingView(booking.getId(), booking.getWorkstation().getId(), booking.getStartsAt(),
                    booking.getEndsAt(), booking.getStatus(), booking.getCreatedAt(), booking.getCancelledAt());
        }
    }

    public record AvailableSlot(WorkstationView workstation, Instant startsAt, Instant endsAt) {}
}
