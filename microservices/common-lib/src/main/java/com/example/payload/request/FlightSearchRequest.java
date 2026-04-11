package com.example.payload.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

import com.example.enums.CabinClassType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightSearchRequest {

    private Long departureAirportId;
    private Long arrivalAirportId;

    @NotNull(message = "Departure date is required")
    private LocalDate departureDate;

    @NotNull(message = "Number of passengers is required")
    @Min(value = 1, message = "At least 1 passenger is required")
    private Integer passengers;

    @NotNull(message = "Cabin class is required")
    private CabinClassType cabinClass;

    // Filter Parameters
    private List<Long> airlines; // Filter by airline ids
    private Double minPrice; // Minimum price filter
    private Double maxPrice; // Maximum price filter
    private String departureTimeRange; // "any", "morning", "afternoon", "evening", "night"
    private String arrivalTimeRange; // "any", "morning", "afternoon", "evening", "night"
    private Integer maxDuration; // Maximum duration in minutes

    // Sorting Parameters
    private String sortBy; // "price", "duration", "departure", "arrival"
    private String sortOrder; // "asc", "desc"
}