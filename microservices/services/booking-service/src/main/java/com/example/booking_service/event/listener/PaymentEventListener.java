package com.example.booking_service.event.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.event.PaymentCompletedEvent;
import com.example.event.PaymentFailedEvent;


@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final BookingRepository bookingRepository;

    @KafkaListener(topics = "payment.completed", groupId = "booking-service-group")
    public void handlePaymentCompleted(PaymentCompletedEvent event) {

        Booking booking = bookingRepository.findById(event.getBookingId())
                .orElse(null);

        if (booking == null)
            return;

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPaymentId(event.getPaymentId());
        booking = bookingRepository.save(booking);

    }

    @KafkaListener(topics = "payment.failed", groupId = "booking-service-group")
    public void handlePaymentFailed(PaymentFailedEvent event) {

        Booking booking = bookingRepository.findById(event.getBookingId()).orElse(null);
        if (booking == null)
            return;

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);
    }
}