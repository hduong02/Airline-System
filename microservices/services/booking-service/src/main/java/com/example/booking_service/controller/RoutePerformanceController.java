package com.example.booking_service.controller;

import com.example.booking_service.service.RoutePerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bookings/route-performance")
public class RoutePerformanceController {
    private final RoutePerformanceService performance;

    @GetMapping("/airline")
    public RoutePerformanceService.Performance forAirline(@RequestHeader("X-User-Id") Long userId) {
        return performance.forOwner(userId);
    }
}
