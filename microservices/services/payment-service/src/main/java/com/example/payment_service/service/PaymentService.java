package com.example.payment_service.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.payload.dto.PaymentDto;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.request.PaymentVerifyRequest;
import com.example.payload.response.PaymentInitiateResponse;

import java.util.List;
import java.util.Map;

public interface PaymentService {

    PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request) throws Exception;

    PaymentDto verifyPayment(PaymentVerifyRequest request) throws Exception;

    Page<PaymentDto> getAllPayments(Pageable pageable);

    Map<Long, PaymentDto> getPaymentsByBookingIds(List<Long> bookingIds);
}