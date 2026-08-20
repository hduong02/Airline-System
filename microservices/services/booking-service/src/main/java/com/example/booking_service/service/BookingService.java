package com.example.booking_service.service;

import java.util.List;

import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.PaymentInitiateResponse;

public interface BookingService {

        PaymentInitiateResponse createBooking(BookingRequest request, Long userId)
                        throws Exception;

        BookingResponse updateBooking(Long id, BookingRequest request)
                        throws Exception;

        BookingResponse getBookingById(Long id, Long userId) throws Exception;

        List<BookingResponse> getAllBookingsByAirline(
                        Long userId,
                        String searchQuery,
                        BookingStatus status,
                        Long flightInstanceId,
                        String sortDirection);

        List<BookingResponse> getBookingsByUser(Long userId);

        BookingResponse cancelBooking(Long id, Long userId) throws Exception;

        void deleteBooking(Long id, Long userId) throws Exception;
}
