package com.example.booking_service.controller;

import org.springframework.data.domain.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.booking_service.service.BookingService;
import com.example.booking_service.payload.BookingConfirmationStatusResponse;
import com.example.booking_service.service.IdempotentBookingService;
import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.PaymentInitiateResponse;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final IdempotentBookingService idempotentBookingService;

    @PostMapping
    public ResponseEntity<PaymentInitiateResponse> createBooking(
            @Valid @RequestBody BookingRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-User-Id") Long userId)
            throws Exception
        {
        PaymentInitiateResponse response = idempotentBookingService.createBooking(request, userId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @PostMapping("/{id}/checkout")
    public ResponseEntity<PaymentInitiateResponse> resumeCheckout(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) throws Exception {
        return ResponseEntity.ok(bookingService.resumeCheckout(id, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getBookingById(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) throws Exception {
        BookingResponse response = bookingService.getBookingById(id, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/confirmation-status")
    public ResponseEntity<BookingConfirmationStatusResponse> getConfirmationStatus(
            @PathVariable Long id, @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(bookingService.getConfirmationStatus(id, userId));
    }

    @GetMapping("/airline")
    public ResponseEntity<List<BookingResponse>> getBookingsByAirline(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) Long flightInstanceId,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestHeader("X-User-Id") Long userId
    ) {
        List<BookingResponse> responses = bookingService.getAllBookingsByAirline(
                userId,
                search,
                status,
                flightInstanceId,
                sortDirection
        );
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/airline/page")
    public ResponseEntity<Page<BookingResponse>> getAirlineBookingPage(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) Long flightInstanceId,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(bookingService.getAirlineBookingPage(
                userId, search, status, flightInstanceId, sortDirection, page, size));
    }

    @GetMapping("/user/history")
    public ResponseEntity<List<BookingResponse>> getBookingsByUser(
            @RequestHeader("X-User-Id") Long userId) {
        List<BookingResponse> responses = bookingService.getBookingsByUser(userId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/history/page")
    public ResponseEntity<Page<BookingResponse>> getTravelerBookingPage(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(bookingService.getTravelerBookingPage(userId, tab, page, size));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) throws Exception {
        BookingResponse response = bookingService.cancelBooking(id, userId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBooking(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) throws Exception {
        bookingService.deleteBooking(id, userId);
        return ResponseEntity.noContent().build();
    }
}
