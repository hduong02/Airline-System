package com.example.booking_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.booking_service.model.Ticket;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByBookingId(Long bookingId);

    @Query("""
            SELECT t FROM Ticket t
            LEFT JOIN FETCH t.booking
            LEFT JOIN FETCH t.passenger
            WHERE t.booking.id = :bookingId
            """)
    List<Ticket> findByBookingIdWithDetails(@Param("bookingId") Long bookingId);

    boolean existsByTicketNumber(String ticketNumber);
}
