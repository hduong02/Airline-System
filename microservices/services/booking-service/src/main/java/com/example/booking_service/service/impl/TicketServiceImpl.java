package com.example.booking_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.model.Ticket;
import com.example.booking_service.repository.TicketRepository;
import com.example.booking_service.service.TicketService;
import com.example.enums.TicketStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;

    @Override
    @Transactional
    public List<Ticket> generateTicketsForBooking(Booking booking) {
        List<Ticket> tickets = new ArrayList<Ticket>();

        for (Passenger passenger : booking.getPassengers()) {
            String ticketNumber = generateUniqueTicketNumber();

            Ticket ticket = Ticket.builder()
                    .ticketNumber(ticketNumber)
                    .status(TicketStatus.BOOKED)
                    .issuedAt(LocalDateTime.now())
                    .booking(booking)
                    .passenger(passenger)
                    .build();

            Ticket savedTicket = ticketRepository.save(ticket);
            tickets.add(savedTicket);
        }
        return tickets;
    }

    private String generateUniqueTicketNumber() {
        String ticketNumber;
        do {
            String datePart = LocalDateTime.now().toString()
                    .substring(0, 10).replace("-", "");
            String randomPart = UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase();

            ticketNumber = String.format("TKT-%s-%s", datePart, randomPart);
        } while (ticketRepository.existsByTicketNumber(ticketNumber));

        return ticketNumber;
    }
}
