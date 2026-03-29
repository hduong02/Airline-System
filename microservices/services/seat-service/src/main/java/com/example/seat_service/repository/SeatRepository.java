package com.example.seat_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seat_service.model.Seat;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    boolean existsBySeatMapId(Long seatMapId);

    List<Seat> findBySeatMapId(Long seatMapId);
}