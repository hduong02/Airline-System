package com.example.payment_service.service;

import com.example.enums.PaymentGateway;
import com.example.enums.PaymentStatus;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payload.response.PaymentLinkResponse;
import com.example.payment_service.client.UserClient;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.gateway.StripeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentInitiationService {
    private final PaymentRepository paymentRepository;
    private final StripeService stripeService;
    private final UserClient userClient;

    // A separate transaction lets the caller recover from a duplicate insert only
    // after that transaction has rolled back, including on checked gateway errors.
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request) throws Exception {
        Payment existing = paymentRepository.findByBookingId(request.getBookingId()).orElse(null);
        if (existing != null) {
            return resumePayment(existing, request);
        }

        Payment payment = Payment.builder()
                .userId(request.getUserId())
                .bookingId(request.getBookingId())
                .amount(request.getAmount())
                .provider(request.getGateway())
                .status(PaymentStatus.PENDING)
                .transactionId("TXN_" + System.currentTimeMillis() + "_"
                        + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();

        // Claim the unique booking ID before calling Stripe. Concurrent requests
        // must lose this insert before they can create another Checkout Session.
        payment = paymentRepository.saveAndFlush(payment);
        PaymentLinkResponse link = null;
        if (payment.getProvider() == PaymentGateway.STRIPE) {
            link = stripeService.createPaymentLink(userClient.getUserById(payment.getUserId()), payment);
            payment.setCheckoutSessionId(link.getPayment_link_id());
            paymentRepository.save(payment);
        }
        return toResponse(payment, request, link);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, rollbackFor = Exception.class)
    public Optional<PaymentInitiateResponse> findExistingPayment(PaymentInitiateRequest request)
            throws Exception {
        Payment payment = paymentRepository.findByBookingId(request.getBookingId()).orElse(null);
        return payment == null ? Optional.empty() : Optional.of(resumePayment(payment, request));
    }

    private PaymentInitiateResponse resumePayment(Payment payment, PaymentInitiateRequest request)
            throws Exception {
        if (!Objects.equals(payment.getUserId(), request.getUserId())
                || !Objects.equals(payment.getAmount(), request.getAmount())
                || payment.getProvider() != request.getGateway()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment request does not match the existing payment for this booking");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment is no longer pending for this booking");
        }
        PaymentLinkResponse link = null;
        if (payment.getProvider() == PaymentGateway.STRIPE) {
            if (payment.getCheckoutSessionId() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Existing payment has no Checkout Session; reconciliation is required");
            }
            link = stripeService.fetchCheckoutLink(payment.getCheckoutSessionId());
        }
        return toResponse(payment, request, link);
    }

    private PaymentInitiateResponse toResponse(Payment payment, PaymentInitiateRequest request,
            PaymentLinkResponse link) {
        return PaymentInitiateResponse.builder()
                .paymentId(payment.getId())
                .gateway(payment.getProvider())
                .transactionId(payment.getTransactionId())
                .amount(payment.getAmount())
                .description(request.getDescription())
                .stripeCheckoutUrl(link == null ? null : link.getPayment_link_id())
                .checkoutUrl(link == null ? null : link.getPayment_link_url())
                .success(true)
                .message("Payment initiated successfully")
                .build();
    }
}
