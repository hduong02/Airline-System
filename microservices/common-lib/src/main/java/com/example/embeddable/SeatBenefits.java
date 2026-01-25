package com.example.embeddable;

import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatBenefits {

    @Builder.Default
    private Boolean extraSeatSpace = false;

    @Builder.Default
    private Boolean preferredSeatChoice = false;

    @Builder.Default
    private Boolean advanceSeatSelection = false;

    @Builder.Default
    private Boolean guaranteedSeatTogether = false;
}