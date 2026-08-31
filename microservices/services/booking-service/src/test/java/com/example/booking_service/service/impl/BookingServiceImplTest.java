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
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

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
import com.example.enums.PaymentStatus;
import com.example.payload.dto.PaymentDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PassengerRequest;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.SeatInstanceResponse;
import com.example.payload.response.BookingResponse;

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

        Instant beforeReservation = Instant.now();
        bookingService.createBooking(request, 1L);

        verify(ticketService, never()).generateTicketsForBooking(
                org.mockito.ArgumentMatchers.any(Booking.class));
        verify(paymentClient).initiatePayment(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L));
        InOrder paymentOrder = inOrder(seatClient, paymentClient);
        paymentOrder.verify(seatClient).reserveBookingSeats(99L, List.of(100L));
        paymentOrder.verify(paymentClient).initiatePayment(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
        org.mockito.ArgumentCaptor<Booking> saved = org.mockito.ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Instant deadline = saved.getValue().getHoldExpiresAt();
        assertEquals(true, !deadline.isBefore(beforeReservation.plusSeconds(1800)));
        assertEquals(true, !deadline.isAfter(Instant.now().plusSeconds(1800)));
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
    void getBookingById_usesRecordedPaymentAndFareDetails() throws Exception {
        Booking booking = Booking.builder().id(99L).userId(1L)
                .flightId(10L).flightInstanceId(20L).fareId(30L).build();
        PaymentDto payment = new PaymentDto();
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setAmount(245.75);
        FareResponse fare = FareResponse.builder().name("Standard")
                .baseFare(100.0).taxesAndFees(20.0).airlineFees(5.0).build();
        when(bookingRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.of(booking));
        when(paymentClient.getPaymentsByBookingIds(List.of(99L)))
                .thenReturn(Map.of(99L, payment));
        when(fareIntegrationService.getFaresByIds(List.of(30L)))
                .thenReturn(Map.of(30L, fare));

        BookingResponse response = bookingService.getBookingById(99L, 1L);

        assertEquals(10L, response.getFlightId());
        assertEquals(30L, response.getFareId());
        assertEquals("Standard", response.getFareName());
        assertEquals(100.0, response.getFareBaseFare());
        assertEquals(PaymentStatus.SUCCESS, response.getPaymentStatus());
        assertEquals(245.75, response.getTotalAmount());
    }

    @Test
    void getBookingsByUser_batchesFinancialLookups() {
        Booking first = Booking.builder().id(99L).fareId(30L).build();
        Booking second = Booking.builder().id(100L).fareId(30L).build();
        PaymentDto payment = new PaymentDto();
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setAmount(245.75);
        when(bookingRepository.findByUserId(1L)).thenReturn(List.of(first, second));
        when(paymentClient.getPaymentsByBookingIds(List.of(99L, 100L)))
                .thenReturn(Map.of(99L, payment));
        when(fareIntegrationService.getFaresByIds(List.of(30L)))
                .thenReturn(Map.of(30L, FareResponse.builder().name("Standard").build()));

        List<BookingResponse> responses = bookingService.getBookingsByUser(1L);

        assertEquals(2, responses.size());
        assertEquals(245.75, responses.get(0).getTotalAmount());
        assertEquals(null, responses.get(1).getTotalAmount());
        verify(paymentClient).getPaymentsByBookingIds(List.of(99L, 100L));
        verify(fareIntegrationService).getFaresByIds(List.of(30L));
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
    void cancelBooking_rejectsConfirmedBookingWithoutReleasingSeatsOrTickets() {
        Booking booking = Booking.builder().id(99L).userId(1L)
                .status(BookingStatus.CONFIRMED).seatInstanceIds(List.of(100L)).build();
        when(bookingRepository.findOwnedByIdForUpdate(99L, 1L)).thenReturn(Optional.of(booking));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> bookingService.cancelBooking(99L, 1L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        verifyNoInteractions(ticketService, applicationEventPublisher, paymentClient);
        verify(bookingRepository, never()).save(org.mockito.ArgumentMatchers.any(Booking.class));
    }

    @Test
    void cancelBooking_doesNotReleaseSeatsAgainWhenAlreadyCancelled() throws Exception {
        Booking booking = Booking.builder().id(99L).userId(1L)
                .status(BookingStatus.CANCELLED).build();
        when(bookingRepository.findOwnedByIdForUpdate(99L, 1L)).thenReturn(Optional.of(booking));

        assertEquals(BookingStatus.CANCELLED,
                bookingService.cancelBooking(99L, 1L).getStatus());

        verifyNoInteractions(ticketService, applicationEventPublisher);
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
