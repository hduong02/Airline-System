package com.example.booking_service.service;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.controller.BookingStatisticsController;
import com.example.booking_service.repository.BookingRepository;
import com.example.payload.response.AirlineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingStatisticsControllerTest {
    @Test void endpointUsesAuthenticatedOwnerAndSerializesChartDates() throws Exception {
        var airlines = mock(AirlineClient.class);
        var bookings = mock(BookingRepository.class);
        var payments = mock(PaymentClient.class);
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(50L).ownerId(7L).build());
        var service = new BookingStatisticsService(bookings, airlines, payments,
                Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new BookingStatisticsController(service)).build();
        mvc.perform(get("/api/bookings/statistics/airline").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBookingsToday").value(0))
                .andExpect(jsonPath("$.revenueThisMonth").value(0))
                .andExpect(jsonPath("$.dailyTrend.length()").value(30))
                .andExpect(jsonPath("$.dailyTrend[29].date").value("2026-01-01"))
                .andExpect(jsonPath("$.monthlyData.length()").value(12))
                .andExpect(jsonPath("$.monthlyData[11].month").value("2026-01"));
        mvc.perform(get("/api/bookings/statistics/airline")).andExpect(status().isBadRequest());
        verify(airlines, times(1)).getAirlineByOwner(7L);
        verifyNoInteractions(payments);
    }
}
