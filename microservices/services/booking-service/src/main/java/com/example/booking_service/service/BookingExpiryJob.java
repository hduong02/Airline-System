package com.example.booking_service.service;

import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.event.listener.PaymentEventListener;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.enums.PaymentStatus;
import com.example.event.PaymentCompletedEvent;
import com.example.payload.dto.PaymentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingExpiryJob {
    private final BookingRepository bookingRepository;
    private final PaymentClient paymentClient;
    private final PaymentEventListener paymentEventListener;
    private final BookingExpiryService expiryService;

    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    public void expireOverdueBookings() {
        for (Long bookingId : bookingRepository.findExpiredBookingIds(BookingStatus.PENDING, Instant.now())) {
            try {
                PaymentDto payment = paymentClient.reconcileExpiredCheckout(bookingId);
                if (payment == null || payment.getStatus() == null) {
                    continue;
                }
                if (payment.getStatus() == PaymentStatus.SUCCESS) {
                    paymentEventListener.handlePaymentCompleted(PaymentCompletedEvent.builder()
                            .bookingId(bookingId)
                            .paymentId(payment.getId())
                            .userId(payment.getUserId())
                            .amount(payment.getAmount())
                            .transactionId(payment.getTransactionId())
                            .providerPaymentId(payment.getGatewayPaymentId())
                            .paidAt(payment.getCompletedAt())
                            .build());
                } else if (payment.getStatus() == PaymentStatus.CANCELLED
                        || payment.getStatus() == PaymentStatus.FAILED) {
                    expiryService.cancelExpiredBooking(bookingId);
                }
            } catch (Exception e) {
                log.warn("Could not reconcile expired booking {}; will retry", bookingId, e);
            }
        }
    }
}
