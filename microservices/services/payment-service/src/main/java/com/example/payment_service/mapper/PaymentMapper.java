package com.example.payment_service.mapper;

import java.time.ZoneId;

import com.example.payload.dto.PaymentDto;
import com.example.payment_service.model.Payment;

public class PaymentMapper {

    public static PaymentDto toDto(Payment payment) {
        if (payment == null) return null;

        PaymentDto dto = new PaymentDto();
        dto.setId(payment.getId());
        dto.setGateway(payment.getProvider());
        dto.setAmount(payment.getAmount() != null ? payment.getAmount().longValue() : null);
        dto.setTransactionId(payment.getTransactionId());
        dto.setStatus(payment.getStatus());
        dto.setUserId(payment.getUserId());
        dto.setBookingId(payment.getBookingId());

        if (payment.getPaidAt() != null) {
            dto.setCompletedAt(payment.getPaidAt()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime());
        }

        if (payment.getCreatedAt() != null) {
            dto.setCreatedAt(payment.getCreatedAt()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime());
            dto.setInitiatedAt(payment.getCreatedAt()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime());
        }

        if (payment.getUpdatedAt() != null) {
            dto.setUpdatedAt(payment.getUpdatedAt()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime());
        }

        return dto;
    }
}