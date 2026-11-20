package com.labreservation.bookings;

import com.labreservation.accounts.UserRepository;
import com.labreservation.catalog.LaboratoryRepository;
import com.labreservation.catalog.Workstation;
import com.labreservation.catalog.WorkstationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;

import static com.labreservation.bookings.BookingDtos.BookingView;
import static com.labreservation.bookings.BookingDtos.NewBooking;

@Service
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookings;
    private final UserRepository users;
    private final LaboratoryRepository laboratories;
    private final WorkstationRepository workstations;
    private final BookingAuthorization authorization;
    private final Clock clock;

    public BookingService(BookingRepository bookings, UserRepository users, LaboratoryRepository laboratories,
                          WorkstationRepository workstations, BookingAuthorization authorization, Clock clock) {
        this.bookings = bookings;
        this.users = users;
        this.laboratories = laboratories;
        this.workstations = workstations;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingView create(NewBooking request) {
        long userId = authorization.currentBookerId();
        BookingTimes.validateSlot(request.startsAt(), request.endsAt(), clock.instant());

        Workstation workstation = lockWorkstation(request.workstationId());
        if (!workstation.isEnabled() || !workstation.getLaboratory().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workstation is not enabled for booking");
        }

        var now = clock.instant();
        var end = BookingTimes.validateSlot(request.startsAt(), request.endsAt(), now);
        if (bookings.findConflictForUpdate(workstation.getId(), request.startsAt(), end).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workstation already booked for this time");
        }
        var user = users.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        return BookingView.from(bookings.save(new Booking(user, workstation, request.startsAt(), end,
                BookingStatus.CONFIRMED, now, null)));
    }

    public List<BookingView> listOwn() {
        return bookings.findAllByUserIdOrderByStartsAtDescIdDesc(authorization.currentBookerId())
                .stream().map(BookingView::from).toList();
    }

    public BookingView detail(long id) {
        Booking booking = bookings.findById(id).orElseThrow(BookingService::bookingNotFound);
        authorization.requireOwner(booking);
        return BookingView.from(booking);
    }

    @Transactional
    public BookingView cancel(long id) {
        long userId = authorization.currentBookerId();
        long workstationId = bookings.findOwnedWorkstationId(id, userId).orElseThrow(BookingService::bookingNotFound);
        lockWorkstation(workstationId);
        var booking = bookings.findForUpdate(id).orElseThrow(BookingService::bookingNotFound);
        authorization.requireOwner(booking);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return BookingView.from(booking);
        }
        var now = clock.instant();
        if (!booking.getStartsAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only future bookings can be cancelled");
        }
        booking.cancel(now);
        return BookingView.from(booking);
    }

    private Workstation lockWorkstation(long id) {
        // The scalar lookup avoids loading a stale workstation before the locking read.
        long laboratoryId = workstations.findLaboratoryId(id).orElseThrow(BookingService::workstationNotFound);
        laboratories.findForUpdate(laboratoryId).orElseThrow(BookingService::workstationNotFound);
        return workstations.findForUpdate(id).orElseThrow(BookingService::workstationNotFound);
    }

    private static ResponseStatusException bookingNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
    }

    private static ResponseStatusException workstationNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workstation not found");
    }
}
