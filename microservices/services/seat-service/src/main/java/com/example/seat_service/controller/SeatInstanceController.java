package com.example.seat_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
