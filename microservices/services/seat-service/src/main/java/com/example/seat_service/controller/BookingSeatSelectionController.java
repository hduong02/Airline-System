package com.example.seat_service.controller;

import com.example.enums.CabinClassType;
import com.example.payload.response.BookingSeatSelectionResponse;
import com.example.seat_service.service.BookingSeatSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/booking-seat-selections")
@RequiredArgsConstructor
public class BookingSeatSelectionController {
    private final BookingSeatSelectionService service;

    @PostMapping("/{flightInstanceId}/{cabinClassType}")
    public ResponseEntity<BookingSeatSelectionResponse> getSelection(@PathVariable Long flightInstanceId,
            @PathVariable CabinClassType cabinClassType, @RequestBody List<Long> seatIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.getSelection(flightInstanceId, cabinClassType, seatIds));
    }
}
