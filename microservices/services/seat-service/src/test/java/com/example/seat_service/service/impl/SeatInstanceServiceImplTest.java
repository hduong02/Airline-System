package com.example.seat_service.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.enums.SeatAvailabilityStatus;
import com.example.seat_service.model.FlightInstanceCabin;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.CancelledBookingRepository;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;

class SeatInstanceServiceImplTest {
    private final SeatInstanceRepository seatRepository = mock(SeatInstanceRepository.class);
    private final FlightInstanceCabinRepository cabinRepository = mock(FlightInstanceCabinRepository.class);
    private final CancelledBookingRepository cancelledRepository = mock(CancelledBookingRepository.class);
    private final SeatInstanceServiceImpl service = new SeatInstanceServiceImpl(
            seatRepository, cabinRepository, cancelledRepository);

    @Test
    void reservationClaimsSeatBeforePaymentAndConfirmationKeepsCount() {
        FlightInstanceCabin cabin = FlightInstanceCabin.builder()
                .id(40L).totalSeats(1).bookedSeats(0).build();
        SeatInstance seat = SeatInstance.builder().id(100L)
                .flightInstanceCabin(cabin).status(SeatAvailabilityStatus.AVAILABLE).build();
        when(seatRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(seat));
        when(cabinRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(cabin));

        service.reserveBookingSeats(99L, List.of(100L));

        assertEquals(SeatAvailabilityStatus.RESERVED, seat.getStatus());
        assertEquals(99L, seat.getBookingId());
        assertEquals(1, cabin.getBookedSeats());
        assertThrows(IllegalStateException.class,
                () -> service.reserveBookingSeats(98L, List.of(100L)));

        service.confirmBookingSeats(99L, List.of(100L));

        assertEquals(SeatAvailabilityStatus.BOOKED, seat.getStatus());
        assertEquals(1, cabin.getBookedSeats());
    }

    @Test
    void failedPaymentReleasesReservation() {
        FlightInstanceCabin cabin = FlightInstanceCabin.builder()
                .id(40L).totalSeats(1).bookedSeats(1).build();
        SeatInstance seat = SeatInstance.builder().id(100L).bookingId(99L)
                .flightInstanceCabin(cabin).status(SeatAvailabilityStatus.RESERVED).build();
        when(seatRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(seat));
        when(cabinRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(cabin));

        service.releaseBookingSeats(99L, List.of(100L));

        assertEquals(SeatAvailabilityStatus.AVAILABLE, seat.getStatus());
        assertEquals(0, cabin.getBookedSeats());
        assertEquals(null, seat.getBookingId());
    }

    @Test
    void confirmationRejectsSeatWithoutReservation() {
        FlightInstanceCabin cabin = FlightInstanceCabin.builder()
                .id(40L).totalSeats(1).bookedSeats(0).build();
        SeatInstance seat = SeatInstance.builder().id(100L)
                .flightInstanceCabin(cabin).status(SeatAvailabilityStatus.AVAILABLE).build();
        when(seatRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(seat));

        assertThrows(IllegalStateException.class,
                () -> service.confirmBookingSeats(99L, List.of(100L)));
        assertEquals(SeatAvailabilityStatus.AVAILABLE, seat.getStatus());
        verify(seatRepository, never()).save(seat);
    }

    @Test
    void cancellationReleasesOwnedSeatAndDecrementsCabinOnce() {
        FlightInstanceCabin cabin = FlightInstanceCabin.builder()
                .id(40L).totalSeats(2).bookedSeats(1).build();
        SeatInstance seat = SeatInstance.builder().id(100L).bookingId(99L)
                .flightInstanceCabin(cabin).status(SeatAvailabilityStatus.BOOKED)
                .isBooked(true).isAvailable(false).build();
        when(seatRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(seat));
        when(cabinRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(cabin));

        service.releaseBookingSeats(99L, List.of(100L, 100L));
        service.releaseBookingSeats(99L, List.of(100L));

        assertEquals(0, cabin.getBookedSeats());
        assertEquals(SeatAvailabilityStatus.AVAILABLE, seat.getStatus());
        assertFalse(seat.isBooked());
        assertTrue(seat.isAvailable());
        assertEquals(null, seat.getBookingId());
        verify(seatRepository).save(seat);
    }

    @Test
    void cancellationDoesNotReleaseSeatOwnedByAnotherBooking() {
        FlightInstanceCabin cabin = FlightInstanceCabin.builder()
                .id(40L).totalSeats(2).bookedSeats(1).build();
        SeatInstance seat = SeatInstance.builder().id(100L).bookingId(98L)
                .flightInstanceCabin(cabin).status(SeatAvailabilityStatus.BOOKED).build();
        when(seatRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(seat));

        service.releaseBookingSeats(99L, List.of(100L));

        assertEquals(1, cabin.getBookedSeats());
        verify(seatRepository, never()).save(seat);
    }

    @Test
    void lateConfirmationCannotRebookCancelledSeat() {
        FlightInstanceCabin cabin = FlightInstanceCabin.builder()
                .id(40L).totalSeats(2).bookedSeats(0).build();
        SeatInstance seat = SeatInstance.builder().id(100L)
                .flightInstanceCabin(cabin).status(SeatAvailabilityStatus.AVAILABLE).build();
        when(seatRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(seat));
        when(cancelledRepository.existsById(99L)).thenReturn(true);

        service.confirmBookingSeats(99L, List.of(100L));

        assertEquals(SeatAvailabilityStatus.AVAILABLE, seat.getStatus());
        assertEquals(0, cabin.getBookedSeats());
        verify(seatRepository, never()).save(seat);
    }
}
