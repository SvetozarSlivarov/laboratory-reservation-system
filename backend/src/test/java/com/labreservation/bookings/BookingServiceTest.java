package com.labreservation.bookings;

import com.labreservation.accounts.User;
import com.labreservation.accounts.UserRepository;
import com.labreservation.accounts.UserRole;
import com.labreservation.catalog.Laboratory;
import com.labreservation.catalog.LaboratoryRepository;
import com.labreservation.catalog.Workstation;
import com.labreservation.catalog.WorkstationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T08:15:00Z");
    private static final Instant START = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LaboratoryRepository laboratories = mock(LaboratoryRepository.class);
    private final WorkstationRepository workstations = mock(WorkstationRepository.class);
    private final BookingAuthorization authorization = mock(BookingAuthorization.class);
    private final BookingService service = new BookingService(bookings, users, laboratories, workstations,
            authorization, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void authenticate() {
        when(authorization.currentBookerId()).thenReturn(5L);
    }

    @Test
    void locksParentsInOrderAndChecksConflictsBeforeInserting() {
        arrangeWorkstation();
        var user = new User("owner@example.test", null, UserRole.USER);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(bookings.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        var booking = service.create(new BookingDtos.NewBooking(9L, START, null));

        assertThat(booking.endsAt()).isEqualTo(END);
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        var order = inOrder(laboratories, workstations, bookings);
        order.verify(laboratories).findForUpdate(7L);
        order.verify(workstations).findForUpdate(9L);
        order.verify(bookings).findConflictForUpdate(9L, START, END);
        order.verify(bookings).save(any(Booking.class));
    }

    @Test
    void rejectsOverlapWithoutSaving() {
        arrangeWorkstation();
        when(bookings.findConflictForUpdate(9L, START, END)).thenReturn(Optional.of(10L));
        assertThatThrownBy(() -> service.create(new BookingDtos.NewBooking(9L, START, END)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(bookings, never()).save(any());
    }

    @Test
    void rejectsInvalidTimesBeforeLocking() {
        assertThatThrownBy(() -> service.create(new BookingDtos.NewBooking(9L, START, START)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(workstations, laboratories, bookings);
    }

    @Test
    void returnsNotFoundForMissingWorkstation() {
        assertThatThrownBy(() -> service.create(new BookingDtos.NewBooking(9L, START, END)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(laboratories, bookings);
    }

    @Test
    void realTimeSlotsCoverBothRepeatedHoursAndSkipMissingHour() {
        var firstRepeated = Instant.parse("2026-10-25T00:00:00Z");
        var secondRepeated = Instant.parse("2026-10-25T01:00:00Z");
        assertThat(BookingTimes.endOf(firstRepeated)).isEqualTo(secondRepeated);
        assertThat(BookingTimes.firstSlotAtOrAfter(firstRepeated.plusSeconds(1))).isEqualTo(secondRepeated);

        var beforeSpringJump = Instant.parse("2026-03-29T00:00:00Z");
        var afterSpringJump = Instant.parse("2026-03-29T01:00:00Z");
        assertThat(BookingTimes.firstSlotAtOrAfter(beforeSpringJump.plusSeconds(1))).isEqualTo(afterSpringJump);
    }

    private void arrangeWorkstation() {
        var laboratory = new Laboratory("Lab", "Room", true);
        var workstation = mock(Workstation.class);
        when(workstation.getId()).thenReturn(9L);
        when(workstation.isEnabled()).thenReturn(true);
        when(workstation.getLaboratory()).thenReturn(laboratory);
        when(workstations.findLaboratoryId(9L)).thenReturn(Optional.of(7L));
        when(laboratories.findForUpdate(7L)).thenReturn(Optional.of(laboratory));
        when(workstations.findForUpdate(9L)).thenReturn(Optional.of(workstation));
    }
}
