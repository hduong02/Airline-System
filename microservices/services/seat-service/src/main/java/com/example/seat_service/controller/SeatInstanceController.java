package com.example.seat_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.example.seat_service.service.SeatInstanceService;

import java.util.List;

@RestController
@RequestMapping("/api/seat-instances")
@RequiredArgsConstructor
public class SeatInstanceController {

    private final SeatInstanceService seatInstanceService;

    @PostMapping("/price/total")
    public ResponseEntity<Double> calculateSeatPrice(
            @RequestBody List<Long> seatInstanceIds) {
        return ResponseEntity.ok(seatInstanceService.calculateSeatPrice(seatInstanceIds));
    }

    @PostMapping("/bookings/{bookingId}/reserve")
    public ResponseEntity<Void> reserveBookingSeats(@PathVariable Long bookingId,
            @RequestBody List<Long> seatInstanceIds) {
        try {
            seatInstanceService.reserveBookingSeats(bookingId, seatInstanceIds);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bookings/{bookingId}/release")
    public ResponseEntity<Void> releaseBookingSeats(@PathVariable Long bookingId,
            @RequestBody List<Long> seatInstanceIds) {
        seatInstanceService.releaseBookingSeats(bookingId, seatInstanceIds);
        return ResponseEntity.noContent().build();
    }
}
