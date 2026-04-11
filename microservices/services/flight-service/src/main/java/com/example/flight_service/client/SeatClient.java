package com.example.flight_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.enums.CabinClassType;
import com.example.payload.response.CabinClassResponse;

import java.util.List;

@FeignClient(name = "seat-service")
public interface SeatClient {

    @GetMapping("api/seats/aircraft/{aircraftId}")
    List<CabinClassResponse> getCabinClassesByAircraftId(
            @PathVariable Long aircraftId);

    @GetMapping("/api/cabin-classes/aircraft/{id}/name/{cabinClass}")
    CabinClassResponse getCabinClassByAircraftIdAndName(
            @PathVariable CabinClassType cabinClass,
            @PathVariable Long id
    );
}