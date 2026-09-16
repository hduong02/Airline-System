package com.example.booking_service.service;

import com.example.booking_service.client.*;
import com.example.booking_service.controller.RoutePerformanceController;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.*;
import com.example.payload.dto.PaymentDto;
import com.example.payload.response.*;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoutePerformanceServiceTest {
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final AirlineClient airlines = mock(AirlineClient.class);
    private final PaymentClient payments = mock(PaymentClient.class);
    private final FlightClient flights = mock(FlightClient.class);
    private final RoutePerformanceService service = new RoutePerformanceService(bookings, airlines, payments, flights,
            Clock.fixed(Instant.parse("2026-01-01T00:30:00Z"), ZoneOffset.UTC));

    private void owner() {
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(50L).ownerId(7L).build());
    }

    @Test void emptyAirlineReturnsZeroSummariesAndEmptyRankings() {
        owner();
        var result = service.forOwner(7L);
        assertEquals(0, result.totalRoutes());
        assertEquals(0, result.totalBookings());
        assertEquals(BigDecimal.ZERO, result.totalRevenue());
        assertEquals(BigDecimal.ZERO, result.averageRevenuePerRoute());
        assertEquals(List.of(), result.topRoutesByBookings());
        assertEquals(List.of(), result.topRoutesByRevenue());
        assertEquals(List.of(), result.topRoutesByAverageRevenue());
        verify(bookings).findStatisticsRows(50L, Instant.parse("2025-02-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
        verifyNoInteractions(payments, flights);
    }

    @Test void groupsFlightsByDirectionalAirportPairAndRanksByEachMetric() {
        owner();
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(List.of(
                row(1, 10, BookingStatus.CONFIRMED), row(2, 10, BookingStatus.PENDING),
                row(3, 11, BookingStatus.CONFIRMED), row(4, 12, BookingStatus.CONFIRMED),
                row(5, 10, BookingStatus.CANCELLED), row(6, 10, BookingStatus.CONFIRMED)));
        when(flights.getFlightById(10L)).thenReturn(flight(1, "HAN", 2, "SGN"));
        when(flights.getFlightById(11L)).thenReturn(flight(1, "HAN", 2, "SGN"));
        when(flights.getFlightById(12L)).thenReturn(flight(2, "SGN", 1, "HAN"));
        when(payments.getPaymentsByBookingIds(List.of(1L, 3L, 4L, 6L))).thenReturn(Map.of(
                1L, paid(PaymentStatus.SUCCESS, 10.10), 3L, paid(PaymentStatus.SUCCESS, 20.20),
                4L, paid(PaymentStatus.SUCCESS, 99.90), 6L, paid(PaymentStatus.REFUNDED, 1000)));
        var result = service.forOwner(7L);
        assertEquals(2, result.totalRoutes());
        assertEquals(6, result.totalBookings());
        assertEquals(new BigDecimal("130.2"), result.totalRevenue());
        assertEquals(new BigDecimal("65.10"), result.averageRevenuePerRoute());
        var popular = result.topRoutesByBookings().getFirst();
        assertEquals("HAN → SGN", popular.route());
        assertEquals(5, popular.totalBookings());
        assertEquals(new BigDecimal("30.3"), popular.totalRevenue());
        assertEquals(new BigDecimal("6.06"), popular.averageRevenuePerBooking());
        assertEquals("SGN → HAN", result.topRoutesByRevenue().getFirst().route());
        assertEquals("SGN → HAN", result.topRoutesByAverageRevenue().getFirst().route());
        verify(flights, times(1)).getFlightById(10L);
        verify(flights, times(1)).getFlightById(11L);
        verify(flights, times(1)).getFlightById(12L);
    }

    @Test void summariesIncludeRoutesOutsideTopTenAndTiesAreDeterministic() {
        owner();
        var rows = java.util.stream.LongStream.rangeClosed(1, 12)
                .mapToObj(id -> row(id, id, BookingStatus.PENDING)).toList();
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(rows);
        for (long id = 1; id <= 12; id++) {
            when(flights.getFlightById(id)).thenReturn(flight(1, "AAA", id + 1, "B%02d".formatted(id)));
        }
        var result = service.forOwner(7L);
        assertEquals(12, result.totalRoutes());
        assertEquals(12, result.totalBookings());
        assertEquals(10, result.topRoutesByBookings().size());
        assertEquals("AAA → B01", result.topRoutesByBookings().getFirst().route());
        assertEquals("AAA → B10", result.topRoutesByRevenue().getLast().route());
        assertEquals(result.topRoutesByBookings(), result.topRoutesByAverageRevenue());
        verifyNoInteractions(payments);
    }

    @Test void rejectsForeignOwnershipBeforeReadingBookings() {
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(50L).ownerId(8L).build());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.forOwner(7L)).getStatusCode().value());
        verifyNoInteractions(bookings, payments, flights);
    }

    @Test void missingIdentityDoesNotCallDependencies() {
        assertThrows(ResponseStatusException.class, () -> service.forOwner(null));
        verifyNoInteractions(airlines, bookings, payments, flights);
    }

    @Test void unavailableFlightFailsInsteadOfReturningPartialAnalytics() {
        owner();
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(List.of(row(1, 10, BookingStatus.PENDING)));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.forOwner(7L)).getStatusCode().value());
        verifyNoInteractions(payments);
    }

    @Test void paymentFailureReturnsServiceUnavailableInsteadOfZeroRevenue() {
        owner();
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(List.of(row(1, 10, BookingStatus.CONFIRMED)));
        when(flights.getFlightById(10L)).thenReturn(flight(1, "HAN", 2, "SGN"));
        when(payments.getPaymentsByBookingIds(anyList())).thenThrow(mock(FeignException.class));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.forOwner(7L)).getStatusCode().value());
    }

    @Test void endpointSerializesContractAndRequiresIdentity() throws Exception {
        owner();
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(List.of(row(1, 10, BookingStatus.CONFIRMED)));
        when(flights.getFlightById(10L)).thenReturn(flight(1, "HAN", 2, "SGN"));
        when(payments.getPaymentsByBookingIds(List.of(1L))).thenReturn(Map.of(1L, paid(PaymentStatus.SUCCESS, 12.34)));
        var mvc = MockMvcBuilders.standaloneSetup(new RoutePerformanceController(service)).build();
        mvc.perform(get("/api/bookings/route-performance/airline").header("X-User-Id", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startDate").value("2025-02-01"))
                .andExpect(jsonPath("$.endDate").value("2026-01-01"))
                .andExpect(jsonPath("$.totalRoutes").value(1))
                .andExpect(jsonPath("$.totalRevenue").value(12.34))
                .andExpect(jsonPath("$.topRoutesByBookings[0].departureAirportCode").value("HAN"))
                .andExpect(jsonPath("$.topRoutesByRevenue[0].totalBookings").value(1))
                .andExpect(jsonPath("$.topRoutesByAverageRevenue[0].averageRevenuePerBooking").value(12.34));
        mvc.perform(get("/api/bookings/route-performance/airline")).andExpect(status().isBadRequest());
    }

    private BookingRepository.StatisticsRow row(long id, long flightId, BookingStatus status) {
        return new BookingRepository.StatisticsRow() {
            public Long getId() { return id; }
            public Long getFlightId() { return flightId; }
            public BookingStatus getStatus() { return status; }
            public Instant getBookingDate() { return Instant.parse("2026-01-01T00:00:00Z"); }
        };
    }
    private FlightResponse flight(long departureId, String departure, long arrivalId, String arrival) {
        return FlightResponse.builder().airline(AirlineResponse.builder().id(50L).build())
                .departureAirport(AirportResponse.builder().id(departureId).iataCode(departure).name(departure + " airport").build())
                .arrivalAirport(AirportResponse.builder().id(arrivalId).iataCode(arrival).name(arrival + " airport").build()).build();
    }
    private PaymentDto paid(PaymentStatus status, double amount) {
        var payment = new PaymentDto();
        payment.setStatus(status);
        payment.setAmount(amount);
        return payment;
    }
}
