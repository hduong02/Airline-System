package com.example.seat_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seat_service.model.Seat;


public interface SeatRepository extends JpaRepository<Seat, Long> {
    boolean existsBySeatMapId(Long seatMapId);
}