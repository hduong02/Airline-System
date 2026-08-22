package com.example.booking_service.event.listener;

import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PricingClient;
import com.example.booking_service.client.UserClient;
import com.example.booking_service.event.publisher.BookingEventProducer;
import com.example.booking_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.event.BookingCancelledEvent;
import com.example.event.PaymentCompletedEvent;
import com.example.event.PaymentFailedEvent;
import com.example.payload.dto.UserDto;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightInstanceResponse;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final BookingEventProducer bookingEventProducer;
    private final BookingRepository bookingRepository;
    private final FlightClient flightClient;
    private final PricingClient pricingClient;
    private final UserClient userClient;
    private final TicketService ticketService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "payment.completed", groupId = "booking-service-group")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {

        Booking booking = bookingRepository.findByIdForUpdate(event.getBookingId())
                .orElse(null);

        if (booking == null || booking.getStatus() != BookingStatus.PENDING)
            return;

        ticketService.generateTicketsForBooking(booking);
        booking.setTicketIssued(true);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPaymentId(event.getPaymentId());
        booking = bookingRepository.save(booking);

        FlightInstanceResponse flightInstance = fetchFlightInstance(booking.getFlightInstanceId());
        FareResponse fareResponse = fetchFare(booking.getFareId());
        UserDto userDto = fetchUser(booking.getUserId());

        bookingEventProducer.sendBookingConfirmed(
            booking,
            event,
            flightInstance,
            fareResponse,
            userDto
        );
    }

    @KafkaListener(topics = "payment.failed", groupId = "booking-service-group")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {

        Booking booking = bookingRepository.findByIdForUpdate(event.getBookingId()).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING) return;

        ticketService.cancelTicketsForBooking(booking.getId());
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);
        applicationEventPublisher.publishEvent(new BookingCancelledEvent(
                booking.getId(), booking.getSeatInstanceIds() == null
                        ? List.of() : new ArrayList<>(booking.getSeatInstanceIds())));
    }

    // Private Helpers

    private FlightInstanceResponse fetchFlightInstance(Long flightInstanceId) {
        if (flightInstanceId == null) return null;
        try {
            return flightClient.getFlightInstanceById(flightInstanceId);
        } catch (Exception e) {
            return null;
        }
    }

    private FareResponse fetchFare(Long fareId) {
        if (fareId == null) return null;
        try {
            return pricingClient.getFareById(fareId);
        } catch (Exception e) {
            return null;
        }
    }

    private UserDto fetchUser(Long userId) {
        if (userId == null) return null;
        try {
            return userClient.getUserById(userId);
        } catch (Exception e) {
            return null;
        }
    }
}
