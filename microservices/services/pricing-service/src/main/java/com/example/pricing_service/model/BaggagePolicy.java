package com.example.pricing_service.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaggagePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne()
    @JsonIgnore
    private Fare fare;

    @Column(nullable = false)
    private String name;

    private String description;

    // Cabin baggage
    private Double cabinBaggageMaxWeight;

    @Builder.Default
    private Integer cabinBaggagePieces = 1;

    private Double cabinBaggageWeightPerPiece;

    private Integer cabinBaggageMaxDimension;

    // Check-in baggage
    private Double checkInBaggageMaxWeight;

    @Builder.Default
    private Integer checkInBaggagePieces = 1;

    private Double checkInBaggageWeightPerPiece;

    @Builder.Default
    private Integer freeCheckedBagsAllowance = 0;

    @Builder.Default
    private Boolean priorityBaggage = false;

    @Builder.Default
    private Boolean extraBaggageAllowance = false;

    private Long airlineId;

    // Audit
    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

}
