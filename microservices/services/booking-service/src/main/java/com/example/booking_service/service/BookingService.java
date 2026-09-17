package com.example.booking_service.service;

import org.springframework.data.domain.Page;
import java.util.List;

import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.PaymentInitiateResponse;

public interface BookingService {

        Page<BookingResponse> getTravelerBookingPage(Long userId, String tab, int page, int size);

        Page<BookingResponse> getAirlineBookingPage(
                        Long userId, String search, BookingStatus status, Long flightInstanceId,
                        String sortDirection, int page, int size);

        PaymentInitiateResponse createBooking(BookingRequest request, Long userId)
                        throws Exception;

        BookingResponse updateBooking(Long id, BookingRequest request)
                        throws Exception;

        BookingResponse getBookingById(Long id, Long userId) throws Exception;

        PaymentInitiateResponse resumeCheckout(Long id, Long userId) throws Exception;

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
