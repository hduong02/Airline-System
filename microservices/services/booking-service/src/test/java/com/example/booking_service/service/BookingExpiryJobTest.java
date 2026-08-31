package com.example.booking_service.service;

import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.event.listener.PaymentEventListener;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.enums.PaymentStatus;
import com.example.event.PaymentCompletedEvent;
import com.example.payload.dto.PaymentDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BookingExpiryJobTest {
    private final BookingRepository repository = mock(BookingRepository.class);
    private final PaymentClient paymentClient = mock(PaymentClient.class);
    private final PaymentEventListener paymentListener = mock(PaymentEventListener.class);
    private final BookingExpiryService expiryService = mock(BookingExpiryService.class);
    private final BookingExpiryJob job = new BookingExpiryJob(
            repository, paymentClient, paymentListener, expiryService);

    @Test
    void cancelsOnlyWhenCheckoutCanNoLongerComplete() {
        when(repository.findExpiredBookingIds(eq(BookingStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(paymentClient.reconcileExpiredCheckout(1L)).thenReturn(payment(PaymentStatus.CANCELLED));
        when(paymentClient.reconcileExpiredCheckout(2L)).thenReturn(payment(PaymentStatus.PENDING));
        when(paymentClient.reconcileExpiredCheckout(3L)).thenThrow(new IllegalStateException("Stripe unavailable"));

        job.expireOverdueBookings();

        verify(expiryService).cancelExpiredBooking(1L);
        verify(expiryService, never()).cancelExpiredBooking(2L);
        verify(expiryService, never()).cancelExpiredBooking(3L);
        verifyNoInteractions(paymentListener);
    }

    @Test
    void successfulPaymentAtDeadlineConfirmsInsteadOfCancelling() {
        when(repository.findExpiredBookingIds(eq(BookingStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(1L));
        PaymentDto payment = payment(PaymentStatus.SUCCESS);
        payment.setId(9L);
        payment.setAmount(200.0);
        when(paymentClient.reconcileExpiredCheckout(1L)).thenReturn(payment);

        job.expireOverdueBookings();

        ArgumentCaptor<PaymentCompletedEvent> event = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(paymentListener).handlePaymentCompleted(event.capture());
        assertEquals(1L, event.getValue().getBookingId());
        assertEquals(9L, event.getValue().getPaymentId());
        verifyNoInteractions(expiryService);
    }

    private PaymentDto payment(PaymentStatus status) {
        PaymentDto payment = new PaymentDto();
        payment.setStatus(status);
        return payment;
    }
}
