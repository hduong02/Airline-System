package com.example.payload.response;

import com.example.enums.CabinClassType;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/** Booking validation data only: no cabin entities, seat map or unselected seats. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingSeatSelectionResponse {
    private Long id;
    private Long flightInstanceId;
    private CabinClassType cabinClassType;
    private Integer availableSeats;
    private List<Seat> seats;

    public record Seat(Long id, Long flightId, Long flightInstanceId, Long flightCabinId, Double price) {}
}
