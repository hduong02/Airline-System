package com.example.booking_service.service;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.controller.AirlineTransactionsController;
import com.example.booking_service.repository.BookingRepository;
import com.example.enums.*;
import com.example.payload.dto.PaymentDto;
import com.example.payload.response.AirlineResponse;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AirlineTransactionsServiceTest {
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final AirlineClient airlines = mock(AirlineClient.class);
    private final PaymentClient payments = mock(PaymentClient.class);
    private final AirlineTransactionsService service = new AirlineTransactionsService(bookings, airlines, payments);

    private void owner() {
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(50L).ownerId(7L).build());
    }
    private void data() {
        owner();
        when(bookings.findIdsByAirlineId(50L)).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(payments.getPaymentsByBookingIds(List.of(1L, 2L, 3L, 4L))).thenReturn(Map.of(
                1L, payment(1, PaymentStatus.SUCCESS, PaymentGateway.STRIPE, 10.10),
                2L, payment(2, PaymentStatus.SUCCESS, PaymentGateway.RAZORPAY, 20.20),
                3L, payment(3, PaymentStatus.PROCESSING, PaymentGateway.STRIPE, 90),
                4L, payment(4, PaymentStatus.FAILED, PaymentGateway.STRIPE, 50),
                999L, payment(999, PaymentStatus.SUCCESS, PaymentGateway.STRIPE, 9999)));
    }

    @Test void paginatesOnlyOwnedPaymentsAndSummarizesAllMatchingPages() {
        data();
        var result = service.forOwner(7L, 1, 1, null, null, null, "amount", "ASC");
        assertEquals(4, result.totalElements());
        assertEquals(4, result.totalPages());
        assertEquals(1, result.number());
        assertEquals(2L, result.content().getFirst().getBookingId());
        assertEquals(new BigDecimal("30.3"), result.summary().totalRevenue());
        assertEquals(2, result.summary().successfulPayments());
        assertEquals(1, result.summary().pendingPayments());
        assertEquals(1, result.summary().failedPayments());
        assertEquals(new BigDecimal("50.0"), result.summary().successRate());
        verify(bookings).findIdsByAirlineId(50L);
    }

    @Test void filtersAcrossAllPagesAndComputesSummaryForMatchingResults() {
        data();
        var result = service.forOwner(7L, 0, 1, " PAY-2 ", PaymentStatus.SUCCESS, PaymentGateway.RAZORPAY, "createdAt", "DESC");
        assertEquals(1, result.totalElements());
        assertEquals(2L, result.content().getFirst().getBookingId());
        assertEquals(new BigDecimal("20.2"), result.summary().totalRevenue());
        assertEquals(new BigDecimal("100.0"), result.summary().successRate());
        assertEquals(0, service.forOwner(7L, 0, 20, "no-match", null, null, "id", "ASC").totalElements());
    }

    @Test void emptyAirlineAndOutOfRangePageReturnEmptyContentWithoutErrors() {
        owner();
        var empty = service.forOwner(7L, 0, 20, null, null, null, "id", "ASC");
        assertTrue(empty.content().isEmpty());
        assertEquals(0, empty.totalPages());
        assertEquals(BigDecimal.ZERO, empty.summary().successRate());
        verifyNoInteractions(payments);
        data();
        var result = service.forOwner(7L, Integer.MAX_VALUE, 100, null, null, null, "id", "ASC");
        assertTrue(result.content().isEmpty());
        assertEquals(4, result.totalElements());
    }

    @Test void rejectsForeignOwnershipBeforeReadingBookingIdsOrPayments() {
        when(airlines.getAirlineByOwner(7L)).thenReturn(AirlineResponse.builder().id(50L).ownerId(8L).build());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.forOwner(7L, 0, 20, null, null, null, "id", "ASC")).getStatusCode().value());
        verifyNoInteractions(bookings, payments);
    }

    @Test void validatesPaginationAndSortBeforeRemoteCalls() {
        for (int size : new int[]{0, 101}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.forOwner(7L, 0, size, null, null, null, "id", "ASC")).getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> service.forOwner(7L, -1, 20, null, null, null, "id", "ASC"));
        assertThrows(ResponseStatusException.class, () -> service.forOwner(7L, 0, 20, null, null, null, "userId", "ASC"));
        assertThrows(ResponseStatusException.class, () -> service.forOwner(7L, 0, 20, null, null, null, "id", "bad"));
        assertThrows(ResponseStatusException.class, () -> service.forOwner(null, 0, 20, null, null, null, "id", "ASC"));
        verifyNoInteractions(airlines, bookings, payments);
    }

    @Test void mismatchedBookingFailsInsteadOfExposingForeignTransaction() {
        owner();
        when(bookings.findIdsByAirlineId(50L)).thenReturn(List.of(1L));
        when(payments.getPaymentsByBookingIds(List.of(1L))).thenReturn(Map.of(
                1L, payment(999, PaymentStatus.SUCCESS, PaymentGateway.STRIPE, 100)));
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> service.forOwner(7L, 0, 20, null, null, null, "id", "ASC")).getStatusCode().value());
    }

    @Test void dependencyFailureDoesNotBecomeAnEmptySuccessfulResponse() {
        owner();
        when(bookings.findIdsByAirlineId(50L)).thenReturn(List.of(1L));
        when(payments.getPaymentsByBookingIds(List.of(1L))).thenThrow(mock(FeignException.class));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.forOwner(7L, 0, 20, null, null, null, "id", "ASC")).getStatusCode().value());
    }

    @Test void batchesOwnedBookingsAndSkipsBookingsWithoutPayments() {
        owner();
        var ids = java.util.stream.LongStream.rangeClosed(1, 401).boxed().toList();
        when(bookings.findIdsByAirlineId(50L)).thenReturn(ids);
        when(payments.getPaymentsByBookingIds(anyList())).thenReturn(Map.of());
        assertEquals(0, service.forOwner(7L, 0, 20, null, null, null, "id", "ASC").totalElements());
        verify(payments).getPaymentsByBookingIds(ids.subList(0, 200));
        verify(payments).getPaymentsByBookingIds(ids.subList(200, 400));
        verify(payments).getPaymentsByBookingIds(ids.subList(400, 401));
    }

    @Test void endpointBindsFiltersAndSerializesSummaryAndPage() throws Exception {
        data();
        var mvc = MockMvcBuilders.standaloneSetup(new AirlineTransactionsController(service)).build();
        mvc.perform(get("/api/bookings/transactions/airline").header("X-User-Id", "7")
                        .param("status", "SUCCESS").param("gateway", "RAZORPAY").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(2))
                .andExpect(jsonPath("$.content[0].gateway").value("RAZORPAY"))
                .andExpect(jsonPath("$.summary.totalRevenue").value(20.2));
        mvc.perform(get("/api/bookings/transactions/airline")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings/transactions/airline").header("X-User-Id", "7")
                .param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings/transactions/airline").header("X-User-Id", "7")
                .param("size", "101")).andExpect(status().isBadRequest());
    }

    private PaymentDto payment(long bookingId, PaymentStatus status, PaymentGateway gateway, double amount) {
        var payment = new PaymentDto();
        payment.setId(bookingId + 100);
        payment.setBookingId(bookingId);
        payment.setUserId(900L); // Traveler identity differs from the owner.
        payment.setStatus(status);
        payment.setGateway(gateway);
        payment.setAmount(amount);
        payment.setTransactionId("TX-" + bookingId);
        payment.setGatewayPaymentId("pay-" + bookingId);
        payment.setCreatedAt(LocalDateTime.of(2026, 10, 16, 10, (int) (bookingId % 60)));
        return payment;
    }
}
