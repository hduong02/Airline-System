package com.example.payload.response;

/** Minimal flight identity needed to validate and persist a booking. */
public record BookingFlightValidationResponse(Long flightId, Long flightInstanceId, Long airlineId) {}
