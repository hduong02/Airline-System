package com.example.booking_service.service.impl;

import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.client.SeatClient;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.BookingAttempt;
import com.example.booking_service.repository.BookingAttemptRepository;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.service.BookingService;
import com.example.booking_service.service.IdempotentBookingService;
import com.example.booking_service.service.BookingReservationService;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.enums.BookingStatus;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Booking disables OSIV: request scope must not retain a JDBC connection between transactions. */
@SpringJUnitConfig(BookingRequestConnectionTest.Config.class)
class BookingRequestConnectionTest {
    @Autowired IdempotentBookingService checkout;
    @Autowired BookingAttemptRepository attempts;
    @Autowired HikariDataSource pool;
    @Autowired BookingService bookings;
    @Autowired BookingRepository bookingRepository;
    @Autowired PaymentClient payments;
    @Autowired SeatClient seats;

    @BeforeEach
    void resetClients() { reset(bookings, payments, seats); }

    @Test
    void slowReservationReturnsTheOnlyConnectionAndDoesNotBlockAnotherCheckout() throws Exception {
        stubPreparation();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if (first.getAndSet(false)) {
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return null;
        }).when(seats).reserveBookingSeats(any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var slow = workers.submit(() -> {
                    withRequest(() -> checkout.createBooking(BookingRequest.builder().build(), 2L, UUID.randomUUID().toString()));
                    return null;
                });
                if (!entered.await(10, TimeUnit.SECONDS)) {
                    slow.get(1, TimeUnit.SECONDS);
                    fail("Reservation did not reach the remote boundary");
                }
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                workers.submit(() -> {
                    withRequest(() -> checkout.createBooking(BookingRequest.builder().build(), 2L, UUID.randomUUID().toString()));
                    return null;
                }).get(5, TimeUnit.SECONDS);
                assertFalse(slow.isDone());
                release.countDown();
                slow.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void replayCompletesWithOneConnectionAndOsivDisabled() throws Exception {
        var request = BookingRequest.builder().build();
        var json = JsonMapper.builder().build();
        String key = UUID.randomUUID().toString();
        var response = PaymentInitiateResponse.builder().paymentId(9L).success(true).build();
        var attempt = new BookingAttempt();
        attempt.setUserId(1L);
        attempt.setIdempotencyKey(key);
        attempt.setRequestHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))));
        attempt.setCheckoutResponse(json.writeValueAsString(response));
        attempts.saveAndFlush(attempt);

        withRequest(() -> assertEquals(9L, checkout.createBooking(request, 1L, key).getPaymentId()));
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void newCheckoutCompletesWithOneConnectionAndOsivDisabled() throws Exception {
        stubPreparation();
        withRequest(() -> assertEquals(10L, checkout.createBooking(
                BookingRequest.builder().build(), 2L, UUID.randomUUID().toString()).getPaymentId()));
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void tenConcurrentCheckoutsCompleteWithTenConnections() throws Exception {
        pool.setMaximumPoolSize(10);
        // Exclude lazy pool growth from the deliberately short acquisition timeout.
        var connections = new ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < 10; i++) connections.add(pool.getConnection());
        } finally {
            for (var connection : connections) connection.close();
        }
        var validated = new CyclicBarrier(10);
        stubPreparation();
        when(bookings.validateBooking(any())).thenAnswer(call -> {
            validated.await(10, TimeUnit.SECONDS);
            return null;
        });
        try (var workers = Executors.newFixedThreadPool(10)) {
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < 10; i++) {
                results.add(workers.submit(() -> {
                    withRequest(() -> assertEquals(10L, checkout.createBooking(
                            BookingRequest.builder().build(), 2L, UUID.randomUUID().toString()).getPaymentId()));
                    return null;
                }));
            }
            for (var result : results) result.get(15, TimeUnit.SECONDS);
            verify(payments, times(10)).initiatePayment(any(), any());
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        } finally {
            pool.setMaximumPoolSize(1);
        }
    }

    private void stubPreparation() throws Exception {
        when(bookings.prepareValidatedBooking(any(), any(), any())).thenAnswer(call -> {
            var booking = bookingRepository.saveAndFlush(Booking.builder()
                    .bookingReference(UUID.randomUUID().toString()).userId(2L)
                    .flightId(10L).flightInstanceId(20L).airlineId(30L).fareId(40L)
                    .status(BookingStatus.PENDING).seatInstanceIds(java.util.List.of(100L))
                    .holdExpiresAt(Instant.now().plusSeconds(120)).build());
            return PaymentInitiateRequest.builder().bookingId(booking.getId()).build();
        });
        when(payments.initiatePayment(any(), any())).thenReturn(
                PaymentInitiateResponse.builder().paymentId(10L).success(true).build());
    }

    private void withRequest(CheckedAction action) throws Exception {
        var request = new ServletWebRequest(new MockHttpServletRequest());
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(request);
        try {
            action.run();
        } finally {
            request.requestCompleted();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    private interface CheckedAction { void run() throws Exception; }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BookingRepository.class)
    @Import({IdempotentBookingService.class, BookingReservationService.class})
    static class Config {
        @Bean HikariDataSource dataSource() {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:booking-request-connection;MODE=MySQL;DB_CLOSE_DELAY=-1");
            pool.setUsername("sa");
            pool.setPassword("");
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(500);
            return pool;
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(HikariDataSource pool) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(pool);
            factory.setPackagesToScan(Booking.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean BookingService bookingService() { return mock(BookingService.class); }
        @Bean PaymentClient paymentClient() { return mock(PaymentClient.class); }
        @Bean SeatClient seatClient() { return mock(SeatClient.class); }
    }
}
