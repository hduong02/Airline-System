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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(PaymentInitiationConnectionTest.Config.class)
class PaymentInitiationConnectionTest {
    @Autowired PaymentService service;
    @Autowired PaymentRepository repository;
    @Autowired StripeService stripe;
    @Autowired UserClient users;
    @Autowired HikariDataSource dataSource;

    @BeforeEach
    void prepare() throws Exception {
        reset(stripe, users);
        repository.deleteAll();
        when(users.getUserById(anyLong())).thenAnswer(invocation ->
                UserDto.builder().id(invocation.getArgument(0)).build());
        when(stripe.createPaymentLink(any(), any())).thenReturn(link());
        when(stripe.fetchCheckoutLink(anyString())).thenReturn(link());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "create", "resume"})
    void unrelatedBookingCompletesWhileRemoteCallIsBlocked(String remote) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> blocked = invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return remote.equals("user") ? UserDto.builder().id(2L).build() : link();
        };
        switch (remote) {
            case "user" -> when(users.getUserById(2L)).thenAnswer(blocked);
            case "create" -> when(stripe.createPaymentLink(any(), argThat(p -> p.getBookingId() == 101L)))
                    .thenAnswer(blocked);
            case "resume" -> {
                service.initiatePayment(request(101L, 2L));
                when(stripe.fetchCheckoutLink("cs_local")).thenAnswer(blocked);
            }
            default -> throw new IllegalArgumentException(remote);
        }

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.initiatePayment(request(101L, 2L)));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var second = executor.submit(() -> service.initiatePayment(request(102L, 3L)));
                assertEquals(102L, second.get(500, TimeUnit.MILLISECONDS).getBookingId());
            } finally {
                release.countDown();
                first.get(3, TimeUnit.SECONDS);
            }
        }
    }

    private PaymentInitiateRequest request(Long booking, Long user) {
        return PaymentInitiateRequest.builder().bookingId(booking).userId(user).amount(100.0)
                .gateway(PaymentGateway.STRIPE).build();
    }

    private PaymentLinkResponse link() {
        return new PaymentLinkResponse("https://example.invalid/checkout", "cs_local");
    }

    @Configuration(proxyBeanMethods = false)
    static class Config extends PaymentInitiationIntegrationTest.Config {
        @Override
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:payment-connection-regression;MODE=MySQL;DB_CLOSE_DELAY=-1");
            pool.setUsername("sa");
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(2000);
            return pool;
        }
    }
}
