package com.example.seat_service.model;

import java.util.List;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private int totalRows;

    @Column(nullable = false)
    private int rightSeatsPerRow;

    @Column(nullable = false)
    private int leftSeatsPerRow;

    @Column(nullable = false)
    private Long airlineId;

    @OneToMany(mappedBy = "seatMap", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Seat> seats;

    @OneToOne
    private CabinClass cabinClass;
}