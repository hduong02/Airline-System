package com.example.seat_service.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.example.enums.SeatAvailabilityStatus;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SeatInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long flightId;

    @ManyToOne
    private FlightInstanceCabin flightInstanceCabin;

    private Long flightInstanceId;

    @ManyToOne
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SeatAvailabilityStatus status = SeatAvailabilityStatus.AVAILABLE;

    @Builder.Default
    private boolean isBooked = false;

    @Builder.Default
    private boolean isAvailable = true;

    private Double fare;
    private Double premiumSurcharge;

    @Version
    private Long version;


    private Long flightScheduleId;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}