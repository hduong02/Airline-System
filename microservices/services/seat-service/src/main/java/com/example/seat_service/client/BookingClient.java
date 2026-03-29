package com.example.seat_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.payload.response.BookingResponse;

@FeignClient(name = "booking-service")
public interface BookingClient {

    @GetMapping("/api/bookings/{bookingId}")
    BookingResponse getBookingById(@PathVariable Long id) throws Exception;
}
