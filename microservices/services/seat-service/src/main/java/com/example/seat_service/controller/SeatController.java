package com.example.seat_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.payload.response.SeatResponse;
import com.example.seat_service.service.SeatService;

import java.util.List;

@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @GetMapping
    public ResponseEntity<List<SeatResponse>> getAllSeats() {
        return ResponseEntity.ok(seatService.getAll());
    }
}