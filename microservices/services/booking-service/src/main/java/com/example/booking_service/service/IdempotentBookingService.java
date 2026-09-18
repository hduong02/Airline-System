package com.example.booking_service.service;

import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.model.BookingAttempt;
import com.example.booking_service.repository.BookingAttemptRepository;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;
import feign.FeignException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Callable;

@Service
public class IdempotentBookingService {
    private final BookingAttemptRepository attempts;
    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final PaymentClient payments;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public IdempotentBookingService(BookingAttemptRepository attempts, BookingRepository bookings,
            BookingService bookingService, PaymentClient payments, PlatformTransactionManager manager) {
        this.attempts = attempts;
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.payments = payments;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PaymentInitiateResponse createBooking(BookingRequest request, Long userId, String key)
            throws Exception {
        if (key == null) return bookingService.createBooking(request, userId);
        if (!key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID");
        }
        String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        Long attemptId;
        try {
            attemptId = inTransaction(() -> {
                var existing = attempts.findByUserIdAndIdempotencyKey(userId, normalizedKey);
                if (existing.isPresent()) return existing.get().getId();
                var attempt = new BookingAttempt();
                attempt.setUserId(userId);
                attempt.setIdempotencyKey(normalizedKey);
                attempt.setRequestHash(hash);
                // Claim the unique key before creating passengers or calling other services.
                attempts.saveAndFlush(attempt);
                PaymentInitiateRequest payment = bookingService.prepareBooking(request, userId);
                attempt.setBookingId(payment.getBookingId());
                attempt.setPaymentRequest(json.writeValueAsString(payment));
                return attempt.getId();
            });
        } catch (DataIntegrityViolationException collision) {
            // A concurrent winner must be read after the losing transaction rolled back.
            attemptId = inTransaction(() -> attempts.findByUserIdAndIdempotencyKey(userId, normalizedKey)
                    .map(BookingAttempt::getId).orElseThrow(() -> collision));
        }
        Long id = attemptId;
        return inTransaction(() -> {
            var attempt = attempts.findByIdForUpdate(id).orElseThrow();
            if (!attempt.getRequestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used with a different booking request");
            }
            if (attempt.getCheckoutResponse() != null) {
                return json.readValue(attempt.getCheckoutResponse(), PaymentInitiateResponse.class);
            }
            var booking = bookings.findByIdForUpdate(attempt.getBookingId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Booking no longer exists"));
            if (booking.getStatus() != BookingStatus.PENDING || booking.getHoldExpiresAt() == null
                    || !booking.getHoldExpiresAt().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking is no longer awaiting checkout");
            }
            // The booking/key already committed. A timeout here retains both for safe retry.
            var payment = json.readValue(attempt.getPaymentRequest(), PaymentInitiateRequest.class);
            var response = payments.initiatePayment(payment, userId);
            attempt.setCheckoutResponse(json.writeValueAsString(response));
            return response;
        });
    }

    private <T> T inTransaction(Callable<T> action) throws Exception {
        try {
            return transactions.execute(status -> {
                try {
                    return action.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CheckedFailure(e);
                }
            });
        } catch (CheckedFailure e) {
            throw (Exception) e.getCause();
        }
    }

    /** Ensure abandoned attempts have a payment to reconcile before releasing seats. */
    public void prepareExpiredCheckout(Long bookingId) throws Exception {
        inTransaction(() -> {
            var existing = attempts.findByBookingId(bookingId);
            if (existing.isEmpty()) return null; // Legacy bookings already have a payment.
            var attempt = attempts.findByIdForUpdate(existing.get().getId()).orElseThrow();
            if (attempt.getCheckoutResponse() != null) return null;
            var booking = bookings.findByIdForUpdate(bookingId).orElse(null);
            if (booking == null || booking.getStatus() != BookingStatus.PENDING) return null;
            try {
                var payment = json.readValue(attempt.getPaymentRequest(), PaymentInitiateRequest.class);
                var response = payments.initiatePayment(payment, attempt.getUserId());
                attempt.setCheckoutResponse(json.writeValueAsString(response));
            } catch (FeignException e) {
                // A terminal payment cannot resume; expiry reconciliation will read it.
                if (e.status() != 409) throw e;
            }
            return null;
        });
    }

    private static class CheckedFailure extends RuntimeException {
        CheckedFailure(Exception cause) { super(cause); }
    }
}
