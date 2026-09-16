package com.example.booking_service.service;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.enums.PaymentStatus;
import com.example.payload.dto.PaymentDto;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class BookingStatisticsService {
    private final BookingRepository bookings;
    private final AirlineClient airlines;
    private final PaymentClient payments;
    private final Clock clock;

    @Autowired
    public BookingStatisticsService(BookingRepository bookings, AirlineClient airlines, PaymentClient payments) {
        this(bookings, airlines, payments, Clock.systemUTC());
    }

    BookingStatisticsService(BookingRepository bookings, AirlineClient airlines, PaymentClient payments, Clock clock) {
        this.bookings = bookings;
        this.airlines = airlines;
        this.payments = payments;
        this.clock = clock;
    }

    public record DailyPoint(LocalDate date, long bookingCount, BigDecimal revenue) {}
    public record MonthlyPoint(YearMonth month, long bookingCount, BigDecimal revenue) {}
    public record Statistics(long totalBookingsToday, BigDecimal revenueToday,
            long totalBookingsThisMonth, BigDecimal revenueThisMonth,
            List<DailyPoint> dailyTrend, List<MonthlyPoint> monthlyData) {}

    public Statistics forOwner(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated owner is required");
        try {
            var airline = resolveAirline(userId);
            if (airline == null || airline.getId() == null || !Objects.equals(airline.getOwnerId(), userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Airline owner is required");
            }
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            YearMonth month = YearMonth.from(today);
            LocalDate start = month.minusMonths(11).atDay(1);
            var rows = bookings.findStatisticsRows(airline.getId(),
                    start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            Map<Long, PaymentDto> paymentByBooking = new HashMap<>();
            var confirmedIds = rows.stream().filter(row -> row.getStatus() == BookingStatus.CONFIRMED)
                    .map(BookingRepository.StatisticsRow::getId).toList();
            // Bound each dependency request rather than sending an airline's entire history.
            for (int offset = 0; offset < confirmedIds.size(); offset += 200) {
                var batch = payments.getPaymentsByBookingIds(
                        confirmedIds.subList(offset, Math.min(offset + 200, confirmedIds.size())));
                if (batch == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment statistics unavailable");
                paymentByBooking.putAll(batch);
            }
            Map<LocalDate, Long> counts = new HashMap<>();
            Map<LocalDate, BigDecimal> revenue = new HashMap<>();
            for (var row : rows) {
                LocalDate date = row.getBookingDate().atZone(ZoneOffset.UTC).toLocalDate();
                counts.merge(date, 1L, Long::sum);
                PaymentDto payment = paymentByBooking.get(row.getId());
                if (row.getStatus() == BookingStatus.CONFIRMED && payment != null
                        && payment.getStatus() == PaymentStatus.SUCCESS && payment.getAmount() != null) {
                    revenue.merge(date, BigDecimal.valueOf(payment.getAmount()), BigDecimal::add);
                }
            }
            var daily = today.minusDays(29).datesUntil(today.plusDays(1))
                    .map(date -> new DailyPoint(date, counts.getOrDefault(date, 0L),
                            revenue.getOrDefault(date, BigDecimal.ZERO))).toList();
            List<MonthlyPoint> monthly = new ArrayList<>();
            for (int i = 11; i >= 0; i--) {
                YearMonth bucket = month.minusMonths(i);
                long count = bucket.atDay(1).datesUntil(bucket.plusMonths(1).atDay(1))
                        .mapToLong(date -> counts.getOrDefault(date, 0L)).sum();
                BigDecimal amount = bucket.atDay(1).datesUntil(bucket.plusMonths(1).atDay(1))
                        .map(date -> revenue.getOrDefault(date, BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                monthly.add(new MonthlyPoint(bucket, count, amount));
            }
            var current = monthly.getLast();
            return new Statistics(counts.getOrDefault(today, 0L), revenue.getOrDefault(today, BigDecimal.ZERO),
                    current.bookingCount(), current.revenue(), daily, monthly);
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Booking statistics dependencies unavailable");
        }
    }

    private com.example.payload.response.AirlineResponse resolveAirline(Long userId) {
        try {
            return airlines.getAirlineByOwner(userId);
        } catch (FeignException ex) {
            if (ex.status() == 403 || ex.status() == 404) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Airline owner is required");
            }
            throw ex;
        }
    }
}
