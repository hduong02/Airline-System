package com.example.seat_service.controller;

import com.example.enums.CabinClassType;
import com.example.payload.request.CabinClassRequest;
import com.example.payload.response.CabinClassResponse;
import com.example.seat_service.service.CabinClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cabin-classes")
@RequiredArgsConstructor
public class CabinClassController {

    private final CabinClassService cabinClassService;

    @PostMapping
    public ResponseEntity<CabinClassResponse> createCabinClass(
            @Valid @RequestBody CabinClassRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cabinClassService.createCabinClass(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CabinClassResponse> getCabinClassById(
            @PathVariable Long id) throws Exception {
        return ResponseEntity.ok(cabinClassService.getCabinClassById(id));
    }

    @GetMapping("/aircraft/{id}/name/{cabinClass}")
    public ResponseEntity<CabinClassResponse> getCabinClassByAircraftIdAndName(
            @PathVariable CabinClassType cabinClass, @PathVariable Long id) {
        return ResponseEntity.ok(
                cabinClassService.getByAircraftIdAndName(id, cabinClass));
    }

    @GetMapping("/aircraft/{aircraftId}")
    public ResponseEntity<List<CabinClassResponse>> getCabinClassesByAircraftId(
            @PathVariable Long aircraftId) {
        return ResponseEntity.ok(cabinClassService.getCabinClassesByAircraftId(aircraftId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CabinClassResponse> updateCabinClass(@PathVariable Long id,
            @Valid @RequestBody CabinClassRequest request) throws Exception {
        return ResponseEntity.ok(cabinClassService.updateCabinClass(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCabinClass(@PathVariable Long id) throws Exception {
        cabinClassService.deleteCabinClass(id);
        return ResponseEntity.noContent().build();
    }
}
