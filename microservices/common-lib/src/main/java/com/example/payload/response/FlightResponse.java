package com.example.payload.response;

import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

import com.example.enums.FlightStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightResponse {

    private Long id;
    private String flightNumber;
    private AirlineResponse airline;
    private AircraftResponse aircraft;
    private AirportResponse departureAirport;
    private AirportResponse arrivalAirport;
    private LocalDateTime departureTime;
    private LocalDateTime arrivalTime;
    private FlightStatus status;
    private Double lowestPrice;
    private Integer totalAvailableSeats;

    private Instant createdAt;
    private Instant updatedAt;
}
