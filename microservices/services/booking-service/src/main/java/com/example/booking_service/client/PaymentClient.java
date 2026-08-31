package com.example.booking_service.client;

import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import com.example.payload.dto.PaymentDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;

import java.util.List;
import java.util.Map;

@FeignClient(name = "payment-service")
public interface PaymentClient {

    @PostMapping("/internal/payments/initiate")
    PaymentInitiateResponse initiatePayment(
            @Valid @RequestBody PaymentInitiateRequest request,
            @RequestHeader("X-User-Id") Long userId);

    @GetMapping("/api/payments/booking/{bookingId}")
    PaymentDto getPaymentByBookingId(@PathVariable Long bookingId);

    @PostMapping("/api/payments/batch/bookings")
    Map<Long, PaymentDto> getPaymentsByBookingIds(@RequestBody List<Long> bookingIds);

    @PostMapping("/internal/payments/booking/{bookingId}/reconcile-expiry")
    PaymentDto reconcileExpiredCheckout(@PathVariable Long bookingId);
}
