package com.example.booking_service.service;

import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightResponse;

/** Read-only validation snapshot; seat availability must be rechecked atomically on reservation. */
public record BookingSelection(FlightResponse flight, FareResponse fare, double seatPrice) {}
