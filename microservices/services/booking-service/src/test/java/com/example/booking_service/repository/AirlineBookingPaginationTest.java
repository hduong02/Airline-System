package com.example.booking_service.repository;

import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.enums.BookingStatus;
import com.example.enums.Gender;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(BookingStatisticsRepositoryTest.Config.class)
@Transactional
class AirlineBookingPaginationTest {
    @Autowired BookingRepository bookings;
    @Autowired EntityManager entityManager;

    @Test void pagesAreBoundedCountedAndScopedToOwnerAirline() {
        var first = insert(50L, "FIRST", 1L, BookingStatus.CONFIRMED);
        var second = insert(50L, "SECOND", 1L, BookingStatus.CONFIRMED);
        insert(99L, "OTHER-AIRLINE", 1L, BookingStatus.CONFIRMED);
        var third = insert(50L, "THIRD", 1L, BookingStatus.CONFIRMED);
        var page = bookings.findAirlinePage(50L, null, null, null,
                PageRequest.of(0, 2, Sort.by("id")));
        assertEquals(List.of(first.getId(), second.getId()), page.map(Booking::getId).getContent());
        assertEquals(3, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        var last = bookings.findAirlinePage(50L, null, null, null,
                PageRequest.of(1, 2, Sort.by("id")));
        assertEquals(List.of(third.getId()), last.map(Booking::getId).getContent());
        assertTrue(last.isLast());
        assertTrue(bookings.findAirlinePage(50L, null, null, null, PageRequest.of(5, 2)).isEmpty());
    }

    @Test void multipleMatchingPassengersDoNotDuplicateOrInflateCounts() {
        var booking = insert(50L, "PASSENGERS", 1L, BookingStatus.CONFIRMED);
        passenger(booking, "Needle");
        passenger(booking, "Needle");
        insert(50L, "UNRELATED", 1L, BookingStatus.CONFIRMED);
        entityManager.flush();
        var page = bookings.findAirlinePage(50L, "nEeDlE", null, null, PageRequest.of(0, 1));
        assertEquals(List.of(booking.getId()), page.map(Booking::getId).getContent());
        assertEquals(1, page.getTotalElements());
        // All passengers remain available for response mapping after filtering.
        entityManager.clear();
        assertEquals(2, bookings.findAirlinePage(50L, "needle", null, null,
                PageRequest.of(0, 1)).getContent().getFirst().getPassengers().size());
    }

    @Test void searchStatusAndFlightFiltersApplyBeforePaging() {
        var match = insert(50L, "NEEDLE-OK", 7L, BookingStatus.CONFIRMED);
        insert(50L, "NEEDLE-STATUS", 7L, BookingStatus.CANCELLED);
        insert(50L, "NEEDLE-FLIGHT", 8L, BookingStatus.CONFIRMED);
        insert(99L, "NEEDLE-OWNER", 7L, BookingStatus.CONFIRMED);
        var page = bookings.findAirlinePage(50L, "needle", BookingStatus.CONFIRMED, 7L,
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "bookingDate", "id")));
        assertEquals(List.of(match.getId()), page.map(Booking::getId).getContent());
        assertEquals(1, page.getTotalElements());
    }

    private Booking insert(Long airline, String reference, Long flightInstance, BookingStatus status) {
        return bookings.saveAndFlush(Booking.builder().bookingReference(reference)
                .userId(1L).airlineId(airline).flightId(1L).flightInstanceId(flightInstance)
                .fareId(1L).status(status).build());
    }

    private void passenger(Booking booking, String name) {
        entityManager.persist(Passenger.builder().firstName(name).lastName("Fixture")
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Gender.MALE).booking(booking).build());
    }
}
