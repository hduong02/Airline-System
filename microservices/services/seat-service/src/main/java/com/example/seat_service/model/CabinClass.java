package com.example.seat_service.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.example.enums.CabinClassType;

import java.time.Instant;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class CabinClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CabinClassType name;

    @Column(nullable = false)
    private String code;

    private String description;

    @OneToOne(mappedBy = "cabinClass", cascade = CascadeType.ALL, orphanRemoval = true)
    private SeatMap seatMap;

    @Column(nullable = false)
    private Long aircraftId;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isBookable = true;

    private Integer typicalSeatPitch;
    private Integer typicalSeatWidth;
    private String seatType;

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

}
