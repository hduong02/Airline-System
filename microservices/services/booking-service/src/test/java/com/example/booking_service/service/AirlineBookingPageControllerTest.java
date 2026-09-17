package com.example.booking_service.service;

import com.example.booking_service.controller.BookingController;
import com.example.booking_service.service.impl.BookingServiceImpl;
import com.example.enums.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AirlineBookingPageControllerTest {
    @Test void endpointPassesAuthenticatedOwnerAndFiltersAndSerializesPage() throws Exception {
        var service = mock(BookingService.class);
        when(service.getAirlineBookingPage(7L, "needle", BookingStatus.CONFIRMED, 9L, "ASC", 1, 10))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 10));
        var mvc = MockMvcBuilders.standaloneSetup(new BookingController(service)).build();
        mvc.perform(get("/api/bookings/airline/page").header("X-User-Id", "7")
                        .param("search", "needle").param("status", "CONFIRMED")
                        .param("flightInstanceId", "9").param("sortDirection", "ASC")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.number").value(1)).andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(10));
        verify(service).getAirlineBookingPage(7L, "needle", BookingStatus.CONFIRMED, 9L, "ASC", 1, 10);
        mvc.perform(get("/api/bookings/airline/page")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings/airline/page").header("X-User-Id", "7")
                .param("page", "bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings/airline/page").header("X-User-Id", "7")
                .param("status", "INVALID")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }

    @Test void defaultsAreBounded() throws Exception {
        var service = mock(BookingService.class);
        when(service.getAirlineBookingPage(7L, null, null, null, "DESC", 0, 20))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        var mvc = MockMvcBuilders.standaloneSetup(new BookingController(service)).build();
        mvc.perform(get("/api/bookings/airline/page").header("X-User-Id", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20));
        verify(service).getAirlineBookingPage(7L, null, null, null, "DESC", 0, 20);
    }

    @Test void rejectsInvalidPagingBeforeCallingAnyDependencies() {
        var service = new BookingServiceImpl(null, null, null, null, null, null, null, null, null, null);
        for (int[] bounds : List.of(new int[]{-1, 20}, new int[]{0, 0}, new int[]{0, 101})) {
            var error = assertThrows(ResponseStatusException.class, () -> service.getAirlineBookingPage(
                    7L, null, null, null, "DESC", bounds[0], bounds[1]));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.getAirlineBookingPage(7L, null, null, null, "invalid", 0, 20)).getStatusCode());
    }
}
