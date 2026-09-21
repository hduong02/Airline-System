package com.example.booking_service.service;

import com.example.booking_service.controller.BookingController;
import com.example.booking_service.payload.BookingConfirmationStatusResponse;
import com.example.enums.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingConfirmationControllerTest {
    @Test
    void returnsSmallUncachedResponseAndRequiresAuthenticatedOwner() throws Exception {
        var service = mock(BookingService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new BookingController(service, mock(IdempotentBookingService.class))).build();
        when(service.getConfirmationStatus(7L, 1L)).thenReturn(
                new BookingConfirmationStatusResponse(7L, BookingStatus.CONFIRMED, true, true, 2, true));
        when(service.getConfirmationStatus(7L, 2L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));

        mvc.perform(get("/api/bookings/7/confirmation-status").header("X-User-Id", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.bookingId").value(7))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentReceived").value(true))
                .andExpect(jsonPath("$.ticketIssued").value(true))
                .andExpect(jsonPath("$.ticketCount").value(2))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.tickets").doesNotExist())
                .andExpect(jsonPath("$.passengers").doesNotExist());
        mvc.perform(get("/api/bookings/7/confirmation-status").header("X-User-Id", "2"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/7/confirmation-status"))
                .andExpect(status().isBadRequest());
        verify(service).getConfirmationStatus(7L, 1L);
        verify(service).getConfirmationStatus(7L, 2L);
        verifyNoMoreInteractions(service);
    }
}
