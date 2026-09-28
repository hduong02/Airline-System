package com.example.booking_service.service.impl;

import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PricingClient;
import com.example.booking_service.client.UserClient;
import com.example.booking_service.event.listener.PaymentEventListener;
import com.example.booking_service.event.publisher.BookingEventProducer;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.repository.TicketRepository;
import com.example.enums.BookingStatus;
import com.example.enums.Gender;
import com.example.event.PaymentCompletedEvent;
import com.example.event.PaymentFailedEvent;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JPA transactions and tickets, with exactly one available DB connection. */
@SpringJUnitConfig(PaymentConfirmationConnectionTest.Config.class)
class PaymentConfirmationConnectionTest {
    @Autowired PaymentEventListener listener;
    @Autowired BookingRepository bookings;
    @Autowired TicketRepository tickets;
    @Autowired FlightClient flights;
    @Autowired PricingClient pricing;
    @Autowired UserClient users;
    @Autowired BookingEventProducer events;
    @Autowired DataSource dataSource;

    @BeforeEach
    void resetFixtures() {
        reset(flights, pricing, users, events);
        tickets.deleteAll();
        bookings.deleteAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"flight", "pricing", "user"})
    void enrichmentReleasesConnectionAndCancellationWins(String boundary) throws Exception {
        Long id = seed();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> slow = call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        };
        switch (boundary) {
            case "flight" -> when(flights.getFlightInstanceById(20L)).thenAnswer(slow);
            case "pricing" -> when(pricing.getFareById(30L)).thenAnswer(slow);
            case "user" -> when(users.getUserById(1L)).thenAnswer(slow);
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner = workers.submit(() -> complete(id));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(0, pool().getHikariPoolMXBean().getActiveConnections(),
                        "Remote enrichment must hold no connection");
                workers.submit(() -> listener.handlePaymentFailed(
                        PaymentFailedEvent.builder().bookingId(id).build())).get(3, TimeUnit.SECONDS);
                assertEquals(BookingStatus.CANCELLED, bookings.findById(id).orElseThrow().getStatus());
            } finally {
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, tickets.count());
        verify(events, never()).sendBookingConfirmed(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentCompletionsIssueOneTicketAndNotification() throws Exception {
        Long id = seed();
        var bothReading = new CountDownLatch(2);
        when(flights.getFlightInstanceById(20L)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            bothReading.countDown();
            assertTrue(bothReading.await(5, TimeUnit.SECONDS));
            return null;
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> complete(id));
            var second = workers.submit(() -> complete(id));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(id).orElseThrow().getStatus());
        assertEquals(1, tickets.count());
        verify(events).sendBookingConfirmed(any(), any(), any(), any(), any());
        assertEquals(0, pool().getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void unavailableEnrichmentStillConfirmsAndLoadsNotificationCollectionsInTransaction() {
        Long id = seed();
        when(flights.getFlightInstanceById(20L)).thenThrow(new IllegalStateException("flight unavailable"));
        when(pricing.getFareById(30L)).thenThrow(new IllegalStateException("pricing unavailable"));
        when(users.getUserById(1L)).thenThrow(new IllegalStateException("user unavailable"));
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Booking booking = call.getArgument(0);
            assertEquals(1, booking.getPassengers().size());
            assertEquals(1, booking.getTickets().size());
            booking.getSeatInstanceIds().size();
            return null;
        }).when(events).sendBookingConfirmed(any(), any(), isNull(), isNull(), isNull());
        complete(id);
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(id).orElseThrow().getStatus());
        assertEquals(1, tickets.count());
        verify(events).sendBookingConfirmed(any(), any(), isNull(), isNull(), isNull());
    }

    @Test
    void failedConfirmationRollsBackTicketsAndCanBeRetried() {
        Long id = seed();
        doThrow(new IllegalStateException("publishing failed")).when(events)
                .sendBookingConfirmed(any(), any(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> complete(id));
        Booking unchanged = bookings.findById(id).orElseThrow();
        assertEquals(BookingStatus.PENDING, unchanged.getStatus());
        assertFalse(unchanged.isTicketIssued());
        assertNull(unchanged.getPaymentId());
        assertEquals(0, tickets.count());
        reset(events);
        complete(id);
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(id).orElseThrow().getStatus());
        assertEquals(1, tickets.count());
    }

    private void complete(Long id) {
        listener.handlePaymentCompleted(PaymentCompletedEvent.builder().bookingId(id).paymentId(42L).build());
    }

    private HikariDataSource pool() { return (HikariDataSource) dataSource; }

    private Long seed() {
        var booking = Booking.builder().bookingReference(UUID.randomUUID().toString()).userId(1L)
                .flightId(10L).flightInstanceId(20L).airlineId(50L).fareId(30L)
                .status(BookingStatus.PENDING).build();
        booking.getPassengers().add(Passenger.builder().firstName("Test").lastName("Passenger")
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Gender.MALE).booking(booking).build());
        return bookings.saveAndFlush(booking).getId();
    }

    @Configuration(proxyBeanMethods = false)
    static class Config extends BookingFlowIntegrationTest.Config {
        @Override @Bean DataSource dataSource() {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:payment-confirmation-connection;MODE=MySQL;DB_CLOSE_DELAY=-1");
            pool.setUsername("sa");
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(500);
            return pool;
        }
    }
}
