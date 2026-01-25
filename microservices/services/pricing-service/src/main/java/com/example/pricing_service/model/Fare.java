package com.example.pricing_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.example.embeddable.BoardingBenefits;
import com.example.embeddable.FlexibilityBenefits;
import com.example.embeddable.InFlightBenefits;
import com.example.embeddable.PremiumServiceBenefits;
import com.example.embeddable.SeatBenefits;
import com.example.enums.CabinClassType;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Fare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Character rbdCode;

    @Column(nullable = false)
    private Long flightId;

    @Column(nullable = false)
    private Long cabinClassId;


    @Enumerated(EnumType.STRING)
    private CabinClassType cabinClass;

    @Column(nullable = false)
    private Double baseFare;

    private Double taxesAndFees;
    private Double airlineFees;

    @Column(nullable = false)
    private Double currentPrice;

    @Column(length = 100)
    private String fareLabel;

    @OneToOne(mappedBy = "fare", cascade = CascadeType.ALL, orphanRemoval = true)
    private BaggagePolicy baggagePolicy;

    @OneToOne(mappedBy = "fare", cascade = CascadeType.ALL, orphanRemoval = true)
    private FareRules fareRules;


    // Embedded benefits
    @Embedded
    @Builder.Default
    private SeatBenefits seatBenefits = new SeatBenefits();

    @Embedded
    @Builder.Default
    private BoardingBenefits boardingBenefits = new BoardingBenefits();

    @Embedded
    @Builder.Default
    private InFlightBenefits inFlightBenefits = new InFlightBenefits();

    @Embedded
    @Builder.Default
    private FlexibilityBenefits flexibilityBenefits = new FlexibilityBenefits();

    @Embedded
    @Builder.Default
    private PremiumServiceBenefits premiumServiceBenefits = new PremiumServiceBenefits();

    // Audit
    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    
    public Double getTotalPrice() {
        return baseFare
                + (airlineFees != null ? airlineFees : 0.0)
                + (taxesAndFees != null ? taxesAndFees : 0.0);
    }

}