package com.example.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "seat-service")
public interface SeatClient {

    @PostMapping("/api/seat-instances/price/total")
    Double calculateSeatPrice(@RequestBody List<Long> seatInstanceIds);
}