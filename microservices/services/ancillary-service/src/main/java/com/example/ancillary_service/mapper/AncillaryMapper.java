package com.example.ancillary_service.mapper;

import java.util.List;

import com.example.payload.response.AncillaryResponse;
import com.example.ancillary_service.model.Ancillary;
import com.example.payload.response.InsuranceCoverageResponse;

public class AncillaryMapper {

    public static AncillaryResponse toResponse(Ancillary entity,
            List<InsuranceCoverageResponse> coverageResponseList) {
        if (entity == null) return null;

        return AncillaryResponse.builder()
                .id(entity.getId())
                .type(entity.getType())
                .subType(entity.getSubType())
                .rfisc(entity.getRfisc())
                .name(entity.getName())
                .description(entity.getDescription())
                .metadata(entity.getMetadata())
                .coverages(coverageResponseList)
                .displayOrder(entity.getDisplayOrder())
                .airlineId(entity.getAirlineId())
                .build();
    }
}
