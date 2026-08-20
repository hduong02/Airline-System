package com.example.seat_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seat_service.model.CancelledBooking;

public interface CancelledBookingRepository extends JpaRepository<CancelledBooking, Long> {}
