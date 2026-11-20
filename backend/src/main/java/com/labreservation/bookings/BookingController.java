package com.labreservation.bookings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static com.labreservation.bookings.BookingDtos.*;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookings;
    private final AvailabilityService availability;

    public BookingController(BookingService bookings, AvailabilityService availability) {
        this.bookings = bookings;
        this.availability = availability;
    }

    @GetMapping("/availability")
    List<AvailableSlot> search(@RequestParam Instant from, @RequestParam Instant to,
                               @Positive @RequestParam(required = false) Long laboratoryId) {
        return availability.search(from, to, laboratoryId);
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView create(@Valid @RequestBody NewBooking request) {
        return bookings.create(request);
    }

    @GetMapping("/bookings/me")
    List<BookingView> listOwn() {
        return bookings.listOwn();
    }

    @GetMapping("/bookings/{id}")
    BookingView detail(@Positive @PathVariable long id) {
        return bookings.detail(id);
    }

    @PostMapping("/bookings/{id}/cancel")
    BookingView cancel(@Positive @PathVariable long id) {
        return bookings.cancel(id);
    }
}
