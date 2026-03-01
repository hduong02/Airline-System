package com.example.payment_service.service.gateway;

import java.math.BigDecimal;

import com.example.payload.dto.UserDto;
import com.example.payload.response.PaymentLinkResponse;
import com.example.payment_service.model.Payment;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.model.PaymentIntent;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StripeService {

    @Value("${stripe.api.key}")
    private String stripeApiKey;

    @Value("${stripe.callback.base-url}")
    private String callbackBaseUrl;

    @PostConstruct
    public void init() {
        if (isConfigured()) {
            Stripe.apiKey = stripeApiKey;
        }
    }

    public PaymentLinkResponse createPaymentLink(UserDto user,
            Payment payment) throws Exception {

        if (!isConfigured())
            throw new Exception("stripe not configured. please setup api key");

        try {
            // Convert amount to smallest currency unit (cents for USD)
            BigDecimal amount = BigDecimal.valueOf(payment.getAmount());
            Long amountInSmallestUnit = amount.multiply(new BigDecimal("100")).longValue();

            String successUrl = callbackBaseUrl + "/booking-success/" + payment.getBookingId()
                    + "?session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl = callbackBaseUrl + "/payment-cancelled/" + payment.getId();

            SessionCreateParams.LineItem.PriceData.ProductData productData =
                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName(payment.getTransactionId())
                            .build();

            SessionCreateParams.LineItem.PriceData priceData =
                    SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("usd")
                            .setUnitAmount(amountInSmallestUnit)
                            .setProductData(productData)
                            .build();

            SessionCreateParams.LineItem lineItem =
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(priceData)
                            .build();

            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addLineItem(lineItem)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setCustomerEmail(user.getEmail())
                    // Enable Stripe's built-in email receipts / reminders
                    .putMetadata("user_id", String.valueOf(user.getId()))
                    .putMetadata("payment_id", String.valueOf(payment.getId()))
                    .putMetadata("booking_id", String.valueOf(payment.getBookingId()));

            if (user.getPhone() != null)
                paramsBuilder.putMetadata("contact", user.getPhone());

            SessionCreateParams params = paramsBuilder.build();

            Session session = Session.create(params);

            PaymentLinkResponse response = new PaymentLinkResponse();
            response.setPayment_link_url(session.getUrl());
            response.setPayment_link_id(session.getId());
            return response;

        } catch (StripeException e) {
            throw new Exception("Failed to create payment link: " + e.getMessage());
        }
    }

    public JSONObject fetchPaymentDetails(String paymentIntentId) throws Exception {
        if (!isConfigured())
            throw new Exception("stripe not configured. please setup api key");
        
        try {
            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            return new JSONObject(paymentIntent.toJson());
        } catch (StripeException e) {
            throw new Exception("Failed to fetch payment details: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return stripeApiKey != null && !stripeApiKey.isEmpty();
    }
}
