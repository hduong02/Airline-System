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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;

@Service
public class IdempotentBookingService {
    private final BookingAttemptRepository attempts;
    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final PaymentClient payments;
    private final BookingReservationService reservations;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public IdempotentBookingService(BookingAttemptRepository attempts, BookingRepository bookings,
            BookingService bookingService, PaymentClient payments, PlatformTransactionManager manager,
            BookingReservationService reservations) {
        this.attempts = attempts;
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.payments = payments;
        this.reservations = reservations;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentInitiateResponse createBooking(BookingRequest request, Long userId, String key)
            throws Exception {
        if (key == null || !key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID");
        }
        String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        // Replays bypass live validation, even when the selected inventory has changed.
        BookingSelection selection = BookingTiming.measure("validation",
                () -> validateNewAttempt(request, userId, normalizedKey));
        PreparedAttempt prepared;
        try {
            prepared = inTransaction(() -> {
                var existing = attempts.findByUserIdAndIdempotencyKey(userId, normalizedKey);
                if (existing.isPresent()) return preparedAttempt(existing.get(), hash);
                var attempt = new BookingAttempt();
                attempt.setUserId(userId);
                attempt.setIdempotencyKey(normalizedKey);
                attempt.setRequestHash(hash);
                // Claim the unique key before persisting passengers or reserving seats.
                attempts.saveAndFlush(attempt);
                PaymentInitiateRequest payment = bookingService.prepareValidatedBooking(request, userId, selection);
                attempt.setBookingId(payment.getBookingId());
                attempt.setPaymentRequest(json.writeValueAsString(payment));
                var booking = bookings.findById(payment.getBookingId()).orElseThrow();
                attempt.setReservationSeatIds(json.writeValueAsString(booking.getSeatInstanceIds()));
                attempt.setReservationState(BookingAttempt.ReservationState.PENDING);
                attempt.setReservationRetryAt(Instant.now());
                return preparedAttempt(attempt, hash);
            });
        } catch (DataIntegrityViolationException collision) {
            // A concurrent winner must be read after the losing transaction rolled back.
            prepared = inTransaction(() -> attempts.findByUserIdAndIdempotencyKey(userId, normalizedKey)
                    .map(attempt -> preparedAttempt(attempt, hash)).orElseThrow(() -> collision));
        }
        if (prepared.response() != null) return prepared.response();
        Long id = prepared.id();
        BookingTiming.measure("reservation", () -> { reservations.ensureReserved(id); return null; });
        return checkoutPreparedAttempt(id);
    }

    private PreparedAttempt preparedAttempt(BookingAttempt attempt, String hash) {
        // Decide while the attempt is already loaded, before recovery can touch inventory.
        if (!attempt.getRequestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used with a different booking request");
        }
        return new PreparedAttempt(attempt.getId(), attempt.getCheckoutResponse() == null ? null
                : json.readValue(attempt.getCheckoutResponse(), PaymentInitiateResponse.class));
    }

    private record PreparedAttempt(Long id, PaymentInitiateResponse response) {}

    @Transactional(propagation = Propagation.NEVER)
    public void recoverReservationAndCheckout(Long attemptId) throws Exception {
        reservations.ensureReserved(attemptId);
        checkoutPreparedAttempt(attemptId);
    }

    private PaymentInitiateResponse checkoutPreparedAttempt(Long id) throws Exception {
        CheckoutClaim claim = inTransaction(() -> {
            var attempt = attempts.findByIdForUpdate(id).orElseThrow();
            if (attempt.getCheckoutResponse() != null) {
                return new CheckoutClaim(id, attempt.getUserId(), null, null,
                        json.readValue(attempt.getCheckoutResponse(), PaymentInitiateResponse.class));
            }
            rejectActiveClaim(attempt);
            var booking = bookings.findByIdForUpdate(attempt.getBookingId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Booking no longer exists"));
            if (booking.getStatus() != BookingStatus.PENDING || booking.getHoldExpiresAt() == null
                    || !booking.getHoldExpiresAt().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking is no longer awaiting checkout");
            }
            return claimCheckout(attempt);
        });
        return claim.response() != null ? claim.response() : initiateCheckout(claim);
    }

    private void rejectActiveClaim(BookingAttempt attempt) {
        if (attempt.getCheckoutClaim() != null && attempt.getCheckoutClaimExpiresAt() != null
                && attempt.getCheckoutClaimExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout is in progress; retry with the same Idempotency-Key");
        }
    }

    private CheckoutClaim claimCheckout(BookingAttempt attempt) {
        String token = UUID.randomUUID().toString();
        attempt.setCheckoutClaim(token);
        // A crashed owner must not prevent recovery forever. Payment-service deduplicates
        // by booking and uses a stable provider key if an overdue owner is still running.
        attempt.setCheckoutClaimExpiresAt(Instant.now().plusSeconds(120));
        return new CheckoutClaim(attempt.getId(), attempt.getUserId(), token,
                json.readValue(attempt.getPaymentRequest(), PaymentInitiateRequest.class), null);
    }

    private PaymentInitiateResponse initiateCheckout(CheckoutClaim claim) throws Exception {
        try {
            var response = BookingTiming.measure("payment.initiation",
                    () -> payments.initiatePayment(claim.payment(), claim.userId()));
            return inTransaction(() -> {
                var attempt = attempts.findByIdForUpdate(claim.attemptId()).orElseThrow();
                if (attempt.getCheckoutResponse() != null) {
                    return json.readValue(attempt.getCheckoutResponse(), PaymentInitiateResponse.class);
                }
                if (!claim.token().equals(attempt.getCheckoutClaim())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout claim changed; retry with the same Idempotency-Key");
                }
                // Confirmation may have completed while payment-service was responding.
                // Persist the replay response without changing the booking's status.
                attempt.setCheckoutResponse(json.writeValueAsString(response));
                attempt.setReservationRetryAt(null);
                clearClaim(attempt);
                return response;
            });
        } catch (Exception failure) {
            try {
                inTransaction(() -> {
                    var attempt = attempts.findByIdForUpdate(claim.attemptId()).orElseThrow();
                    if (claim.token().equals(attempt.getCheckoutClaim())) {
                        clearClaim(attempt);
                        if (attempt.getReservationState() != null) {
                            attempt.setReservationRetryAt(Instant.now().plusSeconds(30));
                        }
                    }
                    return null;
                });
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void clearClaim(BookingAttempt attempt) {
        attempt.setCheckoutClaim(null);
        attempt.setCheckoutClaimExpiresAt(null);
    }

    private record CheckoutClaim(Long attemptId, Long userId, String token,
            PaymentInitiateRequest payment, PaymentInitiateResponse response) {}

    private BookingSelection validateNewAttempt(BookingRequest request, Long userId, String key) throws Exception {
        // Give the preflight lookup an explicit transaction lifecycle as well.
        // An untransactional OSIV lookup can retain a connection when the later
        // REQUIRES_NEW transaction acquires another, exhausting a bounded pool.
        if (attemptExists(userId, key)) return null;
        try {
            return bookingService.validateBooking(request);
        } catch (RuntimeException failure) {
            // A concurrent request may have committed this key and consumed the seats
            // while validation was running. Replay that attempt instead of rejecting it.
            if (attemptExists(userId, key)) return null;
            throw failure;
        }
    }

    private boolean attemptExists(Long userId, String key) throws Exception {
        return inTransaction(() -> attempts.findByUserIdAndIdempotencyKey(userId, key).isPresent());
    }

    private <T> T inTransaction(Callable<T> action) throws Exception {
        try {
            return BookingTiming.measure("transaction", () -> transactions.execute(status -> {
                try {
                    return action.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CheckedFailure(e);
                }
            }));
        } catch (CheckedFailure e) {
            throw (Exception) e.getCause();
        }
    }

    /** Ensure abandoned attempts have a payment to reconcile before releasing seats. */
    @Transactional(propagation = Propagation.NEVER)
    public void prepareExpiredCheckout(Long bookingId) throws Exception {
        Long reservationAttempt = inTransaction(() -> attempts.findByBookingId(bookingId)
                .map(BookingAttempt::getId).orElse(null));
        if (reservationAttempt != null) reservations.ensureReserved(reservationAttempt);
        CheckoutClaim claim = inTransaction(() -> {
            var existing = attempts.findByBookingId(bookingId);
            if (existing.isEmpty()) return null; // Legacy bookings already have a payment.
            var attempt = attempts.findByIdForUpdate(existing.get().getId()).orElseThrow();
            if (attempt.getCheckoutResponse() != null) return null;
            rejectActiveClaim(attempt);
            var booking = bookings.findByIdForUpdate(bookingId).orElse(null);
            if (booking == null || booking.getStatus() != BookingStatus.PENDING) return null;
            return claimCheckout(attempt);
        });
        if (claim == null) return;
        try {
            initiateCheckout(claim);
        } catch (FeignException e) {
            // A terminal payment cannot resume; expiry reconciliation will read it.
            if (e.status() != 409) throw e;
        }
    }

    private static class CheckedFailure extends RuntimeException {
        CheckedFailure(Exception cause) { super(cause); }
    }
}
