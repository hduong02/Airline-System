package com.example.booking_service.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.booking_service.model.Ticket;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.TicketRepository;
import com.example.enums.TicketStatus;

class TicketServiceImplTest {
    @Test
    void issuedTicketsAreAvailableOnBookingForConfirmationEvent() {
        Passenger passenger = Passenger.builder().id(5L).build();
        Booking booking = Booking.builder().id(99L).passengers(Set.of(passenger)).build();
        TicketRepository repository = mock(TicketRepository.class);
        when(repository.save(org.mockito.ArgumentMatchers.any(Ticket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Ticket> tickets = new TicketServiceImpl(repository)
                .generateTicketsForBooking(booking);

        assertEquals(1, tickets.size());
        assertTrue(booking.getTickets().contains(tickets.get(0)));
        assertEquals(TicketStatus.BOOKED, tickets.get(0).getStatus());
    }

    @Test
    void cancellationOnlyChangesBookedTickets() {
        Ticket booked = Ticket.builder().status(TicketStatus.BOOKED).build();
        Ticket used = Ticket.builder().status(TicketStatus.USED).build();
        Ticket refunded = Ticket.builder().status(TicketStatus.REFUNDED).build();
        TicketRepository repository = mock(TicketRepository.class);
        when(repository.findByBookingId(99L)).thenReturn(List.of(booked, used, refunded));

        new TicketServiceImpl(repository).cancelTicketsForBooking(99L);

        assertEquals(TicketStatus.CANCELLED, booked.getStatus());
        assertEquals(TicketStatus.USED, used.getStatus());
        assertEquals(TicketStatus.REFUNDED, refunded.getStatus());
        verify(repository).saveAll(List.of(booked, used, refunded));
    }
}
