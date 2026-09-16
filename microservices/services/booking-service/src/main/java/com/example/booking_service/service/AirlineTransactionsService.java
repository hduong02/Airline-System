package com.example.booking_service.service;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.PaymentGateway;
import com.example.enums.PaymentStatus;
import com.example.payload.dto.PaymentDto;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AirlineTransactionsService {
    private final BookingRepository bookings;
    private final AirlineClient airlines;
    private final PaymentClient payments;

    public record Summary(BigDecimal totalRevenue, long successfulPayments, long pendingPayments,
            long failedPayments, BigDecimal successRate) {}
    public record Transactions(List<PaymentDto> content, long totalElements, int totalPages,
            int number, int size, Summary summary) {}

    public Transactions forOwner(Long userId, int page, int size, String search, PaymentStatus status,
            PaymentGateway gateway, String sortBy, String sortDirection) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated owner is required");
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size between 1 and 100");
        }
        Comparator<PaymentDto> order = switch (sortBy) {
            case "id" -> Comparator.comparing(PaymentDto::getId, Comparator.nullsLast(Comparator.naturalOrder()));
            case "bookingId" -> Comparator.comparing(PaymentDto::getBookingId, Comparator.nullsLast(Comparator.naturalOrder()));
            case "transactionId" -> Comparator.comparing(PaymentDto::getTransactionId, Comparator.nullsLast(Comparator.naturalOrder()));
            case "amount" -> Comparator.comparing(PaymentDto::getAmount, Comparator.nullsLast(Comparator.naturalOrder()));
            case "createdAt" -> Comparator.comparing(PaymentDto::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported transaction sort field");
        };
        if (!"ASC".equalsIgnoreCase(sortDirection) && !"DESC".equalsIgnoreCase(sortDirection)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort direction must be ASC or DESC");
        }
        if ("DESC".equalsIgnoreCase(sortDirection)) order = order.reversed();
        order = order.thenComparing(PaymentDto::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        try {
            var airline = resolveAirline(userId);
            if (airline == null || airline.getId() == null || !Objects.equals(airline.getOwnerId(), userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Airline owner is required");
            }
            var bookingIds = bookings.findIdsByAirlineId(airline.getId());
            List<PaymentDto> owned = new ArrayList<>();
            for (int offset = 0; offset < bookingIds.size(); offset += 200) {
                var ids = bookingIds.subList(offset, Math.min(offset + 200, bookingIds.size()));
                var batch = payments.getPaymentsByBookingIds(ids);
                if (batch == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transactions unavailable");
                // Never include unsolicited entries returned by a dependency.
                for (Long id : ids) {
                    var payment = batch.get(id);
                    if (payment == null) continue;
                    if (!Objects.equals(payment.getBookingId(), id)) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transaction booking mismatch");
                    }
                    owned.add(payment);
                }
            }
            String query = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
            var filtered = owned.stream()
                    .filter(payment -> status == null || payment.getStatus() == status)
                    .filter(payment -> gateway == null || payment.getGateway() == gateway)
                    .filter(payment -> query.isEmpty() || contains(payment.getTransactionId(), query)
                            || contains(payment.getGatewayPaymentId(), query)
                            || contains(String.valueOf(payment.getBookingId()), query))
                    .sorted(order).toList();
            BigDecimal revenue = filtered.stream().filter(payment -> payment.getStatus() == PaymentStatus.SUCCESS)
                    .map(PaymentDto::getAmount).filter(Objects::nonNull).map(BigDecimal::valueOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long successful = filtered.stream().filter(payment -> payment.getStatus() == PaymentStatus.SUCCESS).count();
            long pending = filtered.stream().filter(payment -> payment.getStatus() == PaymentStatus.PENDING
                    || payment.getStatus() == PaymentStatus.PROCESSING).count();
            long failed = filtered.stream().filter(payment -> payment.getStatus() == PaymentStatus.FAILED).count();
            var rate = filtered.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(successful * 100L)
                    .divide(BigDecimal.valueOf(filtered.size()), 1, RoundingMode.HALF_UP);
            int from = (int) Math.min((long) page * size, filtered.size());
            int to = (int) Math.min((long) from + size, filtered.size());
            int pages = (int) ((filtered.size() + (long) size - 1) / size);
            return new Transactions(filtered.subList(from, to), filtered.size(), pages, page, size,
                    new Summary(revenue, successful, pending, failed, rate));
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction dependencies unavailable");
        }
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
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
