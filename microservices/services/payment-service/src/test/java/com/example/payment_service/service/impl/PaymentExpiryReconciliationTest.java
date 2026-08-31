package com.example.payment_service.service.impl;

import com.example.enums.PaymentStatus;
import com.example.payment_service.service.PaymentInitiationService;
import com.example.payment_service.event.PaymentEventProducer;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.gateway.StripeService;
import com.example.payment_service.service.gateway.StripeService.CheckoutOutcome;
import com.example.payment_service.service.gateway.StripeService.CheckoutState;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentExpiryReconciliationTest {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final StripeService stripe = mock(StripeService.class);
    private final PaymentEventProducer events = mock(PaymentEventProducer.class);
    private final PaymentInitiationService initiation = mock(PaymentInitiationService.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(repository, stripe, events, initiation);

    @Test
    void paidCheckoutWinsExpiryAndPublishesSuccessOnlyOnce() throws Exception {
        Payment payment = pendingPayment();
        when(repository.findByBookingIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(repository.save(payment)).thenReturn(payment);
        when(stripe.reconcileCheckoutSession("cs_1"))
                .thenReturn(new CheckoutState(CheckoutOutcome.PAID, "pi_1"));

        assertEquals(PaymentStatus.SUCCESS, service.reconcileExpiredCheckout(1L).getStatus());
        assertEquals(PaymentStatus.SUCCESS, service.reconcileExpiredCheckout(1L).getStatus());

        assertEquals("pi_1", payment.getProviderPaymentId());
        verify(events).sendPaymentCompleted(payment);
        verify(stripe).reconcileCheckoutSession("cs_1");
    }

    @Test
    void expiredCheckoutIsCancelledWithoutPublishingPaymentFailure() throws Exception {
        Payment payment = pendingPayment();
        when(repository.findByBookingIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(repository.save(payment)).thenReturn(payment);
        when(stripe.reconcileCheckoutSession("cs_1"))
                .thenReturn(new CheckoutState(CheckoutOutcome.EXPIRED, null));

        assertEquals(PaymentStatus.CANCELLED, service.reconcileExpiredCheckout(1L).getStatus());
        verifyNoInteractions(events);
    }

    @Test
    void stripeFailureLeavesPaymentPendingForRetry() throws Exception {
        Payment payment = pendingPayment();
        when(repository.findByBookingIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(stripe.reconcileCheckoutSession("cs_1"))
                .thenThrow(new IllegalStateException("Stripe unavailable"));

        assertThrows(IllegalStateException.class, () -> service.reconcileExpiredCheckout(1L));
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verify(repository, never()).save(payment);
        verifyNoInteractions(events);
    }

    @Test
    void lateVerificationCannotReviveExpiredPayment() throws Exception {
        Payment payment = pendingPayment();
        payment.setStatus(PaymentStatus.CANCELLED);
        when(stripe.fetchPaymentDetails("pi_1")).thenReturn(new JSONObject()
                .put("status", "succeeded")
                .put("metadata", new JSONObject().put("payment_id", "9")));
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(payment));

        var request = new com.example.payload.request.PaymentVerifyRequest();
        request.setStripePaymentIntentId("pi_1");
        assertThrows(IllegalStateException.class, () -> service.verifyPayment(request));
        verifyNoInteractions(events);
    }

    private Payment pendingPayment() {
        return Payment.builder().id(9L).bookingId(1L).userId(2L).amount(100.0)
                .checkoutSessionId("cs_1").status(PaymentStatus.PENDING).build();
    }
}
