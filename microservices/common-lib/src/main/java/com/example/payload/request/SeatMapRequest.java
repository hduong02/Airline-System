package com.example.payload.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatMapRequest {

    @NotBlank(message = "Seat map name is required")
    private String name;

    @NotNull(message = "Total rows is required")
    @Positive
    private Integer totalRows;

    @NotNull(message = "Left seats per row is required")
    @Positive
    private Integer leftSeatsPerRow;

    @NotNull(message = "Right seats per row is required")
    @Positive
    private Integer rightSeatsPerRow;

    private Long cabinClassId;
}