package com.example.payload.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CabinClassRequest {

    @NotNull(message = "Name is required")
    private String name;

    @NotBlank(message = "Code is required")
    private String code;

    private String description;

    @NotNull(message = "Aircraft ID is required")
    private Long aircraftId;

    private Integer displayOrder;
    private Boolean isActive;
    private Boolean isBookable;
    private Integer typicalSeatPitch;
    private Integer typicalSeatWidth;
    private String seatType;
}
