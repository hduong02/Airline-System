package com.example.payment_service.model;

import com.example.enums.PaymentStatus;

/** Scalar projection: the initial read must not cache a managed Payment across the Stripe call. */
public record CheckoutVerificationSnapshot(Long paymentId, Long userId, PaymentStatus status,
        String checkoutSessionId) {
}
