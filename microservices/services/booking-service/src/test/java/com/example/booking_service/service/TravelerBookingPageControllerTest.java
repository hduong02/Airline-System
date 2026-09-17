package com.example.booking_service.service;

import com.example.booking_service.controller.BookingController;
import com.example.booking_service.service.impl.BookingServiceImpl;
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

class TravelerBookingPageControllerTest {
    @Test void endpointUsesAuthenticatedTravelerAndPassesCategoryAndPage() throws Exception {
        var service = mock(BookingService.class);
        when(service.getTravelerBookingPage(7L, "past", 1, 20))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 20));
        var mvc = MockMvcBuilders.standaloneSetup(new BookingController(service)).build();
        mvc.perform(get("/api/bookings/user/history/page").header("X-User-Id", "7")
                        .param("tab", "past").param("page", "1").param("size", "20")
                        .param("userId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.totalElements").value(20));
        verify(service).getTravelerBookingPage(7L, "past", 1, 20);
        mvc.perform(get("/api/bookings/user/history/page")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }

    @Test void defaultsUseAllAndTenRows() throws Exception {
        var service = mock(BookingService.class);
        when(service.getTravelerBookingPage(7L, "all", 0, 10))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        MockMvcBuilders.standaloneSetup(new BookingController(service)).build()
                .perform(get("/api/bookings/user/history/page").header("X-User-Id", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(10));
        verify(service).getTravelerBookingPage(7L, "all", 0, 10);
    }

    @Test void invalidParametersFailBeforeDependenciesAreCalled() {
        var service = new BookingServiceImpl(null, null, null, null, null, null, null, null, null, null);
        for (int[] bounds : List.of(new int[]{-1, 10}, new int[]{0, 0}, new int[]{0, 101})) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.getTravelerBookingPage(7L, "all", bounds[0], bounds[1])).getStatusCode());
        }
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.getTravelerBookingPage(7L, "invalid", 0, 10)).getStatusCode());
    }
}
