package com.example.booking_service.service;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.enums.PaymentStatus;
import com.example.payload.dto.PaymentDto;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.FlightResponse;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
public class RoutePerformanceService {
    private final BookingRepository bookings;
    private final AirlineClient airlines;
    private final PaymentClient payments;
    private final FlightClient flights;
    private final Clock clock;

    @Autowired
    public RoutePerformanceService(BookingRepository bookings, AirlineClient airlines,
            PaymentClient payments, FlightClient flights) {
        this(bookings, airlines, payments, flights, Clock.systemUTC());
    }

    RoutePerformanceService(BookingRepository bookings, AirlineClient airlines,
            PaymentClient payments, FlightClient flights, Clock clock) {
        this.bookings = bookings;
        this.airlines = airlines;
        this.payments = payments;
        this.flights = flights;
        this.clock = clock;
    }

    public record Route(String route, String departureAirportCode, String arrivalAirportCode,
            String departureAirportName, String arrivalAirportName, long totalBookings,
            BigDecimal totalRevenue, BigDecimal averageRevenuePerBooking) {}
    public record Performance(LocalDate startDate, LocalDate endDate, long totalRoutes,
            long totalBookings, BigDecimal totalRevenue, BigDecimal averageRevenuePerRoute,
            List<Route> topRoutesByBookings, List<Route> topRoutesByRevenue,
            List<Route> topRoutesByAverageRevenue) {}
    private record RouteKey(Long departureId, Long arrivalId) {}

    public Performance forOwner(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated owner is required");
        try {
            var airline = resolveAirline(userId);
            if (airline == null || airline.getId() == null || !Objects.equals(airline.getOwnerId(), userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Airline owner is required");
            }
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            LocalDate start = YearMonth.from(today).minusMonths(11).atDay(1);
            var rows = bookings.findStatisticsRows(airline.getId(), start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            Map<Long, FlightResponse> flightById = new HashMap<>();
            Map<RouteKey, List<BookingRepository.StatisticsRow>> groups = new HashMap<>();
            for (var row : rows) {
                if (row.getFlightId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Route information unavailable");
                }
                FlightResponse flight = flightById.computeIfAbsent(row.getFlightId(), flights::getFlightById);
                if (flight == null || flight.getAirline() == null
                        || !Objects.equals(flight.getAirline().getId(), airline.getId())
                        || !hasAirport(flight.getDepartureAirport()) || !hasAirport(flight.getArrivalAirport())) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Route information unavailable");
                }
                var key = new RouteKey(flight.getDepartureAirport().getId(), flight.getArrivalAirport().getId());
                groups.computeIfAbsent(key, unused -> new ArrayList<>()).add(row);
            }
            var confirmed = rows.stream().filter(row -> row.getStatus() == BookingStatus.CONFIRMED)
                    .map(BookingRepository.StatisticsRow::getId).toList();
            Map<Long, PaymentDto> paymentByBooking = new HashMap<>();
            for (int offset = 0; offset < confirmed.size(); offset += 200) {
                var batch = payments.getPaymentsByBookingIds(confirmed.subList(offset, Math.min(offset + 200, confirmed.size())));
                if (batch == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment statistics unavailable");
                paymentByBooking.putAll(batch);
            }
            List<Route> routes = new ArrayList<>();
            for (var group : groups.values()) {
                var flight = flightById.get(group.getFirst().getFlightId());
                var departure = flight.getDepartureAirport();
                var arrival = flight.getArrivalAirport();
                BigDecimal revenue = BigDecimal.ZERO;
                for (var row : group) {
                    var payment = paymentByBooking.get(row.getId());
                    if (row.getStatus() == BookingStatus.CONFIRMED && payment != null
                            && payment.getStatus() == PaymentStatus.SUCCESS && payment.getAmount() != null) {
                        revenue = revenue.add(BigDecimal.valueOf(payment.getAmount()));
                    }
                }
                routes.add(new Route(departure.getIataCode() + " → " + arrival.getIataCode(),
                        departure.getIataCode(), arrival.getIataCode(), departure.getName(), arrival.getName(),
                        group.size(), revenue, average(revenue, group.size())));
            }
            BigDecimal totalRevenue = routes.stream().map(Route::totalRevenue).reduce(BigDecimal.ZERO, BigDecimal::add);
            Comparator<Route> tieBreaker = Comparator.comparing(Route::route);
            return new Performance(start, today, routes.size(), rows.size(), totalRevenue,
                    average(totalRevenue, routes.size()),
                    routes.stream().sorted(Comparator.comparingLong(Route::totalBookings).reversed().thenComparing(tieBreaker)).limit(10).toList(),
                    routes.stream().sorted(Comparator.comparing(Route::totalRevenue).reversed().thenComparing(tieBreaker)).limit(10).toList(),
                    routes.stream().sorted(Comparator.comparing(Route::averageRevenuePerBooking).reversed().thenComparing(tieBreaker)).limit(10).toList());
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Route analytics dependencies unavailable");
        }
    }

    private static boolean hasAirport(AirportResponse airport) {
        return airport != null && airport.getId() != null && airport.getIataCode() != null && !airport.getIataCode().isBlank();
    }

    private static BigDecimal average(BigDecimal revenue, long count) {
        return count == 0 ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
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
