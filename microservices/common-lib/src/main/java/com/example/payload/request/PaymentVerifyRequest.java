package com.example.payload.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentVerifyRequest {

    // Razorpay specific fields
    private String razorpayPaymentId;

    // Stripe specific fields
    private String stripePaymentIntentId;

}
