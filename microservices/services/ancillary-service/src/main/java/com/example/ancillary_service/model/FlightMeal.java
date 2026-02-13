package com.example.ancillary_service.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightMeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long flightId;

    @ManyToOne
    private Meal meal;

    @Column(nullable = false)
    @Builder.Default
    private Boolean available = true;

    private Double price;

    @Builder.Default
    private Integer displayOrder = 0;
}