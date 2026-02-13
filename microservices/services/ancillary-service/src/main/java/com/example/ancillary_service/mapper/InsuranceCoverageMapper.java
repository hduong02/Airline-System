package com.example.ancillary_service.mapper;

import com.example.ancillary_service.model.Ancillary;
import com.example.ancillary_service.model.InsuranceCoverage;
import com.example.payload.request.InsuranceCoverageRequest;
import com.example.payload.response.InsuranceCoverageResponse;

public class InsuranceCoverageMapper {

    public static InsuranceCoverageResponse toResponse(InsuranceCoverage entity) {
        if (entity == null) return null;

        return InsuranceCoverageResponse.builder()
                .id(entity.getId())
                .ancillaryId(entity.getAncillary() != null ?
                        entity.getAncillary().getId() : null)
                .ancillaryName(entity.getAncillary() != null ?
                        entity.getAncillary().getName() : null)
                .coverageType(entity.getCoverageType())
                .name(entity.getName())
                .description(entity.getDescription())
                .coverageAmount(entity.getCoverageAmount())
                .isFlat(entity.isFlat())
                .claimCondition(entity.getClaimCondition())
                .emergencyContact(entity.getEmergencyContact())
                .displayOrder(entity.getDisplayOrder())
                .active(entity.isActive())
                .build();
    }

    public static InsuranceCoverage toEntity(InsuranceCoverageRequest request,
            Ancillary ancillary) {
        if (request == null) return null;

        return InsuranceCoverage.builder()
                .ancillary(ancillary)
                .coverageType(request.getCoverageType())
                .name(request.getName())
                .description(request.getDescription())
                .coverageAmount(request.getCoverageAmount())
                .isFlat(request.getIsFlat() != null ? request.getIsFlat() : true)
                .claimCondition(request.getClaimCondition())
                .emergencyContact(request.getEmergencyContact())
                .displayOrder(request.getDisplayOrder())
                .active(request.getActive() != null ? request.getActive() : true)
                .build();
    }

    public static void updateEntityFromRequest(InsuranceCoverage entity,
            InsuranceCoverageRequest request, Ancillary ancillary) {
        if (entity == null || request == null) return;
        if (ancillary != null) entity.setAncillary(ancillary);
        if (request.getCoverageType() != null)
            entity.setCoverageType(request.getCoverageType());
        if (request.getName() != null)
            entity.setName(request.getName());
        if (request.getDescription() != null)
            entity.setDescription(request.getDescription());
        if (request.getCoverageAmount() != null)
            entity.setCoverageAmount(request.getCoverageAmount());
        if (request.getIsFlat() != null)
            entity.setFlat(request.getIsFlat());
        if (request.getClaimCondition() != null)
            entity.setClaimCondition(request.getClaimCondition());
        if (request.getEmergencyContact() != null)
            entity.setEmergencyContact(request.getEmergencyContact());
        if (request.getDisplayOrder() != null)
            entity.setDisplayOrder(request.getDisplayOrder());
        if (request.getActive() != null)
 entity.setActive(request.getActive());
    }
}