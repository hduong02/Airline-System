package com.example.booking_service.service.impl;

import com.example.booking_service.client.*;
import com.example.booking_service.event.listener.PaymentEventListener;
import com.example.booking_service.event.publisher.BookingEventProducer;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.BookingAttempt;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.*;
import com.example.booking_service.service.IdempotentBookingService;
import com.example.enums.*;
import com.example.event.PaymentCompletedEvent;
import com.example.payload.dto.UserDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transactional services/JPA; remote delays are gates, never arbitrary sleeps.
 * H2's database wait graph proves blocking rather than merely an unfinished future.
 */
@SpringJUnitConfig(BookingLockContentionTest.Config.class)
class BookingLockContentionTest {
    @Autowired IdempotentBookingService checkout;
    @Autowired PaymentEventListener confirmations;
    @Autowired BookingRepository bookings;
    @Autowired BookingAttemptRepository attempts;
    @Autowired PassengerRepository passengers;
    @Autowired TicketRepository tickets;
    @Autowired PaymentClient payments;
    @Autowired FlightClient flights;
    @Autowired PricingClient pricing;
    @Autowired UserClient users;
    @Autowired BookingEventProducer events;
    @Autowired DataSource dataSource;

    private final JsonMapper json = JsonMapper.builder().build();
    private final BookingRequest request = BookingRequest.builder().flightId(10L).build();

