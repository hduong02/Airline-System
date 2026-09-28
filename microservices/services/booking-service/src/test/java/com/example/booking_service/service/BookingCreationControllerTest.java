package com.example.booking_service.service;

import com.example.booking_service.controller.BookingController;
import com.example.payload.response.PaymentInitiateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingCreationControllerTest {
    private static final String BODY = """
            {"flightId":10,"flightInstanceId":20,"fareId":30,
             "cabinClass":"ECONOMY","passengers":[{"firstName":"Test"}]}
            """;

    @Test
    void forwardsKeyAndAuthenticatedUserAndPreservesConflictResponse() throws Exception {
        var service = mock(IdempotentBookingService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BookingController(mock(BookingService.class), service)).build();
        String key = "b4625216-6f2c-424a-bf49-29e0b46269f0";
        when(service.createBooking(any(), eq(7L), eq(key)))
                .thenReturn(PaymentInitiateResponse.builder().paymentId(9L).success(true).build());
        mvc.perform(post("/api/bookings").header("X-User-Id", "7").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paymentId").value(9));
        verify(service).createBooking(any(), eq(7L), eq(key));
        when(service.createBooking(any(), eq(7L), eq(key)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Different request"));
        mvc.perform(post("/api/bookings").header("X-User-Id", "7").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void missingKeyIsRejectedBeforeCallingBookingServices() throws Exception {
        var service = mock(IdempotentBookingService.class);
        var bookings = mock(BookingService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BookingController(bookings, service)).build();

        mvc.perform(post("/api/bookings").header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service, bookings);
    }
}
