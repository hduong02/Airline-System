package com.example.payload.response;

import com.example.enums.CoverageType;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsuranceCoverageResponse {
    private long id;
    private Long ancillaryId;
    private String ancillaryName;
    private CoverageType coverageType;
    private String name;
    private String description;
    private Double coverageAmount;
    private String emergencyContact;
    private Boolean isFlat;
    private String claimCondition;
    private Integer displayOrder;
    private Boolean active;
}
