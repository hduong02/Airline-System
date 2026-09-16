package com.example.booking_service.service;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.enums.PaymentStatus;
import com.example.payload.dto.PaymentDto;
import com.example.payload.response.AirlineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingStatisticsServiceTest {
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final AirlineClient airlines = mock(AirlineClient.class);
    private final PaymentClient payments = mock(PaymentClient.class);
    private final BookingStatisticsService statistics = new BookingStatisticsService(bookings, airlines, payments,
            Clock.fixed(Instant.parse("2026-01-01T00:30:00Z"), ZoneOffset.UTC));

    private void owner() {
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(50L).ownerId(7L).build());
    }

    @Test void emptyAirlineHasZeroFilledOrderedTrendsAndDoesNotCallPayments() {
        owner();
        var result = statistics.forOwner(7L);
        assertEquals(0, result.totalBookingsToday());
        assertEquals(0, result.totalBookingsThisMonth());
        assertEquals(BigDecimal.ZERO, result.revenueThisMonth());
        assertEquals(30, result.dailyTrend().size());
        assertEquals(LocalDate.of(2025, 12, 3), result.dailyTrend().getFirst().date());
        assertEquals(LocalDate.of(2026, 1, 1), result.dailyTrend().getLast().date());
        assertEquals(12, result.monthlyData().size());
        assertEquals(YearMonth.of(2025, 2), result.monthlyData().getFirst().month());
        assertEquals(YearMonth.of(2026, 1), result.monthlyData().getLast().month());
        verify(bookings).findStatisticsRows(50L, Instant.parse("2025-02-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
        verifyNoInteractions(payments);
    }

    @Test void countsBookingsByUtcCreationDateAndOnlyRecognizesConfirmedSuccessfulPayments() {
        owner();
        var rows = List.of(row(1L, "2025-12-31T23:59:59Z", BookingStatus.CONFIRMED),
                row(2L, "2026-01-01T00:00:00Z", BookingStatus.CONFIRMED),
                row(3L, "2026-01-01T00:01:00Z", BookingStatus.CANCELLED),
                row(4L, "2026-01-01T00:02:00Z", BookingStatus.PENDING),
                row(5L, "2026-01-01T00:03:00Z", BookingStatus.CONFIRMED));
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(rows);
        when(payments.getPaymentsByBookingIds(List.of(1L, 2L, 5L))).thenReturn(Map.of(
                1L, payment(PaymentStatus.SUCCESS, 30.10), 2L, payment(PaymentStatus.SUCCESS, 20.20),
                5L, payment(PaymentStatus.REFUNDED, 99.0)));
        var result = statistics.forOwner(7L);
        assertEquals(4, result.totalBookingsToday());
        assertEquals(4, result.totalBookingsThisMonth());
        assertEquals(new BigDecimal("20.2"), result.revenueToday());
        assertEquals(new BigDecimal("20.2"), result.revenueThisMonth());
        assertEquals(1, result.dailyTrend().get(28).bookingCount());
        assertEquals(new BigDecimal("30.1"), result.monthlyData().get(10).revenue());
        assertEquals(4, result.monthlyData().getLast().bookingCount());
    }

    @Test void rejectsForeignAirlineBeforeReadingBookingsOrPayments() {
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(99L).ownerId(8L).build());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> statistics.forOwner(7L))
                .getStatusCode().value());
        verifyNoInteractions(bookings, payments);
    }

    @Test void rejectsMissingIdentityWithoutRemoteCalls() {
        assertThrows(ResponseStatusException.class, () -> statistics.forOwner(null));
        verifyNoInteractions(airlines, bookings, payments);
    }

    @Test void batchesPaymentRequestsAndDoesNotRepeatBookings() {
        owner();
        var rows = java.util.stream.LongStream.rangeClosed(1, 401)
                .mapToObj(id -> row(id, "2026-01-01T00:01:00Z", BookingStatus.CONFIRMED)).toList();
        when(bookings.findStatisticsRows(anyLong(), any(), any())).thenReturn(rows);
        when(payments.getPaymentsByBookingIds(anyList())).thenReturn(Map.of());
        assertEquals(401, statistics.forOwner(7L).totalBookingsToday());
        var captured = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(payments, times(3)).getPaymentsByBookingIds(captured.capture());
        assertEquals(List.of(200, 200, 1), captured.getAllValues().stream().map(List::size).toList());
        assertEquals(401, captured.getAllValues().stream().flatMap(List::stream).distinct().count());
    }

    private BookingRepository.StatisticsRow row(Long id, String date, BookingStatus status) {
        var row = mock(BookingRepository.StatisticsRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getBookingDate()).thenReturn(Instant.parse(date));
        when(row.getStatus()).thenReturn(status);
        return row;
    }

    private PaymentDto payment(PaymentStatus status, double amount) {
        var result = new PaymentDto();
        result.setStatus(status);
        result.setAmount(amount);
        return result;
    }
}
