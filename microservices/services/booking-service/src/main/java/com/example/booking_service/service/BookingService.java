package com.example.booking_service.service;

import java.util.List;

import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.response.BookingResponse;

public interface BookingService {

        BookingResponse createBooking(BookingRequest request, Long userId)
                        throws Exception;

        BookingResponse updateBooking(Long id, BookingRequest request)
                        throws Exception;

        BookingResponse getBookingById(Long id) throws Exception;

        List<BookingResponse> getAllBookingsByAirline(
                        Long airlineId,
                        String searchQuery,
                        BookingStatus status,
                        Long flightInstanceId,
                        String sortDirection);

        List<BookingResponse> getBookingsByUser(Long userId);

        BookingResponse cancelBooking(Long id) throws Exception;

        void deleteBooking(Long id) throws Exception;
}