package com.example.booking_service.controller;

import com.example.booking_service.service.AirlineTransactionsService;
import com.example.enums.PaymentGateway;
import com.example.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bookings/transactions")
public class AirlineTransactionsController {
    private final AirlineTransactionsService transactions;

    @GetMapping("/airline")
    public AirlineTransactionsService.Transactions forAirline(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentGateway gateway,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        return transactions.forOwner(userId, page, size, search, status, gateway, sortBy, sortDirection);
    }
}
