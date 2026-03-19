package com.example.seat_service.controller;

import jakarta.validation.Valid;
import com.example.payload.request.SeatMapRequest;
import com.example.payload.response.ApiResponse;
import com.example.payload.response.SeatMapResponse;
import com.example.seat_service.service.SeatMapService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seat-maps")
@RequiredArgsConstructor
public class SeatMapController {

    private final SeatMapService seatMapService;

    @PostMapping
    public ResponseEntity<SeatMapResponse> createSeatMap(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody SeatMapRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(seatMapService.createSeatMap(userId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SeatMapResponse> getSeatMapById(
            @PathVariable Long id) throws Exception {
        return ResponseEntity.ok(seatMapService.getSeatMapById(id));
    }

    @GetMapping("/cabin-class/{cabinClassId}")
    public ResponseEntity<SeatMapResponse> getSeatMapsByCabinClass(
            @PathVariable Long cabinClassId) {
        SeatMapResponse responses = seatMapService.getSeatMapsByCabinClass(cabinClassId);
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SeatMapResponse> updateSeatMap(
            @PathVariable Long id,
            @RequestBody SeatMapRequest request) throws Exception {
        return ResponseEntity.ok(seatMapService.updateSeatMap(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteSeatMap(@PathVariable Long id) throws Exception {
        seatMapService.deleteSeatMap(id);
        ApiResponse response = new ApiResponse("Seat map deleted successfully");
        return ResponseEntity.ok(response);
    }


}