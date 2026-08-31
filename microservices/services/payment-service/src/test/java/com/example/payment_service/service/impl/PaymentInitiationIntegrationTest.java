package com.example.payment_service.service.impl;

import com.example.enums.PaymentGateway;
import com.example.enums.PaymentStatus;
import com.example.payload.dto.UserDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payload.response.PaymentLinkResponse;
import com.example.payment_service.client.UserClient;
import com.example.payment_service.event.PaymentEventProducer;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentInitiationService;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.gateway.StripeService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(PaymentInitiationIntegrationTest.Config.class)
class PaymentInitiationIntegrationTest {
    @Autowired private PaymentService service;
    @MockitoSpyBean private PaymentRepository repository;
    @Autowired private StripeService stripe;
    @Autowired private UserClient users;

    @BeforeEach
    void setUp() throws Exception {
        reset(repository, stripe, users);
        repository.deleteAll();
        when(users.getUserById(2L)).thenReturn(UserDto.builder().id(2L).build());
        when(stripe.createPaymentLink(any(), any())).thenReturn(link());
        when(stripe.fetchCheckoutLink("cs_1")).thenReturn(link());
    }

    @Test
    void checkedStripeFailureRollsBackPaymentAndAllowsRetry() throws Exception {
        when(stripe.createPaymentLink(any(), any())).thenThrow(new Exception("Stripe unavailable"))
                .thenReturn(link());

        assertThrows(Exception.class, () -> service.initiatePayment(request()));
        assertEquals(0, repository.count());

        PaymentInitiateResponse response = service.initiatePayment(request());
        assertEquals(1, repository.count());
        assertEquals("cs_1", repository.findById(response.getPaymentId()).orElseThrow().getCheckoutSessionId());
    }

    @Test
    void repeatedInitiationReturnsExistingCheckoutWithoutAnotherStripeSession() throws Exception {
        PaymentInitiateResponse first = service.initiatePayment(request());
        PaymentInitiateResponse second = service.initiatePayment(request());

        assertEquals(first.getPaymentId(), second.getPaymentId());
        assertEquals(first.getTransactionId(), second.getTransactionId());
        assertEquals(first.getCheckoutUrl(), second.getCheckoutUrl());
        assertEquals(1, repository.count());
        verify(stripe).createPaymentLink(any(), any());
    }

    @Test
    void concurrentInitiationsReturnOnePaymentAndCreateOneStripeSession() throws Exception {
        CyclicBarrier bothReadMissingPayment = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Optional<Payment> payment = repository.findByBookingIdIn(List.of(1L)).stream().findFirst();
            if (payment.isEmpty()) {
                bothReadMissingPayment.await(10, TimeUnit.SECONDS);
            }
            return payment;
        }).when(repository).findByBookingId(1L);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.initiatePayment(request()));
            var second = executor.submit(() -> service.initiatePayment(request()));
            PaymentInitiateResponse firstResponse = first.get(15, TimeUnit.SECONDS);
            PaymentInitiateResponse secondResponse = second.get(15, TimeUnit.SECONDS);

            assertEquals(firstResponse.getPaymentId(), secondResponse.getPaymentId());
            assertEquals(firstResponse.getCheckoutUrl(), secondResponse.getCheckoutUrl());
        }
        assertEquals(1, repository.count());
        verify(stripe).createPaymentLink(any(), any());
    }

    @Test
    void retryWithDifferentPaymentDetailsIsRejected() throws Exception {
        service.initiatePayment(request());
        PaymentInitiateRequest changedAmount = request();
        changedAmount.setAmount(1.0);
        assertThrows(ResponseStatusException.class, () -> service.initiatePayment(changedAmount));
        PaymentInitiateRequest changedUser = request();
        changedUser.setUserId(3L);
        assertThrows(ResponseStatusException.class, () -> service.initiatePayment(changedUser));
        assertEquals(1, repository.count());
        verify(stripe).createPaymentLink(any(), any());
    }

    @Test
    void completedPaymentCannotCreateAnotherCheckout() throws Exception {
        PaymentInitiateResponse response = service.initiatePayment(request());
        Payment payment = repository.findById(response.getPaymentId()).orElseThrow();
        payment.setStatus(PaymentStatus.SUCCESS);
        repository.saveAndFlush(payment);

        assertThrows(ResponseStatusException.class, () -> service.initiatePayment(request()));
        assertEquals(1, repository.count());
        verify(stripe).createPaymentLink(any(), any());
    }

    private PaymentInitiateRequest request() {
        return PaymentInitiateRequest.builder().bookingId(1L).userId(2L).amount(100.0)
                .gateway(PaymentGateway.STRIPE).description("Booking payment").build();
    }

    private PaymentLinkResponse link() {
        return new PaymentLinkResponse("https://checkout.stripe.com/test", "cs_1");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = PaymentRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:payment-initiation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Payment.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean StripeService stripeService() { return mock(StripeService.class); }
        @Bean UserClient userClient() { return mock(UserClient.class); }
        @Bean PaymentEventProducer paymentEventProducer() { return mock(PaymentEventProducer.class); }

        @Bean PaymentInitiationService paymentInitiationService(PaymentRepository repository,
                StripeService stripe, UserClient users) {
            return new PaymentInitiationService(repository, stripe, users);
        }

        @Bean PaymentServiceImpl paymentService(PaymentRepository repository, StripeService stripe,
                PaymentEventProducer events, PaymentInitiationService initiation) {
            return new PaymentServiceImpl(repository, stripe, events, initiation);
        }
    }
}
