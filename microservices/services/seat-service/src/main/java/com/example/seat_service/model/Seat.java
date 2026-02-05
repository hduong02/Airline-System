package com.example.seat_service.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.example.enums.SeatType;

import java.time.LocalDateTime;

@Entity
@Table(name = "seats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String seatNumber;

    @Column(nullable = false)
    private Integer seatRow;

    private Character columnLetter;

    @Enumerated(EnumType.STRING)
    private SeatType seatType;

    private Double basePrice;

    private Double premiumSurcharge;

    @Builder.Default
    private Boolean isAvailable = true;

    @Builder.Default
    private Boolean isBlocked = false;

    @Builder.Default
    private Boolean isEmergencyExit = false;

    @Builder.Default
    private Boolean isActive = true;

    @Builder.Default
    private Boolean hasExtraLegroom = false;


    @Builder.Default
    private Boolean hasPowerOutlet = false;

    @Builder.Default
    private Boolean hasTvScreen = false;


    @Builder.Default
    private Boolean hasExtraWidth = false;

    private Integer seatPitch;
    private Integer seatWidth;

    @ManyToOne
    private SeatMap seatMap;

    @ManyToOne
    private CabinClass cabinClass;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    private Long version;

    public Double getTotalPrice() {
        Double total = basePrice != null ? basePrice : 0.0;
        if (premiumSurcharge != null) {
            total = total + premiumSurcharge;
        }
        return total;
    }

    public boolean isBookable() {
        return isActive && isAvailable && !isBlocked;
    }

    public String getFullPosition() {
        return seatRow + "" + columnLetter;
    }

}