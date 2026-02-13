package com.example.ancillary_service.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Meal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String mealType;

    private String dietaryRestriction;

    private String ingredients;

    private String imageUrl;

    @Builder.Default
    private Boolean available = true;

    @Builder.Default
    private Boolean requiresAdvanceBooking = false;

    private Integer advanceBookingHours;

    @Builder.Default
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Long airlineId;

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}