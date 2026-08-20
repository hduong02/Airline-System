package com.example.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import com.example.enums.CabinClassType;
import com.example.payload.response.FlightInstanceCabinResponse;

@FeignClient(name = "seat-service")
public interface SeatClient {

    @PostMapping("/api/seat-instances/price/total")
    Double calculateSeatPrice(@RequestBody List<Long> seatInstanceIds);

    @GetMapping("/api/flight-instance-cabins/flight-instance/{flightInstanceId}/cabin-class-type/{cabinClassType}")
    FlightInstanceCabinResponse getFlightInstanceCabin(
            @PathVariable Long flightInstanceId,
            @PathVariable CabinClassType cabinClassType);
}
