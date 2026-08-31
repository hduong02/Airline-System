package com.example.payment_service.service.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
                    // Stripe requires at least 30 minutes from Session creation. The
                    // booking job closes this session at the earlier seat-hold deadline.
                    .setExpiresAt(Instant.now().plus(31, ChronoUnit.MINUTES).getEpochSecond())
                    .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                            .putMetadata("user_id", String.valueOf(user.getId()))
                            .putMetadata("payment_id", String.valueOf(payment.getId()))
                            .putMetadata("booking_id", String.valueOf(payment.getBookingId()))
                            .build())
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

    public PaymentLinkResponse fetchCheckoutLink(String sessionId) throws Exception {
        if (!isConfigured())
            throw new Exception("stripe not configured. please setup api key");

        Session session = Session.retrieve(sessionId);
        PaymentLinkResponse response = new PaymentLinkResponse();
        response.setPayment_link_id(session.getId());
        response.setPayment_link_url(session.getUrl());
        return response;
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

    public CheckoutState reconcileCheckoutSession(String sessionId) throws StripeException {
        Session session = Session.retrieve(sessionId);
        if ("paid".equals(session.getPaymentStatus())) {
            return new CheckoutState(CheckoutOutcome.PAID, session.getPaymentIntent());
        }
        if ("expired".equals(session.getStatus())) {
            return new CheckoutState(CheckoutOutcome.EXPIRED, null);
        }
        if ("complete".equals(session.getStatus())) {
            return new CheckoutState(CheckoutOutcome.PROCESSING, null);
        }
        if (!"open".equals(session.getStatus())) {
            throw new IllegalStateException("Unknown Stripe Checkout status: " + session.getStatus());
        }
        try {
            session.expire();
            return new CheckoutState(CheckoutOutcome.EXPIRED, null);
        } catch (StripeException e) {
            // Checkout may have completed while the expire request was in flight.
            Session latest = Session.retrieve(sessionId);
            if ("paid".equals(latest.getPaymentStatus())) {
                return new CheckoutState(CheckoutOutcome.PAID, latest.getPaymentIntent());
            }
            if ("complete".equals(latest.getStatus())) {
                return new CheckoutState(CheckoutOutcome.PROCESSING, null);
            }
            if ("expired".equals(latest.getStatus())) {
                return new CheckoutState(CheckoutOutcome.EXPIRED, null);
            }
            throw e;
        }
    }

    public enum CheckoutOutcome { PAID, EXPIRED, PROCESSING }

    public record CheckoutState(CheckoutOutcome outcome, String paymentIntentId) {}

    public boolean isConfigured() {
        return stripeApiKey != null && !stripeApiKey.isEmpty();
    }
}
