package com.example.seat_service.service;

import com.example.enums.CabinClassType;
import com.example.payload.response.BookingSeatSelectionResponse;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class BookingSeatSelectionService {
    private final FlightInstanceCabinRepository cabins;
    private final SeatInstanceRepository seats;

    @Transactional(readOnly = true)
    public BookingSeatSelectionResponse getSelection(Long flightInstanceId, CabinClassType cabinClassType,
            List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > 500
                || seatIds.stream().anyMatch(Objects::isNull) || new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1 to 500 distinct non-null seat IDs");
        }
        var cabin = cabins.findBookingCapacity(flightInstanceId, cabinClassType).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected cabin does not exist"));
        var selected = seats.findBookingSeats(seatIds).stream()
                .map(s -> new BookingSeatSelectionResponse.Seat(s.getId(), s.getFlightId(),
                        s.getFlightInstanceId(), s.getFlightCabinId(), s.getPrice())).toList();
        return BookingSeatSelectionResponse.builder().id(cabin.getId()).flightInstanceId(cabin.getFlightInstanceId())
                .cabinClassType(cabin.getCabinClassType()).availableSeats(cabin.getAvailableSeats())
                .seats(selected).build();
    }
}
