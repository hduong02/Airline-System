package com.example.booking_service.service;

import java.util.List;

import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Ticket;

public interface TicketService {
    List<Ticket> generateTicketsForBooking(Booking booking);
    void cancelTicketsForBooking(Long bookingId);
}
