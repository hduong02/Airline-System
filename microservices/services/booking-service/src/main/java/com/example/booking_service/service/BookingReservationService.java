package com.example.booking_service.service;

import com.example.booking_service.client.SeatClient;
import com.example.booking_service.model.BookingAttempt;
import com.example.booking_service.model.BookingAttempt.ReservationState;
import com.example.booking_service.repository.BookingAttemptRepository;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.BookingStatus;
import feign.FeignException;
import io.micrometer.core.instrument.Metrics;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Durable reservation request, lease and compensation; HTTP never runs inside a DB transaction. */
@Service
public class BookingReservationService {
    private final BookingAttemptRepository attempts;
    private final BookingRepository bookings;
    private final SeatClient seats;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public BookingReservationService(BookingAttemptRepository attempts, BookingRepository bookings,
            SeatClient seats, PlatformTransactionManager manager) {
        this.attempts = attempts;
        this.bookings = bookings;
        this.seats = seats;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NEVER)
    public void ensureReserved(Long attemptId) {
        Claim claim = transactions.execute(status -> claim(attemptId));
        if (claim == null) return;
        try {
            if (claim.release()) {
                seats.releaseBookingSeats(claim.bookingId(), claim.seatIds());
                transactions.executeWithoutResult(status -> {
                    var attempt = ownedAttempt(claim);
                    attempt.setReservationState(ReservationState.RELEASED);
                    clearClaim(attempt);
                    attempt.setReservationRetryAt(null);
                });
            } else {
                reserve(claim);
                boolean reserved = Boolean.TRUE.equals(transactions.execute(status -> {
                    var attempt = ownedAttempt(claim);
                    var booking = bookings.findByIdForUpdate(claim.bookingId()).orElse(null);
                    boolean usable = booking != null && booking.getStatus() == BookingStatus.PENDING
                            && booking.getHoldExpiresAt() != null && booking.getHoldExpiresAt().isAfter(Instant.now());
                    attempt.setReservationState(usable ? ReservationState.RESERVED : ReservationState.RELEASE_PENDING);
                    clearClaim(attempt);
                    attempt.setReservationRetryAt(usable ? null : Instant.now());
                    return usable;
                }));
                if (reserved) return;
                // Cancellation/expiry won the race with the remote reservation. The release
                // intent is already committed, so a crash here is recovered by the job.
                ensureReserved(attemptId);
            }
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> {
                    var attempt = attempts.findByIdForUpdate(attemptId).orElseThrow();
                    if (!claim.token().equals(attempt.getReservationClaim())) return;
                    if (!claim.release() && failure instanceof FeignException remote && remote.status() == 409) {
                        attempt.setReservationState(ReservationState.RELEASE_PENDING);
                        var booking = bookings.findByIdForUpdate(claim.bookingId()).orElse(null);
                        if (booking != null && booking.getStatus() == BookingStatus.PENDING) {
                            booking.setStatus(BookingStatus.CANCELLED);
                        }
                    }
                    clearClaim(attempt);
                    attempt.setReservationRetryAt(Instant.now().plusSeconds(30));
                });
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof FeignException remote && remote.status() == 409) {
                throw conflict("Selected seat is no longer available", failure);
            }
            throw failure;
        }
        throw conflict("Booking reservation was cancelled", null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public List<Long> recoverableAttemptIds() {
        return transactions.execute(status -> attempts.findRecoverableReservationIds(Instant.now(),
                ReservationState.RESERVED, ReservationState.RELEASED, PageRequest.of(0, 100)));
    }

    private Claim claim(Long attemptId) {
        var attempt = attempts.findByIdForUpdate(attemptId).orElseThrow();
        if (attempt.getReservationState() == null) return null; // Existing bookings were reserved before commit.
        if (attempt.getReservationState() == ReservationState.RELEASED) {
            throw conflict("Booking reservation was cancelled", null);
        }
        if (attempt.getReservationClaim() != null && attempt.getReservationClaimExpiresAt() != null
                && attempt.getReservationClaimExpiresAt().isAfter(Instant.now())) {
            throw conflict("Seat reservation is in progress; retry with the same Idempotency-Key", null);
        }
        var booking = bookings.findByIdForUpdate(attempt.getBookingId()).orElse(null);
        boolean cancelled = booking == null || booking.getStatus() == BookingStatus.CANCELLED;
        boolean expired = attempt.getReservationState() == ReservationState.PENDING && booking != null
                && (booking.getHoldExpiresAt() == null || !booking.getHoldExpiresAt().isAfter(Instant.now()));
        if (cancelled || expired || attempt.getReservationState() == ReservationState.RELEASE_PENDING) {
            attempt.setReservationState(ReservationState.RELEASE_PENDING);
            if (booking != null && booking.getStatus() == BookingStatus.PENDING) booking.setStatus(BookingStatus.CANCELLED);
        } else if (attempt.getReservationState() == ReservationState.RESERVED) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        attempt.setReservationClaim(token);
        attempt.setReservationClaimExpiresAt(Instant.now().plusSeconds(120));
        return new Claim(attemptId, attempt.getBookingId(), token,
                List.of(json.readValue(attempt.getReservationSeatIds(), Long[].class)),
                attempt.getReservationState() == ReservationState.RELEASE_PENDING);
    }

    private BookingAttempt ownedAttempt(Claim claim) {
        var attempt = attempts.findByIdForUpdate(claim.attemptId()).orElseThrow();
        if (!claim.token().equals(attempt.getReservationClaim())) {
            throw conflict("Reservation claim changed; retry with the same Idempotency-Key", null);
        }
        return attempt;
    }

    private void clearClaim(BookingAttempt attempt) {
        attempt.setReservationClaim(null);
        attempt.setReservationClaimExpiresAt(null);
    }

    private void reserve(Claim claim) {
        long started = System.nanoTime();
        boolean success = false;
        try {
            seats.reserveBookingSeats(claim.bookingId(), claim.seatIds());
            success = true;
        } finally {
            Metrics.timer("booking.seat.reservation", "outcome", success ? "success" : "failure")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private ResponseStatusException conflict(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private record Claim(Long attemptId, Long bookingId, String token, List<Long> seatIds, boolean release) {}
}
