package com.example.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@FeignClient(name = "ancillary-service")
public interface AncillaryClient {

    @PostMapping("/api/flight-cabin-ancillaries/price/total")
    double calculateAncillariesPrice(@RequestBody List<Long> flightCabinAncillaryIds);

    @PostMapping("/api/flight-meals/price/total")
    double calculateMealPrice(@RequestBody List<Long> requests);
}
