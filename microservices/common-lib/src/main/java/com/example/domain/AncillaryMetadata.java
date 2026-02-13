package com.example.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AncillaryMetadata {

    private BaggageMetadata baggage;

    private String protectionSummary;

    private String specialServiceDetails;

    private String upgradeDetails;
}