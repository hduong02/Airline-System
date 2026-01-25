package com.example.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaggagePolicyRequest {

    @NotBlank(message = "Policy name is required")
    private String name;

    @NotNull(message = "Fare ID is required")
    private Long fareId;

    private String description;

    // Cabin baggage
    @PositiveOrZero
    private Double cabinBaggageMaxWeight;

    @PositiveOrZero
    private Integer cabinBaggagePieces;

    @PositiveOrZero
    private Double cabinBaggageWeightPerPiece;

    private Integer cabinBaggageMaxDimension;

    // Check-in baggage
    @PositiveOrZero
    private Double checkInBaggageMaxWeight;

    @PositiveOrZero
    private Integer checkInBaggagePieces;

    @PositiveOrZero
    private Double checkInBaggageWeightPerPiece;

    @PositiveOrZero
    private Integer freeCheckedBagsAllowance;

    // Benefits
    private Boolean priorityBaggage;
    private Boolean extraBaggageAllowance;

}