package com.example.booking_service.service;

import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.client.SeatClient;
import com.example.enums.BookingStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BookingExpiryServiceTest {
    private final BookingRepository repository = mock(BookingRepository.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final SeatClient seatClient = mock(SeatClient.class);
    private final BookingExpiryService service = new BookingExpiryService(repository, ticketService, seatClient);

    @Test
    void overduePendingBookingReleasesSeatOnce() {
        Booking booking = Booking.builder().id(1L).status(BookingStatus.PENDING)
                .holdExpiresAt(Instant.now().minusSeconds(1)).build();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));

        service.cancelExpiredBooking(1L);
        service.cancelExpiredBooking(1L);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        verify(ticketService).cancelTicketsForBooking(1L);
        verify(seatClient).releaseBookingSeats(1L, java.util.List.of());
    }

    @Test
    void paymentConfirmationWinningRowLockPreventsCancellation() {
        Booking booking = Booking.builder().id(1L).status(BookingStatus.CONFIRMED)
                .holdExpiresAt(Instant.now().minusSeconds(1)).build();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));

        service.cancelExpiredBooking(1L);

        verifyNoInteractions(ticketService, seatClient);
    }

    @Test
    void deadlineIsRecheckedUnderRowLock() {
        Booking booking = Booking.builder().id(1L).status(BookingStatus.PENDING)
                .holdExpiresAt(Instant.now().plusSeconds(60)).build();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));

        service.cancelExpiredBooking(1L);

        assertEquals(BookingStatus.PENDING, booking.getStatus());
        verifyNoInteractions(ticketService, seatClient);
    }

    @Test
    void failedSeatReleaseLeavesBookingPendingForNextRun() {
        Booking booking = Booking.builder().id(1L).status(BookingStatus.PENDING)
                .holdExpiresAt(Instant.now().minusSeconds(1)).build();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));
        doThrow(new IllegalStateException("seat service unavailable"))
                .when(seatClient).releaseBookingSeats(1L, java.util.List.of());

        assertThrows(IllegalStateException.class, () -> service.cancelExpiredBooking(1L));

        assertEquals(BookingStatus.PENDING, booking.getStatus());
        verifyNoInteractions(ticketService);
        verify(repository, never()).save(booking);
    }
}
