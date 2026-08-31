package com.example.payment_service.service.impl;

import com.example.enums.PaymentStatus;
import com.example.payload.request.PaymentVerifyRequest;
import com.example.payment_service.event.PaymentEventProducer;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentInitiationService;
import com.example.payment_service.service.gateway.StripeService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentVerificationTest {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final StripeService stripe = mock(StripeService.class);
    private final PaymentEventProducer events = mock(PaymentEventProducer.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(repository, stripe, events,
            mock(PaymentInitiationService.class));

    @ParameterizedTest
    @ValueSource(strings = {"requires_payment_method", "requires_confirmation", "requires_action",
            "processing", "requires_capture"})
    void nonterminalPaymentRemainsPendingAndCanSucceedLater(String status) throws Exception {
        Payment payment = payment();
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(payment));
        when(stripe.fetchPaymentDetails("pi_1")).thenReturn(details(status), details("succeeded"));

        assertEquals(PaymentStatus.PENDING, service.verifyPayment(request()).getStatus());
        verify(repository, never()).save(any());
        verifyNoInteractions(events);

        when(repository.save(payment)).thenReturn(payment);
        assertEquals(PaymentStatus.SUCCESS, service.verifyPayment(request()).getStatus());
        assertEquals("pi_1", payment.getProviderPaymentId());
        verify(events).sendPaymentCompleted(payment);
        verify(events, never()).sendPaymentFailed(any());
    }

    @Test
    void canceledPaymentPublishesTerminalFailure() throws Exception {
        Payment payment = payment();
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(payment));
        when(repository.save(payment)).thenReturn(payment);
        when(stripe.fetchPaymentDetails("pi_1")).thenReturn(details("canceled"));

        assertEquals(PaymentStatus.FAILED, service.verifyPayment(request()).getStatus());
        verify(events).sendPaymentFailed(payment);
        verify(events, never()).sendPaymentCompleted(any());
    }

    @Test
    void unknownStripeStatusDoesNotChangePaymentOrPublishFailure() throws Exception {
        Payment payment = payment();
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(payment));
        when(stripe.fetchPaymentDetails("pi_1")).thenReturn(details("unknown"));

        assertThrows(IllegalStateException.class, () -> service.verifyPayment(request()));
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    private JSONObject details(String status) throws Exception {
        return new JSONObject().put("status", status)
                .put("metadata", new JSONObject().put("payment_id", "9"));
    }

    private Payment payment() {
        return Payment.builder().id(9L).bookingId(1L).userId(2L).amount(100.0)
                .status(PaymentStatus.PENDING).build();
    }

    private PaymentVerifyRequest request() {
        PaymentVerifyRequest request = new PaymentVerifyRequest();
        request.setStripePaymentIntentId("pi_1");
        return request;
    }
}
