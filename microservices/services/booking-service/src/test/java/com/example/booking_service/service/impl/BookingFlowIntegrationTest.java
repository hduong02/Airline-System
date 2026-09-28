package com.example.booking_service.service.impl;

import com.example.booking_service.client.*;
import com.example.booking_service.event.listener.PaymentEventListener;
import com.example.booking_service.event.publisher.BookingEventProducer;
import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.*;
import com.example.booking_service.service.BookingService;
import com.example.booking_service.service.IdempotentBookingService;
import com.example.booking_service.service.BookingReservationService;
import com.example.booking_service.service.integration.FareIntegrationService;
import com.example.embeddable.ContactInfo;
import com.example.enums.*;
import com.example.event.PaymentCompletedEvent;
import com.example.payload.dto.PaymentDto;
import com.example.payload.dto.UserDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PassengerRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real booking services, event handler, and JPA; only remote boundaries are mocked. */
@SpringJUnitConfig(BookingFlowIntegrationTest.Config.class)
class BookingFlowIntegrationTest {
    @Autowired private BookingService bookings;
    @Autowired private IdempotentBookingService idempotentBookings;
    @Autowired private BookingAttemptRepository attempts;
    @Autowired private PaymentEventListener paymentEvents;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PassengerRepository passengerRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private FlightClient flights;
    @Autowired private SeatClient seats;
    @Autowired private AncillaryClient ancillaries;
    @Autowired private PricingClient pricing;
    @Autowired private PaymentClient payments;
    @Autowired private UserClient users;
    @Autowired private BookingEventProducer outgoingEvents;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() throws Exception {
        reset(flights, seats, ancillaries, pricing, payments, users, outgoingEvents);
        attempts.deleteAll();
        ticketRepository.deleteAll();
        bookingRepository.deleteAll();
        passengerRepository.deleteAll();

        when(flights.getBookingValidation(20L)).thenReturn(
                new BookingFlightValidationResponse(10L, 20L, 50L));
        when(flights.getFlightById(10L)).thenReturn(FlightResponse.builder().id(10L).flightNumber("VN123")
                .departureAirport(AirportResponse.builder().name("Noi Bai").detailedName("Noi Bai (HAN)")
                        .city(CityResponse.builder().name("Hanoi").build()).build())
                .arrivalAirport(AirportResponse.builder().name("Tan Son Nhat")
                        .city(CityResponse.builder().name("Ho Chi Minh City").build()).build())
                .airline(AirlineResponse.builder().id(50L).build()).build());
        when(flights.getFlightInstanceById(20L)).thenReturn(FlightInstanceResponse.builder()
                .id(20L).flightId(10L)
                .departureDateTime(java.time.LocalDateTime.of(2026, 10, 20, 9, 30))
                .arrivalDateTime(java.time.LocalDateTime.of(2026, 10, 20, 11, 30)).build());
        when(seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenReturn(BookingSeatSelectionResponse.builder().id(40L).flightInstanceId(20L)
                        .cabinClassType(CabinClassType.ECONOMY).availableSeats(1)
                        .seats(List.of(new BookingSeatSelectionResponse.Seat(100L, 10L, 20L, 40L, 10.0))).build());
        FareResponse fare = FareResponse.builder().id(30L).flightId(10L)
                .cabinClass(CabinClassType.ECONOMY).baseFare(100.0)
                .taxesAndFees(20.0).airlineFees(5.0).build();
        when(pricing.getFareById(30L)).thenReturn(fare);
        when(pricing.getFaresByIds(List.of(30L))).thenReturn(Map.of(30L, fare));
        when(seats.getSelectedSeats(List.of(100L))).thenReturn(List.of(
                SeatInstanceResponse.builder().id(100L).seatNumber("12A").flightId(10L)
                        .flightInstanceId(20L).flightCabinId(40L).build()));
        when(ancillaries.calculateAncillariesPrice(List.of(60L))).thenReturn(15.0);
        when(ancillaries.calculateMealPrice(List.of(70L))).thenReturn(5.0);
        when(users.getUserById(1L)).thenReturn(UserDto.builder().id(1L).build());
        when(payments.initiatePayment(any(), eq(1L))).thenReturn(PaymentInitiateResponse.builder()
                .paymentId(9L).gateway(PaymentGateway.STRIPE).amount(155.0).success(true)
                .checkoutUrl("https://checkout.stripe.com/test").stripeCheckoutUrl("cs_test_fixture").build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void remotePricingCompletesOutsidePersistenceTransaction(boolean idempotent) throws Exception {
        when(ancillaries.calculateAncillariesPrice(List.of(60L))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, bookingRepository.count());
            assertEquals(0, passengerRepository.count());
            return 15.0;
        });
        when(ancillaries.calculateMealPrice(List.of(70L))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, bookingRepository.count());
            assertEquals(0, passengerRepository.count());
            return 5.0;
        });
        doAnswer(invocation -> {
            assertEquals(!idempotent, TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(seats).reserveBookingSeats(anyLong(), anyList());

        if (idempotent) {
            idempotentBookings.createBooking(request(), 1L, UUID.randomUUID().toString());
        } else {
            bookings.createBooking(request(), 1L);
        }

        assertEquals(1, bookingRepository.count());
        var initiation = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        verify(payments).initiatePayment(initiation.capture(), eq(1L));
        assertEquals(155.0, initiation.getValue().getAmount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pricingFailurePreventsPersistenceAndPreservesOriginalException(boolean mealFailure) {
        var failure = new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Pricing unavailable");
        if (mealFailure) {
            when(ancillaries.calculateMealPrice(List.of(70L))).thenThrow(failure);
        } else {
            when(ancillaries.calculateAncillariesPrice(List.of(60L))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(ResponseStatusException.class,
                () -> bookings.createBooking(request(), 1L)));

        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
        verify(seats, never()).reserveBookingSeats(anyLong(), anyList());
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @MethodSource("optionalSelections")
    void pricesOnlySelectedExtrasAndMeals(List<Long> ancillaryIds, List<Long> mealIds, double expectedTotal)
            throws Exception {
        var request = request();
        request.setAncillaryIds(ancillaryIds);
        request.setMealIds(mealIds);

        bookings.createBooking(request, 1L);

        if (ancillaryIds == null || ancillaryIds.isEmpty()) {
            verify(ancillaries, never()).calculateAncillariesPrice(any());
        } else {
            verify(ancillaries).calculateAncillariesPrice(ancillaryIds);
        }
        if (mealIds == null || mealIds.isEmpty()) {
            verify(ancillaries, never()).calculateMealPrice(any());
        } else {
            verify(ancillaries).calculateMealPrice(mealIds);
        }
        verifyNoMoreInteractions(ancillaries);
        var initiation = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        verify(payments).initiatePayment(initiation.capture(), eq(1L));
        assertEquals(expectedTotal, initiation.getValue().getAmount());
    }

    static Stream<Arguments> optionalSelections() {
        return Stream.of(
                Arguments.of(null, null, 135.0),
                Arguments.of(List.of(), List.of(), 135.0),
                Arguments.of(null, List.of(), 135.0),
                Arguments.of(List.of(), null, 135.0),
                Arguments.of(List.of(60L), List.of(), 150.0),
                Arguments.of(List.of(60L), null, 150.0),
                Arguments.of(List.of(), List.of(70L), 140.0),
                Arguments.of(null, List.of(70L), 140.0),
                Arguments.of(List.of(60L), List.of(70L), 155.0));
    }

    @Test
    void bookingValidationUsesOneCompactSelectionAndNoFullCabinOrPriceCall() throws Exception {
        bookings.createBooking(request(), 1L);

        verify(seats).getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L));
        verify(seats, never()).getFlightInstanceCabin(any(), any());
        verify(seats, never()).calculateSeatPrice(anyList());
        var initiation = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        verify(payments).initiatePayment(initiation.capture(), eq(1L));
        assertEquals(155.0, initiation.getValue().getAmount());
    }

    @Test
    void compactSelectionPricesMultiplePassengersAndNullPremiumWithoutAnotherLookup() throws Exception {
        var request = request();
        request.setAncillaryIds(List.of());
        request.setMealIds(List.of());
        request.setPassengers(List.of(request.getPassengers().getFirst(), PassengerRequest.builder()
                .firstName("Second").lastName("Traveler").dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender(Gender.MALE).seatInstanceId(101L).build()));
        when(seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L, 101L)))
                .thenReturn(BookingSeatSelectionResponse.builder().id(40L).flightInstanceId(20L)
                        .cabinClassType(CabinClassType.ECONOMY).availableSeats(2)
                        .seats(List.of(new BookingSeatSelectionResponse.Seat(100L, 10L, 20L, 40L, 10.0),
                                new BookingSeatSelectionResponse.Seat(101L, 10L, 20L, 40L, null))).build());

        bookings.createBooking(request, 1L);

        var initiation = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        verify(payments).initiatePayment(initiation.capture(), eq(1L));
        assertEquals(260.0, initiation.getValue().getAmount());
        verify(seats).reserveBookingSeats(onlyBookingId(), List.of(100L, 101L));
        verify(seats, never()).calculateSeatPrice(anyList());
    }

    @Test
    void confirmationStatusUsesOneScalarQueryAndNoRemoteCallsBeforeAndAfterPayment() throws Exception {
        bookings.createBooking(request(), 1L);
        Long id = onlyBookingId();
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        clearInvocations(flights, seats, ancillaries, pricing, payments, users, outgoingEvents);
        statistics.clear();

        var pending = bookings.getConfirmationStatus(id, 1L);
        assertEquals(id, pending.bookingId());
        assertEquals(BookingStatus.PENDING, pending.status());
        assertFalse(pending.paymentReceived());
        assertFalse(pending.ticketIssued());
        assertEquals(0, pending.ticketCount());
        assertFalse(pending.confirmed());
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(0, statistics.getCollectionLoadCount());
        verifyNoInteractions(flights, seats, ancillaries, pricing, payments, users, outgoingEvents);

        completePayment(id);
        clearInvocations(flights, seats, ancillaries, pricing, payments, users, outgoingEvents);
        statistics.clear();
        var confirmed = bookings.getConfirmationStatus(id, 1L);
        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertTrue(confirmed.paymentReceived());
        assertTrue(confirmed.ticketIssued());
        assertEquals(1, confirmed.ticketCount());
        assertTrue(confirmed.confirmed());
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(0, statistics.getCollectionLoadCount());
        verifyNoInteractions(flights, seats, ancillaries, pricing, payments, users, outgoingEvents);
    }

    @Test
    void confirmationStatusHidesOtherOwnersAndDoesNotConfirmCancelledOrIncompleteTickets() throws Exception {
        bookings.createBooking(request(), 1L);
        Long id = onlyBookingId();
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> bookings.getConfirmationStatus(id, 2L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> bookings.getConfirmationStatus(Long.MAX_VALUE, 1L)).getStatusCode().value());
        completePayment(id);
        var booking = bookingRepository.findById(id).orElseThrow();
        booking.setTicketIssued(false);
        bookingRepository.save(booking);
        assertFalse(bookings.getConfirmationStatus(id, 1L).confirmed());
        booking.setTicketIssued(true);
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);
        var cancelled = bookings.getConfirmationStatus(id, 1L);
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.confirmed());
    }

    @Test
    void bookingDetailsFetchOnlySelectedSeats() throws Exception {
        bookings.createBooking(request(), 1L);
        Long id = onlyBookingId();
        clearInvocations(seats);

        BookingResponse response = bookings.getBookingById(id, 1L);

        assertEquals(List.of(100L), response.getSeatInstances().stream()
                .map(SeatInstanceResponse::getId).toList());
        verify(seats).getSelectedSeats(List.of(100L));
        verify(seats, never()).getFlightInstanceCabin(any(), any());
    }

    @Test
    void bookingIsPersistedThenPaymentConfirmsTicketAndHistory() throws Exception {
        PaymentInitiateResponse checkout = bookings.createBooking(request(), 1L);
        Long bookingId = onlyBookingId();
        BookingResponse pending = bookings.getBookingById(bookingId, 1L);
        assertTrue(checkout.getSuccess());
        assertEquals(BookingStatus.PENDING, pending.getStatus());
        assertEquals(1, pending.getPassengers().size());
        assertTrue(pending.getTickets().isEmpty());
        assertEquals(1, passengerRepository.count());
        assertTrue(bookingRepository.findById(bookingId).orElseThrow().getHoldExpiresAt().isAfter(Instant.now()));

        ArgumentCaptor<PaymentInitiateRequest> initiation = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        var order = inOrder(seats, payments);
        order.verify(seats).reserveBookingSeats(bookingId, List.of(100L));
        order.verify(payments).initiatePayment(initiation.capture(), eq(1L));
        assertEquals(bookingId, initiation.getValue().getBookingId());
        assertEquals(1L, initiation.getValue().getUserId());
        assertEquals(155.0, initiation.getValue().getAmount());

        completePayment(bookingId);
        BookingResponse confirmed = bookings.getBookingById(bookingId, 1L);
        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(PaymentStatus.SUCCESS, confirmed.getPaymentStatus());
        assertEquals(155.0, confirmed.getTotalAmount());
        assertEquals(pending.getBookingReference(), confirmed.getBookingReference());
        assertEquals(1, confirmed.getTickets().size());
        assertEquals("VN123", confirmed.getFlightNumber());
        assertEquals("Noi Bai (HAN)", confirmed.getDepartureAirport());
        assertEquals("Tan Son Nhat", confirmed.getArrivalAirport());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 9, 30), confirmed.getDepartureTime());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 11, 30), confirmed.getArrivalTime());
        assertEquals(List.of("12A"), confirmed.getSeatInstances().stream()
                .map(SeatInstanceResponse::getSeatNumber).toList());
        assertEquals(100L, confirmed.getPassengers().getFirst().getSeatInstanceId());
        assertEquals("12A", bookings.getBookingsByUser(1L).getFirst().getSeatInstances().getFirst().getSeatNumber());
        assertEquals(TicketStatus.BOOKED, confirmed.getTickets().getFirst().getStatus());
        assertFalse(confirmed.getTickets().getFirst().getTicketNumber().isBlank());
        assertEquals(9L, bookingRepository.findById(bookingId).orElseThrow().getPaymentId());
        assertEquals(bookingId, bookings.getBookingsByUser(1L).getFirst().getId());
        assertTrue(bookings.getBookingsByUser(2L).isEmpty());
        assertThrows(ResponseStatusException.class, () -> bookings.getBookingById(bookingId, 2L));
        verify(outgoingEvents).sendBookingConfirmed(argThat(booking -> booking.getId().equals(bookingId)),
                any(PaymentCompletedEvent.class), any(), any(), any());
    }

    @Test
    void duplicatePaymentEventDoesNotIssueAnotherTicketOrConfirmation() throws Exception {
        bookings.createBooking(request(), 1L);
        Long bookingId = onlyBookingId();
        completePayment(bookingId);
        completePayment(bookingId);
        assertEquals(1, ticketRepository.count());
        assertEquals(1, bookings.getBookingById(bookingId, 1L).getTickets().size());
        verify(outgoingEvents, times(1)).sendBookingConfirmed(any(), any(), any(), any(), any());
    }

    @Test
    void paymentInitiationFailureRollsBackBookingAndPassengerAndReleasesSeat() throws Exception {
        when(payments.initiatePayment(any(), eq(1L))).thenThrow(new IllegalStateException("Payment unavailable"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bookings.createBooking(request(), 1L));
        assertEquals("Payment unavailable", failure.getMessage());
        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
        assertEquals(0, ticketRepository.count());
        ArgumentCaptor<Long> reservedId = ArgumentCaptor.forClass(Long.class);
        verify(seats).reserveBookingSeats(reservedId.capture(), eq(List.of(100L)));
        verify(seats).releaseBookingSeats(reservedId.getValue(), List.of(100L));
        verifyNoInteractions(outgoingEvents);
    }

    @Test
    void invalidSeatSelectionDoesNotPersistDataOrInitiatePayment() {
        BookingRequest invalid = request();
        invalid.getPassengers().getFirst().setSeatInstanceId(999L);
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(invalid, 1L));
        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
        verify(seats, never()).reserveBookingSeats(anyLong(), anyList());
        verifyNoInteractions(payments, outgoingEvents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "prepare", "idempotent"})
    void validatesBeforeTransactionAndReservesOutsideTransactionForIdempotentCheckout(String path) throws Exception {
        var flight = flights.getBookingValidation(20L);
        var fare = pricing.getFareById(30L);
        var selection = seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L));
        when(flights.getBookingValidation(20L)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return flight;
        });
        when(pricing.getFareById(30L)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return fare;
        });
        when(seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return selection;
                });
        doAnswer(invocation -> {
            assertEquals(!path.equals("idempotent"), TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, bookingRepository.count());
            assertEquals(1, passengerRepository.count());
            return null;
        }).when(seats).reserveBookingSeats(anyLong(), eq(List.of(100L)));

        switch (path) {
            case "legacy" -> bookings.createBooking(request(), 1L);
            case "prepare" -> bookings.prepareBooking(request(), 1L);
            case "idempotent" -> idempotentBookings.createBooking(request(), 1L, UUID.randomUUID().toString());
            default -> fail("Unknown path");
        }
        assertEquals(1, bookingRepository.count());
        verify(seats).reserveBookingSeats(anyLong(), eq(List.of(100L)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void seatConflictRetainsCancelledAttemptForIdempotentRecoveryAndRollsBackLegacy(boolean idempotent) {
        var conflict = feign.FeignException.errorStatus("reserveBookingSeats",
                feign.Response.builder().status(409).reason("Seat taken")
                        .request(feign.Request.create(feign.Request.HttpMethod.POST, "/reserve",
                                Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null)).build());
        doThrow(conflict).when(seats).reserveBookingSeats(anyLong(), anyList());
        var failure = assertThrows(ResponseStatusException.class, () -> {
            if (idempotent) {
                idempotentBookings.createBooking(request(), 1L, UUID.randomUUID().toString());
            } else {
                bookings.createBooking(request(), 1L);
            }
        });
        assertEquals(409, failure.getStatusCode().value());
        assertEquals(idempotent ? 1 : 0, bookingRepository.count());
        assertEquals(idempotent ? 1 : 0, passengerRepository.count());
        assertEquals(idempotent ? 1 : 0, attempts.count());
        if (idempotent) {
            assertEquals(BookingStatus.CANCELLED, bookingRepository.findAll().getFirst().getStatus());
            assertEquals(com.example.booking_service.model.BookingAttempt.ReservationState.RELEASE_PENDING,
                    attempts.findAll().getFirst().getReservationState());
        }
        verifyNoInteractions(payments);
    }

    @Test
    void newCreationUsesSixTransactions() throws Exception {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        long before = statistics.getTransactionCount();

        var response = idempotentBookings.createBooking(request(), 1L, UUID.randomUUID().toString());

        assertEquals(9L, response.getPaymentId());
        assertEquals(6L, statistics.getTransactionCount() - before,
                "Preparation must return the replay decision without a separate transaction");
    }

    @Test
    void repeatedCreationReturnsOriginalCheckoutWithoutRepeatingSideEffects() throws Exception {
        String key = UUID.randomUUID().toString();
        var original = idempotentBookings.createBooking(request(), 1L, key);
        // Simulate lost response/retry after availability has changed.
        clearInvocations(flights, seats, payments, pricing, ancillaries);
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        long before = statistics.getTransactionCount();
        var replay = idempotentBookings.createBooking(request(), 1L, key.toUpperCase());
        assertEquals(2L, statistics.getTransactionCount() - before,
                "A completed replay only needs the preflight and preparation transactions");
        assertEquals(original, replay);
        assertEquals(1, bookingRepository.count());
        assertEquals(1, passengerRepository.count());
        assertEquals(1, attempts.count());
        verifyNoInteractions(flights, seats, payments, pricing, ancillaries);
    }

    @Test
    void concurrentReplayRecoversWhenValidationSeesSeatsConsumedByTheWinner() throws Exception {
        String key = UUID.randomUUID().toString();
        var selection = seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L));
        var validating = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        var validations = new java.util.concurrent.atomic.AtomicInteger();
        when(seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenAnswer(invocation -> {
                    if (validations.incrementAndGet() == 1) {
                        validating.countDown();
                        assertTrue(winnerCommitted.await(10, TimeUnit.SECONDS));
                        throw new IllegalArgumentException("Selected cabin is now full");
                    }
                    return selection;
                });
        try (var workers = Executors.newSingleThreadExecutor()) {
            var retry = workers.submit(() -> idempotentBookings.createBooking(request(), 1L, key));
            try {
                assertTrue(validating.await(5, TimeUnit.SECONDS));
                var winner = idempotentBookings.createBooking(request(), 1L, key);
                winnerCommitted.countDown();
                assertEquals(winner, retry.get(10, TimeUnit.SECONDS));
            } finally {
                winnerCommitted.countDown();
            }
        }
        assertEquals(1, bookingRepository.count());
        assertEquals(1, attempts.count());
        verify(seats, times(1)).reserveBookingSeats(anyLong(), eq(List.of(100L)));
        verify(payments, times(1)).initiatePayment(any(), eq(1L));
    }

    @Test
    void changedPayloadCannotReuseKeyAndInvalidKeysCreateNothing() throws Exception {
        String key = UUID.randomUUID().toString();
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> idempotentBookings.createBooking(request(), 1L, " ")).getStatusCode().value());
        assertEquals(0, bookingRepository.count());
        idempotentBookings.createBooking(request(), 1L, key);
        var changed = request();
        changed.getPassengers().getFirst().setLastName("Changed");
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> idempotentBookings.createBooking(changed, 1L, key)).getStatusCode().value());
        assertEquals(1, bookingRepository.count());
        verify(payments, times(1)).initiatePayment(any(), eq(1L));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid"})
    void missingOrInvalidKeyIsRejectedWithoutPersistenceOrRemoteCalls(String key) {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> idempotentBookings.createBooking(request(), 1L, key)).getStatusCode().value());
        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
        assertEquals(0, attempts.count());
        verifyNoInteractions(seats, flights, pricing, ancillaries, payments);
    }

    @Test
    void paymentTimeoutRetainsBookingAndRetryUsesSamePaymentRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        var checkout = PaymentInitiateResponse.builder().paymentId(9L).success(true)
                .checkoutUrl("https://checkout.stripe.com/recovered").build();
        when(payments.initiatePayment(any(), eq(1L)))
                .thenThrow(new IllegalStateException("Response lost after payment committed")).thenReturn(checkout);
        assertThrows(Exception.class, () -> idempotentBookings.createBooking(request(), 1L, key));
        Long bookingId = onlyBookingId();
        assertEquals(1, attempts.count());
        assertEquals(checkout, idempotentBookings.createBooking(request(), 1L, key));
        var requests = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        verify(payments, times(2)).initiatePayment(requests.capture(), eq(1L));
        assertEquals(requests.getAllValues().get(0), requests.getAllValues().get(1));
        assertEquals(bookingId, requests.getValue().getBookingId());
        verify(seats, times(1)).reserveBookingSeats(bookingId, List.of(100L));
        verify(seats, never()).releaseBookingSeats(anyLong(), anyList());
        assertEquals(1, passengerRepository.count());
    }

    @Test
    void simultaneousRequestsCreateOneBookingAndOneCheckout() throws Exception {
        String key = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<PaymentInitiateResponse> submit = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    return idempotentBookings.createBooking(request(), 1L, key);
                } catch (ResponseStatusException busy) {
                    assertEquals(409, busy.getStatusCode().value());
                    assertTrue(busy.getReason().contains("in progress"));
                    return null;
                }
            };
            var first = workers.submit(submit);
            var second = workers.submit(submit);
            start.countDown();
            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertTrue(firstResult != null || secondResult != null);
            var replay = idempotentBookings.createBooking(request(), 1L, key);
            if (firstResult != null) assertEquals(firstResult, replay);
            if (secondResult != null) assertEquals(secondResult, replay);
        }
        assertEquals(1, bookingRepository.count());
        assertEquals(1, passengerRepository.count());
        assertEquals(1, attempts.count());
        verify(seats, times(1)).reserveBookingSeats(anyLong(), eq(List.of(100L)));
        verify(payments, times(1)).initiatePayment(any(), eq(1L));
    }

    @Test
    void simultaneousDifferentPayloadsRejectTheLoserBeforeRepeatingSideEffects() throws Exception {
        String key = UUID.randomUUID().toString();
        var selection = seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L));
        var validating = new java.util.concurrent.CyclicBarrier(2);
        when(seats.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L)))
                .thenAnswer(invocation -> {
                    validating.await(10, TimeUnit.SECONDS);
                    return selection;
                });
        var changed = request();
        changed.getPassengers().getFirst().setLastName("Changed");
        java.util.function.Function<BookingRequest, java.util.concurrent.Callable<PaymentInitiateResponse>> submit =
                payload -> () -> {
                    try {
                        return idempotentBookings.createBooking(payload, 1L, key);
                    } catch (ResponseStatusException conflict) {
                        assertEquals(409, conflict.getStatusCode().value());
                        assertTrue(conflict.getReason().contains("different booking request"));
                        return null;
                    }
                };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(submit.apply(request()));
            var second = workers.submit(submit.apply(changed));
            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertTrue((firstResult == null) != (secondResult == null), "Exactly one payload must win");
        }
        assertEquals(1, bookingRepository.count());
        assertEquals(1, passengerRepository.count());
        assertEquals(1, attempts.count());
        verify(seats, times(1)).reserveBookingSeats(anyLong(), eq(List.of(100L)));
        verify(payments, times(1)).initiatePayment(any(), eq(1L));
    }

    @Test
    void keysAreScopedToUserAndPreparationFailuresRollBackClaim() throws Exception {
        String key = UUID.randomUUID().toString();
        var invalid = request();
        invalid.getPassengers().getFirst().setSeatInstanceId(999L);
        assertThrows(IllegalArgumentException.class,
                () -> idempotentBookings.createBooking(invalid, 1L, key));
        assertEquals(0, attempts.count());
        idempotentBookings.createBooking(request(), 1L, key);
        when(payments.initiatePayment(any(), eq(2L))).thenReturn(
                PaymentInitiateResponse.builder().paymentId(10L).success(true).build());
        assertEquals(10L, idempotentBookings.createBooking(request(), 2L, key).getPaymentId());
        assertEquals(2, bookingRepository.count());
        assertEquals(2, attempts.count());
    }

    @Test
    void expiredPendingAttemptDoesNotStartAnotherCheckout() throws Exception {
        String key = UUID.randomUUID().toString();
        when(payments.initiatePayment(any(), eq(1L))).thenThrow(new IllegalStateException("timeout"));
        assertThrows(Exception.class, () -> idempotentBookings.createBooking(request(), 1L, key));
        var booking = bookingRepository.findById(onlyBookingId()).orElseThrow();
        booking.setHoldExpiresAt(Instant.now().minusSeconds(1));
        bookingRepository.save(booking);
        clearInvocations(payments);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> idempotentBookings.createBooking(request(), 1L, key)).getStatusCode().value());
        verifyNoInteractions(payments);
        assertEquals(1, bookingRepository.count());
    }

    @Test
    void abandonedAttemptCanRecoverPaymentForExpiryWithoutCreatingAnotherBooking() throws Exception {
        String key = UUID.randomUUID().toString();
        when(payments.initiatePayment(any(), eq(1L))).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> idempotentBookings.createBooking(request(), 1L, key));
        Long bookingId = onlyBookingId();
        var booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.setHoldExpiresAt(Instant.now().minusSeconds(1));
        bookingRepository.save(booking);
        when(payments.initiatePayment(any(), eq(1L))).thenReturn(
                PaymentInitiateResponse.builder().paymentId(9L).success(true).build());
        idempotentBookings.prepareExpiredCheckout(bookingId);
        idempotentBookings.prepareExpiredCheckout(bookingId);
        assertEquals(1, bookingRepository.count());
        verify(payments, times(2)).initiatePayment(argThat(p -> p.getBookingId().equals(bookingId)), eq(1L));
        verify(seats, times(1)).reserveBookingSeats(bookingId, List.of(100L));
        verify(seats, never()).releaseBookingSeats(anyLong(), anyList());
    }

    private Long onlyBookingId() {
        assertEquals(1, bookingRepository.count());
        return bookingRepository.findAll().getFirst().getId();
    }

    private void completePayment(Long bookingId) {
        PaymentDto paid = new PaymentDto();
        paid.setStatus(PaymentStatus.SUCCESS);
        paid.setAmount(155.0);
        when(payments.getPaymentsByBookingIds(List.of(bookingId))).thenReturn(Map.of(bookingId, paid));
        // Invoke the real transactional listener; Kafka transport is outside this suite.
        paymentEvents.handlePaymentCompleted(PaymentCompletedEvent.builder().bookingId(bookingId)
                .paymentId(9L).userId(1L).amount(155.0).build());
    }

    private BookingRequest request() {
        return BookingRequest.builder().flightId(10L).flightInstanceId(20L).fareId(30L)
                .cabinClass(CabinClassType.ECONOMY).ancillaryIds(List.of(60L)).mealIds(List.of(70L))
                .contactInfo(ContactInfo.builder().email("booking@example.test").phone("+15555550123").build())
                .passengers(List.of(PassengerRequest.builder().firstName("Booking").lastName("Test")
                        .email("booking@example.test").dateOfBirth(LocalDate.of(1990, 1, 1))
                        .gender(Gender.MALE).seatInstanceId(100L).build())).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BookingRepository.class)
    @Import({BookingServiceImpl.class, PassengerServiceImpl.class, TicketServiceImpl.class,
            FareIntegrationService.class, PaymentEventListener.class, IdempotentBookingService.class,
            BookingReservationService.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv("RECOVERY_TEST_JDBC_URL");
            if (url != null) {
                if (!url.matches("jdbc:mysql://[a-zA-Z0-9_.-]+:3306/recovery_test_[0-9a-f]{32}")) {
                    throw new IllegalArgumentException("Recovery tests require a disposable recovery_test_UUID schema");
                }
                return new DriverManagerDataSource(url, System.getenv("RECOVERY_TEST_JDBC_USER"),
                        System.getenv("RECOVERY_TEST_JDBC_PASSWORD"));
            }
            return new DriverManagerDataSource("jdbc:h2:mem:booking-flow;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Booking.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.generate_statistics", "true"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean FlightClient flightClient() { return mock(FlightClient.class); }
        @Bean SeatClient seatClient() { return mock(SeatClient.class); }
        @Bean AncillaryClient ancillaryClient() { return mock(AncillaryClient.class); }
        @Bean PricingClient pricingClient() { return mock(PricingClient.class); }
        @Bean PaymentClient paymentClient() { return mock(PaymentClient.class); }
        @Bean AirlineClient airlineClient() { return mock(AirlineClient.class); }
        @Bean UserClient userClient() { return mock(UserClient.class); }
        @Bean BookingEventProducer bookingEventProducer() { return mock(BookingEventProducer.class); }
    }
}
