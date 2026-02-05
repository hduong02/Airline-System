package com.example.payload.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightInstanceCabinRequest {

    @NotNull
    private Long flightId;

    @NotNull(message = "Flight instance ID is required")
    private Long flightInstanceId;

    @NotNull
    private Long cabinClassId;

    @NotNull
    @Positive
    private Double baseFare;

    @NotNull
    @PositiveOrZero
    private Double taxesAndFees;

    @NotNull
    @PositiveOrZero
    private Double airlineFees;

    private Double currentPrice;
    private Boolean isActive;
}