package com.example.booking_service.service;

import com.example.payload.response.FareResponse;

/** Read-only validation snapshot; seat availability must be rechecked atomically on reservation. */
public record BookingSelection(Long airlineId, FareResponse fare, double seatPrice,
        double ancillaryPrice, double mealPrice) {}
