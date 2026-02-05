package com.example.seat_service.mapper;

import com.example.enums.SeatAvailabilityStatus;
import com.example.payload.response.SeatInstanceResponse;
import com.example.seat_service.model.SeatInstance;

public class SeatInstanceMapper {
    public static SeatInstanceResponse toResponse(SeatInstance si) {
        return SeatInstanceResponse.builder()
                .id(si.getId())
                .flightId(si.getFlightId())
                .seatId(si.getSeat() != null ? si.getSeat().getId() : null)
                .seatNumber(si.getSeat() != null ? si.getSeat().getSeatNumber() : null)
                .seatType(si.getSeat() != null ? si.getSeat().getSeatType().name() : null)
                .seatPosition(si.getSeat() != null ? si.getSeat().getFullPosition() : null)
                .seat(SeatMapper.toResponse(si.getSeat()))
                .status(si.getStatus())
                .flightInstanceId(si.getFlightInstanceId())
                .flightCabinId(si.getFlightInstanceCabin() != null ?
                        si.getFlightInstanceCabin().getId() : null)
                .fare(si.getFare())
                .price(si.getPremiumSurcharge())
                .version(si.getVersion())
                .createdAt(si.getCreatedAt())
                .updatedAt(si.getUpdatedAt())
                .isAvailable(si.isAvailable())
                .isBooked(si.isBooked())
                .isOccupied(si.getStatus() == SeatAvailabilityStatus.OCCUPIED)
                .build();
    }
}