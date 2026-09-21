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
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentInitiationService {
    private final PaymentRepository paymentRepository;
    private final StripeService stripeService;
    private final UserClient userClient;
    private final TransactionTemplate transactions;

    public PaymentInitiationService(PaymentRepository paymentRepository, StripeService stripeService,
            UserClient userClient, PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.stripeService = stripeService;
        this.userClient = userClient;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request) throws Exception {
        Payment payment;
        try {
            payment = transactions.execute(status -> findOrCreatePayment(request));
        } catch (DataIntegrityViolationException e) {
            // The unique booking claim lost. Read the winner only after rollback.
            payment = transactions.execute(status -> paymentRepository.findByBookingId(request.getBookingId())
                    .orElseThrow(() -> e));
        }
        return resumePayment(Objects.requireNonNull(payment), request);
    }

    private Payment findOrCreatePayment(PaymentInitiateRequest request) {
        Payment existing = paymentRepository.findByBookingId(request.getBookingId()).orElse(null);
        if (existing != null) {
            return existing;
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

        // Commit the booking claim before contacting either upstream service.
        return paymentRepository.saveAndFlush(payment);
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
                // Keep the committed claim on errors: a timeout may hide a successful
                // Stripe creation. Retries use this payment's stable idempotency key.
                link = stripeService.createPaymentLink(userClient.getUserById(payment.getUserId()), payment);
                String sessionId = link.getPayment_link_id();
                Long paymentId = payment.getId();
                payment = transactions.execute(status -> {
                    Payment current = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
                    if (current.getCheckoutSessionId() != null
                            && !current.getCheckoutSessionId().equals(sessionId)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout Session does not match");
                    }
                    current.setCheckoutSessionId(sessionId);
                    return paymentRepository.save(current);
                });
            } else {
                link = stripeService.fetchCheckoutLink(payment.getCheckoutSessionId());
            }
        }
        return toResponse(payment, request, link);
    }

    private PaymentInitiateResponse toResponse(Payment payment, PaymentInitiateRequest request,
            PaymentLinkResponse link) {
        return PaymentInitiateResponse.builder()
                .paymentId(payment.getId())
                .bookingId(payment.getBookingId())
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
