package com.example.booking_service.event.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PricingClient;
import com.example.booking_service.client.UserClient;
import com.example.booking_service.event.publisher.BookingEventProducer;
import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.service.TicketService;
import com.example.enums.BookingStatus;
import com.example.event.BookingCancelledEvent;
import com.example.event.PaymentCompletedEvent;
import com.example.event.PaymentFailedEvent;

class PaymentEventListenerTest {
    private final BookingEventProducer producer = mock(BookingEventProducer.class);
    private final BookingRepository repository = mock(BookingRepository.class);
    private final FlightClient flightClient = mock(FlightClient.class);
    private final PricingClient pricingClient = mock(PricingClient.class);
    private final UserClient userClient = mock(UserClient.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PaymentEventListener listener = new PaymentEventListener(
            producer, repository, flightClient, pricingClient, userClient,
            ticketService, publisher);

    @Test
    void failedPaymentCancelsTicketsAndPublishesSeatRelease() {
        Booking booking = Booking.builder().id(99L).status(BookingStatus.PENDING)
                .seatInstanceIds(List.of(100L)).build();
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(booking));

        listener.handlePaymentFailed(PaymentFailedEvent.builder().bookingId(99L).build());

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        verify(ticketService).cancelTicketsForBooking(99L);
        verify(repository).save(booking);
        verify(publisher).publishEvent(new BookingCancelledEvent(99L, List.of(100L)));
    }

    @Test
    void lateSuccessfulPaymentCannotConfirmCancelledBooking() {
        Booking booking = Booking.builder().id(99L).status(BookingStatus.CANCELLED).build();
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(booking));

        listener.handlePaymentCompleted(PaymentCompletedEvent.builder().bookingId(99L).build());

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        verifyNoInteractions(producer, ticketService, publisher);
    }
}
