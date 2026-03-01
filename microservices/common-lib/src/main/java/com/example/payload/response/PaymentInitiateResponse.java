package com.example.payload.response;

import com.example.enums.PaymentGateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentInitiateResponse {

    private Long paymentId;
    private PaymentGateway gateway;
    private String transactionId;

    // Razorpay specific fields
    private String razorpayOrderId;

    private Double amount;

    private String description;

    // Frontend should redirect user to this URL for payment
    private String checkoutUrl;

    private String stripeCheckoutUrl;
    
    private String message;
    private Boolean success;
}