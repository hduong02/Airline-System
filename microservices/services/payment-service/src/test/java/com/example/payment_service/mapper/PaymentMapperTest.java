package com.example.payment_service.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.payment_service.model.Payment;

class PaymentMapperTest {

    @Test
    void keepsFractionalPaymentAmount() {
        Payment payment = Payment.builder().amount(245.75).build();

        assertEquals(245.75, PaymentMapper.toDto(payment).getAmount());
    }
}
