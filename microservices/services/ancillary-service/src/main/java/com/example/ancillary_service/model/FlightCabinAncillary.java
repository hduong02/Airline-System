package com.example.ancillary_service.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightCabinAncillary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long flightId;

    @Column(nullable = false)
    private Long cabinClassId;

    @ManyToOne
    private Ancillary ancillary;

    @Builder.Default
    private Boolean available = true;

    private Integer maxQuantity;

    private Double price;

    @Builder.Default
    private Boolean includedInFare = false;
}