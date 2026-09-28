package com.example.booking_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingReservationRecoveryJob {
    private final BookingReservationService reservations;
    private final IdempotentBookingService checkout;

    @Scheduled(fixedDelayString = "${booking.reservation.recovery-delay-ms:30000}")
    public void recoverReservations() {
        for (Long attemptId : reservations.recoverableAttemptIds()) {
            try {
                checkout.recoverReservationAndCheckout(attemptId);
            } catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode().value() != 409) {
                    log.warn("Could not recover reservation attempt {}; will retry", attemptId, conflict);
                }
            } catch (Exception failure) {
                log.warn("Could not recover reservation attempt {}; will retry", attemptId, failure);
            }
        }
    }
}
