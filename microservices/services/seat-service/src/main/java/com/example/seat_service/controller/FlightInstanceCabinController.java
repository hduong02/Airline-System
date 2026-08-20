package com.example.seat_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.payload.request.FlightInstanceCabinRequest;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.enums.CabinClassType;
import com.example.seat_service.service.FlightInstanceCabinService;

@RestController
@RequestMapping("/api/flight-instance-cabins")
@RequiredArgsConstructor
public class FlightInstanceCabinController {

    private final FlightInstanceCabinService flightInstanceCabinService;

    @PostMapping
    public ResponseEntity<FlightInstanceCabinResponse> createFlightInstanceCabin(
            @Valid @RequestBody FlightInstanceCabinRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flightInstanceCabinService.createFlightInstanceCabin(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlightInstanceCabinResponse> getFlightInstanceCabinById(
            @PathVariable Long id) {
        return ResponseEntity.ok(flightInstanceCabinService.getFlightInstanceCabinById(id));
    }

    @GetMapping("/flight-instance/{flightInstanceId}/cabin-class/{cabinClassId}")
    public ResponseEntity<?> getByFlightInstanceIdAndCabinClassId(
            @PathVariable Long cabinClassId,
            @PathVariable Long flightInstanceId) {
        return ResponseEntity.ok(
                flightInstanceCabinService.getByFlightInstanceIdAndCabinClassId(
                        flightInstanceId,cabinClassId
                ));
    }

    @GetMapping("/flight-instance/{flightInstanceId}/cabin-class-type/{cabinClassType}")
    public ResponseEntity<FlightInstanceCabinResponse> getByFlightInstanceIdAndCabinClassType(
            @PathVariable Long flightInstanceId,
            @PathVariable CabinClassType cabinClassType) {
        return ResponseEntity.ok(
                flightInstanceCabinService.getByFlightInstanceIdAndCabinClassType(
                        flightInstanceId, cabinClassType));
    }

    @GetMapping("/flight-instance/{flightInstanceId}")
    public ResponseEntity<Page<FlightInstanceCabinResponse>> getByFlightInstanceId(
            @PathVariable Long flightInstanceId, Pageable pageable) {
        return ResponseEntity.ok(
                flightInstanceCabinService.getByFlightInstanceId(flightInstanceId, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlightInstanceCabinResponse> updateFlightInstanceCabin(
            @PathVariable Long id,
            @RequestBody FlightInstanceCabinRequest request) {
        return ResponseEntity.ok(
                flightInstanceCabinService.updateFlightInstanceCabin(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlightInstanceCabin(@PathVariable Long id) {
        flightInstanceCabinService.deleteFlightInstanceCabin(id);
        return ResponseEntity.noContent().build();
    }
}
