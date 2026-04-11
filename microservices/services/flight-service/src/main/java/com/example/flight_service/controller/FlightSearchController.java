package com.example.flight_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.enums.CabinClassType;
import com.example.flight_service.service.FlightSearchService;
import com.example.payload.request.FlightSearchRequest;
import com.example.payload.response.FlightInstanceResponse;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class FlightSearchController {

    private final FlightSearchService flightSearchService;

    @GetMapping("/api/flights/search")
    public ResponseEntity<Page<FlightInstanceResponse>> searchFlights(
            @RequestParam Long departureAirportId,
            @RequestParam Long arrivalAirportId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDate,
            @RequestParam Integer passengers,
            @RequestParam CabinClassType cabinClass,
            @RequestParam(required = false) List<Long> airlines,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String departureTimeRange,
            @RequestParam(required = false) String arrivalTimeRange,
            @RequestParam(required = false) Integer maxDuration,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) String alliance,
            Pageable pageable) {


        FlightSearchRequest request = FlightSearchRequest.builder()
                .departureAirportId(departureAirportId)
                .arrivalAirportId(arrivalAirportId)
                .departureDate(departureDate)
                .passengers(passengers)
                .cabinClass(cabinClass)
                .airlines(airlines)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .departureTimeRange(departureTimeRange)
                .arrivalTimeRange(arrivalTimeRange)
                .maxDuration(maxDuration)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();
        Page<FlightInstanceResponse> res = flightSearchService
                .searchFlights(request, pageable);

        return ResponseEntity.ok(res);
    }


}