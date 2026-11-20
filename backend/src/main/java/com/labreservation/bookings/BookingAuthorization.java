package com.labreservation.bookings;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingAuthorization {

    public long currentBookerId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Sign in to manage bookings");
        }
        if (authentication.getAuthorities().stream().noneMatch(role -> role.getAuthority().equals("ROLE_USER"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only regular users can manage bookings");
        }
        return Long.parseLong(authentication.getName());
    }

    public void requireOwner(Booking booking) {
        if (!booking.getUser().getId().equals(currentBookerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
    }
}
