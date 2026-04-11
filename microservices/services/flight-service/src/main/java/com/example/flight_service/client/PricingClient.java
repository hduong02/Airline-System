package com.example.flight_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import com.example.payload.response.FareResponse;

@FeignClient(name = "pricing-service")
public interface PricingClient {

    @GetMapping("/api/fares/lowest/flight/{flightId}/cabin-class/{cabinClassId}")
    FareResponse getLowestFareForFlightAndCabinClass(
            @PathVariable Long flightId,
            @PathVariable Long cabinClassId
    );
}
