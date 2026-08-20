package com.example.seat_service.event;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.event.BookingCancelledEvent;
import com.example.event.BookingConfirmedEvent;
import com.example.seat_service.service.SeatInstanceService;

@Service
@RequiredArgsConstructor
public class PaymentEventListener {

    private final SeatInstanceService seatInstanceService;
    @KafkaListener(topics = "booking.confirmed", groupId = "seat-service-group")
    public void handleBookingConfirmed(BookingConfirmedEvent event) {
        seatInstanceService.confirmBookingSeats(event.getBookingId(), event.getSeatInstanceIds());
    }

    @KafkaListener(topics = "booking.cancelled", groupId = "seat-service-group")
    public void handleBookingCancelled(BookingCancelledEvent event) {
        seatInstanceService.releaseBookingSeats(event.getBookingId(), event.getSeatInstanceIds());
    }
}
