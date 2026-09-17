package com.example.booking_service.repository;

import com.example.booking_service.model.Booking;
import com.example.enums.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(BookingStatisticsRepositoryTest.Config.class)
@Transactional
class TravelerBookingPaginationTest {
    @Autowired BookingRepository bookings;

    @Test void historyPagesAndCountsAreScopedToAuthenticatedTraveler() {
        var first = insert(7L, 10L, BookingStatus.CONFIRMED);
        var second = insert(7L, 11L, BookingStatus.PENDING);
        insert(8L, 10L, BookingStatus.CONFIRMED);
        var third = insert(7L, 12L, BookingStatus.CANCELLED);
        var page = bookings.findByUserId(7L, PageRequest.of(0, 2, Sort.by("id")));
        assertEquals(List.of(first.getId(), second.getId()), page.map(Booking::getId).getContent());
        assertEquals(3, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(List.of(third.getId()), bookings.findByUserId(7L,
                PageRequest.of(1, 2, Sort.by("id"))).map(Booking::getId).getContent());
        var empty = bookings.findByUserId(7L, PageRequest.of(9, 2));
        assertTrue(empty.isEmpty());
        assertEquals(3, empty.getTotalElements());
    }

    @Test void categoryFiltersRunBeforePagingAndNeverIncludeAnotherTravelersBookings() {
        var first = insert(7L, 10L, BookingStatus.CONFIRMED);
        insert(7L, 10L, BookingStatus.PENDING);
        var cancelled = insert(7L, 11L, BookingStatus.CANCELLED);
        insert(8L, 10L, BookingStatus.CANCELLED);
        insert(8L, 12L, BookingStatus.CONFIRMED);
        assertEquals(List.of(10L), bookings.findHistoryFlightIds(7L, BookingStatus.CANCELLED));
        var page = bookings.findHistoryByFlightIds(7L, BookingStatus.CANCELLED,
                List.of(10L, 12L), PageRequest.of(0, 1, Sort.by("id")));
        assertEquals(List.of(first.getId()), page.map(Booking::getId).getContent());
        assertEquals(2, page.getTotalElements());
        var cancellations = bookings.findByUserIdAndStatus(7L, BookingStatus.CANCELLED, PageRequest.of(0, 1));
        assertEquals(List.of(cancelled.getId()), cancellations.map(Booking::getId).getContent());
        assertEquals(1, cancellations.getTotalElements());
    }

    private Booking insert(Long user, Long instance, BookingStatus status) {
        return bookings.saveAndFlush(Booking.builder().bookingReference(java.util.UUID.randomUUID().toString())
                .userId(user).airlineId(50L).flightId(1L).flightInstanceId(instance).fareId(1L).status(status).build());
    }
}
