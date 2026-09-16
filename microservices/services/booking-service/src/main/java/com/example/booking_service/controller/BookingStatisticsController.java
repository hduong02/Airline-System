package com.example.booking_service.controller;

import com.example.booking_service.service.BookingStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bookings/statistics")
public class BookingStatisticsController {
    private final BookingStatisticsService statistics;

    @GetMapping("/airline")
    public BookingStatisticsService.Statistics forAirline(@RequestHeader("X-User-Id") Long userId) {
        return statistics.forOwner(userId);
    }
}
