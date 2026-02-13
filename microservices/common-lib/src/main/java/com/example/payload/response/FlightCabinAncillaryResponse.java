package com.example.payload.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightCabinAncillaryResponse {
    private Long id;
    private Long flightId;
    private Long cabinClassId;
    private AncillaryResponse ancillary;
    private Boolean available;
    private Integer maxQuantity;
    private Double price;
    private Boolean includedInFare;
}
