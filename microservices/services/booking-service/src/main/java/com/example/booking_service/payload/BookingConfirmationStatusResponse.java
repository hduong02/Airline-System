package com.example.booking_service.payload;

import com.example.enums.BookingStatus;

public record BookingConfirmationStatusResponse(
        Long bookingId, BookingStatus status, boolean paymentReceived,
        boolean ticketIssued, long ticketCount, boolean confirmed) {
}
