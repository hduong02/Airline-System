package com.example.booking_service.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.booking_service.model.Ticket;
import com.example.booking_service.repository.TicketRepository;
import com.example.enums.TicketStatus;

class TicketServiceImplTest {
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
