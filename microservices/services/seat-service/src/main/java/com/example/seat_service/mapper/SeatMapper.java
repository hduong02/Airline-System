package com.example.seat_service.mapper;

import com.example.payload.request.SeatRequest;
import com.example.payload.response.SeatResponse;
import com.example.seat_service.model.CabinClass;
import com.example.seat_service.model.Seat;
import com.example.seat_service.model.SeatMap;

public class SeatMapper {


    public static void updateEntity(SeatRequest request, Seat seat,
            SeatMap seatMap, CabinClass cabinClass) {
        seat.setSeatNumber(request.getSeatNumber());
        seat.setSeatRow(request.getSeatRow());
        seat.setColumnLetter(request.getColumnLetter());
        seat.setSeatType(request.getSeatType());
        seat.setSeatMap(seatMap);
        seat.setCabinClass(cabinClass);
        if (request.getIsAvailable() != null)
            seat.setIsAvailable(request.getIsAvailable());
        if (request.getIsBlocked() != null)
            seat.setIsBlocked(request.getIsBlocked());
        if (request.getIsEmergencyExit() != null)
            seat.setIsEmergencyExit(request.getIsEmergencyExit());
        if (request.getIsActive() != null)
            seat.setIsActive(request.getIsActive());
        seat.setBasePrice(request.getBasePrice());
        seat.setPremiumSurcharge(request.getPremiumSurcharge());
        if (request.getHasExtraLegroom() != null)
            seat.setHasExtraLegroom(request.getHasExtraLegroom());
        if (request.getHasPowerOutlet() != null)
            seat.setHasPowerOutlet(request.getHasPowerOutlet());
        if (request.getHasTvScreen() != null)
            seat.setHasTvScreen(request.getHasTvScreen());
        if (request.getHasExtraWidth() != null)
            seat.setHasExtraWidth(request.getHasExtraWidth());
        seat.setSeatPitch(request.getSeatPitch());
        seat.setSeatWidth(request.getSeatWidth());
    }

    public static SeatResponse toResponse(Seat seat) {
        return SeatResponse.builder()
                .id(seat.getId())
                .seatNumber(seat.getSeatNumber())
                .seatRow(seat.getSeatRow())
                .columnLetter(seat.getColumnLetter())
                .seatType(seat.getSeatType())
                .isAvailable(seat.getIsAvailable())
                .isBlocked(seat.getIsBlocked())
                .isEmergencyExit(seat.getIsEmergencyExit())
                .isActive(seat.getIsActive())
                .basePrice(seat.getBasePrice())
                .premiumSurcharge(seat.getPremiumSurcharge())
                .totalPrice(seat.getTotalPrice())
                .hasExtraLegroom(seat.getHasExtraLegroom())
                .hasPowerOutlet(seat.getHasPowerOutlet())
                .hasTvScreen(seat.getHasTvScreen())
                .hasExtraWidth(seat.getHasExtraWidth())
                .seatPitch(seat.getSeatPitch())
                .seatWidth(seat.getSeatWidth())
                .seatMapId(seat.getSeatMap() != null ?
                        seat.getSeatMap().getId() : null)
                .seatMapName(seat.getSeatMap() != null ?
                        seat.getSeatMap().getName() : null)
                .cabinClassId(seat.getCabinClass() != null ?
                        seat.getCabinClass().getId() : null)
                .cabinClassName(seat.getCabinClass() != null ?
                        seat.getCabinClass().getName().toString() : null)
                .createdAt(seat.getCreatedAt())
                .updatedAt(seat.getUpdatedAt())
                .createdBy(seat.getCreatedBy())
                .updatedBy(seat.getUpdatedBy())
                .isBookable(seat.isBookable())
                .fullPosition(seat.getFullPosition())
                .build();
    }
}
