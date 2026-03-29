package com.example.seat_service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.enums.SeatAvailabilityStatus;
import com.example.event.PaymentCompletedEvent;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.SeatInstanceResponse;
import com.example.seat_service.client.BookingClient;
import com.example.seat_service.service.SeatInstanceService;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final SeatInstanceService seatInstanceService;
    private final BookingClient bookingClient;

    @KafkaListener(topics = "booking.confirmed", groupId = "seat-service-group")
    public void handleBookingConfirmed(PaymentCompletedEvent event) throws Exception {
        
        BookingResponse bookingResponse = bookingClient.getBookingById(event.getBookingId());

        List<SeatInstanceResponse> seatInstances = bookingResponse.getSeatInstances();

        for (SeatInstanceResponse seatInstance : seatInstances) {
            seatInstanceService.updateSeatInstanceStatus(
                    seatInstance.getId(),
                    SeatAvailabilityStatus.BOOKED
            );
        }
    }
}