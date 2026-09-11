package com.example.payment_service.service.gateway;

import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripeCheckoutStateTest {
    @Test
    void checkoutStatusReadsStripeWithoutExpiringOpenOrProcessingSessions() throws Exception {
        StripeService service = new StripeService();
        ReflectionTestUtils.setField(service, "stripeApiKey", "fixture-key");
        Session session = mock(Session.class);
        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.retrieve("cs_saved")).thenReturn(session);
            when(session.getPaymentStatus()).thenReturn("unpaid");
            when(session.getStatus()).thenReturn("open");
            assertEquals(StripeService.CheckoutOutcome.PROCESSING, service.fetchCheckoutState("cs_saved").outcome());
            when(session.getStatus()).thenReturn("complete");
            assertEquals(StripeService.CheckoutOutcome.PROCESSING, service.fetchCheckoutState("cs_saved").outcome());
            when(session.getStatus()).thenReturn("expired");
            assertEquals(StripeService.CheckoutOutcome.EXPIRED, service.fetchCheckoutState("cs_saved").outcome());
            when(session.getPaymentStatus()).thenReturn("paid");
            when(session.getPaymentIntent()).thenReturn("pi_paid");
            assertEquals(new StripeService.CheckoutState(StripeService.CheckoutOutcome.PAID, "pi_paid"),
                    service.fetchCheckoutState("cs_saved"));
            verify(session, never()).expire();
        }
    }
}
