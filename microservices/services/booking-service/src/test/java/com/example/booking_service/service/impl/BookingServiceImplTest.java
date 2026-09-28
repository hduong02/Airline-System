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
import java.time.LocalDateTime;

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
import com.example.payload.response.BookingSeatSelectionResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.BookingFlightValidationResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.SeatInstanceResponse;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.CityResponse;

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
            applicationEventPublisher, mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void validationAndPricingRequestsOverlapBeforePersistence() throws Exception {
        var request = bookingRequest(100L);
        request.setAncillaryIds(List.of(60L));
        request.setMealIds(List.of(70L));
        var started = new java.util.concurrent.CountDownLatch(5);
        when(seatClient.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenAnswer(invocation -> concurrentResult(started, cabin(SeatInstanceResponse.builder()
                        .id(100L).flightId(10L).flightInstanceId(20L).flightCabinId(40L).price(10.0).build())));
        when(flightClient.getBookingValidation(20L)).thenAnswer(invocation -> concurrentResult(started,
                new BookingFlightValidationResponse(10L, 20L, 50L)));
        when(fareIntegrationService.getFareById(30L)).thenAnswer(invocation -> concurrentResult(started,
                FareResponse.builder().flightId(10L).cabinClass(CabinClassType.ECONOMY).build()));
        when(ancillaryClient.calculateAncillariesPrice(List.of(60L)))
                .thenAnswer(invocation -> concurrentResult(started, 15.0));
        when(ancillaryClient.calculateMealPrice(List.of(70L)))
                .thenAnswer(invocation -> concurrentResult(started, 5.0));
        when(fareIntegrationService.calculateFareTotal(org.mockito.ArgumentMatchers.any(FareResponse.class)))
                .thenReturn(100.0);
        when(passengerService.createPassenger(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L))).thenReturn(Passenger.builder().build());
        when(bookingRepository.save(org.mockito.ArgumentMatchers.any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);
                    booking.setId(99L);
                    return booking;
                });

        var payment = bookingService.prepareBooking(request, 1L);

        assertEquals(130.0, payment.getAmount());
        verify(flightClient).getBookingValidation(20L);
        verify(flightClient, never()).getFlightById(org.mockito.ArgumentMatchers.anyLong());
        verify(flightClient, never()).getFlightInstanceById(org.mockito.ArgumentMatchers.anyLong());
        verify(bookingRepository).save(org.mockito.ArgumentMatchers.argThat(b -> Long.valueOf(50L).equals(b.getAirlineId())));
        verify(seatClient).reserveBookingSeats(99L, List.of(100L));
    }

    private <T> T concurrentResult(java.util.concurrent.CountDownLatch started, T result)
            throws InterruptedException {
        verifyNoInteractions(bookingRepository, passengerService, paymentClient);
        started.countDown();
        org.junit.jupiter.api.Assertions.assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS),
                "All five independent lookups must start before any lookup completes");
        return result;
    }

    @Test
    void resumeCheckoutUsesExistingPaymentAmountAndGateway() throws Exception {
        Booking booking = Booking.builder().id(7L).userId(1L).bookingReference("BK7")
                .status(BookingStatus.PENDING).holdExpiresAt(Instant.now().plusSeconds(600)).build();
        when(bookingRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(booking));
        PaymentDto payment = new PaymentDto();
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(26.0);
        payment.setGateway(com.example.enums.PaymentGateway.STRIPE);
        when(paymentClient.getPaymentsByBookingIds(List.of(7L))).thenReturn(Map.of(7L, payment));
        var response = com.example.payload.response.PaymentInitiateResponse.builder()
                .success(true).checkoutUrl("https://checkout.stripe.com/existing").build();
        when(paymentClient.initiatePayment(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L))).thenReturn(response);

        assertEquals(response, bookingService.resumeCheckout(7L, 1L));
        var request = org.mockito.ArgumentCaptor.forClass(com.example.payload.request.PaymentInitiateRequest.class);
        verify(paymentClient).initiatePayment(request.capture(), org.mockito.ArgumentMatchers.eq(1L));
        assertEquals(7L, request.getValue().getBookingId());
        assertEquals(1L, request.getValue().getUserId());
        assertEquals(26.0, request.getValue().getAmount());
        assertEquals(com.example.enums.PaymentGateway.STRIPE, request.getValue().getGateway());
        verifyNoInteractions(seatClient, passengerService, ticketService);
    }

    @Test
    void resumeCheckoutRejectsForeignBookingBeforeCallingPaymentService() {
        when(bookingRepository.findByIdAndUserId(7L, 2L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> bookingService.resumeCheckout(7L, 2L)).getStatusCode());
        verifyNoInteractions(paymentClient);
    }

    @Test
    void resumeCheckoutRejectsCancelledAndExpiredBookings() {
        Booking booking = Booking.builder().id(7L).status(BookingStatus.CANCELLED)
                .holdExpiresAt(Instant.now().plusSeconds(600)).build();
        when(bookingRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(booking));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> bookingService.resumeCheckout(7L, 1L)).getStatusCode());
        booking.setStatus(BookingStatus.PENDING);
        booking.setHoldExpiresAt(Instant.now().minusSeconds(1));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> bookingService.resumeCheckout(7L, 1L)).getStatusCode());
        verifyNoInteractions(paymentClient);
    }

    @Test
    void resumeCheckoutRejectsSettledOrMissingPayment() {
        Booking booking = Booking.builder().id(7L).status(BookingStatus.PENDING)
                .holdExpiresAt(Instant.now().plusSeconds(600)).build();
        when(bookingRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(booking));
        PaymentDto payment = new PaymentDto();
        payment.setStatus(PaymentStatus.SUCCESS);
        when(paymentClient.getPaymentsByBookingIds(List.of(7L))).thenReturn(Map.of(7L, payment));
        assertThrows(ResponseStatusException.class, () -> bookingService.resumeCheckout(7L, 1L));
        when(paymentClient.getPaymentsByBookingIds(List.of(7L))).thenReturn(Map.of());
        assertThrows(ResponseStatusException.class, () -> bookingService.resumeCheckout(7L, 1L));
        verify(paymentClient, never()).initiatePayment(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createBookingLeavesTicketsUnissuedWhilePaymentIsPending() throws Exception {
        BookingRequest request = bookingRequest(100L);
        request.setAncillaryIds(List.of());
        request.setMealIds(List.of());
        when(seatClient.getBookingSeatSelection(org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(CabinClassType.ECONOMY), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(cabin(SeatInstanceResponse.builder().id(100L)
                        .flightId(10L).flightInstanceId(20L).flightCabinId(40L).build()));
        when(flightClient.getBookingValidation(20L))
                .thenReturn(new BookingFlightValidationResponse(10L, 20L, 50L));
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
                .passengers(List.of(PassengerRequest.builder().seatInstanceId(100L).build(),
                        PassengerRequest.builder().seatInstanceId(101L).build(),
                        PassengerRequest.builder().seatInstanceId(102L).build()))
                .build();
        when(seatClient.getBookingSeatSelection(org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(CabinClassType.ECONOMY), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(BookingSeatSelectionResponse.builder().availableSeats(2).build());

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(request, 1L));

        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient, airlineClient);
    }

    @Test
    void createBooking_rejectsFlightInstanceFromAnotherFlight() {
        BookingRequest request = bookingRequest(100L);
        when(seatClient.getBookingSeatSelection(org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(CabinClassType.ECONOMY), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(cabin());
        when(flightClient.getBookingValidation(20L))
                .thenReturn(new BookingFlightValidationResponse(11L, 20L, 50L));

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request, 1L));
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void validationRejectsMissingOrInconsistentFlightIdentityBeforeWriting() {
        when(seatClient.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenReturn(cabin());
        for (BookingFlightValidationResponse response : java.util.Arrays.asList(null,
                new BookingFlightValidationResponse(10L, 21L, 50L),
                new BookingFlightValidationResponse(10L, 20L, null))) {
            when(flightClient.getBookingValidation(20L)).thenReturn(response);
            assertThrows(IllegalArgumentException.class, () -> bookingService.validateBooking(bookingRequest(100L)));
        }
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void validationPropagatesFlightLookupFailureWithoutFetchingDisplayDetails() {
        when(seatClient.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenReturn(cabin());
        var failure = new ResponseStatusException(HttpStatus.NOT_FOUND, "Flight instance not found");
        when(flightClient.getBookingValidation(20L)).thenThrow(failure);
        assertEquals(failure, assertThrows(ResponseStatusException.class,
                () -> bookingService.validateBooking(bookingRequest(100L))));
        verify(flightClient, never()).getFlightById(org.mockito.ArgumentMatchers.anyLong());
        verify(flightClient, never()).getFlightInstanceById(org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void createBooking_rejectsFareFromAnotherCabin() {
        BookingRequest request = bookingRequest(100L);
        when(seatClient.getBookingSeatSelection(org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(CabinClassType.ECONOMY), org.mockito.ArgumentMatchers.anyList()))
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
        when(seatClient.getBookingSeatSelection(org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(CabinClassType.ECONOMY), org.mockito.ArgumentMatchers.anyList()))
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
        when(seatClient.getBookingSeatSelection(org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(CabinClassType.ECONOMY), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(cabin());
        validFlight();
        validFare();

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request, 1L));
        verifyNoInteractions(bookingRepository, passengerService, ticketService, paymentClient);
    }

    @Test
    void compactSelectionRejectsMissingSeatsAndForeignFlightOrCabinBeforeWriting() {
        validFlight();
        validFare();
        var selections = List.of(cabin(),
                cabin(SeatInstanceResponse.builder().id(100L).flightId(11L)
                        .flightInstanceId(20L).flightCabinId(40L).build()),
                cabin(SeatInstanceResponse.builder().id(100L).flightId(10L)
                        .flightInstanceId(20L).flightCabinId(41L).build()));
        for (var selection : selections) {
            when(seatClient.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                    .thenReturn(selection);
            assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(bookingRequest(100L), 1L));
        }
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
    void getBookingByIdIncludesFlightDetailsUsedByTicket() throws Exception {
        Booking booking = Booking.builder().id(99L).userId(1L)
                .flightId(10L).flightInstanceId(20L).build();
        when(bookingRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.of(booking));
        AirportResponse departure = AirportResponse.builder().name("Noi Bai")
                .detailedName("Noi Bai (HAN)").iataCode("HAN")
                .city(CityResponse.builder().name("Hanoi").build()).build();
        AirportResponse arrival = AirportResponse.builder().name("Tan Son Nhat")
                .detailedName("Tan Son Nhat (SGN)").iataCode("SGN")
                .city(CityResponse.builder().name("Ho Chi Minh City").build()).build();
        when(flightClient.getFlightById(10L)).thenReturn(FlightResponse.builder()
                .id(10L).flightNumber("VN123").departureAirport(departure).arrivalAirport(arrival).build());
        LocalDateTime departureTime = LocalDateTime.of(2026, 10, 20, 9, 30);
        LocalDateTime arrivalTime = departureTime.plusHours(2);
        when(flightClient.getFlightInstanceById(20L)).thenReturn(FlightInstanceResponse.builder()
                .id(20L).flightId(10L).departureDateTime(departureTime).arrivalDateTime(arrivalTime)
                .formattedDuration("2h 0m").build());

        BookingResponse response = bookingService.getBookingById(99L, 1L);

        assertEquals("VN123", response.getFlightNumber());
        assertEquals("Hanoi - Ho Chi Minh City", response.getFlightName());
        assertEquals("Noi Bai (HAN)", response.getDepartureAirport());
        assertEquals("Tan Son Nhat", response.getArrivalAirport());
        assertEquals(departureTime, response.getDepartureTime());
        assertEquals(arrivalTime, response.getArrivalTime());
        assertEquals("2h 0m", response.getFlightDuration());
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
    void bookingHistoryKeepsInstanceTimesSeparateAndReusesSharedFlightLookup() {
        Booking first = Booking.builder().id(99L).flightId(10L).flightInstanceId(20L).build();
        Booking second = Booking.builder().id(100L).flightId(10L).flightInstanceId(21L).build();
        Booking third = Booking.builder().id(101L).flightId(10L).flightInstanceId(20L).build();
        when(bookingRepository.findByUserId(1L)).thenReturn(List.of(first, second, third));
        when(flightClient.getFlightById(10L)).thenReturn(FlightResponse.builder().flightNumber("VN123").build());
        LocalDateTime firstDeparture = LocalDateTime.of(2026, 10, 20, 9, 30);
        LocalDateTime secondDeparture = firstDeparture.plusDays(1);
        when(flightClient.getFlightInstanceById(20L)).thenReturn(FlightInstanceResponse.builder()
                .departureDateTime(firstDeparture).build());
        when(flightClient.getFlightInstanceById(21L)).thenReturn(FlightInstanceResponse.builder()
                .departureDateTime(secondDeparture).build());

        List<BookingResponse> responses = bookingService.getBookingsByUser(1L);

        assertEquals("VN123", responses.get(0).getFlightNumber());
        assertEquals(firstDeparture, responses.get(0).getDepartureTime());
        assertEquals(secondDeparture, responses.get(1).getDepartureTime());
        assertEquals(firstDeparture, responses.get(2).getDepartureTime());
        verify(flightClient).getFlightById(10L);
        verify(flightClient).getFlightInstanceById(20L);
        verify(flightClient).getFlightInstanceById(21L);
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

    private BookingSeatSelectionResponse cabin(SeatInstanceResponse... seats) {
        return BookingSeatSelectionResponse.builder().id(40L).flightInstanceId(20L)
                .cabinClassType(CabinClassType.ECONOMY).availableSeats(2)
                .seats(java.util.Arrays.stream(seats).map(s -> new BookingSeatSelectionResponse.Seat(
                        s.getId(), s.getFlightId(), s.getFlightInstanceId(), s.getFlightCabinId(), s.getPrice())).toList()).build();
    }

    private void validFlight() {
        when(flightClient.getBookingValidation(20L))
                .thenReturn(new BookingFlightValidationResponse(10L, 20L, 50L));
    }

    private void validFare() {
        when(fareIntegrationService.getFareById(30L)).thenReturn(FareResponse.builder()
                .flightId(10L).cabinClass(CabinClassType.ECONOMY).build());
    }
}
