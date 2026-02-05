package com.example.payload.response;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

import com.example.enums.CabinClassType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightInstanceCabinResponse {
    private Long id;
    private Long flightInstanceId;
    private CabinClassType cabinClassType;
    private CabinClassResponse cabinClass;

    @Builder.Default
    private List<SeatInstanceResponse> seats = new ArrayList<>();

    @Builder.Default
    private SeatMapResponse seatMap = new SeatMapResponse();
    
    private Integer totalSeats;
    private Integer bookedSeats;
    private Integer availableSeats;
    private Boolean isActive;
    private Boolean canBook;
}
