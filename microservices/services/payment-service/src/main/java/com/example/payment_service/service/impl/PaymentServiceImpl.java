package com.example.payment_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.example.enums.PaymentGateway;
import com.example.enums.PaymentStatus;
import com.example.payload.dto.PaymentDto;
import com.example.payload.dto.UserDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.request.PaymentVerifyRequest;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payload.response.PaymentLinkResponse;
import com.example.payment_service.mapper.PaymentMapper;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.gateway.StripeService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final StripeService stripeService;

    @Override
    public PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request)
            throws Exception {
        try {
            // Check if payment already exists for this booking
            paymentRepository.findByBookingId(request.getBookingId())
                    .ifPresent(existingPayment -> {
                        if (existingPayment.getStatus() == PaymentStatus.SUCCESS) {
                            throw new RuntimeException(
                                    "Payment already completed for this booking");
                        }
                    });

            // Create payment entity
            Payment payment = Payment.builder()
                    .userId(request.getUserId())
                    .bookingId(request.getBookingId())
                    .amount(request.getAmount())
                    .provider(request.getGateway())
                    .status(PaymentStatus.PENDING)
                    .transactionId(generateTransactionId())
                    .build();

            payment = paymentRepository.save(payment);

            // Create response based on gateway
            PaymentInitiateResponse response = PaymentInitiateResponse.builder()
                    .paymentId(payment.getId())
                    .gateway(request.getGateway())
                    .transactionId(payment.getTransactionId())
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .success(true)
                    .message("Payment initiated successfully")
                    .build();

            if (request.getGateway() == PaymentGateway.STRIPE) {
                UserDto userDto = new UserDto();
                userDto.setId(1L);
                userDto.setFullName("Andy Nguyen");
                userDto.setEmail("hoangduongvd99@gmail.com");
                userDto.setPhone("7865437896");

                // create stripe payment link using stripe service
                PaymentLinkResponse paymentLinkResponse = stripeService.createPaymentLink(
                        userDto, payment
                );
                // set payment link to payment initiate response
                response.setStripeCheckoutUrl(paymentLinkResponse.getPayment_link_id());
                response.setCheckoutUrl(paymentLinkResponse.getPayment_link_url());
            }

            return response;

        } catch (Exception e) {
            throw new Exception("Failed to initiate payment: " + e.getMessage());
        }
    }

    @Override
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
            if (metadata == null) {
                throw new Exception("Stripe PaymentIntent missing metadata");
            }
            paymentId = Long.parseLong(metadata.optString("payment_id"));

            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new Exception(
                            "Payment not found with ID: " + paymentId));

            if (isValid) {
                payment.setProviderPaymentId(request.getStripePaymentIntentId());
            }

            return saveAndPublish(payment, isValid, status);

        } else {
            throw new Exception("No payment method provided");
        }
    }

    /** Persists the verified payment and publishes the corresponding event. */
    private PaymentDto saveAndPublish(Payment payment, boolean isValid, String status) {
        if (isValid) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(LocalDateTime.now());
            payment = paymentRepository.save(payment);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Payment verification failed");
            payment = paymentRepository.save(payment);
        }

        return PaymentMapper.toDto(payment);
    }

    @Override
    public Page<PaymentDto> getAllPayments(Pageable pageable) {
        return paymentRepository.findAll(pageable)
                .map(PaymentMapper::toDto);
    }

    @Override
    public Map<Long, PaymentDto> getPaymentsByBookingIds(List<Long> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty())
            return Map.of();
        return paymentRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(Payment::getBookingId, PaymentMapper::toDto));
    }

    private String generateTransactionId() {
        return "TXN_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
