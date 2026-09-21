package com.example.payment_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.enums.PaymentStatus;
import com.example.enums.UserRole;
import com.example.payload.dto.PaymentDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.request.PaymentVerifyRequest;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payment_service.event.PaymentEventProducer;
import com.example.payment_service.mapper.PaymentMapper;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.PaymentInitiationService;
import com.example.payment_service.service.gateway.StripeService;
import com.example.payment_service.service.gateway.StripeService.CheckoutOutcome;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final StripeService stripeService;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentInitiationService paymentInitiationService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request)
            throws Exception {
        return paymentInitiationService.initiatePayment(request);
    }

    @Override
    @Transactional
    public PaymentDto verifyPayment(PaymentVerifyRequest request) throws Exception {

        JSONObject paymentDetails;
        String status;
        Long paymentId;
        boolean isValid;

        if (request.getStripePaymentIntentId() != null
                && !request.getStripePaymentIntentId().isBlank()) {
            
            paymentDetails = stripeService.fetchPaymentDetails(
                    request.getStripePaymentIntentId());

            // Stripe PaymentIntent status is "succeeded" when captured
            status = paymentDetails.optString("status");
            isValid = "succeeded".equalsIgnoreCase(status);

            // payment_id is stored in metadata during checkout session creation
            JSONObject metadata = paymentDetails.optJSONObject("metadata");
            if (metadata == null)
                throw new Exception("Stripe PaymentIntent missing metadata");
            
            paymentId = Long.parseLong(metadata.optString("payment_id"));

            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new Exception(
                            "Payment not found with ID: " + paymentId));

            if (payment.getStatus() == PaymentStatus.SUCCESS) {
                return PaymentMapper.toDto(payment);
            }
            if (payment.getStatus() != PaymentStatus.PENDING) {
                throw new IllegalStateException("Payment is no longer pending: " + paymentId);
            }

            switch (status) {
                case "requires_payment_method", "requires_confirmation", "requires_action",
                        "processing", "requires_capture" -> {
                    return PaymentMapper.toDto(payment);
                }
                case "succeeded", "canceled" -> { }
                default -> throw new IllegalStateException("Unknown Stripe PaymentIntent status: " + status);
            }

            if (isValid)
                payment.setProviderPaymentId(request.getStripePaymentIntentId());

            return saveAndPublish(payment, isValid, status);
        } else {
            throw new Exception("No payment method provided");
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentDto verifyCheckoutPayment(Long bookingId, Long userId) throws Exception {
        var read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        read.setReadOnly(true);
        var snapshot = Objects.requireNonNull(read.execute(status ->
                paymentRepository.findCheckoutVerificationSnapshot(bookingId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"))));
        if (userId == null || !userId.equals(snapshot.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payment belongs to another user");
        }
        if (snapshot.status() != PaymentStatus.PENDING) {
            return read.execute(status -> {
                Payment payment = paymentRepository.findById(snapshot.paymentId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
                if (!userId.equals(payment.getUserId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payment belongs to another user");
                }
                return PaymentMapper.toDto(payment);
            });
        }
        if (snapshot.checkoutSessionId() == null || snapshot.checkoutSessionId().isBlank()) {
            throw new IllegalStateException("Checkout Session is missing for booking: " + bookingId);
        }
        // Read only: returning to the site must never expire an unpaid checkout.
        var checkout = stripeService.fetchCheckoutState(snapshot.checkoutSessionId());
        var write = new TransactionTemplate(transactionManager);
        write.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return write.execute(status -> {
            Payment payment = paymentRepository.findByBookingIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
            if (!userId.equals(payment.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payment belongs to another user");
            }
            // A concurrent verification or expiry may have completed while Stripe was in flight.
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return PaymentMapper.toDto(payment);
            }
            if (!snapshot.paymentId().equals(payment.getId())
                    || !snapshot.checkoutSessionId().equals(payment.getCheckoutSessionId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout Session changed during verification");
            }
            if (checkout.outcome() == CheckoutOutcome.PAID) {
                if (checkout.paymentIntentId() == null || checkout.paymentIntentId().isBlank()) {
                    throw new IllegalStateException("Paid Checkout Session has no PaymentIntent");
                }
                payment.setProviderPaymentId(checkout.paymentIntentId());
                return saveAndPublish(payment, true, "succeeded");
            }
            return PaymentMapper.toDto(payment);
        });
    }

    @Override
    @Transactional
    public PaymentDto reconcileExpiredCheckout(Long bookingId) throws Exception {
        Payment payment = paymentRepository.findByBookingIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment found for booking: " + bookingId));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return PaymentMapper.toDto(payment);
        }
        if (payment.getCheckoutSessionId() == null) {
            throw new IllegalStateException("Checkout Session is missing for booking: " + bookingId);
        }

        var checkout = stripeService.reconcileCheckoutSession(payment.getCheckoutSessionId());
        if (checkout.outcome() == CheckoutOutcome.PAID) {
            payment.setProviderPaymentId(checkout.paymentIntentId());
            return saveAndPublish(payment, true, "succeeded");
        }
        if (checkout.outcome() == CheckoutOutcome.EXPIRED) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setFailureReason("Checkout expired before payment");
            return PaymentMapper.toDto(paymentRepository.save(payment));
        }
        return PaymentMapper.toDto(payment);
    }

    // Persists the verified payment and publishes the corresponding event
    private PaymentDto saveAndPublish(Payment payment, boolean isValid, String status) {
        if (isValid) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(LocalDateTime.now());
            payment = paymentRepository.save(payment);

            // publish payment completed event
            paymentEventProducer.sendPaymentCompleted(payment);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Stripe PaymentIntent " + status);
            payment = paymentRepository.save(payment);

            // publish payment failed event
            paymentEventProducer.sendPaymentFailed(payment);
        }

        return PaymentMapper.toDto(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentDto> getAllPayments(Pageable pageable, String roles) {
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim)
                .noneMatch(UserRole.ROLE_SYSTEM_ADMIN.name()::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System admin role is required");
        }
        return paymentRepository.findAll(pageable)
                .map(PaymentMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PaymentDto> getPaymentsByBookingIdsForUser(List<Long> bookingIds, Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated user is required");
        }
        if (bookingIds == null || bookingIds.isEmpty())
            return Map.of();
        return paymentRepository.findByBookingIdInAndUserId(bookingIds, userId).stream()
                .collect(Collectors.toMap(Payment::getBookingId, PaymentMapper::toDto));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PaymentDto> getPaymentsByBookingIds(List<Long> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty())
            return Map.of();
        return paymentRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(Payment::getBookingId, PaymentMapper::toDto));
    }

}
