package com.example.payment_service.controller;

import com.example.payload.dto.PaymentDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {
    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public PaymentInitiateResponse initiatePayment(@Valid @RequestBody PaymentInitiateRequest request)
            throws Exception {
        return paymentService.initiatePayment(request);
    }

    @PostMapping("/booking/{bookingId}/reconcile-expiry")
    public PaymentDto reconcileExpiredCheckout(@PathVariable Long bookingId) throws Exception {
        return paymentService.reconcileExpiredCheckout(bookingId);
    }
}
