package com.example.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import com.example.enums.PaymentGateway;
import com.example.enums.PaymentStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentDto {

    private Long id;
    private Long userId;
    private String userName;
    private String userEmail;

    private Long bookingId;
    private PaymentStatus status;
    private PaymentGateway gateway;
    private Double amount;
    private String transactionId;
    private String gatewayPaymentId;
    private String gatewayOrderId;
    private String gatewaySignature;

    private String description;
    private String failureReason;
    
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
    private Boolean notificationSent;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
