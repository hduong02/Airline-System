package com.example.seat_service.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = @Index(name = "idx_flight_instance_cabin_flight_class",
        columnList = "flight_instance_id,cabin_class_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class FlightInstanceCabin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_instance_id", nullable = false)
    private Long flightInstanceId;

    @ManyToOne
    @JoinColumn(name = "cabin_class_id")
    private CabinClass cabinClass;

    @Column(nullable = false)
    private Integer totalSeats;

    @Builder.Default
    private Integer bookedSeats = 0;

    @Builder.Default
    @OneToMany(
            mappedBy = "flightInstanceCabin",
            cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SeatInstance> seats = new ArrayList<>();

    public Integer getAvailableSeats() {
        return totalSeats - bookedSeats;
    }
}
