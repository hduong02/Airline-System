package com.example.booking_service.service.impl;

import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.client.SeatClient;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.BookingAttempt;
import com.example.booking_service.model.BookingAttempt.ReservationState;
import com.example.booking_service.repository.*;
import com.example.booking_service.service.BookingReservationRecoveryJob;
import com.example.booking_service.service.BookingReservationService;
import com.example.booking_service.service.IdempotentBookingService;
import com.example.enums.BookingStatus;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.PaymentInitiateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(BookingFlowIntegrationTest.Config.class)
class BookingReservationRecoveryTest {
    @Autowired BookingReservationService reservations;
    @Autowired IdempotentBookingService checkout;
    @Autowired BookingAttemptRepository attempts;
    @Autowired BookingRepository bookings;
    @Autowired PassengerRepository passengers;
    @Autowired TicketRepository tickets;
    @Autowired SeatClient seats;
    @Autowired PaymentClient payments;
    private final JsonMapper json = JsonMapper.builder().build();
    private final BookingRequest request = BookingRequest.builder().build();

    @BeforeEach
    void resetFixtures() {
        reset(seats, payments);
        attempts.deleteAll();
        tickets.deleteAll();
        bookings.deleteAll();
        passengers.deleteAll();
        when(payments.initiatePayment(any(), eq(1L))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return PaymentInitiateResponse.builder().paymentId(9L).success(true).build();
        });
    }

    @Test
    void lostReservationResponseRetriesSameBookingAndOnlyThenStartsPayment() throws Exception {
        var attempt = seed();
        doThrow(new IllegalStateException("Response lost after seat commit")).doNothing()
                .when(seats).reserveBookingSeats(attempt.getBookingId(), List.of(100L));
        assertThrows(IllegalStateException.class, () -> checkout.createBooking(request, 1L, attempt.getIdempotencyKey()));
        assertEquals(ReservationState.PENDING, read(attempt).getReservationState());
        assertNull(read(attempt).getReservationClaim());
        verifyNoInteractions(payments);

        assertEquals(9L, checkout.createBooking(request, 1L, attempt.getIdempotencyKey()).getPaymentId());
        assertEquals(1, bookings.count());
        assertEquals(ReservationState.RESERVED, read(attempt).getReservationState());
        verify(seats, times(2)).reserveBookingSeats(attempt.getBookingId(), List.of(100L));
        verify(payments, times(1)).initiatePayment(any(), eq(1L));
    }

    @Test
    void recoveryFindsUnclaimedWorkAfterCrashAndWaitsForLiveLease() throws Exception {
        var attempt = seed();
        attempt.setReservationClaim("crashed-owner");
        attempt.setReservationClaimExpiresAt(Instant.now().plusSeconds(120));
        attempts.saveAndFlush(attempt);
        assertTrue(reservations.recoverableAttemptIds().isEmpty());
        attempt.setReservationClaimExpiresAt(Instant.now().minusSeconds(1));
        attempts.saveAndFlush(attempt);
        assertEquals(List.of(attempt.getId()), reservations.recoverableAttemptIds());

        new BookingReservationRecoveryJob(reservations, checkout).recoverReservations();
        assertEquals(ReservationState.RESERVED, read(attempt).getReservationState());
        assertTrue(reservations.recoverableAttemptIds().isEmpty());
        verify(seats).reserveBookingSeats(attempt.getBookingId(), List.of(100L));
        checkout.createBooking(request, 1L, attempt.getIdempotencyKey());
        verify(seats, times(1)).reserveBookingSeats(attempt.getBookingId(), List.of(100L));
        verify(payments).initiatePayment(any(), eq(1L));
    }

    @Test
    void recoveryResumesCheckoutAfterReservationCommitAndWaitsForLiveCheckoutLease() throws Exception {
        var attempt = seed();
        attempt.setReservationState(ReservationState.RESERVED);
        attempt.setCheckoutClaim("crashed-payment-owner");
        attempt.setCheckoutClaimExpiresAt(Instant.now().plusSeconds(120));
        attempts.saveAndFlush(attempt);
        assertTrue(reservations.recoverableAttemptIds().isEmpty());
        attempt.setCheckoutClaimExpiresAt(Instant.now().minusSeconds(1));
        attempts.saveAndFlush(attempt);

        new BookingReservationRecoveryJob(reservations, checkout).recoverReservations();
        assertNotNull(read(attempt).getCheckoutResponse());
        assertNull(read(attempt).getCheckoutClaim());
        verifyNoInteractions(seats);
        verify(payments).initiatePayment(any(), eq(1L));
        assertTrue(reservations.recoverableAttemptIds().isEmpty());
    }

    @Test
    void recoveryRetriesPaymentFailureWithBackoffWithoutReservingAgain() throws Exception {
        var attempt = seed();
        when(payments.initiatePayment(any(), eq(1L)))
                .thenThrow(new IllegalStateException("Payment response lost"))
                .thenReturn(PaymentInitiateResponse.builder().paymentId(9L).success(true).build());
        var job = new BookingReservationRecoveryJob(reservations, checkout);
        job.recoverReservations();
        assertEquals(ReservationState.RESERVED, read(attempt).getReservationState());
        assertNull(read(attempt).getCheckoutResponse());
        assertTrue(reservations.recoverableAttemptIds().isEmpty());
        var retry = read(attempt);
        retry.setReservationRetryAt(Instant.now().minusSeconds(1));
        attempts.saveAndFlush(retry);

        job.recoverReservations();
        assertNotNull(read(attempt).getCheckoutResponse());
        verify(seats, times(1)).reserveBookingSeats(attempt.getBookingId(), List.of(100L));
        verify(payments, times(2)).initiatePayment(any(), eq(1L));
    }

    @Test
    void expiredProvisionalBookingIsReleasedWithoutStartingPaymentAndReleaseIsRetried() throws Exception {
        var attempt = seed();
        var booking = bookings.findById(attempt.getBookingId()).orElseThrow();
        booking.setHoldExpiresAt(Instant.now().minusSeconds(1));
        bookings.saveAndFlush(booking);
        doThrow(new IllegalStateException("Seat service unavailable")).doNothing()
                .when(seats).releaseBookingSeats(attempt.getBookingId(), List.of(100L));

        assertThrows(IllegalStateException.class, () -> reservations.ensureReserved(attempt.getId()));
        assertEquals(BookingStatus.CANCELLED, bookings.findById(attempt.getBookingId()).orElseThrow().getStatus());
        assertEquals(ReservationState.RELEASE_PENDING, read(attempt).getReservationState());
        assertTrue(reservations.recoverableAttemptIds().isEmpty(), "Failed work must back off");
        var retry = read(attempt);
        retry.setReservationRetryAt(Instant.now().minusSeconds(1));
        attempts.saveAndFlush(retry);
        new BookingReservationRecoveryJob(reservations, checkout).recoverReservations();
        assertEquals(ReservationState.RELEASED, read(attempt).getReservationState());
        verify(seats, times(2)).releaseBookingSeats(attempt.getBookingId(), List.of(100L));
        verify(seats, never()).reserveBookingSeats(any(), any());
        verifyNoInteractions(payments);
    }

    @Test
    void cancellationWhileReservationIsInFlightCommitsReleaseIntentAndPreventsPayment() throws Exception {
        var attempt = seed();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(seats).reserveBookingSeats(any(), any());
        try (var workers = Executors.newSingleThreadExecutor()) {
            try {
                var owner = workers.submit(() -> checkout.createBooking(request, 1L, attempt.getIdempotencyKey()));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var booking = bookings.findById(attempt.getBookingId()).orElseThrow();
                booking.setStatus(BookingStatus.CANCELLED);
                bookings.saveAndFlush(booking);
                release.countDown();
                var failure = assertThrows(ExecutionException.class, () -> owner.get(5, TimeUnit.SECONDS));
                assertEquals(409, assertInstanceOf(ResponseStatusException.class, failure.getCause()).getStatusCode().value());
            } finally {
                release.countDown();
            }
        }
        assertEquals(ReservationState.RELEASED, read(attempt).getReservationState());
        verify(seats).releaseBookingSeats(attempt.getBookingId(), List.of(100L));
        verifyNoInteractions(payments);
    }

    @Test
    void staleReservationOwnerCannotOverwriteReplacementLeaseOrStartPayment() throws Exception {
        var attempt = seed();
        doAnswer(call -> {
            var replacement = read(attempt);
            replacement.setReservationClaim("replacement-owner");
            replacement.setReservationClaimExpiresAt(Instant.now().plusSeconds(120));
            attempts.saveAndFlush(replacement);
            return null;
        }).when(seats).reserveBookingSeats(any(), any());

        assertThrows(ResponseStatusException.class, () -> checkout.createBooking(request, 1L, attempt.getIdempotencyKey()));
        assertEquals("replacement-owner", read(attempt).getReservationClaim());
        assertEquals(ReservationState.PENDING, read(attempt).getReservationState());
        verifyNoInteractions(payments);
        verify(seats, never()).releaseBookingSeats(any(), any());
    }

    @Test
    void completedCheckoutReplaysWhileCancelledReservationIsCleanedUpByRecovery() throws Exception {
        var attempt = seed();
        attempt.setReservationState(ReservationState.RESERVED);
        attempt.setCheckoutResponse(json.writeValueAsString(
                PaymentInitiateResponse.builder().paymentId(9L).success(true).build()));
        attempts.saveAndFlush(attempt);
        var booking = bookings.findById(attempt.getBookingId()).orElseThrow();
        booking.setStatus(BookingStatus.CANCELLED);
        bookings.saveAndFlush(booking);

        assertEquals(9L, checkout.createBooking(request, 1L, attempt.getIdempotencyKey()).getPaymentId());
        verifyNoInteractions(seats, payments);
        assertEquals(List.of(attempt.getId()), reservations.recoverableAttemptIds());
        new BookingReservationRecoveryJob(reservations, checkout).recoverReservations();
        assertEquals(ReservationState.RELEASED, read(attempt).getReservationState());
        verify(seats).releaseBookingSeats(attempt.getBookingId(), List.of(100L));
    }

    @Test
    void payloadCollisionCannotStartOrCancelPendingReservation() throws Exception {
        var attempt = seed();
        var changed = BookingRequest.builder().flightId(999L).build();
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> checkout.createBooking(changed, 1L, attempt.getIdempotencyKey())).getStatusCode().value());
        assertEquals(ReservationState.PENDING, read(attempt).getReservationState());
        verifyNoInteractions(seats, payments);
    }

    @Test
    void deletedBookingStillHasDurableSeatIdsForCleanup() throws Exception {
        var attempt = seed();
        bookings.deleteById(attempt.getBookingId());
        assertEquals(List.of(attempt.getId()), reservations.recoverableAttemptIds());
        new BookingReservationRecoveryJob(reservations, checkout).recoverReservations();
        assertEquals(ReservationState.RELEASED, read(attempt).getReservationState());
        verify(seats).releaseBookingSeats(attempt.getBookingId(), List.of(100L));
        verifyNoInteractions(payments);
    }

    static java.util.stream.Stream<Arguments> simulatedInterruptions() {
        return java.util.stream.Stream.of("seat-response-lost", "reservation-worker-crashed", "payment-response-lost")
                .flatMap(fault -> java.util.stream.IntStream.rangeClosed(1, 20)
                        .mapToObj(trial -> Arguments.of(fault, trial)));
    }

    /** Each invocation injects one failure and requires the real recovery job to finish checkout. */
    @ParameterizedTest(name = "{0} trial {1}")
    @MethodSource("simulatedInterruptions")
    void simulatedInterruptionRecoversDurableCheckout(String fault, int trial) throws Exception {
        var attempt = seed();
        var job = new BookingReservationRecoveryJob(reservations, checkout);
        if (fault.equals("reservation-worker-crashed")) {
            attempt.setReservationClaim("crashed-worker");
            attempt.setReservationClaimExpiresAt(Instant.now().plusSeconds(120));
            attempts.saveAndFlush(attempt);
            job.recoverReservations();
            verifyNoInteractions(seats, payments);
            attempt.setReservationClaimExpiresAt(Instant.now().minusSeconds(1));
            attempts.saveAndFlush(attempt);
        } else {
            if (fault.equals("seat-response-lost")) {
                doThrow(new IllegalStateException("Injected lost reservation response")).doNothing()
                        .when(seats).reserveBookingSeats(attempt.getBookingId(), List.of(100L));
            } else {
                when(payments.initiatePayment(any(), eq(1L)))
                        .thenThrow(new IllegalStateException("Injected lost payment response"))
                        .thenReturn(PaymentInitiateResponse.builder().paymentId(9L).success(true).build());
            }
            job.recoverReservations();
            assertNull(read(attempt).getCheckoutResponse());
            assertTrue(reservations.recoverableAttemptIds().isEmpty(), "Retry must respect backoff");
            var retry = read(attempt);
            retry.setReservationRetryAt(Instant.now().minusSeconds(1));
            attempts.saveAndFlush(retry);
        }
        job.recoverReservations();
        var recovered = read(attempt);
        assertEquals(ReservationState.RESERVED, recovered.getReservationState());
        assertNotNull(recovered.getCheckoutResponse());
        assertNull(recovered.getReservationClaim());
        assertNull(recovered.getCheckoutClaim());
        assertEquals(attempt.getBookingId(), recovered.getBookingId());
        assertEquals(1, bookings.count());
        assertEquals(1, attempts.count());
        assertTrue(reservations.recoverableAttemptIds().isEmpty());
        assertEquals(9L, checkout.createBooking(request, 1L, attempt.getIdempotencyKey()).getPaymentId());
        job.recoverReservations();
        verify(seats, times(fault.equals("seat-response-lost") ? 2 : 1))
                .reserveBookingSeats(attempt.getBookingId(), List.of(100L));
        verify(payments, times(fault.equals("payment-response-lost") ? 2 : 1))
                .initiatePayment(argThat(p -> p.getBookingId().equals(attempt.getBookingId())), eq(1L));
        verify(seats, never()).releaseBookingSeats(any(), any());
        System.out.printf("RECOVERY_TRIAL booking %s %d recovered%n", fault, trial);
    }

    private BookingAttempt read(BookingAttempt attempt) { return attempts.findById(attempt.getId()).orElseThrow(); }

    private BookingAttempt seed() throws Exception {
        var booking = bookings.saveAndFlush(Booking.builder().bookingReference(UUID.randomUUID().toString())
                .userId(1L).flightId(10L).flightInstanceId(20L).fareId(30L).airlineId(40L)
                .status(BookingStatus.PENDING).holdExpiresAt(Instant.now().plusSeconds(600))
                .seatInstanceIds(List.of(100L)).build());
        var attempt = new BookingAttempt();
        attempt.setUserId(1L);
        attempt.setIdempotencyKey(UUID.randomUUID().toString());
        attempt.setRequestHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))));
        attempt.setBookingId(booking.getId());
        attempt.setPaymentRequest(json.writeValueAsString(PaymentInitiateRequest.builder()
                .bookingId(booking.getId()).userId(1L).amount(100.0).build()));
        attempt.setReservationSeatIds("[100]");
        attempt.setReservationState(ReservationState.PENDING);
        return attempts.saveAndFlush(attempt);
    }
}
