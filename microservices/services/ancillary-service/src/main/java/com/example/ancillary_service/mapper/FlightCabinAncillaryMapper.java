package com.example.ancillary_service.mapper;

import java.util.List;

import com.example.ancillary_service.model.FlightCabinAncillary;
import com.example.payload.response.FlightCabinAncillaryResponse;
import com.example.payload.response.InsuranceCoverageResponse;

public class FlightCabinAncillaryMapper {

    public static FlightCabinAncillaryResponse toResponse(FlightCabinAncillary entity,
            List<InsuranceCoverageResponse> coverages) {
        if (entity == null) return null;

        return FlightCabinAncillaryResponse.builder()
                .id(entity.getId())
                .flightId(entity.getFlightId())
                .cabinClassId(entity.getCabinClassId())
                .ancillary(AncillaryMapper.toResponse(entity.getAncillary(), coverages))
                .available(entity.getAvailable())
                .maxQuantity(entity.getMaxQuantity())
                .price(entity.getPrice())
                .includedInFare(entity.getIncludedInFare())
                .build();
    }
}