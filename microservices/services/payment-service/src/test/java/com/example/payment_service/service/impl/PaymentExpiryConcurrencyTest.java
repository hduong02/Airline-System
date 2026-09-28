package com.example.payment_service.service.impl;

import com.example.enums.PaymentStatus;
import com.example.payment_service.event.PaymentEventProducer;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.gateway.StripeService;
import com.example.payment_service.service.gateway.StripeService.CheckoutOutcome;
import com.example.payment_service.service.gateway.StripeService.CheckoutState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(PaymentInitiationIntegrationTest.Config.class)
class PaymentExpiryConcurrencyTest {
    @Autowired PaymentService service;
    @Autowired PaymentRepository repository;
    @Autowired StripeService stripe;
    @Autowired PaymentEventProducer events;

    @BeforeEach
    void prepare() {
        reset(stripe, events);
        repository.deleteAll();
        repository.saveAndFlush(Payment.builder().bookingId(1L).userId(2L).amount(100.0)
                .checkoutSessionId("cs_1").status(PaymentStatus.PENDING).build());
    }

    @Test
    void verificationFinishesWhileExpiryIsWaitingForStripe() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(stripe.reconcileCheckoutSession("cs_1")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new CheckoutState(CheckoutOutcome.EXPIRED, null);
        });
        when(stripe.fetchCheckoutState("cs_1"))
                .thenReturn(new CheckoutState(CheckoutOutcome.PAID, "pi_1"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiry = executor.submit(() -> service.reconcileExpiredCheckout(1L));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var verification = executor.submit(() -> service.verifyCheckoutPayment(1L, 2L));
                assertEquals(PaymentStatus.SUCCESS, verification.get(1, TimeUnit.SECONDS).getStatus());
            } finally {
                release.countDown();
                expiry.get(3, TimeUnit.SECONDS);
            }
        }
        assertEquals(PaymentStatus.SUCCESS, repository.findByBookingId(1L).orElseThrow().getStatus());
        verify(events).sendPaymentCompleted(any());
        verify(events, never()).sendPaymentFailed(any());
    }

    @Test
    void verificationCannotRevivePaymentWhenExpiryCommitsFirst() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(stripe.fetchCheckoutState("cs_1")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new CheckoutState(CheckoutOutcome.PROCESSING, null);
        });
        when(stripe.reconcileCheckoutSession("cs_1"))
                .thenReturn(new CheckoutState(CheckoutOutcome.EXPIRED, null));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var verification = executor.submit(() -> service.verifyCheckoutPayment(1L, 2L));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(PaymentStatus.CANCELLED, service.reconcileExpiredCheckout(1L).getStatus());
            } finally {
                release.countDown();
            }
            assertEquals(PaymentStatus.CANCELLED, verification.get(3, TimeUnit.SECONDS).getStatus());
        }
        verifyNoInteractions(events);
    }

    @Test
    void concurrentPaidReconciliationsPublishSuccessOnlyOnce() throws Exception {
        var bothEntered = new CountDownLatch(2);
        when(stripe.reconcileCheckoutSession("cs_1")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            bothEntered.countDown();
            assertTrue(bothEntered.await(3, TimeUnit.SECONDS));
            return new CheckoutState(CheckoutOutcome.PAID, "pi_1");
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.reconcileExpiredCheckout(1L));
            var second = executor.submit(() -> service.reconcileExpiredCheckout(1L));
            assertEquals(PaymentStatus.SUCCESS, first.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(PaymentStatus.SUCCESS, second.get(5, TimeUnit.SECONDS).getStatus());
        }
        verify(events).sendPaymentCompleted(any());
        verify(events, never()).sendPaymentFailed(any());
    }
}
