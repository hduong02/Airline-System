package com.example.payment_service.service.impl;

import com.example.enums.PaymentGateway;
import com.example.payload.dto.UserDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentLinkResponse;
import com.example.payment_service.client.UserClient;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.gateway.StripeService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in reproduction; the latency assertion is expected to fail on current code.
 * Uses real service transactions, H2, and Hikari; never contacts external systems.
 */
@EnabledIfSystemProperty(named = "payment.latency.diagnosis", matches = "true")
@SpringJUnitConfig(PaymentLatencyDiagnosticTest.Config.class)
class PaymentLatencyDiagnosticTest {
    @Autowired PaymentService service;
    @Autowired PaymentRepository repository;
    @Autowired StripeService stripe;
    @Autowired UserClient users;
    @Autowired DataSource dataSource;

    @BeforeEach
    void prepare() throws Exception {
        reset(stripe, users);
        repository.deleteAll();
        when(stripe.createPaymentLink(any(), any()))
                .thenReturn(new PaymentLinkResponse("https://example.invalid/checkout", "cs_local"));
        when(users.getUserById(3L)).thenReturn(UserDto.builder().id(3L).build());
    }

    @Test
    void slowUserLookupMustNotBlockAnUnrelatedBooking() throws Exception {
        var upstreamEntered = new CountDownLatch(1);
        var releaseUpstream = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        when(users.getUserById(2L)).thenAnswer(invocation -> {
            upstreamEntered.countDown();
            if (!releaseUpstream.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Local diagnostic did not release upstream");
            }
            return UserDto.builder().id(2L).build();
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.initiatePayment(request(101L, 2L)));
            try {
                assertTrue(upstreamEntered.await(3, TimeUnit.SECONDS));
                // Optional control: identical flow, but release the slow dependency first.
                if (Boolean.getBoolean("payment.latency.releaseUpstream")) {
                    releaseUpstream.countDown();
                    first.get(3, TimeUnit.SECONDS);
                }
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return service.initiatePayment(request(102L, 3L));
                });
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
                long started = System.nanoTime();
                try {
                    assertEquals(102L, second.get(500, TimeUnit.MILLISECONDS).getBookingId());
                } finally {
                    var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
                    System.out.printf("PAYMENT_DIAG elapsed_ms=%d active=%d waiting=%d pool_size=%d%n",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            pool.getActiveConnections(), pool.getThreadsAwaitingConnection(),
                            ((HikariDataSource) dataSource).getMaximumPoolSize());
                }
            } finally {
                releaseUpstream.countDown();
                first.get(3, TimeUnit.SECONDS);
            }
        }
    }

    private PaymentInitiateRequest request(Long booking, Long user) {
        return PaymentInitiateRequest.builder().bookingId(booking).userId(user).amount(100.0)
                .gateway(PaymentGateway.STRIPE).description("Local latency diagnosis").build();
    }

    @Test
    @EnabledIfSystemProperty(named = "payment.latency.rowLock", matches = "true")
    void slowStripeLookupMustNotBlockVerificationOfTheSameBooking() throws Exception {
        assertTrue(((HikariDataSource) dataSource).getMaximumPoolSize() >= 2,
                "Row-lock diagnosis needs spare connections to exclude pool starvation");
        service.initiatePayment(request(103L, 3L));
        var upstreamEntered = new CountDownLatch(1);
        var releaseUpstream = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var stripeCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(stripe.fetchCheckoutState("cs_local")).thenAnswer(invocation -> {
            // Only the first request is slow; the second must be free to call Stripe itself.
            if (stripeCalls.incrementAndGet() == 1) {
                upstreamEntered.countDown();
                if (!releaseUpstream.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Local diagnostic did not release Stripe");
                }
            }
            return new StripeService.CheckoutState(StripeService.CheckoutOutcome.PROCESSING, null);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.verifyCheckoutPayment(103L, 3L));
            try {
                assertTrue(upstreamEntered.await(3, TimeUnit.SECONDS));
                if (Boolean.getBoolean("payment.latency.releaseUpstream")) {
                    releaseUpstream.countDown();
                    first.get(3, TimeUnit.SECONDS);
                }
                var second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    secondStarted.countDown();
                    return service.verifyCheckoutPayment(103L, 3L);
                });
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
                long started = System.nanoTime();
                try {
                    assertEquals(103L, second.get(500, TimeUnit.MILLISECONDS).getBookingId());
                } finally {
                    var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
                    System.out.printf("PAYMENT_ROW_LOCK elapsed_ms=%d active=%d waiting=%d%n",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            pool.getActiveConnections(), pool.getThreadsAwaitingConnection());
                    for (var frame : secondThread.get().getStackTrace()) {
                        if (frame.getClassName().startsWith("org.h2.mvstore.tx.Transaction")) {
                            System.out.println("PAYMENT_ROW_LOCK " + frame);
                        }
                    }
                }
            } finally {
                releaseUpstream.countDown();
                first.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config extends PaymentInitiationIntegrationTest.Config {
        @Override
        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:payment-latency;MODE=MySQL;DB_CLOSE_DELAY=-1");
            pool.setUsername("sa");
            pool.setPassword("");
            pool.setMaximumPoolSize(Integer.getInteger("payment.latency.poolSize", 1));
            pool.setConnectionTimeout(2000);
            return pool;
        }
    }
}