    @BeforeEach
    void resetFixtures() {
        reset(payments, flights, pricing, users, events);
        attempts.deleteAll();
        tickets.deleteAll();
        bookings.deleteAll();
        passengers.deleteAll();
        when(payments.initiatePayment(any(), eq(1L))).thenAnswer(call ->
                PaymentInitiateResponse.builder().bookingId(call.<PaymentInitiateRequest>getArgument(0).getBookingId())
                        .paymentId(9L).success(true).checkoutUrl("https://checkout.example.test").build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkout-replay", "confirmation"})
    void slowCheckoutBlocksSameAttemptOrBookingButNotAnotherBooking(String competitor) throws Exception {
        var target = seed(20L, 30L, 1L);
        var independent = seed(21L, 31L, 1L);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(payments.initiatePayment(argThat(p -> p.getBookingId().equals(target.bookingId())), eq(1L)))
                .thenAnswer(call -> {
                    entered.countDown();
                    await(release);
                    return PaymentInitiateResponse.builder().bookingId(target.bookingId())
                            .paymentId(9L).success(true).build();
                });

        try (var workers = Executors.newFixedThreadPool(3)) {
            try {
                var owner = workers.submit(() -> checkout.createBooking(request, 1L, target.key()));
                await(entered);
                Future<?> waiter = workers.submit(() -> {
                    if (competitor.equals("checkout-replay")) {
                        return checkout.createBooking(request, 1L, target.key());
                    }
                    confirm(target.bookingId());
                    return null;
                });
                awaitDatabaseWait(competitor.equals("checkout-replay") ? "booking_attempt" : "booking");
                assertFalse(waiter.isDone());

                // Same user, different key/booking: a global/user lock would block this too.
                assertNotNull(workers.submit(() -> checkout.createBooking(request, 1L, independent.key()))
                        .get(3, TimeUnit.SECONDS));
                assertFalse(waiter.isDone(), "Contender escaped while payment was still gated");
                release.countDown();
                var response = owner.get(5, TimeUnit.SECONDS);
                var result = waiter.get(5, TimeUnit.SECONDS);
                if (competitor.equals("checkout-replay")) assertEquals(response, result);
                else assertEquals(BookingStatus.CONFIRMED, bookings.findById(target.bookingId()).orElseThrow().getStatus());
            } finally {
                release.countDown();
                workers.shutdownNow();
            }
        }
        verify(payments, times(1)).initiatePayment(argThat(p -> p.getBookingId().equals(target.bookingId())), eq(1L));
        assertNotNull(attempts.findByUserIdAndIdempotencyKey(1L, target.key()).orElseThrow().getCheckoutResponse());
        System.out.println("CONTENTION checkout -> " + competitor + ": database lock wait observed; independent checkout completed; released successfully");
    }

    @ParameterizedTest
    @CsvSource({"flight,duplicate", "pricing,duplicate", "user,duplicate",
            "flight,checkout", "pricing,checkout", "user,checkout"})
    void slowConfirmationBlocksSameBookingButNotAnotherBooking(String boundary, String competitor) throws Exception {
        var target = seed(20L, 30L, 1L);
        var independent = seed(21L, 31L, 2L);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        switch (boundary) {
            case "flight" -> when(flights.getFlightInstanceById(20L)).thenAnswer(call -> {
                entered.countDown(); await(release); return FlightInstanceResponse.builder().id(20L).build();
            });
            case "pricing" -> when(pricing.getFareById(30L)).thenAnswer(call -> {
                entered.countDown(); await(release); return FareResponse.builder().id(30L).build();
            });
            case "user" -> when(users.getUserById(1L)).thenAnswer(call -> {
                entered.countDown(); await(release); return UserDto.builder().id(1L).build();
            });
            default -> fail("Unknown boundary");
        }

        try (var workers = Executors.newFixedThreadPool(3)) {
            try {
                var owner = workers.submit(() -> confirm(target.bookingId()));
                await(entered);
                var contender = workers.submit(() -> {
                    if (competitor.equals("checkout")) return checkout.createBooking(request, 1L, target.key());
                    confirm(target.bookingId());
                    return null;
                });
                awaitDatabaseWait("booking");
                assertFalse(contender.isDone());
                workers.submit(() -> confirm(independent.bookingId())).get(3, TimeUnit.SECONDS);
                assertFalse(contender.isDone(), "Contender escaped while enrichment was still gated");
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
                if (competitor.equals("checkout")) {
                    var failure = assertThrows(ExecutionException.class, () -> contender.get(5, TimeUnit.SECONDS));
                    var conflict = assertInstanceOf(ResponseStatusException.class, failure.getCause());
                    assertEquals(409, conflict.getStatusCode().value());
                    verifyNoInteractions(payments);
                } else {
                    contender.get(5, TimeUnit.SECONDS);
                }
            } finally {
                release.countDown();
                workers.shutdownNow();
            }
        }
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(target.bookingId()).orElseThrow().getStatus());
        assertEquals(2, tickets.count(), "Exactly one ticket per booking despite the concurrent contender");
        verify(events, times(1)).sendBookingConfirmed(argThat(b -> b.getId().equals(target.bookingId())), any(), any(), any(), any());
        System.out.println("CONTENTION confirmation/" + boundary + " -> " + competitor
                + ": database lock wait observed; independent confirmation completed; one ticket/event per booking");
    }

    private Fixture seed(Long instanceId, Long fareId, Long userId) throws Exception {
        var booking = Booking.builder().bookingReference("LOCK-" + UUID.randomUUID()).userId(userId)
                .flightId(10L).flightInstanceId(instanceId).airlineId(50L).fareId(fareId)
                .status(BookingStatus.PENDING).holdExpiresAt(Instant.now().plusSeconds(600))
                .seatInstanceIds(List.of()).build();
        var passenger = Passenger.builder().firstName("Lock").lastName("Fixture")
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Gender.MALE).booking(booking).build();
        booking.getPassengers().add(passenger);
        booking = bookings.saveAndFlush(booking);
        String key = UUID.randomUUID().toString();
        var attempt = new BookingAttempt();
        attempt.setUserId(userId);
        attempt.setIdempotencyKey(key);
        attempt.setRequestHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))));
        attempt.setBookingId(booking.getId());
        attempt.setPaymentRequest(json.writeValueAsString(PaymentInitiateRequest.builder()
                .bookingId(booking.getId()).userId(userId).amount(155.0).gateway(PaymentGateway.STRIPE).build()));
        attempts.saveAndFlush(attempt);
        return new Fixture(booking.getId(), key);
    }

    private void confirm(Long bookingId) {
        confirmations.handlePaymentCompleted(PaymentCompletedEvent.builder().bookingId(bookingId)
                .paymentId(9L).amount(155.0).build());
    }

    private void awaitDatabaseWait(String table) throws Exception {
        var jdbc = new JdbcTemplate(dataSource);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            var statements = jdbc.queryForList("SELECT EXECUTING_STATEMENT FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL", String.class);
            if (statements.stream().anyMatch(sql -> sql != null && sql.toLowerCase(java.util.Locale.ROOT)
                    .contains("from " + table + " ") && sql.toLowerCase(java.util.Locale.ROOT).contains("for update"))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("No database-confirmed FOR UPDATE wait on " + table);
    }

    private static void await(CountDownLatch gate) throws InterruptedException {
        assertTrue(gate.await(10, TimeUnit.SECONDS), "Worker did not reach/release the remote boundary");
    }

    private record Fixture(Long bookingId, String key) {}

    @Configuration(proxyBeanMethods = false)
    static class Config extends BookingFlowIntegrationTest.Config {
        @Override @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:booking-lock-contention;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
    }
}
