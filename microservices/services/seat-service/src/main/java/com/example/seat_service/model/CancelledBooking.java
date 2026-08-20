package com.example.seat_service.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class CancelledBooking {
    @Id
    private Long bookingId;

    protected CancelledBooking() {}

    public CancelledBooking(Long bookingId) {
        this.bookingId = bookingId;
    }
}
