package com.example.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import com.example.enums.CabinClassType;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.payload.response.SeatInstanceResponse;
import com.example.payload.response.BookingSeatSelectionResponse;

@FeignClient(name = "seat-service")
public interface SeatClient {
    @PostMapping("/internal/booking-seat-selections/{flightInstanceId}/{cabinClassType}")
    BookingSeatSelectionResponse getBookingSeatSelection(@PathVariable Long flightInstanceId,
            @PathVariable CabinClassType cabinClassType, @RequestBody List<Long> seatInstanceIds);

    @PostMapping("/api/seat-instances/batch-by-ids")
    List<SeatInstanceResponse> getSelectedSeats(@RequestBody List<Long> seatInstanceIds);

    @PostMapping("/api/seat-instances/price/total")
    Double calculateSeatPrice(@RequestBody List<Long> seatInstanceIds);

    @PostMapping("/api/seat-instances/bookings/{bookingId}/reserve")
    void reserveBookingSeats(@PathVariable Long bookingId,
            @RequestBody List<Long> seatInstanceIds);

    @PostMapping("/api/seat-instances/bookings/{bookingId}/release")
    void releaseBookingSeats(@PathVariable Long bookingId,
            @RequestBody List<Long> seatInstanceIds);

    @GetMapping("/api/flight-instance-cabins/flight-instance/{flightInstanceId}/cabin-class-type/{cabinClassType}")
    FlightInstanceCabinResponse getFlightInstanceCabin(
            @PathVariable Long flightInstanceId,
            @PathVariable CabinClassType cabinClassType);
}
