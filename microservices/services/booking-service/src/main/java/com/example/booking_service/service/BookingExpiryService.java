package com.example.booking_service.service;

import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.client.SeatClient;
import com.example.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingExpiryService {
    private final BookingRepository bookingRepository;
    private final TicketService ticketService;
    private final SeatClient seatClient;

    @Transactional
    public void cancelExpiredBooking(Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING
                || booking.getHoldExpiresAt() == null
                || booking.getHoldExpiresAt().isAfter(Instant.now())) {
            return;
        }
        // Keep the booking pending if release fails, so the next job run retries it.
        seatClient.releaseBookingSeats(bookingId, booking.getSeatInstanceIds() == null
                ? List.of() : booking.getSeatInstanceIds());
        ticketService.cancelTicketsForBooking(bookingId);
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);
    }
}
