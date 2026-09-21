package com.example.payment_service.service.impl;

import com.example.enums.PaymentStatus;
import com.example.payment_service.event.PaymentEventProducer;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.gateway.StripeService;
import com.example.payment_service.service.gateway.StripeService.CheckoutOutcome;
import com.example.payment_service.service.gateway.StripeService.CheckoutState;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CheckoutVerificationConcurrencyTest.Config.class)
class CheckoutVerificationConcurrencyTest {
    @Autowired PaymentService service;
    @Autowired PaymentRepository repository;
    @Autowired StripeService stripe;
    @Autowired PaymentEventProducer events;
    @Autowired HikariDataSource dataSource;
    @Autowired EntityManagerFactory factory;

    @BeforeEach
    void prepare() {
        reset(stripe, events);
        repository.deleteAll();
        repository.saveAndFlush(Payment.builder().bookingId(101L).userId(2L).amount(100.0)
                .status(PaymentStatus.PENDING).checkoutSessionId("cs_saved").build());
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutOutcome.class, names = {"PROCESSING", "PAID"})
    void secondVerificationCompletesAndPublishesOnceWhileFirstStripeCallIsBlocked(CheckoutOutcome delayed)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(stripe.fetchCheckoutState("cs_saved")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (calls.incrementAndGet() == 1) {
                assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new CheckoutState(delayed, delayed == CheckoutOutcome.PAID ? "pi_saved" : null);
            }
            return new CheckoutState(CheckoutOutcome.PAID, "pi_saved");
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.verifyCheckoutPayment(101L, 2L));
            try {
                if (!entered.await(3, TimeUnit.SECONDS)) {
                    first.get(1, TimeUnit.SECONDS);
                    fail("Stripe lookup was not reached");
                }
                var second = executor.submit(() -> service.verifyCheckoutPayment(101L, 2L));
                assertEquals(PaymentStatus.SUCCESS, second.get(500, TimeUnit.MILLISECONDS).getStatus());
            } finally {
                release.countDown();
            }
            assertEquals(PaymentStatus.SUCCESS, first.get(3, TimeUnit.SECONDS).getStatus());
        }
        verify(events).sendPaymentCompleted(any(Payment.class));
        verify(events, never()).sendPaymentFailed(any());
        assertEquals("pi_saved", repository.findByBookingId(101L).orElseThrow().getProviderPaymentId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "session", "owner"})
    void stalePaidResultCannotOverwriteConcurrentChanges(String change) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(stripe.fetchCheckoutState("cs_saved")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new CheckoutState(CheckoutOutcome.PAID, "pi_stale");
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(this::verifyWithOpenEntityManager);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var update = executor.submit(() -> {
                    Payment payment = repository.findByBookingId(101L).orElseThrow();
                    switch (change) {
                        case "cancel" -> payment.setStatus(PaymentStatus.CANCELLED);
                        case "session" -> payment.setCheckoutSessionId("cs_replacement");
                        case "owner" -> payment.setUserId(3L);
                        default -> throw new IllegalArgumentException(change);
                    }
                    return repository.saveAndFlush(payment);
                });
                update.get(500, TimeUnit.MILLISECONDS);
            } finally {
                release.countDown();
            }
            if (change.equals("cancel")) {
                assertEquals(PaymentStatus.CANCELLED, first.get(3, TimeUnit.SECONDS).getStatus());
            } else {
                var failure = assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
                var error = assertInstanceOf(ResponseStatusException.class, failure.getCause());
                assertEquals(change.equals("owner") ? 403 : 409, error.getStatusCode().value());
            }
        }
        assertNull(repository.findByBookingId(101L).orElseThrow().getProviderPaymentId());
        verifyNoInteractions(events);
    }

    // Exercise the persistence-context lifetime used by OpenEntityManagerInView as well.
    private com.example.payload.dto.PaymentDto verifyWithOpenEntityManager() throws Exception {
        try (var entityManager = factory.createEntityManager()) {
            TransactionSynchronizationManager.bindResource(factory, new EntityManagerHolder(entityManager));
            try {
                return service.verifyCheckoutPayment(101L, 2L);
            } finally {
                TransactionSynchronizationManager.unbindResource(factory);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config extends PaymentInitiationIntegrationTest.Config {
        @Override
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:checkout-verification-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1");
            pool.setUsername("sa");
            pool.setMaximumPoolSize(2);
            pool.setConnectionTimeout(2000);
            return pool;
        }
    }
}
