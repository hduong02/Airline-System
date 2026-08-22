package com.example.booking_service.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.AncillaryClient;
import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.client.SeatClient;
import com.example.event.BookingCancelledEvent;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.service.PassengerService;
import com.example.booking_service.service.TicketService;
import com.example.booking_service.service.integration.FareIntegrationService;
import com.example.enums.CabinClassType;
import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PassengerRequest;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.SeatInstanceResponse;

class BookingServiceImplTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final PassengerService passengerService = mock(PassengerService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final FlightClient flightClient = mock(FlightClient.class);
    private final SeatClient seatClient = mock(SeatClient.class);
    private final AncillaryClient ancillaryClient = mock(AncillaryClient.class);
    private final FareIntegrationService fareIntegrationService = mock(FareIntegrationService.class);
    private final PaymentClient paymentClient = mock(PaymentClient.class);
    private final AirlineClient airlineClient = mock(AirlineClient.class);
    private final ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);

    private final BookingServiceImpl bookingService = new BookingServiceImpl(
            bookingRepository, passengerService, ticketService, flightClient, seatClient,
            ancillaryClient, fareIntegrationService, paymentClient, airlineClient,
            applicationEventPublisher);

    @Test
    void createBookingLeavesTicketsUnissuedWhilePaymentIsPending() throws Exception {
        BookingRequest request = bookingRequest(100L);
        request.setAncillaryIds(List.of());
        request.setMealIds(List.of());
        when(seatClient.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(cabin(SeatInstanceResponse.builder().id(100L)
                        .flightId(10L).flightInstanceId(20L).flightCabinId(40L).build()));
        when(flightClient.getFlightById(10L)).thenReturn(FlightResponse.builder()
                .id(10L).airline(AirlineResponse.builder().id(50L).build()).build());
        when(flightClient.getFlightInstanceById(20L))
                .thenReturn(FlightInstanceResponse.builder().flightId(10L).build());
        validFare();
        when(passengerService.createPassenger(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L))).thenReturn(Passenger.builder().build());
        when(bookingRepository.save(org.mockito.ArgumentMatchers.any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);
                    booking.setId(99L);
                    return booking;
                });
        when(fareIntegrationService.calculateFareTotal(
                org.mockito.ArgumentMatchers.any(FareResponse.class)))
                .thenReturn(100.0);
        when(seatClient.calculateSeatPrice(List.of(100L))).thenReturn(0.0);
        when(ancillaryClient.calculateAncillariesPrice(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0.0);
        when(ancillaryClient.calculateMealPrice(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0.0);

        bookingService.createBooking(request, 1L);

        verify(ticketService, never()).generateTicketsForBooking(
                org.mockito.ArgumentMatchers.any(Booking.class));
        verify(paymentClient).initiatePayment(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L));
        InOrder paymentOrder = inOrder(seatClient, paymentClient);
        paymentOrder.verify(seatClient).reserveBookingSeats(99L, List.of(100L));
        paymentOrder.verify(paymentClient).initiatePayment(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
    }

    @Test
    void createBooking_rejectsRequestWhenPassengersExceedCabinAvailability() {
        BookingRequest request = BookingRequest.builder()
                .flightId(10L)
                .flightInstanceId(20L)
                .cabinClass(CabinClassType.ECONOMY)
                .fareId(30L)
                .passengers(List.of(PassengerRequest.builder().build(),
                        PassengerRequest.builder().build(), PassengerRequest.builder().build()))
                .build();
        when(seatClient.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(FlightInstanceCabinResponse.builder().availableSeats(2).build());

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(request, 1L));

        verifyNoInteractions(bookingRepository, passengerService, ticketService, flightClient,
                ancillaryClient, fareIntegrationService, paymentClient, airlineClient);
    }

    @Test
    void createBooking_rejectsFlightInstanceFromAnotherFlight() {
        BookingRequest request = bookingRequest(100L);
        when(seatClient.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(cabin());
        when(flightClient.getFlightById(10L)).thenReturn(FlightResponse.builder().id(10L).build());
        when(flightClient.getFlightInstanceById(20L))
                .thenReturn(FlightInstanceResponse.builder().flightId(11L).build());

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request, 1L));
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void createBooking_rejectsFareFromAnotherCabin() {
        BookingRequest request = bookingRequest(100L);
        when(seatClient.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(cabin());
        validFlight();
        when(fareIntegrationService.getFareById(30L)).thenReturn(FareResponse.builder()
                .flightId(10L).cabinClass(CabinClassType.BUSINESS).build());

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request, 1L));
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void createBooking_rejectsSeatFromAnotherFlightInstance() {
        BookingRequest request = bookingRequest(100L);
        when(seatClient.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(cabin(SeatInstanceResponse.builder().id(100L)
                        .flightId(10L).flightInstanceId(21L).flightCabinId(40L).build()));
        validFlight();
        validFare();

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request, 1L));
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void createBooking_rejectsDuplicateSeatSelection() {
        BookingRequest request = bookingRequest(100L);
        request.setPassengers(List.of(PassengerRequest.builder().seatInstanceId(100L).build(),
                PassengerRequest.builder().seatInstanceId(100L).build()));
        when(seatClient.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(cabin());
        validFlight();
        validFare();

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request, 1L));
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void getBookingById_returnsNotFoundForAnotherUser() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> bookingService.getBookingById(99L, 2L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(bookingRepository).findByIdAndUserId(99L, 2L);
    }

    @Test
    void cancelBooking_doesNotChangeAnotherUsersBooking() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> bookingService.cancelBooking(99L, 2L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(bookingRepository).findOwnedByIdForUpdate(99L, 2L);
        verify(bookingRepository, never()).save(org.mockito.ArgumentMatchers.any(Booking.class));
    }

    @Test
    void deleteBooking_doesNotDeleteAnotherUsersBooking() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> bookingService.deleteBooking(99L, 2L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(bookingRepository, never()).delete(org.mockito.ArgumentMatchers.any(Booking.class));
    }

    @Test
    void deleteBooking_requiresCancellationFirst() {
        Booking booking = Booking.builder().id(99L).userId(1L)
                .status(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.of(booking));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> bookingService.deleteBooking(99L, 1L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(bookingRepository, never()).delete(booking);
    }

    @Test
    void ownerCanReadCancelAndDeleteBooking() throws Exception {
        Booking booking = Booking.builder().id(99L).userId(1L)
                .status(BookingStatus.PENDING).build();
        when(bookingRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.findOwnedByIdForUpdate(99L, 1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(booking)).thenReturn(booking);

        assertEquals(99L, bookingService.getBookingById(99L, 1L).getId());
        assertEquals(BookingStatus.CANCELLED,
                bookingService.cancelBooking(99L, 1L).getStatus());
        bookingService.deleteBooking(99L, 1L);

        verify(bookingRepository).save(booking);
        verify(bookingRepository).delete(booking);
        verify(ticketService).cancelTicketsForBooking(99L);
        verify(applicationEventPublisher).publishEvent(
                new BookingCancelledEvent(99L, List.of()));
    }

    private BookingRequest bookingRequest(Long seatId) {
        return BookingRequest.builder().flightId(10L).flightInstanceId(20L)
                .cabinClass(CabinClassType.ECONOMY).fareId(30L)
                .passengers(List.of(PassengerRequest.builder().seatInstanceId(seatId).build()))
                .build();
    }

    private FlightInstanceCabinResponse cabin(SeatInstanceResponse... seats) {
        return FlightInstanceCabinResponse.builder().id(40L).flightInstanceId(20L)
                .cabinClassType(CabinClassType.ECONOMY).availableSeats(2)
                .seats(List.of(seats)).build();
    }

    private void validFlight() {
        when(flightClient.getFlightById(10L)).thenReturn(FlightResponse.builder().id(10L).build());
        when(flightClient.getFlightInstanceById(20L))
                .thenReturn(FlightInstanceResponse.builder().flightId(10L).build());
    }

    private void validFare() {
        when(fareIntegrationService.getFareById(30L)).thenReturn(FareResponse.builder()
                .flightId(10L).cabinClass(CabinClassType.ECONOMY).build());
    }
}
